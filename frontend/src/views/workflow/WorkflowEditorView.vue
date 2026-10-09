<script setup lang="ts">
/**
 * 工作流编辑器（PRD §10.8；原型 prototype/workflow-editor.html；D-14 自研 SVG 画布）。
 *
 * ═══ 这一页的两条硬约束（都在注释里落地，不是口头约定）═══
 *
 * ① **必须带 `?version=WFV-xxxx-xx`，编辑器不猜"该编哪一版"**。
 *    详情页刚读过版本列表，它知道"有一份草稿、草稿是 v3"；编辑器两眼一抹黑 ——
 *    `hasDraftChanges` 只是个布尔，`currentVersion` 指向的是**已发布**版本。
 *    让编辑器自己推导只会得到"编错了那一版"，所以缺参数时**不猜、给引导**。
 *
 * ② **保存是整包替换（CONTRACT §6.2）**，必须先 `GET` 取完整 DAG 再在其上改。
 *    "只发改动的那一部分"在这里不是优化，是数据丢失：没提交的步骤与连线等于被删掉。
 *
 * ═══ 一个容易做错、且做错了看不出来的细节：保存后的锚点会变 ═══
 * 请求里的 `steps[].stepId` 只是"本次保存会话内的锚点"，服务端落库时**重新发号**
 * （`WFS-…`，因为 `uk_wstep_step_id` 是全表唯一索引），并把新号放在响应里。
 * 所以保存成功后必须**改用响应里的图**，否则本地锚点与库里长期不一致。
 * 但**不能按数组下标回填**：`WorkflowStepMapper.listByVersionId` 是
 * `ORDER BY pos_y, pos_x, id`（按画布位置读回，不是按插入顺序），
 * 响应顺序 ≠ 请求顺序。回填的键只能是 `stepName` —— 它在同一次保存里
 * 刚被规则 10 保证过唯一。这段逻辑见 `adoptServerGraph()`。
 *
 * ═══ 本页刻意不做的事（以及原因，已登记 README-M3 §4）═══
 * - **没有"丢弃草稿"按钮**：CONTRACT 没有这个端点。一期的"丢弃"表现为
 *   "重新发布当前版本"（`current_version` 切回去，草稿留在库里但不生效）。
 * - **没有独立的"校验"按钮**：全量校验挂在发布路径上、结构校验挂在保存路径上，
 *   没有单独的校验端点。本地自检只提示 1/5/10 三条，不作为闸门。
 * - **工作流参数只做原样 JSON 编辑**：`workflow_params` 是 jsonb 数组，但
 *   **元素的字段结构在 PRD / docs 里都没有定义**（只说了它是覆盖链第 3 层）。
 *   不self-invent 一套 schema，登记为 O-42。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clusterApi } from '@/api/modules/cluster'
import { operatorApi } from '@/api/modules/operator'
import { workflowApi, workflowVersionApi } from '@/api/modules/workflow'
import type { BizError } from '@/api/http'
import type { ClusterItem, QueueItem } from '@/api/types/cluster'
import type { OperatorItem, OperatorVersionItem, ParamDef } from '@/api/types/operator'
import type {
  DagEdgeDef,
  DagStepDef,
  SaveWorkflowVersionParams,
  WorkflowVersionItem,
} from '@/api/types/workflow'
import DagCanvas from '@/components/biz/dag/DagCanvas.vue'
import ToneChip from '@/components/biz/ToneChip.vue'
import { usePermission } from '@/composables/usePermission'
import { PERM } from '@/constants/permissions'
import { messageOf } from '@/utils/errorMessage'
import {
  FAILURE_STRATEGY_LABEL,
  OS_TYPE_LABEL,
  PARAM_TYPE_LABEL,
  STEP_TYPE_LABEL,
  WORKFLOW_VERSION_STATUS_LABEL,
  WORKFLOW_VERSION_STATUS_TONE,
  type FailureStrategy,
  type OsType,
  type ParamType,
  type StepType,
  type Tone,
  type WorkflowVersionStatus,
} from '@/types/enums'
import {
  REF_SOURCES,
  autoLayout,
  blankStep,
  canvasSizeFor,
  checkStructure,
  extractIssues,
  groupIssues,
  nodeSize,
  renderStepOutputRef,
  toEditorModel,
  variableCandidates,
  type DagIssue,
} from '@/utils/dag'
import { formatDateTime } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const { can } = usePermission()

/** 路由参数。`workflows/new` 只有路径没有 workflowId —— 那种情况走"缺参数引导"分支。 */
const workflowId = computed(() => (route.params.workflowId ? String(route.params.workflowId) : ''))
/**
 * ⚠️ 必须是 query 里的 `version`，不是路径段。
 * 详情页的版本列表里"继续编辑 v3"与"查看 v2"进的是同一个路径，
 * 差异只在 query —— 这也是 F-11（原型里两个入口指向同一 URL 导致"点了没反应"）的预防。
 */
const versionId = computed(() => (route.query.version ? String(route.query.version) : ''))

// ═══════════════════════════════════════════════════════════
// 页面状态
// ═══════════════════════════════════════════════════════════

const loading = ref(false)
const saving = ref(false)
const publishing = ref(false)
/** 版本加载失败的原因（旧链接、已不存在的版本号）——不能让它静默成一张空页面。 */
const loadError = ref('')

const version = ref<WorkflowVersionItem | null>(null)
/** 画布上的图。**这是本页唯一真源** —— 保存时整包提交的就是它。 */
const steps = ref<DagStepDef[]>([])
const edges = ref<DagEdgeDef[]>([])
const selectedStepId = ref<string | null>(null)
const selectedEdgeIndex = ref<number | null>(null)
const canvasRef = ref<InstanceType<typeof DagCanvas> | null>(null)

/** 服务端返回的待修正项（保存/发布失败时填充，任何编辑动作都会清空）。 */
const serverIssues = ref<DagIssue[]>([])

/** 基线快照：与它比对得出"有没有改过"（整包保存没有服务端 diff，只能自己算）。 */
const baseline = ref('')

// 下拉与选择器数据：算子在新建步骤时选，集群/队列决定步骤落在哪台机器上
const operators = ref<OperatorItem[]>([])
/** 算子业务编号 → 该算子的全部版本（含 paramTemplate / outputDeclarations，一次拿全）。 */
const operatorVersions = ref<Record<string, OperatorVersionItem[]>>({})
const clusters = ref<ClusterItem[]>([])
const clusterQueues = ref<Record<string, QueueItem[]>>({})

/** 工作流参数（覆盖链第 3 层）—— 原样 JSON 编辑，见文件头第 3 条说明。 */
const workflowParamsText = ref('[]')

// ── 只读判定 ───────────────────────────────────────────────
/**
 * 已发布/已归档的版本冻结（PRD §7.2-5），编辑器变成"查看器"。
 * 注意这与权限无关：有 `workflow:write` 也改不了已发布版本，那是 D-11 的版本不可变。
 */
const canEdit = computed(() => version.value?.publishStatus === 'DRAFT')

const statusLabel = (s: string): string => WORKFLOW_VERSION_STATUS_LABEL[s as WorkflowVersionStatus] ?? s
const statusTone = (s: string): Tone => WORKFLOW_VERSION_STATUS_TONE[s as WorkflowVersionStatus] ?? 'idle'

