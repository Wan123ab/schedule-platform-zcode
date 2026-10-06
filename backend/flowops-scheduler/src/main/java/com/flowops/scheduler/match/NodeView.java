package com.flowops.scheduler.match;

import lombok.Builder;
import lombok.Value;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * 节点匹配的输入视图（docs/05 §3.3 executor_node 投影 + 账本余量）。
 * {@code @Value}（不可变）+ Builder：匹配过程无副作用，选错节点不可归咎于中间态。
 */
@Value
@Builder
public class NodeView {

    long nodeId;
    String nodeName;
    /** SSH/WinRM 连接地址 */
    String machineIp;
    long clusterId;

    boolean enabled;
    /** ONLINE / OFFLINE / UNKNOWN */
    String onlineStatus;
    /** 凭据有效（credential_ref_id 非空且未失效——一期凭据有效性由 credential.status 保证） */
    boolean hasValidCredential;

    /** LINUX / WINDOWS */
    String osType;
    Set<String> tags;

    /** 资源总量（D-22：余量 = total − 账本 reserved，actual 不参与） */
    ReservedLedger.Resource totals;

    /** 三级排序键 ①：运行任务数（PRD §12.2） */
    int runningTaskCount;
    /** 三级排序键 ③：最近分配时间（null = 从未分配，视为最早——"冷节点先用"） */
    OffsetDateTime lastAllocatedAt;

    /** 最大并发步骤数（NULL = 不限制，docs/05 §3.3 v3 新增列） */
    Integer maxConcurrentSteps;

    /** 节点当前已预留步骤数（ReservedLedger.reservedSteps 注入） */
    int reservedSteps;

    public boolean online() {
        return "ONLINE".equals(onlineStatus);
    }
}
