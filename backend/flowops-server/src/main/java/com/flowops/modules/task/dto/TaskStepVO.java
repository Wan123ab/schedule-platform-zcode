package com.flowops.modules.task.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 步骤实例出参（CONTRACT §7 {@code taskStep}：含
 * {@code resourceRequest/resourceActual/outputVars/retryHistory[]}）。
 *
 * <p><b>{@code rowId} 为什么必须显式下发</b>：日志 WebSocket 通道与 M4 的干预接口
 * （{@code rerun-failed} 的 {@code stepInstanceIds[]}）都以<b>物理主键</b>定位步骤，
 * 而业务编号 {@code stepInstanceId}（SI-xxxx）在日志表里并不存在——{@code task_log}
 * 存的是 {@code task_step_id}。不显式给出 rowId，前端就无法拿它去开日志流。</p>
 *
 * <p><b>四个 jsonb 字段为什么是 {@code Object}</b>：同 {@link TaskDetailVO}——
 * 库里是 jsonb、实体以 String 承载，对外必须是嵌套对象。解析失败给 {@code null}。</p>
 */
@Data
public class TaskStepVO {

    /** 物理主键（日志流/干预接口的定位键；它不是"外键业务编号"，故不走 D-27 翻译） */
    private Long rowId;

    /** 业务编号 SI-xxxx */
    private String stepInstanceId;

    private String stepName;

    private Integer stepIndex;

    /** 11 态之一 */
    private String status;

    /** 执行位置（节点匹配成功后写入） */
    private String machineIp;

    /** 算子版本业务编号 OPV-xxxx-xx（D-27；Service 翻译） */
    private String operatorVersionId;

    private OffsetDateTime startTime;

    private OffsetDateTime endTime;

    private Long durationMs;

    private Integer exitCode;

    /** 失败原因摘要（人读） */
    private String failReason;

    /** 结构化失败原因码（08 §3.5 九值枚举：EXIT_NONZERO/TIMEOUT/NODE_UNREACHABLE/...） */
    private String failReasonCode;

    /** 结构化失败的上下文（哪个参数、哪台机器等） */
    private Object failReasonDetail;

    private Integer retryCount;

    private Integer maxRetryCount;

    /** 下次重试时刻（RETRYING 态才有意义） */
    private OffsetDateTime nextRetryAt;

    /** 资源申请（准入判定依据，D-22） */
    private Object resourceRequest;

    /** 资源实际峰值（只做画像与偏差告警，不参与准入） */
    private Object resourceActual;

    /** 输出变量（供下游 ${step.X.output.Y} 引用，D-20） */
    private Object outputVars;

    /** 阻塞原因（诊断面板用；结构见 docs/06 §5.4） */
    private Object blockReason;

    /** 互斥组名（步骤级串行锁；非空表示这一步受互斥组约束） */
    private String mutexGroup;

    private Long logLineCount;

    private Long logBytes;

    /** 超 100MB 截断标记（PRD §13.1-6） */
    private Boolean logTruncated;

    /** 重试历史（按 attemptNo 升序） */
    private List<StepRetryVO> retryHistory;
}
