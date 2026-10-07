package com.flowops.domain.entity.asset;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.flowops.domain.mybatis.StringArrayTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 执行节点（docs/05 §3.3 executor_node，V0.2 术语：<b>不叫 node</b>）。
 *
 * <p><b>三个关键字段的读法</b>：</p>
 * <ul>
 *   <li>{@code online_status}：由心跳链路维护（{@code HeartbeatScanner} 三段阈值 15s/45s/5min），
 *       接口层<b>只读</b>，不允许人工改写；</li>
 *   <li>{@code cpu_used}/{@code memory_used} 等「实际用量」：只用于资源画像与偏差告警，
 *       <b>不参与准入判定</b>（D-22 用预留账本），故本实体允许它们与 reserved 不一致；</li>
 *   <li>{@code max_concurrent_steps}：资源之外的显式步骤数闸门（进程数/句柄数瓶颈），NULL = 不限制。</li>
 * </ul>
 *
 * <p>{@code autoResultMap = true} 是 {@code tags text[]} 与心跳 JSONB 类字段可读的前提（见 StringArrayTypeHandler）。</p>
 */
@Data
@TableName(value = "executor_node", autoResultMap = true)
public class ExecutorNode {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务编号 EN-0001；路由上的 {nodeId} 即此字段 */
    private String executorNodeId;

    private String executorNodeName;

    /** FK cluster.id（R5：节点不可跨集群） */
    private Long clusterId;

    private String ip;

    /** LINUX / WINDOWS（Q-02：一期 Windows 仅登记展示，不支持远程执行） */
    private String osType;

    /** SSH / WINRM / AGENT（Q-01：一期以 SSH 为主） */
    private String connectType;

    /** FK credential.id（R6：ON DELETE RESTRICT 双保险） */
    private Long credentialRefId;

    /** 标签约束调度（PRD §12.1）；PG text[] */
    @TableField(typeHandler = StringArrayTypeHandler.class)
    private String[] tags;

    /** ONLINE / OFFLINE / UNKNOWN —— 心跳链路维护，接口只读 */
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

    /** 预留账本的硬上限（06 §5.1），NULL = 不限制 */
    private Integer maxConcurrentSteps;

    private Boolean enabled;

    private OffsetDateTime lastHeartbeatAt;

    private Integer heartbeatMissCount;

    /** 节点选择第 3 排序键（PRD §12.2） */
    private OffsetDateTime lastAllocatedAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;

    @Version
    private Integer version;

    private Boolean deleted;
}
