package com.flowops.common.guard;

/**
 * 并发控制统一入口（D-12，docs/06 §6.2）。
 *
 * <p><b>为什么必须单点</b>：PRD §12.3 要求手动/定时/API/回填四种触发走同一套并发检查。
 * 历史教训是"每条路径各写一遍 if"——某条路径漏改就产生并发事故。本接口是唯一入口，
 * 防绕过的工程手段是 ArchUnit 单测断言 TaskMapper.insert 只被统一提交服务调用
 * （docs/06 §6.2）——这条规则用测试守住，不靠 code review。</p>
 *
 * <p>判定链（docs/06 §6.4）：提交时只查<b>项目额度 → 工作流额度</b>两层；
 * 队列级并发在出队时查（scheduler 的派发阶段）；互斥锁在步骤执行前查（MutexLockManager）。</p>
 */
public interface ConcurrencyGuard {

    /**
     * 提交前检查项目级 + 工作流级并发。
     *
     * @return 判定结果 —— allowed() 或 deferred() 都表示"任务已建成"（前者直接推进、后者保持 PENDING）；
     *         仅 REJECT_FORBID 表示拒绝建任务（40901）
     */
    CheckResult checkBeforeSubmit(Long workflowId, Long projectId);
}
