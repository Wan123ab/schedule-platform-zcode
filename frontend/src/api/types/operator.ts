/**
 * 算子域类型（docs/07 §8.2；字段口径 docs/05 §3.4）。
 *
 * 【命名说明（D-15）】
 * 这里全部是 camelCase —— snake_case 只存在于网络边界一层（api/http.ts 拦截器
 * 出口 deepSnake / 入口 deepCamel）。写类型时不需要考虑 `operator_id` 这种写法。
 *
 * 【出网一律业务编号（D-27）】
 * `operatorId` 是 `OP-0042`、`versionId` 是 `OPV-0042-01`、`projectId` 是 `PRJ-0007`
 * —— 内部 bigint 主键**从不出网**，服务端在 Service 层翻译好。
 * 所以类型上一律 `string`，不要写成 `number`。
 */

/** 算子（GET /operators、GET /operators/{operatorId}）。 */
export interface OperatorItem {
  /** 业务编号 OP-#### */
  operatorId: string
  operatorName: string
  /** JAR / PYTHON / SHELL / BAT / EXE / CUSTOM */
  operatorType: string
  /** 业务编号 PRJ-xxxx */
  projectId: string
  projectName: string
  description: string | null
  /** ENABLED / DISABLED */
  status: string
  /** 最新版本号（v3 这种），无版本时为 null */
  latestVersion: string | null
  versionCount: number
  creator: string
  createdAt: string
  updatedAt: string
}

export interface SaveOperatorParams {
  operatorName: string
  operatorType: string
  /** 业务编号 PRJ-xxxx */
  projectId: string
  description?: string
  /** 仅编辑时可改（新建强制 ENABLED） */
  status?: string
}

// ── 版本里的嵌套结构（OperatorVersionParts）────────────────────

/** 环境变量。`secret=true` 的值在出网前被后端脱敏。 */
export interface EnvVar {
  key: string
  value: string
  secret: boolean
}

/** 版本级默认资源。memory/disk 单位 MB。 */
export interface DefaultResource {
  cpu: number | null
  gpu: number | null
  memory: number | null
  disk: number | null
}

/**
 * 参数模板条目 —— 它描述"**有哪些参数**"，不描述"值是多少"。
 *
 * 默认值继承链的第 4 层由 `defaultValue` 承载（PRD §12.5：步骤参数 > 算子版本默认值），
 * 工作流编辑器据此预填输入框；`PUT /operator-versions/{id}/param-defaults` 更新的是它。
 * （敏感参数不允许走这条路 —— 默认值明文出网，见 README-M3 O-35。）
 */
export interface ParamDef {
  name: string
  /** 唯一键；试运行与步骤参数都用它作为 Map 的 key */
  paramKey: string
  /** TEXT / NUMBER / BOOLEAN / SINGLE / DATETIME */
  paramType: string
  required: boolean
  defaultValue: string | null
  /** 校验规则（正则或区间），一期由后端按 paramType 解释 */
  rule: string | null
  help: string | null
  /** false = 步骤上不允许覆盖（如算子固有的输入路径） */
  runtimeOverridable: boolean
  /** true = 快照/日志/命令回显一律打码，真实值只留给执行 */
  sensitive: boolean
  /** 仅 SINGLE 有值 */
  options: string[] | null
  seq: number
}

/** 输出声明：上游步骤引用 `${step.X.output.varName}` 的来源。 */
export interface OutputDecl {
  varName: string
  /** REGEX / FILE */
  extractMode: string
  expression: string | null
  valueType: string | null
  exampleValue: string | null
  description: string | null
  required: boolean
  seq: number
}

export interface OperatorVersionItem {
  /** 业务编号 OPV-####-## */
  versionId: string
  /** 业务编号 OP-#### */
  operatorId: string
  operatorName: string
  /** v1 / v2 */
  versionNo: string
  description: string | null
  fileName: string | null
  fileSize: number | null
  /** SHA-256 */
  fileChecksum: string | null
  /** LINUX / WINDOWS */
  osType: string
  startCommand: string | null
  workDir: string | null
  envVars: EnvVar[] | null
  /** 退出码落在其中才算成功；缺省 [0]（PRD §12.4-5） */
  successCodes: number[] | null
  defaultTimeoutSeconds: number | null
  defaultRetryCount: number | null
  defaultRetryIntervalSeconds: number | null
  defaultResource: DefaultResource | null
  logTailLines: number | null
  logMaxBytes: number | null
  /** DRAFT / PUBLISHED / OFFLINE */
  publishStatus: string
  isDefaultVersion: boolean
  publisher: string | null
  publishedAt: string | null
  createdAt: string
  paramTemplate: ParamDef[] | null
  outputDeclarations: OutputDecl[] | null
}

