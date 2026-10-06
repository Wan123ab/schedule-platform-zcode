package com.flowops.modules.task.controller;

import com.flowops.common.annotation.Audited;
import com.flowops.common.annotation.Idempotent;
import com.flowops.common.annotation.RequiresPermission;
import com.flowops.common.api.ApiResult;
import com.flowops.modules.task.dto.SubmitTaskRequest;
import com.flowops.modules.task.dto.TaskStepVO;
import com.flowops.modules.task.dto.TaskSubmitResponse;
import com.flowops.modules.task.dto.TaskVO;
import com.flowops.modules.task.service.TaskQueryService;
import com.flowops.modules.task.service.TaskSubmitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 任务接口（docs/07 §5.4 映射 + §6.4 提交契约；M1 最小集）。
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

    @GetMapping("/{taskId}")
    @RequiresPermission("schedule:task:read")
    public ApiResult<TaskVO> get(@PathVariable String taskId) {
        return ApiResult.ok(queryService.get(taskId));
    }

    @GetMapping("/{taskId}/steps")
    @RequiresPermission("schedule:task:read")
    public ApiResult<List<TaskStepVO>> steps(@PathVariable String taskId) {
        return ApiResult.ok(queryService.steps(taskId));
    }
}
