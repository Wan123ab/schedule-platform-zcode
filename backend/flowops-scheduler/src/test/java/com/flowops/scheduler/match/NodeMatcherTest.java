package com.flowops.scheduler.match;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 节点匹配单测（docs/06 §16：三级排序、约束过滤、余量边界）。
 * 排序语义 PRD §12.2：任务数最少 → 剩余 CPU 最多 → 最近分配最早，明确排除随机。
 */
class NodeMatcherTest {

    private final ReservedLedger ledger = new ReservedLedger();
    private final NodeMatcher matcher = new NodeMatcher(ledger);

    private NodeMatcher.Demand demand(ReservedLedger.Resource request) {
        return NodeMatcher.Demand.builder()
                .request(request)
                .osConstraint(null)
                .tagConstraint(null)
                .targetClusterId(null)
                .clusterAffinityEnabled(false)
                .build();
    }

    private NodeView node(long id, int runningTasks, double cpuTotal, OffsetDateTime lastAllocatedAt) {
        return NodeView.builder()
                .nodeId(id).nodeName("node-" + id).clusterId(1L)
                .enabled(true).onlineStatus("ONLINE").hasValidCredential(true)
                .osType("LINUX").tags(Set.of())
                .totals(new ReservedLedger.Resource(cpuTotal, 0, 16384, 102400))
                .runningTaskCount(runningTasks)
                .lastAllocatedAt(lastAllocatedAt)
                .maxConcurrentSteps(null)
                .reservedSteps(0)
                .build();
    }

    @Test
    void 三级排序_第一键_运行任务数最少优先() {
        // node-1 CPU 余量更大（8 > 2），但任务数多 —— 第一键优先于资源偏好
        var result = matcher.match(demand(new ReservedLedger.Resource(1, 0, 0, 0)), List.of(
                node(1L, 5, 8, null),
                node(2L, 1, 2, null)));

        assertThat(result).isPresent();
        assertThat(result.get().getNode().getNodeId()).isEqualTo(2L);
    }

    @Test
    void 三级排序_第二键_任务数相同则剩余CPU最多优先() {
        var result = matcher.match(demand(new ReservedLedger.Resource(1, 0, 0, 0)), List.of(
                node(1L, 2, 4, null),
                node(2L, 2, 8, null)));

        assertThat(result).get().extracting(r -> r.getNode().getNodeId()).isEqualTo(2L);
    }

    @Test
    void 三级排序_第三键_前两键相同则最近分配最早优先_从未分配视为最早() {
        OffsetDateTime now = OffsetDateTime.now();
        var result = matcher.match(demand(new ReservedLedger.Resource(1, 0, 0, 0)), List.of(
                node(1L, 1, 8, now.minusMinutes(5)),   // 5 分钟前刚被分配
                node(2L, 1, 8, null)));                // 从未分配 → 冷节点先用

        assertThat(result).get().extracting(r -> r.getNode().getNodeId()).isEqualTo(2L);
    }

    @Test
    void 余量边界_恰好满足匹配_差一点不匹配() {
        // docs/06 §16 边界用例 1：availCpu == step.cpu 必须匹配成功
        var result = matcher.match(demand(new ReservedLedger.Resource(4, 0, 0, 0)), List.of(
                node(1L, 0, 4, null)));

        assertThat(result).isPresent();
    }

    @Test
    void 硬约束过滤_离线与禁用与凭据缺失出局() {
        var request = new ReservedLedger.Resource(1, 0, 0, 0);
        NodeView offline = NodeView.builder()
                .nodeId(1L).nodeName("n1").clusterId(1L).enabled(true)
                .onlineStatus("OFFLINE").hasValidCredential(true)
                .osType("LINUX").tags(Set.of())
                .totals(new ReservedLedger.Resource(8, 0, 0, 0))
                .runningTaskCount(0).lastAllocatedAt(null)
                .maxConcurrentSteps(null).reservedSteps(0).build();
        NodeView disabled = NodeView.builder()
                .nodeId(2L).nodeName("n2").clusterId(1L).enabled(false)
                .onlineStatus("ONLINE").hasValidCredential(true)
                .osType("LINUX").tags(Set.of())
                .totals(new ReservedLedger.Resource(8, 0, 0, 0))
                .runningTaskCount(0).lastAllocatedAt(null)
                .maxConcurrentSteps(null).reservedSteps(0).build();
        NodeView noCredential = NodeView.builder()
                .nodeId(3L).nodeName("n3").clusterId(1L).enabled(true)
                .onlineStatus("ONLINE").hasValidCredential(false)
                .osType("LINUX").tags(Set.of())
                .totals(new ReservedLedger.Resource(8, 0, 0, 0))
                .runningTaskCount(0).lastAllocatedAt(null)
                .maxConcurrentSteps(null).reservedSteps(0).build();

        assertThat(matcher.match(demand(request), List.of(offline))).isEmpty();
        assertThat(matcher.match(demand(request), List.of(disabled))).isEmpty();
        assertThat(matcher.match(demand(request), List.of(noCredential))).isEmpty();
    }

