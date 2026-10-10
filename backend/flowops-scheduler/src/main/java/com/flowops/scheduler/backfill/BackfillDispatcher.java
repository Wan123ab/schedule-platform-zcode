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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 回填批次滚动投放引擎（M4-S3，docs/06 §12.1 —— "不是一次性创建所有任务"）。
 *
 * <p><b>为什么必须滚动</b>：补 120 天 = 120 个任务，一次全建会让它们全部挤进 PENDING
 * 队列 —— 队列积压 + 项目额度被占满 + 正常调度被饿死。本引擎每个 tick 只把
 * "在途缺口"补齐：{@code allowed = min(eff_concurrency − inflight, 每轮上限)}。</p>
 *
 * <p><b>单 tick 五步</b>（docs/06 §12.1 原文）：</p>
 * <ol>
 *   <li>按"每日期最新任务状态"聚合刷新批次进度（幂等；全终结 → DONE）；</li>
 *   <li>只有 RUNNING 批次继续（PAUSED 在途任务继续执行但停投，§12.4）；</li>
 *   <li>待投放日期 = planned − skipped − 已投放（retry-failed 新建的日期已在已投放集合，
 *       其最新任务回到 PENDING 自然并回 inflight，不需要特殊处理）；</li>
 *   <li>逐日期经 {@link ConcurrencyGuard}（D-12 唯一入口）+ {@link TaskCreationService}
 *       （落库唯一入口）建任务；FORBID 工作流有实例在跑 → <b>整批本轮停投</b>（下轮再试，
 *       不标记失败 —— 回填不在乎迟到，在乎不失败）；</li>
 *   <li>有投放则重算计数再刷一次进度（新任务是 PENDING → inflight）。</li>
 * </ol>
 *
 * <p><b>进度口径</b>：waiting = total − success − failed − inflight（含未投放日期）；
 * 计数聚合 SQL 是"每 biz_date 取最新一条任务"（retry-failed 会保留旧 FAILED 行作审计，
 * 裸 group by 会把同一日期新旧任务各算一次，success+failed+inflight 超过 total）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackfillDispatcher {

    /** 单轮投放上限（防止单批占满 tick；eff_concurrency 之外的保险丝） */
    static final int PER_ROUND_LIMIT = 10;

    /** 在途状态（含 STOPPING —— 终止中的任务仍占着并发额度） */
    private static final Set<String> INFLIGHT =
            Set.of("PENDING", "SCHEDULING", "RUNNING", "STOPPING");
    /** 失败（进 failed_count；retry-failed 的候选） */
    private static final Set<String> FAILED_SET = Set.of("FAILED", "TIMEOUT");
    /** 成功侧终态（PARTIAL 算跑完；STOPPED 是用户主动让位） */
    private static final Set<String> SUCCESS_SET = Set.of("SUCCESS", "PARTIAL", "STOPPED");

    private final BackfillBatchMapper batchMapper;
    private final BackfillQueryMapper backfillQuery;
    private final WorkflowMapper workflowMapper;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final SubmitQueryMapper submitQuery;
    private final TaskCreationService taskCreation;
    private final ConcurrencyGuard concurrencyGuard;

    @Scheduled(fixedDelayString = "${flowops.backfill.dispatch-interval-ms:5000}")
    public void dispatchTick() {
        List<BackfillBatch> running = batchMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<BackfillBatch>lambdaQuery()
                        .eq(BackfillBatch::getStatus, "RUNNING")
                        .eq(BackfillBatch::getDeleted, false));
        for (BackfillBatch batch : running) {
            try {
                dispatchBatch(batch);
            } catch (Exception e) {
                // 单批失败不拖垮整个 tick（其他批次照常投放）
                log.error("回填批次投放异常 batch={}", batch.getBatchId(), e);
            }
        }
    }

    /** 单批投放（包级可见：单测直入）。 */
    void dispatchBatch(BackfillBatch batch) {
        // ①④⑤ 先按当前状态结算进度（全终结 → DONE，后续步骤自动跳过）
        Map<String, Integer> counts = statusCounts(batch.getId());
        refreshProgress(batch, counts);

        BackfillBatch fresh = batchMapper.selectById(batch.getId());
        if (fresh == null || !"RUNNING".equals(fresh.getStatus())) {
            return;   // DONE/PAUSED/CANCELLED：不再投放（暂停/取消不中断在途任务，§12.4）
        }

        int effConc = fresh.getEffConcurrency() == null ? 1 : fresh.getEffConcurrency();
        int inflight = bucket(counts, INFLIGHT);
        int allowed = Math.min(effConc - inflight, PER_ROUND_LIMIT);
        if (allowed <= 0) {
            return;   // 在途已满，等下一轮（②）
        }

        Set<LocalDate> skipped = fresh.getSkippedDates() == null
                ? Set.of() : Set.of(fresh.getSkippedDates());
        Set<LocalDate> dispatched = new HashSet<>(backfillQuery.findDispatchedDates(fresh.getId()));
        Workflow workflow = workflowMapper.selectById(fresh.getWorkflowId());
        WorkflowVersion version = workflowVersionMapper.selectById(fresh.getWorkflowVersionId());
        Long queueId = submitQuery.findAnyEnabledQueueIdByProject(fresh.getProjectId());
        if (workflow == null || version == null || queueId == null) {
            log.error("回填批次投放中止：工作流/版本/队列缺失 batch={} workflow={} queue={}",
                    fresh.getBatchId(), fresh.getWorkflowId(), queueId);
            return;
        }

        // D-12：回填也是触发方式之一，并发检查走唯一入口（FORBID 有实例在跑 → 整批本轮停投）
        CheckResult guard = concurrencyGuard.checkBeforeSubmit(fresh.getWorkflowId(), fresh.getProjectId());
        if (guard.action() == CheckResult.Action.REJECT_FORBID) {
            log.info("回填批次本轮停投：FORBID 工作流已有实例在运行 batch={}", fresh.getBatchId());
            return;
        }

        int created = 0;
        for (LocalDate date = fresh.getBizFrom();
                !date.isAfter(fresh.getBizTo()) && created < allowed; date = date.plusDays(1)) {
            if (skipped.contains(date) || dispatched.contains(date)) {
                continue;   // ③ 待投放 = planned − skipped − 已投放
            }
            taskCreation.create(new TaskCreationService.Spec(
                    fresh.getWorkflowId(), workflow.getWorkflowName(), fresh.getWorkflowVersionId(),
                    version.getVersionNo(), fresh.getProjectId(),
                    com.flowops.common.enums.TriggerType.BACKFILL, fresh.getId(), date,
                    fresh.getSubmitter(), queueId, 0,
                    fresh.getOverrides() == null || fresh.getOverrides().isBlank()
                            ? "{}" : fresh.getOverrides()));
            dispatched.add(date);
            created++;
        }
        if (created > 0) {
            // ⑤ 新任务是 PENDING → 重算计数再刷一次进度
            refreshProgress(fresh, statusCounts(fresh.getId()));
            log.info("回填批次本轮投放 batch={} created={} inflightBefore={}",
                    fresh.getBatchId(), created, inflight);
        }
    }

    // ── 进度结算（包级可见：单测直入）────────────────────────

    /** 按"每 biz_date 最新任务"聚合状态计数（口径见 BackfillQueryMapper.xml 注释）。 */
    Map<String, Integer> statusCounts(Long batchRowId) {
        Map<String, Integer> counts = new HashMap<>();
        for (BackfillStatusCountRow row : backfillQuery.countTasksByStatus(batchRowId)) {
            counts.merge(row.getStatus(), row.getCnt() == null ? 0 : row.getCnt().intValue(), Integer::sum);
        }
        return counts;
    }

    /** 幂等刷新批次计数（docs/06 §12.1 ④⑤；全终结 → DONE 由 SQL 内判定）。 */
    void refreshProgress(BackfillBatch batch, Map<String, Integer> counts) {
        int success = bucket(counts, SUCCESS_SET);
        int failed = bucket(counts, FAILED_SET);
        int inflight = bucket(counts, INFLIGHT);
        int total = batch.getTotal() == null ? 0 : batch.getTotal();
        int waiting = Math.max(0, total - success - failed - inflight);
        batchMapper.refreshProgress(batch.getId(), success, failed, waiting, inflight);
    }

    private int bucket(Map<String, Integer> counts, Set<String> statuses) {
        return statuses.stream().mapToInt(s -> counts.getOrDefault(s, 0)).sum();
    }
}
