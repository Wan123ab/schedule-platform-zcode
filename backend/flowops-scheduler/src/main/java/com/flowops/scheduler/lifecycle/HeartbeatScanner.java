package com.flowops.scheduler.lifecycle;

import com.flowops.common.enums.StepStatus;
import com.flowops.domain.dto.query.UnreachableStepRow;
import com.flowops.domain.mapper.task.LifecycleQueryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 心跳与在线判定（G4/G5，docs/06 §8.1 —— M1 遗留 O-1 的落地）。
 *
 * <p><b>三段阈值（每段一条集合 SQL，逐节点判定交给 DB）</b>：</p>
 * <ol>
 *   <li>15~45s：miss_count+1，状态保持 ONLINE（G5：单次缺失不告警不降级，前端标"心跳异常"）；</li>
 *   <li>&gt;45s：ONLINE → OFFLINE（触发"节点离线"告警——告警引擎 M4 接入）；</li>
 *   <li>&gt;5min 恢复窗口：该节点上 RUNNING 步骤判 FAILED（NODE_UNREACHABLE）+ 资源清算
 *       —— 恢复窗口内恢复则什么都不做（步骤继续，§8.1 ④）。</li>
 * </ol>
 *
 * <p><b>为什么判失败要释放锁/账本</b>：失联节点上的步骤永远不会自己释放互斥锁与预留账本
 * （docs/06 §8.1 ⑤ 易漏项），漏清算 = 幽灵锁 + 幽灵占用。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatScanner {

    private static final long RECOVERY_WINDOW_SECONDS = 300;   // 5 分钟恢复窗口（G4）

    private final LifecycleQueryMapper lifecycleQuery;
    private final ResourceReleaser releaser;

    /** ①+②：miss 计数与离线标记（各一条集合 UPDATE，幂等）。 */
    @Scheduled(fixedDelay = 5000)
    public void scanHeartbeats() {
        int misses = lifecycleQuery.incrementMissCount();
        int offline = lifecycleQuery.markOfflineNodes();
        if (offline > 0) {
            log.warn("节点离线 {} 个（45s 无心跳，G4）", offline);   // 告警事件随 M4 告警引擎接入
        } else if (misses > 0) {
            log.debug("心跳异常节点 {} 个（15~45s，G5：状态不变不告警）", misses);
        }
    }

    /** ③：恢复窗口到期的失联节点步骤 → FAILED + 清算。 */
    @Scheduled(fixedDelay = 5000)
    public void scanRecoveryWindowExpired() {
        List<UnreachableStepRow> steps =
                lifecycleQuery.findUnreachableSteps(RECOVERY_WINDOW_SECONDS, 100);
        for (var row : steps) {
            if (taskStepFailed(row)) {
                log.warn("节点失联步骤判失败 step={} node={}（§8.1 ⑤，恢复窗口 {}s 已过）",
                        row.getStepInstanceId(), row.getNodeId(), RECOVERY_WINDOW_SECONDS);
            }
            // 无论 CAS 是否成功都清算：漏清算（幽灵锁/幽灵占用）才是事故，与 LifecycleScanner 同一口径
            releaser.release(row.getQueueId(), row.getId(),
                    row.getNodeId() == null ? 0L : row.getNodeId(),
                    row.getDispatchToken(), row.getMutexGroup(), true);
        }
    }

    private boolean taskStepFailed(UnreachableStepRow row) {
        return lifecycleQuery.casStepFailed(row.getId(), StepStatus.RUNNING.name(),
                StepStatus.FAILED.name(), "NODE_UNREACHABLE") > 0;
    }
}
