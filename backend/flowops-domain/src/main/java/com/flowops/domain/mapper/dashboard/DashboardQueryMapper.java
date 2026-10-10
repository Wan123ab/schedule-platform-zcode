package com.flowops.domain.mapper.dashboard;

import com.flowops.domain.dto.query.DashboardFailureRow;
import com.flowops.domain.dto.query.ResourceUsageRow;
import com.flowops.domain.dto.query.TaskStatsRow;
import com.flowops.domain.dto.query.TaskTrendRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 工作台聚合查询（M4-S5，CONTRACT §11；SQL 见 XML）。
 *
 * <p><b>DataScope 说明</b>：task 表已登记数据权限（project_id/cluster_id）——
 * 本 Mapper 的 task 系查询在 PROJECT 范围下自动被 {@code FlowopsDataPermissionHandler}
 * 行级过滤；cluster/queue 维度未登记（指标卡/资源面板全局口径），偏离见 README-M4 O-70。</p>
 */
@Mapper
public interface DashboardQueryMapper {

    /** 任务统计（总数/运行中/成功/失败/今日失败/今日提交）。 */
    TaskStatsRow taskStats();

    /** 近 N 日趋势（按 submit_at::date 归属，含今天；无数据日不出行 —— 前端补零）。 */
    List<TaskTrendRow> taskTrend(@Param("days") int days);

    /** 集群资源用量（used = 节点聚合，total = cluster 容量列）。 */
    List<ResourceUsageRow> resourceUsage();

    /** 最近失败任务（FAILED/TIMEOUT，updated_at 倒序，≤ limit）。 */
    List<DashboardFailureRow> recentFailures(@Param("limit") int limit);
}
