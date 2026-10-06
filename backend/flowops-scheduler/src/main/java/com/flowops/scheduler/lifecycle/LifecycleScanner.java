package com.flowops.scheduler.lifecycle;

import com.flowops.common.enums.StepStatus;
import com.flowops.domain.mapper.task.LifecycleQueryMapper;
import com.flowops.domain.mapper.task.TaskStepMapper;
import com.flowops.scheduler.queue.ReadyQueueManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 生命周期扫描器（docs/06 §3.2 阶段⑧ / §4.6 / §8.3）。
 *
 * <p><b>心跳离线判定（§8.1）暂不在 M1</b>：SSH 直跑模型没有心跳源（Agent 为 E-02 二期项），
 * 心跳上报端点与 45s 离线判定随 M2 资产域（节点心跳展示）落地 —— 见 docs/09 M2 交付物。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LifecycleScanner {

    /** 回执丢失阈值（docs/06 §4.6：SCHEDULING 超 2 分钟）。 */
    public static final int STUCK_MINUTES = 2;

    private final LifecycleQueryMapper lifecycleQuery;
    private final TaskStepMapper taskStepMapper;
    private final ResourceReleaser releaser;
    private final ReadyQueueManager readyQueue;

    /**
     * SCHEDULING 卡住（§4.6）：回执丢失。M1 全部按"可重新下发"处理 ——
     * 回退 WAITING_RESOURCE 后重新入队（原 seq，claim 时已 ZREM），
     * 重新 claim 生成新 token；非幂等算子的 UNKNOWN 转人工随 M3 算子声明落地（届时此处分流）。
     */
    public int scanStuckDispatch(int limit) {
        int handled = 0;
        for (var row : lifecycleQuery.findStuckScheduling(STUCK_MINUTES, limit)) {
            // CAS 语义：rollbackClaim 的 WHERE status='SCHEDULING' AND token=#{token}
            // 保证只回退"确实还在 SCHEDULING 且 token 未变"的步骤
            if (taskStepMapper.rollbackClaim(row.getId(), row.getDispatchToken()) > 0) {
                readyQueue.enqueue(row.getQueueId(), row.getId(),
                        row.getPriority() == null ? 0 : row.getPriority(),
                        row.getEnqueueSeq() == null ? 0L : row.getEnqueueSeq());
                handled++;
                log.warn("回执丢失回退并重新入队 step={}（§4.6，M1 按可重下发处理）", row.getStepInstanceId());
            }
        }
        return handled;
    }

    /**
     * 步骤超时（§8.3）：RUNNING 超过 timeout_seconds → TIMEOUT（终态）→ 资源清算。
     * 远端进程终止靠 execute() 的 awaitExit 断连兜底（E-02：按 PID 终止为二期）。
     */
    public int scanTimeouts(int limit) {
        int handled = 0;
        for (var row : lifecycleQuery.findTimedOutSteps(limit)) {
            // 终态收敛 + 清算（账本扣回 / 互斥释放 / 等待者唤醒）
            if (taskStepMapper.casTransition(row.getId(), "RUNNING", StepStatus.TIMEOUT.name()) > 0) {
                handled++;
                log.warn("步骤超时 step={}（§8.3）", row.getStepInstanceId());
            }
            // 无论 CAS 是否成功都要清算：可能已被他方判 FAILED（如节点失联路径），
            // 账本/锁的清理由幂等 release 兜底，漏清算才是事故
            releaser.release(row.getQueueId(), row.getId(),
                    row.getNodeId() == null ? 0L : row.getNodeId(),
                    row.getDispatchToken(), row.getMutexGroup(), true);
        }
        return handled;
    }
}
