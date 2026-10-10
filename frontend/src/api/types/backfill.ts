/** 回填域类型（CONTRACT §8 / docs/06 §12；camelCase，键名转换只在 axios 拦截器）。 */

/** 批次行（列表 / 详情共用；详情多 failures） */
export interface BackfillBatch {
  batchId: string
  batchName: string
  workflowId: string
  workflowName: string
  projectId: string
  bizFrom: string
  bizTo: string
  concurrency: number
  sequential: boolean
  /** 有效并发（受工作流 max_parallel_runs 收敛后的实际投放并发） */
  effConc: number
  status: string
  total: number
  success: number
  failed: number
  waiting: number
  submitter: string
  submittedAt: string
  finishedAt: string | null
  durationMs: number | null
  failures: BackfillFailure[]
}

export interface BackfillFailure {
  bizDate: string
  taskId: string
  reason: string
}

export interface BackfillPreviewRequest {
  workflowId: string
  bizFrom: string
  bizTo: string
  concurrency?: number
  sequential?: boolean
  overrides?: Record<string, unknown>
}

export interface BackfillPlannedTask {
  bizDate: string
  plannedTaskId: string
  plannedAt: string
}

export interface BackfillPolicyConflict {
  policy: string
  maxParallelRuns: number | null
  effConc: number
}

export interface BackfillPreview {
  tasks: BackfillPlannedTask[]
  /** 与既有任务撞车、本次不计划的任务日期 */
  skippedDates: string[]
  plannedCount: number
  conflictCount: number
  effConc: number
  policyConflict: BackfillPolicyConflict | null
  /** 提交时回传的预览指纹（不符 → 42231） */
  previewHash: string
}

export interface BackfillSubmitRequest {
  confirmedPreviewHash: string
}