// ═══════════════════════════════════════════════════════════
// 加载
// ═══════════════════════════════════════════════════════════

/** 算子版本查表（步骤 → 它绑的那一版）。拿不到就返回 null，不抛错。 */
function versionOf(step: DagStepDef): OperatorVersionItem | null {
  if (!step.operatorId || !step.operatorVersionId) return null
  const list = operatorVersions.value[step.operatorId]
  return list?.find((v) => v.versionId === step.operatorVersionId) ?? null
}

/** 取某算子的版本列表（带缓存：同一算子反复选不重复请求）。 */
async function ensureVersions(operatorId: string): Promise<OperatorVersionItem[]> {
  const cached = operatorVersions.value[operatorId]
  if (cached) return cached
  const list = await operatorApi.listVersions(operatorId)
  operatorVersions.value = { ...operatorVersions.value, [operatorId]: list }
  return list
}

async function loadSideData(): Promise<void> {
  // 算子下拉只取前 200 个：画布上选算子够用，不为它单开"全量"端点
  operators.value = (await operatorApi.page({ page: 1, pageSize: 200 })).records
}

/** 图里出现过的算子，把它们的版本列表补齐（否则节点上的"未发布"提示会误报）。 */
async function prefetchGraphVersions(): Promise<void> {
  const ids = [...new Set(steps.value.map((s) => s.operatorId).filter((v): v is string => !!v))]
  await Promise.all(ids.map((id) => ensureVersions(id).catch(() => [] as OperatorVersionItem[])))
}

