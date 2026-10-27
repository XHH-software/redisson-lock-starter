package com.xhh.lock.test;

import com.xhh.lock.exception.LockAcquireException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Redis 下的集成测试。
 *
 * <p>{@code disabledWithoutDocker = true}：本机没装 Docker 时自动跳过，
 * 不会让 {@code mvn test} 变红；CI 里装了 Docker 就会真实执行。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = TestApplication.class)
class LockAspectIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.redis.host", REDIS::getHost);
        registry.add("spring.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private DemoService demoService;

    @Autowired
    private RedissonClient redissonClient;

    @Test
    @DisplayName("互斥：锁被占用时，waitTime=0 的请求立即失败")
    void shouldRejectWhenLockHeld() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> holder = pool.submit(() -> demoService.hold(1L, 1200, entered));
            assertTrue(entered.await(5, TimeUnit.SECONDS), "持有锁的线程未启动");

            assertThrows(LockAcquireException.class,
                    () -> demoService.hold(1L, 0, new CountDownLatch(1)));

            assertEquals("hold-1", holder.get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        // 业务返回后锁应被释放
        assertFalse(redissonClient.getLock("lock:demo:1").isLocked());
    }

    @Test
    @DisplayName("等待超时：waitTime=300ms 时最多等 300ms 后失败")
    void shouldFailAfterWaitTimeout() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> holder = pool.submit(() -> demoService.hold(2L, 1500, entered));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            long start = System.currentTimeMillis();
            assertThrows(LockAcquireException.class, () -> demoService.holdWithWait(2L));
            long cost = System.currentTimeMillis() - start;

            assertTrue(cost >= 250, "应当等满 waitTime 再失败，实际耗时 " + cost + "ms");
            assertTrue(cost < 1400, "不应等到锁释放才返回，实际耗时 " + cost + "ms");

            holder.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("可重入：同线程跨 Bean 重复加同一把锁，解锁后完全释放")
    void shouldSupportReentrant() {
        assertEquals("inner-3", demoService.outer(3L));
        assertFalse(redissonClient.getLock("lock:nested:3").isLocked(),
                "重入计数未归零，锁没有真正释放");
    }

    @Test
    @DisplayName("事务回滚：业务抛异常后锁被释放，不会把后续请求永久卡死")
    void shouldReleaseLockAfterRollback() {
        assertThrows(IllegalStateException.class, () -> demoService.transactionalFail(4L));
        assertFalse(redissonClient.getLock("lock:tx:4").isLocked());
    }

    @Test
    @DisplayName("事务提交：锁在事务结束后释放")
    void shouldReleaseLockAfterCommit() {
        assertEquals("tx-5", demoService.transactionalSuccess(5L));
        assertFalse(redissonClient.getLock("lock:tx:5").isLocked());
    }

    @Test
    @DisplayName("锁 key 带全局前缀，避免和其他业务数据抢命名空间")
    void shouldUseConfiguredKeyPrefix() throws Exception {
        RLock lock = redissonClient.getLock("lock:demo:6");
        CountDownLatch entered = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> holder = pool.submit(() -> demoService.hold(6L, 800, entered));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(lock.isLocked());
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}
