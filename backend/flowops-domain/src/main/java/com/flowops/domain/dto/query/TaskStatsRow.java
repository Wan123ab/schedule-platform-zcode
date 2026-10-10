package com.flowops.domain.dto.query;

import lombok.Data;

/** 任务统计聚合行（M4-S5 工作台 summary；一条 SQL 出全部口径，避免多趟扫描）。 */
@Data
public class TaskStatsRow {

    private Long taskTotal;

    private Long runningCount;

    private Long successCount;

    private Long failedCount;

    private Long failedToday;

    private Long submittedToday;
}
