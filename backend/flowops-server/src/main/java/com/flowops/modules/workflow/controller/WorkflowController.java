package com.flowops.modules.workflow.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.flowops.common.annotation.Audited;
import com.flowops.common.annotation.DataScope;
import com.flowops.common.annotation.Idempotent;
import com.flowops.common.annotation.RequiresPermission;
import com.flowops.common.api.ApiResult;
import com.flowops.common.api.PageResult;
import com.flowops.modules.workflow.dto.PublishWorkflowRequest;
import com.flowops.modules.workflow.dto.SaveConcurrencyRequest;
import com.flowops.modules.workflow.dto.SaveWorkflowRequest;
import com.flowops.modules.workflow.dto.WorkflowVO;
import com.flowops.modules.workflow.dto.WorkflowVersionVO;
import com.flowops.modules.workflow.service.WorkflowService;
import com.flowops.modules.workflow.service.WorkflowVersionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作流接口（docs/07 §5.4 / CONTRACT §6.1；PRD §10.7）。
 *
 * <p><b>数据范围</b>：工作流归属项目（R11 NOT NULL），故读接口标注
 * {@code @DataScope({"PROJECT","SELF_CREATED"})}。行级条件
 * （{@code workflow.project_id IN (...)}）由
 * {@link com.flowops.config.FlowopsDataPermissionHandler} 在 Mapper 层注入，
 * Controller 只声明语义。</p>
 *
 * <p><b>必审动作</b>（docs/07 §7.3 工作流域 6 个）：本类承载
 * CREATE_WORKFLOW / SAVE_DRAFT / PUBLISH_WORKFLOW / DISABLE_WORKFLOW / UPDATE_CONCURRENCY
 * 五个；{@code DELETE_WORKFLOW} 没有端点 —— CONTRACT 未定义删除接口，
 * 权限点却存在（登记为 README-M3 的偏离项，不凭空造端点）。</p>
 */
@RestController
@RequestMapping("/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;
    private final WorkflowVersionService versionService;

    @GetMapping
    @RequiresPermission("schedule:workflow:read")
    @DataScope({"PROJECT", "SELF_CREATED"})
    public ApiResult<PageResult<WorkflowVO>> page(@RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "20") long pageSize,
                                                  @RequestParam(required = false) String projectId,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) String orderBy,
                                                  @RequestParam(required = false) String orderDir) {
        IPage<WorkflowVO> result = workflowService.page(page, pageSize, projectId, status, keyword,
                orderBy, orderDir);
        return ApiResult.ok(PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(),
                result.getRecords()));
    }

    @PostMapping
    @RequiresPermission("schedule:workflow:write")
    @Audited(action = "CREATE_WORKFLOW", targetType = "WORKFLOW")
    public ApiResult<WorkflowVO> create(@RequestBody @Valid SaveWorkflowRequest request) {
        return ApiResult.ok(workflowService.create(request));
    }

    @GetMapping("/{workflowId}")
    @RequiresPermission("schedule:workflow:read")
    public ApiResult<WorkflowVO> get(@PathVariable String workflowId) {
        return ApiResult.ok(workflowService.get(workflowId));
    }

    @PutMapping("/{workflowId}")
    @RequiresPermission("schedule:workflow:write")
    public ApiResult<WorkflowVO> update(@PathVariable String workflowId,
                                        @RequestBody @Valid SaveWorkflowRequest request) {
        return ApiResult.ok(workflowService.update(workflowId, request));
    }

    /** 并发设置：DAG 规则 8 的校验对象，故单列端点并单独审计。 */
    @PutMapping("/{workflowId}/concurrency")
    @RequiresPermission("schedule:workflow:write")
    @Audited(action = "UPDATE_CONCURRENCY", targetType = "WORKFLOW", targetIdExpr = "#workflowId")
    public ApiResult<WorkflowVO> updateConcurrency(@PathVariable String workflowId,
                                                   @RequestBody @Valid SaveConcurrencyRequest request) {
        return ApiResult.ok(workflowService.updateConcurrency(workflowId, request));
    }

    /**
     * 发布版本。
     *
     * <p>{@code @Idempotent(required = true)}：CONTRACT §0.5 把本端点列为四个"必带
     * Idempotency-Key"的高危端点之一（重复点击会产出重复的发布动作与审计流水）。</p>
     */
    @PostMapping("/{workflowId}/publish")
    @RequiresPermission("schedule:workflow:publish")
    @Idempotent(required = true)
    @Audited(action = "PUBLISH_WORKFLOW", targetType = "WORKFLOW", targetIdExpr = "#workflowId")
    public ApiResult<WorkflowVO> publish(@PathVariable String workflowId,
                                         @RequestBody @Valid PublishWorkflowRequest request) {
        return ApiResult.ok(workflowService.publish(workflowId, request.getVersionId()));
    }

    @PostMapping("/{workflowId}/disable")
    @RequiresPermission("schedule:workflow:publish")
    @Audited(action = "DISABLE_WORKFLOW", targetType = "WORKFLOW", targetIdExpr = "#workflowId")
    public ApiResult<WorkflowVO> disable(@PathVariable String workflowId) {
        return ApiResult.ok(workflowService.disable(workflowId));
    }

    /**
     * 基于当前版本新开草稿（必审动作 SAVE_DRAFT）。
     *
     * <p>已存在未发布草稿时 → <b>42215</b>，提示"需先发布或丢弃"。</p>
     */
    @PostMapping("/{workflowId}/versions")
    @RequiresPermission("schedule:workflow:write")
    @ResponseStatus(HttpStatus.CREATED)
    @Audited(action = "SAVE_DRAFT", targetType = "WORKFLOW_VERSION")
    public ApiResult<WorkflowVersionVO> createDraft(@PathVariable String workflowId) {
        return ApiResult.ok(versionService.createDraft(workflowId));
    }
}
