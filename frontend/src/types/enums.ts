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
