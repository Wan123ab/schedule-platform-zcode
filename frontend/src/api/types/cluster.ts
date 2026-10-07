/**
 * 集群 / 执行节点 / 队列 域类型（docs/07 §8.1；字段口径 docs/05 §3.3）。
 *
 * 【命名说明（D-15）】
 * 这里全部是 camelCase —— 业务代码只认 camelCase，snake_case 只存在于网络边界一层：
 * 请求出口由 axios 拦截器 deepSnake、响应入口 deepCamel（见 api/http.ts）。
 * 所以写类型时不需要考虑 `cluster_id` 这种写法，那是线协议的事。
 */

/** 集群（GET /clusters、GET /clusters/{clusterId}）。 */
export interface ClusterItem {
  clusterId: string
  clusterName: string
  /** GENERAL / GPU / BIGDATA / CUSTOM（SPEC §6.2） */
  clusterType: string
  /** NORMAL / PARTIAL_ABNORMAL / UNAVAILABLE / MAINTENANCE */
  status: string
  cpuTotal: number
  gpuTotal: number
  /** MB */
  memoryTotal: number
  /** MB */
  diskTotal: number
  /** 以下统计列是 30s 定时聚合的快照，仅用于展示（docs/05 §6.3） */
  nodeTotal: number
  nodeOnline: number
  nodeOffline: number
  nodeIdle: number
  runningTaskCount: number
  pendingTaskCount: number
  historyTaskCount: number
  lastHeartbeatAt: string | null
  createdAt: string
}

export interface SaveClusterParams {
  clusterName: string
  clusterType?: string
  /** 只允许 NORMAL / MAINTENANCE —— 另两态由心跳推导，人工改会与心跳扫描打架 */
  status?: string
  cpuTotal?: number
  gpuTotal?: number
  memoryTotal?: number
  diskTotal?: number
}

/** 执行节点（GET /clusters/{id}/nodes、GET /executor-nodes/{nodeId}）。 */
export interface ExecutorNodeItem {
  executorNodeId: string
  executorNodeName: string
  /** 业务编号 CL-0001（后端填充） */
  clusterId: string
  clusterName: string
  ip: string
  /** LINUX / WINDOWS（Q-02：一期 Windows 仅登记展示） */
  osType: string
  /** SSH / WINRM / AGENT */
  connectType: string
  /** 绑定凭据的**业务编号**（CR-xxxx）—— 内部主键不出网 */
  credentialId: string | null
  credentialName: string | null
  /** 标签约束调度（PRD §12.1） */
  tags: string[]
  /** ONLINE / OFFLINE / UNKNOWN —— 心跳链路维护，只读 */
  onlineStatus: string
  cpuTotal: number
  cpuUsed: number
  gpuTotal: number
  gpuUsed: number
  memoryTotal: number
  memoryUsed: number
  diskTotal: number
  diskUsed: number
  runningTaskCount: number
  maxConcurrentSteps: number | null
  enabled: boolean
  lastHeartbeatAt: string | null
  heartbeatMissCount: number
  lastAllocatedAt: string | null
  createdAt: string
}

/**
 * 节点入参 —— 注意这里<b>没有</b> onlineStatus / lastHeartbeatAt / cpuUsed：
 * 它们是心跳与执行器上报的"事实"，不是可编辑的"配置"。
 * 前端类型与后端 DTO 同步少字段，是让"人工污染心跳"在编译期就不可能发生。
 */
export interface SaveExecutorNodeParams {
  executorNodeName: string
  clusterId?: string
  ip: string
  osType: string
  connectType?: string
  /** 绑定凭据的业务编号（CR-xxxx）；不填 = 不绑定 */
  credentialId?: string | null
  tags?: string[]
  maxConcurrentSteps?: number | null
  cpuTotal?: number
  gpuTotal?: number
  memoryTotal?: number
  diskTotal?: number
  enabled?: boolean
}

/** 队列（GET /clusters/{id}/queues、GET /queues/{queueId}）。 */
export interface QueueItem {
  queueId: string
  queueName: string
  /** 业务编号 CL-0001 */
  clusterId: string
  clusterName: string
  status: string
  /** 出队时判定（docs/06 §6.1） */
  maxConcurrentTasks: number
  /** 提交时判定 → 40902 */
  maxWaitingTasks: number
  /** 0~100（E-07） */
  defaultPriority: number
  allowJumpQueue: boolean
  waitTimeoutSeconds: number
  waitingTaskCount: number
  createdAt: string
}

export interface SaveQueueParams {
  queueName: string
  clusterId?: string
  maxConcurrentTasks?: number
  maxWaitingTasks?: number
  defaultPriority?: number
  allowJumpQueue?: boolean
  waitTimeoutSeconds?: number
}

/** 连通性测试结果（POST /executor-nodes/{id}/test）。 */
export interface NodeTestResult {
  success: boolean
  message: string
  elapsedMs: number
}
