package com.flowops.modules.task.controller;

import com.flowops.common.annotation.Audited;
import com.flowops.common.annotation.DataScope;
import com.flowops.common.annotation.Idempotent;
import com.flowops.common.annotation.RequiresPermission;
import com.flowops.common.api.ApiResult;
import com.flowops.common.api.PageResult;
import com.flowops.modules.task.dto.SubmitTaskRequest;
import com.flowops.modules.task.dto.TaskDiagnosisVO;
import com.flowops.modules.task.dto.TaskDetailVO;
import com.flowops.modules.task.dto.TaskLogVO;
import com.flowops.modules.task.dto.TaskStepVO;
import com.flowops.modules.task.dto.TaskSubmitResponse;
import com.flowops.modules.task.dto.TaskVO;
import com.flowops.modules.task.service.TaskDiagnosisService;
import com.flowops.modules.task.service.TaskQueryService;
import com.flowops.modules.task.service.TaskSubmitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 任务接口（docs/07 §5.4 映射 + §6.4 提交契约；M4 S1 补读侧）。
 *
 * <p><b>读侧三端点都是"登录 + 行级数据范围"</b>：权限点只判"能不能看任务"，
 * "能看哪些任务"由 {@code @DataScope} + {@code FlowopsDataPermissionHandler} 在 SQL 层过滤
 * （{@code task} 表有 {@code project_id}/{@code cluster_id}，两个维度都登记过）。</p>
 *
 * <p><b>提交端点的三道横切</b>（本工程切面链的完整示范）：
 * {@code @RequiresPermission} 权限点 → {@code @Idempotent(required=true)} 幂等强制
 * （4 个高危端点之一，D-17）→ {@code @Audited} 必审动作 SUBMIT_TASK。</p>
 */
@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskSubmitService submitService;
    private final TaskQueryService queryService;
    private final TaskDiagnosisService diagnosisService;

    @PostMapping
    @RequiresPermission("schedule:task:submit")
    @Idempotent(required = true)
    @Audited(action = "SUBMIT_TASK", targetType = "TASK")
    public ApiResult<TaskSubmitResponse> submit(@RequestBody @Valid SubmitTaskRequest request) {
        var creation = submitService.create(request);
        return ApiResult.ok(new TaskSubmitResponse(
                creation.taskId(), "PENDING", creation.deferred(),
                creation.deferred() ? creation.queuePosition() : null));
    }

    /**
     * 任务列表（CONTRACT §7 {@code GET /tasks}）。
     *
     * <p><b>查询串一律不做键名转换</b>（README-M3 的既定口径）：前端按本方法的
     * {@code @RequestParam} 名原样发，{@code bizDateFrom} 是驼峰、{@code pageSize} 是驼峰，
     * 别按"后端是蛇形"去猜。</p>
     */
    @GetMapping
    @RequiresPermission("schedule:task:read")
    @DataScope({"PROJECT", "AUTHORIZED_CLUSTER", "SELF_CREATED"})
    public ApiResult<PageResult<TaskVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String workflowId,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String clusterId,
            @RequestParam(required = false) String triggerType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDateTo,
            @RequestParam(required = false) String keyword) {
        var result = queryService.page(page, pageSize, status, workflowId, projectId, clusterId,
                triggerType, bizDateFrom, bizDateTo, keyword);
        return ApiResult.ok(PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(),
                result.getRecords()));
    }

    @GetMapping("/{taskId}")
    @RequiresPermission("schedule:task:read")
    public ApiResult<TaskDetailVO> get(@PathVariable String taskId) {
        return ApiResult.ok(queryService.get(taskId));
    }

    @GetMapping("/{taskId}/steps")
    @RequiresPermission("schedule:task:read")
    public ApiResult<List<TaskStepVO>> steps(@PathVariable String taskId) {
        return ApiResult.ok(queryService.steps(taskId));
    }

    /**
     * 调度诊断（CONTRACT §7 {@code GET /tasks/{taskId}/diagnosis}，docs/06 §5.4/§5.5）。
     *
     * <p>只读端点：登录 + {@code task:read} + 行级数据范围（docs/07 §5.4），与列表同口径；
     * 返回永远是 200 + 阻塞原因清单（{@code 40904} 仅留给 dry-run 的"现在能否立即执行"
     * 判定，docs/07 §6.6，本端点不用）。</p>
     */
    @GetMapping("/{taskId}/diagnosis")
    @RequiresPermission("schedule:task:read")
    @DataScope({"PROJECT", "AUTHORIZED_CLUSTER", "SELF_CREATED"})
    public ApiResult<TaskDiagnosisVO> diagnosis(@PathVariable String taskId) {
        return ApiResult.ok(diagnosisService.diagnose(taskId));
    }

    /**
     * 步骤日志分页（CONTRACT §7）。
     *
     * <p>权限点取 {@code task:log:raw}（原文查看）。{@code task:log:grant} 是"申请跨项目日志授权"
     * 的独立流程，不是读取原文的凭据 —— 只有 grant 而无 raw 的用户不应读到原文。</p>
     */
    @GetMapping("/{taskId}/steps/{stepInstanceId}/logs")
    @RequiresPermission("schedule:task:log:raw")
    public ApiResult<TaskLogVO> logs(@PathVariable String taskId,
                                     @PathVariable String stepInstanceId,
                                     @RequestParam(defaultValue = "0") long offset,
                                     @RequestParam(defaultValue = "1000") int limit) {
        return ApiResult.ok(queryService.logs(taskId, stepInstanceId, offset, limit));
    }
}
