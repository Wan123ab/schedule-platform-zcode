/**
 * 枚举与状态语义（docs/04 §2.4：tone + marker 双区分，前端唯一状态渲染口径）。
 * 任务 9 态 / 步骤 11 态必须全覆盖 —— Record 类型缺一项即 vue-tsc 编译失败（CI 阻塞项）。
 */
export type Tone = 'idle' | 'info' | 'ok' | 'warn' | 'fail' | 'mut'
export type Marker = 'dot' | 'pulse' | 'spin' | 'check' | 'cross' | 'clock' | 'square'

export type TaskStatus =
  | 'PENDING'
  | 'SCHEDULING'
  | 'RUNNING'
  | 'STOPPING'
  | 'SUCCESS'
  | 'FAILED'
  | 'STOPPED'
  | 'TIMEOUT'
  | 'PARTIAL'

export const TASK_STATUS_LABEL: Record<TaskStatus, string> = {
  PENDING: '等待中',
  SCHEDULING: '调度中',
  RUNNING: '运行中',
  STOPPING: '停止中',
  SUCCESS: '成功',
  FAILED: '失败',
  STOPPED: '已停止',
  TIMEOUT: '超时',
  PARTIAL: '部分成功',
}

export const TASK_STATUS_TONE: Record<TaskStatus, Tone> = {
  PENDING: 'idle',
  SCHEDULING: 'info',
  RUNNING: 'info',
  STOPPING: 'warn', // v3：停止中 ≠ 调度中，warn 提示"正在收敛"
  SUCCESS: 'ok',
  FAILED: 'fail',
  TIMEOUT: 'warn',
  STOPPED: 'idle',
  PARTIAL: 'warn',
}

export const TASK_STATUS_MARKER: Record<TaskStatus, Marker> = {
  PENDING: 'dot',
  SCHEDULING: 'pulse',
  RUNNING: 'spin',
  STOPPING: 'pulse',
  SUCCESS: 'check',
  FAILED: 'cross',
  TIMEOUT: 'clock',
  STOPPED: 'square',
  PARTIAL: 'dot',
}

export type StepStatus =
  | 'NOT_STARTED'
  | 'WAITING_DEPENDENCY'
  | 'WAITING_RESOURCE'
  | 'SCHEDULING'
  | 'RUNNING'
  | 'SUCCESS'
  | 'FAILED'
  | 'RETRYING'
  | 'SKIPPED'
  | 'STOPPED'
  | 'TIMEOUT'

export const STEP_STATUS_LABEL: Record<StepStatus, string> = {
  NOT_STARTED: '未开始',
  WAITING_DEPENDENCY: '等待依赖',
  WAITING_RESOURCE: '等待资源',
  SCHEDULING: '调度中',
  RUNNING: '运行中',
  SUCCESS: '成功',
  FAILED: '失败',
  RETRYING: '重试中',
  SKIPPED: '已跳过',
  STOPPED: '已停止',
  TIMEOUT: '超时',
}

/**
 * 步骤 11 态的 tone/marker（docs/04 §2.4：任务 9 态语义基础上补充）。
 * SKIPPED 一期不产生但映射必须存在 —— Record 全覆盖在 strict 下缺一项即编译失败（防漏态）。
 */
export const STEP_STATUS_TONE: Record<StepStatus, Tone> = {
  NOT_STARTED: 'idle',
  WAITING_DEPENDENCY: 'info',
  WAITING_RESOURCE: 'warn',
  SCHEDULING: 'info',
  RUNNING: 'info',
  SUCCESS: 'ok',
  FAILED: 'fail',
  RETRYING: 'warn',
  SKIPPED: 'idle',
  STOPPED: 'idle',
  TIMEOUT: 'warn',
}

export const STEP_STATUS_MARKER: Record<StepStatus, Marker> = {
  NOT_STARTED: 'dot',
  WAITING_DEPENDENCY: 'clock',
  WAITING_RESOURCE: 'dot',
  SCHEDULING: 'pulse',
  RUNNING: 'spin',
  SUCCESS: 'check',
  FAILED: 'cross',
  RETRYING: 'spin', // warn + spin：'还在动但在告警色系'（重试 ≠ 正常运行）
  SKIPPED: 'square',
  STOPPED: 'square',
  TIMEOUT: 'clock',
}

