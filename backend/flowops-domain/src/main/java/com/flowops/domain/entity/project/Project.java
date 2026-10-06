package com.flowops.domain.entity.project;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 项目空间（docs/05 §3.2 project 表映射）—— 多租户隔离单元，所有资源与任务的归属根。
 */
@Data
@TableName("project")
public class Project {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务编号 PRJ-0001 */
    private String projectId;

    private String projectName;

    private Long tenantId;

    private String description;

    /** ENABLED / DISABLED / ARCHIVED */
    private String status;

    // 资源额度（PRD §11.4）
    private Integer maxConcurrentTasks;

    private Integer maxWaitingTasks;

    /** 项目默认参数（继承链第 2 层，PRD §12.5） */
    private String defaultParams;

    private Integer defaultTimeoutSeconds;

    private Integer defaultRetryCount;

    private String defaultFailureStrategy;

    /** 负责人（不可移除，CONTRACT §2 / 42201） */
    private Long ownerUserId;

    // 停用统计快照（冗余计数，docs/05 §6.3：只用于展示）
    private Integer statWorkflowCount;

    private Integer statTaskCount;

    private Integer statMemberCount;

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
