package com.flowops.modules.dashboard.dto;

import lombok.Data;

/** 指标卡（CONTRACT §11 GET /dashboard/summary）。 */
@Data
public class DashboardSummaryVO {

    private Long taskTotal;

    private Long runningCount;

    /** 终态成功率 = SUCCESS / (SUCCESS+FAILED+TIMEOUT+PARTIAL+STOPPED)，无终态时 null */
    private Double successRate;

    private Long queueBacklog;

    private Long onlineNodes;

    private Long totalNodes;

    private Long failedToday;

    /** 今日告警条数（口径 O-69：CONTRACT 未定义 alerts 语义，取今日新增） */
    private Long alerts;
}
