package com.flowops.scheduler.state;

import com.flowops.common.enums.StepStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 步骤实例状态机 —— 声明式转移表（docs/06 §2.2，**唯一权威**，逐行对照）。
 *
 * <p>与 {@link TaskStateTransitions} 同一实现理念：事件枚举是唯一真源，可达性表由事件推导。
 * 步骤状态机比任务多两类关键转移：</p>
 * <ul>
 *   <li><b>回退边</b>：SCHEDULING 下发失败 → WAITING_RESOURCE（释放节点占用，重新参与调度）；
 *       RETRYING 间隔到达 → WAITING_RESOURCE。回退必须保留原 enqueue_seq 防饥饿（docs/06 §4.5）。</li>
 *   <li><b>重置边</b>：重跑失败步骤时 FAILED/TIMEOUT/STOPPED → NOT_STARTED（docs/06 §9.3 ③，
 *       清空执行痕迹、保留 retry_count 累加、output_vars 清空重新产出）。</li>
 * </ul>
 *
 * <p><b>SKIPPED 一期不产生</b>（V1.1 预留，docs/05 §5）——表里刻意没有进入 SKIPPED 的事件；
 * 它出现之日，是 PRD 定义"忽略失败继续"之时（docs/06 §9.2 明确一期不做）。</p>
 */
public final class StepStateTransitions {

    private StepStateTransitions() {
    }

    /** 重跑失败步骤允许重置的来源态（docs/06 §9.3 ③：reset → NOT_STARTED）。 */
    private static final List<StepStatus> RERUN_RESET_SOURCES =
            List.of(StepStatus.FAILED, StepStatus.TIMEOUT, StepStatus.STOPPED);

    private static final Map<StepStatus, Set<StepStatus>> REACHABLE = buildReachable();

    private static Map<StepStatus, Set<StepStatus>> buildReachable() {
        Map<StepStatus, Set<StepStatus>> table = new EnumMap<>(StepStatus.class);
        for (StepEvent event : StepEvent.values()) {
            for (StepStatus from : event.sources()) {
                table.computeIfAbsent(from, k -> EnumSet.noneOf(StepStatus.class)).add(event.target);
            }
        }
        return Map.copyOf(table);
    }

    public static boolean canTransition(StepStatus from, StepStatus to) {
        return REACHABLE.getOrDefault(from, Set.of()).contains(to);
    }

    /** 是否终态（docs/06 §2.2：SUCCESS/FAILED/SKIPPED/STOPPED/TIMEOUT）。 */
    public static boolean isFinal(StepStatus status) {
        return status.isFinal();
    }

    /**
     * 步骤事件（docs/06 §2.2 转移表逐行）。
     * 每条注释注明判定依据 —— 出问题先对照文档，再改表。
     */
    public enum StepEvent {
        /** DAG 推进：该步骤入度 > 0，等待上游 */
        DEP_WAIT(StepStatus.NOT_STARTED, StepStatus.WAITING_DEPENDENCY),
        /** DAG 推进：入度 0，直接可调度 */
        DEP_READY(StepStatus.NOT_STARTED, StepStatus.WAITING_RESOURCE),
        /** 全部上游 SUCCESS */
        UPSTREAM_DONE(StepStatus.WAITING_DEPENDENCY, StepStatus.WAITING_RESOURCE),
        /** 上游有 FAILED 且策略 = TERMINATE：不再执行 */
        UPSTREAM_FAIL(StepStatus.WAITING_DEPENDENCY, StepStatus.STOPPED),
        /** 节点 + 互斥锁均获得（docs/06 §5 / §6：两者都通过才转移） */
        RESOURCE_OK(StepStatus.WAITING_RESOURCE, StepStatus.SCHEDULING),
        /** 等待超时（queue.wait_timeout_seconds，G9） */
        WAIT_TIMEOUT(StepStatus.WAITING_RESOURCE, StepStatus.FAILED),
        /** 命令下发成功 —— 必须 CAS 更新（docs/06 §4.3，防重复下发） */
        DISPATCH_OK(StepStatus.SCHEDULING, StepStatus.RUNNING),
        /** 下发失败（SSH 连不上等）：释放节点占用，回退重试 */
        DISPATCH_FAIL(StepStatus.SCHEDULING, StepStatus.WAITING_RESOURCE),
        /** 退出码 ∈ operator_version.success_codes */
        EXIT_OK(StepStatus.RUNNING, StepStatus.SUCCESS),
        /** 退出码 ∉ success_codes 且还有重试余量（写 task_step_retry，next_retry_at 落库） */
        EXIT_RETRY(StepStatus.RUNNING, StepStatus.RETRYING),
        /** 退出码 ∉ success_codes 且重试耗尽 */
        EXIT_FAIL(StepStatus.RUNNING, StepStatus.FAILED),
        /** 超过 timeout_seconds（超时扫描，docs/06 §8.3） */
        STEP_TIMEOUT(StepStatus.RUNNING, StepStatus.TIMEOUT),
        /** 节点失联超过 5 分钟恢复窗口（G4；❗ 释放互斥锁走 §6.3.1 唤醒路径） */
        NODE_LOST(StepStatus.RUNNING, StepStatus.FAILED),
        /** 用户停止指令（任务级停止的传导） */
        USER_STOP(StepStatus.RUNNING, StepStatus.STOPPED),
        /** 重试间隔到达，重新参与调度（沿用原 enqueue_seq，§4.5 防饥饿） */
        RETRY_READY(StepStatus.RETRYING, StepStatus.WAITING_RESOURCE),
        /** §9.3 ③：重跑失败步骤时重置执行痕迹 */
        RERUN_RESET(null, StepStatus.NOT_STARTED);

        private final StepStatus from;
        private final StepStatus target;

        StepEvent(StepStatus from, StepStatus target) {
            this.from = from;
            this.target = target;
        }

        Set<StepStatus> sources() {
            return from != null ? Set.of(from) : Set.copyOf(RERUN_RESET_SOURCES);
        }

        /** 事件的目标态（DagAdvancer 等调用方直接读取，避免第二份"事件→目标"映射）。 */
        public StepStatus target() {
            return target;
        }
    }
}
