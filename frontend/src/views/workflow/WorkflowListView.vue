<script setup lang="ts">
/**
 * 工作流列表（PRD §10.7；原型 prototype/workflow-list.html）。
 *
 * 【本页演示的机制】
 * - `useListQuery`：分页/关键词/loading 这套状态机与算子页、集群页同源，
 *   页面只剩"筛选 + 渲染 + 动作"三件事。
 * - **排序是只属于本端点的能力**：`GET /workflows` 是全项目唯一支持 `orderBy/orderDir`
 *   的列表端点（docs/07 §7.4「每端点显式声明」），白名单取值来自
 *   `api/modules/workflow.ts` 的 `WORKFLOW_SORTABLE` —— 下拉选项直接由它生成，
 *   于是 40003（排序字段不在白名单）在 UI 上不可达。
 * - **「有未发布修改」不是一个装饰性标签**：它决定"点发布时到底发的是哪一版"。
 *   有草稿时用户想发的是**草稿**，而后端要求显式传 `versionId` —— 所以这里必须先
 *   取一次版本列表把草稿找出来，而不是让前端替他猜"最新的那一版"。
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  workflowApi,
  WORKFLOW_DEFAULT_SORT,
  WORKFLOW_SORT_LABEL,
  WORKFLOW_SORTABLE,
  type SortDirection,
  type WorkflowSortKey,
} from '@/api/modules/workflow'
import { projectApi } from '@/api/modules/project'
import type { BizError } from '@/api/http'
import type { ProjectItem } from '@/api/types/project'
import type { SaveWorkflowParams, WorkflowItem } from '@/api/types/workflow'
import ToneChip from '@/components/biz/ToneChip.vue'
import { useListQuery } from '@/composables/useListQuery'
import { usePermission } from '@/composables/usePermission'
import { PERM } from '@/constants/permissions'
import {
  CONCURRENCY_POLICY_LABEL,
  TASK_STATUS_LABEL,
  TASK_STATUS_TONE,
  WORKFLOW_STATUS_LABEL,
  WORKFLOW_STATUS_TONE,
  WORKFLOW_VERSION_STATUS_LABEL,
  WORKFLOW_VERSION_STATUS_TONE,
  type ConcurrencyPolicy,
  type TaskStatus,
  type Tone,
  type WorkflowStatus,
  type WorkflowVersionStatus,
} from '@/types/enums'
import { formatDateTime } from '@/utils/format'

const router = useRouter()
const { can } = usePermission()

const statusFilter = ref('')
const projectFilter = ref('')
const projects = ref<ProjectItem[]>([])
const orderBy = ref<WorkflowSortKey>(WORKFLOW_DEFAULT_SORT)
const orderDir = ref<SortDirection>('desc')

const { items, total, page, pageSize, keyword, loading, refresh } = useListQuery<WorkflowItem>((params) =>
  workflowApi.page({
    ...params,
    status: statusFilter.value || undefined,
    projectId: projectFilter.value || undefined,
    orderBy: orderBy.value,
    orderDir: orderDir.value,
  }),
)

onMounted(async () => {
  await refresh()
  // 项目下拉只取前 100 个：筛选与新建都够用，不为它单开一个"全量"端点
  projects.value = (await projectApi.page({ page: 1, pageSize: 100 })).records
})

// ── 查表（页面不做颜色判定，F-5）─────────────────────────────
const statusLabel = (s: string): string => WORKFLOW_STATUS_LABEL[s as WorkflowStatus] ?? s
const statusTone = (s: string): Tone => WORKFLOW_STATUS_TONE[s as WorkflowStatus] ?? 'idle'
const versionStatusLabel = (s: string): string =>
  WORKFLOW_VERSION_STATUS_LABEL[s as WorkflowVersionStatus] ?? s
const versionStatusTone = (s: string): Tone =>
  WORKFLOW_VERSION_STATUS_TONE[s as WorkflowVersionStatus] ?? 'idle'
const concurrencyLabel = (p: string): string => CONCURRENCY_POLICY_LABEL[p as ConcurrencyPolicy] ?? p
const runStatusLabel = (s: string | null): string => (s ? (TASK_STATUS_LABEL[s as TaskStatus] ?? s) : '—')
const runStatusTone = (s: string | null): Tone => (s ? (TASK_STATUS_TONE[s as TaskStatus] ?? 'idle') : 'idle')

const STATUS_OPTIONS: WorkflowStatus[] = ['DRAFT', 'PUBLISHED', 'DISABLED', 'ARCHIVED']
const SORT_OPTIONS = WORKFLOW_SORTABLE.map((value) => ({ value, label: WORKFLOW_SORT_LABEL[value] }))

// ── 分页 ───────────────────────────────────────────────────
const totalPages = () => Math.max(1, Math.ceil(total.value / pageSize.value))
function go(target: number) {
  if (target < 1 || target > totalPages()) return
  page.value = target
  refresh()
}

/** 排序变化后必须回到第 1 页：停在第 5 页看"新排序的前 20 条"没有意义。 */
function reorder() {
  go(1)
}

