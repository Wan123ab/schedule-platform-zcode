package com.flowops.scheduler.core;

import com.flowops.common.enums.StepStatus;
import com.flowops.domain.dto.query.ActiveTaskRow;
import com.flowops.domain.dto.query.EdgeRow;
import com.flowops.domain.dto.query.StepDefRow;
import com.flowops.domain.dto.query.StepRuntimeRow;
import com.flowops.scheduler.dag.DagAdvancer;
import com.flowops.scheduler.dag.DagGraph;
import com.flowops.scheduler.dag.TaskOutcome;
import com.flowops.scheduler.queue.ReadyQueueManager;
import com.flowops.scheduler.state.StepStateTransitions;
import com.flowops.scheduler.state.TaskStateTransitions;
import com.flowops.common.enums.TaskStatus;
import com.flowops.domain.mapper.task.TaskMapper;
import com.flowops.domain.mapper.task.TaskStepMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 单任务编排器（docs/06 §7.2 推进算法的落库形态）—— 每个活跃任务一份，缓存在管线内存。
 *
 * <p><b>职责</b>：① 每 tick 驱动 DagAdvancer 产出转移命令；② 命令以 CAS 落库
 * （0 行 = 状态已被他方改变，整图作废待重建——宁可重算不可与 DB 脱节）；
 * ③ WAITING_RESOURCE 步骤入就绪队列（score 用原 enqueue_seq，防饥饿）；
 * ④ 全部步骤终态后产出任务级终结判定（§7.3）。</p>
 *
 * <p><b>内存状态生命周期</b>：任务创建时装配、任务终结后由管线移除；CAS 冲突时整图重建
 * （对应恢复路径 §10.2 ⑦ 的同构逻辑）。</p>
 */
@Slf4j
public class TaskOrchestrator {

    @Getter
    private final ActiveTaskRow task;

    /** 图节点 id（workflow_step.id）→ 步骤实例运行时（task_step 行） */
    private final Map<Long, StepRuntimeRow> runtimeByStepNodeId = new HashMap<>();
    /** task_step.id → 节点 id（出队候选反查用） */
    private final Map<Long, Long> stepNodeIdByRowId = new HashMap<>();

    @Getter
    private final DagGraph graph;

    private final DagAdvancer advancer;
    private final ReadyQueueManager readyQueue;
    private final TaskStepMapper taskStepMapper;
    private final TaskMapper taskMapper;

    TaskOrchestrator(ActiveTaskRow task, DagAdvancer advancer, ReadyQueueManager readyQueue,
                     TaskStepMapper taskStepMapper, TaskMapper taskMapper,
                     List<StepDefRow> defs, List<EdgeRow> edges, List<StepRuntimeRow> runtimes) {
        this.task = task;
        this.advancer = advancer;
        this.readyQueue = readyQueue;
        this.taskStepMapper = taskStepMapper;
        this.taskMapper = taskMapper;
        this.graph = DagGraph.of(
                defs.stream().map(d -> new DagGraph.StepDef(d.getStepId(), d.getStepName(), "NOTE".equals(d.getStepType()))).toList(),
                edges.stream().map(e -> new DagGraph.Edge(e.getSourceStepId(), e.getTargetStepId())).toList());
        runtimes.forEach(rt -> {
            runtimeByStepNodeId.put(rt.getStepId(), rt);
            stepNodeIdByRowId.put(rt.getId(), rt.getStepId());
            // §10.2 ⑦：重建内存图时，把已完成上游的 consumed 语义初始化 ——
            // 已处于终态的步骤直接标记消费，避免其下游入度被误减
            StepStatus status = StepStatus.of(rt.getStatus());
            if (status == StepStatus.SUCCESS) {
                graph.setStatus(rt.getStepId(), StepStatus.SUCCESS);
                graph.consumeIfNotYet(rt.getStepId());
            } else {
                graph.setStatus(rt.getStepId(), status);
            }
        });
    }

    /**
     * 推进一轮：DAG 决策 → CAS 落库 → WAITING_RESOURCE 入队。
     *
     * @return false = CAS 冲突（图与 DB 脱节），调用方应丢弃本编排器待下 tick 重建
     */
    public boolean advance() {
        for (DagAdvancer.Transition cmd : advancer.advance(graph)) {
            StepRuntimeRow runtime = runtimeByStepNodeId.get(cmd.stepId());
            // 画布节点与实例行的防御性对齐（理论上任务创建时一一建齐）
            if (runtime == null) {
                log.warn("步骤实例缺失 task={} stepNodeId={}（图与实例不同构，跳过该命令）",
                        task.getTaskId(), cmd.stepId());
                continue;
            }
            if (!StepStateTransitions.canTransition(cmd.from(), cmd.to())) {
                continue;   // 与图状态不一致的指令（理论上 emit 已校验，双保险）
            }
            if (taskStepMapper.casTransition(runtime.getId(), cmd.from().name(), cmd.to().name()) == 0) {
                log.warn("CAS 冲突 task={} step={} {}→{}（他方已变，本图作废待重建）",
                        task.getTaskId(), runtime.getStepInstanceId(), cmd.from(), cmd.to());
                return false;
            }
            // 图与实例行两份运行时视图同步推进（finishIfDone 读实例行状态）
            runtime.setStatus(cmd.to().name());
            if (cmd.to() == StepStatus.WAITING_RESOURCE) {
                // 入队 score 用原 enqueue_seq —— 回退/重入队不重新发号（§4.5 防饥饿）
                readyQueue.enqueue(task.getQueueId(), runtime.getId(),
                        task.getPriority(), runtime.getEnqueueSeq());
            }
        }
        return true;
    }

