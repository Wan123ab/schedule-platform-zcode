package com.flowops.modules.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDate;

/** 近 7 日任务趋势点（CONTRACT §11；无数据日由服务端补零）。 */
@Data
@AllArgsConstructor
public class TaskTrendVO {

    private LocalDate date;

    private long submitted;

    private long success;

    private long failed;
}
