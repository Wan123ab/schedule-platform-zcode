package com.flowops.modules.workflow.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 工作流版本出参（CONTRACT §6.2/§6.7：{@code GET /workflow-versions/{versionId}} 返回
 * {@code dagDefinition{steps[],edges[]}} + {@code workflowParams[]} + {@code triggerConfig[]}）。
 *
 * <p><b>steps/edges 直接从规范化表读，不从 {@code dag_definition} 的 JSONB 读</b>：
 * JSONB 是"原样快照"，用于审计比对（DDL 注释明写）；读它会把"某次写入解析失败"的坏
 * 快照直接暴露给画布。两张来源不一致时以规范化表为准 —— 它才是校验与调度真正消费的形态。</p>
 */
@Data
public class WorkflowVersionVO {

    /** 业务编号 WFV-<工作流数字段>-<两位序号> */
    private String versionId;

    /** 所属工作流业务编号 WF-#### */
    private String workflowId;

    private String workflowName;

    /** v1 / v2 … */
    private String versionNo;

    /** DRAFT / PUBLISHED / ARCHIVED（发布后永久冻结：PRD §7.2-2/5） */
    private String publishStatus;

    private Integer stepCount;

    /** DAG 节点（含 {@code stepId} 服务端编号，画布据此连线） */
    private List<DagStepDef> steps;

    /** DAG 连线（两端引用上面 steps 的 {@code stepId}） */
    private List<DagEdgeDef> edges;

    /** 工作流级参数（变量覆盖链第 3 层） */
    private List<Map<String, Object>> workflowParams;

    private Integer canvasWidth;

    private Integer canvasHeight;

    private String publisher;

    private OffsetDateTime publishedAt;

    /** 所属工作流是否存在未发布草稿（编辑器顶部的"草稿"标记） */
    private Boolean hasDraftChanges;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
