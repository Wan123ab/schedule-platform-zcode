import { request } from '@/api/http'
import type { TaskDetail, TaskStepItem } from '@/api/types/task'

/** 任务域请求（M1 最小集：详情 / 步骤；列表与干预随 M4）。 */
export const taskApi = {
  get(taskId: string): Promise<TaskDetail> {
    return request<TaskDetail>({ url: `/tasks/${taskId}`, method: 'GET' })
  },
  steps(taskId: string): Promise<TaskStepItem[]> {
    return request<TaskStepItem[]>({ url: `/tasks/${taskId}/steps`, method: 'GET' })
  },
}
