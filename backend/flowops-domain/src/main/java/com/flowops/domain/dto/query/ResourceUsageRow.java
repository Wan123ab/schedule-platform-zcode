package com.flowops.domain.dto.query;

import lombok.Data;

import java.math.BigDecimal;

/** 集群资源用量行（M4-S5；used 从 executor_node 聚合，total 取 cluster 容量列）。 */
@Data
public class ResourceUsageRow {

    private String clusterId;

    private String clusterName;

    private BigDecimal cpuUsed;

    private BigDecimal cpuTotal;

    private BigDecimal gpuUsed;

    private BigDecimal gpuTotal;

    private Long diskUsed;

    private Long diskTotal;
}
