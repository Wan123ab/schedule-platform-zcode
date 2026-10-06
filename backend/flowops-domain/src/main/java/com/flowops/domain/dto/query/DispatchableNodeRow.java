package com.flowops.domain.dto.query;

import lombok.Data;

/**
 * 可派发节点行（NodeMatcher 输入投影，docs/05 §3.3 executor_node）。
 * tags 以 PG text[] 原样返回（形如 "{gpu,cpu}"），解析由 scheduler 侧完成 ——
 * 避免 MyBatis 数组 TypeHandler 的额外依赖面。
 */
@Data
public class DispatchableNodeRow {

    private Long id;
    private String nodeName;
    /** 连接 IP（SSH 下发目标，M1 执行链使用） */
    private String ip;
    private Long clusterId;
    private String onlineStatus;
    private String osType;
    private String tags;
    private Long credentialRefId;
    private Double cpuTotal;
    private Double gpuTotal;
    private Long memoryTotal;
    private Long diskTotal;
    private Integer runningTaskCount;
    private java.time.OffsetDateTime lastAllocatedAt;
    private Integer maxConcurrentSteps;
}
