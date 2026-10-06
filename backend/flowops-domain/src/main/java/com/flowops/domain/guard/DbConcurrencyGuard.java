package com.flowops.domain.guard;

import com.flowops.common.enums.ConcurrencyPolicy;
import com.flowops.common.guard.CheckResult;
import com.flowops.common.guard.ConcurrencyGuard;
import com.flowops.domain.dto.query.ProjectConcurrencyConfig;
import com.flowops.domain.dto.query.WorkflowConcurrencyConfig;
import com.flowops.domain.mapper.concurrency.ConcurrencyQueryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 并发判定的 DB 适配实现（docs/06 §6.4：提交时只查 项目额度 → 工作流额度 两层）。
 *
 * <p><b>位置说明</b>：domain 而非 scheduler —— server（提交）与 scheduler（准入）共用同一实现
 * （D-12），而本实现依赖 ConcurrencyQueryMapper；domain 是两者共享 Mapper 的唯一模块（docs/03 §1.2）。</p>
 *
 * <p><b>职责边界</b>：本类只做"取数 + 编排"，策略分支全部在
 * {@link com.flowops.common.guard.ConcurrencyPolicyEvaluator}（纯函数）—— 保证判定逻辑可被无 DB 单测穷举。</p>
 *
 * <p><b>一致性说明</b>：读计数与建任务之间存在窗口（无锁快照），依赖两层保险收口——
 * ① FORBID 的唯一性由 DB 唯一约束兜底（M1 提交路径串行化检查 + uk 兜底）；
 * ② ALLOW/QUEUE 的轻微超卖可接受（PRD 语义是配额而非硬实时）。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class DbConcurrencyGuard implements ConcurrencyGuard {

    private final ConcurrencyQueryMapper mapper;

    @Override
    public CheckResult checkBeforeSubmit(Long workflowId, Long projectId) {
        // ① 项目额度（docs/06 §6.1：项目级超限 → 保持 PENDING，不是错误）
        ProjectConcurrencyConfig project = mapper.findProjectConcurrency(projectId);
        long projectRunning = mapper.countRunningTasksByProject(projectId);
        if (projectRunning >= project.getMaxConcurrentTasks()) {
            log.info("项目额度已满 project={} running={} limit={}",
                    projectId, projectRunning, project.getMaxConcurrentTasks());
            return new CheckResult(CheckResult.Action.DEFER_PROJECT_QUOTA, null,
                    projectRunning, project.getMaxConcurrentTasks(), null);
        }

        // ② 工作流额度（策略分支见 ConcurrencyPolicyEvaluator）
        WorkflowConcurrencyConfig workflow = mapper.findWorkflowConcurrency(workflowId);
        long workflowRunning = mapper.countRunningTasksByWorkflow(workflowId);
        CheckResult result = ConcurrencyPolicyEvaluator.decide(
                ConcurrencyPolicy.of(workflow.getConcurrencyPolicy()),
                workflow.getMaxParallelRuns(),
                workflowRunning);

        // FORBID 拒绝时懒查询 running_task_id（40901 附带字段，docs/07 §6.4）
        if (result.action() == CheckResult.Action.REJECT_FORBID) {
            result = new CheckResult(result.action(), result.policy(), result.runningCount(),
                    result.limit(), mapper.findAnyRunningTaskId(workflowId));
        }
        return result;
    }
}
