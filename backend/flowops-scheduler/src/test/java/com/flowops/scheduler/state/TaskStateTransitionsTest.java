package com.flowops.scheduler.state;

import com.flowops.common.enums.TaskStatus;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务状态机单测（docs/06 §16 第一行：每个转移一条用例 + 非法转移拒绝）。
 * 用例顺序与 docs/06 §2.1 转移表逐行对应 —— 表改了，这里必须同步改。
 */
class TaskStateTransitionsTest {

    @Test
    void 转移表_逐行_合法转移全部通过() {
        record Row(TaskStatus from, TaskStatus to) {}
        List<Row> table = List.of(
                new Row(TaskStatus.PENDING, TaskStatus.SCHEDULING),    // 行 1
                new Row(TaskStatus.PENDING, TaskStatus.FAILED),        // 行 2（FORBID）
                new Row(TaskStatus.SCHEDULING, TaskStatus.RUNNING),    // 行 3
                new Row(TaskStatus.SCHEDULING, TaskStatus.FAILED),     // 行 4（等待超时）
                new Row(TaskStatus.RUNNING, TaskStatus.SUCCESS),       // 行 5
                new Row(TaskStatus.RUNNING, TaskStatus.FAILED),        // 行 6/7
                new Row(TaskStatus.RUNNING, TaskStatus.PARTIAL),       // 行 8
                new Row(TaskStatus.RUNNING, TaskStatus.TIMEOUT),       // 行 11
                new Row(TaskStatus.RUNNING, TaskStatus.STOPPING),      // §15.1 ①
                new Row(TaskStatus.STOPPING, TaskStatus.STOPPED),      // §15.1 ④
                new Row(TaskStatus.FAILED, TaskStatus.RUNNING),        // §9.3 重跑失败步骤
                new Row(TaskStatus.TIMEOUT, TaskStatus.RUNNING),
                new Row(TaskStatus.PARTIAL, TaskStatus.RUNNING));

        table.forEach(row -> assertThat(TaskStateTransitions.canTransition(row.from(), row.to()))
                .as("%s → %s", row.from(), row.to()).isTrue());
    }

    @Test
    void 终态不可回退() {
        // SUCCESS 是审计可信度的根基，任何情况下不可改写（docs/06 §2.1 铁律）
        Arrays.stream(TaskStatus.values())
                .filter(to -> to != TaskStatus.SUCCESS)
                .forEach(to -> assertThat(TaskStateTransitions.canTransition(TaskStatus.SUCCESS, to))
                        .as("SUCCESS → %s 必须被拒绝", to).isFalse());

        // STOPPED 无"重跑失败步骤"边：重跑已停止任务走整任务重跑 = 新建实例（行 12）
        assertThat(TaskStateTransitions.canTransition(TaskStatus.STOPPED, TaskStatus.RUNNING)).isFalse();
    }

    @Test
    void 非终态之间的跳跃被拒绝() {
        assertThat(TaskStateTransitions.canTransition(TaskStatus.PENDING, TaskStatus.RUNNING)).isFalse();
        assertThat(TaskStateTransitions.canTransition(TaskStatus.RUNNING, TaskStatus.PENDING)).isFalse();
        assertThat(TaskStateTransitions.canTransition(TaskStatus.STOPPING, TaskStatus.RUNNING)).isFalse();
        assertThat(TaskStateTransitions.canTransition(TaskStatus.SCHEDULING, TaskStatus.STOPPING)).isFalse();
    }

    @Test
    void 事件式转移_合法通过_非法抛出诊断异常() {
        assertThat(TaskStateTransitions.transition(TaskStatus.PENDING, TaskStateTransitions.TaskEvent.CONCURRENCY_PASSED))
                .isEqualTo(TaskStatus.SCHEDULING);

        assertThatThrownBy(() ->
                TaskStateTransitions.transition(TaskStatus.SUCCESS, TaskStateTransitions.TaskEvent.STEPS_FAILED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法任务状态转移");
    }

    @Test
    void 重跑失败步骤_仅接受三个非SUCCESS终态() {
        var rerun = TaskStateTransitions.TaskEvent.RERUN_FAILED_STEPS;
        assertThat(rerun.accepts(TaskStatus.FAILED)).isTrue();
        assertThat(rerun.accepts(TaskStatus.TIMEOUT)).isTrue();
        assertThat(rerun.accepts(TaskStatus.PARTIAL)).isTrue();
        assertThat(rerun.accepts(TaskStatus.SUCCESS)).isFalse();
        assertThat(rerun.accepts(TaskStatus.STOPPED)).isFalse();
        assertThat(rerun.accepts(TaskStatus.RUNNING)).isFalse();
    }

    @Test
    void isFinal_恰好五个终态() {
        assertThat(TaskStateTransitions.isFinal(TaskStatus.SUCCESS)).isTrue();
        assertThat(TaskStateTransitions.isFinal(TaskStatus.FAILED)).isTrue();
        assertThat(TaskStateTransitions.isFinal(TaskStatus.STOPPED)).isTrue();
        assertThat(TaskStateTransitions.isFinal(TaskStatus.TIMEOUT)).isTrue();
        assertThat(TaskStateTransitions.isFinal(TaskStatus.PARTIAL)).isTrue();
        assertThat(TaskStateTransitions.isFinal(TaskStatus.RUNNING)).isFalse();
        assertThat(TaskStateTransitions.isFinal(TaskStatus.STOPPING)).isFalse();
        assertThat(TaskStateTransitions.isFinal(TaskStatus.PENDING)).isFalse();
        assertThat(TaskStateTransitions.isFinal(TaskStatus.SCHEDULING)).isFalse();
    }
}
