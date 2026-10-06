import axios from 'axios'
import type { AxiosRequestConfig, InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { deepCamel, deepSnake } from '@/utils/casename'
import { messageOf } from '@/utils/errorMessage'
import { useAuthStore } from '@/stores/auth'
import type { ApiResult } from '@/api/types/common'

/**
 * Axios 实例（docs/04 §4）。
 * ❌ 不开 withCredentials：认证走 Authorization: Bearer（D-06），非 Cookie。
 */
const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api/v1',
  timeout: 20_000,
  headers: { 'Content-Type': 'application/json; charset=utf-8' },
})

const newTraceId = (): string =>
  (crypto.randomUUID?.() ?? `${Date.now()}${Math.random()}`).replace(/-/g, '').slice(0, 32).padEnd(32, '0')

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  // traceId：32 位无连字符小写 hex（与后端 I-01 口径一致）
  config.headers['X-Trace-Id'] = newTraceId()
  // 幂等（D-17）：写操作建议携带
  if (config.method && ['post', 'put', 'patch'].includes(config.method)) {
    config.headers['Idempotency-Key'] = newTraceId()
  }
  // 请求出口转 snake（D-15）
  if (config.data && !(config.data instanceof FormData)) {
    config.data = deepSnake(config.data)
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    // 响应入口转 camel（D-15）
    const payload = deepCamel<ApiResult<unknown>>(response.data)
    if (payload && typeof payload.code === 'number' && payload.code !== 0) {
      return Promise.reject(buildError(payload))
    }
    return payload.data as never
  },
  (error) => {
    const payload = error.response?.data ? deepCamel<ApiResult<unknown>>(error.response.data) : null
    if (payload && typeof payload.code === 'number') {
      return Promise.reject(buildError(payload))
    }
    ElMessage.error('网络异常，请检查连接后重试')
    return Promise.reject(error)
  },
)

export interface BizError extends Error {
  code: number
  payload?: Record<string, unknown>
  traceId?: string
}

function buildError(payload: ApiResult<unknown>): BizError {
  const code = payload.code
  // 认证类错误：拦截器统一处理，视图层不感知（docs/04 §4.3）
  if (code === 40100 || code === 40102) {
    const auth = useAuthStore()
    auth.reset()
    ElMessage.error(messageOf(code, payload.message))
    window.location.href = `/login?redirect=${encodeURIComponent(window.location.pathname)}`
  } else if (code === 40101) {
    ElMessage.error(messageOf(code, payload.message))
  } else if (code === 50300 || code === 50301) {
    ElMessage.error(messageOf(code, payload.message))
  } else if (![40300, 40400, 40001, 40900].includes(code)) {
    // 无歧义错误统一 Toast；需业务分支的错误（40300/40400/40001/40900 等）透传调用方
    ElMessage.error(messageOf(code, payload.message))
  }
  const err = new Error(payload.message) as BizError
  err.code = code
  err.payload = (payload as unknown as Record<string, unknown>).data as Record<string, unknown> | undefined
  err.traceId = payload.trace_id
  return err
}

/** 泛型请求：业务层直接拿 data（不再层层 .data.data）。 */
export function request<T>(config: AxiosRequestConfig): Promise<T> {
  return http.request(config) as Promise<T>
}

export default http
