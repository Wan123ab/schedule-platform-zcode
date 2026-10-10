import { request } from '@/api/http'
import type { PageResult } from '@/api/types/common'
import type {
  AlertChannel,
  AlertChannelType,
  AlertChannelUpdateParams,
  AlertRecord,
  AlertRule,
  AlertRuleUpsertParams,
  AlertTestResult,
} from '@/api/types/alert'

/**
 * 告警域请求（CONTRACT §9 的 9 端点；docs/07 §9）。
 * secret 只写不读：渠道 VO 永不回显（后端 AES-256-GCM 加密落库）。
 */
export const alertApi = {
  // ── 规则（/alert-rules）────────────────────────────────
  listRules(): Promise<AlertRule[]> {
    return request<AlertRule[]>({ url: '/alert-rules', method: 'GET' })
  },
  createRule(params: AlertRuleUpsertParams): Promise<AlertRule> {
    return request<AlertRule>({ url: '/alert-rules', method: 'POST', data: params })
  },
  updateRule(ruleId: string, params: AlertRuleUpsertParams): Promise<AlertRule> {
    return request<AlertRule>({ url: `/alert-rules/${ruleId}`, method: 'PUT', data: params })
  },
  /** 启停（审计动作码并入 UPDATE_ALERT_RULE，O-68） */
  updateRuleEnabled(ruleId: string, enabled: boolean): Promise<AlertRule> {
    return request<AlertRule>({
      url: `/alert-rules/${ruleId}/enabled`,
      method: 'PUT',
      data: { enabled },
    })
  },
  removeRule(ruleId: string): Promise<void> {
    return request<void>({ url: `/alert-rules/${ruleId}`, method: 'DELETE' })
  },

  // ── 渠道（/alert-channels）────────────────────────────
  listChannels(): Promise<AlertChannel[]> {
    return request<AlertChannel[]>({ url: '/alert-channels', method: 'GET' })
  },
  /** upsert（channel_type UNIQUE，表初始为空 —— 首次即创建） */
  updateChannel(type: AlertChannelType, params: AlertChannelUpdateParams): Promise<AlertChannel> {
    return request<AlertChannel>({ url: `/alert-channels/${type}`, method: 'PUT', data: params })
  },
  /** 真发测试（成功置 verified；EMAIL 占位恒失败 O-65） */
  testChannel(type: AlertChannelType): Promise<AlertTestResult> {
    return request<AlertTestResult>({ url: `/alert-channels/${type}/test`, method: 'POST' })
  },

  // ── 记录（/alert-history）─────────────────────────────
  /** 查询串键名原样驼峰（eventType）；from/to 为 ISO 日期时间 */
  history(params: {
    eventType?: string
    from?: string
    to?: string
    page?: number
    pageSize?: number
  }): Promise<PageResult<AlertRecord>> {
    return request<PageResult<AlertRecord>>({ url: '/alert-history', method: 'GET', params })
  },
}