async function load(): Promise<void> {
  if (!versionId.value) return
  loading.value = true
  loadError.value = ''
  try {
    const detail = await workflowVersionApi.get(versionId.value)
    version.value = detail
    const model = toEditorModel(detail)
    steps.value = model.steps
    edges.value = model.edges
    workflowParamsText.value = JSON.stringify(model.workflowParams, null, 2)
    selectedStepId.value = model.steps[0]?.stepId ?? null
    selectedEdgeIndex.value = null
    serverIssues.value = []
    baseline.value = snapshot()
    await prefetchGraphVersions()
    await nextTick()
    // 换版本时组件实例会复用（同一路由），length 可能不变 → 依赖 watch 会漏，
    // 这里显式适应一次（watch 只负责"首次从空图变成有图"）
    canvasRef.value?.fitView()
  } catch (e) {
    // 收藏夹里的旧链接、已被丢弃的草稿编号都会走到这里。40300/40400 拦截器
    // **刻意不 toast**（要留给调用方做业务分支），所以这一页必须自己说清楚
    const err = e as BizError
    loadError.value = messageOf(err.code, err.message || '版本加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  window.addEventListener('keydown', onKeyDown)
  await loadSideData().catch(() => undefined)
  await load()
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKeyDown))

// ═══════════════════════════════════════════════════════════
// 校验（本地自检 + 服务端结论）
// ═══════════════════════════════════════════════════════════

/** 本地结构自检（镜像服务端规则 1/5/10，见 utils/dag.ts 的说明）。 */
const localIssues = computed(() => checkStructure(steps.value, edges.value))

/**
 * 展示用的待修正项。
 *
 * 【为什么是"二选一"而不是合并】服务端已经跑过一次时，它的结论是**权威且更全**的
 * （发布时是 10 条，本地只能算 3 条）；把两者拼在一起会让同一条问题出现两次，
 * 计数也不可信。两者都不为空时以服务端为准，本地自检退回"实时提示"的角色。
 */
const activeIssues = computed<DagIssue[]>(() =>
  serverIssues.value.length > 0 ? serverIssues.value : localIssues.value,
)
const issuesByStep = computed(() => groupIssues(activeIssues.value).byStep)
const globalIssues = computed(() => groupIssues(activeIssues.value).global)
const issueSource = computed(() => (serverIssues.value.length > 0 ? '服务端校验结果' : '本地结构自检（1/5/10）'))

/**
 * 节点上的"⚠"提示：**规则 2 与规则 7 的成因**。
 *
 * 这两条是发布失败最常见的原因（忘了选算子、算子在草稿态），而它们需要跨域数据 ——
 * 本地算完提示、让用户在点"发布"之前就看见，比发布失败后再解释一遍有效得多。
 * 注意这只影响**视觉效果**，不改任何数据，也不参与保存。
 */
const warningsByStepId = computed(() => {
  const map = new Map<string, string[]>()
  for (const step of steps.value) {
    if (step.stepType === 'NOTE') continue
    const warns: string[] = []
    if (!step.operatorId) {
      warns.push('未选择算子（发布时规则 2：42213）')
    } else if (!step.operatorVersionId) {
      warns.push('未选择算子版本（发布时规则 2：42213）')
    } else {
      const list = operatorVersions.value[step.operatorId]
      const bound = versionOf(step)
      // 版本列表还没加载完时**不预警**：拿"查不到"当"不存在"会造出一堆假警告
      if (list && !bound) warns.push('绑定的算子版本已不存在（发布时规则 7：42218）')
      else if (bound && bound.publishStatus !== 'PUBLISHED') {
        warns.push(`引用了未发布的算子版本 ${bound.versionNo}（发布时规则 7：42218）`)
      }
    }
    if (warns.length) map.set(step.stepId, warns)
  }
  return map
})

const operatorNames = computed<Record<string, string>>(() =>
  Object.fromEntries(operators.value.map((o) => [o.operatorId, o.operatorName])),
)

// ═══════════════════════════════════════════════════════════
// 保存 / 发布
// ═══════════════════════════════════════════════════════════

/**
 * 解析工作流参数文本。
 *
 * 【为什么是纯函数】（`workflowParamsError` 由它派生，而不是被它写入）
 * 它会被 `snapshot()`（脏状态 computed）反复调用。如果顺手往一个 ref 里写错误信息，
 * 这个 ref 又反过来是同一个 computed 的依赖 —— 就会得到"计算 → 写 → 依赖变化 → 再计算"
 * 的自我触发。纯函数 + 派生 error 把这条环路拆掉。
 */
function parseParamsText(text: string): { value: Record<string, unknown>[] | null; error: string } {
  try {
    const parsed: unknown = text.trim() === '' ? [] : JSON.parse(text)
    if (!Array.isArray(parsed)) throw new Error('必须是一个 JSON 数组')
    return { value: parsed as Record<string, unknown>[], error: '' }
  } catch (e) {
    return { value: null, error: (e as Error).message }
  }
}

const workflowParams = computed(() => parseParamsText(workflowParamsText.value))

/** 组装整包请求体（参数非法时返回 null，调用方负责提示）。 */
function buildPayload(): SaveWorkflowVersionParams | null {
  const params = workflowParams.value.value
  if (params === null) return null
  const { canvasWidth, canvasHeight } = canvasSizeFor(steps.value)
  return {
    steps: steps.value.map((s) => ({ ...s })),
    edges: edges.value.map((e) => ({ ...e })),
    workflowParams: params,
    canvasWidth,
    canvasHeight,
  }
}

/** 脏状态判定的快照串（整包保存没有服务端 diff，只能客户端算）。 */
function snapshot(): string {
  const payload = buildPayload()
  // 参数非法时把"非法"本身纳入快照：改坏 JSON 也算改过，否则用户会看到"没改过却存不了"
  return JSON.stringify(payload ?? { invalidParams: workflowParams.value.error })
}

const isDirty = computed(() => snapshot() !== baseline.value)

/** 任何会改变图的操作都要走它：清掉上一轮的服务端错误（它们已经过期了）。 */
function touch(): void {
  serverIssues.value = []
}

async function save(): Promise<void> {
  const id = versionId.value
  if (!id || saving.value) return
  const payload = buildPayload()
  if (!payload) {
    ElMessage.warning(`工作流参数不是合法 JSON：${workflowParams.value.error}`)
    return
  }
  saving.value = true
  try {
    const saved = await workflowVersionApi.saveDraft(id, payload)
    adoptServerGraph(saved)
    ElMessage.success(`已保存：${saved.stepCount} 个步骤 / ${saved.edges.length} 条连线`)
  } catch (e) {
    await handleDagFailure(e, '保存')
  } finally {
    saving.value = false
  }
}

/**
 * 保存成功后**改用响应里的图**（服务端重新发过号了）。
 *
 * ⚠️ 不能按下标回填：`listByVersionId` 是 `ORDER BY pos_y, pos_x, id`，
 * 响应顺序是**画布位置顺序**而不是请求顺序。用 `stepName` 当回填键 ——
 * 它在上一次保存里刚被规则 10 校验过唯一性，是此刻唯一可靠的对应关系。
 */
function adoptServerGraph(saved: WorkflowVersionItem): void {
  const previousName = steps.value.find((s) => s.stepId === selectedStepId.value)?.stepName ?? null
  version.value = saved
  const model = toEditorModel(saved)
  steps.value = model.steps
  edges.value = model.edges
  workflowParamsText.value = JSON.stringify(model.workflowParams, null, 2)
  selectedStepId.value = model.steps.find((s) => s.stepName === previousName)?.stepId ?? null
  selectedEdgeIndex.value = null
  serverIssues.value = []
  baseline.value = snapshot()
}

/**
 * 处理 DAG 类失败。
 *
 * ⚠️ `40001` 在拦截器里是**刻意不 toast** 的（见 `api/http.ts` 的透传清单）：
 * 它对应"请求体本身读不成一张图"（步骤键重复 / 连线端点不存在 / 自环，
 * 见 `DagAssembler#indexByKey`），错误消息是后端写给人看的中文，
 * 原样展示比映射成"参数校验失败，请检查表单"有用得多。
 */
async function handleDagFailure(e: unknown, action: string): Promise<void> {
  const err = e as BizError
  if (err.code === 40001) {
    await ElMessageBox.alert(err.message, `${action}被拒绝：请求体不合法`, { type: 'warning' })
    return
  }
  const issues = extractIssues(err.payload)
  if (issues.length > 0) {
    serverIssues.value = issues
    return
  }
  // 没有明细（42212 非草稿不可编辑、42215 已有草稿…）：拦截器已 toast 过通用文案，
  // 这里只补一句"为什么"，不再重复弹一条一模一样的消息
  if (![42212, 42215, 42216, 42217, 42218].includes(err.code)) {
    ElMessage.error(err.message || `${action}失败`)
  }
}

async function publish(): Promise<void> {
  const wfId = version.value?.workflowId ?? workflowId.value
  const id = versionId.value
  if (!wfId || !id) return
  if (isDirty.value) {
    const go = await askConfirm(
      '画布上有未保存的修改，发布的是**已保存**的那一份。要先保存再发布吗？',
      '未保存的修改',
      '先保存再发布',
    )
    if (!go) return
    await save()
    // 保存失败（42213 等）时 serverIssues 已填好，这里必须停住 ——
    // 否则会拿"库里的旧图"去发布，用户以为发的是刚画的那张
    if (serverIssues.value.length > 0 || isDirty.value) return
  }

  publishing.value = true
  try {
    await workflowApi.publish(wfId, { versionId: id })
    // 服务端返回工作流 VO（不带 DAG）；重取一次版本详情拿到最新的
    // publishStatus / publisher / publishedAt，页面同时切换成只读态
    await load()
    ElMessage.success('已发布。该版本已冻结，后续修改请基于当前版本新开草稿')
  } catch (e) {
    await handleDagFailure(e, '发布')
  } finally {
    publishing.value = false
  }
}

// ═══════════════════════════════════════════════════════════
// 画布操作
// ═══════════════════════════════════════════════════════════

function addStep(posX: number, posY: number, stepType: 'TASK' | 'NOTE'): void {
  if (!canEdit.value) return
  const step = blankStep(steps.value, posX, posY, stepType)
  steps.value = [...steps.value, step]
  selectedStepId.value = step.stepId
  selectedEdgeIndex.value = null
  touch()
}

/** 工具栏的"添加"按钮：在没有节点的右侧放一个（比"丢到左上角"更符合直觉）。 */
function addAtFreeSpot(stepType: 'TASK' | 'NOTE'): void {
  let x = 40
  for (const s of steps.value) {
    const { w } = nodeSize(s)
    x = Math.max(x, (s.posX ?? 0) + w + 80)
  }
  // 纵向对齐到现有节点的最上一行：新节点与图在同一水平带上，一眼看到
  const y = steps.value.reduce((min, s) => Math.min(min, s.posY ?? 40), 40)
  addStep(x, y, stepType)
}

/** 画布拖拽结束时的落点回写（画布只在抬手时 emit，见 DagCanvas 的说明）。 */
function onMoveStep(payload: { stepId: string; posX: number; posY: number }): void {
  const step = steps.value.find((s) => s.stepId === payload.stepId)
  if (!step) return
  step.posX = payload.posX
  step.posY = payload.posY
  touch()
}

function removeStep(stepId: string): void {
  if (!canEdit.value) return
  const step = steps.value.find((s) => s.stepId === stepId)
  if (!step) return
  const linked = edges.value.filter((e) => e.sourceStepId === stepId || e.targetStepId === stepId).length
  // 删除节点一律确认：画布上的误点代价是"图少了一个点"，而保存是整包的 ——
  // 一次误点就会把删掉的节点真的从库里带出去
  const extra = linked ? `\n与它相连的 ${linked} 条连线会一并删除（否则保存会因"连线引用了不存在的步骤"被 40001 拦下）。` : ''
  askConfirm(`删除步骤「${step.stepName}」？${extra}`, '删除步骤', '删除').then((ok) => {
    if (ok) doRemoveStep(stepId)
  })
}

/**
 * 真正删除。
 *
 * **必须同时清掉悬挂连线**：服务端 `DagAssembler#indexByKey` 对"连线引用了不存在的步骤"
 * 直接 40001（那是请求体不合法，不是 DAG 规则），删节点留线会当场把保存打回。
 */
function doRemoveStep(stepId: string): void {
  steps.value = steps.value.filter((s) => s.stepId !== stepId)
  edges.value = edges.value.filter((e) => e.sourceStepId !== stepId && e.targetStepId !== stepId)
  if (selectedStepId.value === stepId) selectedStepId.value = null
  touch()
}

function removeEdge(index: number): void {
  if (!canEdit.value) return
  edges.value = edges.value.filter((_, i) => i !== index)
  selectedEdgeIndex.value = null
  touch()
}

function connect(sourceStepId: string, targetStepId: string): void {
  if (!canEdit.value) return
  edges.value = [...edges.value, { sourceStepId, targetStepId }]
  touch()
}

function applyAutoLayout(): void {
  if (!canEdit.value) return
  steps.value = autoLayout(steps.value, edges.value)
  touch()
  nextTick(() => canvasRef.value?.fitView())
}

function onKeyDown(e: KeyboardEvent): void {
  // 在输入框里按 Delete 是想删字符，不是想删节点 —— 这个判断漏掉会造成"打字打到一半图没了"
  const target = e.target as HTMLElement | null
  const tag = target?.tagName?.toLowerCase()
  const typing = tag === 'input' || tag === 'textarea' || target?.isContentEditable === true
  if (typing) return

  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
    e.preventDefault()
    if (canEdit.value) void save()
    return
  }
  if (e.key !== 'Delete' && e.key !== 'Backspace') return
  if (selectedEdgeIndex.value !== null) {
    e.preventDefault()
    removeEdge(selectedEdgeIndex.value)
  } else if (selectedStepId.value) {
    e.preventDefault()
    removeStep(selectedStepId.value)
  }
}

