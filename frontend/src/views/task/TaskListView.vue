<script setup lang="ts">
/**
 * 任务列表（M4-S1a/S2 实装；原型 prototype/task-list.html，PRD §10.5）。
 *
 * 【本页的机制】
 * - useListQuery：列表状态机复用（与集群列表同一套）；
 * - **查询串键名原样驼峰**（`bizDateFrom`/`triggerType`）：拦截器不转换 params，
 *   写错只报 40001 且不提示命名问题（tests/m4Contract.spec.ts 文本断言钉住）；
 * - 干预动作按权限点显隐 + 终态收敛前禁用：stop 仅非终态可用（42233 由后端兜底，
 *   前端置灰只是体验，不是安全边界）；retry = 新建实例，文案必须说明这一点。
 */
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { taskApi } from '@/api/modules/task'
import type { TaskListItem } from '@/api/types/task'
import ToneChip from '@/components/biz/ToneChip.vue'
import { useListQuery } from '@/composables/useListQuery'
import { usePermission } from '@/composables/usePermission'
import { PERM } from '@/constants/permissions'
import {
  TASK_STATUS_LABEL,
  TASK_STATUS_TONE,
  type TaskStatus,
  type Tone,
} from '@/types/enums'
import { dash, formatDateTime, formatDuration } from '@/utils/format'

const { can } = usePermission()

const statusFilter = ref('')
const triggerFilter = ref('')
const bizDateFrom = ref('')
const bizDateTo = ref('')

const { items, total, page, pageSize, keyword, loading, refresh } = useListQuery<TaskListItem>(
  (params) =>
    taskApi.page({
      ...params,
      status: statusFilter.value || undefined,
      triggerType: triggerFilter.value || undefined,
      bizDateFrom: bizDateFrom.value || undefined,
      bizDateTo: bizDateTo.value || undefined,
    }),
)

onMounted(refresh)

// ── 状态查表（F-5：颜色只来自映射表）──────────────────────────
const statusLabel = (status: string): string => TASK_STATUS_LABEL[status as TaskStatus] ?? status
const statusTone = (status: string): Tone => TASK_STATUS_TONE[status as TaskStatus] ?? 'idle'

const TERMINAL = ['SUCCESS', 'FAILED', 'STOPPED', 'TIMEOUT', 'PARTIAL']
const isTerminal = (status: string): boolean => TERMINAL.includes(status)

// ── 分页 ─────────────────────────────────────────────────
const totalPages = () => Math.max(1, Math.ceil(total.value / pageSize.value))
function go(target: number) {
  if (target < 1 || target > totalPages()) return
  page.value = target
  refresh()
}

// ── 人工干预（docs/06 §15.1 / §9.3）────────────────────────
async function stopTask(row: TaskListItem) {
  const { value } = await ElMessageBox.prompt(
    `停止后调度器会终止「${row.workflowName}」的全部运行中步骤，任务收敛为 STOPPED。请填写停止原因（至少 5 个字）：`,
    '停止任务',
    {
      inputValidator: (v: string) => (v?.trim().length >= 5 ? true : '停止原因需至少 5 个字符'),
      confirmButtonText: '确认停止',
      cancelButtonText: '取消',
      type: 'warning',
    },
  )
  const detail = await taskApi.stop(row.taskId, value.trim())
  ElMessage.success(`停止指令已受理（当前状态：${statusLabel(detail.status)}，收敛需要一点时间）`)
  refresh()
}

async function retryTask(row: TaskListItem) {
  await ElMessageBox.confirm(
    `整任务重跑会「新建一个任务实例」（原任务保持终态、日志可追溯，docs/06 §9.3）。确认重跑「${row.workflowName}」？`,
    '整任务重跑',
    { type: 'warning', confirmButtonText: '重跑', cancelButtonText: '取消' },
  )
  const detail = await taskApi.retry(row.taskId)
  ElMessage.success(`已创建新任务实例 ${detail.taskId}`)
  refresh()
}

async function jumpQueue(row: TaskListItem) {
  await ElMessageBox.confirm(
    `把「${row.workflowName}」的优先级临时提到最高（E-07 上限）。互斥组任务不允许插队。确认插队？`,
    '插队',
    { type: 'warning', confirmButtonText: '插队', cancelButtonText: '取消' },
  )
  await taskApi.enqueueFront(row.taskId)
  ElMessage.success('插队已受理')
  refresh()
}
</script>

