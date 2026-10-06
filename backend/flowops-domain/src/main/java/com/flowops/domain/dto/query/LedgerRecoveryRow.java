package com.flowops.domain.dto.query;

import lombok.Data;

/** 恢复期投影：节点预留账本行（docs/06 §10.2 ④，D-22 口径重建）。 */
@Data
public class LedgerRecoveryRow {

    private Long id;

    private String stepInstanceId;

    /** machine_ip + cluster_id 反查的节点 id（uk_node_cluster_ip） */
    private Long nodeId;

    /** 资源申请 JSON（{cpu,gpu,memory,disk}） */
    private String resourceRequest;
}
