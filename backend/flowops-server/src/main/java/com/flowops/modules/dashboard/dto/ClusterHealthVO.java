package com.flowops.modules.dashboard.dto;

import lombok.Data;

/** 集群健康（CONTRACT §11；nodeOnline/nodeTotal/runningTasks 取 cluster 冗余计数列）。 */
@Data
public class ClusterHealthVO {

    private String clusterId;

    private String clusterName;

    private String status;

    private Integer nodeOnline;

    private Integer nodeTotal;

    private Integer runningTasks;
}
