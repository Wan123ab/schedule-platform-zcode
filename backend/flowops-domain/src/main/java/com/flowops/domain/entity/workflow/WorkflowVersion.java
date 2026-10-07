package com.flowops.domain.entity.workflow;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.flowops.domain.mybatis.JsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 工作流版本快照（docs/05 §3.4 workflow_version）—— 发布后<b>不可改不可删</b>。
 *
 * <p><b>不可变性的理由（docs/03 §4.2 / PRD §7.2）</b>：任务强绑定
 * {@code workflow_version}。若已发布版本可改，历史任务的"当时执行的是什么"就永久失效，
 * 审计与排障同时归零。故发布后唯一能做的事是「基于此版本新开草稿」——
 * 那是<b>另起一行</b>，不是改这一行。</p>
 *
 * <p><b>为什么同时存 JSONB 与规范化表</b>：{@code dag_definition} 是"原样快照"，
 * 用于审计比对与前端一次性取图；{@code workflow_step} / {@code workflow_edge} 是
 * 规范化落地，供调度器按步骤/边查询与 join 算子。两者必须由同一次保存写出来
 * （见 {@code WorkflowVersionService#saveDraft}），否则会出现"JSONB 与表内容不一致"
 * 这种无人能判定的状态。</p>
 *
 * <p><b>步骤计数是冗余列</b>：{@code step_count} 只服务列表页展示，
 * 权威值永远是 {@code workflow_step} 的行数（docs/05 §6.3 口径）。</p>
 */
@Data
@TableName(value = "workflow_version", autoResultMap = true)
public class WorkflowVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 版本业务编号 WFV-0001-01 */
    private String versionId;

    /** 所属工作流内部主键（外键 RESTRICT） */
    private Long workflowId;

    /** v1 / v2（同一工作流内非空唯一，靠 {@code uk_wv_workflow_no} 部分唯一索引） */
    private String versionNo;

    /** 整包图快照 {"steps":[],"edges":[]}；原样留档，用于审计比对 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String dagDefinition;

    /** 工作流级参数定义（继承链第 3 层） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String workflowParams;

    /** 触发器配置快照（PRD §10.9；M3 触发器切片落地后填充） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String triggerConfig;

    /** 步骤数（冗余展示值，权威以 workflow_step 行数为准） */
    private Integer stepCount;

    /** DRAFT / PUBLISHED / ARCHIVED */
    private String publishStatus;

    private String publisher;
    private OffsetDateTime publishedAt;

    // ── 画布渲染缓存（避免每次解析 JSONB 算尺寸）──
    private Integer canvasWidth;
    private Integer canvasHeight;

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
