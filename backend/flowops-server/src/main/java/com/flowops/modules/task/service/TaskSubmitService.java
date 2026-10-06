package com.flowops.modules.task.service;

import com.flowops.common.api.ErrorCode;
import com.flowops.common.exception.BizException;
import com.flowops.common.guard.CheckResult;
import com.flowops.common.guard.ConcurrencyGuard;
import com.flowops.domain.entity.task.Task;
import com.flowops.domain.entity.task.TaskStep;
import com.flowops.domain.dto.query.StepDefRow;
import com.flowops.domain.dto.query.SubmitVersionRow;
import com.flowops.domain.mapper.concurrency.SchedulingQueryMapper;
import com.flowops.domain.mapper.task.SubmitQueryMapper;
import com.flowops.domain.mapper.task.TaskMapper;
import com.flowops.domain.mapper.task.TaskStepMapper;
import com.flowops.modules.task.dto.SubmitTaskRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 任务提交服务 —— <b>所有触发路径的唯一入口</b>（D-12，docs/06 §6.2）。
 *
 * <p><b>为什么收口到一个方法</b>：并发检查（D-12）、版本绑定（D-11）、编号发号（M-09）、
 * 步骤实例初始化四件事只允许在这里发生。定时触发（scheduler）、回填（M4）、API 触发（M5）
 * 都必须调用 {@link #create}，<b>不允许任何路径直接 insert task</b>——
 * 这条规则由 ArchUnit 单测断言（ArchitectureTest），不靠 code review。</p>
 *
 * <p><b>PENDING 语义</b>：提交永远建 PENDING 任务（即使并发全通过）——
 * 准入推进（PENDING→SCHEDULING）由 scheduler 的 QuotaScanner 每 tick 统一执行，
 * 单一入口、单一推进方向，避免两条路径竞争同一状态位。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskSubmitService {

    private static final DateTimeFormatter TASK_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String SEQ_ENQUEUE = "flowops:seq:enqueue";
    private static final String SEQ_TASK_PREFIX = "flowops:seq:task:";

    private final ConcurrencyGuard concurrencyGuard;
    private final SubmitQueryMapper submitQuery;
    private final SchedulingQueryMapper schedulingQuery;
    private final TaskMapper taskMapper;
    private final TaskStepMapper taskStepMapper;
    private final StringRedisTemplate redis;
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

        // ④ 建任务实例（PENDING；docs/01 §5.1 ③）
        String taskId = nextTaskId();
        Task task = new Task();
        task.setTaskId(taskId);
        task.setWorkflowId(request.getWorkflowId());
        task.setWorkflowName(version.getWorkflowName());
        task.setWorkflowVersionId(version.getVersionId());
        task.setWorkflowVersion(version.getVersionNo());
        task.setProjectId(request.getProjectId());
        task.setTriggerType(com.flowops.common.enums.TriggerType.MANUAL);   // 定时/回填路径接入后按来源传参
        task.setBizDate(request.getBizDate() != null ? request.getBizDate() : LocalDate.now());
        task.setSubmitter(com.flowops.common.context.UserContext.get() != null
                ? com.flowops.common.context.UserContext.get().getUsername() : "system");
        task.setQueueId(queueId);
        task.setPriority(request.getPriority() == null ? 0 : request.getPriority());
        task.setStatus(com.flowops.common.enums.TaskStatus.PENDING);
        task.setEnqueueSeq(nextEnqueueSeq());
        task.setVariableSnapshot(snapshot(request.getRunParams()));
        task.setSubmitAt(OffsetDateTime.now());
        taskMapper.insert(task);

        // ⑤ 建步骤实例（实例数 = 版本步骤数，docs/05 R18；enqueue_seq 全局发号，M-09）
        List<StepDefRow> defs = schedulingQuery.findStepDefsByVersion(version.getVersionId()).stream()
                .filter(d -> !"NOTE".equals(d.getStepType()))
                .sorted(java.util.Comparator.comparing(StepDefRow::getStepId))
                .toList();
        if (defs.isEmpty()) {
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "工作流版本没有可执行步骤（DAG 校验规则 1）");
        }
        int index = 0;
        for (StepDefRow def : defs) {
            TaskStep step = new TaskStep();
            step.setStepInstanceId("SI-" + nextEnqueueSeq());   // M1 用 seq 充当实例编号，M3 起走独立编号器
            step.setTaskId(task.getId());
            step.setStepId(def.getStepId());
            step.setStepName(def.getStepName());
            step.setStepIndex(index++);
            step.setStatus(com.flowops.common.enums.StepStatus.NOT_STARTED);
            step.setEnqueueSeq(nextEnqueueSeq());
            step.setRetryCount(0);
            taskStepMapper.insert(step);
        }

        log.info("任务已创建 task={} workflow={} version={} steps={} deferred={}（queuePosition={}）",
                taskId, request.getWorkflowId(), version.getVersionNo(), defs.size(),
                guard.deferred(), guard.runningCount());
        return new Creation(task.getId(), taskId, guard.deferred(), guard.runningCount());
    }

    /** 业务编号：TASK-yyyyMMdd-####（日序列 Redis INCR，跨日归零；docs/05 §6.2）。 */
    private String nextTaskId() {
        String date = TASK_DATE.format(LocalDate.now());
        Long seq = redis.opsForValue().increment(SEQ_TASK_PREFIX + date);
        // DB uk_task_task_id 兜底唯一（docs/05 §6.2）；Redis 不可用时降级随机段避免阻塞提交
        long n = seq != null ? seq : (System.currentTimeMillis() % 100000);
        return "TASK-" + date + "-" + n;
    }

    /** 全局入队序号（M-09：Redis INCR 严格单调，同毫秒并列会破坏 FIFO 确定性）。 */
    private long nextEnqueueSeq() {
        Long seq = redis.opsForValue().increment(SEQ_ENQUEUE);
        if (seq == null) {
            throw new BizException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Redis 不可用，无法发号");
        }
        return seq;
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
