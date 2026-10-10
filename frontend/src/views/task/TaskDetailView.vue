<script setup lang="ts">
/**
 * 任务详情（M1 最小闭环 + M4 升级：诊断面板 + 人工干预）。
 *
 * 【本页演示的机制】
 * - route.params：动态路由段（/tasks/:taskId → route.params.taskId）；
 * - watch + 3s 轮询：任务/步骤状态刷新，终态自动停止（docs/04 §4.7 轮询策略的最简形态）；
 * - useTaskLogStream：WebSocket 日志流 + offset 续传（本页只负责"选中哪一步"，流逻辑全在 composable）；
 * - StatusTag kind="STEP"：步骤 11 态的 tone/marker 查表渲染（F-5：标签颜色只来自映射表）；
 * - 【M4-S1b】诊断面板：排队位置 / ETA / 阻塞原因 / 建议（GET /tasks/{id}/diagnosis）——
 *   与任务状态同轮询刷新；eta.basis 后端已带"数据不足"解释，前端原样展示不自算；
 * - 【M4-S2】干预动作：stop（STOPPING 中间态语义，O-49）/ retry（新建实例）/
 *   rerun-failed（原实例续跑）/ enqueue-front（仅 PENDING，互斥组拒绝）。
 */
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { taskApi } from '@/api/modules/task'
import type { TaskDetail, TaskDiagnosis, TaskStepItem } from '@/api/types/task'
import { useTaskLogStream } from '@/composables/useTaskLogStream'
import { usePermission } from '@/composables/usePermission'
import { PERM } from '@/constants/permissions'
import StatusTag from '@/components/biz/StatusTag.vue'
import { formatDuration } from '@/utils/format'

const { can } = usePermission()
const route = useRoute()
const taskId = ref(String(route.params.taskId ?? ''))

const task = ref<TaskDetail | null>(null)
const steps = ref<TaskStepItem[]>([])
const selectedRowId = ref<number | null>(null)
const diagnosis = ref<TaskDiagnosis | null>(null)

const TERMINAL = ['SUCCESS', 'FAILED', 'STOPPED', 'TIMEOUT', 'PARTIAL']
const isTerminal = computed(() => !!task.value && TERMINAL.includes(task.value.status))

/** 日志通道以 task_step.id（rowId）定位 —— 行 id 由接口显式下发，前端不做任何推导。 */
function rowIdOf(step: TaskStepItem): number {
  return step.rowId
}

async function refresh() {
  task.value = await taskApi.get(taskId.value)
  steps.value = await taskApi.steps(taskId.value)
  if (selectedRowId.value === null && steps.value.length > 0) {
    selectedRowId.value = rowIdOf(steps.value[0])
  }
  // 诊断与状态同源刷新；失败自吞（诊断挂了不能拖垮详情页）
  try {
    diagnosis.value = await taskApi.diagnosis(taskId.value)
  } catch {
    diagnosis.value = null
  }
}

watch(taskId, refresh, { immediate: true })

// 状态轮询：3s（docs/04 §4.7），终态停止；组件卸载清理定时器
const pollTimer = window.setInterval(() => {
  if (!isTerminal.value) {
    refresh()
  }
}, 3000)
onUnmounted(() => window.clearInterval(pollTimer))

const { lines, connected, switchTo } = useTaskLogStream(taskId, selectedRowId)

function selectStep(step: TaskStepItem) {
  switchTo(rowIdOf(step))
}

function fmtDuration(ms: number | null): string {
  if (ms == null) return '—'
  return formatDuration(ms)
}

// ── 人工干预（M4-S2；动作后立即 refresh 让面板收敛到新状态）──────
async function stopTask() {
  const { value } = await ElMessageBox.prompt(
    '停止后调度器会终止全部运行中步骤，任务收敛为 STOPPED。请填写停止原因（至少 5 个字）：',
    '停止任务',
    {
      inputValidator: (v: string) => (v?.trim().length >= 5 ? true : '停止原因需至少 5 个字符'),
      confirmButtonText: '确认停止',
      cancelButtonText: '取消',
      type: 'warning',
    },
  )
  await taskApi.stop(taskId.value, value.trim())
  ElMessage.success('停止指令已受理（状态收敛需要一点时间）')
  await refresh()
}

