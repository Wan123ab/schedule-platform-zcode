<script setup lang="ts">
/**
 * 工作流详情（PRD §10.7；原型 prototype/workflow-detail.html 的「概览 / 版本 / 触发器 / 并发」四块）。
 *
 * 【本页的三条主线】
 * ① **草稿是唯一可编辑的形态**（D-11）：已发布版本冻结，"改"这件事一律从
 *    「新开草稿 / 继续编辑草稿」进入编辑器。所以本页的核心动作不是"编辑"，
 *    而是把用户**送到正确的那个版本号上** —— 编辑器路由带 `?version=WFV-xxxx-xx`。
 * ② **并发配置是独立端点**（`PUT /workflows/{id}/concurrency`）：它是 DAG 规则 8 的
 *    校验对象，改它会影响"能不能发布"，故单独一个有独立审计动作的入口。
 * ③ **触发器在本页维护**（CONTRACT §6.3 六个端点）：列表必须带 `workflowId`，
 *    新建/编辑共用一个抽屉，cron 走服务端预览（前端不自己算，否则方言一旦不一致就是假绿）。
 *
 * 【本页刻意不做的事，以及原因】（已登记为 README-M3 偏离项）
 * - 不展示工作流级默认值（超时/重试/失败策略）的编辑入口：`WorkflowVO` 出这四个字段，
 *   但没有任何端点能改它们（`PUT /workflows/{id}` 只收名称/项目/描述）。只读展示，不假装能改。
 * - 不做「执行历史」页签：需要任务列表端点（M4）。
 * - 不做「版本对比」：CONTRACT 未定义 diff 端点，前端自己 diff 会与审计日志的 diff 口径分叉。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { triggerApi, workflowApi } from '@/api/modules/workflow'
import type { BizError } from '@/api/http'
import type {
  SaveConcurrencyParams,
  SaveTriggerParams,
  SaveWorkflowParams,
  TriggerItem,
  WorkflowItem,
  WorkflowVersionBrief,
} from '@/api/types/workflow'
import ToneChip from '@/components/biz/ToneChip.vue'
import { usePermission } from '@/composables/usePermission'
import { PERM } from '@/constants/permissions'
import {
  CONCURRENCY_POLICY_LABEL,
  FAILURE_STRATEGY_LABEL,
  TRIGGER_TYPE_LABEL,
  WORKFLOW_STATUS_LABEL,
  WORKFLOW_STATUS_TONE,
  WORKFLOW_VERSION_STATUS_LABEL,
  WORKFLOW_VERSION_STATUS_TONE,
  type ConcurrencyPolicy,
  type FailureStrategy,
  type Tone,
  type TriggerType,
  type WorkflowStatus,
  type WorkflowVersionStatus,
} from '@/types/enums'
import { formatDateTime } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const { can } = usePermission()

const workflowId = computed(() => String(route.params.workflowId))

const detail = ref<WorkflowItem | null>(null)
const versions = ref<WorkflowVersionBrief[]>([])
const triggers = ref<TriggerItem[]>([])
const loading = ref(false)

// ── 查表 ───────────────────────────────────────────────────
const statusLabel = (s: string): string => WORKFLOW_STATUS_LABEL[s as WorkflowStatus] ?? s
const statusTone = (s: string): Tone => WORKFLOW_STATUS_TONE[s as WorkflowStatus] ?? 'idle'
const versionStatusLabel = (s: string): string =>
  WORKFLOW_VERSION_STATUS_LABEL[s as WorkflowVersionStatus] ?? s
const versionStatusTone = (s: string): Tone =>
  WORKFLOW_VERSION_STATUS_TONE[s as WorkflowVersionStatus] ?? 'idle'
const concurrencyLabel = (p: string): string => CONCURRENCY_POLICY_LABEL[p as ConcurrencyPolicy] ?? p
const triggerTypeLabel = (t: string): string => TRIGGER_TYPE_LABEL[t as TriggerType] ?? t
const failureStrategyLabel = (s: string | null): string =>
  s ? (FAILURE_STRATEGY_LABEL[s as FailureStrategy] ?? s) : '—'

const CONCURRENCY_OPTIONS: ConcurrencyPolicy[] = ['FORBID', 'ALLOW', 'QUEUE']

/** 草稿 = 版本列表里状态为 DRAFT 的那一行（后端保证同时最多一个，42215 就是为此）。 */
const draft = computed(() => versions.value.find((v) => v.publishStatus === 'DRAFT') ?? null)

