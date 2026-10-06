/** 任务域类型（docs/07 §6.4 / §8.1；camelCase，键名转换只在 axios 拦截器）。 */
export interface TaskDetail {
  taskId: string
  status: string
  workflowName: string
  workflowVersion: string
  priority: number
  submitter: string
  submitAt: string
  startTime: string | null
  endTime: string | null
  durationMs: number | null
  stepTotal: number
  finishedSteps: number
}

export interface TaskStepItem {
  /** 物理主键：日志 WebSocket 通道的定位键（row_id） */
  rowId: number
  stepInstanceId: string
  stepName: string
  stepIndex: number
  status: string
  machineIp: string | null
  startTime: string | null
  endTime: string | null
  durationMs: number | null
  exitCode: number | null
  retryCount: number
}
