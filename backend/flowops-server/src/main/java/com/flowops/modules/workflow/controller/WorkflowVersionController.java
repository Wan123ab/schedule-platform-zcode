package com.flowops.modules.workflow.controller;

import com.flowops.common.annotation.Audited;
import com.flowops.common.annotation.RequiresPermission;
import com.flowops.common.api.ApiResult;
import com.flowops.modules.workflow.dto.SaveWorkflowVersionRequest;
import com.flowops.modules.workflow.dto.WorkflowVersionVO;
import com.flowops.modules.workflow.service.WorkflowVersionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作流版本与 DAG 接口（docs/07 §5.4 / CONTRACT §6.2）。
 *
 * <p><b>为什么路径不带 {@code /workflows} 前缀</b>：版本有自己的业务编号
 * （{@code WFV-xxxx-xx}），详情与保存都只需要版本号。CONTRACT §6.2 也是这么定的
 * （{@code GET/PUT /workflow-versions/{versionId}}）。</p>
 *
 * <p><b>没有 {@code DELETE}</b>：PRD §7.2-6 明文"已发布版本不可删除（历史任务需回溯）"，
 * 而草稿的丢弃在 CONTRACT 里也没有对应端点 —— 一期"丢弃草稿"表现为"重新发布当前版本"
 * （{@code current_version} 切回去、草稿留在库里但不生效）。不凭空造删除端点。</p>
 *
 * <p><b>没有独立的"校验"端点</b>：编辑器上的「校验」按钮在 CONTRACT 里没有对应接口。
 * 全量校验挂在发布路径上（发布即校验）；结构校验挂在保存路径上。
 * 已登记为 README-M3 的偏离项。</p>
 */
@RestController
@RequiredArgsConstructor
public class WorkflowVersionController {

    private final WorkflowVersionService versionService;

    /** 版本全量（含 DAG）：画布的读取入口。 */
    @GetMapping("/workflow-versions/{versionId}")
    @RequiresPermission("schedule:workflow:read")
    public ApiResult<WorkflowVersionVO> get(@PathVariable String versionId) {
        return ApiResult.ok(versionService.get(versionId));
    }

    /**
     * 保存草稿（整包 DAG；必审动作 SAVE_DRAFT）。
     *
     * <p>失败返回 <b>42213/42214/42218 + errors[]</b>（每条带 {@code rule} 与
     * {@code step_name}），前端把错误挂到画布对应节点上 —— 与算子上传的 42210 同口径：
     * 一次收齐，不让用户改一条提交一次。</p>
     */
    @PutMapping("/workflow-versions/{versionId}")
    @RequiresPermission("schedule:workflow:write")
    @Audited(action = "SAVE_DRAFT", targetType = "WORKFLOW_VERSION", targetIdExpr = "#versionId")
    public ApiResult<WorkflowVersionVO> saveDraft(@PathVariable String versionId,
                                                  @RequestBody @Valid SaveWorkflowVersionRequest request) {
        return ApiResult.ok(versionService.saveDraft(versionId, request));
    }
}
