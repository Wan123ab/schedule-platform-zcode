package com.flowops.scheduler.state;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 步骤实例状态机单测（docs/06 §16；用例与 §2.2 转移表逐行对应）。
 * 重点覆盖：回退边（DISPATCH_FAIL / RETRY_READY）与重置边（RERUN_RESET）——
 * 这两类是任务状态机没有的、最容易实现错的转移。
 */
class StepStateTransitionsTest {

    @Test
    void 转移表_逐行_合法转移全部通过() {
        record Row(StepStatus from, StepStatus to) {}
        List<Row> table = List.of(
                new Row(StepStatus.NOT_STARTED, StepStatus.WAITING_DEPENDENCY),   // 入度 > 0
                new Row(StepStatus.NOT_STARTED, StepStatus.WAITING_RESOURCE),     // 入度 0
                new Row(StepStatus.WAITING_DEPENDENCY, StepStatus.WAITING_RESOURCE), // 上游全成功
                new Row(StepStatus.WAITING_DEPENDENCY, StepStatus.STOPPED),       // 上游失败+TERMINATE
                new Row(StepStatus.WAITING_RESOURCE, StepStatus.SCHEDULING),      // 节点+锁均获得
                new Row(StepStatus.WAITING_RESOURCE, StepStatus.FAILED),          // 等待超时
                new Row(StepStatus.SCHEDULING, StepStatus.RUNNING),               // 下发成功（CAS）
                new Row(StepStatus.SCHEDULING, StepStatus.WAITING_RESOURCE),      // 下发失败回退
                new Row(StepStatus.RUNNING, StepStatus.SUCCESS),
                new Row(StepStatus.RUNNING, StepStatus.RETRYING),
                new Row(StepStatus.RUNNING, StepStatus.FAILED),
                new Row(StepStatus.RUNNING, StepStatus.TIMEOUT),
                new Row(StepStatus.RUNNING, StepStatus.STOPPED),
                new Row(StepStatus.RETRYING, StepStatus.WAITING_RESOURCE),        // 重试间隔到达
                new Row(StepStatus.FAILED, StepStatus.NOT_STARTED),               // §9.3 重置
                new Row(StepStatus.TIMEOUT, StepStatus.NOT_STARTED),
                new Row(StepStatus.STOPPED, StepStatus.NOT_STARTED));

        table.forEach(row -> assertThat(StepStateTransitions.canTransition(row.from(), row.to()))
                .as("%s → %s", row.from(), row.to()).isTrue());
    }

    @Test
    void 终态不可直接流转到其他终态或执行态() {
        Arrays.stream(StepStatus.values()).filter(StepStatus::isFinal).forEach(finalState ->
                Arrays.stream(StepStatus.values()).filter(to -> to != finalState).forEach(to -> {
                    boolean rerunReset = to == StepStatus.NOT_STARTED
                            && (finalState == StepStatus.FAILED
                                || finalState == StepStatus.TIMEOUT
                                || finalState == StepStatus.STOPPED);
                    // 唯一例外：重跑失败步骤的显式重置边（§9.3）
                    if (!rerunReset) {
                        assertThat(StepStateTransitions.canTransition(finalState, to))
                                .as("%s → %s 必须被拒绝", finalState, to).isFalse();
                    }
                }));
    }

    @Test
    void SKIPPED_一期不产生_无任何进入边() {
        // V1.1 预留态（docs/05 §5）：刻意没有任何事件进入 SKIPPED，
        // 它出现之日即 PRD 定义 continue-on-error 之时
        Arrays.stream(StepStatus.values())
                .filter(from -> from != StepStatus.SKIPPED)
                .forEach(from -> assertThat(StepStateTransitions.canTransition(from, StepStatus.SKIPPED))
                        .as("%s → SKIPPED 一期必须不可达", from).isFalse());
    }

    @Test
    void RETRYING_不能跳过WAITING_RESOURCE_直接重跑() {
        // 重试必须重新参与节点匹配（默认换节点，docs/06 §9.1），不允许绕过队列
        assertThat(StepStateTransitions.canTransition(StepStatus.RETRYING, StepStatus.RUNNING)).isFalse();
        assertThat(StepStateTransitions.canTransition(StepStatus.RETRYING, StepStatus.SCHEDULING)).isFalse();
    }
}