<template>
  <div>
    <div class="page-header">
      <div>
        <h1>任务列表</h1>
        <div class="sub">
          <span>任务实例 · 支持按状态 / 触发方式 / 业务日期筛选（PRD §10.5）</span>
          <span class="mono">共 {{ total }} 条</span>
        </div>
      </div>
      <div class="actions">
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 130px" @change="go(1)">
          <el-option v-for="(label, key) in TASK_STATUS_LABEL" :key="key" :label="label" :value="key" />
        </el-select>
        <el-select v-model="triggerFilter" placeholder="触发方式" clearable style="width: 120px" @change="go(1)">
          <el-option label="手动" value="MANUAL" />
          <el-option label="定时" value="CRON" />
          <el-option label="回填" value="BACKFILL" />
        </el-select>
        <el-date-picker
          v-model="bizDateFrom"
          type="date"
          placeholder="业务日期从"
          value-format="YYYY-MM-DD"
          style="width: 140px"
          @change="go(1)"
        />
        <el-date-picker
          v-model="bizDateTo"
          type="date"
          placeholder="到"
          value-format="YYYY-MM-DD"
          style="width: 120px"
          @change="go(1)"
        />
        <el-input
          v-model="keyword"
          placeholder="搜索任务号 / 工作流"
          style="width: 200px"
          clearable
          @keyup.enter="go(1)"
        />
        <el-button @click="go(1)">查询</el-button>
      </div>
    </div>

    <div class="panel">
      <div class="table-wrap" v-loading="loading">
        <table class="tbl">
          <thead>
            <tr>
              <th>任务</th>
              <th>状态</th>
              <th>工作流</th>
              <th>业务日期</th>
              <th>触发</th>
              <th>进度</th>
              <th>队列</th>
              <th>提交</th>
              <th>耗时</th>
              <th style="width: 200px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in items" :key="row.taskId">
              <td>
                <router-link class="link-id" :to="`/tasks/${row.taskId}`">{{ row.taskId }}</router-link>
              </td>
              <td><ToneChip :tone="statusTone(row.status)" :label="statusLabel(row.status)" /></td>
              <td>
                <div>{{ row.workflowName }}</div>
                <div class="sub mono">{{ row.workflowVersion }}</div>
              </td>
              <td class="mono">{{ dash(row.bizDate) }}</td>
              <td>
                {{ row.triggerType === 'MANUAL' ? '手动' : row.triggerType === 'CRON' ? '定时' : '回填' }}
              </td>
              <td class="mono">{{ row.finishedSteps }}/{{ row.stepTotal }}</td>
              <td>{{ dash(row.queueName) }}</td>
              <td>
                <div>{{ row.submitter }}</div>
                <div class="sub mono">{{ formatDateTime(row.submitAt) }}</div>
              </td>
              <td class="mono">{{ formatDuration(row.durationMs) }}</td>
              <td>
                <div class="actions">
                  <router-link :to="`/tasks/${row.taskId}`">
                    <el-button text size="small">详情</el-button>
                  </router-link>
                  <el-button
                    v-if="can(PERM.TASK_STOP)"
                    text
                    size="small"
                    :disabled="isTerminal(row.status) || row.status === 'STOPPING'"
                    @click="stopTask(row)"
                  >
                    停止
                  </el-button>
                  <el-button
                    v-if="can(PERM.TASK_RETRY)"
                    text
                    size="small"
                    :disabled="!isTerminal(row.status)"
                    @click="retryTask(row)"
                  >
                    重跑
                  </el-button>
                  <el-button
                    v-if="can(PERM.TASK_ENQUEUE_FRONT)"
                    text
                    size="small"
                    :disabled="row.status !== 'PENDING'"
                    @click="jumpQueue(row)"
                  >
                    插队
                  </el-button>
                </div>
              </td>
            </tr>
            <tr v-if="items.length === 0">
              <td colspan="10" class="empty-hint">{{ loading ? '加载中…' : '暂无任务' }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="pager">
        <span>第 {{ page }} / {{ totalPages() }} 页</span>
        <div class="pgs">
          <button :disabled="page <= 1" @click="go(page - 1)">‹</button>
          <button class="on">{{ page }}</button>
          <button :disabled="page >= totalPages()" @click="go(page + 1)">›</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.link-id {
  color: var(--t1);
  font-weight: 600;
  text-decoration: none;
}

.link-id:hover {
  color: var(--pri-hi);
}

.empty-hint {
  text-align: center;
  color: var(--t3);
  padding: 30px;
}
</style>