async function load() {
  loading.value = true
  try {
    detail.value = await workflowApi.get(workflowId.value)
    // 版本列表与触发器都是"附属信息"，任一失败不该让整页空白，故各自 try
    await Promise.all([loadVersions(), loadTriggers()])
    syncConcurrencyForm()
  } finally {
    loading.value = false
  }
}

async function loadVersions() {
  try {
    versions.value = await workflowApi.versions(workflowId.value)
  } catch {
    versions.value = []
  }
}

async function loadTriggers() {
  try {
    triggers.value = await triggerApi.listByWorkflow(workflowId.value)
  } catch {
    triggers.value = []
  }
}

onMounted(load)

// ── 基础信息编辑 ───────────────────────────────────────────
const infoVisible = ref(false)
const savingInfo = ref(false)
const infoForm = ref<SaveWorkflowParams>({ workflowName: '', projectId: '', description: '' })

function openInfoEdit() {
  if (!detail.value) return
  infoForm.value = {
    workflowName: detail.value.workflowName,
    projectId: detail.value.projectId,
    description: detail.value.description ?? '',
  }
  infoVisible.value = true
}

async function submitInfo() {
  if (!infoForm.value.workflowName.trim()) {
    ElMessage.warning('工作流名称必填')
    return
  }
  savingInfo.value = true
  try {
    detail.value = await workflowApi.update(workflowId.value, infoForm.value)
    ElMessage.success('已保存')
    infoVisible.value = false
  } finally {
    savingInfo.value = false
  }
}

// ── 并发设置 ───────────────────────────────────────────────
const concurrencyForm = ref<SaveConcurrencyParams>({ concurrencyPolicy: 'FORBID', maxParallelRuns: 1 })
const savingConcurrency = ref(false)

function syncConcurrencyForm() {
  if (!detail.value) return
  concurrencyForm.value = {
    concurrencyPolicy: detail.value.concurrencyPolicy,
    maxParallelRuns: detail.value.maxParallelRuns,
  }
}

async function saveConcurrency() {
  const max = concurrencyForm.value.maxParallelRuns
  // 规则 8 要求并发配置非空且并行数 >= 1；前端先提醒，省一次 40001
  if (!Number.isInteger(max) || max < 1) {
    ElMessage.warning('最大并行实例数必须是大于等于 1 的整数')
    return
  }
  savingConcurrency.value = true
  try {
    detail.value = await workflowApi.updateConcurrency(workflowId.value, concurrencyForm.value)
    ElMessage.success('并发配置已保存')
    syncConcurrencyForm()
  } finally {
    savingConcurrency.value = false
  }
}

// ── 草稿：新开 / 继续编辑 ──────────────────────────────────
const creatingDraft = ref(false)

/**
 * 进入编辑器。
 *
 * **一定要带 `?version=`**：编辑器不能靠"工作流当前版本"自行推导该编辑哪一版 ——
 * 草稿存在时该编草稿、不存在时得先建，而这两种情况下"最新版本"是同一个还是不同的，
 * 取决于服务端状态。让本页（刚读过版本列表）把答案显式传过去，编辑器就只剩"按号取图"。
 */
function openEditor(versionId?: string) {
  router.push({
    path: `/workflows/${workflowId.value}/edit`,
    query: versionId ? { version: versionId } : undefined,
  })
}

async function startDraft() {
  creatingDraft.value = true
  try {
    const created = await workflowApi.createDraft(workflowId.value)
    ElMessage.success(`草稿 ${created.versionNo} 已创建`)
    openEditor(created.versionId)
  } catch (e) {
    // 42215 = 已有一份未发布草稿。这不该发生（按钮已按 hasDraftChanges 分支），
    // 但并发编辑下会撞上：给出去哪继续编辑的明确指引，而不是笼统"创建失败"
    if ((e as BizError).code === 42215) {
      await loadVersions()
      const existing = versions.value.find((v) => v.publishStatus === 'DRAFT')
      const go = await askConfirm(
        existing
          ? `已存在未发布草稿 ${existing.versionNo}。是否直接去编辑它？`
          : '已存在未发布草稿。请刷新后从版本列表进入。',
        '已有草稿',
        '去编辑',
      )
      if (go && existing) openEditor(existing.versionId)
    }
  } finally {
    creatingDraft.value = false
  }
}

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

