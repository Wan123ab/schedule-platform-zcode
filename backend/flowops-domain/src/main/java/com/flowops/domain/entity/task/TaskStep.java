package com.flowops.domain.entity.task;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.flowops.common.enums.StepStatus;
import com.flowops.domain.mybatis.JsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 步骤实例（docs/05 §3.5 task_step 表映射）—— 调度器写入量最大的表，11 态状态机的载体。
 *
 * <p><b>调度器关心的核心列（与 docs/06 的对应）</b>：</p>
 * <ul>
 *   <li>{@code dispatch_token}：CAS 幂等标记（D-23 / §4.3）——出队两段式的支点，
 *       先 DB 占位后 ZREM，崩溃最坏情况是"占了位还在队列"，CAS 失败自然跳过（安全方向）；</li>
 *   <li>{@code enqueue_seq}：score 组成部分（§4.2）；回退/重试时保留原值防饥饿（§4.5）；</li>
 *   <li>{@code next_retry_at}：重试到期时刻落库（§9.1）——调度器重启后重试不丢；</li>
 *   <li>{@code mutex_holder}：互斥锁持有点标记（§10.2 ③ 恢复依据）；</li>
 *   <li>{@code output_vars}：下游 ${step.X.output.Y} 引用的数据来源（D-20）。</li>
 * </ul>
 *
 * <p><b>与 task 表的约定差异</b>：本表无 created_by/updated_by/deleted 列（docs/05 原文口径，
 * 实例随任务存亡，单独逻辑删除无意义）；乐观锁同样启用（调度器 vs 人工操作并发保护）。</p>
 */
@Data
@TableName(value = "task_step", autoResultMap = true)
public class TaskStep {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务编号 SI-xxxx */
    private String stepInstanceId;

    private Long taskId;

    /** 来源步骤（workflow_step.id）；NULL 仅出现在理论边界（任务生成即建全部实例，此列实际非空） */
    private Long stepId;

    private String stepName;

    private Integer stepIndex;

    private StepStatus status;

    /** 执行位置（节点匹配成功后写入） */
    private Long clusterId;

    private String machineIp;

    private Long operatorId;

    private Long operatorVersionId;

    private OffsetDateTime startTime;

    private OffsetDateTime endTime;

    private Long durationMs;

    private Integer exitCode;

    private String failReason;

    /** 结构化失败原因（08 §3.5 九值枚举：EXIT_NONZERO/TIMEOUT/NODE_UNREACHABLE/...） */
    private String failReasonCode;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String failReasonDetail;

    private Integer retryCount;

    private Integer maxRetryCount;

    /** 重试到期时刻（RetryHandler 主查询依据，§9.1） */
    private OffsetDateTime nextRetryAt;

    /** 就绪队列 FIFO 序号（score 组成部分；回退保留原值防饥饿，§4.5） */
    private Long enqueueSeq;

    private String stdoutLogRef;

    private String stderrLogRef;

    private Long logLineCount;

    private Long logBytes;

    /** 超 100MB 截断标记（PRD §13.1-6） */
    private Boolean logTruncated;

    /** 资源申请（准入判定依据，D-22） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String resourceRequest;

    /** 资源实际峰值（只做画像与偏差告警，不参与准入） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String resourceActual;

    /** 输出变量（供下游 ${step.X.output.Y} 引用，D-20） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String outputVars;

    /** 阻塞原因（诊断面板，PRD §10.10） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String blockReason;

    private String mutexGroup;

    /** 互斥锁持有点标记（恢复时需重建，§10.2 ③） */
    private Boolean mutexHolder;

    /** CAS 幂等标记（§4.3 出队两段式；NULL = 未被占位） */
    private String dispatchToken;

    /** 解析后的实际命令（排障用） */
    private String resolvedCommand;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @Version
    private Integer version;
}