/** 「取消」在 Element Plus 里是 reject('cancel') 而非错误，必须接住（否则控制台会报无关错误）。 */
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

// ═══════════════════════════════════════════════════════════
// 属性面板（选中步骤）
// ═══════════════════════════════════════════════════════════

const selectedStep = computed(() => steps.value.find((s) => s.stepId === selectedStepId.value) ?? null)
const selectedVersion = computed(() => (selectedStep.value ? versionOf(selectedStep.value) : null))
const selectedEdge = computed(() =>
  selectedEdgeIndex.value === null ? null : (edges.value[selectedEdgeIndex.value] ?? null),
)

/**
 * 连线两端显示名。
 *
 * 放在脚本里而不是模板内联，有两个原因：模板里的箭头函数**会丢掉 v-if 的类型收窄**
 * （`selectedEdge.sourceStepId` 会被判成 possibly null），以及"找不到步骤"
 * 需要用文案说清（悬挂连线是编辑中途的常态，不能显示成空白）。
 */
const edgeEndNames = computed(() => {
  const edge = selectedEdge.value
  const nameOf = (id: string): string => steps.value.find((s) => s.stepId === id)?.stepName ?? '（已删除的步骤）'
  return { source: edge ? nameOf(edge.sourceStepId) : '', target: edge ? nameOf(edge.targetStepId) : '' }
})

/** 参数模板按 seq 排（服务端存的是顺序号，不能依赖数组顺序）。 */
const paramDefs = computed<ParamDef[]>(() =>
  [...(selectedVersion.value?.paramTemplate ?? [])].sort((a, b) => a.seq - b.seq),
)

const STEP_TYPES: StepType[] = ['TASK', 'NOTE']
const OS_TYPES: OsType[] = ['LINUX', 'WINDOWS']
const FAILURE_STRATEGIES: FailureStrategy[] = ['TERMINATE', 'RETRY']
const paramTypeLabel = (t: string): string => PARAM_TYPE_LABEL[t as ParamType] ?? t
const osLabel = (o: string): string => OS_TYPE_LABEL[o as OsType] ?? o

/** 选算子：连带自动选一版（默认版本 > 最新已发布 > 最新），并预取它的版本列表。 */
async function onOperatorPick(operatorId: string): Promise<void> {
  const step = selectedStep.value
  if (!step) return
  step.operatorId = operatorId || null
  step.operatorVersionId = null
  if (!operatorId) {
    touch()
    return
  }
  const list = await ensureVersions(operatorId)
  const preferred =
    list.find((v) => v.isDefaultVersion && v.publishStatus === 'PUBLISHED') ??
    list.find((v) => v.publishStatus === 'PUBLISHED') ??
    list[0]
  if (preferred) step.operatorVersionId = preferred.versionId
  touch()
}

function onClusterPick(clusterId: string): void {
  const step = selectedStep.value
  if (!step) return
  step.targetClusterId = clusterId || null
  // 换集群必须清掉队列：队列属于集群，留着旧队列等于引用一个"不属于该集群"的队列
  step.targetQueueId = null
  if (clusterId && !clusterQueues.value[clusterId]) {
    clusterApi
      .queues(clusterId, { page: 1, pageSize: 100 })
      .then((res) => {
        clusterQueues.value = { ...clusterQueues.value, [clusterId]: res.records }
      })
      .catch(() => undefined)
  }
  touch()
}

/** 标签约束是 `string[] | null`：空数组存 null，避免库里出现 `[]` 与 `null` 两种"没约束"。 */
function onTagsChange(tags: string[]): void {
  const step = selectedStep.value
  if (!step) return
  step.tagConstraint = tags.length ? tags : null
  touch()
}

/**
 * 写参数值。
 *
 * 【为什么要做类型收敛】`params` 是 `Map<String,Object>`：NUMBER 参数当然可以是数字，
 * 但**它也可以是一个变量引用**（`${step.清洗.output.rows}`）—— 那时它是字符串。
 * 用 `el-input-number` 会当场把引用吃掉，所以 NUMBER 走文本输入，在这里按
 * "纯数字就转数字、含 `${` 就原样保留"的规则收敛。
 */
function setParam(def: ParamDef, raw: unknown): void {
  const step = selectedStep.value
  if (!step) return
  const params: Record<string, unknown> = { ...(step.params ?? {}) }
  const blank = raw === null || raw === undefined || (typeof raw === 'string' && raw.trim() === '')
  if (blank) {
    // 空值 = 未覆盖（运行时按继承链取值）。写空串会让后端判成"必填参数已填"，
    // 规则 3 就再也拦不住"没填"了
    delete params[def.paramKey]
  } else if (def.paramType === 'NUMBER' && typeof raw === 'string' && !raw.includes('${')) {
    const num = Number(raw)
    params[def.paramKey] = Number.isFinite(num) ? num : raw
  } else {
    params[def.paramKey] = raw
  }
  step.params = Object.keys(params).length ? params : null
  touch()
}

const paramValue = (step: DagStepDef, def: ParamDef): unknown => step.params?.[def.paramKey] ?? null
const paramText = (step: DagStepDef, def: ParamDef): string => {
  const v = paramValue(step, def)
  return v === null || v === undefined ? '' : String(v)
}

/** 当前步骤可选的目标队列。 */
const queueOptions = computed<QueueItem[]>(() =>
  selectedStep.value?.targetClusterId ? (clusterQueues.value[selectedStep.value.targetClusterId] ?? []) : [],
)

// ── 自定义参数（JSON 编辑）────────────────────────────────
const customParamsText = ref('{}')
const customParamsError = ref('')

watch(
  selectedStep,
  (step) => {
    customParamsText.value = step?.customParams ? JSON.stringify(step.customParams, null, 2) : '{}'
    customParamsError.value = ''
  },
  { immediate: true },
)

function applyCustomParams(): void {
  const step = selectedStep.value
  if (!step) return
  try {
    const parsed: unknown = customParamsText.value.trim() === '' ? {} : JSON.parse(customParamsText.value)
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error('需要是一个 JSON 对象')
    }
    const obj = parsed as Record<string, unknown>
    step.customParams = Object.keys(obj).length ? obj : null
    customParamsError.value = ''
    touch()
  } catch (e) {
    customParamsError.value = (e as Error).message
  }
}

