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
 * 调度队列（docs/05 §3.3 queue）—— 集群内的并发闸门单元。
 *
 * <p><b>与调度器的契约（docs/06 §6.1）</b>：{@code max_concurrent_tasks} 在<b>出队时</b>判定
 * （超限则该队列本轮不出队，任务保持 PENDING）；{@code max_waiting_tasks} 在<b>提交时</b>判定
 * （超限 → 40902）。两者判定的时机不同，不可互换。</p>
 */
@Data
@TableName("queue")
public class Queue {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务编号 QU-0001 */
    private String queueId;

    private String queueName;

    /** FK cluster.id（ON DELETE RESTRICT：集群下仍有队列时不可删集群） */
    private Long clusterId;

    /** ENABLED / DISABLED */
    private String status;

    /** 出队时判定（docs/06 §6.1） */
    private Integer maxConcurrentTasks;

    /** 提交时判定 → 40902 */
    private Integer maxWaitingTasks;

    /** 默认优先级 0~100（E-07 定案，数值越大越先执行） */
    private Integer defaultPriority;

    /** 是否允许插队（PRD §12.2-6） */
    private Boolean allowJumpQueue;

    /** 排队超时秒数，超时 → 调度失败（PRD §12.2-6） */
    private Integer waitTimeoutSeconds;

    /** 等待计数快照（聚合缓存，非业务判定依据） */
    private Integer waitingTaskCount;

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
