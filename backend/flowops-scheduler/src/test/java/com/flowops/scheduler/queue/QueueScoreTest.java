package com.flowops.scheduler.queue;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 就绪队列 score 构造单测（docs/06 §16 第二行：优先级/FIFO 组合与边界）。
 * 边界清单对应 §16「必测的边界用例」之 1/2/3 的 score 部分。
 */
class QueueScoreTest {

    @Test
    void 优先级越大_score越小_越先出队() {
        // ZSet 升序取（ZRANGE），score 小者先出 —— 高优任务必须拿到更小的 score
        assertThat(QueueScore.of(100, 1)).isLessThan(QueueScore.of(0, Long.MAX_VALUE / 2));
        assertThat(QueueScore.of(50, 9_999_999)).isLessThan(QueueScore.of(49, 0));
    }

    @Test
    void 同优先级_按enqueueSeq严格FIFO() {
        long first = QueueScore.of(0, 1);
        long second = QueueScore.of(0, 2);
        long third = QueueScore.of(0, 3);
        assertThat(first).isLessThan(second).isLessThan(third);
    }

    @Test
    void 优先级边界_0与100() {
        assertThat(QueueScore.of(0, 0)).isEqualTo(100L * QueueScore.SEQ_WEIGHT);
        assertThat(QueueScore.of(100, 0)).isZero();
    }

    @Test
    void 优先级越界_拒绝构造() {
        assertThatThrownBy(() -> QueueScore.of(101, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QueueScore.of(-1, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 序号不越权_高优先级取最大序号仍排在低优先级取最小序号之前() {
        // docs/06 §4.2 的事故推演：只要 enqueueSeq < SEQ_WEIGHT（≈348 年），序号永远不会
        // 把步骤挤进相邻优先级区间 —— 在此契约内，优先级严格压倒序号
        long maxSeqInContract = QueueScore.SEQ_WEIGHT - 1;
        assertThat(QueueScore.of(1, maxSeqInContract)).isLessThan(QueueScore.of(0, 0));

        // 上限安全：最坏组合也不越过 Long 上限（100 × 2^40 ≈ 1.1e14 ≪ 9.2e18）
        assertThat(QueueScore.of(0, maxSeqInContract)).isPositive();
    }

    @Test
    void priorityOf_反解一致() {
        assertThat(QueueScore.priorityOf(QueueScore.of(0, 123))).isZero();
        assertThat(QueueScore.priorityOf(QueueScore.of(37, 456))).isEqualTo(37);
        assertThat(QueueScore.priorityOf(QueueScore.of(100, 789))).isEqualTo(100);
    }
}
