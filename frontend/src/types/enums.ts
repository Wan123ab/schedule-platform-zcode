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