function toggleDir() {
  orderDir.value = orderDir.value === 'desc' ? 'asc' : 'desc'
  reorder()
}

// ── 新建 / 编辑基础信息 ─────────────────────────────────────
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const saving = ref(false)
const form = ref<SaveWorkflowParams>({ workflowName: '', projectId: '', description: '' })

function openCreate() {
  editingId.value = null
  form.value = { workflowName: '', projectId: projectFilter.value || '', description: '' }
  dialogVisible.value = true
}

function openEdit(row: WorkflowItem) {
  editingId.value = row.workflowId
  form.value = {
    workflowName: row.workflowName,
    projectId: row.projectId,
    description: row.description ?? '',
  }
  dialogVisible.value = true
}

async function submitForm() {
  if (!form.value.workflowName.trim()) {
    ElMessage.warning('工作流名称必填')
    return
  }
  if (!form.value.projectId) {
    ElMessage.warning('请选择归属项目')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await workflowApi.update(editingId.value, form.value)
      ElMessage.success('已保存')
      dialogVisible.value = false
      refresh()
    } else {
      const created = await workflowApi.create(form.value)
      ElMessage.success('工作流已创建')
      dialogVisible.value = false
      // 新建后直接进详情页：那里有「新开草稿」这个唯一的编排入口
      router.push(`/workflows/${created.workflowId}`)
    }
  } finally {
    saving.value = false
  }
}

// ── 动作 ───────────────────────────────────────────────────
/** 「取消」在 Element Plus 里是 reject('cancel') 而非错误，必须接住（见算子列表页同处注释）。 */
async function askConfirm(message: string, title: string, okText: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, title, {
      type: 'warning',
      confirmButtonText: okText,
      cancelButtonText: '取消',
    })
    return true
  } catch {
    return false
  }
}

/** 把 DAG 校验的 errors[] 摊开：每条带 rule 与 stepName，逐条列出来才改得动。 */
async function showDagErrors(e: unknown) {
  const code = (e as BizError).code
  if (![42213, 42214, 42218].includes(code)) return
  const payload = (e as BizError).payload as
    | { errors?: { rule?: string; stepName?: string; message?: string }[] }
    | undefined
  const errors = payload?.errors
  if (!Array.isArray(errors) || errors.length === 0) return
  const lines = errors
    .slice(0, 10)
    .map((it) => `· [${it.rule ?? ''}] ${it.stepName ? it.stepName + '：' : ''}${it.message ?? ''}`)
  await ElMessageBox.alert(lines.join('\n'), 'DAG 校验未通过', { type: 'warning' })
}

/**
 * 发布：**要发布哪一版**必须先定下来。
 *
 * - 有未发布草稿 → 发草稿（这才是用户点"发布"时的真实意图）；
 * - 没有草稿 → 发当前版本，后端对已 PUBLISHED 的版本只做"切回当前版本"
 *   （不改发布人与发布时间，因此这是一次无副作用的确认）。
 * - 一个版本都没有 → 拦住并指向编辑器：此时没有任何可发布对象。
 */
