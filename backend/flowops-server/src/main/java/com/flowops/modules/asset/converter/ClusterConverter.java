package com.flowops.modules.asset.converter;

import com.flowops.domain.entity.asset.Cluster;
import com.flowops.modules.asset.dto.ClusterVO;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * 集群转换器（docs/07 §8.3：MapStruct 编译期生成 + unmappedTargetPolicy=ERROR 防漏字段）。
 * cluster 实体的字段与 VO 一一对应（VO 无私自增字段），故无需 @Mapping 修正。
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ClusterConverter {

    ClusterVO toVO(Cluster entity);
}
