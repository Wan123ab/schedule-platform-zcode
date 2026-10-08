/**
 * 工作流 / 版本 / DAG / 触发器域类型（docs/07 §9；字段口径 docs/05 §3.4）。
 *
 * 【命名说明（D-15）】全部 camelCase，snake_case 只存在于网络边界（api/http.ts）。
 *
 * 【出网一律业务编号（D-27）】
 * `workflowId` = `WF-0042`、`versionId` = `WFV-0042-01`、`operatorId` = `OP-0042`、
 * `targetClusterId` = `CL-0001` …… 内部 bigint 主键从不出网，故一律 `string`。
 *
 * 【用户数据的键不做转换（§5-13 的教训）】
 * `params` / `customParams` / `workflowParams` / `runParams` 这些字段的**键是用户填的**，
 * `utils/casename.ts` 的 `OPAQUE_VALUE_KEYS` 会保证它们不被 `deepSnake`/`deepCamel` 改写。
 * 在这里写成 `Record<string, ...>` 时不必额外做保护，但**不要**在别处手写转换。
 */

/** 工作流列表项 / 详情（GET /workflows、GET /workflows/{workflowId}）。 */
export interface WorkflowItem {
  /** WF-#### */
  workflowId: string
  workflowName: string
  /** PRJ-xxxx */
  projectId: string
  projectName: string
  description: string | null
  /** DRAFT / PUBLISHED / DISABLED / ARCHIVED */
  status: string
  /** 当前生效版本，从未发布过时为 null */
  currentVersion: WorkflowVersionBrief | null
  /** 新开草稿后与原发布版本的差异标记（42215 的判定依据） */
  hasDraftChanges: boolean
  /** FORBID / ALLOW / QUEUE */
  concurrencyPolicy: string
  maxParallelRuns: number
  clusterAffinityEnabled: boolean
  defaultTimeoutSeconds: number | null
  defaultRetryCount: number | null
  defaultRetryIntervalSeconds: number | null
  defaultFailureStrategy: string | null
  creator: string
  lastRunStatus: string | null
  lastRunAt: string | null
  createdAt: string
  updatedAt: string
}

/** 工作流列表里"当前版本"的摘要（嵌套对象，不是完整版本）。 */
export interface WorkflowVersionBrief {
  /** WFV-####-## */
  versionId: string
  versionNo: string
  /** DRAFT / PUBLISHED / ARCHIVED */
  publishStatus: string
  stepCount: number
  publisher: string | null
  publishedAt: string | null
}

export interface SaveWorkflowParams {
  workflowName: string
  /** PRJ-xxxx；创建后不可变更（换项目等于搬出数据范围边界） */
  projectId: string
  description?: string
}

export interface SaveConcurrencyParams {
  /** FORBID / ALLOW / QUEUE */
  concurrencyPolicy: string
  maxParallelRuns: number
}

/** 发布工作流版本。注意该端点要求 `Idempotency-Key` 头 —— 拦截器对 POST 会自动带上。 */
export interface PublishWorkflowParams {
  /** WFV-####-## */
  versionId: string
}

/** DAG 节点（画布上一个框）。请求与响应同形。 */
export interface DagStepDef {
  /**
   * 会话内稳定键（≤32 字符）：**只在本次请求里有效**，服务端保存时会重新发号。
   * 它的唯一用途是让 edges 能指向"同一个请求里的某个步骤"，不要把它当数据库主键。
   */
  stepId: string
  /** 步骤名（变量引用的键，工作流内唯一）：`${step.清洗.output.x}` 里的"清洗" */
  stepName: string
  /** TASK / NOTE —— NOTE 不参与执行与终结判定（PRD §10.8 规则 1） */
  stepType: string
  description: string | null
  /** OP-xxxx */
  operatorId: string | null
  /** OPV-xxxx-## */
  operatorVersionId: string | null
  /** 算子参数（键是 param_key，值可含变量引用） */
  params: Record<string, unknown> | null
  /** 自定义参数（不来自算子模板的那部分） */
  customParams: Record<string, unknown> | null
  /** CL-xxxx */
  targetClusterId: string | null
  /** QU-xxxx */
  targetQueueId: string | null
  /** LINUX / WINDOWS */
  osConstraint: string | null
  tagConstraint: string[] | null
  cpu: number | null
  gpu: number | null
  /** MB */
  memory: number | null
  /** MB */
  disk: number | null
  timeoutSeconds: number | null
  /** 重试次数上限（PRD §10.8 规则 7：不超过 10） */
  retryCount: number | null
  retryIntervalSeconds: number | null
  /** TERMINATE / RETRY */
  failureStrategy: string | null
  /** 互斥组名（同组内同一时刻只允许一个步骤在跑） */
  mutexGroup: string | null
  /** 画布坐标 */
  posX: number | null
  posY: number | null
}

