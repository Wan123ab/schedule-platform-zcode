package com.flowops.modules.workflow.converter;

import com.flowops.domain.entity.workflow.Trigger;
import com.flowops.modules.workflow.dto.TriggerVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * 触发器 Entity → VO（MapStruct 生成，D-15）。
 *
 * <p>被 ignore 的四项由 Service 翻译/拆装：</p>
 * <ul>
 *   <li>{@code workflowId}：源是内部 bigint 主键，目标是业务编号 WF-xxxx（D-27）；</li>
 *   <li>{@code effectiveStart} / {@code effectiveEnd}：源是 JSONB
 *       {@code {"start":..,"end":..}} 单列，目标是两个独立字段；</li>
 *   <li>{@code runParams}：源是 JSONB 字符串，目标是 Map（Service 统一走
 *       ObjectMapper，坏数据抛 500 而不是静默置空）。</li>
 * </ul>
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface TriggerConverter {

    @Mapping(target = "workflowId", ignore = true)
    @Mapping(target = "effectiveStart", ignore = true)
    @Mapping(target = "effectiveEnd", ignore = true)
    @Mapping(target = "runParams", ignore = true)
    TriggerVO toVO(Trigger entity);
}
