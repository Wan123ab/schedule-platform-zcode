package com.flowops.domain.entity.audit;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 审计日志（docs/05 §3.6 audit_log）。
 * 不变式：只增不改不删（DB 层 REVOKE UPDATE/DELETE，docs/05 §7.4）。
 */
@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** AU-0001 */
    private String auditId;

    private OffsetDateTime operatedAt;

    private String operator;

    private String operatorName;

    /** 对齐 CHECK：PROJECT/WORKFLOW/.../TASK/... */
    private String targetType;

    private String targetId;

    private String targetName;

    private Long projectId;

    private String action;

    private String beforeSummary;

    private String afterSummary;

    /** [{field,old,new}] */
    private String diff;

    /** SUCCESS / FAIL / PARTIAL */
    private String result;

    private String failReason;

    private String sourceIp;

    private String userAgent;

    /** 32 位无连字符 hex（D-05：线协议 trace_id） */
    private String traceId;

    private String reason;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
}
