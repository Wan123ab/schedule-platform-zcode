package com.flowops.modules.task.converter;

import com.flowops.domain.dto.query.TaskStepRetryRow;
import com.flowops.domain.entity.task.Task;
import com.flowops.domain.entity.task.TaskStep;
import com.flowops.modules.task.dto.StepRetryVO;
import com.flowops.modules.task.dto.TaskDetailVO;
import com.flowops.modules.task.dto.TaskStepVO;
import com.flowops.modules.task.dto.TaskVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * 任务域转换器（docs/07 §8.3；枚举 → 线协议字符串由 MapStruct 按 name 自动映射）。
 *
 * <p><b>被 ignore 的三类目标字段</b>（{@code unmappedTargetPolicy=ERROR} 逼着每一条都说清楚）：</p>
 * <ol>
 *   <li><b>外键业务编号</b>（{@code workflowId/projectId/clusterId/queueId/triggerId}）：
 *       源是内部 bigint 主键，目标是业务编号字符串（D-27）。MapStruct 本可以把
 *       {@code Long} 直接转成 {@code "12"}，那是个<b>看起来成功、语义完全错</b>的转换，
 *       所以必须显式 ignore，由 Service 翻译；</li>
 *   <li><b>jsonb 快照类</b>（{@code variableSnapshot/diagnosisInfo/failReasonDetail/
 *       resourceRequest/resourceActual/outputVars/blockReason}）：实体以 String 承载，
 *       对外要是嵌套对象，String→Object 的解析需要 ObjectMapper，属 Service 职责；</li>
 *   <li><b>派生集合</b>（{@code retryHistory}）：来自另一张表（task_step_retry），
 *       需要批量查询后在 Service 里挂上。</li>
 * </ol>
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface TaskConverter {

    @Mapping(target = "workflowId", ignore = true)
    @Mapping(target = "projectId", ignore = true)
    @Mapping(target = "clusterId", ignore = true)
    @Mapping(target = "queueId", ignore = true)
    @Mapping(target = "triggerId", ignore = true)
    TaskVO toVO(Task entity);

    @Mapping(target = "workflowId", ignore = true)
    @Mapping(target = "projectId", ignore = true)
    @Mapping(target = "clusterId", ignore = true)
    @Mapping(target = "queueId", ignore = true)
    @Mapping(target = "triggerId", ignore = true)
    @Mapping(target = "variableSnapshot", ignore = true)
    @Mapping(target = "diagnosisInfo", ignore = true)
    TaskDetailVO toDetailVO(Task entity);

    @Mapping(target = "rowId", source = "id")
    @Mapping(target = "operatorVersionId", ignore = true)
    @Mapping(target = "failReasonDetail", ignore = true)
    @Mapping(target = "resourceRequest", ignore = true)
    @Mapping(target = "resourceActual", ignore = true)
    @Mapping(target = "outputVars", ignore = true)
    @Mapping(target = "blockReason", ignore = true)
    @Mapping(target = "retryHistory", ignore = true)
    TaskStepVO toStepVO(TaskStep entity);

    StepRetryVO toRetryVO(TaskStepRetryRow row);
}
