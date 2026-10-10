package com.flowops.modules.task.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowops.common.api.ErrorCode;
import com.flowops.common.enums.TaskStatus;
import com.flowops.common.enums.TriggerType;
import com.flowops.common.exception.BizException;
import com.flowops.domain.dto.query.TaskLogRow;
import com.flowops.domain.dto.query.TaskStepRetryRow;
import com.flowops.domain.entity.asset.Cluster;
import com.flowops.domain.entity.asset.OperatorVersion;
import com.flowops.domain.entity.asset.Queue;
import com.flowops.domain.entity.project.Project;
import com.flowops.domain.entity.task.Task;
import com.flowops.domain.entity.task.TaskStep;
import com.flowops.domain.entity.workflow.Trigger;
import com.flowops.domain.entity.workflow.Workflow;
import com.flowops.domain.mapper.asset.ClusterMapper;
import com.flowops.domain.mapper.asset.OperatorVersionMapper;
import com.flowops.domain.mapper.asset.QueueMapper;
import com.flowops.domain.mapper.project.ProjectMapper;
import com.flowops.domain.mapper.task.RetryQueryMapper;
import com.flowops.domain.mapper.task.TaskLogMapper;
import com.flowops.domain.mapper.task.TaskMapper;
import com.flowops.domain.mapper.task.TaskStepMapper;
import com.flowops.domain.mapper.workflow.TriggerMapper;
import com.flowops.domain.mapper.workflow.WorkflowMapper;
import com.flowops.modules.task.converter.TaskConverter;
import com.flowops.modules.task.dto.TaskDetailVO;
import com.flowops.modules.task.dto.TaskLogVO;
import com.flowops.modules.task.dto.TaskStepVO;
import com.flowops.modules.task.dto.TaskVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 任务查询（M4 S1：列表 / 详情 / 步骤 / 日志）。
 *
 * <p><b>三件必须在这里做、但不能在 Converter 里做的事</b>：</p>
 * <ol>
 *   <li><b>外键 → 业务编号</b>（D-27）。本层持有被引用表的 Mapper，按 id 批量翻译；
 *       绝不把 bigint 原样出网，也绝不让 MapStruct 做 {@code Long→String} 的"数字字符串"转换。</li>
 *   <li><b>jsonb → 嵌套对象</b>。实体字段是 String（{@code JsonbTypeHandler}），对外要对象；
 *       坏数据（历史脏行 / 手工改库）解析失败时给 {@code null} 并记日志，<b>不让整个查询 500</b>。</li>
 *   <li><b>派生集合</b>（重试历史）。同一次请求里对所有步骤<b>批量</b>取一次，
 *       再按 {@code taskStepId} 分组挂到各自 VO 上 —— 避免 N+1。</li>
 * </ol>
 *
 * <p><b>行级数据权限不用在这里写</b>：{@code task}/{@code task_step} 已登记到
 * {@code FlowopsDataPermissionHandler}，MyBatis 拦截器按 {@code ScopeContext} 自动注入
 * {@code project_id}/{@code cluster_id} 条件（docs/07 §5.3）。本层只需保证查询是
 * <b>不带别名的裸表 lambdaQuery</b>，否则注入的列限定会落到不存在的表名上。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskQueryService {

    /** 列表页单页上限（与 docs/07 §7.4 / 工作流列表口径一致）。 */
    private static final long MAX_PAGE_SIZE = 200;

    /** 日志单次读取上限（防止一次拉走几十万行打爆前端与网络）。 */
    private static final int MAX_LOG_LIMIT = 5000;

    private final TaskMapper taskMapper;
    private final TaskStepMapper taskStepMapper;
    private final TaskLogMapper taskLogMapper;
    private final RetryQueryMapper retryQueryMapper;
    private final TaskConverter converter;
    private final ObjectMapper objectMapper;

    private final WorkflowMapper workflowMapper;
    private final ProjectMapper projectMapper;
    private final ClusterMapper clusterMapper;
    private final QueueMapper queueMapper;
    private final TriggerMapper triggerMapper;
    private final OperatorVersionMapper operatorVersionMapper;

    // ── 列表 ────────────────────────────────────────────────

    public IPage<TaskVO> page(long page, long size, String status, String workflowBusinessId,
                              String projectBusinessId, String clusterBusinessId,
                              String triggerType, LocalDate bizDateFrom, LocalDate bizDateTo,
                              String keyword) {
        if (bizDateFrom != null && bizDateTo != null && bizDateFrom.isAfter(bizDateTo)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "业务日期起始晚于结束: " + bizDateFrom + " > " + bizDateTo);
        }
        Long workflowId = blank(workflowBusinessId) ? null : requireWorkflowId(workflowBusinessId);
        Long projectId = blank(projectBusinessId) ? null : requireProjectId(projectBusinessId);
        Long clusterId = blank(clusterBusinessId) ? null : requireClusterId(clusterBusinessId);
        TaskStatus statusEnum = parseStatus(status);
        TriggerType triggerEnum = parseTriggerType(triggerType);

        Page<Task> result = taskMapper.selectPage(new Page<>(page, Math.min(size, MAX_PAGE_SIZE)),
                Wrappers.<Task>lambdaQuery()
                        .eq(Task::getDeleted, false)
                        .eq(statusEnum != null, Task::getStatus, statusEnum)
                        .eq(workflowId != null, Task::getWorkflowId, workflowId)
                        .eq(projectId != null, Task::getProjectId, projectId)
                        .eq(clusterId != null, Task::getClusterId, clusterId)
                        .eq(triggerEnum != null, Task::getTriggerType, triggerEnum)
                        .ge(bizDateFrom != null, Task::getBizDate, bizDateFrom)
                        .le(bizDateTo != null, Task::getBizDate, bizDateTo)
                        .and(keyword != null && !keyword.isBlank(), w -> w
                                .like(Task::getTaskId, keyword)
                                .or().like(Task::getWorkflowName, keyword))
                        .orderByDesc(Task::getSubmitAt));

        // 列表只翻译"能点进去"的那一个业务编号（工作流）。其余外键留给详情页 ——
        // 列表每页最多 200 行，逐行补 5 张表的编号会白白多出 4 次批量查询。
        Map<Long, String> workflowCodes = workflowCodes(result.getRecords().stream()
                .map(Task::getWorkflowId).filter(Objects::nonNull).collect(Collectors.toSet()));
        return result.convert(t -> {
            TaskVO vo = converter.toVO(t);
            vo.setWorkflowId(workflowCodes.get(t.getWorkflowId()));
            return vo;
        });
    }

    // ── 详情 ────────────────────────────────────────────────

    public TaskDetailVO get(String taskId) {
        Task task = requireTask(taskId);
        TaskDetailVO vo = converter.toDetailVO(task);
        fillBusinessCodes(vo, task);
        vo.setVariableSnapshot(parseJson(task.getVariableSnapshot()));
        vo.setDiagnosisInfo(parseJson(task.getDiagnosisInfo()));
        return vo;
    }

    // ── 步骤 ────────────────────────────────────────────────

    public List<TaskStepVO> steps(String taskId) {
        Task task = requireTask(taskId);
        List<TaskStep> stepEntities = taskStepMapper.selectList(Wrappers.<TaskStep>lambdaQuery()
                .eq(TaskStep::getTaskId, task.getId())
                .orderByAsc(TaskStep::getStepIndex));

        // 重试历史一次批量取、按 taskStepId 分组（避免 N+1）
        Map<Long, List<TaskStepRetryRow>> retriesByStep = loadRetries(stepEntities);
        // 算子版本业务编号一次批量取（D-27）
        Map<Long, String> versionCodes = operatorVersionCodes(stepEntities.stream()
                .map(TaskStep::getOperatorVersionId).filter(Objects::nonNull).collect(Collectors.toSet()));

        return stepEntities.stream().map(e -> {
            TaskStepVO vo = converter.toStepVO(e);
            vo.setOperatorVersionId(versionCodes.get(e.getOperatorVersionId()));
            vo.setFailReasonDetail(parseJson(e.getFailReasonDetail()));
            vo.setResourceRequest(parseJson(e.getResourceRequest()));
            vo.setResourceActual(parseJson(e.getResourceActual()));
            vo.setOutputVars(parseJson(e.getOutputVars()));
            vo.setBlockReason(parseJson(e.getBlockReason()));
            vo.setRetryHistory(retriesByStep.getOrDefault(e.getId(), List.of()).stream()
                    .sorted((a, b) -> Integer.compare(nullSafe(a.getAttemptNo()), nullSafe(b.getAttemptNo())))
                    .map(converter::toRetryVO)
                    .toList());
            return vo;
        }).toList();
    }

    // ── 日志 ────────────────────────────────────────────────

    /**
     * 步骤日志分页（CONTRACT §7 {@code GET /tasks/{taskId}/steps/{stepInstanceId}/logs}）。
     *
     * <p><b>为什么先解析任务再解析步骤</b>：{@code task_log} 只认 {@code task_step_id}
     * （物理主键），而接口给的是步骤<b>业务编号</b>。先确认任务可见，才能保证不会跨任务
     * 读到同名步骤实例号；同时让越权在 {@code task} 这一层就被行级过滤拦下
     * （{@code task} 有 {@code project_id} 列，{@code task_step} 只有 {@code cluster_id}）。</p>
     */
    public TaskLogVO logs(String taskId, String stepInstanceId, long offset, int limit) {
        Task task = requireTask(taskId);
        TaskStep step = taskStepMapper.selectOne(Wrappers.<TaskStep>lambdaQuery()
                .eq(TaskStep::getTaskId, task.getId())
                .eq(TaskStep::getStepInstanceId, stepInstanceId));
        if (step == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "步骤实例不存在: " + stepInstanceId,
                    Map.of("resource_type", "TASK_STEP", "resource_id", stepInstanceId));
        }
        long safeOffset = Math.max(0, offset);
        int safeLimit = (int) Math.min(Math.max(1, limit), MAX_LOG_LIMIT);

        long total = taskLogMapper.countByTaskStepId(step.getId());
        List<TaskLogRow> rows = total == 0
                ? List.of()
                : taskLogMapper.selectRange(step.getId(), safeOffset, safeLimit);

        TaskLogVO vo = new TaskLogVO();
        vo.setContent(rows.stream().map(TaskLogRow::getContent)
                .collect(Collectors.joining(System.lineSeparator())));
        vo.setEof(rows.size() < safeLimit);
        vo.setTotalLines(total);
        vo.setOffset(safeOffset);
        vo.setLimit(safeLimit);
        return vo;
    }

    // ── 内部：业务编号翻译 ──────────────────────────────────

    /** 列表页：只补工作流编号（列表里"能点进去"的唯一链接目标）。 */
    private Map<Long, String> workflowCodes(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return workflowMapper.selectList(Wrappers.<Workflow>lambdaQuery()
                        .in(Workflow::getId, ids)
                        .eq(Workflow::getDeleted, false))
                .stream()
                .collect(Collectors.toMap(Workflow::getId, Workflow::getWorkflowId,
                        (a, b) -> a, LinkedHashMap::new));
    }

    /** 详情页：把外键一次性翻译成业务编号（D-27）。 */
    private void fillBusinessCodes(TaskDetailVO vo, Task task) {
        if (task.getWorkflowId() != null) {
            vo.setWorkflowId(workflowCodes(Set.of(task.getWorkflowId())).get(task.getWorkflowId()));
        }
        if (task.getProjectId() != null) {
            Project p = projectMapper.selectById(task.getProjectId());
            vo.setProjectId(p == null ? null : p.getProjectId());
        }
        if (task.getClusterId() != null) {
            Cluster c = clusterMapper.selectById(task.getClusterId());
            vo.setClusterId(c == null ? null : c.getClusterId());
        }
        if (task.getQueueId() != null) {
            Queue q = queueMapper.selectById(task.getQueueId());
            vo.setQueueId(q == null ? null : q.getQueueId());
        }
        if (task.getTriggerId() != null) {
            Trigger t = triggerMapper.selectById(task.getTriggerId());
            vo.setTriggerId(t == null ? null : t.getTriggerId());
        }
    }

    private Map<Long, String> operatorVersionCodes(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return operatorVersionMapper.selectList(Wrappers.<OperatorVersion>lambdaQuery()
                        .in(OperatorVersion::getId, ids)
                        .eq(OperatorVersion::getDeleted, false))
                .stream()
                .collect(Collectors.toMap(OperatorVersion::getId, OperatorVersion::getVersionId,
                        (a, b) -> a, LinkedHashMap::new));
    }

    private Map<Long, List<TaskStepRetryRow>> loadRetries(List<TaskStep> steps) {
        List<Long> stepIds = steps.stream().map(TaskStep::getId).toList();
        if (stepIds.isEmpty()) {
            return Map.of();
        }
        return retryQueryMapper.findRetryHistoryByTaskStepIds(stepIds).stream()
                .collect(Collectors.groupingBy(TaskStepRetryRow::getTaskStepId));
    }

    // ── 内部：存在性 / 参数校验 / jsonb 解析 ─────────────────

    private Task requireTask(String taskId) {
        Task task = taskMapper.selectOne(Wrappers.<Task>lambdaQuery()
                .eq(Task::getTaskId, taskId)
                .eq(Task::getDeleted, false));
        if (task == null) {
            // 行级过滤下"越权"与"不存在"在 SQL 上等价，此处只能报 NOT_FOUND。
            // 需要区分 40301/40400 时用 ScopeGuard（本端点暂不区分，登记为 README-M4 偏离）。
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在: " + taskId,
                    Map.of("resource_type", "TASK", "resource_id", taskId));
        }
        return task;
    }

    private Long requireWorkflowId(String businessId) {
        Workflow w = workflowMapper.selectOne(Wrappers.<Workflow>lambdaQuery()
                .eq(Workflow::getWorkflowId, businessId)
                .eq(Workflow::getDeleted, false));
        if (w == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "工作流不存在: " + businessId,
                    Map.of("resource_type", "WORKFLOW", "resource_id", businessId));
        }
        return w.getId();
    }

    private Long requireProjectId(String businessId) {
        Project p = projectMapper.selectOne(Wrappers.<Project>lambdaQuery()
                .eq(Project::getProjectId, businessId)
                .eq(Project::getDeleted, false));
        if (p == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "项目不存在: " + businessId,
                    Map.of("resource_type", "PROJECT", "resource_id", businessId));
        }
        return p.getId();
    }

    private Long requireClusterId(String businessId) {
        Cluster c = clusterMapper.selectOne(Wrappers.<Cluster>lambdaQuery()
                .eq(Cluster::getClusterId, businessId)
                .eq(Cluster::getDeleted, false));
        if (c == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "集群不存在: " + businessId,
                    Map.of("resource_type", "CLUSTER", "resource_id", businessId));
        }
        return c.getId();
    }

    /** 非法值给 40001，而不是让 {@code TaskStatus.of} 抛 IllegalArgumentException 变 500。 */
    private TaskStatus parseStatus(String status) {
        if (blank(status)) {
            return null;
        }
        try {
            return TaskStatus.of(status.trim());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "非法任务状态: " + status,
                    Map.of("allowed", Arrays.stream(TaskStatus.values())
                            .map(Enum::name).toList()));
        }
    }

    private TriggerType parseTriggerType(String triggerType) {
        if (blank(triggerType)) {
            return null;
        }
        try {
            return TriggerType.of(triggerType.trim());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "非法触发类型: " + triggerType,
                    Map.of("allowed", Arrays.stream(TriggerType.values())
                            .map(Enum::name).toList()));
        }
    }

    /**
     * jsonb 文本 → 嵌套对象。空值/坏值都给 {@code null}（坏数据不该让查询整体 500，
     * 也不该把 {@code "null"} 这种字符串塞给前端）。
     */
    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            log.warn("jsonb 快照解析失败，按 null 出网: {}", e.getMessage());
            return null;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static int nullSafe(Integer v) {
        return v == null ? 0 : v;
    }
}
