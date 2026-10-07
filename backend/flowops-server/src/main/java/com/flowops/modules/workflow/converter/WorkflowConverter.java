package com.flowops.modules.workflow.converter;

import com.flowops.domain.entity.workflow.Workflow;
import com.flowops.modules.workflow.dto.WorkflowVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * 工作流 Entity → VO（MapStruct 生成，D-15）。
 *
 * <p>被 ignore 的三项都是"本层拿不到、由 Service 翻译"的派生数据：</p>
 * <ul>
 *   <li>{@code projectId}：源是内部 bigint 主键，目标是业务编号 PRJ-xxxx（D-27）；</li>
 *   <li>{@code projectName}：来自 project 表；</li>
 *   <li>{@code currentVersion}：来自 workflow_version 表（且只取概要字段）。</li>
 * </ul>
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface WorkflowConverter {

    @Mapping(target = "projectId", ignore = true)
    @Mapping(target = "projectName", ignore = true)
    @Mapping(target = "currentVersion", ignore = true)
    WorkflowVO toVO(Workflow entity);
}