// ── 变量引用插入器 ─────────────────────────────────────────
/** 步骤 id → 该步骤算子版本已声明的输出变量名（规则 4 判定的依据）。 */
const outputDecls = computed(() => {
  const map = new Map<string, string[]>()
  for (const step of steps.value) {
    const bound = versionOf(step)
    if (bound) map.set(step.stepId, (bound.outputDeclarations ?? []).map((d) => d.varName))
  }
  return map
})

/** 当前步骤能引用的上游输出。 */
const refCandidates = computed(() =>
  selectedStep.value
    ? variableCandidates(steps.value, edges.value, selectedStep.value.stepId, outputDecls.value)
    : [],
)

/**
 * 复制引用串。
 *
 * 【为什么是"复制"而不是"插入到光标处"】插入需要跟踪"最后一次聚焦的是哪个输入框"，
 * 那是一个跨十几个字段的状态机，且 JSON 文本域的光标位置还要单独处理。
 * 复制到剪贴板对**所有**字段（含启动命令式的自定义参数）都成立，代价只有一次粘贴。
 */
async function copyRef(ref: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(ref)
    ElMessage.success('已复制，粘贴到参数值里即可')
  } catch {
    // 非安全上下文（http 且非 localhost）会拒绝剪贴板；此时 ref 本身在页面上是可选中的
    ElMessage.warning('浏览器拒绝了剪贴板访问，请直接选中页面上的引用串复制')
  }
}

/** 把 `${step."名字".output.x}` 的写法做出来，避免用户自己撞上 42214。 */
const previewRef = computed(() =>
  selectedStep.value ? renderStepOutputRef(selectedStep.value.stepName, 'varName') : '',
)

// ═══════════════════════════════════════════════════════════
// 导航
// ═══════════════════════════════════════════════════════════

function backToDetail(): void {
  const wfId = version.value?.workflowId ?? workflowId.value
  if (wfId) router.push(`/workflows/${wfId}`)
  else router.push('/workflows')
}

/**
 * 手工填版本号进入。
 *
 * 【为什么留这个入口】`workflows/new` 这个路由名是原型 19 页映射的一部分
 * （`tests/permissions.spec.ts` 会断言它存在），所以不能删；但它没有版本号，
 * 编辑器做不了任何事。与其给一个空白页，不如给一条"贴版本号就能干活"的路 ——
 * 排障时运维常直接拿到一个 `WFV-…` 编号，这个框正好用得上。
 */
const manualVersion = ref('')

function gotoManualVersion(): void {
  const id = manualVersion.value.trim()
  if (!id) return
  router.push({ path: route.path, query: { version: id } })
}
</script>