async function publishVersion(row: WorkflowVersionBrief) {
  const ok = await askConfirm(
    `将发布 ${row.versionNo}（${row.versionId}）。发布时跑 DAG 全量校验（10 条），通过后该版本冻结、任务开始可以引用它。确认发布？`,
    '发布版本',
    '发布',
  )
  if (!ok) return
  try {
    detail.value = await workflowApi.publish(workflowId.value, { versionId: row.versionId })
    ElMessage.success('已发布')
    await loadVersions()
  } catch (e) {
    await showDagErrors(e)
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

async function disableWorkflow() {
  if (!detail.value) return
  const ok = await askConfirm(
    `停用「${detail.value.workflowName}」后，定时触发器不再产生新任务（运行中的任务不受影响）。确认停用？`,
    '停用工作流',
    '停用',
  )
  if (!ok) return
  detail.value = await workflowApi.disable(workflowId.value)
  ElMessage.success('已停用')
}

// ══════════════════════════════════════════════════════════
// 触发器（CONTRACT §6.3）
// ══════════════════════════════════════════════════════════
interface TriggerForm {
  triggerName: string
  triggerType: TriggerType
  /** CRON 下二选一：cron 表达式 / 固定周期。二者都给或都不给都会 42216 */
  configMode: 'cron' | 'period'
  cronExpression: string
  periodSeconds: number | null
  timezone: string
  effectiveStart: Date | null
  effectiveEnd: Date | null
  /** 触发时参数：用户填的 JSON 对象（键是用户数据，不参与键转换） */
  runParamsText: string
  enabled: boolean
  catchUpEnabled: boolean
  catchUpMaxTimes: number | null
}

const triggerVisible = ref(false)
const editingTriggerId = ref<string | null>(null)
const savingTrigger = ref(false)
const triggerForm = ref<TriggerForm>(emptyTriggerForm())

/** cron 预览结果（接下来 N 次触发时间）。 */
const previewTimes = ref<string[]>([])
const previewError = ref('')
const previewing = ref(false)

function emptyTriggerForm(): TriggerForm {
  return {
    triggerName: '',
    triggerType: 'CRON',
    configMode: 'cron',
    cronExpression: '',
    periodSeconds: null,
    timezone: 'Asia/Shanghai',
    effectiveStart: null,
    effectiveEnd: null,
    runParamsText: '',
    enabled: true,
    catchUpEnabled: true,
    catchUpMaxTimes: 3,
  }
}

/** Date → 后端要求的 ISO-8601。`toISOString()` 输出 UTC 的 `...Z`，`OffsetDateTime.parse` 认它。 */
const toIso = (d: Date | null): string | undefined => (d ? d.toISOString() : undefined)

function openCreateTrigger() {
  editingTriggerId.value = null
  triggerForm.value = emptyTriggerForm()
  resetPreview()
  triggerVisible.value = true
}

function openEditTrigger(t: TriggerItem) {
  editingTriggerId.value = t.triggerId
  triggerForm.value = {
    triggerName: t.triggerName,
    triggerType: (t.triggerType as TriggerType) ?? 'CRON',
    configMode: t.cronExpression ? 'cron' : 'period',
    cronExpression: t.cronExpression ?? '',
    periodSeconds: t.periodSeconds,
    timezone: t.timezone ?? 'Asia/Shanghai',
    effectiveStart: t.effectiveStart ? new Date(t.effectiveStart) : null,
    effectiveEnd: t.effectiveEnd ? new Date(t.effectiveEnd) : null,
    runParamsText: t.runParams ? JSON.stringify(t.runParams, null, 2) : '',
    enabled: t.enabled,
    catchUpEnabled: t.catchUpEnabled ?? true,
    catchUpMaxTimes: t.catchUpMaxTimes ?? 3,
  }
  resetPreview()
  triggerVisible.value = true
}

/** runParams 输入框的占位示例。放在 script 里而不是模板里：属性里再套一层引号会被 vue/html-quotes 拒掉。 */
const RUN_PARAMS_PLACEHOLDER = '{"bizDate": "${bizDate}", "env": "prod"}'

function resetPreview() {
  previewTimes.value = []
  previewError.value = ''
}

/** runParams 是用户手写的 JSON：解析失败要原地报错，不能吞掉后发一个空对象给后端。 */
function parseRunParams(): Record<string, unknown> | undefined {
  const text = triggerForm.value.runParamsText.trim()
  if (!text) return undefined
  const parsed = JSON.parse(text)
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('触发参数必须是一个 JSON 对象，例如 {"bizDate": "${bizDate}"}')
  }
  return parsed as Record<string, unknown>
}

/** 把表单（含 Date 与 JSON 文本）翻成线协议入参。创建与更新共用。 */
function toTriggerParams(): SaveTriggerParams {
  const f = triggerForm.value
  const isCron = f.triggerType === 'CRON'
  return {
    // 更新时挂靠不可变更，故不传 workflowId
    workflowId: editingTriggerId.value ? undefined : workflowId.value,
    triggerName: f.triggerName,
    triggerType: f.triggerType,
    cronExpression: isCron && f.configMode === 'cron' ? f.cronExpression : undefined,
    periodSeconds: isCron && f.configMode === 'period' ? (f.periodSeconds ?? undefined) : undefined,
    timezone: f.timezone || undefined,
    effectiveStart: toIso(f.effectiveStart),
    effectiveEnd: toIso(f.effectiveEnd),
    runParams: parseRunParams(),
    enabled: f.enabled,
    catchUpEnabled: f.catchUpEnabled,
    catchUpMaxTimes: f.catchUpMaxTimes ?? undefined,
  }
}

async function submitTrigger() {
  const f = triggerForm.value
  if (!f.triggerName.trim()) {
    ElMessage.warning('触发器名称必填')
    return
  }
  if (f.triggerType === 'CRON') {
    if (f.configMode === 'cron' && !f.cronExpression.trim()) {
      ElMessage.warning('请填写 cron 表达式，或切换到固定周期')
      return
    }
    if (f.configMode === 'period' && (!f.periodSeconds || f.periodSeconds < 1)) {
      ElMessage.warning('固定周期必须是大于等于 1 的秒数')
      return
    }
  }
  if (f.effectiveStart && f.effectiveEnd && f.effectiveEnd <= f.effectiveStart) {
    // 与后端 42217 同一条规则，提前拦一次省一轮往返
    ElMessage.warning('生效窗口的结束时间必须晚于开始时间')
    return
  }
  let params: SaveTriggerParams
  try {
    params = toTriggerParams()
  } catch (e) {
    ElMessage.warning((e as Error).message)
    return
  }
  savingTrigger.value = true
  try {
    if (editingTriggerId.value) {
      await triggerApi.update(editingTriggerId.value, params)
      ElMessage.success('触发器已保存')
    } else {
      await triggerApi.create(params)
      ElMessage.success('触发器已创建')
    }
    triggerVisible.value = false
    await loadTriggers()
  } finally {
    savingTrigger.value = false
  }
}

/**
 * cron 预览走**服务端**（`GET /triggers/cron-preview`）。
 *
 * 前端不自己算下次触发时间：方言（Spring 6 段 vs Quartz 7 段）、时区、夏令时
 * 三件事任何一件不一致，"预览说 10:00 触发、实际 11:00 才触发"就会变成一个没人能复现的 bug。
 * 预览是"用真解析器试算"，因此它同时充当表达式的即时校验（非法 → 42216）。
 */
async function previewCron() {
  const expression = triggerForm.value.cronExpression.trim()
  if (!expression) {
    ElMessage.warning('请先填写 cron 表达式')
    return
  }
  previewing.value = true
  resetPreview()
  try {
    previewTimes.value = await triggerApi.cronPreview(expression, triggerForm.value.timezone || undefined, 5)
    if (previewTimes.value.length === 0) {
      previewError.value = '该表达式在生效窗口内没有后续触发时间'
    }
  } catch (e) {
    // 42216 的错误消息里带解析器原话（"Spring 6 段格式，秒 分 时 日 月 周"），直接展示最有用
    previewError.value = (e as Error).message || '表达式无法解析'
  } finally {
    previewing.value = false
  }
}

/** 启停：后端没有单独的开关端点，改 `enabled` 走同一条 PUT。 */
async function toggleTrigger(t: TriggerItem) {
  const params: SaveTriggerParams = {
    triggerName: t.triggerName,
    triggerType: t.triggerType,
    cronExpression: t.cronExpression ?? undefined,
    periodSeconds: t.periodSeconds ?? undefined,
    timezone: t.timezone ?? undefined,
    effectiveStart: t.effectiveStart ?? undefined,
    effectiveEnd: t.effectiveEnd ?? undefined,
    runParams: t.runParams ?? undefined,
    enabled: !t.enabled,
    catchUpEnabled: t.catchUpEnabled ?? undefined,
    catchUpMaxTimes: t.catchUpMaxTimes ?? undefined,
  }
  await triggerApi.update(t.triggerId, params)
  ElMessage.success(t.enabled ? '已停用' : '已启用')
  await loadTriggers()
}

async function removeTrigger(t: TriggerItem) {
  const ok = await askConfirm(`确认删除触发器「${t.triggerName}」？`, '删除触发器', '删除')
  if (!ok) return
  await triggerApi.remove(t.triggerId)
  ElMessage.success('已删除')
  await loadTriggers()
}

/** 触发器的"配置摘要"一列：CRON 下要能一眼看出是表达式还是固定周期。 */
function triggerConfigText(t: TriggerItem): string {
  if (t.cronExpression) return t.cronExpression
  if (t.periodSeconds) return `每 ${t.periodSeconds} 秒`
  return '手动触发'
}
</script>

<template>
  <div v-loading="loading">
    <div class="page-header">
      <div>
        <h1>{{ detail?.workflowName ?? '工作流详情' }}</h1>
        <div class="sub">
          <span class="mono">{{ workflowId }}</span>
          <span v-if="detail">归属 {{ detail.projectName }}</span>
          <ToneChip v-if="detail" :tone="statusTone(detail.status)" :label="statusLabel(detail.status)" />
          <ToneChip v-if="detail?.hasDraftChanges" tone="warn" label="有未发布修改" />
        </div>
      </div>
      <div class="actions">
        <router-link to="/workflows">
          <el-button>返回列表</el-button>
        </router-link>
        <el-button v-if="can(PERM.WORKFLOW_WRITE)" @click="openInfoEdit">编辑信息</el-button>
        <!-- 主入口只有一个，且"该编哪一版"由本页决定（见 openEditor 的注释） -->
        <el-button v-if="draft" v-loading="false" type="primary" @click="openEditor(draft.versionId)">
          继续编辑草稿 {{ draft.versionNo }}
        </el-button>
        <el-button
          v-else-if="can(PERM.WORKFLOW_WRITE)"
          type="primary"
          :loading="creatingDraft"
          @click="startDraft"
        >
          {{ detail?.currentVersion ? '基于当前版本新开草稿' : '开始编排' }}
        </el-button>
        <el-button
          v-if="can(PERM.WORKFLOW_PUBLISH) && detail?.status === 'PUBLISHED'"
          @click="disableWorkflow"
        >
          停用
        </el-button>
      </div>
    </div>

    <div v-if="detail" class="panel">
      <div class="kv">
        <div class="kv-item">
          <label>所属项目</label>
          <span>{{ detail.projectName }}</span>
        </div>
        <div class="kv-item">
          <label>当前版本</label>
          <span class="mono">{{ detail.currentVersion?.versionNo ?? '尚未发布' }}</span>
        </div>
        <div class="kv-item">
          <label>并发策略</label>
          <span>
            {{ concurrencyLabel(detail.concurrencyPolicy) }}
            <span class="mono faint">（{{ detail.concurrencyPolicy }} × {{ detail.maxParallelRuns }}）</span>
          </span>
        </div>
        <div class="kv-item">
          <label>集群亲和</label>
          <span>{{ detail.clusterAffinityEnabled ? '已开启' : '未开启' }}</span>
        </div>
        <div class="kv-item">
          <label>创建人</label>
          <span>{{ detail.creator }}</span>
        </div>
        <div class="kv-item">
          <label>创建时间</label>
          <span class="mono">{{ formatDateTime(detail.createdAt) }}</span>
        </div>
        <div class="kv-item">
          <label>更新时间</label>
          <span class="mono">{{ formatDateTime(detail.updatedAt) }}</span>
        </div>
      </div>
      <div v-if="detail.description" class="desc">{{ detail.description }}</div>

      <!--
        工作流级默认值：**只读**。WorkflowVO 出这四个字段，但没有任何端点能改它们
        （PUT /workflows/{id} 只收名称/项目/描述）。把它们显示出来是为了让"继承链的第 3 层
        到底有没有值"可查，不提供编辑入口 —— 假装能改比不显示更糟。
      -->
      <div class="inherit">
        <div class="inherit-title">工作流级默认值（继承链第 3 层，只读）</div>
        <div class="inherit-grid">
          <span class="k">超时</span>
          <span class="v mono">{{ detail.defaultTimeoutSeconds != null ? detail.defaultTimeoutSeconds + 's' : '未设置（向下继承）' }}</span>
          <span class="k">重试次数</span>
          <span class="v mono">{{ detail.defaultRetryCount ?? '未设置（向下继承）' }}</span>
          <span class="k">重试间隔</span>
          <span class="v mono">{{ detail.defaultRetryIntervalSeconds != null ? detail.defaultRetryIntervalSeconds + 's' : '未设置（向下继承）' }}</span>
          <span class="k">失败策略</span>
          <span class="v">{{ failureStrategyLabel(detail.defaultFailureStrategy) }}</span>
        </div>
        <div class="faint tiny">
          继承链：平台 → 项目 → 工作流 → 算子版本 → 步骤，任一层留空向上继承（PRD §12.5）。
          一期的写入入口只在步骤层，工作流级这四个值暂无编辑端点。
        </div>
      </div>
    </div>

    <!-- 并发与亲和 -->
    <div class="panel">
      <div class="panel-head">
        <h2>并发配置</h2>
        <span class="sub">并发策略是发布必填项（DAG 校验规则 8），改它会影响能否发布</span>
      </div>
      <div class="form-row">
        <div class="field">
          <label>并发超限策略</label>
          <el-select v-model="concurrencyForm.concurrencyPolicy" style="width: 170px" :disabled="!can(PERM.WORKFLOW_WRITE)">
            <el-option v-for="p in CONCURRENCY_OPTIONS" :key="p" :label="concurrencyLabel(p)" :value="p" />
          </el-select>
          <div class="help">
            禁止并发：已有实例在跑则本次触发被跳过（不是报错，是"这次不跑"）；
            排队等待：进入等待队列，队列满则 40902。
          </div>
        </div>
        <div class="field">
          <label>最大并行实例数</label>
          <el-input-number
            v-model="concurrencyForm.maxParallelRuns"
            :min="1"
            :max="1000"
            :disabled="!can(PERM.WORKFLOW_WRITE)"
          />
          <div class="help">同一工作流同时运行的任务数；手动/定时/API/回填四种触发共用这一套闸门。</div>
        </div>
        <el-button
          v-if="can(PERM.WORKFLOW_WRITE)"
          type="primary"
          :loading="savingConcurrency"
          @click="saveConcurrency"
        >
          保存并发配置
        </el-button>
      </div>
    </div>

    <!-- 版本 -->
    <div class="panel">
      <div class="panel-head">
        <h2>版本</h2>
        <span class="sub">版本不可变：已发布版本只可查看，修改必须新开草稿（D-11）</span>
      </div>
      <div class="table-wrap">
        <table class="tbl">
          <thead>
            <tr>
              <th>版本</th>
              <th>状态</th>
              <th>步骤数</th>
              <th>发布人</th>
              <th>发布时间</th>
              <th style="width: 200px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in versions" :key="row.versionId">
              <td>
                <span class="mono strong">{{ row.versionNo }}</span>
                <div class="sub mono">{{ row.versionId }}</div>
              </td>
              <td>
                <ToneChip :tone="versionStatusTone(row.publishStatus)" :label="versionStatusLabel(row.publishStatus)" />
                <ToneChip
                  v-if="detail?.currentVersion?.versionId === row.versionId"
                  tone="info"
                  label="当前生效"
                />
              </td>
              <td class="mono">{{ row.stepCount }}</td>
              <td>{{ row.publisher ?? '—' }}</td>
              <td class="mono">{{ row.publishedAt ? formatDateTime(row.publishedAt) : '—' }}</td>
              <td>
                <div class="actions">
                  <el-button text size="small" @click="openEditor(row.versionId)">
                    {{ row.publishStatus === 'DRAFT' ? '继续编辑' : '查看' }}
                  </el-button>
                  <el-button
                    v-if="can(PERM.WORKFLOW_PUBLISH) && row.publishStatus === 'DRAFT'"
                    text
                    size="small"
                    @click="publishVersion(row)"
                  >
                    发布
                  </el-button>
                </div>
              </td>
            </tr>
            <tr v-if="versions.length === 0">
              <td colspan="6" class="empty-hint">
                这个工作流还没有任何版本。点右上角「开始编排」创建第一份草稿。
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 触发器 -->
    <div class="panel">
      <div class="panel-head">
        <h2>触发器</h2>
        <span class="sub">cron 采用 Spring 6 段格式（秒 分 时 日 月 周），下次触发时间由服务端试算</span>
        <div class="spacer" />
        <el-button v-if="can(PERM.TRIGGER_WRITE)" type="primary" size="small" @click="openCreateTrigger">
          新建触发器
        </el-button>
      </div>
      <div class="table-wrap">
        <table class="tbl">
          <thead>
            <tr>
              <th>触发器</th>
              <th>类型</th>
              <th>配置</th>
              <th>时区</th>
              <th>生效窗口</th>
              <th>下次触发</th>
              <th>状态</th>
              <th style="width: 180px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in triggers" :key="t.triggerId">
              <td>
                <span class="strong">{{ t.triggerName }}</span>
                <div class="sub mono">{{ t.triggerId }}</div>
              </td>
              <td>{{ triggerTypeLabel(t.triggerType) }}</td>
              <td class="mono">{{ triggerConfigText(t) }}</td>
              <td class="mono">{{ t.timezone ?? '—' }}</td>
              <td class="mono tiny">
                {{ t.effectiveStart ? formatDateTime(t.effectiveStart) : '不限' }}
                →
                {{ t.effectiveEnd ? formatDateTime(t.effectiveEnd) : '不限' }}
              </td>
              <td class="mono">{{ formatDateTime(t.nextFireTime) }}</td>
              <td>
                <ToneChip :tone="t.enabled ? 'ok' : 'idle'" :label="t.enabled ? '启用' : '停用'" />
              </td>
              <td>
                <div class="actions">
                  <el-button v-if="can(PERM.TRIGGER_WRITE)" text size="small" @click="openEditTrigger(t)">
                    编辑
                  </el-button>
                  <el-button v-if="can(PERM.TRIGGER_WRITE)" text size="small" @click="toggleTrigger(t)">
                    {{ t.enabled ? '停用' : '启用' }}
                  </el-button>
                  <el-button v-if="can(PERM.TRIGGER_WRITE)" text size="small" @click="removeTrigger(t)">
                    删除
                  </el-button>
                </div>
              </td>
            </tr>
            <tr v-if="triggers.length === 0">
              <td colspan="8" class="empty-hint">还没有触发器。工作流发布后可以在这里挂定时任务。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 基础信息编辑 -->
    <el-dialog v-model="infoVisible" title="编辑工作流信息" width="560px">
      <el-form label-position="top">
        <el-form-item label="工作流名称" required>
          <el-input v-model="infoForm.workflowName" maxlength="128" />
        </el-form-item>
        <el-form-item label="归属项目">
          <!-- 只读：换项目等于把一份可能正被引用的编排搬出数据范围边界，后端会 40001 -->
          <el-input :model-value="detail?.projectName ?? ''" disabled />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="infoForm.description" type="textarea" :rows="3" maxlength="2000" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="infoVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingInfo" @click="submitInfo">保存</el-button>
      </template>
    </el-dialog>

    <!-- 触发器新建 / 编辑 -->
    <el-drawer v-model="triggerVisible" :title="editingTriggerId ? '编辑触发器' : '新建触发器'" size="620px">
      <el-form label-position="top">
        <el-form-item label="触发器名称" required>
          <el-input v-model="triggerForm.triggerName" maxlength="128" placeholder="例如：每日 02:00 清算" />
        </el-form-item>
        <el-form-item label="触发类型">
          <el-radio-group v-model="triggerForm.triggerType">
            <el-radio value="CRON">定时（Cron）</el-radio>
            <el-radio value="MANUAL">手动</el-radio>
          </el-radio-group>
          <div class="form-hint">
            一期只放 <strong>Cron</strong> 与 <strong>手动</strong>；API / 事件触发未开放（docs/07 §11）。
          </div>
        </el-form-item>

        <template v-if="triggerForm.triggerType === 'CRON'">
          <el-form-item label="配置方式">
            <el-radio-group v-model="triggerForm.configMode" @change="resetPreview">
              <el-radio value="cron">Cron 表达式</el-radio>
              <el-radio value="period">固定周期</el-radio>
            </el-radio-group>
          </el-form-item>

          <el-form-item v-if="triggerForm.configMode === 'cron'" label="Cron 表达式" required>
            <div class="row-inline">
              <el-input v-model="triggerForm.cronExpression" placeholder="0 0 2 * * ?" class="grow" />
              <el-button :loading="previewing" @click="previewCron">试算</el-button>
            </div>
            <div class="form-hint">
              Spring <strong>6 段</strong>格式：秒 分 时 日 月 周（<code>0 0 2 * * ?</code> = 每天 02:00）。
              注意不是 Quartz 的 7 段写法，多写一段会被 42216 拒绝。
            </div>
            <div v-if="previewTimes.length" class="preview">
              <div class="preview-title">接下来 5 次触发（服务端试算）：</div>
              <div v-for="(t, i) in previewTimes" :key="t" class="mono tiny">{{ i + 1 }}. {{ formatDateTime(t) }}</div>
            </div>
            <div v-else-if="previewError" class="preview error">{{ previewError }}</div>
          </el-form-item>

          <el-form-item v-else label="固定周期（秒）" required>
            <el-input-number v-model="triggerForm.periodSeconds" :min="1" :max="86400" />
            <div class="form-hint">与 Cron 表达式<strong>二选一</strong>：同时填或都不填都会得到 42216。</div>
          </el-form-item>

          <el-form-item label="时区">
            <el-input v-model="triggerForm.timezone" placeholder="Asia/Shanghai" />
            <div class="form-hint">IANA 名称（如 <code>Asia/Shanghai</code>）；留空用服务端默认。时区非法 → 40001。</div>
          </el-form-item>
        </template>

        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="生效开始">
              <el-date-picker v-model="triggerForm.effectiveStart" type="datetime" style="width: 100%" placeholder="不限" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="生效结束">
              <el-date-picker v-model="triggerForm.effectiveEnd" type="datetime" style="width: 100%" placeholder="不限" />
            </el-form-item>
          </el-col>
        </el-row>
        <div class="form-hint">结束时间必须晚于开始时间，否则 42217；两端都可留空表示不限。</div>

        <el-form-item label="触发参数（JSON 对象）">
          <el-input
            v-model="triggerForm.runParamsText"
            type="textarea"
            :rows="4"
:placeholder="RUN_PARAMS_PLACEHOLDER"
          />
          <div class="form-hint">
            这里的<strong>键是参数名</strong>（用户数据，大小写原样保留）；值是覆盖链第 4 层的来源。
            留空表示不覆盖。格式必须是 JSON 对象。
          </div>
        </el-form-item>

        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="停机补跑">
              <el-switch v-model="triggerForm.catchUpEnabled" />
              <div class="form-hint">服务重启期间错过的触发点是否补跑（补跑执行属 M4）。</div>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="补跑上限">
              <el-input-number
                v-model="triggerForm.catchUpMaxTimes"
                :min="0"
                :max="100"
                :disabled="!triggerForm.catchUpEnabled"
              />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="启用">
          <el-switch v-model="triggerForm.enabled" />
          <div class="form-hint">
            启用态变更同样走保存（没有单独的开关端点）并记入审计（UPDATE_TRIGGER）。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="triggerVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingTrigger" @click="submitTrigger">保存</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.kv {
  display: flex;
  flex-wrap: wrap;
  gap: 28px;
}

.kv-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.kv-item label {
  font-size: 11.5px;
  color: var(--t3);
}

.desc {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--line-faint);
  color: var(--t2);
  line-height: 1.8;
  white-space: pre-wrap;
}

