package com.flowops.scheduler.dag;

import com.flowops.common.enums.StepStatus;
import com.flowops.common.util.OrderedCollections;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DAG 图的内存表示（docs/06 §7.1）—— 单个任务实例内的步骤拓扑 + 运行时视图。
 *
 * <p><b>设计思路</b>：</p>
 * <ul>
 *   <li><b>拓扑不可变，运行时可变</b>：步骤/边集合在任务创建时一次性加载（docs/06 §7.1 加载时机），
 *       之后只读；入度余量、步骤状态、consumed 标记是运行时视图，由 DagAdvancer 推进。</li>
 *   <li><b>NOTE 步骤不进图</b>：PRD §10.8 规则 1"备注除外"——备注节点不参与执行、不参与终结判定，
 *       在构建期即剔除，后续所有逻辑无需再判。</li>
 *   <li><b>构建期拒绝环</b>（docs/06 §16：含环应拒绝）：Kahn 拓扑排序，剩余节点即环成员。
 *       环在发布校验（规则 5）就该被拦，这里是调度侧的最后一道防线。</li>
 *   <li><b>consumed 标记保证入度递减恰好一次</b>（docs/06 §7.2）：若同一 SUCCESS 步骤被重复处理
 *       （如状态落库成功但 tick 提前返回），入度会被多减导致下游提前触发。标记是任务级内存态，不需持久化。</li>
 * </ul>
 *
 * <p><b>加载时机与生命周期</b>：任务创建时从 workflow_step + workflow_edge 加载，缓存在调度器内存
 * （Map&lt;taskId, DagGraph&gt;），任务完成后移除（docs/06 §7.1——500 任务 × 10 步 = 5000 节点，
 * 无需持久化缓存）。CAS 落库失败时由调用方整图重建（对应恢复路径，docs/06 §10.2 ⑦）。</p>
 */
public final class DagGraph {

    /** 步骤定义（拓扑输入，与 DB 行解耦，便于单测与未来从不同来源装配）。 */
    public record StepDef(Long stepId, String stepName, boolean note) {}

    /** 连线定义（source → target，上游 → 下游）。 */
    public record Edge(Long source, Long target) {}

    private final Map<Long, String> stepNames;
    private final Map<Long, List<Long>> successors;
    private final Map<Long, Set<Long>> predecessors;
    /** 剩余未完成上游数（运行时可变；docs/06 §7.1 inDegree） */
    private final Map<Long, Integer> remainingInDegree;
    private final Map<Long, StepStatus> statuses;
    /** 入度 0 的可执行步骤（NOTE 已剔除） */
    private final Set<Long> roots;
    /** 已消费完成事件的步骤（§7.2 consumed：入度递减恰好一次） */
    private final Set<Long> consumed;

    private DagGraph(Map<Long, String> stepNames,
                     Map<Long, List<Long>> successors,
                     Map<Long, Set<Long>> predecessors,
                     Map<Long, Integer> remainingInDegree,
                     Set<Long> roots) {
        this.stepNames = stepNames;
        this.successors = successors;
        this.predecessors = predecessors;
        this.remainingInDegree = remainingInDegree;
        this.roots = roots;
        this.statuses = new HashMap<>();
        this.consumed = new HashSet<>();
        stepNames.keySet().forEach(id -> statuses.put(id, StepStatus.NOT_STARTED));
    }

    /** 构建图：剔除 NOTE、校验自环/悬挂边、Kahn 拒环。 */
    public static DagGraph of(List<StepDef> steps, List<Edge> edges) {
        Map<Long, String> names = new HashMap<>();
        for (StepDef step : steps) {
            if (!step.note()) {
                names.put(step.stepId(), step.stepName());
            }
        }
        for (Edge edge : edges) {
            if (!names.containsKey(edge.source()) || !names.containsKey(edge.target())) {
                throw new IllegalArgumentException(
                        "连线引用了不存在的可执行步骤: " + edge + "（NOTE 步骤不参与连线，PRD §10.8 规则 1）");
            }
            if (edge.source().equals(edge.target())) {
                throw new IllegalArgumentException(
                        "自环: 步骤 " + names.get(edge.source()) + "（ck_edge_no_self_loop）");
            }
        }

        Map<Long, List<Long>> succ = new HashMap<>();
        Map<Long, Set<Long>> pred = new HashMap<>();
        Map<Long, Integer> inDegree = new HashMap<>();
        names.keySet().forEach(id -> {
            succ.put(id, new ArrayList<>());
            pred.put(id, new HashSet<>());
            inDegree.put(id, 0);
        });
        for (Edge edge : edges) {
            succ.get(edge.source()).add(edge.target());
            pred.get(edge.target()).add(edge.source());
            inDegree.merge(edge.target(), 1, Integer::sum);
        }

        rejectCycles(names, succ, inDegree);

        Set<Long> roots = new HashSet<>();
        inDegree.forEach((id, d) -> {
            if (d == 0) {
                roots.add(id);
            }
        });
        return new DagGraph(names, succ, pred, inDegree, roots);
    }