<template>
  <div>
    <!-- ── 缺参数引导：编辑器不猜"该编哪一版" ──────────────── -->
    <template v-if="!versionId">
      <div class="page-header">
        <div>
          <h1>工作流编辑器</h1>
          <div class="sub"><span>需要指定要编辑的版本</span></div>
        </div>
        <div class="actions">
          <el-button @click="backToDetail()">返回工作流列表</el-button>
        </div>
      </div>
      <div class="panel">
        <div class="guide">
          <p>
            编辑器的入参是<strong>版本号</strong>，不是工作流号 —— 一个工作流可以有多个版本，
            而"该编哪一版"取决于服务端状态（有没有未发布草稿），<strong>编辑器不替用户猜</strong>。
          </p>
          <p>正确入口是<strong>工作流详情页 → 版本列表</strong>：那里按状态给出「继续编辑草稿」或「新开草稿」。</p>
          <div class="guide-form">
            <el-input v-model="manualVersion" placeholder="也可以直接填版本号，如 WFV-0042-03" style="width: 260px" />
            <el-button type="primary" :disabled="!manualVersion.trim()" @click="gotoManualVersion">打开</el-button>
          </div>
        </div>
      </div>
    </template>

    <template v-else>
      <!-- ── 页头 ─────────────────────────────────────────── -->
      <div class="page-header">
        <div>
          <h1>
            {{ version?.workflowName || '工作流' }}
            <span class="ver">{{ version?.versionNo ?? '' }}</span>
            <ToneChip v-if="version" :tone="statusTone(version.publishStatus)" :label="statusLabel(version.publishStatus)" />
            <ToneChip v-if="isDirty" tone="warn" label="修改未保存" />
          </h1>
          <div class="sub">
            <span class="mono">{{ versionId }}</span>
            <span class="mono">工作流 {{ version?.workflowId ?? workflowId }}</span>
            <span>{{ steps.length }} 个步骤</span>
            <span>{{ edges.length }} 条连线</span>
            <span v-if="version?.updatedAt">更新于 {{ formatDateTime(version.updatedAt) }}</span>
          </div>
        </div>
        <div class="actions">
          <el-button @click="backToDetail">返回详情</el-button>
          <template v-if="canEdit">
            <el-button :loading="saving" :disabled="!can(PERM.WORKFLOW_WRITE)" @click="save">
              保存草稿<em class="key-hint">Ctrl+S</em>
            </el-button>
            <el-button
              type="primary"
              :loading="publishing"
              :disabled="!can(PERM.WORKFLOW_PUBLISH)"
              @click="publish"
            >
              发布该版本
            </el-button>
          </template>
        </div>
      </div>

      <!-- 加载失败：与其渲染一个空画布让人以为"图是空的"，不如直接说清 -->
      <div v-if="loadError" class="panel">
        <div class="empty-hint">
          {{ loadError }}
          <div class="sub" style="margin-top: 8px">
            编辑器只能打开<strong>已存在</strong>的版本（{{ versionId }}）。草稿被丢弃、版本号写错、
            或该工作流不在你的数据范围内，都会走到这里。
          </div>
          <div style="margin-top: 16px">
            <el-button type="primary" @click="backToDetail">返回工作流详情</el-button>
          </div>
        </div>
      </div>

      <template v-else>
      <!-- 只读横幅：已发布版本冻结（PRD §7.2-5），这不是权限问题 -->
      <div v-if="version && !canEdit" class="panel readonly-banner">
        该版本已经是<strong>{{ statusLabel(version.publishStatus) }}</strong>状态，按 PRD §7.2-5
        （版本不可变）不可再修改。需要调整编排，请回到详情页基于当前版本<strong>新开草稿</strong>。
      </div>

      <div class="editor-body">
        <!-- ── 左：画布 ───────────────────────────────────── -->
        <div class="canvas-col">
          <div class="toolbar" v-if="canEdit">
            <el-button size="small" @click="addAtFreeSpot('TASK')">+ 任务节点</el-button>
            <el-button size="small" @click="addAtFreeSpot('NOTE')">+ 备注节点</el-button>
            <el-button size="small" :disabled="steps.length === 0" @click="applyAutoLayout">自动布局</el-button>
            <el-button size="small" :disabled="!selectedStepId" @click="removeStep(selectedStepId as string)">
              删除选中步骤
            </el-button>
            <el-button
              size="small"
              :disabled="selectedEdgeIndex === null"
              @click="selectedEdgeIndex !== null && removeEdge(selectedEdgeIndex)"
            >
              删除选中连线
            </el-button>
            <span class="spacer" />
            <span class="sub">双击空白新建 · 从节点右侧圆点拖出连线</span>
          </div>

          <div class="canvas-host" v-loading="loading">
            <DagCanvas
              ref="canvasRef"
              :steps="steps"
              :edges="edges"
              :selected-step-id="selectedStepId"
              :selected-edge-index="selectedEdgeIndex"
              :issues-by-step="issuesByStep"
              :warnings-by-step-id="warningsByStepId"
              :operator-names="operatorNames"
              :readonly="!canEdit"
              @select-step="selectedStepId = $event"
              @select-edge="selectedEdgeIndex = $event"
              @move-step="onMoveStep"
              @connect="(p) => connect(p.sourceStepId, p.targetStepId)"
              @reject-connect="(reason) => ElMessage.warning(reason)"
              @add-step="(p) => addStep(p.posX, p.posY, 'TASK')"
              @remove-edge="removeEdge"
            />
          </div>

          <!-- ── 校验结果 ─────────────────────────────────── -->
          <div class="panel issues-panel">
            <div class="panel-head">
              <h2>待修正项（{{ activeIssues.length }}）</h2>
              <span class="sub">{{ issueSource }}</span>
              <span class="spacer" />
              <span v-if="!serverIssues.length" class="sub">
                本地自检只覆盖结构（规则 1/5/10）；发布时服务端跑全量 10 条
              </span>
            </div>
            <div v-if="activeIssues.length === 0" class="empty-hint">
              结构没问题<template v-if="!serverIssues.length">（完整校验在发布时进行）</template>
            </div>
            <ul v-else class="issue-list">
              <li v-for="(it, i) in globalIssues" :key="`g${i}`">
                <span class="rule mono">规则 {{ it.rule }}</span>
                <span class="msg">{{ it.message }}</span>
              </li>
              <li v-for="step in steps" :key="step.stepId">
                <template v-if="issuesByStep.get(step.stepName)?.length">
                  <div class="issue-step">{{ step.stepName }}</div>
                  <div
                    v-for="(it, i) in issuesByStep.get(step.stepName)"
                    :key="`s${i}`"
                    class="issue-row"
                    @click="selectedStepId = step.stepId"
                  >
                    <span class="rule mono">规则 {{ it.rule }}</span>
                    <span class="msg">{{ it.message }}</span>
                  </div>
                </template>
              </li>
            </ul>
          </div>
        </div>

        <!-- ── 右：属性面板 ───────────────────────────────── -->
        <aside class="side-col">
          <!-- 未选中任何东西 -->
          <div v-if="!selectedStep && !selectedEdge" class="panel">
            <div class="panel-head"><h2>属性</h2></div>
            <div class="empty-hint">点一个节点看它的配置；点一条连线可以删除它</div>
          </div>

          <!-- 选中连线 -->
          <div v-else-if="selectedEdge" class="panel">
            <div class="panel-head"><h2>连线</h2></div>
            <div class="kv">
              <span class="k">起点</span>
              <span class="v mono">{{ edgeEndNames.source }}</span>
              <span class="k">终点</span>
              <span class="v mono">{{ edgeEndNames.target }}</span>
            </div>
            <el-button
              v-if="canEdit"
              size="small"
              style="margin-top: 12px"
              @click="selectedEdgeIndex !== null && removeEdge(selectedEdgeIndex)"
            >
              删除这条连线
            </el-button>
          </div>

          <!-- 选中步骤 -->
          <template v-else-if="selectedStep">
            <div class="panel">
              <div class="panel-head">
                <h2>步骤</h2>
                <span class="sub mono">{{ selectedStep.stepId }}</span>
              </div>
              <div
                v-for="w in warningsByStepId.get(selectedStep.stepId) ?? []"
                :key="w"
                class="warn-box"
              >
                ⚠ {{ w }}
              </div>

              <el-form label-position="top" :disabled="!canEdit">
                <el-row :gutter="10">
                  <el-col :span="12">
                    <el-form-item label="步骤名（变量引用的键）">
                      <el-input
                        v-model="selectedStep.stepName"
                        size="small"
                        maxlength="128"
                        @change="touch()"
                      />
                    </el-form-item>
                  </el-col>
                  <el-col :span="12">
                    <el-form-item label="类型">
                      <el-select v-model="selectedStep.stepType" size="small" style="width: 100%" @change="touch()">
                        <el-option v-for="t in STEP_TYPES" :key="t" :label="STEP_TYPE_LABEL[t]" :value="t" />
                      </el-select>
                    </el-form-item>
                  </el-col>
                </el-row>
                <el-form-item label="描述">
                  <el-input
                    v-model="selectedStep.description as string"
                    type="textarea"
                    :rows="2"
                    size="small"
                    @change="touch()"
                  />
                </el-form-item>

                <template v-if="selectedStep.stepType === 'TASK'">
                  <el-form-item label="算子">
                    <el-select
                      :model-value="selectedStep.operatorId ?? ''"
                      filterable
                      clearable
                      size="small"
                      style="width: 100%"
                      placeholder="选择算子"
                      @change="(v: string) => onOperatorPick(v)"
                    >
                      <el-option
                        v-for="o in operators"
                        :key="o.operatorId"
                        :label="`${o.operatorName}（${o.operatorId}）`"
                        :value="o.operatorId"
                      />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="算子版本">
                    <el-select
                      v-model="selectedStep.operatorVersionId"
                      size="small"
                      style="width: 100%"
                      placeholder="选择版本"
                      :disabled="!selectedStep.operatorId"
                      @change="touch()"
                    >
                      <el-option
                        v-for="v in selectedStep.operatorId ? operatorVersions[selectedStep.operatorId] ?? [] : []"
                        :key="v.versionId"
                        :label="`${v.versionNo} · ${v.publishStatus}${v.isDefaultVersion ? ' · 默认' : ''}`"
                        :value="v.versionId"
                      />
                    </el-select>
                    <!-- 规则 7 的成因提前说清：这里选了非 PUBLISHED 的版本，发布必然 42218 -->
                    <div v-if="selectedVersion && selectedVersion.publishStatus !== 'PUBLISHED'" class="form-hint">
                      该版本当前是 <strong>{{ selectedVersion.publishStatus }}</strong>：
                      发布时会因「引用了未发布的算子版本」被拒（42218 规则 7）。
                    </div>
                  </el-form-item>

                  <div v-if="paramDefs.length" class="params-title">算子参数（{{ paramDefs.length }}）</div>
                  <el-form-item v-for="def in paramDefs" :key="def.paramKey">
                    <template #label>
                      <span class="param-label">
                        {{ def.name }}
                        <em v-if="def.required" class="req">*</em>
                        <span class="mono key">{{ def.paramKey }}</span>
                        <ToneChip v-if="def.sensitive" tone="warn" label="敏感" />
                        <ToneChip v-if="!def.runtimeOverridable" tone="mut" label="不可覆盖" />
                      </span>
                    </template>
                    <el-select
                      v-if="def.paramType === 'SINGLE'"
                      :model-value="paramText(selectedStep, def)"
                      size="small"
                      filterable
                      allow-create
                      clearable
                      style="width: 100%"
                      :disabled="!def.runtimeOverridable"
                      :placeholder="def.defaultValue ? `默认 ${def.defaultValue}` : '选择或直接输入'"
                      @change="(v: string) => setParam(def, v)"
                    >
                      <el-option v-for="opt in def.options ?? []" :key="opt" :label="opt" :value="opt" />
                    </el-select>
                    <el-switch
                      v-else-if="def.paramType === 'BOOLEAN'"
                      :model-value="paramValue(selectedStep, def) === true"
                      :disabled="!def.runtimeOverridable"
                      @change="(v: boolean) => setParam(def, v)"
                    />
                    <!--
                      NUMBER 也用文本输入：参数值允许是变量引用（`${step.A.output.rows}`），
                      el-input-number 会把引用串当场吃掉。数字在 setParam 里收敛。
                    -->
                    <el-input
                      v-else
                      :model-value="paramText(selectedStep, def)"
                      size="small"
                      :type="def.sensitive ? 'password' : 'text'"
                      :show-password="def.sensitive"
                      :disabled="!def.runtimeOverridable"
                      :placeholder="
                        def.defaultValue
                          ? `默认 ${def.sensitive ? '***' : def.defaultValue}`
                          : def.help ?? '留空则按继承链取值'
                      "
                      @change="(v: string) => setParam(def, v)"
                    />
                    <div class="form-hint">
                      <span class="mono">{{ def.paramKey }}</span> · {{ paramTypeLabel(def.paramType) }}
                      <template v-if="def.help"> · {{ def.help }}</template>
                      <template v-if="def.sensitive"> · 快照与日志中会打码（M-07）</template>
                    </div>
                  </el-form-item>
                  <!-- 独立成一条 v-if（而不是接在 v-for 后面的 v-else）：v-else 只能接
                       v-if / v-else-if，接在 v-for 上会被 vue/valid-v-else 拦下 —— 它确实也说不清"否则"指哪一项 -->
                  <div v-if="paramDefs.length === 0" class="form-hint">
                    该算子版本没有参数模板。参数值可以直接写在自定义参数里（下方 JSON）。
                  </div>

                  <div class="params-title">运行约束</div>
                  <el-row :gutter="10">
                    <el-col :span="12">
                      <el-form-item label="目标集群">
                        <el-select
                          :model-value="selectedStep.targetClusterId ?? ''"
                          size="small"
                          clearable
                          filterable
                          style="width: 100%"
                          placeholder="不限"
                          @change="(v: string) => onClusterPick(v)"
                        >
                          <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
                        </el-select>
                      </el-form-item>
                    </el-col>
                    <el-col :span="12">
                      <el-form-item label="目标队列">
                        <el-select
                          v-model="selectedStep.targetQueueId"
                          size="small"
                          clearable
                          style="width: 100%"
                          placeholder="不限"
                          :disabled="!selectedStep.targetClusterId"
                          @change="touch()"
                        >
                          <el-option v-for="q in queueOptions" :key="q.queueId" :label="q.queueName" :value="q.queueId" />
                        </el-select>
                      </el-form-item>
                    </el-col>
                  </el-row>
                  <el-row :gutter="10">
                    <el-col :span="12">
                      <el-form-item label="操作系统约束">
                        <el-select
                          v-model="selectedStep.osConstraint"
                          size="small"
                          clearable
                          style="width: 100%"
                          placeholder="不限"
                          @change="touch()"
                        >
                          <el-option v-for="o in OS_TYPES" :key="o" :label="osLabel(o)" :value="o" />
                        </el-select>
                      </el-form-item>
                    </el-col>
                    <el-col :span="12">
                      <el-form-item label="标签约束（回车添加）">
                        <el-select
                          :model-value="selectedStep.tagConstraint ?? []"
                          size="small"
                          multiple
                          filterable
                          allow-create
                          default-first-option
                          style="width: 100%"
                          placeholder="如 gpu / ssd"
                          @change="(v: string[]) => onTagsChange(v)"
                        />
                      </el-form-item>
                    </el-col>
                  </el-row>
                  <el-row :gutter="10">
                    <el-col :span="6">
                      <el-form-item label="CPU">
                        <el-input-number v-model="selectedStep.cpu as number" size="small" :min="0" style="width: 100%" @change="touch()" />
                      </el-form-item>
                    </el-col>
                    <el-col :span="6">
                      <el-form-item label="GPU">
                        <el-input-number v-model="selectedStep.gpu as number" size="small" :min="0" style="width: 100%" @change="touch()" />
                      </el-form-item>
                    </el-col>
                    <el-col :span="6">
                      <el-form-item label="内存(MB)">
                        <el-input-number v-model="selectedStep.memory as number" size="small" :min="0" style="width: 100%" @change="touch()" />
                      </el-form-item>
                    </el-col>
                    <el-col :span="6">
                      <el-form-item label="磁盘(MB)">
                        <el-input-number v-model="selectedStep.disk as number" size="small" :min="0" style="width: 100%" @change="touch()" />
                      </el-form-item>
                    </el-col>
                  </el-row>
                  <el-row :gutter="10">
                    <el-col :span="8">
                      <el-form-item label="超时(秒)">
                        <el-input-number
                          v-model="selectedStep.timeoutSeconds as number"
                          size="small"
                          :min="1"
                          style="width: 100%"
                          :placeholder="selectedVersion?.defaultTimeoutSeconds ? `继承 ${selectedVersion.defaultTimeoutSeconds}` : '继承工作流默认'"
                          @change="touch()"
                        />
                      </el-form-item>
                    </el-col>
                    <el-col :span="8">
                      <el-form-item label="重试次数（≤10）">
                        <el-input-number
                          v-model="selectedStep.retryCount as number"
                          size="small"
                          :min="0"
                          :max="10"
                          style="width: 100%"
                          :placeholder="selectedVersion?.defaultRetryCount ? `继承 ${selectedVersion.defaultRetryCount}` : '继承工作流默认'"
                          @change="touch()"
                        />
                      </el-form-item>
                    </el-col>
                    <el-col :span="8">
                      <el-form-item label="重试间隔(秒)">
                        <el-input-number
                          v-model="selectedStep.retryIntervalSeconds as number"
                          size="small"
                          :min="0"
                          style="width: 100%"
                          @change="touch()"
                        />
                      </el-form-item>
                    </el-col>
                  </el-row>
                  <el-row :gutter="10">
                    <el-col :span="12">
                      <el-form-item label="失败策略">
                        <el-select
                          v-model="selectedStep.failureStrategy"
                          size="small"
                          clearable
                          style="width: 100%"
                          placeholder="继承工作流默认"
                          @change="touch()"
                        >
                          <el-option
                            v-for="f in FAILURE_STRATEGIES"
                            :key="f"
                            :label="FAILURE_STRATEGY_LABEL[f]"
                            :value="f"
                          />
                        </el-select>
                      </el-form-item>
                    </el-col>
                    <el-col :span="12">
                      <el-form-item label="互斥组">
                        <el-input
                          v-model="selectedStep.mutexGroup as string"
                          size="small"
                          maxlength="128"
                          placeholder="同组步骤不并发"
                          @change="touch()"
                        />
                      </el-form-item>
                    </el-col>
                  </el-row>
                </template>

                <el-form-item label="自定义参数（JSON 对象）">
                  <el-input
                    v-model="customParamsText"
                    type="textarea"
                    :rows="3"
                    size="small"
                    spellcheck="false"
                    @change="applyCustomParams"
                  />
                  <div v-if="customParamsError" class="form-hint error">JSON 不合法：{{ customParamsError }}</div>
                  <div v-else class="form-hint">
                    不来自算子模板的那部分参数。键是用户数据，不做 camel/snake 转换（§5-13）。
                  </div>
                </el-form-item>
              </el-form>
            </div>

            <!-- ── 变量引用插入器 ───────────────────────────── -->
            <div class="panel">
              <div class="panel-head">
                <h2>变量引用</h2>
                <span class="sub">{{ refCandidates.length }} 个可用上游输出</span>
              </div>
              <div class="form-hint">
                语法 <code>{{ previewRef }}</code>（D-20）。只列<strong>可达上游</strong>的输出 ——
                引用下游或自身会被规则 4 判为无效（42214）。
              </div>
              <div v-if="refCandidates.length" class="ref-list">
                <div v-for="c in refCandidates" :key="c.ref" class="ref-item">
                  <code class="ref">{{ c.ref }}</code>
                  <el-button text size="small" @click="copyRef(c.ref)">复制</el-button>
                </div>
              </div>
              <div v-else class="empty-hint">
                上游还没有声明输出的步骤。算子的输出声明在「算子版本」页维护。
              </div>
              <div class="ref-sources">
                <span class="sub">其他来源（一期不做可达性判定，运行时从覆盖链取值）：</span>
                <span v-for="s in REF_SOURCES.slice(1)" :key="s.prefix" class="src">
                  <code>{{ '${' + s.prefix + '.…}' }}</code>
                  <span class="sub">{{ s.label }}</span>
                </span>
              </div>
            </div>
          </template>

          <!-- ── 工作流参数（整份 JSON）───────────────────── -->
          <div v-if="canEdit" class="panel">
            <div class="panel-head">
              <h2>工作流参数</h2>
              <span class="sub">变量覆盖链第 3 层</span>
            </div>
            <el-input
              v-model="workflowParamsText"
              type="textarea"
              :rows="5"
              spellcheck="false"
              @change="touch()"
            />
            <div v-if="workflowParams.error" class="form-hint error">JSON 不合法：{{ workflowParams.error }}</div>
            <div v-else class="form-hint">
              <code>workflow_params</code> 是 jsonb 数组，但 PRD / docs <strong>没有定义元素的字段结构</strong>
              （只说明它是覆盖链第 3 层），所以这里只做原样 JSON 编辑，不自造 schema（O-42）。
            </div>
          </div>

          <div class="panel">
            <div class="panel-head"><h2>版本</h2></div>
            <div class="kv">
              <span class="k">版本号</span><span class="v mono">{{ version?.versionNo ?? '—' }}</span>
              <span class="k">状态</span><span class="v">{{ version ? statusLabel(version.publishStatus) : '—' }}</span>
              <span class="k">步骤数</span><span class="v mono">{{ version?.stepCount ?? 0 }}</span>
              <span class="k">发布人</span><span class="v">{{ version?.publisher ?? '—' }}</span>
              <span class="k">发布时间</span>
              <span class="v mono">{{ version?.publishedAt ? formatDateTime(version.publishedAt) : '未发布' }}</span>
            </div>
          </div>
        </aside>
      </div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.ver {
  margin-left: 8px;
  font-size: 13px;
  font-weight: 400;
  color: var(--t3);
}

