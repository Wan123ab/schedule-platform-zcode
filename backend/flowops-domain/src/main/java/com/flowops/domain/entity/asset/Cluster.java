package com.flowops.domain.entity.asset;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 集群（docs/05 §3.3 cluster）—— 执行节点集合，调度资源池的顶层单元。
 *
 * <p><b>状态语义（DDL CHECK）</b>：{@code NORMAL}（全部节点正常）/ {@code PARTIAL_ABNORMAL}（部分离线）/
 * {@code UNAVAILABLE}（全部离线）/ {@code MAINTENANCE}（人工维护，暂停派发）。</p>
 *
 * <p><b>冗余计数纪律（docs/05 §6.3）</b>：{@code node_*} 与 {@code *_task_count} 是 30s 定时聚合的快照，
 * 只用于列表展示；删除/停用等业务判定一律实时 COUNT。</p>
 */
@Data
@TableName("cluster")
public class Cluster {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务编号 CL-0001（docs/05 §6.2，Redis INCR + 唯一索引兜底） */
    private String clusterId;

    private String clusterName;

    /** 集群类型（SPEC §6.2，一期默认 GENERAL） */
    private String clusterType;

    /** NORMAL / PARTIAL_ABNORMAL / UNAVAILABLE / MAINTENANCE */
    private String status;

    private BigDecimal cpuTotal;

    private BigDecimal gpuTotal;

    /** 内存总量（MB） */
    private Long memoryTotal;

    /** 磁盘总量（MB） */
    private Long diskTotal;

    private Integer nodeTotal;

    private Integer nodeOnline;

    private Integer nodeOffline;

    private Integer nodeIdle;

    private Integer runningTaskCount;

    private Integer pendingTaskCount;

    private Integer historyTaskCount;

    private OffsetDateTime lastHeartbeatAt;

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
