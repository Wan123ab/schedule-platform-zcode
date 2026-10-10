import { request } from '@/api/http'
import type { PageResult } from '@/api/types/common'
import type {
  TaskDetail,
  TaskDiagnosis,
  TaskListItem,
  TaskListParams,
  TaskLogPage,
  TaskStepItem,
} from '@/api/types/task'

/**
 * 任务域请求（docs/07 §6.4 / CONTRACT §7）。
 *
 * 【查询串命名纪律】`params` 不做键名转换（拦截器只转 `config.data`）——
 * 本模块的查询参数名必须按后端 `@RequestParam` **原样驼峰**书写
 * （`bizDateFrom` / `pageSize`），不要"矫正"成蛇形（tests/m4Contract.spec.ts 钉住）。
 */
export const taskApi = {
  /** 任务列表分页（S1a） */
  page(params: TaskListParams): Promise<PageResult<TaskListItem>> {
    return request<PageResult<TaskListItem>>({ url: '/tasks', method: 'GET', params })
  },
  get(taskId: string): Promise<TaskDetail> {
    return request<TaskDetail>({ url: `/tasks/${taskId}`, method: 'GET' })
  },
  steps(taskId: string): Promise<TaskStepItem[]> {
    return request<TaskStepItem[]>({ url: `/tasks/${taskId}/steps`, method: 'GET' })
  },
  /** 调度诊断（S1b：排队位置 / ETA / 阻塞原因 / 建议） */
  diagnosis(taskId: string): Promise<TaskDiagnosis> {
    return request<TaskDiagnosis>({ url: `/tasks/${taskId}/diagnosis`, method: 'GET' })
  },
  /** 步骤日志分页（原文查看，权限点 task:log:raw） */
  logs(taskId: string, stepInstanceId: string, offset = 0, limit = 1000): Promise<TaskLogPage> {
    return request<TaskLogPage>({
      url: `/tasks/${taskId}/steps/${stepInstanceId}/logs`,
      method: 'GET',
      params: { offset, limit },
    })
  },

  // ── 人工干预（M4-S2；响应为干预后的任务详情，stop 返回 STOPPING 中间态）──
  stop(taskId: string, stopReason: string): Promise<TaskDetail> {
    return request<TaskDetail>({ url: `/tasks/${taskId}/stop`, method: 'POST', data: { stopReason } })
  },
  /** 整任务重跑 = 新建任务实例（docs/06 §9.3），原任务保持终态 */
  retry(taskId: string): Promise<TaskDetail> {
    return request<TaskDetail>({ url: `/tasks/${taskId}/retry`, method: 'POST' })
  },
  /** 重跑失败步骤（原实例续跑）；stepInstanceIds 省略 = 全部失败步骤及其下游 */
  rerunFailed(taskId: string, stepInstanceIds?: string[]): Promise<TaskDetail> {
    return request<TaskDetail>({
      url: `/tasks/${taskId}/rerun-failed`,
      method: 'POST',
      data: { stepInstanceIds: stepInstanceIds ?? [] },
    })
  },
  /** 插队（仅 PENDING；互斥组任务被 42200 拒绝） */
  enqueueFront(taskId: string): Promise<TaskDetail> {
    return request<TaskDetail>({ url: `/tasks/${taskId}/enqueue-front`, method: 'POST' })
  },
}
