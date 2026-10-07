package com.flowops.domain.entity.asset;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.flowops.domain.mybatis.IntegerArrayTypeHandler;
import com.flowops.domain.mybatis.JsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 算子版本（docs/05 §3.3 operator_version）—— 一次上传 = 一份不可变快照。
 *
 * <p><b>不可变性（D-11 / PRD §10.7）</b>：{@code publish_status} 从 DRAFT 变为
 * PUBLISHED 之后，本行与其 {@code operator_param_def} / {@code operator_output_decl}
 * 子行<b>都不允许再改</b>（42212）。原因不是洁癖：工作流步骤按
 * {@code operator_version_id} 绑定，已发布版本一旦被改，历史任务的"当时执行的是哪份代码"
 * 就永久不可追溯了。</p>
 *
 * <p><b>jsonb 用 String 承载</b>：{@code env_vars} / {@code default_resource} 在实体里是
 * JSON 字符串（{@link JsonbTypeHandler} 负责与 jsonb 互转），反序列化时机交给 Service ——
 * 这样实体不依赖任何 JSON 库，domain 模块也就能继续对业务零依赖。</p>
 *
 * <p><b>{@code success_codes} 是数组不是字符串</b>：调度器判定步骤成败时要逐个比对退出码，
 * 用 {@code integer[]} + {@link IntegerArrayTypeHandler} 可省掉每次 {@code parseInt}。</p>
 */
@Data
@TableName(value = "operator_version", autoResultMap = true)
public class OperatorVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 版本业务编号 OPV-0003-03（原型口径：算子数字段 + 两位版本序号） */
    private String versionId;

    /** 所属算子内部主键（外键 RESTRICT：有版本即不可物理删算子） */
    private Long operatorId;

    /** v1 / v2（同一算子内非空唯一） */
    private String versionNo;

    private String description;

    // ── 文件信息 ──
    private String fileName;
    private Long fileSize;
    /** SHA-256（服务端计算；同算子内重复上传同一文件会被识别出来） */
    private String fileChecksum;
    private String filePath;

    // ── 运行环境 ──
    /** LINUX / WINDOWS */
    private String osType;
    private String startCommand;
    private String workDir;

    /** [{key,value,secret}] —— 含 secret=true 的项在日志与快照中必须脱敏 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String envVars;

    /** 成功退出码，默认 {0} */
    @TableField(typeHandler = IntegerArrayTypeHandler.class)
    private Integer[] successCodes;

    // ── 默认值（参数继承链第 4 层，PRD §12.5）──
    private Integer defaultTimeoutSeconds;
    private Integer defaultRetryCount;
    private Integer defaultRetryIntervalSeconds;

    /** {cpu,gpu,memory,disk} */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String defaultResource;

    // ── 日志模式（PRD §13.1-7 大日志模式）──
    private Integer logTailLines;
    private Long logMaxBytes;

    // ── 发布状态（不可变性的关键）──
    /** DRAFT / PUBLISHED / OFFLINE */
    private String publishStatus;
    private Boolean isDefaultVersion;
    private String publisher;
    private OffsetDateTime publishedAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;

    @Version
    private Integer version;

    private Boolean deleted;
}
