import { request } from '@/api/http'
import type { PageResult } from '@/api/types/common'
import type {
  OperatorItem,
  OperatorReferenceItem,
  OperatorVersionItem,
  OperatorVersionMeta,
  SaveDefaultParamsParams,
  SaveOperatorParams,
} from '@/api/types/operator'

/**
 * 算子域请求（docs/07 §5.4 / CONTRACT §5）。
 *
 * 【这个文件只关心两件事】
 * ① 路径与方法（与后端 @RequestMapping 一一对应）；
 * ② 出入参的 TS 类型（写 camelCase，键转换交给 http.ts 拦截器）。
 * 不在这里做业务判断、不做字段拼装。
 *
 * 【为什么试运行（dry-run）不在这里】
 * 它是 SSE + POST，走的不是 axios 那条 JSON 通道（拦截器无法处理流式响应），
 * 需要 `fetch` + `ReadableStream` 手工解析 —— 见 `composables/useDryRunStream.ts`。
 */
export const operatorApi = {
  page(params: {
    page: number
    pageSize: number
    operatorType?: string
    /** 业务编号 PRJ-xxxx */
    projectId?: string
    keyword?: string
  }): Promise<PageResult<OperatorItem>> {
    return request<PageResult<OperatorItem>>({ url: '/operators', method: 'GET', params })
  },

  get(operatorId: string): Promise<OperatorItem> {
    return request<OperatorItem>({ url: `/operators/${operatorId}`, method: 'GET' })
  },

  create(params: SaveOperatorParams): Promise<OperatorItem> {
    return request<OperatorItem>({ url: '/operators', method: 'POST', data: params })
  },

  update(operatorId: string, params: SaveOperatorParams): Promise<OperatorItem> {
    return request<OperatorItem>({ url: `/operators/${operatorId}`, method: 'PUT', data: params })
  },

  /**
   * 删除算子。
   *
   * 任一版本被工作流步骤引用时后端返回 **42211**（闸门在 `OperatorMapper#countVersionReferences`）。
   * 前端应把 42211 单独提示成"先去工作流里解绑"，而不是笼统的"删除失败"。
   */
  remove(operatorId: string): Promise<void> {
    return request<void>({ url: `/operators/${operatorId}`, method: 'DELETE' })
  },

  /** 某算子的全部版本（非分页）。 */
  listVersions(operatorId: string): Promise<OperatorVersionItem[]> {
    return request<OperatorVersionItem[]>({ url: `/operators/${operatorId}/versions`, method: 'GET' })
  },
}

/**
 * meta 要作为**一段 JSON 字符串**塞进 multipart 的 part 里（不是独立字段）。
 * 序列化收在这里一处，调用方不必重复 `JSON.stringify`。
 */
function versionForm(meta: OperatorVersionMeta, file?: File): FormData {
  const form = new FormData()
  if (file) {
    form.append('file', file)
  }
  form.append('meta', JSON.stringify(meta))
  return form
}

export const operatorVersionApi = {
  /**
   * 上传新版本（HTTP 201）。
   *
   * 刻意**不设** `Content-Type`：axios 1.x 检测到 FormData 时会清掉实例默认的
   * `application/json`，改由浏览器补 `multipart/form-data; boundary=...`。
   * 一旦手写 `multipart/form-data`（没有 boundary），服务端会解析不出任何 part。
   */
  upload(operatorId: string, file: File, meta: OperatorVersionMeta): Promise<OperatorVersionItem> {
    return request<OperatorVersionItem>({
      url: `/operators/${operatorId}/versions`,
      method: 'POST',
      data: versionForm(meta, file),
    })
  },

  get(versionId: string): Promise<OperatorVersionItem> {
    return request<OperatorVersionItem>({ url: `/operator-versions/${versionId}`, method: 'GET' })
  },

  /** 编辑草稿：只发 meta，不换文件（D-11 版本不可变 —— 文件与结构都冻结）。 */
  updateMeta(versionId: string, meta: OperatorVersionMeta): Promise<OperatorVersionItem> {
    return request<OperatorVersionItem>({
      url: `/operator-versions/${versionId}`,
      method: 'PUT',
      data: versionForm(meta),
    })
  },

  publish(versionId: string): Promise<OperatorVersionItem> {
    return request<OperatorVersionItem>({
      url: `/operator-versions/${versionId}/publish`,
      method: 'POST',
    })
  },

  offline(versionId: string): Promise<OperatorVersionItem> {
    return request<OperatorVersionItem>({
      url: `/operator-versions/${versionId}/offline`,
      method: 'POST',
    })
  },

  /** 被哪些工作流步骤引用（删除闸门 42211 的解释与排障入口）。 */
  references(versionId: string): Promise<OperatorReferenceItem[]> {
    return request<OperatorReferenceItem[]>({
      url: `/operator-versions/${versionId}/references`,
      method: 'GET',
    })
  },

  /**
   * 把试运行的参数另存为该版本的默认值（PRD §10.6 的后续动作）。
   *
   * 只改 `param_template[].default_value` 一列 —— 默认值只是"新建步骤时的预填值"，
   * 不进任何已保存步骤的参数快照，故不违反 D-11 的版本不可变。
   * **敏感参数会被后端拒绝（42210）**：默认值明文出网，不能让它成为脱敏的后门。
   */
  saveParamDefaults(versionId: string, params: Record<string, string>): Promise<OperatorVersionItem> {
    const body: SaveDefaultParamsParams = { params }
    return request<OperatorVersionItem>({
      url: `/operator-versions/${versionId}/param-defaults`,
      method: 'PUT',
      data: body,
    })
  },
}
