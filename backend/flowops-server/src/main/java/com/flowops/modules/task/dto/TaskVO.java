package com.flowops.modules.task.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 任务出参（CONTRACT §7 {@code task}；列表与详情共用主体，详情额外挂
 * {@link TaskDetailVO} 的变量快照与诊断）。
 *
 * <p><b>全部外键出业务编号</b>（D-27）：{@code workflowId}=WF-####、
 * {@code projectId}=PRJ-####、{@code clusterId}=CL-####、{@code queueId}=队列编号、
 * {@code triggerId}=触发器编号；内部 bigint 主键一律不出网，由 Service 翻译。</p>
 *
 * <p><b>为什么名称类字段（workflowName/clusterName/queueName）直接在 VO 里</b>：
 * 它们本就是 {@code task} 表的<b>快照冗余列</b>（docs/05 §3.5，为避免列表页 join）。
 * 用快照而非实时 join 是刻意的——任务要能考证"当时是哪个名字"，改名不应回溯改写历史。</p>
 */
@Data
public class TaskVO {

    /** 业务编号 TASK-yyyyMMdd-#### */
    private String taskId;

    /** 9 态之一 */
    private String status;

    /** 工作流业务编号 WF-####（D-27；Service 翻译） */
    private String workflowId;

    /** 工作流名快照 */
    private String workflowName;

    /** 版本号快照（D-11：任务强绑定版本） */
    private String workflowVersion;

    /** MANUAL / CRON / API / EVENT / BACKFILL */
    private String triggerType;

    /** 业务日期（回填/定时场景；是"哪一天"而非"哪一瞬"） */
    private LocalDate bizDate;

    /** 项目业务编号 PRJ-####（D-27；Service 翻译） */
    private String projectId;

    /** 提交人（DataScope 的 SELF_CREATED 维度就是它） */
    private String submitter;

    /** 越大越优先（0~100） */
    private Integer priority;

    /** 集群业务编号 CL-####（D-27；Service 翻译） */
    private String clusterId;

    private String clusterName;

    /** 队列编号（D-27；Service 翻译） */
    private String queueId;

    private String queueName;

    /** 触发器编号（D-27；Service 翻译；手工提交为 null） */
    private String triggerId;

    private String currentStepName;

    private Integer currentStepIndex;

    private Integer stepTotal;

    private Integer finishedSteps;

    private OffsetDateTime submitAt;

    private OffsetDateTime startTime;

    private OffsetDateTime endTime;

    private Long durationMs;

    /** 失败原因（任务级；步骤级在 {@link TaskStepVO#getFailReason()}） */
    private String failReason;

    private String stoppedBy;

    private String stopReason;

    private OffsetDateTime createdAt;
}
