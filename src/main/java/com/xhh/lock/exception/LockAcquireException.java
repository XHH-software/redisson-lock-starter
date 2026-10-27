package com.xhh.lock.exception;

/**
 * 获取锁失败时抛出。
 */
public class LockAcquireException extends RuntimeException {

    private final String lockKey;

    public LockAcquireException(String lockKey) {
        super("获取分布式锁失败: " + lockKey);
        this.lockKey = lockKey;
    }

    public String getLockKey() {
        return lockKey;
    }
}
