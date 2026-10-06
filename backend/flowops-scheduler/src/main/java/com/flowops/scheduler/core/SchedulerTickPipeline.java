package com.flowops.scheduler.core;

import com.flowops.common.enums.StepStatus;
import com.flowops.common.enums.TaskStatus;
import com.flowops.domain.dto.query.ActiveTaskRow;
import com.flowops.domain.dto.query.DispatchableNodeRow;
import com.flowops.domain.dto.query.QueueConcurrencyConfig;
import com.flowops.domain.dto.query.RetryingStepRow;
import com.flowops.domain.dto.query.StepRuntimeRow;
import com.flowops.domain.mapper.concurrency.ConcurrencyQueryMapper;
import com.flowops.domain.mapper.concurrency.SchedulingQueryMapper;
import com.flowops.domain.mapper.task.RetryQueryMapper;
import com.flowops.domain.mapper.task.TaskMapper;
import com.flowops.domain.mapper.task.TaskStepMapper;
import com.flowops.common.guard.CheckResult;
import com.flowops.common.guard.ConcurrencyGuard;
import com.flowops.scheduler.guard.MutexLockManager;
import com.flowops.scheduler.lifecycle.LifecycleScanner;
import com.flowops.scheduler.lifecycle.ResourceReleaser;
import com.flowops.scheduler.log.LogIngestService;
import com.flowops.scheduler.match.NodeMatcher;
import com.flowops.scheduler.match.NodeView;
import com.flowops.scheduler.match.ReservedLedger;
import com.flowops.scheduler.queue.ReadyQueueManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 调度 tick 管线（docs/06 §3.2 的 M1 子集，阶段顺序不可调换）：
 *
 * <pre>
 *   ① QuotaScanner   PENDING 任务经 ConcurrencyGuard 准入（D-12 唯一入口）→ SCHEDULING
 *   ② DagAdvancer    每个活跃任务推进依赖图，WAITING_RESOURCE 入就绪队列
 *   ③④⑤⑥⑦ Dispatch  出队（freeSlots 闸门 + 两段式 CAS）→ 节点匹配（预留账本）
 *                     → 互斥锁（waiters 唤醒语义由 release 侧承接）→ 交下发槽位
 *   ⑧ Finish         全步骤终态 → TaskOutcome 收敛任务终态
 * </pre>
 *
 * <p><b>幂等性（§3.3）</b>：每个阶段重跑无副作用 —— 准入/推进/终结全走 CAS（0 行即放弃），
 * 出队走两段式占位；账本 acquire/release 幂等。下发经由 dispatch_token，重复 claim 不重发。</p>
 *
 * <p><b>单线程契约</b>：本类不是线程安全的，只允许调度主循环（单线程 tick）调用 ——
 * 这是 docs/06 §3.1 一致性视图的根基，切勿并行化。</p>
 */
@Slf4j
public class SchedulerTickPipeline {

    /** 互斥锁 holder TTL：max(步骤超时, 1h) × 1.5 的 M1 简化取值（步骤超时解析链在 M4 完整落地）。 */
    private static final Duration MUTEX_HOLDER_TTL = Duration.ofMinutes(90);

    private final SchedulingQueryMapper schedulingQuery;
    private final ConcurrencyQueryMapper concurrencyQuery;
    private final TaskMapper taskMapper;
    private final TaskStepMapper taskStepMapper;
    private final ConcurrencyGuard concurrencyGuard;
    private final ReadyQueueManager readyQueue;
    private final NodeMatcher nodeMatcher;
    private final ReservedLedger ledger;
    private final MutexLockManager mutexes;
    private final DispatchSink dispatchSink;
    private final CompletionBus completionBus;
    private final RetryQueryMapper retryQuery;
    private final LogIngestService logIngest;
    private final LifecycleScanner lifecycleScanner;
    private final ResourceReleaser releaser;
    private final DagAdvancer advancer = new DagAdvancer();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 任务编排器缓存：taskId → orchestrator（任务终结/冲突时移除）。 */
    private final Map<Long, TaskOrchestrator> orchestrators = new HashMap<>();

