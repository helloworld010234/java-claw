package com.tinyclaw.application.approval;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 基于内存的细粒度锁注册表，用于保证同一 approval 的 resume 操作串行执行。
 *
 * <p>不同 approvalId 之间互不阻塞。锁对象持久化在 Map 中（不 remove），
 * 避免并发 remove 带来的 race condition。单进程内足够；分布式场景不在本轮范围。</p>
 */
public class ApprovalResumeLockRegistry {

    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    /**
     * 在指定 approvalId 的独占锁内执行动作。
     *
     * @param approvalId the approval ID to lock on
     * @param action     the action to execute under the lock
     * @param <T>        the result type
     * @return the result of the action
     */
    public <T> T withLock(String approvalId, Supplier<T> action) {
        Object lock = locks.computeIfAbsent(approvalId, k -> new Object());
        synchronized (lock) {
            return action.get();
        }
    }
}