/**
 * 版本上传/编辑的 meta（multipart 里作为**一段 JSON 字符串**提交，不是独立字段）。
 *
 * 上传是 `file` + `meta` 两个 part；编辑草稿只发 `meta`（不换文件，D-11 版本不可变）。
 */
export interface OperatorVersionMeta {
  description?: string
  osType?: string
  startCommand?: string
  workDir?: string
  envVars?: EnvVar[]
  successCodes?: number[]
  paramTemplate?: Omit<ParamDef, 'seq'>[] | ParamDef[]
  outputDeclarations?: Omit<OutputDecl, 'seq'>[] | OutputDecl[]
  defaultResource?: DefaultResource
  defaultTimeoutSeconds?: number
  defaultRetryCount?: number
  defaultRetryIntervalSeconds?: number
  logTailLines?: number
  logMaxBytes?: number
}

/** 引用查询：这个版本被哪些工作流的哪个步骤用了（42211 删除闸门的依据）。 */
export interface OperatorReferenceItem {
  /** 业务编号 WF-#### */
  workflowId: string
  workflowName: string
  versionNo: string
  /** DRAFT / PUBLISHED / OFFLINE */
  publishStatus: string
  stepName: string
}

/** PUT /operator-versions/{versionId}/param-defaults 的请求体。 */
export interface SaveDefaultParamsParams {
  /** key = operator_param_def.param_key；**空串表示清除该默认值** */
  params: Record<string, string>
}

// ── 试运行（POST /operator-versions/{versionId}/dry-run，SSE）──────

/**
 * 试运行请求。
 *
 * 注意 `params` 的值**保留原类型**（NUMBER 传数字、BOOLEAN 传布尔）——
 * 后端刻意不做字符串化，这样整串单引用才能透传原类型。
 */
export interface DryRunParams {
  /** 业务编号 EN-#### */
  executorNodeId: string
  params: Record<string, unknown>
  /** 空则用版本默认值，再退化为平台上限 600 */
  timeoutSeconds?: number
}

/**
 * SSE 帧（复用 docs/07 §7.5 的词汇表，线协议 snake_case 由拦截器转换）。
 *
 * 这里**不走 axios**（拦截器只管 JSON 请求），所以帧的字段名要在 composable 里
 * 手工 camel 化 —— 见 `composables/useDryRunStream.ts` 的 `normalizeFrame`。
 */
export type DryRunFrameType = 'CMD' | 'LOG' | 'EOF' | 'ERROR'

/** 第一帧：把"将要执行什么"摊开给用户看（命令已脱敏）。 */
export interface DryRunCmdFrame {
  type: 'CMD'
  dryRunId: string
  nodeId: string
  nodeName: string
  machineIp: string
  /** **脱敏后**的完整命令 */
  command: string
  timeoutSeconds: number
  successCodes: number[]
  params: Record<string, unknown>
  /** 参数名 → 来源层（如 "步骤参数"），排障溯源用（PRD §10.0.4） */
  sources: Record<string, string>
}

/** 日志行：与任务日志同构（{seq, stream, content}）。 */
export interface DryRunLogFrame {
  type: 'LOG'
  seq: number
  /** stdout / stderr */
  stream: string
  content: string
}

/**
 * 结束帧。
 *
 * `exitCode === null` 是**有语义的**取值：表示"进程未能确认启动"
 * （连接/认证失败、超时强杀），恒判失败，提示方向应是"机器是否可达"
 * 而不是"命令是不是写错了"（见 README-M3 §1.4 第 5 条）。
 */
export interface DryRunEofFrame {
  type: 'EOF'
  exitCode: number | null
  durationMs: number | null
  success: boolean
  failReason: string | null
}

/** 建流**之后**的错误（并发已满 42900、执行期异常）。建流之前的错误走普通 JSON。 */
export interface DryRunErrorFrame {
  type: 'ERROR'
  code: number
  message: string
}
