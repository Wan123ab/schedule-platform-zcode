import { request } from '@/api/http'
import type { LoginParams, LoginResult } from '@/api/types/auth'

export const authApi = {
  login(params: LoginParams): Promise<LoginResult> {
    return request<LoginResult>({ url: '/auth/login', method: 'POST', data: params })
  },
  logout(): Promise<void> {
    return request<void>({ url: '/auth/logout', method: 'POST' })
  },
  me(): Promise<{ username: string; displayName: string; permissions: string[] }> {
    return request({ url: '/auth/me', method: 'GET' })
  },
}
