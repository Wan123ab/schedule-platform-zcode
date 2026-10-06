<script setup lang="ts">
/**
 * 任务详情（M1 最小闭环的验收页面，docs/09 M1 交付物："一个任务详情页（DAG 只读 + 日志窗口）"）。
 *
 * 【本页演示的机制】
 * - route.params：动态路由段（/tasks/:taskId → route.params.taskId）；
 * - watch + 3s 轮询：任务/步骤状态刷新，终态自动停止（docs/04 §4.7 轮询策略的最简形态）；
 * - useTaskLogStream：WebSocket 日志流 + offset 续传（本页只负责"选中哪一步"，流逻辑全在 composable）；
 * - StatusTag kind="STEP"：步骤 11 态的 tone/marker 查表渲染（F-5：标签颜色只来自映射表）。
 *
 * 【与 M4 的边界】DAG 画布着色、诊断面板、重跑干预是 M4 交付 —— 本页用步骤列表
 * 表达依赖顺序（按 step_index），交互升级不改数据面（api/types/task.ts 已定型）。
 */
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { taskApi } from '@/api/modules/task'
import type { TaskDetail, TaskStepItem } from '@/api/types/task'
import { useTaskLogStream } from '@/composables/useTaskLogStream'
import StatusTag from '@/components/biz/StatusTag.vue'

const route = useRoute()
const taskId = ref(String(route.params.taskId ?? ''))

const task = ref<TaskDetail | null>(null)
const steps = ref<TaskStepItem[]>([])
const selectedRowId = ref<number | null>(null)

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
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`
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
