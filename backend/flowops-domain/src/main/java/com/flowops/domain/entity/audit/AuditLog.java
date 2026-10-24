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

    // ── 没有 createdBy 字段（O-76，2026-10-25 真实 PG 上首次暴露）──────────────
    // 这里原先声明了 `private String createdBy`（@TableField(fill = INSERT)），
    // 于是 MP 的 INSERT 会带上 created_by 列 —— 而 audit_log 表**根本没有这一列**
    // （docs/05 §3.6 的 DDL 与 V3 迁移都没有，只有 created_at）。
    // 后果：每一次审计落库都在真实 PG 上抛 "column created_by of relation audit_log
    // does not exist"，而 AuditAspect 是自吞异常的（不能让审计失败拖垮业务），
    // 于是表现为**审计一条都没落进去、控制台只有一行 ERROR** ——
    // 典型的静默失效，且在只有单测的环境里永远不会被发现。
    //
    // 为什么是删字段而不是给表补列：`operator` 已经记录了"谁做的"，
    // created_by（"谁写的这条记录"）在审计语义上是冗余的；docs/05 §3.6 的
    // 权威 DDL 也确认没有它。以 DDL 为准，实体对齐表，而不是反过来。
}