async function publish(row: WorkflowItem) {
  let versionId = row.currentVersion?.versionId
  let targetLabel = '当前版本'
  if (row.hasDraftChanges) {
    const versions = await workflowApi.versions(row.workflowId)
    const draft = versions.find((v) => v.publishStatus === 'DRAFT')
    if (!draft) {
      // 标记说有草稿、列表里却没有：数据不一致。不猜，让用户去详情页看
      ElMessage.warning('标记为「有未发布修改」，但版本列表里找不到草稿，请到详情页确认')
      return
    }
    versionId = draft.versionId
    targetLabel = `草稿 ${draft.versionNo}`
  }
  if (!versionId) {
    ElMessage.warning('这个工作流还没有任何版本，请先到编辑器里保存一张 DAG')
    return
  }
  const ok = await askConfirm(
    `将发布${targetLabel}（${versionId}）。发布时会跑 DAG 全量校验，通过后该版本即冻结、任务开始可以引用它。确认发布？`,
    '发布工作流',
    '发布',
  )
  if (!ok) return
  try {
    await workflowApi.publish(row.workflowId, { versionId })
    ElMessage.success('已发布')
    refresh()
  } catch (e) {
    // 通用 toast 只说"校验失败"，不足以定位；这里把逐条原因摊开
    await showDagErrors(e)
  }
}

async function disable(row: WorkflowItem) {
  const ok = await askConfirm(
    `停用「${row.workflowName}」后，定时触发器不再产生新任务（运行中的任务不受影响）。确认停用？`,
    '停用工作流',
    '停用',
  )
  if (!ok) return
  await workflowApi.disable(row.workflowId)
  ElMessage.success('已停用')
  refresh()
}
</script>

