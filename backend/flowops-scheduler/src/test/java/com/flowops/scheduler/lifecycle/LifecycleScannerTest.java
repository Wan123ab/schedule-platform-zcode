package com.flowops.scheduler.lifecycle;

import com.flowops.common.enums.StepStatus;
import com.flowops.domain.dto.query.StuckDispatchRow;
import com.flowops.domain.dto.query.TimeoutStepRow;
import com.flowops.domain.mapper.task.LifecycleQueryMapper;
import com.flowops.domain.mapper.task.TaskStepMapper;
import com.flowops.scheduler.guard.MutexLockManager;
import com.flowops.scheduler.match.ReservedLedger;
import com.flowops.scheduler.queue.ReadyQueueManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 生命周期扫描单测（docs/06 §4.6 卡住回退 / §8.3 超时收敛）。
 * 重点：CAS 只动"确实还卡住"的步骤；清算幂等（漏清算才是事故）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LifecycleScannerTest {

    @Mock private LifecycleQueryMapper lifecycleQuery;
    @Mock private TaskStepMapper taskStepMapper;
    @Mock private MutexLockManager mutexes;
    @Mock private ReadyQueueManager readyQueue;

    private final ReservedLedger ledger = new ReservedLedger();
    private LifecycleScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new LifecycleScanner(lifecycleQuery, taskStepMapper,
                new ResourceReleaser(ledger, mutexes, taskStepMapper, readyQueue), readyQueue);
    }

    @Test
    void 卡住回退_仅动token未变的步骤_并重新入队() {
        StuckDispatchRow stuck = new StuckDispatchRow();
        stuck.setId(101L);
        stuck.setStepInstanceId("SI-101");
        stuck.setDispatchToken("tok-101");
        stuck.setEnqueueSeq(101L);
        stuck.setQueueId(5L);
        stuck.setPriority(7);
        when(lifecycleQuery.findStuckScheduling(2, 100)).thenReturn(List.of(stuck));
        when(taskStepMapper.rollbackClaim(101L, "tok-101")).thenReturn(1);

        int handled = scanner.scanStuckDispatch(100);

        assertThat(handled).isEqualTo(1);
        // §4.5：重新入队沿用原 seq/priority（claim 时已 ZREM，回退必须补回）
        verify(readyQueue).enqueue(5L, 101L, 7, 101L);
    }

    @Test
    void 卡住回退_CAS失败_说明已被他方处理_不入队() {
        StuckDispatchRow stuck = new StuckDispatchRow();
        stuck.setId(101L);
        stuck.setStepInstanceId("SI-101");
        stuck.setDispatchToken("tok-101");
        stuck.setEnqueueSeq(101L);
        stuck.setQueueId(5L);
        stuck.setPriority(7);
        when(lifecycleQuery.findStuckScheduling(anyInt(), anyInt())).thenReturn(List.of(stuck));
        when(taskStepMapper.rollbackClaim(101L, "tok-101")).thenReturn(0);

        assertThat(scanner.scanStuckDispatch(100)).isZero();
        verify(readyQueue, never()).enqueue(anyLong(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void 超时收敛_终态CAS_账本扣回_互斥释放与等待者唤醒() {
        TimeoutStepRow timedOut = new TimeoutStepRow();
        timedOut.setId(101L);
        timedOut.setStepInstanceId("SI-101");
        timedOut.setDispatchToken("tok-101");
        timedOut.setMutexGroup("etl-lock");
        timedOut.setNodeId(100L);
        timedOut.setQueueId(5L);
        timedOut.setPriority(7);
        timedOut.setEnqueueSeq(101L);
        when(lifecycleQuery.findTimedOutSteps(100)).thenReturn(List.of(timedOut));
        when(taskStepMapper.casTransition(101L, "RUNNING", "TIMEOUT")).thenReturn(1);
        when(mutexes.release("etl-lock", 101L)).thenReturn(Optional.of(
                new MutexLockManager.Waiter(202L, 88L, 3, 5L, "etl-lock")));
        ledger.acquire(100L, 101L, new ReservedLedger.Resource(4, 0, 0, 0));

        int handled = scanner.scanTimeouts(100);

        assertThat(handled).isEqualTo(1);
        verify(taskStepMapper).casTransition(101L, "RUNNING", StepStatus.TIMEOUT.name());
        assertThat(ledger.reservedSteps(100L)).isZero();                 // 账本扣回（D-22 对称清算）
        verify(taskStepMapper).setMutexHolder(101L, "tok-101", "etl-lock", false);
        verify(readyQueue).enqueue(5L, 202L, 3, 88L);                    // §6.3.1 等待者唤醒
        verify(readyQueue).clearFailure(5L, 101L);                       // 终态清队头计数
    }

    @Test
    void 超时CAS失败_仍执行清算_漏清算才是事故() {
        TimeoutStepRow timedOut = new TimeoutStepRow();
        timedOut.setId(101L);
        timedOut.setStepInstanceId("SI-101");
        timedOut.setDispatchToken("tok-101");
        timedOut.setMutexGroup(null);
        timedOut.setNodeId(100L);
        timedOut.setQueueId(5L);
        timedOut.setPriority(7);
        timedOut.setEnqueueSeq(101L);
        when(lifecycleQuery.findTimedOutSteps(anyInt())).thenReturn(List.of(timedOut));
        when(taskStepMapper.casTransition(anyLong(), anyString(), anyString())).thenReturn(0);
        ledger.acquire(100L, 101L, new ReservedLedger.Resource(4, 0, 0, 0));

        assertThat(scanner.scanTimeouts(100)).isZero();
        assertThat(ledger.reservedSteps(100L)).isZero();   // 状态已他方收敛，但资源必须清干净
        verify(mutexes, never()).release(anyString(), anyLong());
    }
}