    public SchedulerTickPipeline(SchedulingQueryMapper schedulingQuery,
                                 ConcurrencyQueryMapper concurrencyQuery,
                                 TaskMapper taskMapper, TaskStepMapper taskStepMapper,
                                 ConcurrencyGuard concurrencyGuard,
                                 ReadyQueueManager readyQueue, NodeMatcher nodeMatcher,
                                 ReservedLedger ledger, MutexLockManager mutexes,
                                 DispatchSink dispatchSink, CompletionBus completionBus,
                                 RetryQueryMapper retryQuery, LogIngestService logIngest,
                                 LifecycleScanner lifecycleScanner, ResourceReleaser releaser) {
        this.schedulingQuery = schedulingQuery;
        this.concurrencyQuery = concurrencyQuery;
        this.taskMapper = taskMapper;
        this.taskStepMapper = taskStepMapper;
        this.concurrencyGuard = concurrencyGuard;
        this.readyQueue = readyQueue;
        this.nodeMatcher = nodeMatcher;
        this.ledger = ledger;
        this.mutexes = mutexes;
        this.dispatchSink = dispatchSink;
        this.completionBus = completionBus;
        this.retryQuery = retryQuery;
        this.logIngest = logIngest;
        this.lifecycleScanner = lifecycleScanner;
        this.releaser = releaser;
    }

    public void tick() {
        // ⑦.5 回执收敛（最前）：上一 tick 的执行结果在此统一入账 —— 单线程契约下
        // 状态机/账本/互斥锁全部回到主循环线程处理，回执线程零状态变更
        drainCompletions();

        List<ActiveTaskRow> activeTasks = schedulingQuery.findActiveTasks();

        admitPendingTasks(activeTasks);
        advanceTasks(activeTasks);
        dispatchWaitingSteps(activeTasks);
        lifecycleScanner.scanStuckDispatch(100);   // ⑧ 卡住扫描（§4.6）
        lifecycleScanner.scanTimeouts(100);        // ⑧ 超时扫描（§8.3；心跳判定随 M2 资产域）
        retryDueSteps();                           // ⑨ 重试（§9.1）
        finishTasksIfDone(activeTasks);
        logIngest.flush();                         // ⑩ 日志刷盘（D-10：日志不进主链路——只占主循环一次批量 IO）
    }

    /**
     * 回执收敛（§4.6 / §2.2）：
     *   exitCode = null → 进程未确认启动（DISPATCH_FAIL）：回退 WAITING_RESOURCE、
     *                     释放节点预留与互斥锁、重新入队（原 seq）、计 failTicks；
     *   exitCode ∈ successCodes → RUNNING → SUCCESS（终态：扣回账本、释放锁）；
     *   其余 → RUNNING → FAILED（M1 不含重试；RetryHandler 为下一纵切，届时改走 RETRYING）。
     */
    private void drainCompletions() {
        completionBus.drainEach(completion -> {
            DispatchSink.DispatchInstruction ins = completion.instruction();
            TaskOrchestrator orchestrator = findOrchestratorByRow(ins.taskStepRowId());
            if (orchestrator == null) {
                log.warn("回执找不到编排器（任务已终结/编排器已重建）step={}，仅释放资源", ins.stepInstanceId());
                releaseExecutionResources(ins, completion.exitCode() != null);
                return;
            }
            if (completion.exitCode() == null) {
                // DISPATCH_FAIL：SSH 连不上/凭据不可用 —— 回退重调度（docs/06 §2.2 判定依据）
                log.warn("下发失败按回退处理 step={} reason={}", ins.stepInstanceId(), completion.failReason());
                orchestrator.applyExternalTransition(ins.taskStepRowId(), StepStatus.WAITING_RESOURCE);
                releaseExecutionResources(ins, false);
                readyQueue.recordFailure(ins.queueId(), ins.taskStepRowId());
                readyQueue.enqueue(ins.queueId(), ins.taskStepRowId(), ins.priority(), ins.enqueueSeq());
                return;
            }
            // 执行确认：SCHEDULING → RUNNING（DISPATCH_OK）
            orchestrator.applyExternalTransition(ins.taskStepRowId(), StepStatus.RUNNING);
            if (completion.exitCode() == 0) {
                orchestrator.applyExternalTransition(ins.taskStepRowId(), StepStatus.SUCCESS);
                log.info("步骤收敛 step={} exitCode=0 → SUCCESS", ins.stepInstanceId());
            } else if (!retryOrFail(orchestrator, ins, completion.exitCode(), completion.failReason())) {
                orchestrator.applyExternalTransition(ins.taskStepRowId(), StepStatus.FAILED);
                log.info("步骤收敛 step={} exitCode={} → FAILED", ins.stepInstanceId(), completion.exitCode());
            }
            releaseExecutionResources(ins, true);
        });
    }

