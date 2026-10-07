package com.flowops.modules.workflow.converter;

import com.flowops.domain.entity.workflow.WorkflowVersion;
import com.flowops.modules.workflow.dto.WorkflowVersionBrief;
import com.flowops.modules.workflow.dto.WorkflowVersionVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * 工作流版本 Entity → VO（MapStruct 生成）。
 *
 * <p>ignore 的都是"实体存的是另一种形态 / 需要跨表"的字段：</p>
 * <ul>
 *   <li>{@code workflowId}：内部主键 → 业务编号 WF-####（D-27），Service 回填；</li>
 *   <li>{@code steps}/{@code edges}：实体侧是 {@code dag_definition} 与规范化表，
 *       VO 从规范化表组装（见 {@code WorkflowVersionService}）；</li>
 *   <li>{@code workflowParams}：实体侧是 JSONB 字符串；</li>
 *   <li>{@code hasDraftChanges}：来自父 workflow 表。</li>
 * </ul>
 *
 * <p>{@code dag_definition} 这个内部字段<b>刻意不进 VO</b>：它是"原样快照"，
 * 供审计比对用；对外只暴露规范化后的 steps/edges，避免把"某次写入留下的坏 JSON"
 * 直接透给画布。</p>
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface WorkflowVersionConverter {

    @Mapping(target = "workflowId", ignore = true)
    @Mapping(target = "workflowName", ignore = true)
    @Mapping(target = "steps", ignore = true)
    @Mapping(target = "edges", ignore = true)
    @Mapping(target = "workflowParams", ignore = true)
    @Mapping(target = "hasDraftChanges", ignore = true)
    WorkflowVersionVO toVO(WorkflowVersion entity);

    /** 详情页顶部的"当前版本"概要（字段少，直接用同一个实体映射）。 */
    WorkflowVersionBrief toBrief(WorkflowVersion entity);
}
