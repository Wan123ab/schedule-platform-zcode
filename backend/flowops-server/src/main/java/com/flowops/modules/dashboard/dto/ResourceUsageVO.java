package com.flowops.modules.dashboard.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 集群资源用量（CONTRACT §11；used=节点聚合，total=cluster 容量列）。 */
@Data
public class ResourceUsageVO {

    private String clusterId;

    private String clusterName;

    private BigDecimal cpuUsed;

    private BigDecimal cpuTotal;

    private BigDecimal gpuUsed;

    private BigDecimal gpuTotal;

    private Long diskUsed;

    private Long diskTotal;
}
