package com.flowops.modules.task.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.Map;

/**
 * 任务提交入参（docs/07 §6.4：POST /tasks 的 body）。
 * 幂等键走请求头 Idempotency-Key（@Idempotent(required=true) —— 4 个高危端点之一，D-17）。
 */
@Data
public class SubmitTaskRequest {

    @NotNull(message = "workflowId 必填")
    private Long workflowId;

    @NotNull(message = "projectId 必填")
    private Long projectId;

    /** 缺省 = 当前发布版本（D-11） */
    private Long versionId;

    /** 0~100，越大越优先（E-07）；缺省 0 */
    private Integer priority;

    /** 缺省 = 项目绑定的任一启用队列（M1 口径） */
    private Long targetQueueId;

    /** 业务日期（定时/回填场景；手动触发可空） */
    private LocalDate bizDate;

    /** 触发时参数（变量解析链第 4 层，docs/03 §4.4） */
    private Map<String, Object> runParams;
}
