package com.flowops.modules.project.converter;

import com.flowops.domain.entity.project.Project;
import com.flowops.modules.project.dto.ProjectVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/** 项目转换器（docs/07 §8.3）。ownerUsername 由 service 层查 user 后填充。 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ProjectConverter {

    @Mapping(target = "ownerUsername", ignore = true)
    ProjectVO toVO(Project entity);
}
