package com.flowops.modules.workflow.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 工作流出参（CONTRACT §6.7 {@code workflow}；列表页另带 {@code last_run_status/last_run_at}）。
 *
 * <p><b>全部外键出业务编号</b>（D-27）：{@code projectId}=PRJ-xxxx、
 * {@code currentVersionId}=WFV-xxxx-xx；内部 bigint 主键不出网。</p>
 */
@Data
public class WorkflowVO {

    /** 业务编号 WF-####（docs/05 §6.2） */
    private String workflowId;

    private String workflowName;

    /** 所属项目业务编号 PRJ-xxxx */
    private String projectId;

    private String projectName;

    private String description;

    /** DRAFT / PUBLISHED / DISABLED / ARCHIVED */
    private String status;

    /** 当前生效版本；从未发布过时为 null */
    private WorkflowVersionBrief currentVersion;

    /** 是否存在未发布的草稿修改（列表页"未发布修改"列 / 42215 闸门的依据） */
    private Boolean hasDraftChanges;

    /** FORBID / ALLOW / QUEUE */
    private String concurrencyPolicy;

    private Integer maxParallelRuns;

    private Boolean clusterAffinityEnabled;

    /** 工作流级默认值（继承链第 3 层）；null = 不设默认，由下游各层决定 */
    private Integer defaultTimeoutSeconds;

    private Integer defaultRetryCount;

    private Integer defaultRetryIntervalSeconds;

    private String defaultFailureStrategy;

    private String creator;

    /** 列表页冗余快照（来自最近一次任务，非实时值） */
    private String lastRunStatus;

    private OffsetDateTime lastRunAt;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
