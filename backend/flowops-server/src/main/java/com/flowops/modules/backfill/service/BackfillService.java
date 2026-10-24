package com.flowops.modules.backfill.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowops.common.api.ErrorCode;
import com.flowops.common.context.UserContext;
import com.flowops.common.exception.BizException;
import com.flowops.common.util.IdGen;
import com.flowops.domain.entity.task.BackfillBatch;
import com.flowops.domain.entity.task.Task;
import com.flowops.domain.entity.workflow.Workflow;
import com.flowops.domain.mapper.project.ProjectMapper;
import com.flowops.domain.mapper.task.BackfillBatchMapper;
import com.flowops.domain.mapper.task.BackfillQueryMapper;
import com.flowops.domain.mapper.task.SubmitQueryMapper;
import com.flowops.domain.mapper.workflow.WorkflowMapper;
import com.flowops.domain.dto.query.BackfillDateRow;
import com.flowops.domain.dto.query.BackfillStatusCountRow;
import com.flowops.domain.entity.project.Project;
import com.flowops.modules.backfill.dto.BackfillBatchVO;
import com.flowops.modules.backfill.dto.BackfillPreviewRequest;
import com.flowops.modules.backfill.dto.BackfillPreviewVO;
import com.flowops.modules.backfill.dto.BackfillSubmitRequest;
import com.flowops.modules.task.service.TaskCreationServiceBridge;
import com.flowops.modules.task.service.TaskQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 回填批次（M4-S3，CONTRACT §8 + docs/06 §12）。
 *
 * <p><b>双闸门（CONTRACT §8-1）</b>：preview 生成 SHA-256（canonical 配置串）并存 Redis
 * {@code flowops:backfill:preview:{hash}}，5 分钟 TTL；提交时服务端<b>重算比对</b> + 校验
 * 预览缓存存在 —— 跳过预览直提 / 预览后改配置 / 预览过期，全部 42231。</p>
 *
 * <p><b>投放模型</b>：提交只建批次行（RUNNING + waiting=total），<b>不建任务</b>；
 * 滚动投放由 scheduler 的 BackfillDispatcher 按 eff_concurrency 逐步执行（docs/06 §12.1 ——
 * 一次补 120 天全部进队列会打爆项目额度）。批次进度是派生态，由投放引擎按
 * "日期 × 最新任务状态" 聚合刷新。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackfillService {

    /** 单批业务日期上限（CONTRACT §8-5；超出 42232） */
    static final int MAX_DATES = 120;

    /** 预览缓存 TTL（docs/07 §6.5：5 分钟） */
    static final Duration PREVIEW_TTL = Duration.ofMinutes(5);

    static final String PREVIEW_KEY_PREFIX = "flowops:backfill:preview:";

    /** 回填窗口：近 180 天，且不晚于昨天（CONTRACT §8-4） */
    private static final int WINDOW_DAYS = 180;

    /** 冲突窗口 ±30 分钟（docs/06 §12.3） */
    private static final int CONFLICT_WINDOW_MINUTES = 30;

    private final WorkflowMapper workflowMapper;
    private final ProjectMapper projectMapper;
    private final BackfillBatchMapper batchMapper;
    private final BackfillQueryMapper backfillQuery;
    private final SubmitQueryMapper submitQuery;
    private final TaskQueryService taskQueryService;
    private final TaskCreationServiceBridge taskCreation;
    private final IdGen idGen;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    /** 越权/不存在的区分（O-77）：backfill_batch 已登记 PROJECT 数据权限（docs/07 §5.4）。 */
    private final com.flowops.modules.governance.scope.ScopeGuard scopeGuard;

    /** 预览结果（内部传递：计划日期 + 冲突 + hash，提交时复用同一套校验）。 */
    private record Plan(List<LocalDate> planned, List<LocalDate> skipped, long conflictCount,
                        int effConc, Workflow workflow, String hash) {}

    // ── 预览（不落库）───────────────────────────────────────

    public BackfillPreviewVO preview(BackfillPreviewRequest request) {
        Plan plan = buildPlan(request);
        BackfillPreviewVO vo = new BackfillPreviewVO();
        DateTimeFormatter time = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        ZoneId zone = ZoneId.systemDefault();
        List<BackfillPreviewVO.PlannedTask> tasks = new ArrayList<>();
        int seq = 0;
        for (LocalDate date : plan.planned()) {
            BackfillPreviewVO.PlannedTask t = new BackfillPreviewVO.PlannedTask();
            t.setBizDate(date);
            t.setPlannedTaskId("P-" + String.format("%04d", ++seq));
            t.setPlannedAt(time.format(plannedAt(date, zone)));
            tasks.add(t);
        }
        // 冲突二次回填（buildPlan 已算过一遍；这里给每行挂 conflict 明细）
        long conflicts = 0;
        for (BackfillPreviewVO.PlannedTask t : tasks) {
            Map<String, Object> conflict = findConflict(plan.workflow(), t.getBizDate(), zone);
            if (conflict != null) {
                conflicts++;
                t.setConflict(conflict);
            }
        }
        vo.setTasks(tasks);
        vo.setSkippedDates(plan.skipped());
        vo.setPlannedCount(plan.planned().size());
        vo.setConflictCount(conflicts);
        vo.setEffConc(plan.effConc());
        int requested = request.getConcurrency() == null ? 1 : request.getConcurrency();
        // 有收敛事实才提示：FORBID（并发必降 1）或 sequential（docs/06 §12.2 ② 的前端提示要求），
        // 或被工作流上限收敛（effConc < 请求并发）
        if ("FORBID".equals(plan.workflow().getConcurrencyPolicy())
                || Boolean.TRUE.equals(request.getSequential())
                || plan.effConc() < requested) {
            BackfillPreviewVO.PolicyConflict pc = new BackfillPreviewVO.PolicyConflict();
            pc.setPolicy(plan.workflow().getConcurrencyPolicy());
            pc.setMaxParallelRuns(plan.workflow().getMaxParallelRuns());
            pc.setEffConc(plan.effConc());
            vo.setPolicyConflict(pc);
        }
        vo.setPreviewHash(plan.hash());
        return vo;
    }

    // ── 提交（双闸门第二道）─────────────────────────────────

    @Transactional(rollbackFor = Exception.class)
    public BackfillBatchVO submit(BackfillSubmitRequest request) {
        Plan plan = buildPlan(request);
        // 闸门 ①：配置未变（服务端重算比对）
        if (!plan.hash().equals(request.getConfirmedPreviewHash())) {
            throw new BizException(ErrorCode.BACKFILL_PREVIEW_INVALID,
                    "预览后配置已变更，请重新预览", Map.of("expected", plan.hash()));
        }
        // 闸门 ②：确实预览过且未过期（5 分钟 TTL）
        String key = PREVIEW_KEY_PREFIX + plan.hash();
        String cached = redis.opsForValue().get(key);
        if (cached == null) {
            throw new BizException(ErrorCode.BACKFILL_PREVIEW_INVALID,
                    "未预览或预览已过期（5 分钟），请先预览");
        }
        redis.delete(key);   // 一次性：同一份预览不允许重复提交

        Long queueId = submitQuery.findAnyEnabledQueueIdByProject(plan.workflow().getProjectId());
        BackfillBatch batch = new BackfillBatch();
        batch.setBatchId(idGen.next("BF", "backfill"));
        batch.setBatchName(plan.workflow().getWorkflowName() + " " + request.getBizFrom() + "~" + request.getBizTo());
        batch.setWorkflowId(plan.workflow().getId());
        batch.setWorkflowVersionId(plan.workflow().getCurrentVersionId());
        batch.setProjectId(plan.workflow().getProjectId());
        batch.setBizFrom(request.getBizFrom());
        batch.setBizTo(request.getBizTo());
        batch.setConcurrency(request.getConcurrency() == null ? 1 : request.getConcurrency());
        batch.setSequential(request.getSequential() == null || request.getSequential());
        batch.setEffConcurrency(plan.effConc());
        batch.setStatus("RUNNING");
        batch.setTotal(plan.planned().size());
        batch.setSuccessCount(0);
        batch.setFailedCount(0);
        batch.setWaitingCount(plan.planned().size());
        batch.setSkippedDates(plan.skipped().toArray(new LocalDate[0]));
        batch.setPreviewHash(plan.hash());
        batch.setSubmittedAt(OffsetDateTime.now());
        batch.setOverrides(toJson(request.getOverrides() == null ? Map.of() : request.getOverrides()));
        batch.setSubmitter(UserContext.get() != null ? UserContext.get().getUsername() : "system");
        batch.setDeleted(false);
        batchMapper.insert(batch);
        log.info("回填批次已创建 batch={} workflow={} dates={} effConc={}",
                batch.getBatchId(), plan.workflow().getWorkflowId(), plan.planned().size(), plan.effConc());
        return toVO(batch, false);
    }

    // ── 批次管理 ────────────────────────────────────────────

    public com.baomidou.mybatisplus.core.metadata.IPage<BackfillBatchVO> list(String status, long page, long size) {
        Page<BackfillBatch> result = batchMapper.selectPage(new Page<>(page, Math.min(size, 200)),
                Wrappers.<BackfillBatch>lambdaQuery()
                        .eq(BackfillBatch::getDeleted, false)
                        .eq(status != null && !status.isBlank(), BackfillBatch::getStatus, status)
                        .orderByDesc(BackfillBatch::getSubmittedAt));
        return result.convert(b -> toVO(b, false));
    }

    public com.baomidou.mybatisplus.core.metadata.IPage<BackfillBatchVO> history(long page, long size) {
        Page<BackfillBatch> result = batchMapper.selectPage(new Page<>(page, Math.min(size, 200)),
                Wrappers.<BackfillBatch>lambdaQuery()
                        .eq(BackfillBatch::getDeleted, false)
                        .in(BackfillBatch::getStatus, "DONE", "CANCELLED")
                        .orderByDesc(BackfillBatch::getSubmittedAt));
        return result.convert(b -> toVO(b, false));
    }

    public BackfillBatchVO get(String batchId) {
        return toVO(requireBatch(batchId), true);
    }

    /** 批次任务明细（CONTRACT §8 {@code GET /backfills/{batchId}/tasks}）。 */
    public com.baomidou.mybatisplus.core.metadata.IPage<com.flowops.modules.task.dto.TaskVO> pageTasks(
            String batchId, long page, long size) {
        BackfillBatch batch = requireBatch(batchId);
        return taskQueryService.pageByBatch(batch.getId(), page, size);
    }

    @Transactional(rollbackFor = Exception.class)
    public BackfillBatchVO pause(String batchId) {
        BackfillBatch batch = requireBatch(batchId);
        if (batchMapper.pauseBatch(batch.getId()) == 0) {
            throw new BizException(ErrorCode.BACKFILL_STATUS_FORBIDDEN,
                    "批次非 RUNNING 状态，不可暂停: " + batch.getStatus());
        }
        return toVO(requireBatch(batchId), true);
    }

    @Transactional(rollbackFor = Exception.class)
    public BackfillBatchVO resume(String batchId) {
        BackfillBatch batch = requireBatch(batchId);
        if (batchMapper.resumeBatch(batch.getId()) == 0) {
            throw new BizException(ErrorCode.BACKFILL_STATUS_FORBIDDEN,
                    "批次非 PAUSED 状态，不可继续: " + batch.getStatus());
        }
        return toVO(requireBatch(batchId), true);
    }

    /**
     * 重跑失败（CONTRACT §8：失败计数并入 waiting）。实现：为失败日期<b>新建任务</b>
     * （TriggerType=BACKFILL、同批次标记；原失败任务保留作审计）—— 日期的"最新任务状态"
     * 变为 PENDING，进度聚合自动把 failed 并回 waiting；DONE 批次复活为 RUNNING。
     * README-M4 O-59。
     */
    @Transactional(rollbackFor = Exception.class)
    public BackfillBatchVO retryFailed(String batchId) {
        BackfillBatch batch = requireBatch(batchId);
        if ("CANCELLED".equals(batch.getStatus())) {
            throw new BizException(ErrorCode.BACKFILL_STATUS_FORBIDDEN, "批次已取消，不可重跑失败");
        }
        List<LocalDate> failedDates = backfillQuery.findFailedDates(batch.getId());
        if (failedDates.isEmpty()) {
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "批次没有失败日期可重跑",
                    Map.of("rule", "NO_FAILED_DATES"));
        }
        Workflow workflow = workflowMapper.selectById(batch.getWorkflowId());
        Long queueId = submitQuery.findAnyEnabledQueueIdByProject(batch.getProjectId());
        int created = 0;
        for (LocalDate date : failedDates) {
            taskCreation.createForBackfill(batch, workflow, date, queueId, batch.getOverrides());
            created++;
        }
        if ("DONE".equals(batch.getStatus()) && batchMapper.reopenBatch(batch.getId()) == 0) {
            throw new BizException(ErrorCode.BACKFILL_STATUS_FORBIDDEN, "批次状态已变化，请刷新后重试");
        }
        log.info("回填批次重跑失败 batch={} dates={} created={}", batchId, failedDates.size(), created);
        return toVO(requireBatch(batchId), true);
    }

    // ── 内部：计划构建（preview/submit 共用同一套校验）───────

    private Plan buildPlan(BackfillPreviewRequest request) {
        if (request.getBizFrom().isAfter(request.getBizTo())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "业务日期起始晚于结束: " + request.getBizFrom() + " > " + request.getBizTo());
        }
        Workflow workflow = workflowMapper.selectOne(Wrappers.<Workflow>lambdaQuery()
                .eq(Workflow::getWorkflowId, request.getWorkflowId())
                .eq(Workflow::getDeleted, false));
        if (workflow == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "工作流不存在: " + request.getWorkflowId(),
                    Map.of("resource_type", "WORKFLOW", "resource_id", request.getWorkflowId()));
        }
        if (workflow.getCurrentVersionId() == null) {
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "工作流没有已发布版本，无法回填");
        }
        int concurrency = request.getConcurrency() == null ? 1 : Math.max(1, request.getConcurrency());
        boolean sequential = request.getSequential() == null || request.getSequential();
        int effConc = effConc(workflow, concurrency, sequential);

        // 日期切分：越窗（早于 today-180 / 晚于昨天）跳过
        LocalDate windowStart = LocalDate.now().minusDays(WINDOW_DAYS);
        LocalDate windowEnd = LocalDate.now().minusDays(1);
        List<LocalDate> planned = new ArrayList<>();
        List<LocalDate> skipped = new ArrayList<>();
        for (LocalDate d = request.getBizFrom(); !d.isAfter(request.getBizTo()); d = d.plusDays(1)) {
            if (d.isBefore(windowStart) || d.isAfter(windowEnd)) {
                skipped.add(d);
            } else {
                planned.add(d);
            }
        }
        if (planned.size() > MAX_DATES) {
            throw new BizException(ErrorCode.BACKFILL_RANGE_EXCEEDED,
                    "业务日期数超限: " + planned.size() + " > " + MAX_DATES,
                    Map.of("max", MAX_DATES));
        }
        String hash = previewHash(request, workflow);
        return new Plan(planned, skipped, 0, effConc, workflow, hash);
    }

    /** 收敛公式（docs/06 §12.2 原文）：sequential ? 1 : (FORBID ? 1 : min(并发, 工作流上限))。 */
    static int effConc(Workflow workflow, int concurrency, boolean sequential) {
        if (sequential) {
            return 1;
        }
        if ("FORBID".equals(workflow.getConcurrencyPolicy())) {
            return 1;
        }
        Integer max = workflow.getMaxParallelRuns();
        return max == null ? concurrency : Math.min(concurrency, max);
    }

    /** 计划投递时刻 = 业务日期 00:00（口径与偏离登记见 README-M4 O-56）。 */
    static OffsetDateTime plannedAt(LocalDate date, ZoneId zone) {
        return OffsetDateTime.of(LocalDateTime.of(date, LocalTime.MIDNIGHT), zone.getRules().getOffset(java.time.Instant.now()));
    }

    private Map<String, Object> findConflict(Workflow workflow, LocalDate date, ZoneId zone) {
        OffsetDateTime planned = plannedAt(date, zone);
        BackfillDateRow row = backfillQuery.findConflictTask(
                workflow.getId(), date,
                planned.minusMinutes(CONFLICT_WINDOW_MINUTES),
                planned.plusMinutes(CONFLICT_WINDOW_MINUTES));
        return row == null ? null : Map.of("taskId", row.getTaskId(), "status", row.getStatus());
    }

    /**
     * 预览哈希 = SHA-256(canonical 配置串)（docs/07 §6.5：
     * workflow_id/biz_from/biz_to/concurrency/sequential/overrides/version_id）。
     * overrides 键排序后序列化，保证同一配置算出同一哈希。
     */
    private String previewHash(BackfillPreviewRequest request, Workflow workflow) {
        try {
            Map<String, Object> overrides = request.getOverrides() == null
                    ? Map.of() : new TreeMap<>(request.getOverrides());
            String canonical = String.join("|",
                    String.valueOf(workflow.getId()),
                    request.getBizFrom().toString(),
                    request.getBizTo().toString(),
                    String.valueOf(request.getConcurrency() == null ? 1 : request.getConcurrency()),
                    String.valueOf(request.getSequential() == null || request.getSequential()),
                    objectMapper.writeValueAsString(overrides),
                    String.valueOf(workflow.getCurrentVersionId()));
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new BizException(ErrorCode.DEPENDENCY_UNAVAILABLE, "预览哈希计算失败: " + e.getMessage());
        }
    }

    private BackfillBatch requireBatch(String batchId) {
        BackfillBatch batch = batchMapper.selectOne(Wrappers.<BackfillBatch>lambdaQuery()
                .eq(BackfillBatch::getBatchId, batchId)
                .eq(BackfillBatch::getDeleted, false));
        if (batch == null) {
            // 同 O-77：批次按 project_id 做了行级过滤，查不到时不能一律说"不存在"。
            throw scopeGuard.notVisible("BACKFILL_BATCH", batchId,
                    () -> batchMapper.selectCount(Wrappers.<BackfillBatch>lambdaQuery()
                            .eq(BackfillBatch::getBatchId, batchId)
                            .eq(BackfillBatch::getDeleted, false)) > 0);
        }
        return batch;
    }

    private BackfillBatchVO toVO(BackfillBatch batch, boolean withFailures) {
        BackfillBatchVO vo = new BackfillBatchVO();
        vo.setBatchId(batch.getBatchId());
        vo.setBatchName(batch.getBatchName());
        Workflow w = workflowMapper.selectById(batch.getWorkflowId());
        vo.setWorkflowId(w == null ? null : w.getWorkflowId());
        vo.setWorkflowName(batch.getBatchName());
        Project p = batch.getProjectId() == null ? null : projectMapper.selectById(batch.getProjectId());
        vo.setProjectId(p == null ? null : p.getProjectId());
        vo.setBizFrom(batch.getBizFrom());
        vo.setBizTo(batch.getBizTo());
        vo.setConcurrency(batch.getConcurrency());
        vo.setSequential(batch.getSequential());
        vo.setEffConc(batch.getEffConcurrency());
        vo.setStatus(batch.getStatus());
        vo.setTotal(batch.getTotal());
        vo.setSuccess(batch.getSuccessCount());
        vo.setFailed(batch.getFailedCount());
        vo.setWaiting(batch.getWaitingCount());
        vo.setSubmitter(batch.getSubmitter());
        vo.setSubmittedAt(batch.getSubmittedAt());
        vo.setFinishedAt(batch.getFinishedAt());
        vo.setDurationMs(batch.getDurationMs());
        if (withFailures) {
            vo.setFailures(taskQueryService.listBatchFailures(batch.getId(), 20).stream().map(t -> {
                BackfillBatchVO.Failure f = new BackfillBatchVO.Failure();
                f.setBizDate(t.getBizDate());
                f.setTaskId(t.getTaskId());
                f.setReason(t.getFailReason());
                return f;
            }).collect(Collectors.toList()));
        }
        return vo;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
