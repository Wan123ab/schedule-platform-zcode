package com.flowops.modules.asset.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 算子列表/详情出参（docs/07 §5.4 {@code GET /operators*}）。
 *
 * <p><b>projectId 出业务编号而非内部主键</b>（D-27）：内部 bigint 不出网，
 * 另给只读的 {@code projectName} 让列表页免去二次查询。</p>
 */
@Data
public class OperatorVO {

    /** 业务编号 OP-0001 */
    private String operatorId;

    private String operatorName;

    /** JAR / PYTHON / SHELL / BAT / EXE / CUSTOM */
    private String operatorType;

    /** 所属项目业务编号 PRJ-xxxx */
    private String projectId;

    private String projectName;

    private String description;

    /** ENABLED / DISABLED */
    private String status;

    /** 最新版本号快照（展示用；可发布性以版本自身的 publishStatus 为准） */
    private String latestVersion;

    private Integer versionCount;

    private String creator;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
