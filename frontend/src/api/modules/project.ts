import { request } from '@/api/http'
import type { PageResult } from '@/api/types/common'
import type { ProjectImpact, ProjectItem, SaveProjectParams } from '@/api/types/project'

/** 项目域请求（docs/07 §2；停用前必须先 impact——§6.1 闸门的前半段在服务端，前半在前端引导）。 */
export const projectApi = {
  page(params: { page: number; pageSize: number; keyword?: string }): Promise<PageResult<ProjectItem>> {
    return request<PageResult<ProjectItem>>({ url: '/projects', method: 'GET', params })
  },
  get(projectId: string): Promise<ProjectItem> {
    return request<ProjectItem>({ url: `/projects/${projectId}`, method: 'GET' })
  },
  create(params: SaveProjectParams): Promise<ProjectItem> {
    return request<ProjectItem>({ url: '/projects', method: 'POST', data: params })
  },
  update(projectId: string, params: SaveProjectParams): Promise<ProjectItem> {
    return request<ProjectItem>({ url: `/projects/${projectId}`, method: 'PUT', data: params })
  },
  /** 停用影响面（42203 闸门的友好化前置：先展示受影响清单再让用户确认） */
  impact(projectId: string): Promise<ProjectImpact> {
    return request<ProjectImpact>({ url: `/projects/${projectId}/impact`, method: 'GET' })
  },
  updateStatus(projectId: string, status: 'ENABLED' | 'DISABLED'): Promise<void> {
    return request<void>({ url: `/projects/${projectId}/status`, method: 'PUT', data: { status } })
  },
}
