package com.flowops.domain.entity.task;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.flowops.common.enums.TaskStatus;
import com.flowops.common.enums.TriggerType;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 任务 = 工作流的一次运行实例（docs/05 §3.5 task 表映射，DDL 唯一真源，不在此另立定义）。
 *
 * <p><b>设计说明</b>：</p>
 * <ul>
 *   <li>业务编号 {@code task_id}（TASK-yyyyMMdd-####）与物理主键 {@code id}（bigserial）分离，
 *       外键一律引用 bigint 的 {@code id}（docs/05 §1.2）。</li>
 *   <li>枚举字段直接用共享枚举（{@code @EnumValue} 落库、{@code @JsonValue} 出参），三段风格自动对齐。</li>
 *   <li>{@code @Version} 乐观锁：任务状态流转全部走 CAS（docs/03 §3.4），调度器与人工操作并发保护。</li>
 *   <li>JSONB 快照类字段（variable_snapshot / diagnosis_info）以 String 承载——JSON 的解析时机
 *       由调用方决定；列绑定经 {@code JsonbTypeHandler}（String ⇄ jsonb）。</li>
 * </ul>
 */
@Data
@TableName(value = "task", autoResultMap = true)   // autoResultMap：读取侧 ResultMap 带上 jsonb TypeHandler
public class Task {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务编号 TASK-20260921-0001（uk_task_task_id 兜底唯一） */
    private String taskId;

    private Long workflowId;

    /** 快照冗余（避免列表页 join） */
    private String workflowName;

    /** 任务强绑定版本快照（D-11：版本发布后冻结，任务可考证"当时执行的是什么"） */
    private Long workflowVersionId;

    private String workflowVersion;

    private Long projectId;

    private TriggerType triggerType;

    private Long triggerId;

    /** 回填批次（PRD §10.11-1） */
    private Long backfillBatchId;

    /** 业务日期（回填/定时场景；是"哪一天"而非"哪一瞬"） */
    private LocalDate bizDate;

    private String submitter;

    private Long clusterId;

    private String clusterName;

    private Long queueId;

    private String queueName;

    /** 越大越优先（E-07：0~100，默认 0） */
    private Integer priority;

    private TaskStatus status;

    /** 进度（列表页展示，冗余自 task_step 聚合） */
    private String currentStepName;

    private Integer currentStepIndex;

    private Integer stepTotal;

    private Integer finishedSteps;

    private OffsetDateTime submitAt;

    private OffsetDateTime startTime;

    private OffsetDateTime endTime;

    private Long durationMs;

    private String failReason;

    private String stoppedBy;

    private String stopReason;

    /** 6 层变量解析后的最终值快照（PRD §10.0.4；敏感值不落原文，M-07）。JSONB 经 JsonbTypeHandler 绑定 */
    @TableField(typeHandler = com.flowops.domain.mybatis.JsonbTypeHandler.class)
    private String variableSnapshot;

    /** 调度诊断（PRD §10.10）。JSONB 经 JsonbTypeHandler 绑定 */
    @TableField(typeHandler = com.flowops.domain.mybatis.JsonbTypeHandler.class)
    private String diagnosisInfo;

    /** 入队序号（Redis INCR，M-09：同毫秒并列会破坏 FIFO 确定性） */
    private Long enqueueSeq;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;

    /** 乐观锁：调度器 CAS 更新的并发保护（docs/05 §6.1） */
    @Version
    private Integer version;

    private Boolean deleted;
}
