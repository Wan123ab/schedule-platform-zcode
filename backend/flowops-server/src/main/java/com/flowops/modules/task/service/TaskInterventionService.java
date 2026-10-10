package com.flowops.modules.task.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flowops.common.api.ErrorCode;
import com.flowops.common.context.UserContext;
import com.flowops.common.enums.StepStatus;
import com.flowops.common.enums.TaskStatus;
import com.flowops.common.exception.BizException;
import com.flowops.domain.entity.asset.Queue;
import com.flowops.domain.entity.task.Task;
import com.flowops.domain.entity.task.TaskStep;
import com.flowops.domain.entity.workflow.WorkflowEdge;
import com.flowops.domain.mapper.asset.QueueMapper;
import com.flowops.domain.mapper.task.TaskMapper;
import com.flowops.domain.mapper.task.TaskStepMapper;
import com.flowops.domain.mapper.workflow.WorkflowEdgeMapper;
import com.flowops.modules.task.dto.RerunFailedRequest;
import com.flowops.modules.task.dto.SubmitTaskRequest;
import com.flowops.modules.task.dto.TaskDetailVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 任务人工干预（M4 S2，CONTRACT §7：stop / retry / rerun-failed / enqueue-front）。
 *
 * <p><b>与调度器的分工（D-08：只通过 DB 状态 + Redis 协作）</b>：</p>
 * <ul>
 *   <li><b>stop</b>：server 只写 {@code STOPPING} 中间态 + 停止人/原因（docs/06 §15.1 ①），
 *       调度器终止全部运行中步骤后收敛为 STOPPED —— <b>绝不直接写终态</b>（进程还在跑）；</li>
 *   <li><b>retry（整任务重跑）</b>：按 docs/06 §9.3 = <b>新建任务实例</b>（原任务保持终态）。
 *       走 {@link TaskSubmitService#create}（D-12 唯一入口：并发检查/发号/步骤初始化只发生在那里，
 *       ArchitectureTest 把 TaskMapper 访问收敛在 task.service 包 —— 重跑不是第二条 insert 通道）；</li>
 *   <li><b>rerun-failed</b>：不产生新任务编号。targets + 下游 reset 为 NOT_STARTED、清执行痕迹、
 *       保留 output_vars 于非目标步骤（变量解析链第 5 层自然复用旧值，docs/06 §9.3 的设计收益）；
 *       任务 CAS 回 RUNNING，由调度器 DagAdvancer 推进（NOT_STARTED 按入度分流是它既有的每 tick 行为）；</li>
 *   <li><b>enqueue-front</b>：仅 PENDING（docs/07 §6.4 排队语义 = PENDING + queue_position）。
 *       服务端动作 = 置 {@code priority = 100}（E-07 上限）；PENDING 任务尚不在调度器 ZSet 中，
 *       server 不代写 scheduler 的 ZSet —— score 更新由调度器步骤入队时按 task.priority 自然生效
 *       （README-M4 O-52）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskInterventionService {

    /**
     * 插队目标优先级（E-07 定案：0~100，越大越先执行；100 = 压过所有非满分任务）。
     * ⚠️ 落库字面量在 {@code TaskMapper.xml#jumpQueue}（{@code SET priority = 100}），
     * 本常量是它的 Java 侧镜像 + 单测断言锚点 —— 改一处必须同步另一处。
     */
    static final int JUMP_PRIORITY = 100;

    private final TaskMapper taskMapper;
    private final TaskStepMapper taskStepMapper;
    private final WorkflowEdgeMapper workflowEdgeMapper;
    private final QueueMapper queueMapper;
    private final TaskSubmitService submitService;
    private final TaskQueryService taskQueryService;
    private final ObjectMapper objectMapper;

    // ── 停止（docs/06 §15.1）────────────────────────────────

    @Transactional(rollbackFor = Exception.class)
    public TaskDetailVO stop(String taskId, String stopReason) {
        String reason = stopReason == null ? "" : stopReason.trim();
        if (reason.length() < 5) {
            // @Size 已在 DTO 拦一层；这里兜服务级调用（重跑/内部路径不过 Bean Validation）
            throw new BizException(ErrorCode.STOP_REASON_TOO_SHORT,
                    "停止原因至少 5 个字符，当前 " + reason.length() + " 个");
        }
        Task task = requireTask(taskId);
        if (task.getStatus() != TaskStatus.RUNNING) {
            // PRD §10.10 只定义"停止运行中任务"；PENDING/SCHEDULING 取消未定义 → 拒绝（README-M4 O-49）
            throw new BizException(ErrorCode.TASK_NOT_STOPPABLE,
                    "任务非运行中状态，不可停止: " + task.getStatus());
        }
        String operator = UserContext.get() != null ? UserContext.get().getUsername() : "system";
        int rows = taskMapper.requestStop(task.getId(), operator, reason);
        if (rows == 0) {
            // CAS 落空：并发停止或调度器恰好已收敛 —— 按不可停止处理（幂等方向安全）
            throw new BizException(ErrorCode.TASK_NOT_STOPPABLE, "任务状态已变化（并发停止或已收敛），请刷新后重试");
        }
        log.info("停止请求已受理 task={} operator={}", taskId, operator);
        return taskQueryService.get(taskId);
    }

    // ── 整任务重跑（docs/06 §9.3：新建任务实例）──────────────

    @Transactional(rollbackFor = Exception.class)
    public TaskDetailVO retry(String taskId) {
        Task task = requireTask(taskId);
        if (!isRerunnableTask(task.getStatus())) {
            throw new BizException(ErrorCode.TASK_NOT_FINISHED,
                    "任务非可重跑状态: " + task.getStatus() + "（仅 FAILED/STOPPED/TIMEOUT/PARTIAL 可整任务重跑）");
        }
        SubmitTaskRequest request = new SubmitTaskRequest();
        request.setWorkflowId(task.getWorkflowId());
        request.setProjectId(task.getProjectId());
        request.setVersionId(task.getWorkflowVersionId());   // D-11：重跑绑定原冻结版本，不是当前发布版
        request.setPriority(task.getPriority());
        request.setTargetQueueId(task.getQueueId());
        request.setBizDate(task.getBizDate());               // 重跑沿用原业务日期（README-M4 O-51）
        request.setRunParams(originalRunParams(task));
        var creation = submitService.create(request);
        log.info("整任务重跑 source={} newTask={}", taskId, creation.taskId());
        return taskQueryService.get(creation.taskId());
    }

    // ── 重跑失败步骤（docs/06 §9.3：原实例 reset + 下游）─────

    @Transactional(rollbackFor = Exception.class)
    public TaskDetailVO rerunFailed(String taskId, RerunFailedRequest request) {
        Task task = requireTask(taskId);
        // TaskStateTransitions.RERUN_SOURCES = FAILED/TIMEOUT/PARTIAL（STOPPED 走整任务重跑；
        // RUNNING 不允许 —— §9.3 ①）
        if (task.getStatus() != TaskStatus.FAILED
                && task.getStatus() != TaskStatus.TIMEOUT
                && task.getStatus() != TaskStatus.PARTIAL) {
            throw new BizException(ErrorCode.TASK_NOT_FINISHED,
                    "任务非可重跑失败步骤状态: " + task.getStatus() + "（仅 FAILED/TIMEOUT/PARTIAL）");
        }

        List<TaskStep> steps = taskStepMapper.selectList(Wrappers.<TaskStep>lambdaQuery()
                .eq(TaskStep::getTaskId, task.getId())
                .orderByAsc(TaskStep::getStepIndex));

        Set<Long> targetStepIds = resolveTargets(task, steps, request == null ? null : request.getStepInstanceIds());

        // reset targets：FAILED/TIMEOUT/STOPPED/SUCCESS 一律重跑（§9.3 ③：targets 内的 SUCCESS
        // 是失败步骤的下游，不重跑会拿到坏数据）；retry_count 保留（累加语义，XML resetForRerun）
        int reset = 0;
        for (TaskStep step : steps) {
            if (!targetStepIds.contains(step.getStepId())) {
                continue;
            }
            reset += taskStepMapper.resetForRerun(step.getId());
        }

        // 任务 CAS 回 RUNNING（RERUN_FAILED_STEPS 事件）+ 进度重算（§9.3 ④⑤）
        int finished = (int) steps.stream()
                .filter(s -> !targetStepIds.contains(s.getStepId()))
                .filter(s -> s.getStatus() == StepStatus.SUCCESS)
                .count();
        int rows = taskMapper.resumeAfterRerun(task.getId(), task.getStatus().name(), finished);
        if (rows == 0) {
            throw new BizException(ErrorCode.STATUS_CONFLICT, "任务状态已变化，请刷新后重试");
        }
        log.info("重跑失败步骤 task={} targets={} reset={} finished={}",
                taskId, targetStepIds.size(), reset, finished);
        return taskQueryService.get(taskId);
    }

    // ── 插队（docs/07 §6.4 / PRD §12.2-6）───────────────────

    @Transactional(rollbackFor = Exception.class)
    public TaskDetailVO enqueueFront(String taskId) {
        Task task = requireTask(taskId);
        if (task.getStatus() != TaskStatus.PENDING) {
            throw new BizException(ErrorCode.TASK_NOT_WAITING,
                    "任务不在等待态，不可插队: " + task.getStatus());
        }
        List<TaskStep> steps = taskStepMapper.selectList(Wrappers.<TaskStep>lambdaQuery()
                .eq(TaskStep::getTaskId, task.getId()));
        boolean hasMutexStep = steps.stream()
                .anyMatch(s -> s.getMutexGroup() != null && !s.getMutexGroup().isBlank());
        if (hasMutexStep) {
            // docs/07 §6.6：互斥组禁止优先级插队（破坏全平台串行语义）
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "互斥组任务禁止插队",
                    Map.of("rule", "MUTEX_GROUP_NO_PRIORITY"));
        }
        if (task.getQueueId() == null) {
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "任务未绑定队列，无法插队",
                    Map.of("rule", "TASK_HAS_NO_QUEUE"));
        }
        Queue queue = queueMapper.selectById(task.getQueueId());
        // PRD §12.2-6：插队前置于"队列允许"闸门（allow_jump_queue）；docs 未给专用错误码，
        // 复用 42200 + rule（README-M4 O-50）
        if (queue == null || !Boolean.TRUE.equals(queue.getAllowJumpQueue())) {
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "队列不允许插队",
                    Map.of("rule", "QUEUE_JUMP_FORBIDDEN"));
        }
        int rows = taskMapper.jumpQueue(task.getId());
        if (rows == 0) {
            throw new BizException(ErrorCode.TASK_NOT_WAITING, "任务状态已变化，请刷新后重试");
        }
        log.info("插队完成 task={} priority={}（score 由调度器步骤入队时按新优先级生效）", taskId, JUMP_PRIORITY);
        return taskQueryService.get(taskId);
    }

    // ── 内部：targets 解析（§9.3 ②）─────────────────────────

    /**
     * 目标集合 = 指定的步骤实例（映射到 workflow_step.id）或缺省全部 FAILED/TIMEOUT 步骤，
     * 再闭包其**所有下游**（拓扑可达）。下游里的 SUCCESS 也在 targets 内（§9.3 ③ ⚠️ 条）。
     */
    private Set<Long> resolveTargets(Task task, List<TaskStep> steps, List<String> stepInstanceIds) {
        Set<Long> targets = new HashSet<>();
        if (stepInstanceIds != null && !stepInstanceIds.isEmpty()) {
            Map<String, Long> byInstanceId = new HashMap<>();
            for (TaskStep step : steps) {
                byInstanceId.put(step.getStepInstanceId(), step.getStepId());
            }
            for (String instanceId : stepInstanceIds) {
                Long stepId = byInstanceId.get(instanceId);
                if (stepId == null) {
                    throw new BizException(ErrorCode.NOT_FOUND, "步骤实例不属于该任务: " + instanceId,
                            Map.of("resource_type", "TASK_STEP", "resource_id", instanceId));
                }
                targets.add(stepId);
            }
        } else {
            steps.stream()
                    .filter(s -> s.getStatus() == StepStatus.FAILED || s.getStatus() == StepStatus.TIMEOUT)
                    .forEach(s -> targets.add(s.getStepId()));
            if (targets.isEmpty()) {
                throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "没有可重跑的失败步骤",
                        Map.of("rule", "NO_FAILED_STEPS"));
            }
        }

        // 下游闭包：workflow_edge 的 sourceStepId → targetStepId 邻接表，BFS
        List<WorkflowEdge> edges = workflowEdgeMapper.selectList(Wrappers.<WorkflowEdge>lambdaQuery()
                .eq(WorkflowEdge::getWorkflowVersionId, task.getWorkflowVersionId()));
        Map<Long, List<Long>> adjacency = new HashMap<>();
        for (WorkflowEdge edge : edges) {
            adjacency.computeIfAbsent(edge.getSourceStepId(), k -> new ArrayList<>()).add(edge.getTargetStepId());
        }
        Deque<Long> queue = new ArrayDeque<>(targets);
        Set<Long> visited = new HashSet<>(targets);
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            for (Long next : adjacency.getOrDefault(current, List.of())) {
                if (visited.add(next)) {
                    targets.add(next);
                    queue.add(next);
                }
            }
        }
        return targets;
    }

    // ── 内部 ────────────────────────────────────────────────

    /** 整任务重跑允许的来源态（docs/06 §2.1 转移表行：FAILED/STOPPED/TIMEOUT/PARTIAL → 新建任务）。 */
    private static boolean isRerunnableTask(TaskStatus status) {
        return status == TaskStatus.FAILED || status == TaskStatus.STOPPED
                || status == TaskStatus.TIMEOUT || status == TaskStatus.PARTIAL;
    }

    /** 原任务的变量快照即重跑参数（快照本就是解析链第 4 层的落库形态）；坏 JSON 降级为空参不阻断。 */
    private Map<String, Object> originalRunParams(Task task) {
        String snapshot = task.getVariableSnapshot();
        if (snapshot == null || snapshot.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(snapshot, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("重跑读取原任务变量快照失败，按空参提交 task={} err={}", task.getTaskId(), e.getMessage());
            return Map.of();
        }
    }

    private Task requireTask(String taskId) {
        Task task = taskMapper.selectOne(Wrappers.<Task>lambdaQuery()
                .eq(Task::getTaskId, taskId)
                .eq(Task::getDeleted, false));
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在: " + taskId,
                    Map.of("resource_type", "TASK", "resource_id", taskId));
        }
        return task;
    }
}