.inherit {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--line-faint);
}

.inherit-title {
  font-size: 12.5px;
  color: var(--t2);
  margin-bottom: 10px;
}

.inherit-grid {
  display: grid;
  grid-template-columns: 76px 1fr 76px 1fr;
  gap: 6px 12px;
  font-size: 12px;
}

.inherit-grid .k {
  color: var(--t3);
}

.panel-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 12px;
}

.panel-head h2 {
  font-size: 15px;
  margin: 0;
}

.spacer {
  flex: 1;
}

.form-row {
  display: flex;
  align-items: flex-start;
  gap: 28px;
  flex-wrap: wrap;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.field label {
  font-size: 12px;
  color: var(--t2);
}

.help {
  font-size: 11px;
  color: var(--t3);
  max-width: 340px;
  line-height: 1.7;
}

.row-inline {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.grow {
  flex: 1;
}

.preview {
  margin-top: 8px;
  padding: 8px 10px;
  background: var(--bg-inset);
  border: 1px solid var(--line-faint);
  border-radius: var(--r-sm);
}

.preview-title {
  font-size: 11.5px;
  color: var(--t3);
  margin-bottom: 4px;
}

.preview.error {
  color: var(--warn, #d98a2b);
  font-size: 11.5px;
}

.strong {
  font-weight: 600;
}

.tiny {
  font-size: 11.5px;
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
  margin-top: 6px;
  background: var(--bg-inset);
  border: 1px solid var(--line-faint);
  border-radius: var(--r-sm);
}

.form-hint code {
  color: var(--t1);
}
</style>
