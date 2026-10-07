package com.flowops.modules.asset.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 队列出参。{@code clusterId} 是<b>业务编号</b>（CL-0001），不是内部主键 ——
 * 前端只认业务编号（与路由 /clusters/:clusterId 一致），内部 Long 主键不出网。
 */
@Data
public class QueueVO {

    private String queueId;
    private String queueName;

    /** 业务编号 CL-0001（服务层填充） */
    private String clusterId;

    /** 集群名（服务层填充，免前端二次请求） */
    private String clusterName;

    private String status;
    private Integer maxConcurrentTasks;
    private Integer maxWaitingTasks;
    private Integer defaultPriority;
    private Boolean allowJumpQueue;
    private Integer waitTimeoutSeconds;
    private Integer waitingTaskCount;
    private OffsetDateTime createdAt;
}
