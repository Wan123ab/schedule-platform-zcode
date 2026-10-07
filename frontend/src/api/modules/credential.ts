import { request } from '@/api/http'
import type { PageResult } from '@/api/types/common'
import type { CredentialItem, SaveCredentialParams } from '@/api/types/credential'

/**
 * 凭据域请求（docs/07 §6.2 / CONTRACT §4）。
 *
 * 【为什么入参类型把 secret 标成可选】
 * `secret` 只在两个时机上行：创建时必填、轮换时单独走 rotate。
 * 编辑其它字段（改名/改描述/改过期时间）时<b>不该</b>带 secret ——
 * 带了就等于"顺手改了密码"，而用户的本意只是改个名字。
 * 类型上可选 + 调用点显式（见 CredentialListView 的编辑分支）比后端兜底更早一步拦住。
 */
export const credentialApi = {
  page(params: {
    page: number
    pageSize: number
    keyword?: string
  }): Promise<PageResult<CredentialItem>> {
    return request<PageResult<CredentialItem>>({ url: '/credentials', method: 'GET', params })
  },
  get(credentialId: string): Promise<CredentialItem> {
    return request<CredentialItem>({ url: `/credentials/${credentialId}`, method: 'GET' })
  },
  create(params: SaveCredentialParams): Promise<CredentialItem> {
    return request<CredentialItem>({ url: '/credentials', method: 'POST', data: params })
  },
  update(credentialId: string, params: SaveCredentialParams): Promise<CredentialItem> {
    return request<CredentialItem>({ url: `/credentials/${credentialId}`, method: 'PUT', data: params })
  },
  /** 轮换：换密文 + 刷新指纹；引用它的节点下次执行自动使用新凭据（无需重新绑定） */
  rotate(credentialId: string, secret: string): Promise<CredentialItem> {
    return request<CredentialItem>({
      url: `/credentials/${credentialId}/rotate`,
      method: 'POST',
      data: { secret },
    })
  },
  remove(credentialId: string): Promise<void> {
    return request<void>({ url: `/credentials/${credentialId}`, method: 'DELETE' })
  },
}
