<script setup lang="ts">
/**
 * 回填补数（M4-S3 实装；原型 prototype/backfill.html，PRD §10.11）。
 *
 * 【两段式提交】preview 产出计划 + previewHash → 用户确认后 submit 回传
 * confirmedPreviewHash（不符 → 42231「预览已失效，请重新预览」）。
 * 预览就是闸门：conflictCount（撞既有任务）与 policyConflict（并发策略收敛）
 * 都在提交前可见，没有"跳过预览直接提交"的路径。
 *
 * 【批次生命周期】RUNNING ⇄ PAUSED（pause/resume）→ DONE / FAILED；
 * FAILED 批次可 retry-failed（新建任务实例，原 FAILED 行保留审计 →
 * 一个业务日期可能有两行任务，批次详情里如实分开展示）。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { backfillApi } from '@/api/modules/backfill'
import { workflowApi } from '@/api/modules/workflow'
import type { WorkflowItem } from '@/api/types/workflow'
import type { BackfillBatch, BackfillPreview } from '@/api/types/backfill'
import type { TaskListItem } from '@/api/types/task'
import ToneChip from '@/components/biz/ToneChip.vue'
import { useListQuery } from '@/composables/useListQuery'
import { usePermission } from '@/composables/usePermission'
import { PERM } from '@/constants/permissions'
import {
  BACKFILL_STATUS_LABEL,
  BACKFILL_STATUS_TONE,
  type BackfillStatus,
  type Tone,
} from '@/types/enums'
import { dash, formatDateTime, formatDuration } from '@/utils/format'

const { can } = usePermission()
const canWrite = computed(() => can(PERM.BACKFILL_WRITE))

// ── 预览表单 ─────────────────────────────────────────────
const workflows = ref<WorkflowItem[]>([])
const form = ref({ workflowId: '', range: [] as string[], concurrency: 1, sequential: true })
const previewing = ref(false)
const preview = ref<BackfillPreview | null>(null)
const submitting = ref(false)

onMounted(async () => {
  // 下拉源：只列已发布工作流（回填按当前生效版本执行）
  const result = await workflowApi.page({ page: 1, pageSize: 100, status: 'PUBLISHED' })
  workflows.value = result.records
})

const dateRangeError = computed(() => {
  if (form.value.range.length !== 2) return ''
  return form.value.range[0] <= form.value.range[1] ? '' : '开始日期不能晚于结束日期'
})

async function doPreview() {
  if (!form.value.workflowId || form.value.range.length !== 2 || dateRangeError.value) {
    ElMessage.warning('请选择工作流与业务日期范围')
    return
  }
  previewing.value = true
  preview.value = null
  try {
    preview.value = await backfillApi.preview({
      workflowId: form.value.workflowId,
      bizFrom: form.value.range[0],
      bizTo: form.value.range[1],
      concurrency: form.value.concurrency,
      sequential: form.value.sequential,
    })
  } finally {
    previewing.value = false
  }
}

async function doSubmit() {
  if (!preview.value) return
  await ElMessageBox.confirm(
    `确认提交回填批次？共 ${preview.value.plannedCount} 个计划任务（跳过 ${preview.value.skippedDates.length} 个冲突日期，有效并发 ${preview.value.effConc}）。`,
    '提交回填批次',
    { type: 'warning', confirmButtonText: '提交', cancelButtonText: '再想想' },
  )
  submitting.value = true
  try {
    const batch = await backfillApi.submit(preview.value.previewHash)
    ElMessage.success(`批次 ${batch.batchId} 已创建`)
    preview.value = null
    refresh()
  } finally {
    submitting.value = false
  }
}

// ── 批次列表 ─────────────────────────────────────────────
const statusFilter = ref('')
const { items, total, page, pageSize, loading, refresh } = useListQuery<BackfillBatch>((params) =>
  backfillApi.page({ ...params, status: statusFilter.value || undefined }),
)
onMounted(refresh)

const statusLabel = (s: string): string => BACKFILL_STATUS_LABEL[s as BackfillStatus] ?? s
const statusTone = (s: string): Tone => BACKFILL_STATUS_TONE[s as BackfillStatus] ?? 'idle'

const totalPages = () => Math.max(1, Math.ceil(total.value / pageSize.value))
function go(target: number) {
  if (target < 1 || target > totalPages()) return
  page.value = target
  refresh()
}

// ── 批次操作 ─────────────────────────────────────────────
async function pause(b: BackfillBatch) {
  await ElMessageBox.confirm(`暂停后剩余日期停止投放，进行中任务不受影响。确认暂停「${b.batchId}」？`, '暂停批次', {
    type: 'warning',
    confirmButtonText: '暂停',
    cancelButtonText: '取消',
  })
  await backfillApi.pause(b.batchId)
  ElMessage.success('已暂停')
  refresh()
}

async function resume(b: BackfillBatch) {
  await backfillApi.resume(b.batchId)
  ElMessage.success('已恢复投放')
  refresh()
}

async function retryFailed(b: BackfillBatch) {
  await ElMessageBox.confirm(
    `为「${b.batchId}」的 ${b.failed} 个失败日期新建任务重跑（原失败行保留审计，批次从 DONE 复活为 RUNNING）。确认重跑失败日期？`,
    '重跑失败日期',
    { type: 'warning', confirmButtonText: '重跑失败日期', cancelButtonText: '取消' },
  )
  await backfillApi.retryFailed(b.batchId)
  ElMessage.success('失败日期重跑已受理')
  refresh()
}

// ── 批次详情（抽屉：任务实例分页 + 失败明细）────────────────
const detailVisible = ref(false)
const detailBatch = ref<BackfillBatch | null>(null)
const detailTasks = ref<TaskListItem[]>([])
const detailLoading = ref(false)

async function openDetail(b: BackfillBatch) {
  detailBatch.value = b
  detailVisible.value = true
  detailLoading.value = true
  try {
    const result = await backfillApi.tasks(b.batchId, 1, 50)
    detailTasks.value = result.records
    detailBatch.value = await backfillApi.get(b.batchId)
  } finally {
    detailLoading.value = false
  }
}
</script>

<template>
  <div>
    <div class="page-header">
      <div>
        <h1>回填补数</h1>
        <div class="sub">
          <span>历史业务日期的批量重跑 · 预览双闸门 + 滚动投放（PRD §10.11）</span>
          <span class="mono">共 {{ total }} 个批次</span>
        </div>
      </div>
      <div class="actions">
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 130px" @change="go(1)">
          <el-option v-for="(label, key) in BACKFILL_STATUS_LABEL" :key="key" :label="label" :value="key" />
        </el-select>
        <el-button @click="go(1)">查询</el-button>
      </div>
    </div>

    <!-- 新建回填（预览两段式） -->
    <div v-if="canWrite" class="panel" style="margin-bottom: 14px; padding: 14px 16px">
      <div class="panel-h" style="padding: 0 0 10px"><b>新建回填</b></div>
      <div class="actions" style="flex-wrap: wrap; gap: 10px">
        <el-select v-model="form.workflowId" placeholder="选择已发布工作流" filterable style="width: 260px">
          <el-option
            v-for="w in workflows"
            :key="w.workflowId"
            :label="`${w.workflowName}（${w.currentVersion?.versionNo ?? '—'}）`"
            :value="w.workflowId"
          />
        </el-select>
        <el-date-picker
          v-model="form.range"
          type="daterange"
          start-placeholder="业务日期从"
          end-placeholder="到"
          value-format="YYYY-MM-DD"
          style="width: 260px"
        />
        <el-input-number v-model="form.concurrency" :min="1" :max="16" placeholder="并发" style="width: 120px" />
        <el-checkbox v-model="form.sequential">按日期顺序投放</el-checkbox>
        <el-button type="primary" :loading="previewing" :disabled="!!dateRangeError" @click="doPreview">
          预览
        </el-button>
        <span v-if="dateRangeError" class="range-err">{{ dateRangeError }}</span>
      </div>

      <!-- 预览结果（提交闸门） -->
      <div v-if="preview" class="preview-box">
        <div class="actions" style="margin-bottom: 8px">
          <span>计划 <b class="mono">{{ preview.plannedCount }}</b> 个任务</span>
          <span>· 撞车跳过 <b class="mono">{{ preview.skippedDates.length }}</b> 个日期</span>
          <span>· 有效并发 <b class="mono">{{ preview.effConc }}</b></span>
          <span v-if="preview.policyConflict" class="range-err">
            ⚠ 并发策略 {{ preview.policyConflict.policy }}
            （max_parallel_runs={{ preview.policyConflict.maxParallelRuns ?? '不限' }}）收敛为
            {{ preview.policyConflict.effConc }}
          </span>
          <el-button
            type="primary"
            size="small"
            :loading="submitting"
            :disabled="preview.plannedCount === 0"
            style="margin-left: auto"
            @click="doSubmit"
          >
            确认提交批次
          </el-button>
        </div>
        <div class="mono preview-dates">
          <span v-for="t in preview.tasks" :key="t.bizDate" class="preview-date" :title="t.plannedTaskId">
            {{ t.bizDate }}
          </span>
          <span v-for="d in preview.skippedDates" :key="'skip' + d" class="preview-date skipped">{{ d }}（已存在）</span>
        </div>
      </div>
    </div>

    <!-- 批次列表 -->
    <div class="panel">
      <div class="table-wrap" v-loading="loading">
        <table class="tbl">
          <thead>
            <tr>
              <th>批次</th>
              <th>状态</th>
              <th>工作流</th>
              <th>业务日期范围</th>
              <th>进度（成功/失败/等待）</th>
              <th>有效并发</th>
              <th>提交人 / 时间</th>
              <th style="width: 240px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="b in items" :key="b.batchId">
              <td>
                <div class="link-id">{{ b.batchId }}</div>
                <div class="sub mono">{{ b.batchName || '—' }}</div>
              </td>
              <td><ToneChip :tone="statusTone(b.status)" :label="statusLabel(b.status)" /></td>
              <td>{{ b.workflowName }}</td>
              <td class="mono">{{ b.bizFrom }} ~ {{ b.bizTo }}</td>
              <td class="mono">
                {{ b.total }}（<span style="color: var(--ok)">{{ b.success }}</span> /
                <span style="color: var(--fail)">{{ b.failed }}</span> / {{ b.waiting }}）
              </td>
              <td class="mono">{{ b.effConc }}</td>
              <td>
                <div>{{ b.submitter }}</div>
                <div class="sub mono">{{ formatDateTime(b.submittedAt) }}</div>
              </td>
              <td>
                <div class="actions">
                  <el-button text size="small" @click="openDetail(b)">详情</el-button>
                  <template v-if="canWrite">
                    <el-button v-if="b.status === 'RUNNING'" text size="small" @click="pause(b)">暂停</el-button>
                    <el-button v-if="b.status === 'PAUSED'" text size="small" @click="resume(b)">继续</el-button>
                    <el-button
                      v-if="b.failed > 0 && (b.status === 'DONE' || b.status === 'FAILED')"
                      text
                      size="small"
                      @click="retryFailed(b)"
                    >
                      重跑失败日期
                    </el-button>
                  </template>
                </div>
              </td>
            </tr>
            <tr v-if="items.length === 0">
              <td colspan="8" class="empty-hint">{{ loading ? '加载中…' : '暂无回填批次' }}</td>
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

    <!-- 批次详情抽屉 -->
    <el-drawer v-model="detailVisible" :title="`批次 ${detailBatch?.batchId ?? ''}`" size="620px">
      <div v-loading="detailLoading">
        <div v-if="detailBatch" class="detail-meta">
          <div class="meta-item">状态 <ToneChip :tone="statusTone(detailBatch.status)" :label="statusLabel(detailBatch.status)" /></div>
          <div class="meta-item">耗时 <b class="mono">{{ formatDuration(detailBatch.durationMs) }}</b></div>
          <div class="meta-item">完成 <b class="mono">{{ formatDateTime(detailBatch.finishedAt) }}</b></div>
        </div>

        <div v-if="detailBatch && detailBatch.failures.length > 0" class="fail-box">
          <div class="panel-h"><b>失败明细（{{ detailBatch.failures.length }}）</b></div>
          <div v-for="f in detailBatch.failures" :key="f.bizDate + f.taskId" class="fail-row">
            <span class="mono">{{ f.bizDate }}</span>
            <router-link v-if="f.taskId" :to="`/tasks/${f.taskId}`">{{ f.taskId }}</router-link>
            <span class="sub">{{ f.reason }}</span>
          </div>
        </div>

        <div class="panel-h"><b>批次任务实例（一个日期可能有两行：重跑新建 + 原行审计）</b></div>
        <table class="tbl">
          <thead>
            <tr>
              <th>任务</th>
              <th>业务日期</th>
              <th>状态</th>
              <th>提交时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in detailTasks" :key="t.taskId">
              <td><router-link class="link-id" :to="`/tasks/${t.taskId}`">{{ t.taskId }}</router-link></td>
              <td class="mono">{{ dash(t.bizDate) }}</td>
              <td><ToneChip :tone="statusTone(t.status)" :label="statusLabel(t.status)" /></td>
              <td class="mono">{{ formatDateTime(t.submitAt) }}</td>
            </tr>
            <tr v-if="detailTasks.length === 0">
              <td colspan="4" class="empty-hint">暂无任务实例</td>
            </tr>
          </tbody>
        </table>
      </div>
    </el-drawer>
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

.range-err {
  color: var(--fail);
  font-size: 12px;
}

.preview-box {
  margin-top: 12px;
  padding: 10px 12px;
  background: var(--bg-inset);
  border: 1px solid var(--line-faint);
  border-radius: var(--r-sm);
}

.preview-dates {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.preview-date {
  padding: 2px 8px;
  background: var(--pri-dim);
  border-radius: var(--r-sm);
  font-size: 11px;
  color: var(--t1);
}

.preview-date.skipped {
  background: var(--bg-hover);
  color: var(--t3);
  text-decoration: line-through;
}

.detail-meta {
  display: flex;
  gap: 20px;
  margin-bottom: 12px;
}

.fail-box {
  margin-bottom: 12px;
  padding: 10px 12px;
  background: var(--bg-inset);
  border-radius: var(--r-sm);
}

.fail-row {
  display: flex;
  gap: 10px;
  font-size: 12px;
  padding: 3px 0;
}
</style>
