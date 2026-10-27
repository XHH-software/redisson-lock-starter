package com.xhh.lock.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 分布式锁注解。
 *
 * <p>用法：
 * <pre>{@code
 * @Lock(key = "#orderId")
 * public void createOrder(Long orderId) { ... }
 *
 * @Lock(key = "'order:' + #order.id", type = LockType.FAIR, waitTime = 500)
 * public void updateOrder(Order order) { ... }
 * }</pre>
 *
 * <p>注意：Spring AOP 基于代理，同类内部调用（this.xxx()）不会生效。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Lock {

    /**
     * 锁的 key，支持 SpEL。
     * <p>为空时退化为「类名#方法名」，此时是方法级互斥，请确认符合业务语义。
     */
    String key() default "";

    /**
     * 锁类型。
     */
    LockType type() default LockType.REENTRANT;

    /**
     * 获取锁的最长等待时间（毫秒）。
     * <p>-1 表示沿用全局配置 {@code xhh.lock.wait-time}；0 表示不等待，立即返回失败。
     */
    long waitTime() default -1;

    /**
     * 锁的持有时间（毫秒）。
     * <p>-1 表示沿用全局配置 {@code xhh.lock.lease-time}，全局默认也是 -1，
     * 即启用 Redisson 看门狗自动续期（推荐）。指定具体值则看门狗失效，
     * 业务执行超过该时长后锁会被强制释放。
     */
    long leaseTime() default -1;
}
