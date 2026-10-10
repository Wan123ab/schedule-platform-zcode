package com.flowops.domain.entity.task;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.flowops.domain.mybatis.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 回填批次（docs/05 §3.5 backfill_batch，DDL 唯一真源）。
 *
 * <p><b>投放模型（docs/06 §12.1）</b>：批次不是"一次性建全部任务"——调度器侧
 * BackfillDispatcher 按 {@code eff_concurrency} 滚动投放，"待投放日期"不落 item 表，
 * 由<b>派生态</b>推导：planned 范围 − skipped_dates − 已有任务（backfill_batch_id 关联）的日期。</p>
 *
 * <p><b>双闸门（CONTRACT §8-1）</b>：提交必须携带 preview 的 {@code confirmed_preview_hash}，
 * 服务端重算比对 + 5 分钟 TTL（{@code preview_hash} 列 + Redis）。</p>
 */
@Data
@TableName(value = "backfill_batch", autoResultMap = true)
public class BackfillBatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务编号 BF-0001（uk_backfill_batch_id） */
    private String batchId;

    private String batchName;

    private Long workflowId;

    /** D-11：批次绑定版本快照（空 = 提交时的当前发布版） */
    private Long workflowVersionId;

    private Long projectId;

    private LocalDate bizFrom;

    private LocalDate bizTo;

    /** 用户设定并发（收敛前） */
    private Integer concurrency;

    private Boolean sequential;

    /** 收敛后实际并发（CONTRACT §8-2 三层收敛公式） */
    private Integer effConcurrency;

    /** RUNNING / PAUSED / DONE / FAILED / CANCELLED（DDL CHECK） */
    private String status;

    private Integer total;

    private Integer successCount;

    private Integer failedCount;

    private Integer waitingCount;

    /** 越窗跳过的日期（date[]，DateArrayTypeHandler） */
    @TableField(typeHandler = com.flowops.domain.mybatis.DateArrayTypeHandler.class)
    private LocalDate[] skippedDates;

    /** 防跳过预览直提：preview 配置的 SHA-256 */
    private String previewHash;

    private OffsetDateTime submittedAt;

    private OffsetDateTime finishedAt;

    private Long durationMs;

    private String submitter;

    /** 用户覆盖参数（jsonb，逐日期 merge 进 runParams——投放时由 dispatcher 解析） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String overrides;

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
