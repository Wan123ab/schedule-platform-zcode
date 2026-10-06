package com.flowops.domain.dto.query;

import lombok.Data;

/** 超时步骤行（docs/06 §8.3：步骤超时扫描）。 */
@Data
public class TimeoutStepRow {

    private Long id;

    private String stepInstanceId;

    private String dispatchToken;

    private String mutexGroup;

    /** machine_ip → 节点反查（uk_node_cluster_ip），账本扣回需要 nodeId */
    private Long nodeId;

    private Long queueId;

    private Integer priority;

    private Long enqueueSeq;
}
