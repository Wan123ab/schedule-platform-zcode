package com.flowops.scheduler.state;

import com.flowops.common.enums.TaskStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 任务状态机 —— 声明式转移表（docs/06 §2.1，**唯一权威**，逐行对照）。
 *
 * <p><b>设计思路（docs/06 §2.3）</b>：不给每个状态写一个类（过度设计），把规则收敛成一组
 * 「事件 = (来源态, 目标态)」的枚举常量，再由事件列表**推导**出可达性表 —— 事件列表是唯一真源，
 * 不存在第二份需要同步的表（DRY，docs/10）。收益有三：
 * ① 状态机可被单测<b>穷举</b>（每个转移一条用例，docs/06 §16 第一行）；
 * ② PRD 改规则时增删一行枚举即可，不改代码结构；
 * ③ 表外的转移天然被拒绝 —— 非法状态流转不可能发生，这是审计可信度的根基。</p>
 *
 * <p><b>铁律</b>：终态（SUCCESS/FAILED/STOPPED/TIMEOUT/PARTIAL）不可回退。
 * 唯一的"回退"是重跑失败步骤（RERUN_FAILED_STEPS，docs/06 §9.3）—— 那是 server 发起的显式操作，
 * 任务历史仍完整可考；SUCCESS 不在重跑来源之列，重跑已停止任务走"整任务重跑"= 新建任务实例。</p>
 *
 * <p><b>STOPPING 中间态（M-08，docs/06 §15.1）</b>：用户点停止时 server 只写 STOPPING，
 * 调度器终止全部运行中步骤后才置 STOPPED —— 前端因此能正确展示"正在停止"。</p>
 */
public final class TaskStateTransitions {

    private TaskStateTransitions() {
    }

    /** 重跑失败步骤允许的来源态（docs/06 §9.3 ①：任务已终结才允许）。 */
    private static final List<TaskStatus> RERUN_SOURCES =
            List.of(TaskStatus.FAILED, TaskStatus.TIMEOUT, TaskStatus.PARTIAL);

    /** 可达性表：from → 合法目标态集合。由 TaskEvent 推导，勿手工维护。 */
    private static final Map<TaskStatus, Set<TaskStatus>> REACHABLE = buildReachable();

    private static Map<TaskStatus, Set<TaskStatus>> buildReachable() {
        Map<TaskStatus, Set<TaskStatus>> table = new EnumMap<>(TaskStatus.class);
        for (TaskEvent event : TaskEvent.values()) {
            for (TaskStatus from : event.sources()) {
                table.computeIfAbsent(from, k -> EnumSet.noneOf(TaskStatus.class)).add(event.target);
            }
        }
        return Map.copyOf(table);
    }

    /** 当前状态是否允许转移到目标状态。 */
    public static boolean canTransition(TaskStatus from, TaskStatus to) {
        return REACHABLE.getOrDefault(from, Set.of()).contains(to);
    }

    /** 按事件转移；非法转移抛出带诊断信息的异常（调用方应先用 canTransition 分流业务分支）。 */
    public static TaskStatus transition(TaskStatus from, TaskEvent event) {
        if (!event.accepts(from)) {
            throw new IllegalStateException(
                    "非法任务状态转移: " + from + " --" + event + "--> " + event.target
                            + "（查阅 docs/06 §2.1 转移表）");
        }
        return event.target;
    }

    /** 是否终态。判终态的地方统一走这里，避免散落的 == 比较漂移。 */
    public static boolean isFinal(TaskStatus status) {
        return switch (status) {
            case SUCCESS, FAILED, STOPPED, TIMEOUT, PARTIAL -> true;
            default -> false;
        };
    }

    /**
     * 任务事件：事件名即业务语言（"并发检查通过"、"用户请求停止"），
     * 每个事件自带来源态与目标态 —— 读这组枚举就是在读 docs/06 §2.1 的转移表。
     */
    public enum TaskEvent {
        /** 行 1：并发检查通过，入队 ZSet，开始 DAG 推进 */
        CONCURRENCY_PASSED(TaskStatus.PENDING, TaskStatus.SCHEDULING),
        /** 行 2：FORBID 策略下并发拒绝（触发"并发达限"提示级告警） */
        CONCURRENCY_FORBID(TaskStatus.PENDING, TaskStatus.FAILED),
        /** 行 3：首个步骤成功下发，设 start_time */
        FIRST_STEP_DISPATCHED(TaskStatus.SCHEDULING, TaskStatus.RUNNING),
        /** 行 4：队列等待超时（G9） */
        QUEUE_WAIT_TIMEOUT(TaskStatus.SCHEDULING, TaskStatus.FAILED),
        /** 行 5：所有步骤成功 */
        ALL_STEPS_SUCCESS(TaskStatus.RUNNING, TaskStatus.SUCCESS),
        /** 行 6/7：步骤失败且策略=TERMINATE，或 RETRY 耗尽 */
        STEPS_FAILED(TaskStatus.RUNNING, TaskStatus.FAILED),
        /** 行 8：分支 DAG 下部分成功 */
        PARTIAL_SUCCESS(TaskStatus.RUNNING, TaskStatus.PARTIAL),
        /** 行 11：任务超时，终止所有运行中步骤 */
        TASK_TIMEOUT(TaskStatus.RUNNING, TaskStatus.TIMEOUT),
        /** §15.1 ①：server 写入停止请求（用户点停止只到 STOPPING，不直接终态） */
        STOP_REQUESTED(TaskStatus.RUNNING, TaskStatus.STOPPING),
        /** §15.1 ④：全部步骤终结，调度器收敛为 STOPPED */
        ALL_STEPS_TERMINATED(TaskStatus.STOPPING, TaskStatus.STOPPED),
        /** §9.3：重跑失败步骤 —— 来源为三个非 SUCCESS 终态（见 RERUN_SOURCES） */
        RERUN_FAILED_STEPS(null, TaskStatus.RUNNING);

        private final TaskStatus from;
        private final TaskStatus target;

        TaskEvent(TaskStatus from, TaskStatus target) {
            this.from = from;
            this.target = target;
        }

        /** 事件的合法来源态集合（from = null 表示多个来源，见 RERUN_SOURCES）。 */
        Set<TaskStatus> sources() {
            return from != null ? Set.of(from) : Set.copyOf(RERUN_SOURCES);
        }

        boolean accepts(TaskStatus current) {
            return sources().contains(current);
        }
    }
}
