package com.xhh.lock.core;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 锁释放时机的兜底策略。
 *
 * <p>正常路径下，切面顺序已经把锁包在事务外层（详见 {@link LockAspect} 的 order 说明），
 * 事务提交后锁才释放，不存在「锁已释放、事务未提交」的窗口。
 *
 * <p>但如果业务方给 {@code @Transactional} 显式指定了更高的优先级（order 更小），
 * 事务切面就会跑到锁切面外面，此时锁会在事务提交前被释放。这里通过
 * {@link TransactionSynchronization} 把 unlock 推迟到 {@code afterCompletion}，
 * 保证无论谁在外面，锁都不会早于事务结束而释放。
 */
public final class LockReleaseSupport {

    private LockReleaseSupport() {
    }

    /**
     * 如果当前线程正处于一个真实事务中，则把释放动作推迟到事务完成后执行；
     * 否则立即执行。
     *
     * <p>注意：这里必须同时判断 {@code isActualTransactionActive()}。
     * 只用 {@code isSynchronizationActive()} 时，在 {@code PROPAGATION_SUPPORTS}
     * 这类没有真实事务的场景下会注册一个永远不会被回调的同步器，导致锁泄漏。
     */
    public static void releaseAfterTransaction(Runnable unlockAction) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    unlockAction.run();
                }
            });
            return;
        }
        unlockAction.run();
    }
}
