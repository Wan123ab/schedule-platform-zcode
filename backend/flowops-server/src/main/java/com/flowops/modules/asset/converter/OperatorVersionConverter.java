package com.flowops.modules.asset.converter;

import com.flowops.domain.entity.asset.OperatorVersion;
import com.flowops.modules.asset.dto.OperatorVersionVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * 算子版本 Entity → VO（MapStruct 生成）。
 *
 * <p><b>为什么 ignore 列表这么长</b>：实体把 {@code env_vars}/{@code default_resource} 存成
 * JSON 字符串、{@code success_codes} 存成 {@code Integer[]}，而对外 VO 是带类型的集合 ——
 * 这层"字符串 ⇄ 类型化"的转换要 ObjectMapper 参与，属于 Service 的职责。
 * 显式 ignore（而不是放松 {@code unmappedTargetPolicy}）换来的是：将来实体加字段时，
 * 编译器会立刻报"目标未映射"，逼人做一次有意识的选择。</p>
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface OperatorVersionConverter {

    @Mapping(target = "operatorId", ignore = true)        // 内部主键 → 业务编号（D-27），Service 回填
    @Mapping(target = "operatorName", ignore = true)
    @Mapping(target = "envVars", ignore = true)
    @Mapping(target = "successCodes", ignore = true)
    @Mapping(target = "defaultResource", ignore = true)
    @Mapping(target = "paramTemplate", ignore = true)
    @Mapping(target = "outputDeclarations", ignore = true)
    OperatorVersionVO toVO(OperatorVersion entity);
}
