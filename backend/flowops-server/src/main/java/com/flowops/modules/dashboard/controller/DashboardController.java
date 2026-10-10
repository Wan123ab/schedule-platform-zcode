package com.flowops.modules.dashboard.controller;

import com.flowops.common.annotation.RequiresPermission;
import com.flowops.common.api.ApiResult;
import com.flowops.modules.alert.dto.AlertRecordVO;
import com.flowops.modules.dashboard.dto.ClusterHealthVO;
import com.flowops.modules.dashboard.dto.DashboardSummaryVO;
import com.flowops.modules.dashboard.dto.QueueBacklogVO;
import com.flowops.modules.dashboard.dto.RecentFailureVO;
import com.flowops.modules.dashboard.dto.ResourceUsageVO;
import com.flowops.modules.dashboard.dto.TaskTrendVO;
import com.flowops.modules.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工作台聚合接口（M4-S5，CONTRACT §11 表，7 端点逐一对应）。
 *
 * <p><b>权限（docs/07 §5.4）</b>：{@code /dashboard/*} 登录即可（全局 checkLogin 拦截器兜底），
 * 不挂权限点；task 维度聚合在 PROJECT 数据范围下被 {@code FlowopsDataPermissionHandler}
 * 行级过滤（O-70：cluster/queue 维度未登记 → 全局口径）。</p>
 *
 * <p>前端 5s 轮询（docs/07 §7 刷新策略表）。</p>
 */
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/summary")
    public ApiResult<DashboardSummaryVO> summary() {
        return ApiResult.ok(dashboardService.summary());
    }

    @GetMapping("/task-trend")
    public ApiResult<List<TaskTrendVO>> taskTrend(@RequestParam(defaultValue = "7") int days) {
        return ApiResult.ok(dashboardService.taskTrend(days));
    }

    @GetMapping("/resource-usage")
    public ApiResult<List<ResourceUsageVO>> resourceUsage() {
        return ApiResult.ok(dashboardService.resourceUsage());
    }

    @GetMapping("/cluster-health")
    public ApiResult<List<ClusterHealthVO>> clusterHealth() {
        return ApiResult.ok(dashboardService.clusterHealth());
    }

    @GetMapping("/queue-backlog")
    public ApiResult<List<QueueBacklogVO>> queueBacklog() {
        return ApiResult.ok(dashboardService.queueBacklog());
    }

    @GetMapping("/recent-failures")
    public ApiResult<List<RecentFailureVO>> recentFailures() {
        return ApiResult.ok(dashboardService.recentFailures());
    }

    @GetMapping("/latest-alerts")
    public ApiResult<List<AlertRecordVO>> latestAlerts() {
        return ApiResult.ok(dashboardService.latestAlerts());
    }
}
