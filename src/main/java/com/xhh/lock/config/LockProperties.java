package com.xhh.lock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 锁组件配置项。
 */
@ConfigurationProperties(prefix = "xhh.lock")
public class LockProperties {

    /** 是否启用该组件 */
    private boolean enabled = true;

    /** 锁 key 的统一前缀，便于在 Redis 中区分业务数据 */
    private String keyPrefix = "lock:";

    /** 默认等待时间（毫秒），注解上未指定时生效 */
    private long waitTime = 3000;

    /** 默认持有时间（毫秒），-1 表示启用看门狗 */
    private long leaseTime = -1;

    /** 获取锁失败时是否打印 warn 日志 */
    private boolean logOnFail = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public long getWaitTime() {
        return waitTime;
    }

    public void setWaitTime(long waitTime) {
        this.waitTime = waitTime;
    }

    public long getLeaseTime() {
        return leaseTime;
    }

    public void setLeaseTime(long leaseTime) {
        this.leaseTime = leaseTime;
    }

    public boolean isLogOnFail() {
        return logOnFail;
    }

    public void setLogOnFail(boolean logOnFail) {
        this.logOnFail = logOnFail;
    }
}
