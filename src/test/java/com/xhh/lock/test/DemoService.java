package com.xhh.lock.test;

import com.xhh.lock.annotation.Lock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 集成测试用的业务对象：覆盖加锁、等待超时、事务提交/回滚、重入几种场景。
 */
@Service
public class DemoService {

    private final InnerService innerService;

    public DemoService(InnerService innerService) {
        this.innerService = innerService;
    }

    @Lock(key = "'demo:' + #id", waitTime = 0)
    public String hold(long id, long millis, CountDownLatch entered) {
        // 能执行到方法体，说明锁已经拿到了，通知主线程来抢同一把锁
        entered.countDown();
        sleep(millis);
        return "hold-" + id;
    }

    @Lock(key = "'demo:' + #id", waitTime = 300)
    public String holdWithWait(long id) {
        return "wait-" + id;
    }

    @Lock(key = "'tx:' + #id", waitTime = 0)
    @Transactional(rollbackFor = Exception.class)
    public void transactionalFail(long id) {
        throw new IllegalStateException("模拟业务异常");
    }

    @Lock(key = "'tx:' + #id", waitTime = 0)
    @Transactional(rollbackFor = Exception.class)
    public String transactionalSuccess(long id) {
        return "tx-" + id;
    }

    /**
     * 同线程重入：outer 拿到锁后，通过代理调用 inner，同一把锁应该能再次获取成功。
     * 注意必须是跨 Bean 调用，this.inner() 不走代理，注解不会生效。
     */
    @Lock(key = "'nested:' + #id", waitTime = 0)
    public String outer(long id) {
        return innerService.inner(id);
    }

    private void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
