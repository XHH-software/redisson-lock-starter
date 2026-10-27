package com.xhh.lock.test;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * 集成测试用的启动类。
 *
 * <p>这里刻意放在 {@code com.xhh.lock.test} 包下，让组件扫描只覆盖测试辅助 Bean，
 * 组件本身仍然通过 {@code META-INF/spring.factories} 自动装配 —— 顺便验证了
 * 「引入依赖就能用，不需要 @EnableXxx」这件事。
 */
@SpringBootApplication
public class TestApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestApplication.class, args);
    }

    /**
     * 测试里没有数据库，但事务用例需要一个生效的 TransactionManager。
     * 用一个不落地的空实现，只要能让 {@code TransactionSynchronizationManager}
     * 进入「真实事务活跃」状态就够了。
     */
    @Bean
    public PlatformTransactionManager transactionManager() {
        return new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        };
    }
}