    /**
     * 重试判定（docs/06 §9.1）：退出码 ∉ success_codes 且 retry_count < 上限 →
     * RETRYING（next_retry_at 落库 + 重试历史）→ true；重试耗尽或登记失败 → false 走 FAILED。
     * 默认换节点重试（重新参与节点匹配），上游产出复用不受影响。
     */
    private boolean retryOrFail(TaskOrchestrator orchestrator, DispatchSink.DispatchInstruction ins,
                                int exitCode, String failReason) {
        StepRuntimeRow runtime = orchestrator.runtimeOfRow(ins.taskStepRowId());
        if (runtime == null) {
            return false;
        }
        int maxRetry = runtime.getMaxRetryCount() == null ? 0 : runtime.getMaxRetryCount();
        int attempted = runtime.getRetryCount() == null ? 0 : runtime.getRetryCount();
        if (attempted >= maxRetry) {
            return false;   // 重试耗尽（E-04：MAX_RETRY=10 硬上限在 DAG 校验规则 9 保证）
        }
        OffsetDateTime nextRetryAt = OffsetDateTime.now()
                .plusSeconds(runtime.getRetryIntervalSeconds() == null ? 5 : runtime.getRetryIntervalSeconds());
        if (!orchestrator.applyRetrying(ins.taskStepRowId(), nextRetryAt, failReason)) {
            return false;   // 与人工停止竞态（CAS 失败）：按 FAILED 收敛，由终态语义兜底
        }
        retryQuery.insertRetryRecord(ins.taskStepRowId(), attempted + 1, "FAILED",
                ins.machineIp(), exitCode, failReason);
        log.info("步骤进入重试 step={} attempt={}/{} nextRetryAt={}",
                ins.stepInstanceId(), attempted + 1, maxRetry, nextRetryAt);
        return true;
    }

    /**
     * 阶段 ⑨ RetryHandler（docs/06 §9.1 ⑤）：到期 RETRYING → WAITING_RESOURCE 重新参与调度。
     * 沿用原 enqueue_seq（§4.5 防饥饿）；默认换节点（重新匹配，§9.1）。
     */
    private void retryDueSteps() {
        for (RetryingStepRow due : retryQuery.findDueRetrying(100)) {
            TaskOrchestrator orchestrator = findOrchestratorByRow(due.getId());
            if (orchestrator != null) {
                orchestrator.applyExternalTransition(due.getId(), StepStatus.WAITING_RESOURCE);
            } else {
                // 编排器缺失（重启后未重建/任务已终结）：直接 CAS，保证重试不因缓存丢失而卡死
                taskStepMapper.casTransition(due.getId(), "RETRYING", "WAITING_RESOURCE");
            }
            readyQueue.enqueue(due.getQueueId(), due.getId(), due.getPriority(), due.getEnqueueSeq());
            log.info("重试到期重新入队 step={} queue={} seq={}", due.getStepInstanceId(), due.getQueueId(), due.getEnqueueSeq());
        }
    }

