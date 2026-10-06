package com.flowops.scheduler.lifecycle;

import com.flowops.domain.mapper.task.TaskStepMapper;
import com.flowops.scheduler.guard.MutexLockManager;
import com.flowops.scheduler.match.ReservedLedger;
import com.flowops.scheduler.queue.ReadyQueueManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 执行资源清算器（账本扣回 + 互斥锁释放 + 等待者唤醒重排队）。
 *
 * <p><b>为何独立成组件</b>：回执收敛（drainCompletions）、超时扫描、卡住扫描三条路径
 * 都要做同一套清算动作 —— DRY（docs/10）：同一知识只写一处，漏一处就是幽灵锁或幽灵占用。
 * 幂等：账本 release 与锁 forceUnlock 均可重复调用。</p>
 */
@Component
@RequiredArgsConstructor
public class ResourceReleaser {

    private final ReservedLedger ledger;
    private final MutexLockManager mutexes;
    private final TaskStepMapper taskStepMapper;
    private final ReadyQueueManager readyQueue;

    /**
     * @param queueId      步骤所在队列（等待者唤醒重排队目标）
     * @param taskStepRowId 步骤实例行
     * @param nodeId        执行节点（账本扣回；未知传 0 —— 账本按 (nodeId,rowId) 键控，0 号键为无害空操作）
     * @param dispatchToken 占位令牌（互斥持有点标记的动 lock 条件）
     * @param mutexGroup    互斥组（空 = 无锁）
     * @param clearFailTicks 终态时清队头阻塞计数（回退路径保留计数）
     */
    public void release(long queueId, long taskStepRowId, long nodeId,
                        String dispatchToken, String mutexGroup, boolean clearFailTicks) {
        ledger.release(nodeId, taskStepRowId);
        if (mutexGroup != null && !mutexGroup.isBlank()) {
            taskStepMapper.setMutexHolder(taskStepRowId, dispatchToken, mutexGroup, false);
            // §6.3.1：等待者按原 (seq, priority, queue) 重排回就绪队列（1 tick 延迟的等价实现）
            mutexes.release(mutexGroup, taskStepRowId).ifPresent(waiter ->
                    readyQueue.enqueue(waiter.queueId(), waiter.taskStepId(),
                            waiter.priority(), waiter.enqueueSeq()));
        }
        if (clearFailTicks) {
            readyQueue.clearFailure(queueId, taskStepRowId);
        }
    }
}