/** DAG 连线。两端引用的是**同请求内**的 `DagStepDef.stepId`，不是数据库主键。 */
export interface DagEdgeDef {
  sourceStepId: string
  targetStepId: string
}

/**
 * 保存草稿（PUT /workflow-versions/{versionId}）—— **整包替换**。
 *
 * 也就是说：没提交的步骤/连线等于被删掉。页面必须先拿到完整 DAG（GET 版本详情）
 * 再在其上修改，而不是只发改动的那一部分。
 */
export interface SaveWorkflowVersionParams {
  steps: DagStepDef[]
  edges: DagEdgeDef[]
  /** 工作流级参数（键是用户填的参数名） */
  workflowParams: Record<string, unknown>[]
  canvasWidth: number
  canvasHeight: number
}

/** 版本详情（GET /workflow-versions/{versionId}）—— 画布读取入口。 */
export interface WorkflowVersionItem {
  /** WFV-<工作流数字段>-<两位序号> */
  versionId: string
  /** WF-#### */
  workflowId: string
  workflowName: string
  versionNo: string
  /** DRAFT / PUBLISHED / ARCHIVED */
  publishStatus: string
  stepCount: number
  steps: DagStepDef[]
  edges: DagEdgeDef[]
  workflowParams: Record<string, unknown>[]
  canvasWidth: number
  canvasHeight: number
  publisher: string | null
  publishedAt: string | null
  createdAt: string
  updatedAt: string
  hasDraftChanges: boolean
}

/** 触发器（GET /triggers?workflowId=xxx、GET /triggers/{id}）。 */
export interface TriggerItem {
  triggerId: string
  /** WF-xxxx */
  workflowId: string
  triggerName: string
  /** MANUAL / CRON */
  triggerType: string
  /** Spring 6 段方言（秒 分 时 日 月 周）—— 见 README-M3 O-31 */
  cronExpression: string | null
  periodSeconds: number | null
  timezone: string | null
  /** ISO-8601 */
  effectiveStart: string | null
  effectiveEnd: string | null
  enabled: boolean
  /** 触发时参数（键是用户填的参数名） */
  runParams: Record<string, unknown> | null
  catchUpEnabled: boolean | null
  catchUpMaxTimes: number | null
  /** 调度器扫表游标：只在调度配置变化时重算 */
  nextFireTime: string | null
  lastFireTime: string | null
  lastFireStatus: string | null
  createdAt: string
  updatedAt: string
}

/**
 * 新建/更新触发器。
 *
 * `cronExpression` 与 `periodSeconds` **二选一**：同时给或都不给都会得到 42216。
 */
export interface SaveTriggerParams {
  /** WF-xxxx（仅创建时必填；更新时挂靠不可变更） */
  workflowId?: string
  triggerName: string
  /** MANUAL / CRON */
  triggerType: string
  cronExpression?: string
  periodSeconds?: number
  timezone?: string
  effectiveStart?: string
  effectiveEnd?: string
  runParams?: Record<string, unknown>
  enabled?: boolean
  catchUpEnabled?: boolean
  catchUpMaxTimes?: number
}
