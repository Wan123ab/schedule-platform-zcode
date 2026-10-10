package com.flowops.modules.task.service;

import com.flowops.common.api.ErrorCode;
import com.flowops.common.enums.TriggerType;
import com.flowops.common.exception.BizException;
import com.flowops.domain.entity.task.BackfillBatch;
import com.flowops.domain.entity.workflow.Workflow;
import com.flowops.domain.entity.workflow.WorkflowVersion;
import com.flowops.domain.mapper.workflow.WorkflowVersionMapper;
import com.flowops.domain.service.TaskCreationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;

/**
 * 回填建任务桥（server 侧 retryFailed / scheduler 侧 BackfillDispatcher 共用语义的
 * server 端封装）。职责只有三件事，<b>不做</b>并发检查、不做投放闸门 —— 那两道闸
 * 分别属于 ConcurrencyGuard（提交准入）与 BackfillDispatcher（eff_concurrency 滚动投放）：
 *
 * <ol>
 *   <li>把批次行翻译成 {@link TaskCreationService.Spec}（版本号回查 workflow_version 取 versionNo）；</li>
 *   <li>钉死 TriggerType=BACKFILL + backfillBatchId（任务表回填标记，审计与进度聚合都靠它）；</li>
 *   <li>变量快照 = 批次 overrides JSON 原样透传（口径偏离登记 README-M4 O-57：
 *       overrides 不与工作流默认参数在创建期合并，合并发生在执行期变量解析链）。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class TaskCreationServiceBridge {

    private final TaskCreationService taskCreationService;
    private final WorkflowVersionMapper workflowVersionMapper;

    /**
     * 为回填批次的一个业务日期建任务（PENDING + 全部步骤实例）。
     *
     * @param variableSnapshotJson 变量快照 JSON（批次 overrides；null/空按 "{}"）
     */
    public TaskCreationService.Creation createForBackfill(BackfillBatch batch, Workflow workflow,
                                                          LocalDate bizDate, Long queueId,
                                                          String variableSnapshotJson) {
        WorkflowVersion version = workflowVersionMapper.selectById(batch.getWorkflowVersionId());
        if (version == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "回填批次绑定的版本不存在或已删除",
                    Map.of("resource_type", "WORKFLOW_VERSION", "resource_id",
                            String.valueOf(batch.getWorkflowVersionId())));
        }
        return taskCreationService.create(new TaskCreationService.Spec(
                batch.getWorkflowId(), workflow.getWorkflowName(), batch.getWorkflowVersionId(),
                version.getVersionNo(), batch.getProjectId(), TriggerType.BACKFILL, batch.getId(),
                bizDate, batch.getSubmitter(), queueId, 0,
                variableSnapshotJson == null || variableSnapshotJson.isBlank() ? "{}" : variableSnapshotJson));
    }
}
