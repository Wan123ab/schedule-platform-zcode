package com.flowops.scheduler.lifecycle;

import com.flowops.common.enums.StepStatus;
import com.flowops.domain.dto.query.UnreachableStepRow;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 心跳离线判定单测（G4/G5，docs/06 §8.1）：三段阈值 + 恢复窗口清算（M1 遗留 O-1 的落地）。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HeartbeatScannerTest {

    @Mock private LifecycleQueryMapper lifecycleQuery;
    @Mock private MutexLockManager mutexes;
    @Mock private ReadyQueueManager readyQueue;
    @Mock private TaskStepMapper taskStepMapper;

    private final ReservedLedger ledger = new ReservedLedger();
    private HeartbeatScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new HeartbeatScanner(lifecycleQuery,
                new ResourceReleaser(ledger, mutexes, taskStepMapper, readyQueue));
        when(lifecycleQuery.incrementMissCount()).thenReturn(0);
        when(lifecycleQuery.markOfflineNodes()).thenReturn(0);
        when(lifecycleQuery.findUnreachableSteps(anyLong(), anyInt())).thenReturn(List.of());
    }

    @Test
    void 单次缺失与离线标记_各走一条集合SQL() {
        when(lifecycleQuery.incrementMissCount()).thenReturn(3);
        when(lifecycleQuery.markOfflineNodes()).thenReturn(2);

        scanner.scanHeartbeats();

        // G5 的"状态不变"由 SQL 的 WHERE online_status='ONLINE' 保证；这里验证调用发生
        verify(lifecycleQuery).incrementMissCount();
        verify(lifecycleQuery).markOfflineNodes();
    }

    @Test
    void 恢复窗口到期_步骤判FAILED_结构化失败原因_资源清算与等待者唤醒() {
        UnreachableStepRow row = new UnreachableStepRow();
        row.setId(101L);
        row.setStepInstanceId("SI-101");
        row.setDispatchToken("tok-101");
        row.setMutexGroup("etl-lock");
        row.setNodeId(100L);
        row.setQueueId(5L);
        when(lifecycleQuery.findUnreachableSteps(300L, 100)).thenReturn(List.of(row));
        when(lifecycleQuery.casStepFailed(101L, "RUNNING", "FAILED", "NODE_UNREACHABLE")).thenReturn(1);
        when(mutexes.release("etl-lock", 101L)).thenReturn(Optional.of(
                new MutexLockManager.Waiter(202L, 88L, 3, 5L, "etl-lock")));
        ledger.acquire(100L, 101L, new ReservedLedger.Resource(4, 0, 0, 0));

        scanner.scanRecoveryWindowExpired();

        verify(lifecycleQuery).casStepFailed(101L, "RUNNING", StepStatus.FAILED.name(), "NODE_UNREACHABLE");
        assertThat(ledger.reservedSteps(100L)).isZero();                    // 账本扣回（§8.1 ⑤ 易漏项）
        verify(taskStepMapper).setMutexHolder(101L, "tok-101", "etl-lock", false);
        verify(readyQueue).enqueue(5L, 202L, 3, 88L);                       // §6.3.1 等待者唤醒
    }

    @Test
    void 恢复窗口内_什么都不做_步骤继续执行() {
        when(lifecycleQuery.findUnreachableSteps(anyLong(), anyInt())).thenReturn(List.of());

        scanner.scanRecoveryWindowExpired();

        verify(lifecycleQuery, never()).casStepFailed(anyLong(), anyString(), anyString(), anyString());
        assertThat(ledger.reservedSteps(1L)).isZero();
    }
}
