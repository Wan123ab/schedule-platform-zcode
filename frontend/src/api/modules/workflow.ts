import { request } from '@/api/http'
import type { PageResult } from '@/api/types/common'
import type {
  PublishWorkflowParams,
  SaveConcurrencyParams,
  SaveTriggerParams,
  SaveWorkflowParams,
  SaveWorkflowVersionParams,
  TriggerItem,
  WorkflowItem,
  WorkflowVersionBrief,
  WorkflowVersionItem,
} from '@/api/types/workflow'

/**
 * 工作流 / 版本 / 触发器域请求（docs/07 §5.4 / CONTRACT §6）。
 *
 * 【与 api/modules/operator.ts 同一条纪律】
 * 这个文件只关心"路径 + 方法 + 出入参类型"，不做业务判断、不做字段拼装。
 * 键转换（camel ↔ snake）由 `api/http.ts` 的拦截器统一负责 —— 但**查询串不转**，
 * 见下面 `WORKFLOW_SORTABLE` 的说明。
 */

/**
 * 列表排序白名单 —— 与后端 `WorkflowService.SORTABLE` 逐字对应（docs/07 §7.4）。
 *
 * 【为什么白名单要出现在前端】
 * `orderBy` 的值会进 ORDER BY 片段，后端因此用白名单挡任意列名，不在表内 → **40003**。
 * 把它的**取值集合**做成下拉选项，用户就没有机会触发 40003。
 * 复制的是"规则的数据"（取值集合本身就是线协议的一部分），不是"规则的实现" ——
 * 与 `types/enums.ts` 里那些枚举映射表同一个思路。
 *
 * 【注意这些值是 snake_case 字面量】
 * 请求拦截器只对 `config.data` 做 `deepSnake`，**不动 `config.params`**；
 * 而这两个值到后端是直接进白名单查表的，所以必须按后端期望原样写。
 */
export const WORKFLOW_SORTABLE = [
  'updated_at',
  'created_at',
  'last_run_at',
  'workflow_name',
  'status',
] as const

export type WorkflowSortKey = (typeof WORKFLOW_SORTABLE)[number]

export const WORKFLOW_SORT_LABEL: Record<WorkflowSortKey, string> = {
  updated_at: '最近更新',
  created_at: '创建时间',
  last_run_at: '最近运行',
  workflow_name: '名称',
  status: '状态',
}

/** 与后端 `WorkflowService.DEFAULT_SORT` 保持一致，避免"前端默认值不在白名单"这种自伤。 */
export const WORKFLOW_DEFAULT_SORT: WorkflowSortKey = 'updated_at'

export type SortDirection = 'asc' | 'desc'

export const workflowApi = {
  /**
   * 分页列表。**只有本端点支持排序**（其余列表端点的 orderBy 是未定义行为）。
   *
   * `orderBy` 传 `WORKFLOW_SORTABLE` 里的字面量；传表外的值后端返回 40003。
   */
  page(params: {
    page: number
    pageSize: number
    /** 业务编号 PRJ-xxxx */
    projectId?: string
    /** DRAFT / PUBLISHED / DISABLED / ARCHIVED */
    status?: string
    keyword?: string
    orderBy?: WorkflowSortKey
    orderDir?: SortDirection
  }): Promise<PageResult<WorkflowItem>> {
    return request<PageResult<WorkflowItem>>({ url: '/workflows', method: 'GET', params })
  },

  get(workflowId: string): Promise<WorkflowItem> {
    return request<WorkflowItem>({ url: `/workflows/${workflowId}`, method: 'GET' })
  },

  create(params: SaveWorkflowParams): Promise<WorkflowItem> {
    return request<WorkflowItem>({ url: '/workflows', method: 'POST', data: params })
  },

  /** 基础信息。`projectId` 只是为了告诉后端"别改归属"—— 传别的项目会 40001。 */
  update(workflowId: string, params: SaveWorkflowParams): Promise<WorkflowItem> {
    return request<WorkflowItem>({ url: `/workflows/${workflowId}`, method: 'PUT', data: params })
  },

  /**
   * 并发设置（独立端点，因为它是 DAG 规则 8 的校验对象，有独立审计动作）。
   *
   * 改这里会影响"这个工作流还能不能发布"：`maxParallelRuns` 为空或 < 1 → 规则 8 失败
   * → 发布时 42213。所以后端用 `@Min(1)` + `@NotNull` 挡住。
   */
  updateConcurrency(workflowId: string, params: SaveConcurrencyParams): Promise<WorkflowItem> {
    return request<WorkflowItem>({
      url: `/workflows/${workflowId}/concurrency`,
      method: 'PUT',
      data: params,
    })
  },

  /**
   * 发布某一版。**必须带 `versionId`** —— "发布最新的"在并发编辑下不可解释。
   *
   * 该端点要求 `Idempotency-Key`（CONTRACT §0.5 的四个高危端点之一），
   * 请求拦截器对 POST/PUT/PATCH 已自动附加，这里不必手写。
   *
   * 发布即跑 **DAG 全量 10 条校验**：违约返回 42213（结构）/42214（变量引用）/
   * 42218（引用了未发布算子版本），三码语义不同，提示方向也不同。
   */
  publish(workflowId: string, params: PublishWorkflowParams): Promise<WorkflowItem> {
    return request<WorkflowItem>({
      url: `/workflows/${workflowId}/publish`,
      method: 'POST',
      data: params,
    })
  },

  /** 停用（只有已发布的工作流可停用）。 */
  disable(workflowId: string): Promise<WorkflowItem> {
    return request<WorkflowItem>({ url: `/workflows/${workflowId}/disable`, method: 'POST' })
  },

  /**
   * 基于当前版本新开草稿（HTTP 201），返回带 DAG 全量的新版本。
   *
   * 已存在未发布草稿时 → **42215**。调用方应把 42215 提示成"先去把那份草稿发布或丢弃"，
   * 而不是笼统的"创建失败"；草稿的版本号可以从 `versions()` 里拿到。
   */
  createDraft(workflowId: string): Promise<WorkflowVersionItem> {
    return request<WorkflowVersionItem>({
      url: `/workflows/${workflowId}/versions`,
      method: 'POST',
    })
  },

  /**
   * 版本列表（新→旧），只读。
   *
   * 【它是干什么用的】`WorkflowItem.hasDraftChanges === true` 只说明"有一份未发布的草稿"，
   * **不带它的版本号**；而 `createDraft` 遇到已有草稿会 42215。
   * 没有这个读接口，草稿一旦离开编辑器就再也找不回来 —— 既回不去也无法发布，
   * 工作流会永久停在"有草稿变更"。列表第一条就是最新版本，草稿就是它时按
   * `publishStatus === 'DRAFT'` 判定。
   */
  versions(workflowId: string): Promise<WorkflowVersionBrief[]> {
    return request<WorkflowVersionBrief[]>({
      url: `/workflows/${workflowId}/versions`,
      method: 'GET',
    })
  },
}

