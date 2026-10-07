package com.flowops.modules.workflow.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 保存草稿入参（CONTRACT §6.2 {@code PUT /workflow-versions/{versionId}}）。
 *
 * <p><b>整包保存，没有步骤级增量接口</b>（CONTRACT 明文 / docs/04 §画布）。
 * 换来的是"保存后的表内容 == 提交的图"这条可判定性质；代价是前端必须维护完整
 * 的 {@code DagModel} 并自己做变更检测（{@code hasDraftChanges}）。</p>
 *
 * <p>保存阶段<b>只跑结构校验</b>（规则 1/5/10，docs/07 §9.2）—— 否则"画了半个图想先存
 * 一下"会被拒，编辑中途无法保存。全量 10 条在发布时跑。</p>
 */
@Data
public class SaveWorkflowVersionRequest {

    /** DAG 节点（顺序即服务端发号的序号依据，故调用方不应随意重排） */
    @Valid
    @NotNull(message = "DAG 步骤列表必填（空图请传空数组）")
    private List<DagStepDef> steps;

    @Valid
    @NotNull(message = "DAG 连线列表必填（空图请传空数组）")
    private List<DagEdgeDef> edges;

    /** 工作流级参数定义（PRD §10.0.1 第 3 层，变量覆盖链的一环） */
    private List<Map<String, Object>> workflowParams;

    /** 画布尺寸（页面渲染缓存，避免每次解析 JSONB） */
    @Min(value = 0, message = "画布宽度不能为负")
    @Max(value = 100000, message = "画布宽度超出合理范围")
    private Integer canvasWidth;

    @Min(value = 0, message = "画布高度不能为负")
    @Max(value = 100000, message = "画布高度超出合理范围")
    private Integer canvasHeight;
}
