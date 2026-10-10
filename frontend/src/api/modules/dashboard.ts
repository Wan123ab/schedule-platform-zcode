import { request } from '@/api/http'
import type {
  ClusterHealthItem,
  DashboardSummary,
  PlatformHealth,
  QueueBacklogItem,
  RecentFailure,
  ResourceUsage,
  TaskTrendPoint,
} from '@/api/types/dashboard'
import type { AlertRecord } from '@/api/types/alert'

/**
 * 工作台聚合请求（CONTRACT §10 / docs/07）。
 * /dashboard/* 登录即可（docs/07 line 555，不挂权限点）；/platform/health 挂
 * schedule:platform:health。days 上限 30（后端 cap，超出静默收敛）。
 */
export const dashboardApi = {
  summary(): Promise<DashboardSummary> {
    return request<DashboardSummary>({ url: '/dashboard/summary', method: 'GET' })
  },
  taskTrend(days = 7): Promise<TaskTrendPoint[]> {
    return request<TaskTrendPoint[]>({ url: '/dashboard/task-trend', method: 'GET', params: { days } })
  },
  resourceUsage(): Promise<ResourceUsage[]> {
    return request<ResourceUsage[]>({ url: '/dashboard/resource-usage', method: 'GET' })
  },
  clusterHealth(): Promise<ClusterHealthItem[]> {
    return request<ClusterHealthItem[]>({ url: '/dashboard/cluster-health', method: 'GET' })
  },
  queueBacklog(): Promise<QueueBacklogItem[]> {
    return request<QueueBacklogItem[]>({ url: '/dashboard/queue-backlog', method: 'GET' })
  },
  recentFailures(): Promise<RecentFailure[]> {
    return request<RecentFailure[]>({ url: '/dashboard/recent-failures', method: 'GET' })
  },
  latestAlerts(): Promise<AlertRecord[]> {
    return request<AlertRecord[]>({ url: '/dashboard/latest-alerts', method: 'GET' })
  },
  /** 平台健康度（第 19 页，D-24） */
  platformHealth(): Promise<PlatformHealth> {
    return request<PlatformHealth>({ url: '/platform/health', method: 'GET' })
  },
}
