import { request } from '@/api/http'
import type { PageResult } from '@/api/types/common'
import type {
  BackfillBatch,
  BackfillPreview,
  BackfillPreviewRequest,
} from '@/api/types/backfill'
import type { TaskListItem } from '@/api/types/task'

/**
 * 回填域请求（CONTRACT §8 的 9 端点；docs/06 §12）。
 *
 * 【提交两段式】preview 产出 previewHash → 提交时回传 confirmedPreviewHash
 * （不符 → 42231「预览已失效」）。幂等键由 http.ts 拦截器统一注入 Idempotency-Key。
 */
export const backfillApi = {
  preview(params: BackfillPreviewRequest): Promise<BackfillPreview> {
    return request<BackfillPreview>({ url: '/backfills/preview', method: 'POST', data: params })
  },
  /** 提交批次（body 只有 confirmedPreviewHash —— 计划内容以预览时的指纹为准） */
  submit(confirmedPreviewHash: string): Promise<BackfillBatch> {
    return request<BackfillBatch>({ url: '/backfills', method: 'POST', data: { confirmedPreviewHash } })
  },
  /** 批次列表（可按状态过滤；查询串键名原样驼峰） */
  page(params: { status?: string; page: number; pageSize: number }): Promise<PageResult<BackfillBatch>> {
    return request<PageResult<BackfillBatch>>({ url: '/backfills', method: 'GET', params })
  },
  /** 已完结批次历史（含 DONE / CANCELLED，供追溯） */
  history(params: { page: number; pageSize: number }): Promise<PageResult<BackfillBatch>> {
    return request<PageResult<BackfillBatch>>({ url: '/backfills/history', method: 'GET', params })
  },
  get(batchId: string): Promise<BackfillBatch> {
    return request<BackfillBatch>({ url: `/backfills/${batchId}`, method: 'GET' })
  },
  /** 批次下的任务实例分页（一个业务日期可能有两行：失败重跑新建 + 原行保留审计） */
  tasks(batchId: string, page = 1, pageSize = 50): Promise<PageResult<TaskListItem>> {
    return request<PageResult<TaskListItem>>({
      url: `/backfills/${batchId}/tasks`,
      method: 'GET',
      params: { page, pageSize },
    })
  },
  pause(batchId: string): Promise<BackfillBatch> {
    return request<BackfillBatch>({ url: `/backfills/${batchId}/pause`, method: 'POST' })
  },
  resume(batchId: string): Promise<BackfillBatch> {
    return request<BackfillBatch>({ url: `/backfills/${batchId}/resume`, method: 'POST' })
  },
  /** 失败日期重跑（新建任务实例，原 FAILED 行保留审计） */
  retryFailed(batchId: string): Promise<BackfillBatch> {
    return request<BackfillBatch>({ url: `/backfills/${batchId}/retry-failed`, method: 'POST' })
  },
}
