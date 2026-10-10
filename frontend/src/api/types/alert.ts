/** 告警域类型（CONTRACT §9 / docs/07 §9；camelCase，键名转换只在 axios 拦截器）。 */

/** 事件类型：以 DDL CHECK 9 值 + V5 seed 为准（README-M4 勘定，CONTRACT §9 只列 5 个是过时口径） */
export const ALERT_EVENT_TYPES = [
  'TASK_FAILED',
  'TASK_TIMEOUT',
  'STEP_RETRY_EXHAUSTED',
  'NODE_OFFLINE',
  'CONCURRENCY_LIMIT',
  'WORKFLOW_FAIL_RATE',
  'QUEUE_BACKLOG',
  'BACKFILL_STALL',
  'PLATFORM_ERROR',
] as const
export type AlertEventType = (typeof ALERT_EVENT_TYPES)[number]

export const ALERT_EVENT_TYPE_LABEL: Record<AlertEventType, string> = {
  TASK_FAILED: '任务失败',
  TASK_TIMEOUT: '任务超时',
  STEP_RETRY_EXHAUSTED: '步骤重试耗尽',
  NODE_OFFLINE: '节点离线',
  CONCURRENCY_LIMIT: '并发受限',
  WORKFLOW_FAIL_RATE: '工作流失败率',
  QUEUE_BACKLOG: '队列积压',
  BACKFILL_STALL: '回填停滞',
  PLATFORM_ERROR: '平台异常',
}

/** 渠道类型 4 值（DDL CHECK；EMAIL 一期占位不真实投递 —— O-65） */
export const ALERT_CHANNEL_TYPES = ['WEBHOOK', 'EMAIL', 'WECOM', 'DINGTALK'] as const
export type AlertChannelType = (typeof ALERT_CHANNEL_TYPES)[number]

export const ALERT_CHANNEL_TYPE_LABEL: Record<AlertChannelType, string> = {
  WEBHOOK: 'Webhook',
  EMAIL: '邮件',
  WECOM: '企业微信',
  DINGTALK: '钉钉',
}

export const ALERT_LEVEL_LABEL: Record<string, string> = {
  CRITICAL: '严重',
  WARN: '警告',
  INFO: '提示',
}

export const ALERT_RESULT_LABEL: Record<string, string> = {
  SENT: '已送达',
  PARTIAL: '部分送达',
  FAILED: '发送失败',
}

export interface AlertRule {
  ruleId: string
  ruleName: string
  eventType: string
  condition: Record<string, unknown> | null
  channels: string[]
  receivers: string[]
  suppressWindowMinutes: number
  projectId: string | null
  clusterId: string | null
  enabled: boolean
  creator: string
  createdAt: string
  updatedAt: string
}

/** 规则新建 / 编辑（projectId/clusterId 为空 = 全局；scope 展平口径见 O-66） */
export interface AlertRuleUpsertParams {
  ruleName: string
  eventType: string
  condition?: Record<string, unknown>
  channels: string[]
  receivers?: string[]
  suppressWindowMinutes: number
  projectId?: string
  clusterId?: string
}

export interface AlertChannel {
  channelType: string
  endpoint: string | null
  enabled: boolean
  verified: boolean
  lastTestAt: string | null
  lastTestResult: string | null
}

export interface AlertChannelUpdateParams {
  endpoint: string
  secret?: string
}

export interface AlertTestResult {
  ok: boolean
  latencyMs: number
  error: string | null
}

export interface AlertRecord {
  alertId: string
  ruleId: string | null
  eventType: string
  level: string
  targetType: string
  targetId: number
  targetName: string
  projectId: string | null
  clusterId: string | null
  content: string
  channels: string[]
  deliverResult: { channel: string; ok: boolean; error: string | null }[]
  result: string
  firedAt: string
}