export const workflowVersionApi = {
  /** 版本全量（含 steps/edges/workflowParams）—— 画布的读取入口。 */
  get(versionId: string): Promise<WorkflowVersionItem> {
    return request<WorkflowVersionItem>({
      url: `/workflow-versions/${versionId}`,
      method: 'GET',
    })
  },

  /**
   * 保存草稿：**整包替换**（没提交的步骤/连线等于被删掉）。
   *
   * 失败返回 42213/42214/42218 + `errors[]`（每项带 `rule` 与 `step_name`），
   * 逐个挂到画布对应节点上 —— 一次收齐，不让用户改一条提交一次。
   */
  saveDraft(versionId: string, params: SaveWorkflowVersionParams): Promise<WorkflowVersionItem> {
    return request<WorkflowVersionItem>({
      url: `/workflow-versions/${versionId}`,
      method: 'PUT',
      data: params,
    })
  },
}

export const triggerApi = {
  /** 某工作流下的触发器。`workflowId` **必填**（触发器脱离工作流没有意义）。 */
  listByWorkflow(workflowId: string): Promise<TriggerItem[]> {
    return request<TriggerItem[]>({ url: '/triggers', method: 'GET', params: { workflowId } })
  },

  get(triggerId: string): Promise<TriggerItem> {
    return request<TriggerItem>({ url: `/triggers/${triggerId}`, method: 'GET' })
  },

  /** 创建（42216 = cron/周期配置问题；42217 = 生效窗口 end <= start）。 */
  create(params: SaveTriggerParams): Promise<TriggerItem> {
    return request<TriggerItem>({ url: '/triggers', method: 'POST', data: params })
  },

  /** 编辑 / 启停（`enabled` 也走这个端点，没有单独的开关接口）。 */
  update(triggerId: string, params: SaveTriggerParams): Promise<TriggerItem> {
    return request<TriggerItem>({ url: `/triggers/${triggerId}`, method: 'PUT', data: params })
  },

  remove(triggerId: string): Promise<void> {
    return request<void>({ url: `/triggers/${triggerId}`, method: 'DELETE' })
  },

  /**
   * cron 预览：接下来 N 次触发时间（默认 5，docs/04 §658）。
   *
   * ⚠️ 查询参数名是**蛇形** `cron_expression`（后端 `@RequestParam("cron_expression")`），
   * 不是 `cronExpression`。查询串不做键转换，写错就是 40001「缺少必填参数」，
   * 而报错信息不会告诉你"是命名风格的问题"。
   *
   * 表达式非法 → 42216（方言是 Spring 的 **6 段**：秒 分 时 日 月 周，
   * 不是 Quartz 的 7 段）。
   */
  cronPreview(cronExpression: string, timezone?: string, count?: number): Promise<string[]> {
    return request<string[]>({
      url: '/triggers/cron-preview',
      method: 'GET',
      params: { cron_expression: cronExpression, timezone, count },
    })
  },
}