    /**
     * 任务级终结判定（§7.3）：全部可执行步骤终态时给出任务终态，CAS 落库。
     * SUCCESS 等终态不可回退 —— casStatus 的 from 条件天然挡住重复收敛。
     */
    public Optional<TaskStatus> finishIfDone() {
        Optional<TaskStatus> outcome = TaskOutcome.evaluate(graph.executableStepIds().stream()
                .map(id -> runtimeByStepNodeId.get(id))
                .filter(java.util.Objects::nonNull)
                .map(rt -> StepStatus.of(rt.getStatus()))
                .toList());
        if (outcome.isEmpty()) {
            return Optional.empty();
        }
        TaskStatus target = outcome.get();
        // 当前 DB 状态可能停在 RUNNING（正常）或 SCHEDULING（全部步骤瞬间终态的边界）；
        // 逐个尝试合法来源态，终态不可回退由 CAS 的 from 条件保证
        for (TaskStatus from : List.of(TaskStatus.RUNNING, TaskStatus.SCHEDULING)) {
            if (TaskStateTransitions.canTransition(from, target)
                    && taskMapper.casStatus(task.getId(), from.name(), target.name()) > 0) {
                log.info("任务收敛 task={} → {}", task.getTaskId(), target);
                return Optional.of(target);
            }
        }
        return Optional.empty();
    }

    public StepRuntimeRow runtimeOfRow(long taskStepRowId) {
        Long stepNodeId = stepNodeIdByRowId.get(taskStepRowId);
        return stepNodeId != null ? runtimeByStepNodeId.get(stepNodeId) : null;
    }

    /** 当前处于 WAITING_RESOURCE 的实例行（管线派发阶段构建候选反查表用）。 */
    public List<StepRuntimeRow> pendingWaitingRows() {
        return runtimeByStepNodeId.values().stream()
                .filter(rt -> StepStatus.WAITING_RESOURCE.name().equals(rt.getStatus()))
                .toList();
    }

    /** 图节点 id → 步骤名（诊断与下发指令展示用）。 */
    public String stepNameOf(Long stepNodeId) {
        return graph.nameOf(stepNodeId);
    }

    /**
     * 外部状态转移（执行回执驱动：RUNNING → SUCCESS/FAILED/RETRYING/...，M1 执行链的接入口）。
     * 与 {@link #advance()} 同构：CAS 落库 → 图与实例行双视图同步 → SUCCESS 向下游传播消费。
     * 回执乱序/重复（at-least-once）由 CAS 的 from 条件天然去重。
     */
    public void applyExternalTransition(long taskStepRowId, StepStatus to) {
        StepRuntimeRow runtime = runtimeOfRow(taskStepRowId);
        if (runtime == null) {
            return;
        }
        StepStatus from = StepStatus.of(runtime.getStatus());
        if (!StepStateTransitions.canTransition(from, to)
                || taskStepMapper.casTransition(taskStepRowId, from.name(), to.name()) == 0) {
            return;
        }
        runtime.setStatus(to.name());
        graph.setStatus(runtime.getStepId(), to);
        if (to == StepStatus.SUCCESS) {
            graph.consumeIfNotYet(runtime.getStepId());
        }
    }

    /**
     * 重试登记（docs/06 §9.1）：RUNNING → RETRYING，next_retry_at 落库 + retry_count 累加。
     * 与通用 CAS 分开：RETRYING 附带 next_retry_at / fail_reason 三列，一条 SQL 原子完成。
     */
    public boolean applyRetrying(long taskStepRowId, OffsetDateTime nextRetryAt, String failReason) {
        StepRuntimeRow runtime = runtimeOfRow(taskStepRowId);
        if (runtime == null || taskStepMapper.markRetrying(taskStepRowId, nextRetryAt, failReason) == 0) {
            return false;
        }
        runtime.setStatus(StepStatus.RETRYING.name());
        if (runtime.getRetryCount() != null) {
            runtime.setRetryCount(runtime.getRetryCount() + 1);
        }
        graph.setStatus(runtime.getStepId(), StepStatus.RETRYING);
        return true;
    }
}
