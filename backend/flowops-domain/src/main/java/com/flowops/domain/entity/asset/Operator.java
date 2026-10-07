package com.flowops.domain.entity.asset;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 算子（docs/05 §3.3 operator）—— 工作流步骤的执行单元，归属某个项目。
 *
 * <p><b>为什么归属项目</b>：算子是可执行代码（jar/py/sh），一旦跨项目共享，
 * "谁能改这份代码"就不可回答了。故 R8 把 {@code project_id} 定为 NOT NULL + FK，
 * 数据范围也走项目维度（docs/07 §5.3 的 PROJECT 分支）。</p>
 *
 * <p><b>版本是独立实体</b>：{@code latest_version} / {@code version_count} 只是展示快照，
 * 真正的"哪一版可被工作流引用"由 {@link OperatorVersion#getPublishStatus()} 决定
 * （docs/09 §M3：发布后冻结，D-11）。</p>
 */
@Data
@TableName("operator")
public class Operator {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务编号 OP-0001（docs/05 §6.2：全局递增，非按日归零） */
    private String operatorId;

    private String operatorName;

    /** JAR / PYTHON / SHELL / BAT / EXE / CUSTOM（DDL CHECK） */
    private String operatorType;

    private Long projectId;

    private String description;

    /** ENABLED / DISABLED（停用算子后其版本不可被新步骤引用） */
    private String status;

    /** 最新版本号 vN（展示用快照，非权威） */
    private String latestVersion;

    /** 版本总数（展示用快照，非权威） */
    private Integer versionCount;

    private String creator;

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
