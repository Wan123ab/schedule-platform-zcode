package com.flowops.scheduler.core;

import com.flowops.common.guard.CheckResult;
import com.flowops.common.guard.ConcurrencyGuard;
import com.flowops.domain.dto.query.ActiveTaskRow;
import com.flowops.domain.dto.query.DispatchableNodeRow;
import com.flowops.domain.dto.query.EdgeRow;
import com.flowops.domain.dto.query.QueueConcurrencyConfig;
import com.flowops.domain.dto.query.RetryingStepRow;
import com.flowops.domain.dto.query.StepDefRow;
import com.flowops.domain.dto.query.StepRuntimeRow;
import com.flowops.domain.mapper.concurrency.ConcurrencyQueryMapper;
import com.flowops.domain.mapper.concurrency.SchedulingQueryMapper;
import com.flowops.domain.mapper.task.LifecycleQueryMapper;
import com.flowops.domain.mapper.task.RetryQueryMapper;
import com.flowops.domain.mapper.task.TaskMapper;
import com.flowops.domain.mapper.task.TaskStepMapper;
import com.flowops.scheduler.guard.MutexLockManager;
import com.flowops.scheduler.lifecycle.LifecycleScanner;
import com.flowops.scheduler.lifecycle.ResourceReleaser;
import com.flowops.scheduler.log.LogIngestService;
import com.flowops.scheduler.match.NodeMatcher;
import com.flowops.scheduler.match.ReservedLedger;
import com.flowops.scheduler.queue.ReadyQueueManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 调度管线端到端单测（Mockito 隔离 DB/Redis；真实 ReservedLedger / NodeMatcher / CompletionBus）。
 * 走通 docs/06 §3.2 的 M1 主链：推进 → 出队 → 匹配 → 下发 →（回执）→ 收敛，
 * 并覆盖 docs/06 §16 边界用例 8 的调度侧语义（dispatch_token 全程贯通）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulerTickPipelineTest {

    @Mock private SchedulingQueryMapper schedulingQuery;
    @Mock private ConcurrencyQueryMapper concurrencyQuery;
    @Mock private TaskMapper taskMapper;
    @Mock private TaskStepMapper taskStepMapper;
    @Mock private ConcurrencyGuard concurrencyGuard;
    @Mock private ReadyQueueManager readyQueue;
    @Mock private MutexLockManager mutexes;
    @Mock private RetryQueryMapper retryQuery;
    @Mock private LogIngestService logIngest;
    @Mock private LifecycleQueryMapper lifecycleQuery;

    private final ReservedLedger ledger = new ReservedLedger();
    private final CompletionBus bus = new CompletionBus();

    private DispatchSink.DispatchInstruction dispatched;

    private SchedulerTickPipeline pipeline;

    @BeforeEach
    void setUp() {
        // 桩 sink：捕获指令，供测试手动向总线发布回执（等价真实 SSH 的异步回流）
        ResourceReleaser releaser = new ResourceReleaser(ledger, mutexes, taskStepMapper, readyQueue);
        LifecycleScanner scanner = new LifecycleScanner(lifecycleQuery, taskStepMapper, releaser, readyQueue);
        pipeline = new SchedulerTickPipeline(schedulingQuery, concurrencyQuery,
                taskMapper, taskStepMapper, concurrencyGuard, readyQueue,
                new NodeMatcher(ledger), ledger, mutexes,
                instruction -> dispatched = instruction, bus, retryQuery, logIngest,
                scanner, releaser);

        lenient().when(retryQuery.findDueRetrying(anyInt())).thenReturn(List.of());
        lenient().when(lifecycleQuery.findStuckScheduling(anyInt(), anyInt())).thenReturn(List.of());
        lenient().when(lifecycleQuery.findTimedOutSteps(anyInt())).thenReturn(List.of());

        // 活跃任务：SCHEDULING，3 步线性图，队列 5 优先级 7
        ActiveTaskRow task = activeTask();
        lenient().when(schedulingQuery.findActiveTasks()).thenReturn(List.of(task));
        lenient().when(schedulingQuery.findStepDefsByVersion(10L)).thenReturn(List.of(
                def(1L, "拉取"), def(2L, "清洗"), def(3L, "入库")));
        lenient().when(schedulingQuery.findEdgesByVersion(10L)).thenReturn(List.of(
                edge(1L, 2L), edge(2L, 3L)));
        lenient().when(schedulingQuery.findStepRuntimesByTask(1L)).thenReturn(List.of(
                runtime(101L, 1L), runtime(102L, 2L), runtime(103L, 3L)));
        lenient().when(schedulingQuery.findDispatchableNodes()).thenReturn(List.of(node(100L)));

        lenient().when(taskStepMapper.casTransition(anyLong(), anyString(), anyString())).thenReturn(1);
        lenient().when(taskMapper.casStatus(anyLong(), anyString(), anyString())).thenReturn(1);
        lenient().when(concurrencyQuery.findQueueConcurrency(5L)).thenReturn(queueConfig(5L, 10));
        lenient().when(concurrencyQuery.countRunningStepsByQueue(5L)).thenReturn(0L);
        lenient().when(readyQueue.claim(eq(5L), anyLong()))
                .thenAnswer(inv -> Optional.of(new ReadyQueueManager.Claim(inv.getArgument(1), "tok-" + inv.getArgument(1))));
        lenient().when(readyQueue.drainCandidates(eq(5L), anyLong())).thenReturn(List.of(101L));
        lenient().when(concurrencyGuard.checkBeforeSubmit(anyLong(), anyLong()))
                .thenReturn(CheckResult.proceed(null, 0, 1));
    }

    @Test
    void 两tick主链_下发带三元组_回执收敛为SUCCESS且资源清算() {
        pipeline.tick();   // tick 1：推进 + 出队 + 匹配 + 下发

        assertThat(dispatched).isNotNull();
        assertThat(dispatched.dispatchToken()).isEqualTo("tok-101");
        assertThat(dispatched.attemptNo()).isEqualTo(1);
        assertThat(dispatched.nodeId()).isEqualTo(100L);
        assertThat(dispatched.machineIp()).isEqualTo("10.0.0.100");
        assertThat(dispatched.command()).isEqualTo("echo hi");
        // 分配即记账（D-22）：节点 100 上有 1 个在途预留
        assertThat(ledger.reservedSteps(100L)).isEqualTo(1);
        verify(taskMapper).casStatus(1L, "SCHEDULING", "RUNNING");

        // 真实 SSH 的异步回执：结果进总线
        bus.publish(new CompletionBus.Completion(dispatched, 0, null));

        pipeline.tick();   // tick 2：回执收敛

        // SCHEDULING → RUNNING → SUCCESS（两段 CAS，走状态机合法边）
        verify(taskStepMapper).casTransition(eq(101L), eq("SCHEDULING"), eq("RUNNING"));
        verify(taskStepMapper).casTransition(eq(101L), eq("RUNNING"), eq("SUCCESS"));
        // 资源清算：账本扣回（§5.1 分配/释放对称）
        assertThat(ledger.reservedSteps(100L)).isZero();
    }

    @Test
    void 回执_进程未确认启动_DISPATCH_FAIL回退并重新入队() {
        pipeline.tick();
        assertThat(dispatched).isNotNull();

        bus.publish(new CompletionBus.Completion(dispatched, null, "SSH 连接失败"));
        // 回退后本 tick 不再重派（真实场景由 freeSlots/队列状态决定，这里固定探针）
        when(readyQueue.drainCandidates(eq(5L), anyLong())).thenReturn(List.of());
        pipeline.tick();

        // SCHEDULING → WAITING_RESOURCE（DISPATCH_FAIL 边），重新入队用原 seq/priority
        verify(taskStepMapper).casTransition(eq(101L), eq("SCHEDULING"), eq("WAITING_RESOURCE"));
        verify(readyQueue, org.mockito.Mockito.times(2)).enqueue(5L, 101L, 7, 101L);   // advance 入队 + 回退重入队
        verify(readyQueue).recordFailure(5L, 101L);
        assertThat(ledger.reservedSteps(100L)).isZero();   // 回退即清算
    }

    @Test
    void 无匹配节点_回退占位_计failTicks_不入账本() {
        // 节点资源耗尽 → 匹配失败
        ledger.acquire(100L, 999L, new ReservedLedger.Resource(8, 0, 0, 0));

        pipeline.tick();

        verify(readyQueue).recordFailure(5L, 101L);
        verify(readyQueue, org.mockito.Mockito.never()).clearFailure(anyLong(), anyLong());
        assertThat(dispatched).isNull();   // 未下发
        assertThat(ledger.reservedSteps(100L)).isEqualTo(1);   // 只有预置的那笔，步骤未入账
    }

    @Test
    void 互斥未获取_回退_且等待者被登记() {
        ActiveTaskRow task = activeTask();
        StepRuntimeRow mutexStep = runtime(101L, 1L);
        mutexStep.setMutexGroup("etl-lock");
        lenient().when(schedulingQuery.findStepRuntimesByTask(1L)).thenReturn(List.of(mutexStep));
        lenient().when(mutexes.tryAcquire(eq("etl-lock"), eq(101L), anyLong(), anyInt(), eq(5L),
                anyString(), org.mockito.ArgumentMatchers.any(Duration.class))).thenReturn(false);

        pipeline.tick();

        verify(mutexes).tryAcquire(eq("etl-lock"), eq(101L), anyLong(), eq(7), eq(5L),
                anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
        assertThat(dispatched).isNull();                       // 拿不到锁不下发
        assertThat(ledger.reservedSteps(100L)).isZero();       // 回退即清算（§6.3）
    }

    @Test
    void 互斥锁释放_等待者按原seq与priority重排回队列() {
        ActiveTaskRow task = activeTask();
        StepRuntimeRow mutexStep = runtime(101L, 1L);
        mutexStep.setMutexGroup("etl-lock");
        lenient().when(schedulingQuery.findStepRuntimesByTask(1L)).thenReturn(List.of(mutexStep));
        lenient().when(mutexes.tryAcquire(eq("etl-lock"), eq(101L), anyLong(), anyInt(), eq(5L),
                anyString(), org.mockito.ArgumentMatchers.any(Duration.class))).thenReturn(true);

        pipeline.tick();   // 拿到锁并下发
        assertThat(dispatched).isNotNull();

        MutexLockManager.Waiter waiter = new MutexLockManager.Waiter(202L, 88L, 3, 5L, "etl-lock");
        when(mutexes.release("etl-lock", 101L)).thenReturn(Optional.of(waiter));
        bus.publish(new CompletionBus.Completion(dispatched, 0, null));
        pipeline.tick();   // 回执收敛：终态 + 释放锁 + 唤醒等待者

        verify(taskStepMapper).setMutexHolder(101L, "tok-101", "etl-lock", false);
        verify(readyQueue).enqueue(5L, 202L, 3, 88L);   // §6.3.1：原 seq=88 / priority=3，防饥饿
    }

    @Test
    void 重试耗尽_收敛FAILED_不再重试() {
        pipeline.tick();
        bus.publish(new CompletionBus.Completion(dispatched, 1, "exit 1"));
        pipeline.tick();   // maxRetryCount 缺省 0 → 直接 FAILED

        verify(taskStepMapper).casTransition(eq(101L), eq("RUNNING"), eq("FAILED"));
        verify(taskStepMapper, org.mockito.Mockito.never()).markRetrying(anyLong(), org.mockito.ArgumentMatchers.any(), anyString());
    }

    @Test
    void 重试未耗尽_登记RETRYING与next_retry_at并写重试历史() {
        // 步骤配置 1 次重试（继承链 M4 完整解析；M1 取实例行/步骤定义投影）
        StepRuntimeRow retryable = runtime(101L, 1L);
        retryable.setMaxRetryCount(1);
        retryable.setRetryIntervalSeconds(5);
        lenient().when(schedulingQuery.findStepRuntimesByTask(1L)).thenReturn(List.of(
                retryable, runtime(102L, 2L), runtime(103L, 3L)));

        when(taskStepMapper.markRetrying(eq(101L), org.mockito.ArgumentMatchers.any(), eq("exit 1"))).thenReturn(1);

        pipeline.tick();
        bus.publish(new CompletionBus.Completion(dispatched, 1, "exit 1"));
        pipeline.tick();

        // RUNNING → RETRYING（markRetrying 一条 SQL 原子完成 status/next_retry_at/retry_count）
        verify(taskStepMapper).markRetrying(eq(101L), org.mockito.ArgumentMatchers.any(), eq("exit 1"));
        verify(retryQuery).insertRetryRecord(eq(101L), eq(1), eq("FAILED"), anyString(), eq(1), eq("exit 1"));
        verify(taskStepMapper, org.mockito.Mockito.never()).casTransition(eq(101L), eq("RUNNING"), eq("FAILED"));
    }

    @Test
    void 阶段9_到期重试重新入队_沿用原enqueueSeq() {
        RetryingStepRow due = new RetryingStepRow();
        due.setId(102L);
        due.setStepInstanceId("SI-102");
        due.setStatus("RETRYING");
        due.setEnqueueSeq(102L);
        due.setTaskId(1L);
        due.setQueueId(5L);
        due.setPriority(7);
        // 编排器内存视图须与 DB 一致（102 已是 RETRYING），否则 applyExternalTransition 按 NOT_STARTED 转移
        StepRuntimeRow retryingRow = runtime(102L, 2L);
        retryingRow.setStatus("RETRYING");
        lenient().when(schedulingQuery.findStepRuntimesByTask(1L)).thenReturn(List.of(
                runtime(101L, 1L), retryingRow, runtime(103L, 3L)));
        when(retryQuery.findDueRetrying(100)).thenReturn(List.of(due));

        pipeline.tick();

        // RETRYING → WAITING_RESOURCE（RETRY_READY 边）+ 原序号入队（§4.5 防饥饿）
        verify(taskStepMapper).casTransition(eq(102L), eq("RETRYING"), eq("WAITING_RESOURCE"));
        verify(readyQueue).enqueue(5L, 102L, 7, 102L);
    }

    // ── 测试数据工厂 ─────────────────────────────────────────

    private ActiveTaskRow activeTask() {
        ActiveTaskRow task = new ActiveTaskRow();
        task.setId(1L);
        task.setTaskId("TASK-20261006-0001");
        task.setStatus("SCHEDULING");
        task.setWorkflowId(1000L);
        task.setProjectId(1L);
        task.setWorkflowVersionId(10L);
        task.setQueueId(5L);
        task.setPriority(7);
        return task;
    }

    private StepDefRow def(long id, String name) {
        StepDefRow row = new StepDefRow();
        row.setStepId(id);
        row.setStepName(name);
        row.setStepType("TASK");
        return row;
    }

    private EdgeRow edge(long from, long to) {
        EdgeRow row = new EdgeRow();
        row.setSourceStepId(from);
        row.setTargetStepId(to);
        return row;
    }

    private StepRuntimeRow runtime(long rowId, long stepNodeId) {
        StepRuntimeRow row = new StepRuntimeRow();
        row.setId(rowId);
        row.setStepInstanceId("SI-" + rowId);
        row.setStepId(stepNodeId);
        row.setStatus("NOT_STARTED");
        row.setEnqueueSeq((long) rowId);
        row.setStartCommand("echo hi");
        row.setResourceRequest("{\"cpu\":4,\"memory\":2048}");
        return row;
    }

    private DispatchableNodeRow node(long id) {
        DispatchableNodeRow row = new DispatchableNodeRow();
        row.setId(id);
        row.setNodeName("node-" + id);
        row.setIp("10.0.0.100");
        row.setClusterId(1L);
        row.setOnlineStatus("ONLINE");
        row.setOsType("LINUX");
        row.setTags("{}");
        row.setCredentialRefId(7L);
        row.setCpuTotal(8.0);
        row.setGpuTotal(0.0);
        row.setMemoryTotal(16384L);
        row.setDiskTotal(102400L);
        row.setRunningTaskCount(0);
        row.setLastAllocatedAt(OffsetDateTime.now());
        row.setMaxConcurrentSteps(null);
        return row;
    }

    private QueueConcurrencyConfig queueConfig(long id, int max) {
        QueueConcurrencyConfig config = new QueueConcurrencyConfig();
        config.setQueueId(id);
        config.setMaxConcurrentTasks(max);
        return config;
    }
}
