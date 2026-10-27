package com.xhh.lock.core;

import com.xhh.lock.annotation.Lock;
import com.xhh.lock.annotation.LockType;
import com.xhh.lock.config.LockProperties;
import com.xhh.lock.exception.LockAcquireException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.Ordered;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * {@link Lock} 的切面实现。
 *
 * <p><b>为什么 order 是 {@link Ordered#LOWEST_PRECEDENCE} - 10？</b>
 * Spring 的 {@code @Transactional} 默认 order 是 {@code LOWEST_PRECEDENCE}，
 * order 越小越靠外层。把锁切面设为比事务小 10，锁就会包在事务外面：
 * <pre>
 * 加锁 → 开启事务 → 业务 → 提交事务 → 释放锁
 * </pre>
 * 顺序反过来的话是「加锁 → 开启事务 → 业务 → 释放锁 → 提交事务」，
 * 释放锁到提交事务之间有一个极短窗口，其他线程拿到锁后读到的是旧数据。
 * 这在「先查后改」的库存、余额类场景里就是超卖 / 少扣。
 */
@Aspect
public class LockAspect implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(LockAspect.class);

    private final RedissonClient redissonClient;
    private final LockKeyParser keyParser;
    private final LockProperties properties;

    public LockAspect(RedissonClient redissonClient, LockKeyParser keyParser, LockProperties properties) {
        this.redissonClient = redissonClient;
        this.keyParser = keyParser;
        this.properties = properties;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10;
    }

    @Around("@annotation(lock)")
    public Object around(ProceedingJoinPoint pjp, Lock lock) throws Throwable {
        Method method = resolveMethod(pjp);
        String key = keyParser.parse(lock.key(), method, pjp.getArgs(), pjp.getTarget());

        long waitTime = lock.waitTime() >= 0 ? lock.waitTime() : properties.getWaitTime();
        long leaseTime = lock.leaseTime() >= 0 ? lock.leaseTime() : properties.getLeaseTime();

        RLock rLock = resolveLock(lock.type(), key);
        if (!tryAcquire(rLock, key, waitTime, leaseTime)) {
            if (properties.isLogOnFail()) {
                log.warn("获取分布式锁失败, key={}, waitTime={}ms, method={}#{}",
                        key, waitTime, method.getDeclaringClass().getSimpleName(), method.getName());
            }
            throw new LockAcquireException(key);
        }

        boolean success = false;
        try {
            Object result = pjp.proceed();
            success = true;
            return result;
        } finally {
            if (!success) {
                log.debug("业务方法执行异常, 即将释放锁, key={}", key);
            }
            release(key, rLock);
        }
    }

    private boolean tryAcquire(RLock rLock, String key, long waitTime, long leaseTime) {
        long actualWait = Math.max(waitTime, 0L);
        try {
            // leaseTime = -1 时 Redisson 会启用看门狗（默认 30s 续期一次），无需业务关心执行时长
            return rLock.tryLock(actualWait, leaseTime, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待分布式锁时被中断, key={}", key);
            return false;
        }
    }

    private void release(String key, RLock rLock) {
        LockReleaseSupport.releaseAfterTransaction(() -> {
            try {
                if (rLock.isHeldByCurrentThread()) {
                    rLock.unlock();
                    return;
                }
                // 走到这里通常是 leaseTime 设置过短、业务执行超时，锁已被 Redisson 自动释放。
                // 此时直接 unlock 会抛 IllegalMonitorStateException，提前判断掉，避免污染业务异常栈。
                log.warn("锁 [{}] 已不归当前线程持有（可能是 leaseTime 到期自动释放），跳过 unlock", key);
            } catch (Exception e) {
                log.error("释放分布式锁失败, key={}", key, e);
            }
        });
    }

    private RLock resolveLock(LockType type, String key) {
        if (type == null) {
            return redissonClient.getLock(key);
        }
        switch (type) {
            case FAIR:
                return redissonClient.getFairLock(key);
            case READ:
                return redissonClient.getReadWriteLock(key).readLock();
            case WRITE:
                return redissonClient.getReadWriteLock(key).writeLock();
            case REENTRANT:
            default:
                return redissonClient.getLock(key);
        }
    }

    /**
     * 取出真正声明注解的方法。
     * JDK 动态代理下 {@code pjp.getSignature()} 拿到的是接口方法，
     * 参数名 / 注解可能都在实现类上，所以要通过目标类再解析一次。
     */
    private Method resolveMethod(ProceedingJoinPoint pjp) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        Object target = pjp.getTarget();
        if (target == null) {
            return method;
        }
        return AopUtils.getMostSpecificMethod(method, AopUtils.getTargetClass(target));
    }
}
