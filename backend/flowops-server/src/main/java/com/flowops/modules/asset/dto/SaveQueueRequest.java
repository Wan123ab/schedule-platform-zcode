package com.flowops.modules.asset.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 队列创建/更新入参（docs/05 §3.3 queue）。
 *
 * <p>两个并发阈值的作用时机不同（docs/06 §6.1）：{@code maxConcurrentTasks} 在<b>出队</b>判定，
 * {@code maxWaitingTasks} 在<b>提交</b>判定（超限 → 40902）。</p>
 */
@Data
public class SaveQueueRequest {

    @NotBlank(message = "队列名称必填")
    private String queueName;

    /** 业务编号 CL-0001（更新时以路径为准，此字段可省） */
    private String clusterId;

    @Min(value = 1, message = "并发上限至少为 1")
    @Max(value = 1000, message = "并发上限过大")
    private Integer maxConcurrentTasks;

    @Min(value = 0, message = "等待上限不可为负")
    private Integer maxWaitingTasks;

    /** 0~100（E-07 定案；上限取平台配置 queue.default_priority_max 的默认值） */
    @Min(value = 0, message = "优先级范围 0~100")
    @Max(value = 100, message = "优先级范围 0~100")
    private Integer defaultPriority;

    private Boolean allowJumpQueue;

    @Min(value = 1, message = "排队超时至少 1 秒")
    private Integer waitTimeoutSeconds;
}