async function retryTask() {
  await ElMessageBox.confirm(
    '整任务重跑会「新建一个任务实例」（原任务保持终态、日志可追溯）。确认重跑？',
    '整任务重跑',
    { type: 'warning', confirmButtonText: '重跑', cancelButtonText: '取消' },
  )
  const detail = await taskApi.retry(taskId.value)
  ElMessage.success(`已创建新任务实例 ${detail.taskId}`)
}

async function rerunFailed() {
  await ElMessageBox.confirm(
    '重跑失败步骤将重置 FAILED/TIMEOUT 步骤及其下游闭包（原实例续跑，历史日志保留）。确认重跑失败步骤？',
    '重跑失败步骤',
    { type: 'warning', confirmButtonText: '重跑失败步骤', cancelButtonText: '取消' },
  )
  await taskApi.rerunFailed(taskId.value)
  ElMessage.success('重跑已受理')
  await refresh()
}

async function jumpQueue() {
  await ElMessageBox.confirm(
    '把该任务的优先级临时提到最高（E-07 上限）。互斥组任务不允许插队（42200）。确认插队？',
    '插队',
    { type: 'warning', confirmButtonText: '插队', cancelButtonText: '取消' },
  )
  await taskApi.enqueueFront(taskId.value)
  ElMessage.success('插队已受理')
  await refresh()
}
</script>

<template>
  <div>
    <div class="page-header">
      <h1>任务 {{ task?.taskId ?? taskId }}</h1>
      <div class="sub">
        <StatusTag v-if="task" :status="(task.status as never)" kind="TASK" />
        <span v-if="task">{{ task.workflowName }} · {{ task.workflowVersion }}</span>
        <span v-if="task" class="mono">提交人 {{ task.submitter }} · 优先级 {{ task.priority }}</span>
      </div>
    </div>

    <div class="panel" style="padding: 12px 16px; margin-bottom: 14px">
      <span class="meta-item">步骤进度 <b class="mono">{{ task?.finishedSteps ?? 0 }}/{{ task?.stepTotal ?? 0 }}</b></span>
      <span class="meta-item">开始 <b class="mono">{{ task?.startTime ?? '—' }}</b></span>
      <span class="meta-item">耗时 <b class="mono">{{ fmtDuration(task?.durationMs ?? null) }}</b></span>
      <span v-if="task?.failReason" class="meta-item fail-reason">失败原因 <b>{{ task.failReason }}</b></span>
      <div class="actions" style="float: right">
        <el-button
          v-if="can(PERM.TASK_STOP) && task && !isTerminal && task.status !== 'STOPPING'"
          type="warning"
          size="small"
          @click="stopTask"
        >
          停止
        </el-button>
        <el-button
          v-if="can(PERM.TASK_RETRY) && task && isTerminal"
          size="small"
          @click="retryTask"
        >
          整任务重跑
        </el-button>
        <el-button
          v-if="can(PERM.TASK_RETRY) && task && (task.status === 'FAILED' || task.status === 'TIMEOUT' || task.status === 'PARTIAL')"
          size="small"
          @click="rerunFailed"
        >
          重跑失败步骤
        </el-button>
        <el-button
          v-if="can(PERM.TASK_ENQUEUE_FRONT) && task && task.status === 'PENDING'"
          size="small"
          @click="jumpQueue"
        >
          插队
        </el-button>
      </div>
    </div>

    <!-- 调度诊断面板（M4-S1b，CONTRACT §7）：与任务状态同轮询 -->
    <div v-if="diagnosis" class="panel" style="margin-bottom: 14px; padding: 12px 16px">
      <div class="panel-h" style="padding: 0 0 8px">
        <b>调度诊断</b>
        <span v-if="diagnosis.queuePosition !== null" class="diag-item mono">
          排队位置 {{ diagnosis.queuePosition }}（前方 {{ diagnosis.aheadCount ?? '—' }} 个）
        </span>
      </div>
      <div v-if="diagnosis.eta" class="diag-item">
        预计等待：<b class="mono">{{ diagnosis.eta.estimatedSeconds !== null ? formatDuration(diagnosis.eta.estimatedSeconds * 1000) : '—' }}</b>
        <span class="diag-basis">{{ diagnosis.eta.basis }}（样本 {{ diagnosis.eta.sampleCount }}）</span>
      </div>
      <div v-for="(reason, i) in diagnosis.blockingReasons" :key="i" class="diag-item">
        <span class="diag-type mono">{{ reason.type }}</span>
        {{ reason.desc }}
        <span v-if="reason.holderDesc" class="diag-basis">持有者：{{ reason.holderDesc }}</span>
      </div>
      <div v-for="(sg, i) in diagnosis.suggestions" :key="'s' + i" class="diag-item diag-suggest">
        → {{ sg.text }}
      </div>
      <div
        v-if="diagnosis.queuePosition === null && diagnosis.blockingReasons.length === 0 && !diagnosis.eta"
        class="diag-basis"
      >
        当前没有排队或资源阻塞信息
      </div>
    </div>

    <div class="detail-grid">
      <!-- 步骤列表（DAG 只读的 M1 形态：按 step_index 表达依赖顺序；画布随 M4） -->
      <div class="panel">
        <div class="panel-h"><b>步骤（依赖顺序）</b></div>
        <div
          v-for="step in steps"
          :key="step.stepInstanceId"
          class="step-row"
          :class="{ active: selectedRowId === rowIdOf(step) }"
          @click="selectStep(step)"
        >
          <span class="mono step-idx">{{ step.stepIndex + 1 }}</span>
          <StatusTag :status="(step.status as never)" kind="STEP" />
          <span class="step-name">{{ step.stepName }}</span>
          <span v-if="step.failReason" class="step-fail" :title="step.failReason">{{ step.failReason }}</span>
          <span class="mono step-dur">{{ fmtDuration(step.durationMs) }}</span>
          <span v-if="step.retryCount > 0" class="mono step-retry">重试 {{ step.retryCount }}</span>
        </div>
        <div v-if="steps.length === 0" class="empty-hint">暂无步骤实例</div>
      </div>

      <!-- 日志窗口：WebSocket 实时流 + offset 续传 -->
      <div class="panel log-panel">
        <div class="panel-h">
          <b>实时日志</b>
          <span class="mono" :style="{ color: connected ? 'var(--ok)' : 'var(--t3)' }">
            {{ connected ? '● 已连接' : '○ 未连接' }}
          </span>
        </div>
        <div class="log-body">
          <div v-for="line in lines" :key="line.seq" class="log-line" :class="line.stream">
            <span class="mono log-seq">{{ line.seq }}</span>
            <span class="log-content">{{ line.content }}</span>
          </div>
          <div v-if="lines.length === 0" class="empty-hint">
            {{ connected ? '等待输出…' : '日志通道未连接（任务运行后自动恢复）' }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mono {
  font-family: var(--mono);
  font-size: 11.5px;
  color: var(--t2);
}

.meta-item {
  margin-right: 22px;
  font-size: 12px;
  color: var(--t3);
}

.meta-item b {
  color: var(--t1);
}

/* 原型踩坑预防（docs/04 §8 F-7/F-8）：固定高父级内滚动容器必须 min-height:0 */
.detail-grid {
  display: grid;
  grid-template-columns: 380px 1fr;
  gap: 14px;
  align-items: start;
}

.step-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 14px;
  border-bottom: 1px solid var(--line-faint);
  cursor: pointer;
}

