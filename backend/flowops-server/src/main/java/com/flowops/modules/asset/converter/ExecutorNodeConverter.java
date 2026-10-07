package com.flowops.modules.asset.converter;

import com.flowops.domain.entity.asset.ExecutorNode;
import com.flowops.modules.asset.dto.ExecutorNodeVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * 执行节点转换器。
 *
 * <p>三个 ignore 都是"跨表/跨口径补齐"字段，由 Service 填充：
 * {@code credentialId}（内部主键 → 业务编号 CR-xxxx）、{@code clusterId}/{@code clusterName}（跨表）。</p>
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ExecutorNodeConverter {

    @Mapping(target = "clusterId", ignore = true)
    @Mapping(target = "clusterName", ignore = true)
    @Mapping(target = "credentialId", ignore = true)
    @Mapping(target = "credentialName", ignore = true)
    ExecutorNodeVO toVO(ExecutorNode entity);
}
