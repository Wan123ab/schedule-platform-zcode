package com.flowops.common.guard;

import com.flowops.common.enums.ConcurrencyPolicy;

/**
 * 工作流并发策略判定器 —— <b>纯函数</b>，docs/06 §6.1 表中"工作流级"一行的可测形态。
 *
 * <p><b>为什么独立成纯函数</b>：D-12 要求所有触发路径走同一 ConcurrencyGuard；
 * 而策略分支（FORBID/QUEUE/ALLOW）是最容易被"顺手改坏"的业务规则。把分支抽成无依赖的纯函数，
 * 单测可以穷举每种策略 × 边界（running == max、running == 0），DB 适配层只剩"取数"一件事。</p>
 */
public final class ConcurrencyPolicyEvaluator {

    private ConcurrencyPolicyEvaluator() {
    }

    /**
     * @param policy          工作流声明的并发策略（DAG 校验规则 8 保证非空）
     * @param maxParallelRuns 最大并行实例数
     * @param runningCount    在途实例数（SCHEDULING/RUNNING/STOPPING）
     */
    public static CheckResult decide(ConcurrencyPolicy policy, int maxParallelRuns, long runningCount) {
        return switch (policy) {
            // FORBID：语义是"不允许并行实例"，与 maxParallelRuns 无关，上限即 1（40901）
            case FORBID -> runningCount >= 1
                    ? new CheckResult(CheckResult.Action.REJECT_FORBID, policy, runningCount, 1, null)
                    : CheckResult.proceed(policy, runningCount, 1);
            // QUEUE：达上限 → 保持 PENDING 排队（成功语义，非错误）
            case QUEUE -> runningCount >= maxParallelRuns
                    ? new CheckResult(CheckResult.Action.DEFER_QUEUE, policy, runningCount, maxParallelRuns, null)
                    : CheckResult.proceed(policy, runningCount, maxParallelRuns);
            // ALLOW：不限并行，limit 仅作展示
            case ALLOW -> CheckResult.proceed(policy, runningCount, maxParallelRuns);
        };
    }
}
