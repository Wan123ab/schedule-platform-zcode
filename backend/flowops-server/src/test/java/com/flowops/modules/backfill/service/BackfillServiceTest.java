package com.flowops.modules.backfill.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.flowops.common.api.ErrorCode;
import com.flowops.common.exception.BizException;
import com.flowops.common.util.IdGen;
import com.flowops.common.util.SequenceOps;
import com.flowops.domain.dto.query.BackfillDateRow;
import com.flowops.domain.entity.task.BackfillBatch;
import com.flowops.domain.entity.workflow.Workflow;
import com.flowops.domain.mapper.project.ProjectMapper;
import com.flowops.domain.mapper.task.BackfillBatchMapper;
import com.flowops.domain.mapper.task.BackfillQueryMapper;
import com.flowops.domain.mapper.task.SubmitQueryMapper;
import com.flowops.domain.mapper.workflow.WorkflowMapper;
import com.flowops.modules.backfill.dto.BackfillBatchVO;
import com.flowops.modules.backfill.dto.BackfillPreviewRequest;
import com.flowops.modules.backfill.dto.BackfillPreviewVO;
import com.flowops.modules.backfill.dto.BackfillSubmitRequest;
import com.flowops.modules.task.dto.TaskVO;
import com.flowops.modules.task.service.TaskCreationServiceBridge;
import com.flowops.modules.task.service.TaskQueryService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回填批次单测（M4-S3）。
 *
 * <p><b>本测试守的是什么</b>——回填是"一次操作生成上百个任务"的高杠杆动作，
 * 错一道闸门就是额度事故：</p>
 * <ol>
 *   <li><b>双闸门</b>（docs/07 §6.5）：提交时服务端<b>重算</b> hash 比对（改配置直提被拦）
 *       + Redis 预览存在性（跳过预览/过期被拦），两道都必须真的会拦；</li>
 *   <li><b>eff_concurrency 收敛公式</b>（docs/06 §12.2 原文三层）—— 这是调度器投放
 *       闸门的同一口径，公式错 = 打爆并发额度；</li>
 *   <li><b>越窗跳过而非报错</b>（CONTRACT §8-4：skipped_dates），超 120 天才 42232；</li>
 *   <li><b>pause/resume 的 CAS 落空拒绝</b>（42238）与 retry-failed 的无失败日期拒绝。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BackfillServiceTest {

    @Mock private WorkflowMapper workflowMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private BackfillBatchMapper batchMapper;
    @Mock private BackfillQueryMapper backfillQuery;
    @Mock private SubmitQueryMapper submitQuery;
    @Mock private TaskQueryService taskQueryService;
    @Mock private TaskCreationServiceBridge taskCreation;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    private BackfillService service;

    @BeforeEach
    void setUp() {
        IdGen idGen = new IdGen((SequenceOps) key -> 42L);   // 发号固定值，断言 batchId 字面量
        // ScopeGuard 用真实现（无外部依赖）：它决定"查不到时给 40400 还是 40301"，
        // 若换成 mock，越权语义就是个 null，测试断言的 40400/40301 会失去意义
        service = new BackfillService(workflowMapper, projectMapper, batchMapper, backfillQuery,
                submitQuery, taskQueryService, taskCreation, idGen, redis, new ObjectMapper(),
                new com.flowops.modules.governance.scope.ScopeGuard());
        when(redis.opsForValue()).thenReturn(valueOps);
        when(submitQuery.findAnyEnabledQueueIdByProject(any())).thenReturn(40L);
        when(batchMapper.insert(any(BackfillBatch.class))).thenAnswer(inv -> {
            inv.getArgument(0, BackfillBatch.class).setId(9L);
            return 1;
        });
    }

    // ── 夹具 ────────────────────────────────────────────────

    private Workflow workflow(String policy, Integer maxParallel) {
        Workflow w = new Workflow();
        w.setId(10L);
        w.setWorkflowId("WF-0001");
        w.setWorkflowName("订单同步");
        w.setProjectId(20L);
        w.setCurrentVersionId(11L);
        w.setConcurrencyPolicy(policy);
        w.setMaxParallelRuns(maxParallel);
        return w;
    }

    private BackfillPreviewRequest request(LocalDate from, LocalDate to, Integer concurrency,
                                           Boolean sequential) {
        BackfillPreviewRequest r = new BackfillPreviewRequest();
        r.setWorkflowId("WF-0001");
        r.setBizFrom(from);
        r.setBizTo(to);
        r.setConcurrency(concurrency);
        r.setSequential(sequential);
        return r;
    }

    @SuppressWarnings("unchecked")
    private void stubWorkflowFound(Workflow w) {
        when(workflowMapper.selectOne(any(Wrapper.class))).thenReturn(w);
    }

    // ── eff_concurrency 收敛公式（docs/06 §12.2 原文三层）────

    @Test
    void effConc_sequentialWins() {
        assertThat(BackfillService.effConc(workflow("CONCURRENCY", 5), 10, true)).isEqualTo(1);
    }

    @Test
    void effConc_forbidDownToOne() {
        assertThat(BackfillService.effConc(workflow("FORBID", 5), 10, false)).isEqualTo(1);
    }

    @Test
    void effConc_cappedByMaxParallelRuns() {
        assertThat(BackfillService.effConc(workflow("CONCURRENCY", 3), 10, false)).isEqualTo(3);
        assertThat(BackfillService.effConc(workflow("CONCURRENCY", 8), 5, false)).isEqualTo(5);
    }

    @Test
    void effConc_nullCapFallsToRequested() {
        assertThat(BackfillService.effConc(workflow("CONCURRENCY", null), 6, false)).isEqualTo(6);
    }

    // ── buildPlan 校验 ──────────────────────────────────────

    @Test
    void preview_rejectsInvertedRange() {
        assertThatThrownBy(() -> service.preview(request(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 1), null, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void preview_rejectsUnknownWorkflow() {
        when(workflowMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThatThrownBy(() -> service.preview(request(LocalDate.now().minusDays(3), LocalDate.now().minusDays(1), null, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void preview_rejectsUnpublishedWorkflow() {
        Workflow w = workflow("CONCURRENCY", 3);
        w.setCurrentVersionId(null);
        stubWorkflowFound(w);
        assertThatThrownBy(() -> service.preview(request(LocalDate.now().minusDays(3), LocalDate.now().minusDays(1), null, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.RULE_NOT_SATISFIED);
    }

    @Test
    void preview_rejectsOverMaxDates() {
        stubWorkflowFound(workflow("CONCURRENCY", 3));
        // 131 天全在窗口内（today-150 ~ today-20）→ 42232
        assertThatThrownBy(() -> service.preview(request(LocalDate.now().minusDays(150), LocalDate.now().minusDays(20), null, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.BACKFILL_RANGE_EXCEEDED);
    }

    @Test
    void preview_skipsOutOfRangeDates() {
        stubWorkflowFound(workflow("CONCURRENCY", 3));
        // 今天与明天都晚于窗口终点（昨天）→ 跳过不报错
        BackfillPreviewVO vo = service.preview(request(LocalDate.now(), LocalDate.now().plusDays(1), null, null));
        assertThat(vo.getPlannedCount()).isZero();
        assertThat(vo.getSkippedDates()).hasSize(2);
    }

    // ── 预览内容 ────────────────────────────────────────────

    @Test
    void preview_buildsTasksWithConflictDetail() {
        stubWorkflowFound(workflow("CONCURRENCY", 3));
        BackfillDateRow hit = new BackfillDateRow();
        hit.setTaskId("TASK-20261010-0001");
        hit.setStatus("SUCCESS");
        when(backfillQuery.findConflictTask(eq(10L), any(LocalDate.class), any(), any()))
                .thenReturn(null)                       // 第一天无冲突
                .thenReturn(hit);                       // 第二天有冲突
        BackfillPreviewVO vo = service.preview(request(
                LocalDate.now().minusDays(2), LocalDate.now().minusDays(1), null, null));
        assertThat(vo.getPlannedCount()).isEqualTo(2);
        assertThat(vo.getTasks()).hasSize(2);
        assertThat(vo.getTasks().get(0).getPlannedTaskId()).isEqualTo("P-0001");
        assertThat(vo.getTasks().get(1).getPlannedTaskId()).isEqualTo("P-0002");
        assertThat(vo.getTasks().get(0).getConflict()).isNull();
        assertThat(vo.getTasks().get(1).getConflict())
                .containsEntry("taskId", "TASK-20261010-0001").containsEntry("status", "SUCCESS");
        assertThat(vo.getConflictCount()).isEqualTo(1);
        assertThat(vo.getPreviewHash()).hasSize(64);   // SHA-256 hex
        assertThat(vo.getPolicyConflict()).isNull();   // CONCURRENCY 3 / 默认并发 1 → 无收敛事实
    }

    @Test
    void preview_flagsPolicyConflictForForbid() {
        stubWorkflowFound(workflow("FORBID", 5));
        BackfillPreviewVO vo = service.preview(request(
                LocalDate.now().minusDays(2), LocalDate.now().minusDays(1), 4, false));
        assertThat(vo.getEffConc()).isEqualTo(1);
        assertThat(vo.getPolicyConflict()).isNotNull();
        assertThat(vo.getPolicyConflict().getPolicy()).isEqualTo("FORBID");
        assertThat(vo.getPolicyConflict().getEffConc()).isEqualTo(1);
    }

    // ── 双闸门（docs/07 §6.5）────────────────────────────────

    @Test
    void submit_rejectsWhenConfigChangedAfterPreview() {
        stubWorkflowFound(workflow("CONCURRENCY", 3));
        when(valueOps.get(anyString())).thenReturn("cached");
        BackfillSubmitRequest r = new BackfillSubmitRequest();
        r.setWorkflowId("WF-0001");
        r.setBizFrom(LocalDate.now().minusDays(2));
        r.setBizTo(LocalDate.now().minusDays(1));
        r.setConcurrency(2);                       // 与 preview 请求不同并发 → hash 必然不同
        r.setSequential(false);
        r.setConfirmedPreviewHash("deadbeef");
        assertThatThrownBy(() -> service.submit(r))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.BACKFILL_PREVIEW_INVALID);
        verify(batchMapper, never()).insert(any(BackfillBatch.class));
    }

    @Test
    void submit_rejectsWhenNeverPreviewedOrExpired() {
        stubWorkflowFound(workflow("CONCURRENCY", 3));
        when(valueOps.get(anyString())).thenReturn(null);   // 无缓存 = 未预览或过期
        BackfillSubmitRequest r = new BackfillSubmitRequest();
        r.setWorkflowId("WF-0001");
        r.setBizFrom(LocalDate.now().minusDays(2));
        r.setBizTo(LocalDate.now().minusDays(1));
        r.setConfirmedPreviewHash(null);
        assertThatThrownBy(() -> service.submit(r))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.BACKFILL_PREVIEW_INVALID);
        verify(redis, never()).delete(anyString());
    }

    @Test
    void submit_createsBatchRowWithoutTasks() {
        Workflow w = workflow("CONCURRENCY", 3);
        stubWorkflowFound(w);
        when(workflowMapper.selectById(10L)).thenReturn(w);
        when(valueOps.get(anyString())).thenReturn("1");    // 预览缓存存在

        BackfillSubmitRequest r = new BackfillSubmitRequest();
        r.setWorkflowId("WF-0001");
        r.setBizFrom(LocalDate.now().minusDays(2));
        r.setBizTo(LocalDate.now().minusDays(1));
        r.setSequential(true);
        r.setOverrides(Map.of("paramKey", "v2"));

        // 先 preview 拿正确 hash（请求参数与提交完全一致，含 overrides），再提交 —— 模拟前端双闸门回传
        BackfillPreviewRequest pr = request(LocalDate.now().minusDays(2), LocalDate.now().minusDays(1), null, true);
        pr.setOverrides(Map.of("paramKey", "v2"));
        String hash = service.preview(pr).getPreviewHash();
        r.setConfirmedPreviewHash(hash);

        BackfillBatchVO vo = service.submit(r);
        assertThat(vo.getStatus()).isEqualTo("RUNNING");
        assertThat(vo.getTotal()).isEqualTo(2);
        assertThat(vo.getWaiting()).isEqualTo(2);           // 提交不建任务，waiting=total

        ArgumentCaptor<BackfillBatch> captor = ArgumentCaptor.forClass(BackfillBatch.class);
        verify(batchMapper).insert(captor.capture());
        BackfillBatch saved = captor.getValue();
        assertThat(saved.getBatchId()).isEqualTo("BF-0042"); // IdGen seq 固定 42
        assertThat(saved.getBatchName()).contains("订单同步");
        assertThat(saved.getEffConcurrency()).isEqualTo(1);  // sequential → 1
        assertThat(saved.getOverrides()).isEqualTo("{\"paramKey\":\"v2\"}");
        verify(redis).delete(anyString());                   // 一次性消费
        verify(taskCreation, never()).createForBackfill(any(), any(), any(), any(), any());
    }

    // ── 批次管理 ────────────────────────────────────────────

    private BackfillBatch batch(String status) {
        BackfillBatch b = new BackfillBatch();
        b.setId(9L);
        b.setBatchId("BF-0001");
        b.setBatchName("订单同步 2026-09-01~2026-09-03");
        b.setWorkflowId(10L);
        b.setWorkflowVersionId(11L);
        b.setProjectId(20L);
        b.setStatus(status);
        b.setTotal(3);
        b.setSuccessCount(1);
        b.setFailedCount(1);
        b.setWaitingCount(1);
        b.setOverrides("{}");
        b.setSubmitter("admin");
        return b;
    }

    @SuppressWarnings("unchecked")
    private void stubBatchFound(BackfillBatch b) {
        when(batchMapper.selectOne(any(Wrapper.class))).thenReturn(b);
    }

    @Test
    void get_rejectsUnknownBatch() {
        when(batchMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThatThrownBy(() -> service.get("BF-9999"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void pause_rejectsWhenCasLost() {
        BackfillBatch b = batch("RUNNING");
        stubBatchFound(b);
        when(workflowMapper.selectById(10L)).thenReturn(workflow("CONCURRENCY", 3));
        when(batchMapper.pauseBatch(9L)).thenReturn(0);      // CAS 落空：恰好被暂停/完结
        assertThatThrownBy(() -> service.pause("BF-0001"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.BACKFILL_STATUS_FORBIDDEN);
    }

    @Test
    void resume_rejectsWhenCasLost() {
        BackfillBatch b = batch("PAUSED");
        stubBatchFound(b);
        when(workflowMapper.selectById(10L)).thenReturn(workflow("CONCURRENCY", 3));
        when(batchMapper.resumeBatch(9L)).thenReturn(0);
        assertThatThrownBy(() -> service.resume("BF-0001"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.BACKFILL_STATUS_FORBIDDEN);
    }

    @Test
    void retryFailed_rejectsWhenNoFailedDates() {
        stubBatchFound(batch("RUNNING"));
        when(backfillQuery.findFailedDates(9L)).thenReturn(List.of());
        assertThatThrownBy(() -> service.retryFailed("BF-0001"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.RULE_NOT_SATISFIED);
        verify(taskCreation, never()).createForBackfill(any(), any(), any(), any(), any());
    }

    @Test
    void retryFailed_createsNewTaskPerFailedDate() {
        BackfillBatch b = batch("RUNNING");
        stubBatchFound(b);
        Workflow w = workflow("CONCURRENCY", 3);
        when(workflowMapper.selectById(10L)).thenReturn(w);
        LocalDate d1 = LocalDate.of(2026, 9, 2);
        LocalDate d2 = LocalDate.of(2026, 9, 3);
        when(backfillQuery.findFailedDates(9L)).thenReturn(List.of(d1, d2));
        when(batchMapper.reopenBatch(9L)).thenReturn(1);

        BackfillBatchVO vo = service.retryFailed("BF-0001");
        assertThat(vo.getBatchId()).isEqualTo("BF-0001");
        verify(taskCreation).createForBackfill(b, w, d1, 40L, "{}");
        verify(taskCreation).createForBackfill(b, w, d2, 40L, "{}");
        verify(batchMapper, never()).reopenBatch(9L);        // RUNNING 无需复活
    }

    @Test
    void retryFailed_reopensDoneBatch() {
        BackfillBatch b = batch("DONE");
        stubBatchFound(b);
        when(workflowMapper.selectById(10L)).thenReturn(workflow("CONCURRENCY", 3));
        when(backfillQuery.findFailedDates(9L)).thenReturn(List.of(LocalDate.of(2026, 9, 2)));
        when(batchMapper.reopenBatch(9L)).thenReturn(1);
        service.retryFailed("BF-0001");
        verify(batchMapper).reopenBatch(9L);                 // DONE 批次复活为 RUNNING
    }

    // ── 批次任务明细 ────────────────────────────────────────

    @Test
    void pageTasks_resolvesBusinessIdToRowId() {
        BackfillBatch b = batch("RUNNING");
        stubBatchFound(b);
        Page<TaskVO> page = new Page<>(1, 50);
        when(taskQueryService.pageByBatch(9L, 1, 50)).thenReturn(page);
        IPage<TaskVO> result = service.pageTasks("BF-0001", 1, 50);
        assertThat(result).isSameAs(page);
        verify(taskQueryService).pageByBatch(9L, 1, 50);
    }
}