    /** 执行结束（或失败回退）后的资源清算：账本扣回 + 互斥锁释放与等待者唤醒（复用 ResourceReleaser）。 */
    private void releaseExecutionResources(DispatchSink.DispatchInstruction ins, boolean finished) {
        releaser.release(ins.queueId(), ins.taskStepRowId(), ins.nodeId(),
                ins.dispatchToken(), ins.mutexGroup(), finished);
    }

    private TaskOrchestrator findOrchestratorByRow(long rowId) {
        return orchestrators.values().stream()
                .filter(o -> o.runtimeOfRow(rowId) != null)
                .findFirst().orElse(null);
    }

    /** ① 准入：PENDING 经统一并发检查（D-12）；DEFER 保持 PENDING 下一 tick 重查。 */
    private void admitPendingTasks(List<ActiveTaskRow> activeTasks) {
        for (ActiveTaskRow task : activeTasks) {
            if (!"PENDING".equals(task.getStatus())) {
                continue;
            }
            CheckResult result = concurrencyGuard.checkBeforeSubmit(task.getWorkflowId(), task.getProjectId());
            if (result.allowed()) {
                taskMapper.casStatus(task.getId(), TaskStatus.PENDING.name(), TaskStatus.SCHEDULING.name());
            } else if (result.action() == CheckResult.Action.REJECT_FORBID) {
                // FORBID 拒绝本应在提交时发生（server 侧拦截）；到这里说明提交时放行后出现竞争——
                // 保持 PENDING 并写失败原因（收敛为终态属 M4 人工干预细化，M1 观察日志）
                log.warn("PENDING 任务准入被 FORBID 拒绝 task={}（提交路径竞争，保持 PENDING）", task.getTaskId());
            }
            // DEFER_*：保持 PENDING，等待额度释放（docs/06 §6.1）
        }
    }

    /** ② 推进：装配/复用编排器 → advance；CAS 冲突则丢弃缓存，下 tick 重建。 */
    private void advanceTasks(List<ActiveTaskRow> activeTasks) {
        for (ActiveTaskRow task : activeTasks) {
            if ("PENDING".equals(task.getStatus())) {
                continue;
            }
            TaskOrchestrator orchestrator = orchestrators.computeIfAbsent(task.getId(),
                    id -> load(task));
            if (orchestrator == null) {
                continue;   // 装配失败（图异常已记日志），下 tick 重试
            }
            if (!orchestrator.advance()) {
                orchestrators.remove(task.getId());   // CAS 冲突：宁可重算，不可与 DB 脱节
            }
        }
    }

    /** ③~⑦ 派发：就绪队列 → 节点 → 互斥锁 → 下发槽位。 */
    private void dispatchWaitingSteps(List<ActiveTaskRow> activeTasks) {
        if (orchestrators.isEmpty()) {
            return;
        }
        // rowId → 上下文：候选反查所属任务与步骤元数据
        Map<Long, DispatchContext> contextByRowId = new HashMap<>();
        Set<Long> queueIds = new java.util.HashSet<>();
        for (ActiveTaskRow task : activeTasks) {
            TaskOrchestrator orchestrator = orchestrators.get(task.getId());
            if (orchestrator == null || task.getQueueId() == null) {
                continue;
            }
            queueIds.add(task.getQueueId());
            orchestrator.pendingWaitingRows().forEach(row ->
                    contextByRowId.put(row.getId(), new DispatchContext(task, orchestrator, row)));
        }

        List<DispatchableNodeRow> nodeRows = schedulingQuery.findDispatchableNodes();
        List<NodeView> nodeViews = nodeRows.stream().map(this::toNodeView).toList();

        for (Long queueId : queueIds) {
            long freeSlots = freeSlots(queueId);
            if (freeSlots <= 0) {
                continue;   // 队列隔离优先于优先级（G2-4）
            }
            for (Long rowId : readyQueue.drainCandidates(queueId, freeSlots)) {
                dispatchOne(queueId, rowId, contextByRowId.get(rowId), nodeViews);
            }
        }
    }

