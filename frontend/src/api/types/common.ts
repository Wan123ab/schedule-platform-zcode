/** 线协议公共类型（docs/07 §3.3；trace_id 为 snake_case，D-05）。 */
export interface ApiResult<T> {
  code: number
  message: string
  data: T
  trace_id: string
}

export interface PageResult<T> {
  total: number
  page: number
  pageSize: number
  records: T[]
  totalCapped: boolean
}
