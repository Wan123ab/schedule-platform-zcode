package com.flowops.scheduler.core;

import com.flowops.common.enums.StepStatus;
import com.flowops.domain.dto.query.ActiveTaskRow;
import com.flowops.domain.dto.query.EdgeRow;
import com.flowops.domain.dto.query.StepDefRow;
import com.flowops.domain.dto.query.StepRuntimeRow;
import com.flowops.domain.mapper.task.TaskMapper;
import com.flowops.domain.mapper.task.TaskStepMapper;
import com.flowops.scheduler.dag.DagAdvancer;
import com.flowops.scheduler.queue.ReadyQueueManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TaskOrchestrator 单测（Mockito 隔离 DB 与 Redis）：
 * 推进落库的 CAS 语义、WAITING_RESOURCE 入队、终结判定 —— docs/06 §7.2/§7.3 的接线形态。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskOrchestratorTest {

    @Mock
    private TaskStepMapper taskStepMapper;
    @Mock
    private TaskMapper taskMapper;
    @Mock
    private ReadyQueueManager readyQueue;

    private TaskOrchestrator linearTask() {
        // 拉取(1) → 清洗(2) → 入库(3)；实例行 id = 101/102/103
        ActiveTaskRow task = new ActiveTaskRow();
        task.setId(1L);
        task.setTaskId("TASK-20261006-0001");
        task.setWorkflowVersionId(10L);
        task.setQueueId(5L);
        task.setPriority(7);

        List<StepDefRow> defs = List.of(def(1L, "拉取"), def(2L, "清洗"), def(3L, "入库"));
        List<EdgeRow> edges = List.of(edge(1L, 2L), edge(2L, 3L));
        List<StepRuntimeRow> runtimes = List.of(
                runtime(101L, 1L, "NOT_STARTED"), runtime(102L, 2L, "NOT_STARTED"), runtime(103L, 3L, "NOT_STARTED"));

        return new TaskOrchestrator(task, new DagAdvancer(), readyQueue, taskStepMapper, taskMapper,
                defs, edges, runtimes);
    }

    private static StepDefRow def(long id, String name) {
        StepDefRow row = new StepDefRow();
        row.setStepId(id);
        row.setStepName(name);
        row.setStepType("TASK");
        return row;
    }

    private static EdgeRow edge(long from, long to) {
        EdgeRow row = new EdgeRow();
        row.setSourceStepId(from);
        row.setTargetStepId(to);
        return row;
    }

    private static StepRuntimeRow runtime(long rowId, long stepNodeId, String status) {
        StepRuntimeRow row = new StepRuntimeRow();
        row.setId(rowId);
        row.setStepInstanceId("SI-" + rowId);
        row.setStepId(stepNodeId);
        row.setStatus(status);
        row.setEnqueueSeq(rowId);
        return row;
    }

    /** 模拟执行回执的完整生命周期：SCHEDULING → RUNNING → SUCCESS（严格走状态机合法边）。 */
    private void completeRow(TaskOrchestrator orchestrator, long rowId) {
        orchestrator.applyExternalTransition(rowId, StepStatus.SCHEDULING);
        orchestrator.applyExternalTransition(rowId, StepStatus.RUNNING);
        orchestrator.applyExternalTransition(rowId, StepStatus.SUCCESS);
    }

    @Test
    void 首tick_三步骤按入度分流并全部CAS落库() {
        TaskOrchestrator orchestrator = linearTask();
        when(taskStepMapper.casTransition(anyLong(), anyString(), anyString())).thenReturn(1);

        assertThat(orchestrator.advance()).isTrue();

        verify(taskStepMapper).casTransition(eq(101L), eq("NOT_STARTED"), eq("WAITING_RESOURCE"));
        verify(taskStepMapper).casTransition(eq(102L), eq("NOT_STARTED"), eq("WAITING_DEPENDENCY"));
        verify(taskStepMapper).casTransition(eq(103L), eq("NOT_STARTED"), eq("WAITING_DEPENDENCY"));
        // 只有 WAITING_RESOURCE 入队；task.priority=7 传入 score 构造
        verify(readyQueue, times(1)).enqueue(eq(5L), eq(101L), eq(7), eq(101L));
        verify(readyQueue, never()).enqueue(anyLong(), eq(102L), anyInt(), anyLong());
    }

    @Test
    void CAS冲突_返回false且不再入队() {
        TaskOrchestrator orchestrator = linearTask();
        when(taskStepMapper.casTransition(anyLong(), anyString(), anyString())).thenReturn(0);

        assertThat(orchestrator.advance()).isFalse();   // 调用方应丢弃本编排器（宁可重算）
        verify(readyQueue, never()).enqueue(anyLong(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void 上游成功后_下游解锁并入队_入队用原enqueueSeq() {
        TaskOrchestrator orchestrator = linearTask();
        when(taskStepMapper.casTransition(anyLong(), anyString(), anyString())).thenReturn(1);
        orchestrator.advance();

        // 步骤 101 执行成功（回执路径：SCHEDULING→RUNNING→SUCCESS，CAS 落库 + 双视图同步）
        completeRow(orchestrator, 101L);

        assertThat(orchestrator.advance()).isTrue();

        // 102 转 WAITING_RESOURCE 并入队，score 用其原 enqueue_seq=102（防饥饿，§4.5）
        verify(taskStepMapper).casTransition(eq(102L), eq("WAITING_DEPENDENCY"), eq("WAITING_RESOURCE"));
        verify(readyQueue).enqueue(eq(5L), eq(102L), eq(7), eq(102L));
    }

    @Test
    void 全部终态_任务收敛SUCCESS() {
        TaskOrchestrator orchestrator = linearTask();
        when(taskStepMapper.casTransition(anyLong(), anyString(), anyString())).thenReturn(1);
        orchestrator.advance();

        completeRow(orchestrator, 101L);
        orchestrator.advance();
        completeRow(orchestrator, 102L);
        orchestrator.advance();
        completeRow(orchestrator, 103L);

        when(taskMapper.casStatus(eq(1L), eq("RUNNING"), eq("SUCCESS"))).thenReturn(1);
        assertThat(orchestrator.finishIfDone()).isPresent();
        verify(taskMapper).casStatus(eq(1L), eq("RUNNING"), eq("SUCCESS"));
    }

    @Test
    void 有步骤未终态_任务不收敛() {
        TaskOrchestrator orchestrator = linearTask();
        when(taskStepMapper.casTransition(anyLong(), anyString(), anyString())).thenReturn(1);
        orchestrator.advance();

        assertThat(orchestrator.finishIfDone()).isEmpty();
        verify(taskMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }
}
