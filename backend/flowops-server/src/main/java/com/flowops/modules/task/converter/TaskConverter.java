package com.flowops.modules.task.converter;

import com.flowops.domain.entity.task.Task;
import com.flowops.domain.entity.task.TaskStep;
import com.flowops.modules.task.dto.TaskStepVO;
import com.flowops.modules.task.dto.TaskVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/** 任务域转换器（docs/07 §8.3；枚举 → 线协议字符串由 MapStruct 按 name 自动映射）。 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface TaskConverter {

    TaskVO toVO(Task entity);

    @Mapping(target = "rowId", source = "id")
    TaskStepVO toStepVO(TaskStep entity);
}
