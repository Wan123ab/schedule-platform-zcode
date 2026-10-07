package com.flowops.modules.asset.converter;

import com.flowops.domain.entity.asset.Operator;
import com.flowops.modules.asset.dto.OperatorVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * 算子 Entity → VO（MapStruct 生成，D-15 的字段搬运不手写）。
 *
 * <p>被忽略的字段都不是"忘了映射"，而是<b>本层不该负责</b>的派生数据：</p>
 * <ul>
 *   <li>{@code projectId}：源是内部 bigint 主键，目标是业务编号（D-27）——
 *       翻译要查 project 表，由 Service 完成后回填；</li>
 *   <li>{@code projectName}：同上，来自 project 表。</li>
 * </ul>
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface OperatorConverter {

    @Mapping(target = "projectId", ignore = true)
    @Mapping(target = "projectName", ignore = true)
    OperatorVO toVO(Operator entity);
}