// ─────────────────────────────────────────────────────────────
// 资产域枚举（M2 新增：集群 / 节点 / 队列 / 凭据）
//
// 与任务/步骤保持同一套纪律（docs/04 §2.4 / F-5）：
// 文案与颜色只从映射表取，业务代码里禁止出现 `if (status === 'NORMAL')` 决定颜色。
// 用 Record<留出字面量联合类型> 而不是 Record<string, ...>：
// 前者漏一个取值就编译失败，后者永远不报错（也就是永远可能漏）。
// ─────────────────────────────────────────────────────────────

export type ClusterStatus = 'NORMAL' | 'PARTIAL_ABNORMAL' | 'UNAVAILABLE' | 'MAINTENANCE'

export const CLUSTER_STATUS_LABEL: Record<ClusterStatus, string> = {
  NORMAL: '正常',
  PARTIAL_ABNORMAL: '部分异常',
  UNAVAILABLE: '不可用',
  MAINTENANCE: '维护中',
}

export const CLUSTER_STATUS_TONE: Record<ClusterStatus, Tone> = {
  NORMAL: 'ok',
  PARTIAL_ABNORMAL: 'warn',
  UNAVAILABLE: 'fail',
  MAINTENANCE: 'info', // 维护是"人为正常状态"，不是故障 —— 用信息色而非告警色
}

/** 节点在线状态（心跳链路推导，接口只读）。 */
export type NodeOnlineStatus = 'ONLINE' | 'OFFLINE' | 'UNKNOWN'

export const NODE_ONLINE_LABEL: Record<NodeOnlineStatus, string> = {
  ONLINE: '在线',
  OFFLINE: '离线',
  UNKNOWN: '未上报',
}

export const NODE_ONLINE_TONE: Record<NodeOnlineStatus, Tone> = {
  ONLINE: 'ok',
  OFFLINE: 'fail',
  // UNKNOWN = 从没收到过心跳（新加机器）——判成"离线"会误报，故用中性色
  UNKNOWN: 'idle',
}

export type QueueStatus = 'ENABLED' | 'DISABLED'

export const QUEUE_STATUS_LABEL: Record<QueueStatus, string> = {
  ENABLED: '启用',
  DISABLED: '停用',
}

export const QUEUE_STATUS_TONE: Record<QueueStatus, Tone> = {
  ENABLED: 'ok',
  DISABLED: 'idle',
}

export type CredentialStatus = 'VALID' | 'EXPIRING' | 'EXPIRED' | 'REVOKED'

export const CREDENTIAL_STATUS_LABEL: Record<CredentialStatus, string> = {
  VALID: '有效',
  EXPIRING: '即将过期',
  EXPIRED: '已过期',
  REVOKED: '已吊销',
}

export const CREDENTIAL_STATUS_TONE: Record<CredentialStatus, Tone> = {
  VALID: 'ok',
  EXPIRING: 'warn',
  EXPIRED: 'fail',
  REVOKED: 'idle',
}

/** 凭据类型（docs/05 §3.3 credential.credential_type；与后端 @Pattern 白名单一致） */
export type CredentialType = 'SSH_KEY' | 'USER_PASSWORD' | 'WINRM' | 'TOKEN'

export const CREDENTIAL_TYPE_LABEL: Record<CredentialType, string> = {
  SSH_KEY: 'SSH 密钥',
  USER_PASSWORD: '用户名密码',
  WINRM: 'WinRM',
  TOKEN: 'Token',
}

// ═════════════════════════════════════════════════════════════
// 算子域（docs/05 §3.4；M3）
// ═════════════════════════════════════════════════════════════

/** 算子启停状态（operator.status）。 */
export type OperatorStatus = 'ENABLED' | 'DISABLED'

export const OPERATOR_STATUS_LABEL: Record<OperatorStatus, string> = {
  ENABLED: '启用',
  DISABLED: '停用',
}

export const OPERATOR_STATUS_TONE: Record<OperatorStatus, Tone> = {
  ENABLED: 'ok',
  DISABLED: 'idle',
}

/** 算子类型（与后端 @Pattern 白名单、DDL CHECK 一致）。 */
export type OperatorType = 'JAR' | 'PYTHON' | 'SHELL' | 'BAT' | 'EXE' | 'CUSTOM'

export const OPERATOR_TYPE_LABEL: Record<OperatorType, string> = {
  JAR: 'JAR',
  PYTHON: 'Python',
  SHELL: 'Shell',
  BAT: 'Bat',
  EXE: 'Exe',
  CUSTOM: '自定义',
}

