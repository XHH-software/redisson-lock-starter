package com.xhh.lock.config;

import com.xhh.lock.core.LockAspect;
import com.xhh.lock.core.LockKeyParser;
import org.aspectj.lang.annotation.Aspect;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * 自动装配入口。
 *
 * <p>引入依赖 + 配置 {@code spring.redis.*} 即可用，无需任何 {@code @EnableXxx}。
 * 想关掉时设置 {@code xhh.lock.enabled=false}。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({RedissonClient.class, Aspect.class})
@ConditionalOnProperty(prefix = "xhh.lock", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(LockProperties.class)
@EnableAspectJAutoProxy(proxyTargetClass = true)
public class LockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LockKeyParser lockKeyParser(LockProperties properties) {
        return new LockKeyParser(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public LockAspect lockAspect(RedissonClient redissonClient,
                                 LockKeyParser lockKeyParser,
                                 LockProperties properties) {
        return new LockAspect(redissonClient, lockKeyParser, properties);
    }
}
