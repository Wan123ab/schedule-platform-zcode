package com.flowops.common.guard;

import com.flowops.common.enums.ConcurrencyPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发策略判定器单测（纯函数穷举）。
 * 分支语义对应 docs/06 §6.1 工作流级一行 + docs/07 §6.4 的响应分流约定。
 */
class ConcurrencyPolicyEvaluatorTest {

    @Test
    void FORBID_已有实例运行_拒绝并预留runningTaskId槽位() {
        CheckResult r = ConcurrencyPolicyEvaluator.decide(ConcurrencyPolicy.FORBID, 5, 1);

        assertThat(r.allowed()).isFalse();
        assertThat(r.action()).isEqualTo(CheckResult.Action.REJECT_FORBID);
        assertThat(r.limit()).isEqualTo(1);   // FORBID 语义上限即 1，与 maxParallelRuns 无关
    }

    @Test
    void FORBID_无实例_放行() {
        assertThat(ConcurrencyPolicyEvaluator.decide(ConcurrencyPolicy.FORBID, 5, 0).allowed()).isTrue();
    }

    @Test
    void QUEUE_达上限_排队而非报错() {
        CheckResult r = ConcurrencyPolicyEvaluator.decide(ConcurrencyPolicy.QUEUE, 2, 2);

        assertThat(r.allowed()).isFalse();
        assertThat(r.deferred()).isTrue();    // 排队是"成功语义"（docs/07 §6.4 关键约定）
        assertThat(r.action()).isEqualTo(CheckResult.Action.DEFER_QUEUE);
        assertThat(r.limit()).isEqualTo(2);
    }

    @Test
    void QUEUE_未达上限_放行() {
        assertThat(ConcurrencyPolicyEvaluator.decide(ConcurrencyPolicy.QUEUE, 3, 2).allowed()).isTrue();
    }

    @Test
    void ALLOW_永不受running限制() {
        assertThat(ConcurrencyPolicyEvaluator.decide(ConcurrencyPolicy.ALLOW, 1, 99).allowed()).isTrue();
    }
}
