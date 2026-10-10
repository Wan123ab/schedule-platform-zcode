package com.flowops.modules.task.service;

import com.flowops.common.api.ErrorCode;
import com.flowops.common.context.UserContext;
import com.flowops.common.exception.BizException;
import com.flowops.common.guard.CheckResult;
import com.flowops.common.guard.ConcurrencyGuard;
import com.flowops.domain.dto.query.SubmitVersionRow;
import com.flowops.domain.mapper.task.SubmitQueryMapper;
import com.flowops.domain.service.TaskCreationService;
import com.flowops.modules.task.dto.SubmitTaskRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;

/**
 * 任务提交服务 —— 手动提交路径（docs/06 §6.2；D-12 并发检查入口）。
 *
 * <p><b>职责切分（M4-S3 起）</b>：本类保留"触发路径侧"的三件事 —— 并发检查（G6）、
 * 版本绑定（D-11）、队列解析；<b>落库（建任务 + 建步骤）下沉到
 * {@link TaskCreationService}</b>（domain，server 与 scheduler 的共同依赖终点）——
 * 回填投放（scheduler）与手动提交（server）从此走同一份落库逻辑，
 * "不允许任何路径直接 insert task" 这条 D-12 规则由 ArchUnit 继续守住。</p>
 *
 * <p><b>PENDING 语义</b>：提交永远建 PENDING 任务（即使并发全通过）——
 * 准入推进（PENDING→SCHEDULING）由 scheduler 的 QuotaScanner 每 tick 统一执行，
 * 单一入口、单一推进方向，避免两条路径竞争同一状态位。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskSubmitService {

    private final ConcurrencyGuard concurrencyGuard;
    private final SubmitQueryMapper submitQuery;
    private final TaskCreationService taskCreationService;
    private final ObjectMapper objectMapper;

    /** 提交结果：任务行 + 排队信息（deferred 时前端渲染"已排队"）。 */
    public record Creation(long taskRowId, String taskId, boolean deferred, long queuePosition) {}

    @Transactional(rollbackFor = Exception.class)
    public Creation create(SubmitTaskRequest request) {
        // ① 并发检查（D-12 唯一入口；docs/06 §6.4：提交时只查项目 → 工作流两层）
        CheckResult guard = concurrencyGuard.checkBeforeSubmit(request.getWorkflowId(), request.getProjectId());
        if (guard.action() == CheckResult.Action.REJECT_FORBID) {
            throw new BizException(ErrorCode.CONCURRENCY_FORBID, "并发策略 FORBID，工作流已有实例在运行",
                    Map.of("running_task_id", guard.runningTaskId() == null ? "" : guard.runningTaskId()));
        }

        // ② 版本绑定（D-11：任务强绑定发布版本）
        SubmitVersionRow version = submitQuery.resolveVersionForSubmit(
                request.getWorkflowId(), request.getVersionId());
        if (version == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "工作流不存在或没有可绑定的已发布版本");
        }
        if (!version.getProjectId().equals(request.getProjectId())) {
            throw new BizException(ErrorCode.SCOPE_EXCEEDED, "工作流不属于当前项目");
        }

        // ③ 队列解析（M1：显式指定优先，否则项目绑定队列兜底）
        Long queueId = request.getTargetQueueId() != null
                ? request.getTargetQueueId()
                : submitQuery.findAnyEnabledQueueIdByProject(request.getProjectId());
        if (queueId == null) {
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "项目未绑定可用队列，无法提交");
        }

        // ④⑤ 建任务 + 建步骤（落库唯一入口下沉 domain；docs/06 §6.2 ①）
        TaskCreationService.Creation creation = taskCreationService.create(new TaskCreationService.Spec(
                request.getWorkflowId(), version.getWorkflowName(), version.getVersionId(), version.getVersionNo(),
                request.getProjectId(), com.flowops.common.enums.TriggerType.MANUAL, null,
                request.getBizDate() != null ? request.getBizDate() : LocalDate.now(),
                UserContext.get() != null ? UserContext.get().getUsername() : "system",
                queueId, request.getPriority(), snapshot(request.getRunParams())));

        log.info("任务已提交 task={} workflow={} version={} deferred={}（queuePosition={}）",
                creation.taskId(), request.getWorkflowId(), version.getVersionNo(),
                guard.deferred(), guard.runningCount());
        return new Creation(creation.taskRowId(), creation.taskId(), guard.deferred(), guard.runningCount());
    }

    /** 变量快照初始层：触发时参数（解析链第 4 层，完整 6 层解析在 M3 落地）。 */
    private String snapshot(Map<String, Object> runParams) {
        try {
            return objectMapper.writeValueAsString(runParams == null ? Map.of() : runParams);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
