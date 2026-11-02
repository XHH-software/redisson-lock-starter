package com.xhh.lock.test;

import com.xhh.lock.exception.LockAcquireException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

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
 * <p>Redis 来源有两种，按优先级：
 * <ol>
 *   <li>环境变量 {@code TEST_REDIS_HOST} 指向的现成实例（CI 用 GitHub Actions 的
 *       service container 提供）；</li>
 *   <li>本机 Docker，用 Testcontainers 拉起 {@code redis:7.4-alpine}。</li>
 * </ol>
 *
 * <p>两条都不满足时整个类跳过，保证没有 Docker 的机器上 {@code mvn test} 依然是绿的。
 *
 * <p>为什么 CI 不再依赖 Testcontainers 探测 Docker：{@code @Testcontainers(disabledWithoutDocker = true)}
 * 的语义是「探测不到 Docker 就跳过」，而「探测不到」和「真的没有」是两件事——runner 镜像里
 * Docker 明明在，只是 Testcontainers 的客户端握手失败，测试也会被静默跳过，构建照样绿。
 * service container 由 Actions 自己拉起并做健康检查，不经过这层探测，结果更可信。
 */
@EnabledIf("redisAvailable")
@SpringBootTest(classes = TestApplication.class)
class LockAspectIntegrationTest {

    private static final String EXTERNAL_HOST = System.getenv("TEST_REDIS_HOST");
    private static final String EXTERNAL_PORT =
            System.getenv().getOrDefault("TEST_REDIS_PORT", "6379");

    private static GenericContainer<?> redis;

    static boolean redisAvailable() {
        if (EXTERNAL_HOST != null && !EXTERNAL_HOST.isEmpty()) {
            return true;
        }
        return DockerClientFactory.instance().isDockerAvailable();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        if (EXTERNAL_HOST != null && !EXTERNAL_HOST.isEmpty()) {
            registry.add("spring.redis.host", () -> EXTERNAL_HOST);
            registry.add("spring.redis.port", () -> EXTERNAL_PORT);
            return;
        }
        redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
        redis.start();
        registry.add("spring.redis.host", redis::getHost);
        registry.add("spring.redis.port", () -> redis.getMappedPort(6379));
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
