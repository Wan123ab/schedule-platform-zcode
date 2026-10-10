package com.flowops.modules.task.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 单次重试记录出参（task_step_retry 投影）。
 *
 * <p>与父步骤的 {@code retryCount} 的关系：{@code retryCount} 是"还要不要继续试"的
 * 计数器（调度器用），这里的一行行是"过去每次试成什么样"（人用）。排障时需要的
 * 是后者——同一台机器反复失败说明节点有问题，换机器后成功说明是偶发。</p>
 */
@Data
public class StepRetryVO {

    /** 第几次尝试（1 起） */
    private Integer attemptNo;

    private String status;

    private String machineIp;

    private OffsetDateTime startTime;

    private OffsetDateTime endTime;

    private Long durationMs;

    private Integer exitCode;

    private String failReason;
}
