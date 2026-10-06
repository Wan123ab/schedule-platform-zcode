package com.flowops.scheduler.dag;

import com.flowops.common.enums.StepStatus;
import com.flowops.common.enums.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 任务级终结判定单测（docs/06 §7.3：优先级 STOPPED &gt; TIMEOUT &gt; FAILED &gt; PARTIAL &gt; SUCCESS）。 */
class TaskOutcomeTest {

    @Test
    void 有非终态步骤_任务未结束() {
        assertThat(TaskOutcome.evaluate(List.of(StepStatus.SUCCESS, StepStatus.RUNNING))).isEmpty();
        assertThat(TaskOutcome.evaluate(List.of(StepStatus.RETRYING))).isEmpty();
        assertThat(TaskOutcome.evaluate(List.of())).isEmpty();   // 空集不构成结论（DAG 规则 1 保证至少一个步骤）
    }

    @Test
    void 全部成功() {
        assertThat(TaskOutcome.evaluate(List.of(StepStatus.SUCCESS, StepStatus.SUCCESS)))
                .contains(TaskStatus.SUCCESS);
    }

    @Test
    void STOPPED语义最强() {
        // 用户主动停止的语义优先于一切系统性终态（docs/06 §7.3 优先级定死）
        assertThat(TaskOutcome.evaluate(List.of(
                StepStatus.SUCCESS, StepStatus.TIMEOUT, StepStatus.STOPPED)))
                .contains(TaskStatus.STOPPED);
    }

    @Test
    void TIMEOUT仅次于STOPPED() {
        assertThat(TaskOutcome.evaluate(List.of(StepStatus.SUCCESS, StepStatus.TIMEOUT)))
                .contains(TaskStatus.TIMEOUT);
        assertThat(TaskOutcome.evaluate(List.of(StepStatus.FAILED, StepStatus.TIMEOUT)))
                .contains(TaskStatus.TIMEOUT);
    }

    @Test
    void 分支DAG_成功与失败共存为PARTIAL() {
        assertThat(TaskOutcome.evaluate(List.of(StepStatus.SUCCESS, StepStatus.FAILED)))
                .contains(TaskStatus.PARTIAL);
    }

    @Test
    void 只有失败无成功_为FAILED() {
        assertThat(TaskOutcome.evaluate(List.of(StepStatus.FAILED)))
                .contains(TaskStatus.FAILED);
        assertThat(TaskOutcome.evaluate(List.of(StepStatus.FAILED, StepStatus.STOPPED)))
                .contains(TaskStatus.STOPPED);   // STOPPED 优先级更高
    }
}