.key-hint {
  margin-left: 6px;
  font-size: 10px;
  font-style: normal;
  color: var(--t4);
}

.readonly-banner {
  margin-bottom: 12px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--t2);
}

.guide {
  font-size: 13px;
  line-height: 1.9;
  color: var(--t2);
}

.guide p {
  margin: 0 0 10px;
}

.guide-form {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 14px;
}

/* ── 主体两栏 ── */
.editor-body {
  display: grid;
  grid-template-columns: minmax(520px, 1fr) minmax(360px, 420px);
  gap: 14px;
  align-items: start;
}

@media (max-width: 1280px) {
  .editor-body {
    grid-template-columns: 1fr;
  }
}

.canvas-col {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

.toolbar .sub {
  font-size: 11px;
  color: var(--t3);
}

/*
  画布给固定高度而不是 height:100%：
  F-8（原型里画布被压到 140px）的根因就是"让 flex 容器去决定画布高度"。
  固定 640px + 外层滚动（.content 已经是 overflow-y:auto）没有这个风险。
*/
.canvas-host {
  height: 640px;
}

/* ── 校验 ── */
.issues-panel {
  min-height: 90px;
}

.panel-head {
  display: flex;
  gap: 10px;
  align-items: baseline;
  margin-bottom: 10px;
}

.panel-head h2 {
  margin: 0;
  font-size: 14px;
}

.panel-head .sub {
  font-size: 11px;
  color: var(--t3);
}

.issue-list {
  margin: 0;
  padding-left: 0;
  list-style: none;
}

.issue-list li {
  padding: 5px 0;
  font-size: 12.5px;
  border-bottom: 1px solid var(--line-faint);
}

.issue-list li:last-child {
  border-bottom: 0;
}

.issue-step {
  margin-top: 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--t1);
}

