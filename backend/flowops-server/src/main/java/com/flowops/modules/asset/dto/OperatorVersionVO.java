package com.flowops.modules.asset.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 算子版本出参（docs/07 §5.4 {@code GET /operator-versions/{versionId}}）。
 *
 * <p><b>为什么把参数与输出内联进版本</b>：它们是"这一版怎么被调用"的完整契约，
 * 前端版本详情页要一次渲染出来；拆成三个接口会让页面出现三次 loading 与中间态。
 * 版本号越小（一版几十个参数）这个取舍越划算，且它们本就是同生命周期快照。</p>
 */
@Data
public class OperatorVersionVO {

    /** 版本业务编号 OPV-xxxx-xx */
    private String versionId;

    /** 所属算子业务编号 OP-xxxx */
    private String operatorId;

    private String operatorName;

    /** v1 / v2 */
    private String versionNo;

    private String description;

    // ── 文件信息 ──
    private String fileName;
    private Long fileSize;
    /** SHA-256（服务端计算） */
    private String fileChecksum;

    // ── 运行环境 ──
    private String osType;
    private String startCommand;
    private String workDir;
    private List<OperatorVersionParts.EnvVar> envVars;
    private List<Integer> successCodes;

    // ── 默认值 ──
    private Integer defaultTimeoutSeconds;
    private Integer defaultRetryCount;
    private Integer defaultRetryIntervalSeconds;
    private OperatorVersionParts.DefaultResource defaultResource;

    // ── 日志模式 ──
    private Integer logTailLines;
    private Long logMaxBytes;

    // ── 发布状态 ──
    /** DRAFT / PUBLISHED / OFFLINE */
    private String publishStatus;
    private Boolean isDefaultVersion;
    private String publisher;
    private OffsetDateTime publishedAt;
    private OffsetDateTime createdAt;

    // ── 随版本快照的组成部件 ──
    private List<OperatorVersionParts.ParamDef> paramTemplate;
    private List<OperatorVersionParts.OutputDecl> outputDeclarations;
}