<template>
  <div>
    <div class="page-header">
      <div>
        <h1>工作流</h1>
        <div class="sub">
          <span>编排单元 · 每个工作流下可有多个不可变版本（PRD §10.7）</span>
          <span class="mono">共 {{ total }} 个</span>
        </div>
      </div>
      <div class="actions">
        <el-select v-model="projectFilter" placeholder="全部项目" clearable style="width: 150px" @change="go(1)">
          <el-option v-for="p in projects" :key="p.projectId" :label="p.projectName" :value="p.projectId" />
        </el-select>
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 128px" @change="go(1)">
          <el-option v-for="s in STATUS_OPTIONS" :key="s" :label="WORKFLOW_STATUS_LABEL[s]" :value="s" />
        </el-select>
        <el-input v-model="keyword" placeholder="搜索工作流名" style="width: 170px" clearable @keyup.enter="go(1)" />
        <el-select v-model="orderBy" style="width: 140px" @change="reorder">
          <el-option v-for="s in SORT_OPTIONS" :key="s.value" :label="'按' + s.label" :value="s.value" />
        </el-select>
        <el-button :title="orderDir === 'desc' ? '当前：降序' : '当前：升序'" @click="toggleDir">
          {{ orderDir === 'desc' ? '↓ 降序' : '↑ 升序' }}
        </el-button>
        <el-button @click="go(1)">查询</el-button>
        <el-button v-if="can(PERM.WORKFLOW_WRITE)" type="primary" @click="openCreate">新建工作流</el-button>
      </div>
    </div>

    <div class="panel">
      <div class="table-wrap" v-loading="loading">
        <table class="tbl">
          <thead>
            <tr>
              <th>工作流</th>
              <th>归属项目</th>
              <th>状态</th>
              <th>当前版本</th>
              <th>并发</th>
              <th>最近运行</th>
              <th>创建人</th>
              <th>更新时间</th>
              <th style="width: 190px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in items" :key="row.workflowId">
              <td>
                <router-link class="link-id" :to="`/workflows/${row.workflowId}`">
                  {{ row.workflowName }}
                </router-link>
                <div class="sub mono">{{ row.workflowId }}</div>
              </td>
              <td>{{ row.projectName }}</td>
              <td><ToneChip :tone="statusTone(row.status)" :label="statusLabel(row.status)" /></td>
              <td>
                <template v-if="row.currentVersion">
                  <div class="row-inline">
                    <span class="mono">{{ row.currentVersion.versionNo }}</span>
                    <ToneChip
                      :tone="versionStatusTone(row.currentVersion.publishStatus)"
                      :label="versionStatusLabel(row.currentVersion.publishStatus)"
                    />
                  </div>
                  <div class="sub mono">{{ row.currentVersion.stepCount }} 个步骤</div>
                </template>
                <!-- 从未发布过：不是"版本是空的"，而是"还没有任何一个版本"，文案要说清 -->
                <span v-else class="sub">尚未发布任何版本</span>
                <ToneChip v-if="row.hasDraftChanges" tone="warn" label="有未发布修改" />
              </td>
              <td>
                <div>{{ concurrencyLabel(row.concurrencyPolicy) }}</div>
                <div class="sub mono">× {{ row.maxParallelRuns }}</div>
              </td>
              <td>
                <ToneChip :tone="runStatusTone(row.lastRunStatus)" :label="runStatusLabel(row.lastRunStatus)" />
                <div class="sub mono">{{ row.lastRunAt ? formatDateTime(row.lastRunAt) : '—' }}</div>
              </td>
              <td>{{ row.creator }}</td>
              <td class="mono">{{ formatDateTime(row.updatedAt) }}</td>
              <td>
                <div class="actions">
                  <router-link :to="`/workflows/${row.workflowId}`">
                    <el-button text size="small">详情</el-button>
                  </router-link>
                  <el-button v-if="can(PERM.WORKFLOW_WRITE)" text size="small" @click="openEdit(row)">编辑</el-button>
                  <el-button v-if="can(PERM.WORKFLOW_PUBLISH)" text size="small" @click="publish(row)">发布</el-button>
                  <el-button
                    v-if="can(PERM.WORKFLOW_PUBLISH) && row.status === 'PUBLISHED'"
                    text
                    size="small"
                    @click="disable(row)"
                  >
                    停用
                  </el-button>
                </div>
              </td>
            </tr>
            <tr v-if="items.length === 0">
              <td colspan="9" class="empty-hint">{{ loading ? '加载中…' : '暂无工作流' }}</td>
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

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑工作流' : '新建工作流'" width="560px">
      <el-form label-position="top">
        <el-form-item label="工作流名称" required>
          <el-input v-model="form.workflowName" maxlength="128" placeholder="例如：日增量清算" />
        </el-form-item>
        <el-form-item label="归属项目" required>
          <!-- 创建后不可变更：换项目等于把一份可能正被引用的编排搬出数据范围边界 -->
          <el-select v-model="form.projectId" style="width: 100%" :disabled="!!editingId" placeholder="请选择">
            <el-option v-for="p in projects" :key="p.projectId" :label="p.projectName" :value="p.projectId" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="2000" />
        </el-form-item>
        <div class="form-hint">
          这里只建"壳"（名称 + 归属 + 并发默认值）。真正的编排 —— 节点、连线、参数 ——
          都在<strong>版本</strong>上，且版本一经发布即不可修改；改已发布工作流的唯一途径是
          基于当前版本新开草稿（D-11）。
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>
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

.row-inline {
  display: flex;
  align-items: center;
  gap: 6px;
}

.empty-hint {
  text-align: center;
  color: var(--t3);
  padding: 30px;
}

.form-hint {
  font-size: 11.5px;
  color: var(--t3);
  line-height: 1.7;
  padding: 8px 10px;
  background: var(--bg-inset);
  border: 1px solid var(--line-faint);
  border-radius: var(--r-sm);
}
</style>