.issue-row {
  display: flex;
  gap: 8px;
  align-items: baseline;
  cursor: pointer;
}

.issue-row:hover .msg {
  color: var(--pri-hi);
}

.rule {
  flex: none;
  padding: 1px 6px;
  font-size: 10.5px;
  color: var(--warn);
  background: var(--warn-dim);
  border-radius: var(--r-full);
}

.msg {
  color: var(--t2);
}

/* ── 右栏 ── */
.side-col {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

.params-title {
  margin: 12px 0 6px;
  font-size: 11.5px;
  color: var(--t3);
}

.param-label {
  display: inline-flex;
  gap: 6px;
  align-items: center;
}

.req {
  font-style: normal;
  color: var(--fail);
}

.key {
  font-size: 10.5px;
  color: var(--t3);
}

.kv {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.kv .k {
  min-width: 62px;
  color: var(--t3);
}

.kv .v {
  flex: 1;
  word-break: break-all;
}

.warn-box {
  padding: 7px 10px;
  margin-bottom: 10px;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--warn);
  background: var(--warn-dim);
  border-radius: var(--r-sm);
}

.ref-list {
  max-height: 220px;
  margin-top: 10px;
  overflow-y: auto;
}

.ref-item {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  padding: 3px 0;
}

.ref {
  overflow: hidden;
  font-family: var(--mono);
  font-size: 11.5px;
  color: var(--t2);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ref-sources {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--line-faint);
}

.ref-sources .src {
  display: flex;
  gap: 4px;
  align-items: baseline;
}

.form-hint {
  padding: 8px 10px;
  margin-top: 6px;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--t3);
  background: var(--bg-inset);
  border: 1px solid var(--line-faint);
  border-radius: var(--r-sm);
}

.form-hint.error {
  color: var(--fail);
  border-color: var(--fail-line);
  background: var(--fail-dim);
}

.form-hint code {
  color: var(--t1);
}

.empty-hint {
  padding: 22px;
  color: var(--t3);
  text-align: center;
}
</style>