    private void dispatchOne(long queueId, long rowId, DispatchContext ctx, List<NodeView> nodeViews) {
        if (ctx == null) {
            readyQueue.remove(queueId, rowId);   // 编排器已被重建：候选失效，清理防幽灵
            return;
        }
        Optional<ReadyQueueManager.Claim> claim = readyQueue.claim(queueId, rowId);
        if (claim.isEmpty()) {
            return;   // 两段式未占位（他方处理/并发移除），跳过
        }
        String token = claim.get().dispatchToken();

        // 节点匹配（预留账本口径，D-22）
        NodeMatcher.Demand demand = NodeMatcher.Demand.builder()
                .request(parseResource(ctx.row.getResourceRequest()))
                .osConstraint(null)                 // 步骤级 OS/标签约束 M3 随编排域接入
                .tagConstraint(null)
                .targetClusterId(null)
                .clusterAffinityEnabled(false)
                .build();
        Optional<NodeMatcher.MatchResult> match = nodeMatcher.match(demand, nodeViews);
        if (match.isEmpty()) {
            rollbackClaim(queueId, rowId, token);
            readyQueue.recordFailure(queueId, rowId);   // 队头阻塞计数（score 不动，§4.5 防饥饿）
            return;
        }
        NodeView node = match.get().getNode();
        ledger.acquire(node.getNodeId(), rowId, demand.getRequest());   // 分配成功立即记账（§5.1）

        // 互斥锁（步骤级，出队后竞争——提交时绝不查锁，PRD §12.3）
        String mutexGroup = ctx.row.getMutexGroup();
        if (mutexGroup != null && !mutexGroup.isBlank()) {
            long enqueueSeq = ctx.row.getEnqueueSeq() == null ? 0L : ctx.row.getEnqueueSeq();
            boolean acquired = mutexes.tryAcquire(mutexGroup, rowId, enqueueSeq,
                    ctx.task.getPriority() == null ? 0 : ctx.task.getPriority(), queueId,
                    "task:" + ctx.task.getTaskId() + "/step:" + ctx.row.getStepInstanceId(), MUTEX_HOLDER_TTL);
            if (!acquired) {
                ledger.release(node.getNodeId(), rowId);
                rollbackClaim(queueId, rowId, token);
                // §6.3.1：已在 waiters（由 tryAcquire 登记）；释放侧走唤醒路径，不回 ready 队列
                return;
            }
            taskStepMapper.setMutexHolder(rowId, token, mutexGroup, true);
        }

        readyQueue.clearFailure(queueId, rowId);   // 真正下发 → 清队头阻塞计数
        dispatchSink.dispatch(new DispatchSink.DispatchInstruction(
                rowId, ctx.task.getId(), ctx.row.getStepInstanceId(), token,
                (ctx.row.getRetryCount() == null ? 0 : ctx.row.getRetryCount()) + 1,
                ctx.stepName(),
                queueId, node.getNodeId(), node.getNodeName(), node.getMachineIp(),
                mutexGroup,
                ctx.row.getEnqueueSeq() == null ? 0L : ctx.row.getEnqueueSeq(),
                ctx.task.getPriority() == null ? 0 : ctx.task.getPriority(),
                ctx.row.getStartCommand()));
        // 首步下发 → 任务 SCHEDULING→RUNNING（§2.1 行 3）；0 行 = 已是 RUNNING，忽略
        taskMapper.casStatus(ctx.task.getId(), TaskStatus.SCHEDULING.name(), TaskStatus.RUNNING.name());
    }

    /** ⑧ 终结：全部步骤终态 → 任务终态（STOPPED > TIMEOUT > FAILED > PARTIAL > SUCCESS）。 */
    private void finishTasksIfDone(List<ActiveTaskRow> activeTasks) {
        for (ActiveTaskRow task : activeTasks) {
            TaskOrchestrator orchestrator = orchestrators.get(task.getId());
            if (orchestrator == null) {
                continue;
            }
            Optional<TaskStatus> outcome = orchestrator.finishIfDone();
            if (outcome.isPresent()) {
                orchestrators.remove(task.getId());   // 终结即移除（docs/06 §7.1 生命周期）
            }
        }
    }

