package com.flowops.domain.dto.query;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 重试历史行（task_step_retry，docs/05 R19）。
 *
 * <p>任务详情页的「重试历史」直接渲染这一行行——它与 {@code task_step} 的聚合列
 * （{@code retry_count}）是两回事：聚合列只说"试了几次"，这里能说清"每次是
 * 在哪台机器上、跑了多久、退出码多少、为什么失败"。排障时需要后者。</p>
 */
@Data
public class TaskStepRetryRow {

    private Long id;

    private Long taskStepId;

    /** 第几次尝试（1 起；uk_tsretry_attempt 保证同一步骤内唯一） */
    private Integer attemptNo;

    private String status;

    private String machineIp;

    private OffsetDateTime startTime;

    private OffsetDateTime endTime;

    private Long durationMs;

    private Integer exitCode;

    private String failReason;
}
