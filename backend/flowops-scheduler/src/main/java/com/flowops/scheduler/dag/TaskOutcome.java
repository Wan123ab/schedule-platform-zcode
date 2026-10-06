package com.flowops.scheduler.dag;

import com.flowops.common.enums.StepStatus;
import com.flowops.common.enums.TaskStatus;

import java.util.Collection;
import java.util.Optional;

/**
 * 任务级终结判定（docs/06 §7.3）—— 全部可执行步骤到终态后，任务收敛到哪个终态。
 *
 * <p><b>优先级（docs/06 §7.3 定死）</b>：{@code STOPPED > TIMEOUT > FAILED > PARTIAL > SUCCESS}。
 * 用户主动停止的语义最强（用户意图明确）；超时次之（系统性异常）；FAILED 与 SUCCESS 共存时
 * 说明 DAG 有分支——一条分支失败、另一条成功，即 PARTIAL（部分成功）。</p>
 *
 * <p><b>NOTE 步骤不参与判定</b>（PRD §10.8 规则 1"备注除外"）：由调用方过滤——DagGraph
 * 构建时已剔除 NOTE，所以传入 {@code graph.executableStepIds()} 对应的状态集合即是。</p>
 */
public final class TaskOutcome {

    private TaskOutcome() {
    }

    /**
     * @param statuses 任务内全部可执行步骤实例的当前状态
     * @return 任务终态；尚有非终态步骤时返回 empty（任务未结束，继续轮询）
     */
    public static Optional<TaskStatus> evaluate(Collection<StepStatus> statuses) {
        if (statuses.isEmpty()) {
            return Optional.empty();   // 无可执行步骤不构成结论（DAG 校验规则 1 要求至少一个入度 0 步骤）
        }
        boolean hasFinalOnly = statuses.stream().allMatch(StepStatus::isFinal);
        if (!hasFinalOnly) {
            return Optional.empty();
        }
        // 判定顺序即优先级：短路返回第一个命中的最高优先级终态
        if (statuses.contains(StepStatus.STOPPED)) {
            return Optional.of(TaskStatus.STOPPED);
        }
        if (statuses.contains(StepStatus.TIMEOUT)) {
            return Optional.of(TaskStatus.TIMEOUT);
        }
        if (statuses.contains(StepStatus.FAILED)) {
            boolean anySuccess = statuses.contains(StepStatus.SUCCESS);
            return Optional.of(anySuccess ? TaskStatus.PARTIAL : TaskStatus.FAILED);
        }
        // ⚠️ SKIPPED（V1.1 预留）目前落入此分支会计为 SUCCESS —— 一期不会产生该状态；
        //    它参与判定的语义应随 continue-on-error（docs/06 §9.2，E-08）一并定义
        return Optional.of(TaskStatus.SUCCESS);
    }
}
