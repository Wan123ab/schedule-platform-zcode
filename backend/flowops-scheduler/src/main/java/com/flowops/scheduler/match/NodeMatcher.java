package com.flowops.scheduler.match;

import lombok.Value;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 节点选择器（G3，docs/06 §5）。
 *
 * <p><b>两级结构</b>：先硬过滤（§5.1 候选集，一条不满足即出局），
 * 再三级排序（§5.2 G3，<b>明确排除随机</b>）：</p>
 * <ol>
 *   <li>运行任务数最少 —— 均摊负载；</li>
 *   <li>剩余 CPU 最多（余量按预留账本口径）—— 资源偏好；</li>
 *   <li>最近分配时间最早 —— 冷节点先用；null 视为最早，新节点立即参与负载。</li>
 * </ol>
 *
 * <p><b>准入余量唯一依据是预留账本</b>（D-22）：节点心跳上报的实际用量只做画像与偏差告警，
 * 本类根本不接触它 —— 这是防止"峰值滞后超卖"的结构性保证（见 ReservedLedger 类注释）。</p>
 */
@Value
public class NodeMatcher {

    /** 步骤的资源与约束需求（来自继承链解析后的 workflow_step）。 */
    @Value
    @lombok.Builder
    public static class Demand {
        ReservedLedger.Resource request;
        /** LINUX/WINDOWS/null = 不限 */
        String osConstraint;
        Set<String> tagConstraint;
        /** 亲和锚定集群（§5.3 ①：由调用方从任务内已完成/运行中步骤汇聚；null = 不限集群） */
        Long targetClusterId;
        boolean clusterAffinityEnabled;
    }

    /** 匹配结果：选中节点 + 亲和是否被打破（打破必须记录原因进诊断，docs/06 §5.3）。 */
    @Value
    public static class MatchResult {
        NodeView node;
        boolean affinityBroken;
        String affinityBrokenReason;
    }

    private final ReservedLedger ledger;

    public Optional<MatchResult> match(Demand demand, List<NodeView> nodes) {
        // 亲和优先（§5.3 ②）：任务已落在某集群 → 优先在同集群内匹配
        Optional<MatchResult> pinned = tryMatch(demand, nodes, pinnedClusters(demand));
        if (pinned.isPresent()) {
            return pinned;
        }
        if (canBreakAffinity(demand)) {
            // 亲和不可满足 → 跨集群匹配（允许），但必须留下"为什么跨了"的记录（PRD §12.2）
            return tryMatch(demand, nodes, null).map(node ->
                    new MatchResult(node.getNode(), true,
                            "亲和集群 " + demand.getTargetClusterId() + " 无满足约束的节点，已回退跨集群"));
        }
        return Optional.empty();
    }

    private boolean canBreakAffinity(Demand demand) {
        return demand.isClusterAffinityEnabled() && demand.getTargetClusterId() != null;
    }

    /** 亲和锁定集合：亲和开启时 = [targetClusterId]；未开启 = null（不限）。 */
    private static Set<Long> pinnedClusters(Demand demand) {
        if (!demand.isClusterAffinityEnabled() || demand.getTargetClusterId() == null) {
            return null;
        }
        return Set.of(demand.getTargetClusterId());
    }

    private Optional<MatchResult> tryMatch(Demand demand, List<NodeView> nodes, Set<Long> clusterPin) {
        return nodes.stream()
                .filter(NodeView::isEnabled)
                .filter(NodeView::online)                              // 在线（G3 硬约束）
                .filter(NodeView::credentialReady)                  // 凭据有效
                .filter(n -> clusterPin == null || clusterPin.contains(n.getClusterId()))
                .filter(n -> osMatch(n, demand.getOsConstraint()))
                .filter(n -> tagsMatch(n, demand.getTagConstraint()))
                .filter(n -> stepsQuotaLeft(n))                        // max_concurrent_steps 闸门
                .filter(n -> resourcesFit(n, demand.getRequest()))        // 预留账本余量（D-22）
                // 三级排序（§5.2）：任务数升序 → 余 CPU 降序 → 分配时间最早优先（nullsFirst）
                .min(Comparator
                        .comparingInt(NodeView::getRunningTaskCount)
                        .thenComparing(m -> remainingCpu(m, demand), Comparator.reverseOrder())
                        .thenComparing(NodeView::getLastAllocatedAt,
                                Comparator.nullsFirst(Comparator.<java.time.OffsetDateTime>naturalOrder())))
                .map(node -> new MatchResult(node, false, null));
    }

    private boolean osMatch(NodeView node, String osConstraint) {
        return osConstraint == null || node.getOsType().equals(osConstraint);
    }

    private boolean tagsMatch(NodeView node, Set<String> required) {
        return required == null || node.getTags().containsAll(required);
    }

    /** 步骤数闸门：max_concurrent_steps = null 表示不限制（防"进程数/句柄数"类非资源瓶颈，docs/05 v3）。 */
    private boolean stepsQuotaLeft(NodeView node) {
        return node.getMaxConcurrentSteps() == null
                || node.getReservedSteps() < node.getMaxConcurrentSteps();
    }

    /** 余量判定：>= 恰好满足也算匹配（docs/06 §16 边界用例 1）。 */
    private boolean resourcesFit(NodeView node, ReservedLedger.Resource request) {
        return ledger.availableOf(node.getNodeId(), node.getTotals()).fits(request);
    }

    private double remainingCpu(NodeView node, Demand demand) {
        return ledger.availableOf(node.getNodeId(), node.getTotals()).cpu();
    }
}
