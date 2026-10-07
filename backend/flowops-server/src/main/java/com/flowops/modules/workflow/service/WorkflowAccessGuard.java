package com.flowops.modules.workflow.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flowops.common.context.ScopeContext;
import com.flowops.domain.entity.workflow.Workflow;
import com.flowops.domain.mapper.workflow.WorkflowMapper;
import com.flowops.modules.governance.scope.ScopeGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 工作流可见性收口（40301 / 40400 的唯一判定点）。
 *
 * <p><b>为什么独立成一个类而不是放在 Service 里</b>：工作流侧有两个服务
 * （{@link WorkflowService} 管工作流行，{@link WorkflowVersionService} 管版本与 DAG），
 * 而"这个工作流我能不能看"必须只有一个实现 —— 否则两处各写一遍，某天只改了其中一处，
 * 就会出现"详情页进得去、版本接口进不去"这类看起来像 bug 的权限差异。</p>
 *
 * <p><b>派生链</b>：{@code workflow_version} / {@code workflow_step} / {@code workflow_edge}
 * 都<b>没有</b> {@code project_id} 列（docs/05 §3.4），{@code FlowopsDataPermissionHandler}
 * 也刻意不登记它们。版本侧一律「先取自身行 → 回父 workflow 做范围判定」，
 * 与 {@code operator_version} 的处置一致。若反过来给这三张表加 {@code project_id}，
 * 就出现了两份必须同步的项目归属，迟早不一致。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowAccessGuard {

    private final WorkflowMapper workflowMapper;
    private final ScopeGuard scopeGuard;

    /** 按业务编号取工作流（已应用行级数据范围）。不存在/越权时抛 40400 / 40301。 */
    public Workflow requireVisible(String workflowId) {
        Workflow visible = findByBusinessId(workflowId);
        if (visible != null) {
            return visible;
        }
        throw scopeGuard.notVisible("WORKFLOW", workflowId, () -> findByBusinessId(workflowId) != null);
    }

    /** 按内部主键取工作流（不做越权判定：调用方已在归属链上判过）。 */
    public Workflow findById(Long id) {
        return id == null ? null : workflowMapper.selectById(id);
    }

    /**
     * 按内部主键取工作流并判定可见性（版本/步骤/连线必须走这条路径）。
     *
     * <p>无过滤探测只用来区分"真不存在"（40400）与"存在但越权"（40301）；
     * 探测返回值绝不用于组装响应体（{@link ScopeContext} 的纪律）。</p>
     */
    public Workflow requireVisibleById(Long id) {
        Workflow visible = findById(id);
        if (visible != null) {
            return visible;
        }
        throw scopeGuard.notVisible("WORKFLOW", String.valueOf(id),
                () -> ScopeContext.withoutScope(() -> workflowMapper.selectById(id)) != null);
    }

    private Workflow findByBusinessId(String workflowId) {
        return workflowMapper.selectOne(Wrappers.<Workflow>lambdaQuery()
                .eq(Workflow::getWorkflowId, workflowId)
                .eq(Workflow::getDeleted, false));
    }
}
