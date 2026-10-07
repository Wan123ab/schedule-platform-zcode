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
 * 工作流（docs/05 §3.4 workflow）—— 用户自己定义的 DAG 的「容器」，本身不承载图结构。
 *
 * <p><b>图在哪</b>：DAG 属于 {@link WorkflowVersion}（版本快照）。本表只保存
 * "当前生效哪一版"（{@code currentVersionId}）与并发/默认值这类跨版本共用的配置。
 * 这么分的原因见 docs/03 §4.2：任务强绑定 {@code workflow_version}，
 * 若图结构挂在容器上，"历史任务当时执行的是什么"就无从考证。</p>
 *
 * <p><b>{@code has_draft_changes} 是<b>容器级</b>的标记</b>：从已发布版本「新开草稿」时
 * 会复制出一条新版本行（原版本保持冻结），此时置 true；发布后置 false。存在的意义是
 * 让列表页不必 join 版本表就能提示"有未发布的改动"（docs/04 §709 的 CONTRACT §6.2）。</p>
 *
 * <p><b>并发配置为何在工作流而非版本</b>：{@code concurrency_policy} /
 * {@code max_parallel_runs} 是"这套工作流最多同时跑几个"的运维口径（PRD §12.3），
 * 调它不应产生新版本、也不该让历史任务看起来"用的旧配置"。DAG 规则 8 强制其必填。</p>
 */
@Data
@TableName(value = "workflow", autoResultMap = true)
public class Workflow {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务编号 WF-0001（docs/05 §6.2：全局递增式，非按日归零） */
    private String workflowId;

    private String workflowName;

    private Long projectId;

    /**
     * 工作流备注。
     *
     * <p>该列由 Flyway V7 补建：CONTRACT §6.1 的 POST/PUT 都带 {@code description}，
     * 但 docs/05 §3.4 的 DDL 漏了这一列（同节的 {@code workflow_step} 有）。</p>
     */
    private String description;

    /** DRAFT / PUBLISHED / DISABLED / ARCHIVED（DDL CHECK） */
    private String status;

    /** 当前生效版本（内部主键；出网时翻译为版本业务编号，D-27） */
    private Long currentVersionId;

    /** 存在未发布的草稿改动（容器级标记，见类注释） */
    private Boolean hasDraftChanges;

    /** FORBID / ALLOW / QUEUE */
    private String concurrencyPolicy;

    private Integer maxParallelRuns;

    /** 集群亲和（PRD §12.2） */
    private Boolean clusterAffinityEnabled;

    // ── 工作流级默认值（参数继承链第 3 层，PRD §12.5）──
    private Integer defaultTimeoutSeconds;
    private Integer defaultRetryCount;
    private Integer defaultRetryIntervalSeconds;
    private String defaultFailureStrategy;

    /** 告警接收人，JSON 数组；以 String 承载 JSON（反序列化时机交给 Service） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String notifyReceivers;

    private String creator;

    // ── 列表页冗余（避免 join 版本 + 任务）──
    private String lastRunStatus;
    private OffsetDateTime lastRunAt;

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
