package com.flowops.domain.dto.query;

import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 最近失败任务行（M4-S5 精简卡片）。 */
@Data
public class DashboardFailureRow {

    private String taskId;

    private String workflowName;

    private String status;

    private LocalDate bizDate;

    private String failReason;

    private OffsetDateTime updatedAt;
}
