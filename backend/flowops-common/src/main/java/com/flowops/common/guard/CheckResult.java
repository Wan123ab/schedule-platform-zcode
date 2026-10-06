package com.flowops.common.guard;

import com.flowops.common.enums.ConcurrencyPolicy;

/**
 * 并发判定结果（docs/06 §6.2 CheckResult 的落地形态）。
 *
 * <p><b>动作语义（docs/07 §6.4 的关键约定）</b>：</p>
 * <ul>
 *   <li>{@link Action#PROCEED} —— 通过，继续建任务并推进；</li>
 *   <li>{@link Action#DEFER_PROJECT_QUOTA} / {@link Action#DEFER_QUEUE} —— <b>不是错误</b>：任务保持
 *       PENDING 排队等待，前端渲染"已排队，前方 N 个"；</li>
 *   <li>{@link Action#REJECT_FORBID} —— 唯一的拒绝分支：FORBID 策略下已有实例在跑（40901，附 runningTaskId）。</li>
 * </ul>
 *
 * <p>40902（QUEUE 策略但等待数满）需要队列维度数据，属于提交路径的队列闸门（M1 接线时补），
 * 不在本判定器的职责内 —— 判定器保持"只看策略 + 计数"的纯粹性。</p>
 *
 * <p><b>位置说明</b>：common 而非 scheduler —— D-12 要求 server（提交路径）与 scheduler（准入扫描）
 * 引用同一个类型；common 不依赖任何业务模块，是唯一两边都能看见的家（docs/02 §3.1 v3 注）。</p>
 */
public record CheckResult(Action action, ConcurrencyPolicy policy, long runningCount, long limit,
                          String runningTaskId) {

    public enum Action {
        PROCEED,
        /** 项目额度已满：保持 PENDING（docs/06 §6.1 项目级超限行为） */
        DEFER_PROJECT_QUOTA,
        /** QUEUE 策略且已达 maxParallelRuns：保持 PENDING 排队 */
        DEFER_QUEUE,
        /** FORBID 策略下已有实例运行：40901 */
        REJECT_FORBID
    }

    public boolean allowed() {
        return action == Action.PROCEED;
    }

    public boolean deferred() {
        return action == Action.DEFER_PROJECT_QUOTA || action == Action.DEFER_QUEUE;
    }

    public static CheckResult proceed(ConcurrencyPolicy policy, long runningCount, long limit) {
        return new CheckResult(Action.PROCEED, policy, runningCount, limit, null);
    }
}
