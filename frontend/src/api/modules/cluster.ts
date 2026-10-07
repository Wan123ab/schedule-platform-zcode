import { request } from '@/api/http'
import type { PageResult } from '@/api/types/common'
import type {
  ClusterItem,
  ExecutorNodeItem,
  NodeTestResult,
  QueueItem,
  SaveClusterParams,
  SaveExecutorNodeParams,
  SaveQueueParams,
} from '@/api/types/cluster'

/**
 * 集群域请求（docs/07 §5.4 / CONTRACT §3）。
 *
 * 【本文件是"网络边界"的一部分，只有两件事要关心】
 * ① 路径与方法（与后端 @RequestMapping 一一对应）；
 * ② 出入参的 TS 类型（字段名写 camelCase，转换交给 http.ts 的拦截器）。
 * 不在这里做任何业务判断、不做字段拼装 —— 那是视图与后端各自的职责。
 */
export const clusterApi = {
  page(params: {
    page: number
    pageSize: number
    status?: string
    keyword?: string
  }): Promise<PageResult<ClusterItem>> {
    return request<PageResult<ClusterItem>>({ url: '/clusters', method: 'GET', params })
  },
  get(clusterId: string): Promise<ClusterItem> {
    return request<ClusterItem>({ url: `/clusters/${clusterId}`, method: 'GET' })
  },
  create(params: SaveClusterParams): Promise<ClusterItem> {
    return request<ClusterItem>({ url: '/clusters', method: 'POST', data: params })
  },
  update(clusterId: string, params: SaveClusterParams): Promise<ClusterItem> {
    return request<ClusterItem>({ url: `/clusters/${clusterId}`, method: 'PUT', data: params })
  },
  /** 维护开关单独一个端点：审计上与"普通编辑"区分开（MAINTENANCE_CLUSTER 是独立动作码） */
  updateStatus(clusterId: string, status: 'NORMAL' | 'MAINTENANCE'): Promise<ClusterItem> {
    return request<ClusterItem>({ url: `/clusters/${clusterId}/status`, method: 'PUT', data: { status } })
  },
  remove(clusterId: string): Promise<void> {
    return request<void>({ url: `/clusters/${clusterId}`, method: 'DELETE' })
  },

  /** 集群下节点分页（集群详情页节点 tab；tag 走数组包含过滤） */
  nodes(
    clusterId: string,
    params: {
      page: number
      pageSize: number
      onlineStatus?: string
      osType?: string
      tag?: string
    },
  ): Promise<PageResult<ExecutorNodeItem>> {
    return request<PageResult<ExecutorNodeItem>>({
      url: `/clusters/${clusterId}/nodes`,
      method: 'GET',
      params,
    })
  },
  createNode(clusterId: string, params: SaveExecutorNodeParams): Promise<ExecutorNodeItem> {
    return request<ExecutorNodeItem>({
      url: `/clusters/${clusterId}/nodes`,
      method: 'POST',
      data: params,
    })
  },

  /** 集群下队列分页 */
  queues(
    clusterId: string,
    params: { page: number; pageSize: number; status?: string },
  ): Promise<PageResult<QueueItem>> {
    return request<PageResult<QueueItem>>({
      url: `/clusters/${clusterId}/queues`,
      method: 'GET',
      params,
    })
  },
  createQueue(clusterId: string, params: SaveQueueParams): Promise<QueueItem> {
    return request<QueueItem>({
      url: `/clusters/${clusterId}/queues`,
      method: 'POST',
      data: params,
    })
  },
}

/** 执行节点域请求。 */
export const nodeApi = {
  get(nodeId: string): Promise<ExecutorNodeItem> {
    return request<ExecutorNodeItem>({ url: `/executor-nodes/${nodeId}`, method: 'GET' })
  },
  update(nodeId: string, params: SaveExecutorNodeParams): Promise<ExecutorNodeItem> {
    return request<ExecutorNodeItem>({ url: `/executor-nodes/${nodeId}`, method: 'PUT', data: params })
  },
  /** 启停（服务端 42205 闸门：有运行中步骤则拒绝禁用） */
  setEnabled(nodeId: string, enabled: boolean): Promise<ExecutorNodeItem> {
    return request<ExecutorNodeItem>({
      url: `/executor-nodes/${nodeId}/enabled`,
      method: 'PUT',
      data: { enabled },
    })
  },
  remove(nodeId: string): Promise<void> {
    return request<void>({ url: `/executor-nodes/${nodeId}`, method: 'DELETE' })
  },
  /** 连通性测试：真实 SSH 握手，返回耗时与摘要（失败也是正常返回，不是异常） */
  test(nodeId: string): Promise<NodeTestResult> {
    return request<NodeTestResult>({ url: `/executor-nodes/${nodeId}/test`, method: 'POST' })
  },
}

/** 队列域请求。 */
export const queueApi = {
  get(queueId: string): Promise<QueueItem> {
    return request<QueueItem>({ url: `/queues/${queueId}`, method: 'GET' })
  },
  update(queueId: string, params: SaveQueueParams): Promise<QueueItem> {
    return request<QueueItem>({ url: `/queues/${queueId}`, method: 'PUT', data: params })
  },
  updateStatus(queueId: string, status: 'ENABLED' | 'DISABLED'): Promise<QueueItem> {
    return request<QueueItem>({ url: `/queues/${queueId}/status`, method: 'PUT', data: { status } })
  },
  remove(queueId: string): Promise<void> {
    return request<void>({ url: `/queues/${queueId}`, method: 'DELETE' })
  },
}
