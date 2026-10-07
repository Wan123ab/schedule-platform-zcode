package com.flowops.modules.asset.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 集群出参（docs/07 §8.1 XxxVO；字段取 docs/05 §3.3 cluster）。
 *
 * <p>{@code node_*} 与 {@code *_task_count} 是 30s 聚合快照（docs/05 §6.3），
 * 列表页展示用；前端不得据此做"能否删除集群"的判断（那是 42204 的服务端职责）。</p>
 */
@Data
public class ClusterVO {

    private String clusterId;
    private String clusterName;
    private String clusterType;
    private String status;

    private BigDecimal cpuTotal;
    private BigDecimal gpuTotal;
    /** MB */
    private Long memoryTotal;
    /** MB */
    private Long diskTotal;

    private Integer nodeTotal;
    private Integer nodeOnline;
    private Integer nodeOffline;
    private Integer nodeIdle;

    private Integer runningTaskCount;
    private Integer pendingTaskCount;
    private Integer historyTaskCount;

    private OffsetDateTime lastHeartbeatAt;
    private OffsetDateTime createdAt;
}