    /** Kahn 拓扑排序：处理不完的节点就是环成员（docs/03 §4.3 规则 5 实现要点）。 */
    private static void rejectCycles(Map<Long, String> names, Map<Long, List<Long>> succ, Map<Long, Integer> inDegree) {
        Map<Long, Integer> pending = new HashMap<>(inDegree);
        Deque<Long> zero = new ArrayDeque<>();
        pending.forEach((id, d) -> {
            if (d == 0) {
                zero.add(id);
            }
        });
        int processed = 0;
        while (!zero.isEmpty()) {
            Long id = zero.pop();
            processed++;
            for (Long next : succ.get(id)) {
                if (pending.merge(next, -1, Integer::sum) == 0) {
                    zero.add(next);
                }
            }
        }
        if (processed < names.size()) {
            List<String> cycleMembers = pending.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .map(e -> names.get(e.getKey()))
                    .sorted()
                    .toList();
            throw new IllegalArgumentException("检测到循环依赖：" + String.join(" → ", cycleMembers));
        }
    }

    // ── 只读查询（DagAdvancer 的判定输入）──────────────────────

    /** 尚未到终态的可执行步骤（每 tick 的处理对象，docs/06 §7.2）。 */
    public Set<Long> pendingSteps() {
        Set<Long> pending = new HashSet<>();
        statuses.forEach((id, s) -> {
            if (!s.isFinal()) {
                pending.add(id);
            }
        });
        return pending;
    }

    /** 剩余未完成上游数（NOT_STARTED 阶段判定"能否直接调度"）。 */
    public int inDegree(Long stepId) {
        return remainingInDegree.getOrDefault(stepId, 0);
    }

    /** 是否入度 0（roots 会被 consumed 之外的机制改变吗？不会——入度只减不增）。 */
    public boolean isRoot(Long stepId) {
        return roots.contains(stepId);
    }

    /** 全部上游 SUCCESS（WAITING_DEPENDENCY → WAITING_RESOURCE 的判定，docs/06 §2.2）。 */
    public boolean allUpstreamSuccess(Long stepId) {
        return predecessors.get(stepId).stream()
                .allMatch(up -> statuses.get(up) == StepStatus.SUCCESS);
    }

    /** 任一上游最终 FAILED（策略语义见 DagAdvancer：RETRY 耗尽后同 TERMINATE，docs/06 §9.2）。 */
    public boolean anyUpstreamFailed(Long stepId) {
        return predecessors.get(stepId).stream()
                .anyMatch(up -> statuses.get(up) == StepStatus.FAILED);
    }

    public List<Long> successors(Long stepId) {
        return List.copyOf(successors.get(stepId));
    }

    public StepStatus statusOf(Long stepId) {
        return statuses.get(stepId);
    }

    public String nameOf(Long stepId) {
        return stepNames.get(stepId);
    }

    public boolean consumed(Long stepId) {
        return consumed.contains(stepId);
    }

    /**
     * 可执行（非 NOTE）步骤集合。
     *
     * <p>返回保序副本：当前的唯一调用方只做 stream 求值，顺序无关；但这是把内部键集
     * 交给外部的公共出口，一旦有人拿它去渲染/落库/拼诊断消息，{@code Set.copyOf}
     * 那种随机迭代顺序会让结果不可复现（见 {@code OrderedCollections}）。</p>
     */
    public Set<Long> executableStepIds() {
        return OrderedCollections.orderedSet(stepNames.keySet());
    }

    // ── 运行时推进（仅 DagAdvancer 调用）────────────────────────

    /** 更新步骤运行时状态（DB 侧由调用方以 CAS 落库，docs/06 §4.3）。 */
    public void setStatus(Long stepId, StepStatus status) {
        statuses.put(stepId, status);
    }

    /** SUCCESS 步骤向下游传播：递减入度（恰好一次），返回受影响的下游。 */
    public List<Long> consumeIfNotYet(Long stepId) {
        if (!consumed.add(stepId)) {
            return List.of();   // 已消费：幂等返回（§7.2 consumed 标记的必要性）
        }
        List<Long> affected = successors.get(stepId);
        affected.forEach(next -> remainingInDegree.merge(next, -1, Integer::sum));
        return affected;
    }
}