    private void rollbackClaim(long queueId, long rowId, String token) {
        // SCHEDULING → WAITING_RESOURCE：语义即 §2.2 的"回退重试"边；
        // 步骤仍在就绪队列的计分体系外重新排队——由下一 tick 的 advance 重新入队
        taskStepMapper.rollbackClaim(rowId, token);
    }

    private long freeSlots(long queueId) {
        QueueConcurrencyConfig config = concurrencyQuery.findQueueConcurrency(queueId);
        if (config == null) {
            return 0;   // 队列不存在/已删：不出队（保守）
        }
        long running = concurrencyQuery.countRunningStepsByQueue(queueId);
        return config.getMaxConcurrentTasks() - running;
    }

    private TaskOrchestrator load(ActiveTaskRow task) {
        try {
            return new TaskOrchestrator(task, advancer, readyQueue, taskStepMapper, taskMapper,
                    schedulingQuery.findStepDefsByVersion(task.getWorkflowVersionId()),
                    schedulingQuery.findEdgesByVersion(task.getWorkflowVersionId()),
                    schedulingQuery.findStepRuntimesByTask(task.getId()));
        } catch (IllegalArgumentException e) {
            log.error("任务图装配失败 task={}（含环或定义异常，转人工排查）: {}", task.getTaskId(), e.getMessage());
            return null;
        }
    }

    private NodeView toNodeView(DispatchableNodeRow row) {
        return NodeView.builder()
                .nodeId(row.getId()).nodeName(row.getNodeName())
                .machineIp(row.getIp()).clusterId(row.getClusterId())
                .enabled(true).onlineStatus(row.getOnlineStatus())
                .hasValidCredential(row.getCredentialRefId() != null)
                .osType(row.getOsType()).tags(parseTags(row.getTags()))
                .totals(new ReservedLedger.Resource(
                        row.getCpuTotal() == null ? 0 : row.getCpuTotal(),
                        row.getGpuTotal() == null ? 0 : row.getGpuTotal(),
                        row.getMemoryTotal() == null ? 0 : row.getMemoryTotal(),
                        row.getDiskTotal() == null ? 0 : row.getDiskTotal()))
                .runningTaskCount(row.getRunningTaskCount() == null ? 0 : row.getRunningTaskCount())
                .lastAllocatedAt(row.getLastAllocatedAt())
                .maxConcurrentSteps(row.getMaxConcurrentSteps())
                .reservedSteps(ledger.reservedSteps(row.getId()))
                .build();
    }

    /** PG text[] 原样字符串（"{gpu,cpu}"）→ Set；空数组/NULL → 空集。 */
    private static Set<String> parseTags(String raw) {
        if (raw == null || raw.length() < 2) {
            return Set.of();
        }
        String inner = raw.substring(1, raw.length() - 1);
        return inner.isBlank() ? Set.of() : Set.of(inner.split(","));
    }

    /** 资源申请 JSON 解析；缺失/解析失败按 0 计（§10.2 ④ 防御：宁可少算，不可拒绝所有调度）。 */
    private ReservedLedger.Resource parseResource(String json) {
        if (json == null || json.isBlank()) {
            return ReservedLedger.Resource.ZERO;
        }
        try {
            Map<?, ?> map = objectMapper.readValue(json, Map.class);
            return new ReservedLedger.Resource(
                    numberValue(map.get("cpu")), numberValue(map.get("gpu")),
                    (long) numberValue(map.get("memory")), (long) numberValue(map.get("disk")));
        } catch (Exception e) {
            log.warn("资源申请解析失败按 0 计（防御口径 §10.2 ④）: {}", json);
            return ReservedLedger.Resource.ZERO;
        }
    }

    private static double numberValue(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    /** 派发上下文：候选 rowId 反查所需的最小元数据。 */
    private record DispatchContext(ActiveTaskRow task, TaskOrchestrator orchestrator, StepRuntimeRow row) {
        String stepName() {
            return orchestrator.stepNameOf(row.getStepId());
        }
    }
}
