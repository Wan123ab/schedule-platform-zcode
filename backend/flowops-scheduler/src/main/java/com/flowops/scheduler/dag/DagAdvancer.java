package com.flowops.scheduler.dag;

import com.flowops.common.enums.StepStatus;
import com.flowops.scheduler.state.StepStateTransitions;
import com.flowops.scheduler.state.StepStateTransitions.StepEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * DAG 推进器（docs/06 §7.2）—— 每 tick 对每个活跃任务执行一次的<b>纯决策引擎</b>。
 *
 * <p><b>设计思路</b>：推进器只回答"哪些步骤该从什么状态转移到什么状态"，不碰 DB、不碰 Redis。
 * 它产出带事件依据的转移命令，由调用方（M1 的调度闭环接线层）以 CAS 落库
 * （{@code UPDATE task_step SET status=:to WHERE status=:from AND version=:v}，docs/03 §3.4）。</p>
 *
 * <p><b>图内状态与 DB 的先后关系</b>：命令产出时图内状态同步推进——这样同一 tick 内
 * "步骤 A 成功 → 下游 B 的 WAITING_DEPENDENCY 判定"立即生效（docs/06 §7.2 伪码同构）；
 * 若 CAS 落库失败（状态已被他方改变），调用方放弃本批命令并整图重建（恢复路径，docs/06 §10.2 ⑦）——
 * 宁可重算，不可与 DB 脱节。</p>
 *
 * <p><b>终止语义</b>：上游 FAILED → 下游 STOPPED，对 TERMINATE 与 RETRY 两种策略同样成立——
 * RETRY 耗尽后上游才会是 FAILED（重试期上游是 RETRYING，anyUpstreamFailed 为 false），
 * 等价于 docs/06 §9.2"RETRY 耗尽后等同于 TERMINATE"。</p>
 */
public class DagAdvancer {

    /** 转移命令：调用方据此 CAS 落库；event 留作写 schedule_decision_log 的 reason 依据。 */
    public record Transition(Long stepId, StepStatus from, StepStatus to, StepEvent event) {}

    /**
     * 推进一轮：返回本 tick 应落库的转移命令列表（可能为空）。
     * 副作用仅限图内运行时视图（statuses / 入度 / consumed）。
     */
    public List<Transition> advance(DagGraph graph) {
        List<Transition> commands = new ArrayList<>();
        // 快照后遍历：advance 过程中可能改变 pending 集合（状态推进），避免 ConcurrentModification
        for (Long stepId : List.copyOf(graph.pendingSteps())) {
            StepStatus current = graph.statusOf(stepId);
            switch (current) {
                case NOT_STARTED -> collectInitialTransition(graph, stepId, commands);
                case WAITING_DEPENDENCY -> collectDependencyResolution(graph, stepId, commands);
                case SUCCESS -> graph.consumeIfNotYet(stepId);   // 上游完成 → 递减下游入度（恰好一次）
                default -> { /* RUNNING/RETRYING/WAITING_RESOURCE 由生命周期扫描器与队列管理器接管（§3.2 阶段④~⑨） */ }
            }
        }
        return commands;
    }

    /** §7.2：NOT_STARTED 按入度分流 —— 0 直达 WAITING_RESOURCE，>0 转 WAITING_DEPENDENCY。 */
    private void collectInitialTransition(DagGraph graph, Long stepId, List<Transition> commands) {
        if (graph.inDegree(stepId) == 0) {
            emit(graph, stepId, StepEvent.DEP_READY, commands);
        } else {
            emit(graph, stepId, StepEvent.DEP_WAIT, commands);
        }
    }

    /** §7.2：WAITING_DEPENDENCY 的两条出路 —— 上游全成功 → 可调度；任一上游最终失败 → STOPPED。 */
    private void collectDependencyResolution(DagGraph graph, Long stepId, List<Transition> commands) {
        if (graph.allUpstreamSuccess(stepId)) {
            emit(graph, stepId, StepEvent.UPSTREAM_DONE, commands);
        } else if (graph.anyUpstreamFailed(stepId)) {
            emit(graph, stepId, StepEvent.UPSTREAM_FAIL, commands);
        }
    }

    private void emit(DagGraph graph, Long stepId, StepEvent event, List<Transition> commands) {
        StepStatus from = graph.statusOf(stepId);
        StepStatus to = event.target();
        // 转移合法性由状态机守卫：推进器与状态机两份知识互相校验，表外流转在这里就会被抓住
        if (!StepStateTransitions.canTransition(from, to)) {
            throw new IllegalStateException(
                    "DAG 推进产出了非法转移: " + from + " → " + to + "（步骤 " + graph.nameOf(stepId) + "）");
        }
        graph.setStatus(stepId, to);
        commands.add(new Transition(stepId, from, to, event));
    }
}
