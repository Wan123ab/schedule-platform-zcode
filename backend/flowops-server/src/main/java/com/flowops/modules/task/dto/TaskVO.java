package com.flowops.modules.task.dto;

import java.time.OffsetDateTime;

/** 任务详情出参（docs/07 §8.1：出参 XxxVO，禁止 Entity 直出）。M1 最小字段集。 */
public record TaskVO(
        String taskId,
        String status,
        String workflowName,
        String workflowVersion,
        Integer priority,
        String submitter,
        OffsetDateTime submitAt,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        Long durationMs,
        Integer stepTotal,
        Integer finishedSteps) {
}