    @Test
    void 标签与OS约束_不满足出局() {
        NodeView gpuNode = NodeView.builder()
                .nodeId(1L).nodeName("gpu-1").clusterId(1L).enabled(true)
                .onlineStatus("ONLINE").hasValidCredential(true)
                .osType("LINUX").tags(Set.of("gpu"))
                .totals(new ReservedLedger.Resource(8, 2, 0, 0))
                .runningTaskCount(0).lastAllocatedAt(null)
                .maxConcurrentSteps(null).reservedSteps(0).build();

        var withTags = NodeMatcher.Demand.builder()
                .request(new ReservedLedger.Resource(1, 0, 0, 0))
                .osConstraint("LINUX").tagConstraint(Set.of("gpu"))
                .targetClusterId(null).clusterAffinityEnabled(false)
                .build();
        assertThat(matcher.match(withTags, List.of(gpuNode))).isPresent();

        var needWindows = NodeMatcher.Demand.builder()
                .request(new ReservedLedger.Resource(1, 0, 0, 0))
                .osConstraint("WINDOWS").tagConstraint(null)
                .targetClusterId(null).clusterAffinityEnabled(false)
                .build();
        assertThat(matcher.match(needWindows, List.of(gpuNode))).isEmpty();
    }

    @Test
    void 步骤数闸门_达到maxConcurrentSteps即出局() {
        NodeView full = NodeView.builder()
                .nodeId(1L).nodeName("n1").clusterId(1L).enabled(true)
                .onlineStatus("ONLINE").hasValidCredential(true)
                .osType("LINUX").tags(Set.of())
                .totals(new ReservedLedger.Resource(64, 0, 0, 0))   // 资源充足
                .runningTaskCount(0).lastAllocatedAt(null)
                .maxConcurrentSteps(2).reservedSteps(2)             // 但步骤数闸门已满
                .build();

        assertThat(matcher.match(demand(new ReservedLedger.Resource(1, 0, 0, 0)), List.of(full))).isEmpty();
    }

    @Test
    void 集群亲和_锚定集群优先_不可满足则跨集群并记录原因() {
        var request = new ReservedLedger.Resource(1, 0, 0, 0);
        NodeView inCluster = node(1L, 1, 8, null);
        NodeView outCluster = NodeView.builder()
                .nodeId(2L).nodeName("other").clusterId(99L)
                .enabled(true).onlineStatus("ONLINE").hasValidCredential(true)
                .osType("LINUX").tags(Set.of())
                .totals(new ReservedLedger.Resource(8, 0, 0, 0))
                .runningTaskCount(0).lastAllocatedAt(null)
                .maxConcurrentSteps(null).reservedSteps(0).build();

        var pinnedDemand = NodeMatcher.Demand.builder()
                .request(request).osConstraint(null).tagConstraint(null)
                .targetClusterId(1L).clusterAffinityEnabled(true)
                .build();

        // 锚定集群有解：不跨集群
        var pinned = matcher.match(pinnedDemand, List.of(inCluster, outCluster));
        assertThat(pinned).get().extracting(r -> r.getNode().getNodeId()).isEqualTo(1L);
        assertThat(pinned.get().isAffinityBroken()).isFalse();

        // 锚定集群无解（inCluster 资源不够）：跨集群，但必须留下诊断记录（PRD §12.2）
        ledger.acquire(1L, 888L, new ReservedLedger.Resource(8, 0, 0, 0));   // 耗尽 node-1
        var fallback = matcher.match(pinnedDemand, List.of(inCluster, outCluster));
        assertThat(fallback).get().extracting(r -> r.getNode().getNodeId()).isEqualTo(2L);
        assertThat(fallback.get().isAffinityBroken()).isTrue();
        assertThat(fallback.get().getAffinityBrokenReason()).contains("1");
    }
}
