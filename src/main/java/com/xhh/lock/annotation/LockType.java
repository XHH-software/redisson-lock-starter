package com.xhh.lock.annotation;

/**
 * 锁类型。
 */
public enum LockType {

    /** 可重入锁（默认），同一线程可重复加锁 */
    REENTRANT,

    /** 公平锁，按请求顺序获取，吞吐略低于非公平锁 */
    FAIR,

    /** 读锁，读读不互斥，读写互斥 */
    READ,

    /** 写锁，与读锁、写锁均互斥 */
    WRITE
}