.step-row:hover {
  background: var(--bg-hover);
}

.step-row.active {
  background: var(--pri-dim);
  box-shadow: inset 2px 0 0 var(--pri);
}

.step-idx {
  color: var(--t3);
}

.step-name {
  font-size: 12.5px;
  color: var(--t1);
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.step-retry {
  color: var(--warn);
}

.step-fail {
  font-size: 11.5px;
  color: var(--fail);
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fail-reason b {
  color: var(--fail);
}

.diag-item {
  font-size: 12px;
  color: var(--t2);
  line-height: 1.8;
}

.diag-type {
  color: var(--warn);
  margin-right: 6px;
}

.diag-basis {
  color: var(--t3);
  margin-left: 8px;
  font-size: 11.5px;
}

.diag-suggest {
  color: var(--t1);
}

.diag-item.mono {
  margin-left: 14px;
}

.log-panel {
  display: flex;
  flex-direction: column;
  max-height: 70vh;
}

.log-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  background: var(--bg-inset);
  padding: 10px 0;
  font-family: var(--mono);
  font-size: 11.5px;
}

.log-line {
  display: flex;
  gap: 10px;
  padding: 1px 14px;
  line-height: 1.6;
}

.log-seq {
  color: var(--t4);
  flex: 0 0 44px;
  text-align: right;
  user-select: none;
}

.log-content {
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--t2);
}

.log-line.ERR .log-content {
  color: var(--fail);
}

.empty-hint {
  padding: 26px;
  text-align: center;
  color: var(--t3);
  font-size: 12px;
}
</style>
