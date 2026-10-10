package com.flowops.scheduler.backfill;

import com.flowops.common.guard.CheckResult;
import com.flowops.common.guard.ConcurrencyGuard;
import com.flowops.domain.dto.query.BackfillStatusCountRow;
import com.flowops.domain.entity.task.BackfillBatch;
import com.flowops.domain.entity.workflow.Workflow;
import com.flowops.domain.entity.workflow.WorkflowVersion;
import com.flowops.domain.mapper.task.BackfillBatchMapper;
import com.flowops.domain.mapper.task.BackfillQueryMapper;
import com.flowops.domain.mapper.task.SubmitQueryMapper;
import com.flowops.domain.mapper.workflow.WorkflowMapper;
import com.flowops.domain.mapper.workflow.WorkflowVersionMapper;
import com.flowops.domain.service.TaskCreationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回填滚动投放引擎单测（M4-S3，docs/06 §12.1）。
 *
 * <p><b>本测试守的是什么</b>——投放引擎是"自动建任务"的唯一自动入口，闸门错一行
 * 就是 120 个任务一夜挤爆队列：</p>
 * <ol>
 *   <li><b>滚动闸门</b>：allowed = min(eff_concurrency − inflight, 单轮上限)，
 *       在途已满 → 零投放；DONE/PAUSED → 不投（暂停不中断在途也不新增）；</li>
 *   <li><b>待投放 = planned − skipped − 已投放</b>（skipped 与已投放日期绝不重复建）；</li>
 *   <li><b>FORBID 有实例在跑 → 整批本轮停投</b>（不建任务、不标记失败，下轮再试）；</li>
 *   <li><b>进度结算口径</b>：waiting = total − success − failed − inflight，
 *       全终结 → refreshProgress 收到 waiting=0/inflight=0（DONE 判定在 SQL 内）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BackfillDispatcherTest {

    @Mock private BackfillBatchMapper batchMapper;
    @Mock private BackfillQueryMapper backfillQuery;
    @Mock private WorkflowMapper workflowMapper;
    @Mock private WorkflowVersionMapper workflowVersionMapper;
    @Mock private SubmitQueryMapper submitQuery;
    @Mock private TaskCreationService taskCreation;
    @Mock private ConcurrencyGuard concurrencyGuard;

    private BackfillDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new BackfillDispatcher(batchMapper, backfillQuery, workflowMapper,
                workflowVersionMapper, submitQuery, taskCreation, concurrencyGuard);
        when(submitQuery.findAnyEnabledQueueIdByProject(any())).thenReturn(40L);
        Workflow w = new Workflow();
        w.setId(10L);
        w.setWorkflowId("WF-0001");
        w.setWorkflowName("订单同步");
        when(workflowMapper.selectById(10L)).thenReturn(w);
        WorkflowVersion v = new WorkflowVersion();
        v.setId(11L);
        v.setVersionNo("v3");
        when(workflowVersionMapper.selectById(11L)).thenReturn(v);
        when(batchMapper.selectById(anyLong())).thenAnswer(inv -> batch("RUNNING"));
        when(concurrencyGuard.checkBeforeSubmit(any(), any()))
                .thenReturn(CheckResult.proceed(com.flowops.common.enums.ConcurrencyPolicy.ALLOW, 0, 10));
    }

    // ── 夹具 ────────────────────────────────────────────────

    private BackfillBatch batch(String status) {
        BackfillBatch b = new BackfillBatch();
        b.setId(9L);
        b.setBatchId("BF-0001");
        b.setWorkflowId(10L);
        b.setWorkflowVersionId(11L);
        b.setProjectId(20L);
        b.setBizFrom(LocalDate.of(2026, 9, 1));
        b.setBizTo(LocalDate.of(2026, 9, 3));       // 3 个计划日期
        b.setConcurrency(3);
        b.setSequential(false);
        b.setEffConcurrency(3);
        b.setStatus(status);
        b.setTotal(3);
        b.setSubmitter("admin");
        b.setOverrides("{}");
        return b;
    }

    private BackfillStatusCountRow count(String status, long cnt) {
        BackfillStatusCountRow row = new BackfillStatusCountRow();
        row.setStatus(status);
        row.setCnt(cnt);
        return row;
    }

    // ── 滚动闸门 ────────────────────────────────────────────

    @Test
    void dispatchBatch_fullInflight_dispatchesNothing() {
        // inflight = 3 = eff_concurrency → allowed = 0
        when(backfillQuery.countTasksByStatus(9L)).thenReturn(List.of(
                count("RUNNING", 2), count("PENDING", 1)));
        dispatcher.dispatchBatch(batch("RUNNING"));
        verify(taskCreation, never()).create(any());
    }

    @Test
    void dispatchBatch_pausedBatch_skipsDispatch() {
        when(backfillQuery.countTasksByStatus(9L)).thenReturn(List.of());
        when(batchMapper.selectById(9L)).thenReturn(batch("PAUSED"));
        dispatcher.dispatchBatch(batch("PAUSED"));
        verify(taskCreation, never()).create(any());
        verify(batchMapper, atLeastOnce()).refreshProgress(anyLong(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void dispatchBatch_fillsOnlyInflightGap_upToPerRoundLimit() {
        // 无任何任务 → allowed = min(3, 10) = 3，但计划日期只有 3 天 → 全部投出
        when(backfillQuery.countTasksByStatus(9L)).thenReturn(List.of());
        when(backfillQuery.findDispatchedDates(9L)).thenReturn(List.of());
        dispatcher.dispatchBatch(batch("RUNNING"));
        ArgumentCaptor<TaskCreationService.Spec> captor =
                ArgumentCaptor.forClass(TaskCreationService.Spec.class);
        verify(taskCreation, org.mockito.Mockito.times(3)).create(captor.capture());
        List<TaskCreationService.Spec> specs = captor.getAllValues();
        assertThat(specs).allSatisfy(s -> {
            assertThat(s.triggerType()).isEqualTo(com.flowops.common.enums.TriggerType.BACKFILL);
            assertThat(s.backfillBatchId()).isEqualTo(9L);
            assertThat(s.queueId()).isEqualTo(40L);
            assertThat(s.priority()).isZero();
            assertThat(s.variableSnapshotJson()).isEqualTo("{}");
        });
        // 投放日期按 bizFrom 起序（1/2/3 号），无跳日
        assertThat(specs).extracting(TaskCreationService.Spec::bizDate)
                .containsExactly(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3));
    }

    @Test
    void dispatchBatch_perRoundLimitCapsCreation() {
        // 10 天窗口，eff_concurrency 5，单轮上限 10 → 投 5 个后闸门生效
        BackfillBatch b = batch("RUNNING");
        b.setBizTo(LocalDate.of(2026, 9, 10));
        b.setTotal(10);
        b.setEffConcurrency(5);
        when(batchMapper.selectById(9L)).thenReturn(b);
        when(backfillQuery.countTasksByStatus(9L)).thenReturn(List.of());
        when(backfillQuery.findDispatchedDates(9L)).thenReturn(List.of());
        dispatcher.dispatchBatch(b);
        verify(taskCreation, org.mockito.Mockito.times(5)).create(any());
    }

    @Test
    void dispatchBatch_skipsSkippedAndDispatchedDates() {
        when(backfillQuery.countTasksByStatus(9L)).thenReturn(List.of());
        // 9/1 已投放、9/2 在 skipped（越窗口径在提交期已定）、9/3 待投
        when(backfillQuery.findDispatchedDates(9L)).thenReturn(List.of(LocalDate.of(2026, 9, 1)));
        BackfillBatch b = batch("RUNNING");
        b.setSkippedDates(new LocalDate[]{LocalDate.of(2026, 9, 2)});
        when(batchMapper.selectById(9L)).thenReturn(b);
        dispatcher.dispatchBatch(b);
        ArgumentCaptor<TaskCreationService.Spec> captor =
                ArgumentCaptor.forClass(TaskCreationService.Spec.class);
        verify(taskCreation, org.mockito.Mockito.times(1)).create(captor.capture());
        assertThat(captor.getValue().bizDate()).isEqualTo(LocalDate.of(2026, 9, 3));
    }

    @Test
    void dispatchBatch_doneAfterSettlement_stopsDispatching() {
        // 结算后批次变 DONE（selectById 返回 DONE）→ 不再投放
        when(backfillQuery.countTasksByStatus(9L)).thenReturn(List.of(
                count("SUCCESS", 3)));
        when(batchMapper.selectById(9L)).thenReturn(batch("DONE"));
        dispatcher.dispatchBatch(batch("RUNNING"));
        verify(taskCreation, never()).create(any());
        // 结算时 waiting=0 / inflight=0（SQL 内据此落 DONE）
        verify(batchMapper).refreshProgress(9L, 3, 0, 0, 0);
    }

    @Test
    void dispatchBatch_forbidWorkflowWithRunningInstance_stopsWholeRound() {
        when(backfillQuery.countTasksByStatus(9L)).thenReturn(List.of());
        when(backfillQuery.findDispatchedDates(9L)).thenReturn(List.of());
        when(concurrencyGuard.checkBeforeSubmit(any(), any()))
                .thenReturn(new CheckResult(CheckResult.Action.REJECT_FORBID,
                        com.flowops.common.enums.ConcurrencyPolicy.FORBID, 1, 1, "TASK-20261011-0009"));
        dispatcher.dispatchBatch(batch("RUNNING"));
        verify(taskCreation, never()).create(any());
    }

    // ── 进度结算口径 ────────────────────────────────────────

    @Test
    void refreshProgress_bucketsStatusesAndDerivesWaiting() {
        BackfillBatch b = batch("RUNNING");   // total=3
        Map<String, Integer> counts = Map.of("SUCCESS", 1, "FAILED", 1, "RUNNING", 1);
        dispatcher.refreshProgress(b, counts);
        // waiting = 3 − 1 − 1 − 1 = 0
        verify(batchMapper).refreshProgress(9L, 1, 1, 0, 1);
    }

    @Test
    void refreshProgress_undispatchedDatesCountIntoWaiting() {
        BackfillBatch b = batch("RUNNING");   // total=3
        Map<String, Integer> counts = Map.of("PENDING", 1);
        dispatcher.refreshProgress(b, counts);
        // 1 在途 + 2 未投放 = waiting 2
        verify(batchMapper).refreshProgress(9L, 0, 0, 2, 1);
    }

    @Test
    void statusCounts_mergesDuplicateStatusRows() {
        when(backfillQuery.countTasksByStatus(9L)).thenReturn(List.of(
                count("PENDING", 2), count("PENDING", 1), count("SUCCESS", 1)));
        assertThat(dispatcher.statusCounts(9L))
                .containsEntry("PENDING", 3)
                .containsEntry("SUCCESS", 1);
    }

    @Test
    void dispatchTick_isolatesBatchFailure() {
        BackfillBatch bad = batch("RUNNING");
        BackfillBatch good = batch("RUNNING");
        good.setId(12L);
        good.setBatchId("BF-0002");
        when(batchMapper.selectList(any())).thenReturn(List.of(bad, good));
        when(backfillQuery.countTasksByStatus(9L)).thenThrow(new RuntimeException("db hiccup"));
        when(backfillQuery.countTasksByStatus(12L)).thenReturn(List.of());
        when(backfillQuery.findDispatchedDates(12L)).thenReturn(List.of());
        dispatcher.dispatchTick();
        // 第一批抛异常不拖垮第二批
        verify(taskCreation, org.mockito.Mockito.times(3)).create(any());
    }
}