/**
 * 版本发布状态（operator_version.publish_status）。
 *
 * 注意与工作流版本的差别：这里有 `OFFLINE`，因为算子版本可以"主动下线"
 * （下线后新步骤不能再引用它，但已引用它的历史步骤不受影响）。
 */
export type VersionPublishStatus = 'DRAFT' | 'PUBLISHED' | 'OFFLINE'

export const VERSION_PUBLISH_STATUS_LABEL: Record<VersionPublishStatus, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  OFFLINE: '已下线',
}

export const VERSION_PUBLISH_STATUS_TONE: Record<VersionPublishStatus, Tone> = {
  DRAFT: 'idle',
  PUBLISHED: 'ok',
  OFFLINE: 'mut',
}

/** 参数类型（operator_param_def.param_type）。 */
export type ParamType = 'TEXT' | 'NUMBER' | 'BOOLEAN' | 'SINGLE' | 'DATETIME'

export const PARAM_TYPE_LABEL: Record<ParamType, string> = {
  TEXT: '文本',
  NUMBER: '数字',
  BOOLEAN: '布尔',
  SINGLE: '单选',
  DATETIME: '日期时间',
}

/** 输出提取方式（operator_output_decl.extract_mode）。 */
export type ExtractMode = 'REGEX' | 'FILE'

export const EXTRACT_MODE_LABEL: Record<ExtractMode, string> = {
  REGEX: '正则提取',
  FILE: '文件读取',
}

/** 操作系统（Q-02：一期 Windows 仅登记展示，试运行只支持 LINUX）。 */
export type OsType = 'LINUX' | 'WINDOWS'

export const OS_TYPE_LABEL: Record<OsType, string> = {
  LINUX: 'Linux',
  WINDOWS: 'Windows',
}

// ═════════════════════════════════════════════════════════════
// 工作流域（docs/05 §3.4；M3）
// ═════════════════════════════════════════════════════════════

/** 工作流状态（workflow.status）。 */
export type WorkflowStatus = 'DRAFT' | 'PUBLISHED' | 'DISABLED' | 'ARCHIVED'

export const WORKFLOW_STATUS_LABEL: Record<WorkflowStatus, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  DISABLED: '已停用',
  ARCHIVED: '已归档',
}

export const WORKFLOW_STATUS_TONE: Record<WorkflowStatus, Tone> = {
  DRAFT: 'idle',
  PUBLISHED: 'ok',
  DISABLED: 'warn',
  ARCHIVED: 'mut',
}

/** 工作流版本状态（workflow_version.publish_status）—— 比算子少一个 OFFLINE。 */
export type WorkflowVersionStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'

export const WORKFLOW_VERSION_STATUS_LABEL: Record<WorkflowVersionStatus, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  ARCHIVED: '已归档',
}

export const WORKFLOW_VERSION_STATUS_TONE: Record<WorkflowVersionStatus, Tone> = {
  DRAFT: 'idle',
  PUBLISHED: 'ok',
  ARCHIVED: 'mut',
}

/** 并发策略（workflow.concurrency_policy，PRD §10.4）。 */
export type ConcurrencyPolicy = 'FORBID' | 'ALLOW' | 'QUEUE'

export const CONCURRENCY_POLICY_LABEL: Record<ConcurrencyPolicy, string> = {
  FORBID: '禁止并发',
  ALLOW: '允许并发',
  QUEUE: '排队等待',
}

/** 画布节点类型（workflow_step.step_type，PRD §10.8 规则 1"备注除外"）。 */
export type StepType = 'TASK' | 'NOTE'

export const STEP_TYPE_LABEL: Record<StepType, string> = {
  TASK: '任务',
  NOTE: '备注',
}

/** 失败策略（workflow_step.failure_strategy）。 */
export type FailureStrategy = 'TERMINATE' | 'RETRY'

export const FAILURE_STRATEGY_LABEL: Record<FailureStrategy, string> = {
  TERMINATE: '终止',
  RETRY: '重试',
}

/** 触发器类型（一期只放 CRON，MANUAL 由"手动执行"入口承担）。 */
export type TriggerType = 'MANUAL' | 'CRON'

export const TRIGGER_TYPE_LABEL: Record<TriggerType, string> = {
  MANUAL: '手动',
  CRON: '定时（Cron）',
}
