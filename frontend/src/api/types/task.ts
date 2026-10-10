/** 任务域类型（docs/07 §6.4 / §8.1；camelCase，键名转换只在 axios 拦截器）。 */
export interface TaskDetail {
  taskId: string
  status: string
  workflowId: string | null
  workflowName: string
  workflowVersion: string
  triggerType: string
  bizDate: string | null
  projectId: string | null
  submitter: string
  priority: number
  clusterId: string | null
  clusterName: string | null
  queueId: string | null
  queueName: string | null
  triggerId: string | null
  currentStepName: string | null
  currentStepIndex: number | null
  stepTotal: number
  finishedSteps: number
  submitAt: string
  startTime: string | null
  endTime: string | null
  durationMs: number | null
  failReason: string | null
  /** 详情独有：变量快照 / 调度器写入的结构化诊断（M4：原样透传展示） */
  variableSnapshot: unknown
  diagnosisInfo: unknown
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
  /** M4 补充（S1a 详情读侧）：失败诊断与重试计划 */
  failReason: string | null
  failReasonCode: string | null
  maxRetryCount: number | null
  nextRetryAt: string | null
  mutexGroup: string | null
}

/** 任务列表行（GET /tasks，docs/07 §6.4；字段为 TaskVO 子集声明） */
export interface TaskListItem {
  taskId: string
  status: string
  workflowName: string
  workflowVersion: string
  triggerType: string
  bizDate: string | null
  projectId: string | null
  submitter: string
  priority: number
  clusterName: string | null
  queueName: string | null
  currentStepName: string | null
  currentStepIndex: number | null
  stepTotal: number
  finishedSteps: number
  submitAt: string
  startTime: string | null
  endTime: string | null
  durationMs: number | null
  failReason: string | null
}

/** 任务列表筛选（docs/07 §6.4；**查询串不做键转换**，键名按 @RequestParam 原样驼峰） */
export interface TaskListParams {
  page: number
  pageSize: number
  status?: string
  workflowId?: string
  projectId?: string
  clusterId?: string
  triggerType?: string
  bizDateFrom?: string
  bizDateTo?: string
  keyword?: string
}

// ── 调度诊断（M4-S1b，CONTRACT §7 / docs/06 §5.4、§5.5）────────────

export interface DiagnosisEta {
  estimatedSeconds: number | null
  sampleCount: number
  basis: string
}

export interface DiagnosisBlockReason {
  type: string
  desc: string
  stepInstanceId: string | null
  stepName: string | null
  mutexGroup: string | null
  holderDesc: string | null
  waitersCount: number | null
}

export interface DiagnosisSuggestion {
  text: string
}

export interface TaskDiagnosis {
  taskId: string
  status: string
  queuePosition: number | null
  aheadCount: number | null
  eta: DiagnosisEta | null
  blockingReasons: DiagnosisBlockReason[]
  suggestions: DiagnosisSuggestion[]
}

/** 步骤日志分页（CONTRACT §7 GET /tasks/{id}/steps/{sid}/logs） */
export interface TaskLogPage {
  content: string
  eof: boolean
  totalLines: number
  offset: number
  limit: number
}
