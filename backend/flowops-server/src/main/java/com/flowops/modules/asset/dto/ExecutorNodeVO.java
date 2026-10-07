package com.flowops.modules.asset.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 执行节点出参（docs/07 §8.1；字段取 docs/05 §3.3 executor_node）。
 *
 * <p><b>只读字段</b>：{@code onlineStatus} / {@code lastHeartbeatAt} / {@code heartbeatMissCount}
 * 由心跳链路（调度器 HeartbeatScanner + /internal/nodes/{id}/heartbeat）维护，接口层只展示，
 * 入参结构里根本没有它们 —— 用类型系统而非文档约定来防误改。</p>
 */
@Data
public class ExecutorNodeVO {

    private String executorNodeId;
    private String executorNodeName;

    /** 业务编号 CL-0001（服务层填充） */
    private String clusterId;

    /** 集群名（服务层填充） */
    private String clusterName;

    private String ip;
    private String osType;
    private String connectType;

    /** 引用的凭据**业务编号**（CR-xxxx）—— 内部 Long 主键不出网，与路由/前端口径一致 */
    private String credentialId;

    /** 引用凭据名（服务层填充；已删除或未设置时为 null） */
    private String credentialName;

    /** 标签约束调度（PRD §12.1） */
    private String[] tags;

    /** ONLINE / OFFLINE / UNKNOWN —— 只读 */
    private String onlineStatus;

    private BigDecimal cpuTotal;
    private BigDecimal cpuUsed;
    private BigDecimal gpuTotal;
    private BigDecimal gpuUsed;
    /** MB */
    private Long memoryTotal;
    private Long memoryUsed;
    /** MB */
    private Long diskTotal;
    private Long diskUsed;

    private Integer runningTaskCount;

    /** 预留账本硬上限（NULL = 不限制） */
    private Integer maxConcurrentSteps;

    private Boolean enabled;

    private OffsetDateTime lastHeartbeatAt;
    private Integer heartbeatMissCount;
    private OffsetDateTime lastAllocatedAt;
    private OffsetDateTime createdAt;
}
