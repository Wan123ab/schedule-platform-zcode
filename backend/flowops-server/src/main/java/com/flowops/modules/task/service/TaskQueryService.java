package com.flowops.modules.task.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flowops.common.api.ErrorCode;
import com.flowops.common.exception.BizException;
import com.flowops.domain.entity.task.Task;
import com.flowops.domain.entity.task.TaskStep;
import com.flowops.domain.mapper.task.TaskMapper;
import com.flowops.domain.mapper.task.TaskStepMapper;
import com.flowops.modules.task.converter.TaskConverter;
import com.flowops.modules.task.dto.TaskStepVO;
import com.flowops.modules.task.dto.TaskVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 任务查询（M1 最小集：详情 + 步骤列表；列表页/诊断/日志随 M4）。 */
@Service
@RequiredArgsConstructor
public class TaskQueryService {

    private final TaskMapper taskMapper;
    private final TaskStepMapper taskStepMapper;
    private final TaskConverter converter;

    public TaskVO get(String taskId) {
        Task task = taskMapper.selectOne(Wrappers.<Task>lambdaQuery()
                .eq(Task::getTaskId, taskId)
                .eq(Task::getDeleted, false));
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在: " + taskId);
        }
        return converter.toVO(task);
    }

    public List<TaskStepVO> steps(String taskId) {
        Task task = taskMapper.selectOne(Wrappers.<Task>lambdaQuery()
                .eq(Task::getTaskId, taskId)
                .eq(Task::getDeleted, false));
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在: " + taskId);
        }
        return taskStepMapper.selectList(Wrappers.<TaskStep>lambdaQuery()
                        .eq(TaskStep::getTaskId, task.getId())
                        .orderByAsc(TaskStep::getStepIndex))
                .stream()
                .map(converter::toStepVO)
                .toList();
    }
}
