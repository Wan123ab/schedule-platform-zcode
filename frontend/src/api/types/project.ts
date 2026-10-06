/** 项目域类型（docs/07 §6.1 / §2）。 */
export interface ProjectItem {
  projectId: string
  projectName: string
  description: string | null
  status: string
  maxConcurrentTasks: number
  maxWaitingTasks: number
  ownerUsername: string | null
  statWorkflowCount: number
  statTaskCount: number
  statMemberCount: number
  createdAt: string
}

export interface ProjectImpact {
  workflowCount: number
  runningTaskCount: number
  memberCount: number
  triggerCount: number
  blocking: string[]
}

export interface SaveProjectParams {
  projectName: string
  description?: string
  defaultParams?: Record<string, unknown>
}
