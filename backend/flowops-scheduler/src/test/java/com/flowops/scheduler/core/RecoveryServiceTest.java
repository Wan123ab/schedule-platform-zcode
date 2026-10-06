package com.flowops.scheduler.core;

import com.flowops.domain.dto.query.LedgerRecoveryRow;
import com.flowops.domain.dto.query.MutexHolderRow;
import com.flowops.domain.dto.query.WaitingRecoveryRow;
import com.flowops.domain.mapper.task.RecoveryQueryMapper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 启动恢复单测（docs/06 §10.2：② 队列 / ③ 锁 / ④ 账本）。
 * D-09 的验收点：重启后 Redis 派生态与 PG 一致，任务状态零丢失。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecoveryServiceTest {

    @Mock private RecoveryQueryMapper recoveryQuery;
    @Mock private ReadyQueueManager readyQueue;
    @Mock private MutexLockManager mutexes;
    @Mock private LeaderElector leaderElector;

    private final ReservedLedger ledger = new ReservedLedger();
    private RecoveryService recovery;

    @BeforeEach
    void setUp() {
        recovery = new RecoveryService(recoveryQuery, readyQueue, ledger, mutexes, leaderElector, 90);
        lenient().when(leaderElector.isLeader()).thenReturn(true);
        lenient().when(recoveryQuery.findWaitingSteps(anyInt())).thenReturn(List.of());
        lenient().when(recoveryQuery.findMutexHolders(anyInt())).thenReturn(List.of());
        lenient().when(recoveryQuery.findLedgerRows(anyInt())).thenReturn(List.of());
    }

    @Test
    void 恢复_三重建_全部按PG口径执行() {
        when(recoveryQuery.findWaitingSteps(5000)).thenReturn(List.of(waiting(101L, 5L, 7, 900L)));
        when(recoveryQuery.findMutexHolders(5000)).thenReturn(List.of(holder(102L, "etl-lock")));
        when(recoveryQuery.findLedgerRows(5000)).thenReturn(List.of(
                ledgerRow(103L, 100L, "{\"cpu\":4,\"gpu\":0,\"memory\":4096,\"disk\":1024}")));
        when(mutexes.tryAcquire(eq("etl-lock"), eq(102L), anyLong(), anyInt(), anyLong(), anyString(), any()))
                .thenReturn(true);

        recovery.recover();

        // ② 队列：原 priority + 原 seq（FIFO 与重启前一致）
        verify(readyQueue).enqueue(5L, 101L, 7, 900L);
        // ③ 锁：按 PG 的持有点重新获取，holder 描述与 tryAcquire 写入格式一致
        verify(mutexes).tryAcquire(eq("etl-lock"), eq(102L), anyLong(), anyInt(), anyLong(),
                eq("task:TASK-20261006-0001/step:SI-102"), any());
        // ④ 账本：rebuild 覆盖 —— 崩溃前的脏账清掉
        assertThat(ledger.reservedSteps(100L)).isEqualTo(1);
        assertThat(ledger.reservedOf(100L).cpu()).isEqualTo(4);
    }

    @Test
    void 锁重建失败_记录但不阻断_其余重建继续() {
        when(recoveryQuery.findMutexHolders(5000)).thenReturn(List.of(holder(102L, "etl-lock")));
        when(mutexes.tryAcquire(anyString(), anyLong(), anyLong(), anyInt(), anyLong(), anyString(), any()))
                .thenReturn(false);   // 单活下不应发生：Redis 异常信号

        recovery.recover();   // 不抛异常：恢复继续（锁丢失由超时保护 TTL 兜底）
        recovery.recover();   // 幂等（§10.3）：重复执行结果一致

        verify(mutexes, org.mockito.Mockito.times(2))
                .tryAcquire(anyString(), anyLong(), anyLong(), anyInt(), anyLong(), anyString(), any());
    }

    @Test
    void 空库恢复_零副作用() {
        recovery.recover();
        recovery.recover();

        verify(readyQueue, org.mockito.Mockito.never())
                .enqueue(anyLong(), anyLong(), anyInt(), anyLong());
        assertThat(ledger.reservedSteps(1L)).isZero();
    }

    private WaitingRecoveryRow waiting(long id, long queueId, int priority, long seq) {
        WaitingRecoveryRow row = new WaitingRecoveryRow();
        row.setId(id);
        row.setStepInstanceId("SI-" + id);
        row.setQueueId(queueId);
        row.setPriority(priority);
        row.setEnqueueSeq(seq);
        return row;
    }

    private MutexHolderRow holder(long id, String group) {
        MutexHolderRow row = new MutexHolderRow();
        row.setId(id);
        row.setStepInstanceId("SI-" + id);
        row.setMutexGroup(group);
        row.setDispatchToken("tok-" + id);
        row.setEnqueueSeq((long) id);
        row.setQueueId(5L);
        row.setPriority(0);
        row.setTaskId("TASK-20261006-0001");
        return row;
    }

    private LedgerRecoveryRow ledgerRow(long id, long nodeId, String request) {
        LedgerRecoveryRow row = new LedgerRecoveryRow();
        row.setId(id);
        row.setStepInstanceId("SI-" + id);
        row.setNodeId(nodeId);
        row.setResourceRequest(request);
        return row;
    }
}
