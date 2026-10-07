package com.flowops.modules.asset.converter;

import com.flowops.domain.entity.asset.Queue;
import com.flowops.modules.asset.dto.QueueVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * 队列转换器。
 *
 * <p>{@code clusterId} / {@code clusterName} 在 VO 里是<b>业务编号与名称</b>，
 * 分别来自另一个实体/另一张表，属于"跨表补齐"而非纯字段映射 —— 由 Service 填充，
 * 这里显式 ignore（MapStruct 的 unmappedTargetPolicy=ERROR 逼我们把它写出来，
 * 而不是让一个新字段悄悄漏成 null）。</p>
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface QueueConverter {

    @Mapping(target = "clusterId", ignore = true)
    @Mapping(target = "clusterName", ignore = true)
    QueueVO toVO(Queue entity);
}
