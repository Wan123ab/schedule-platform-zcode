package com.flowops.domain.dto.query;

import lombok.Data;

/** 刚被标记离线的执行节点行（M4-S4：NODE_OFFLINE 告警事件源，docs/06 §8.1 ②）。 */
@Data
public class OfflineNodeRow {

    /** executor_node 行 id（alert_record.target_id） */
    private Long id;

    private String executorNodeId;

    private String executorNodeName;

    private Long clusterId;

    /** 节点所属集群名（content 用；join 出） */
    private String clusterName;
}
