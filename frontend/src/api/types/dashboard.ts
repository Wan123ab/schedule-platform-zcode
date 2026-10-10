/** 工作台聚合类型（CONTRACT §10 / docs/07 §dashboard、§6.7；camelCase）。 */

export interface DashboardSummary {
  taskTotal: number
  runningCount: number
  /** 无终态任务时为 null（后端不造 0% 假数据） */
  successRate: number | null
  queueBacklog: number
  onlineNodes: number
  totalNodes: number
  failedToday: number
  alerts: number
}

/** 趋势按 submit_at::date 归属（O-69）；后端已补零升序 */
export interface TaskTrendPoint {
  date: string
  submitted: number
  success: number
  failed: number
}

export interface ResourceUsage {
  clusterId: string
  clusterName: string
  cpuUsed: number
  cpuTotal: number
  gpuUsed: number
  gpuTotal: number
  diskUsed: number | null
  diskTotal: number | null
}

export interface ClusterHealthItem {
  clusterId: string
  clusterName: string
  status: string
  nodeOnline: number
  nodeTotal: number
  runningTasks: number
}

export interface QueueBacklogItem {
  queueId: string
  queueName: string
  waitingCount: number
  maxWaiting: number | null
}

export interface RecentFailure {
  taskId: string
  workflowName: string
  status: string
  bizDate: string | null
  failReason: string | null
  updatedAt: string
}

// ── /platform/health（docs/07 §6.7 v3 评审新增；scheduler 段锁推导降级 O-71）──

export interface PlatformHealth {
  scheduler: {
    leaderId: string | null
    schedulerAlive: boolean
    leaseRemainingSeconds: number | null
    isLeader: boolean | null
    lastTickAt: string | null
    tickIntervalMs: number | null
    standbyCount: number | null
  }
  db: {
    poolActive: number
    poolIdle: number
    poolMax: number
    slowQueryCount1m: number | null
  }
  redis: {
    connected: boolean
    usedMemoryHuman: string | null
    evictionPolicy: string | null
  }
  nodes: {
    queueDepth: number | null
    online: number
    offline: number
    total: number
    onlineRate: number
  }
  queues: {
    queueId: string
    name: string
    waiting: number
    running: number
    maxConcurrentTasks: number | null
    maxWaitingTasks: number | null
    waterLevel: number
  }[]
  today: {
    dispatchedCount: number
    failedCount: number
    logIngestBytes: number | null
  }
  recentAlerts: {
    alertId: string
    level: string
    title: string
    firedAt: string
  }[]
}
