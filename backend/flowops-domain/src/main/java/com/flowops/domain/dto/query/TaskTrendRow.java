package com.flowops.domain.dto.query;

import lombok.Data;

import java.time.LocalDate;

/** 近 7 日任务趋势行（M4-S5；按 submit_at::date 归属，README-M4 O-69 口径）。 */
@Data
public class TaskTrendRow {

    private LocalDate date;

    private Long submitted;

    private Long success;

    private Long failed;
}
