<script setup lang="ts">
/**
 * 自研 SVG 画布（D-14）。
 *
 * ═══ 为什么是自研而不是 X6 / LogicFlow（docs/04 §7.3）═══
 * ① 视觉一致性：本项目的发光/渐变/dim 底是自定义 CSS 令牌，图库内置节点样式
 *    改造成本高于重写；② 依赖体积与无 CDN 约束（内网部署）；③ 节点内要嵌 Vue 组件
 *    （状态角标、错误标记），图库的自定义节点 API 反而绕。
 * 纯 SVG + pointer 事件在"单工作流 ≤ 100 节点"这个规模上完全够用。
 *
 * ═══ 这个组件的边界（它不持业务状态）═══
 * 画布只做三件事：**把图按坐标画出来**、**把指针操作翻译成结构化事件**、
 * **按可选的标记数据做视觉提示**。它不知道"版本能不能改"（那是 `readonly` 这个布尔）、
 * 不知道"这个节点为什么报错"（只拿到 `issuesByStep` 这个 Map 并按条数画角标）。
 * 好处是：所有"图的语义"都在 `utils/dag.ts` 的纯函数里，可以用 vitest 逐条断言；
 * 画布本身只剩渲染与手势，也就是最难测、最需要人眼的那部分被压到最小。
 *
 * ═══ 与原型的关系 ═══
 * 几何与手势沿用 `prototype/workflow-editor.html`（四向锚点 + 智能选边 + 三次贝塞尔），
 * 但**去掉了原型里的两样东西**，理由是它们会变成"看起来能改、其实存不下来"：
 * ① 连线中点拖拽调曲率 —— `DagEdgeDef` 只有 `source/target` 两个字段（CONTRACT §6.2），
 *    没有 bend 的位置，存完就丢；
 * ② CONFIG（中间件配置）节点 —— `workflow_step.step_type` 的 CHECK 只有 `TASK|NOTE`
 *    （docs/05 §3.4），画出来也落不了库。
 * 宁可不画，也不给一个"改了白改"的控件。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { DagEdgeDef, DagStepDef } from '@/api/types/workflow'
import { GRID, canConnect, hasPath, nodeSize, type DagIssue } from '@/utils/dag'

/** 同一个页面可能挂多个画布实例（当前不会，但 id 冲突是静默失效，直接防掉）。 */
let seq = 0
const uid = `dag${(seq += 1)}`
const GRID_ID = `${uid}-grid`
const ARROW_ID = `${uid}-arrow`
const ARROW_BAD_ID = `${uid}-arrow-bad`

const props = withDefaults(
  defineProps<{
    steps: DagStepDef[]
    edges: DagEdgeDef[]
    selectedStepId?: string | null
    /** 选中的连线下标（`props.edges` 里的位置）；未选中为 null */
    selectedEdgeIndex?: number | null
    /** 步骤名 → 待修正项（客户端自检 + 服务端 errors[] 合并后的结果） */
    issuesByStep?: Map<string, DagIssue[]>
    /** 步骤 id → 提示标签（如「未选择算子」「算子版本未发布」）；画布只负责画 */
    warningsByStepId?: Map<string, string[]>
    /** 算子业务编号 → 名称（节点上显示名字比显示 OP-0002 有用得多） */
    operatorNames?: Record<string, string>
    /** 只读：已发布版本只能看（PRD §7.2-5 版本不可变） */
    readonly?: boolean
  }>(),
  {
    selectedStepId: null,
    selectedEdgeIndex: null,
    issuesByStep: () => new Map(),
    warningsByStepId: () => new Map(),
    operatorNames: () => ({}),
    readonly: false,
  },
)

const emit = defineEmits<{
  (e: 'select-step', stepId: string | null): void
  (e: 'select-edge', index: number | null): void
  (e: 'move-step', payload: { stepId: string; posX: number; posY: number }): void
  (e: 'connect', payload: { sourceStepId: string; targetStepId: string }): void
  /** 连线被守卫拒绝（自环/重复/成环/备注节点）；由页面决定怎么提示用户 */
  (e: 'reject-connect', reason: string): void
  (e: 'add-step', payload: { posX: number; posY: number }): void
  (e: 'remove-edge', index: number): void
}>()

// ═══════════════════════════════════════════════════════════
// 视图变换（平移 + 缩放）
// ═══════════════════════════════════════════════════════════

const svgRef = ref<SVGSVGElement | null>(null)
/** 视图：`world` 组的变换 = translate(x, y) scale(k)，k=1 表示 100%。 */
const view = ref({ x: 30, y: 20, k: 1 })
const MIN_K = 0.35
const MAX_K = 2

const worldTransform = computed(() => `translate(${view.value.x},${view.value.y}) scale(${view.value.k})`)

const toWorld = (clientX: number, clientY: number): { x: number; y: number } => {
  const rect = svgRef.value?.getBoundingClientRect()
  if (!rect) return { x: 0, y: 0 }
  return {
    x: (clientX - rect.left - view.value.x) / view.value.k,
    y: (clientY - rect.top - view.value.y) / view.value.k,
  }
}

/** 「适应画布」：把所有节点框进视口。空图时回到左上角原点。 */
function fitView(): void {
  const rect = svgRef.value?.getBoundingClientRect()
  if (!rect || rect.width === 0) return
  if (props.steps.length === 0) {
    view.value = { x: 30, y: 20, k: 1 }
    return
  }
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  for (const s of props.steps) {
    const { x, y } = pos(s)
    const { w, h } = nodeSize(s)
    minX = Math.min(minX, x)
    minY = Math.min(minY, y)
    maxX = Math.max(maxX, x + w)
    maxY = Math.max(maxY, y + h)
  }
  const pad = 56
  const spanX = maxX - minX + pad * 2
  const spanY = maxY - minY + pad * 2
  // 不放大（k ≤ 1）：一张 3 个节点的图被拉到 300% 只会让人以为节点变大了
  const k = Math.min(1, rect.width / spanX, rect.height / spanY)
  view.value = {
    k: Math.max(MIN_K, k),
    x: (rect.width - spanX * Math.max(MIN_K, k)) / 2 - (minX - pad) * Math.max(MIN_K, k),
    y: (rect.height - spanY * Math.max(MIN_K, k)) / 2 - (minY - pad) * Math.max(MIN_K, k),
  }
}

/** 首次拿到非空图时自动适应一次；之后交给用户（不再抢他的视口）。 */
const fitted = ref(false)
watch(
  () => props.steps.length,
  (n) => {
    if (n > 0 && !fitted.value) {
      fitted.value = true
      requestAnimationFrame(fitView)
    }
  },
)

function zoomBy(factor: number, anchor?: { x: number; y: number }): void {
  const rect = svgRef.value?.getBoundingClientRect()
  const k = Math.min(MAX_K, Math.max(MIN_K, view.value.k * factor))
  if (!rect || !anchor) {
    view.value = { ...view.value, k }
    return
  }
  // 以鼠标所在点为中心缩放：先记下该点的世界坐标，缩放后把它挪回原处
  const world = toWorld(anchor.x, anchor.y)
  view.value = {
    k,
    x: anchor.x - rect.left - world.x * k,
    y: anchor.y - rect.top - world.y * k,
  }
}

function onWheel(e: WheelEvent): void {
  // 滚轮缩放而不是滚动页面：画布区是固定高度的，页面本身没有可滚动内容；
  // 需要平移时拖空白即可（与原型一致）
  zoomBy(e.deltaY < 0 ? 1.12 : 1 / 1.12, { x: e.clientX, y: e.clientY })
}

/** 供页面工具栏调用（页面通过模板 ref 拿到）。 */
defineExpose({ fitView, zoomIn: () => zoomBy(1.2), zoomOut: () => zoomBy(1 / 1.2) })

// ═══════════════════════════════════════════════════════════
// 节点几何
// ═══════════════════════════════════════════════════════════

/** 拖动中的临时坐标：只在画布内生效，抬手时才 emit 给页面。 */
const dragPos = ref<{ stepId: string; x: number; y: number } | null>(null)

/**
 * 节点的**有效**坐标（拖动中用临时坐标覆盖）。
 *
 * 【为什么要这份临时状态】拖动过程中每次 pointermove 都 emit 给页面，
 * 页面更新 `steps` 数组 → 整张图重新渲染。68 个节点时这一帧要重建 68 个
 * `foreignObject`，手感会明显发涩。临时覆盖只让**被拖的那个节点**重渲染，
 * 抬手时一次提交，页面状态也不会产生一串中间快照。
 */
function pos(step: DagStepDef): { x: number; y: number } {
  if (dragPos.value && dragPos.value.stepId === step.stepId) {
    return { x: dragPos.value.x, y: dragPos.value.y }
  }
  return { x: step.posX ?? 0, y: step.posY ?? 0 }
}

interface Rect {
  x: number
  y: number
  w: number
  h: number
}

function rectOf(step: DagStepDef): Rect {
  const { x, y } = pos(step)
  const { w, h } = nodeSize(step)
  return { x, y, w, h }
}

type Side = 'l' | 'r' | 't' | 'b'

function anchor(rect: Rect, side: Side): [number, number] {
  if (side === 'r') return [rect.x + rect.w, rect.y + rect.h / 2]
  if (side === 'l') return [rect.x, rect.y + rect.h / 2]
  if (side === 'b') return [rect.x + rect.w / 2, rect.y + rect.h]
  return [rect.x + rect.w / 2, rect.y]
}

/**
 * 智能选边：按两节点的相对位置挑出口/入口。
 *
 * 【为什么不是"右出左进"】正上方/正下方的节点用左右两侧接，线会绕一大圈，
 * 视觉上分不清优先关系。按主方向选边（dx 主导就左右、dy 主导就上下）才读得出流向。
 */
function pickSides(a: Rect, b: Rect): { from: Side; to: Side } {
  const dx = b.x + b.w / 2 - (a.x + a.w / 2)
  const dy = b.y + b.h / 2 - (a.y + a.h / 2)
  if (Math.abs(dx) >= Math.abs(dy)) {
    return dx >= 0 ? { from: 'r', to: 'l' } : { from: 'l', to: 'r' }
  }
  return dy >= 0 ? { from: 'b', to: 't' } : { from: 't', to: 'b' }
}

const DIR: Record<Side, [number, number]> = { l: [-1, 0], r: [1, 0], t: [0, -1], b: [0, 1] }

/** 三次贝塞尔路径：控制点沿出发/到达方向各伸出一段，得到"从端口平顺钻出来"的观感。 */
function bezier(p1: [number, number], p2: [number, number], from: Side, to: Side): string {
  const dist = Math.max(36, Math.hypot(p2[0] - p1[0], p2[1] - p1[1]) / 2)
  const c1: [number, number] = [p1[0] + DIR[from][0] * dist, p1[1] + DIR[from][1] * dist]
  const c2: [number, number] = [p2[0] + DIR[to][0] * dist, p2[1] + DIR[to][1] * dist]
  return `M${p1[0]} ${p1[1]} C${c1[0]} ${c1[1]} ${c2[0]} ${c2[1]} ${p2[0]} ${p2[1]}`
}

const stepById = computed(() => new Map(props.steps.map((s) => [s.stepId, s])))

interface EdgeView {
  index: number
  d: string
  /** 环上的线：高亮成失败色，用户在画布上直接看到"这条线不该在"（规则 5） */
  bad: boolean
  selected: boolean
}

/**
 * 连线视图。**环检测按边逐个算**（`hasPath(to, from)` 为真即该边在环上）——
 * 与服务端 `findCycleEdges` 的口径一致，命中时把线画成失败色，
 * 免得用户对着"检测到循环依赖：A → B → A"的提示去猜是哪条线。
 */
const edgeViews = computed<EdgeView[]>(() =>
  props.edges.map((e, index) => {
    const a = stepById.value.get(e.sourceStepId)
    const b = stepById.value.get(e.targetStepId)
    if (!a || !b) {
      // 悬挂连线（数据不一致）：不画。服务端读回时也会跳过它（readGraph 的兜底）
      return { index, d: '', bad: false, selected: false }
    }
    const sides = pickSides(rectOf(a), rectOf(b))
    return {
      index,
      d: bezier(anchor(rectOf(a), sides.from), anchor(rectOf(b), sides.to), sides.from, sides.to),
      bad: hasPath(props.steps, props.edges, e.targetStepId, e.sourceStepId),
      selected: props.selectedEdgeIndex === index,
    }
  }),
)

/** 正在拉的那条线的预览路径。 */
const linkPreview = computed(() => {
  if (!linkFrom.value || !linkPoint.value) return ''
  const a = stepById.value.get(linkFrom.value)
  if (!a) return ''
  const rect = rectOf(a)
  const target: Rect = { x: linkPoint.value.x, y: linkPoint.value.y, w: 1, h: 1 }
  const sides = pickSides(rect, target)
  return bezier(anchor(rect, sides.from), [linkPoint.value.x, linkPoint.value.y], sides.from, sides.to)
})

// ═══════════════════════════════════════════════════════════
// 指针手势（拖动节点 / 拉线 / 平移画布）
// ═══════════════════════════════════════════════════════════

interface DragState {
  stepId: string
  clientX: number
  clientY: number
  originX: number
  originY: number
}

const drag = ref<DragState | null>(null)
const pan = ref<{ clientX: number; clientY: number; originX: number; originY: number } | null>(null)
const linkFrom = ref<string | null>(null)
const linkPoint = ref<{ x: number; y: number } | null>(null)

/** 吸附到网格：手拖出来的图必然参差，吸附后连线自然横平竖直。 */
const snap = (v: number): number => Math.round(v / GRID) * GRID

function onSvgPointerDown(e: PointerEvent): void {
  // 只有点在背景（网格矩形）上才算"点空白"：点节点/连线时事件被各自处理器接走
  if ((e.target as Element | null)?.classList?.contains('dag-bg')) {
    emit('select-step', null)
    emit('select-edge', null)
    if (!props.readonly) {
      pan.value = {
        clientX: e.clientX,
        clientY: e.clientY,
        originX: view.value.x,
        originY: view.value.y,
      }
    }
  }
}

function onSvgDblClick(e: MouseEvent): void {
  if (props.readonly) return
  const el = e.target as Element | null
  if (!el?.classList?.contains('dag-bg')) return
  const world = toWorld(e.clientX, e.clientY)
  emit('add-step', { posX: snap(world.x), posY: snap(world.y) })
}

function onNodePointerDown(e: PointerEvent, step: DagStepDef): void {
  e.stopPropagation()
  emit('select-step', step.stepId)
  emit('select-edge', null)
  if (props.readonly) return
  const { x, y } = pos(step)
  drag.value = { stepId: step.stepId, clientX: e.clientX, clientY: e.clientY, originX: x, originY: y }
}

/** 单击连线：选中它（页面会显示"删除此连线"的入口，Delete 键也可用）。 */
function selectEdge(index: number): void {
  emit('select-step', null)
  emit('select-edge', index)
}

/** 双击连线：直接删（与原型一致；删线是画布上最高频的修正动作）。 */
function onEdgeDblClick(index: number): void {
  if (props.readonly) return
  emit('remove-edge', index)
}

/** 点输入端口只做选中：拉线一律从"出"端口起，避免出现反向绘制的线。 */
function selectStep(stepId: string): void {
  emit('select-step', stepId)
}

function onPortPointerDown(e: PointerEvent, step: DagStepDef): void {
  e.stopPropagation()
  if (props.readonly) return
  emit('select-step', step.stepId)
  linkFrom.value = step.stepId
  linkPoint.value = toWorld(e.clientX, e.clientY)
}

/** 世界坐标下的命中测试（逆序 = 视觉上层优先）。比 `elementFromPoint` 可靠：不受缩放与 foreignObject 干扰。 */
function stepAt(world: { x: number; y: number }): DagStepDef | null {
  for (let i = props.steps.length - 1; i >= 0; i -= 1) {
    const s = props.steps[i]
    const r = rectOf(s)
    if (world.x >= r.x && world.x <= r.x + r.w && world.y >= r.y && world.y <= r.y + r.h) return s
  }
  return null
}

function onPointerMove(e: PointerEvent): void {
  if (pan.value) {
    view.value = {
      ...view.value,
      x: pan.value.originX + (e.clientX - pan.value.clientX),
      y: pan.value.originY + (e.clientY - pan.value.clientY),
    }
    return
  }
  if (drag.value) {
    const dx = (e.clientX - drag.value.clientX) / view.value.k
    const dy = (e.clientY - drag.value.clientY) / view.value.k
    dragPos.value = {
      stepId: drag.value.stepId,
      x: Math.max(0, snap(drag.value.originX + dx)),
      y: Math.max(0, snap(drag.value.originY + dy)),
    }
    return
  }
  if (linkFrom.value) {
    linkPoint.value = toWorld(e.clientX, e.clientY)
  }
}

function onPointerUp(e: PointerEvent): void {
  if (drag.value && dragPos.value) {
    const { stepId, x, y } = dragPos.value
    const original = stepById.value.get(stepId)
    // 位置没变就不提交：一次点击也会走完 down→up，白 emit 会污染脏状态判定
    if (original && ((original.posX ?? 0) !== x || (original.posY ?? 0) !== y)) {
      emit('move-step', { stepId, posX: x, posY: y })
    }
  }
  if (linkFrom.value) {
    const target = stepAt(toWorld(e.clientX, e.clientY))
    const source = linkFrom.value
    if (target && target.stepId !== source) {
      // 守卫放在画布内：拖拽的"即时反馈"必须当场发生，等页面回一趟再报错就已经抬手了。
      // 页面侧不再重复校验 —— 用户绕不过这个 emit（没有其他建线入口）
      const verdict = canConnect(props.steps, props.edges, source, target.stepId)
      if (verdict.ok) {
        emit('connect', { sourceStepId: source, targetStepId: target.stepId })
      } else if (verdict.reason) {
        emit('reject-connect', verdict.reason)
      }
    }
  }
  drag.value = null
  dragPos.value = null
  pan.value = null
  linkFrom.value = null
  linkPoint.value = null
}

onMounted(() => {
  // 挂到 window 而不是元素上：指针拖出画布再松开（甚至移出浏览器）时也要收到 up，
  // 否则会留下一个"永远在拖"的幽灵状态
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp)
})
onBeforeUnmount(() => {
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
})

// ═══════════════════════════════════════════════════════════
// 节点外观
// ═══════════════════════════════════════════════════════════

function issuesOf(step: DagStepDef): DagIssue[] {
  return props.issuesByStep.get(step.stepName) ?? []
}

function warningsOf(step: DagStepDef): string[] {
  return props.warningsByStepId.get(step.stepId) ?? []
}

/** 节点上的元信息小片：把"这个步骤跑起来要什么"压缩到一眼可见。 */
function chipsOf(step: DagStepDef): string[] {
  const chips: string[] = []
  if (step.cpu) chips.push(`CPU ${step.cpu}`)
  if (step.gpu) chips.push(`GPU ${step.gpu}`)
  if (step.memory) chips.push(`内存 ${step.memory}MB`)
  if (step.timeoutSeconds) chips.push(`超时 ${step.timeoutSeconds}s`)
  if (step.retryCount) chips.push(`重试 ${step.retryCount}`)
  if (step.mutexGroup) chips.push(`互斥 ${step.mutexGroup}`)
  for (const t of step.tagConstraint ?? []) chips.push(`#${t}`)
  return chips.slice(0, 3)
}

function operatorText(step: DagStepDef): string {
  if (step.stepType === 'NOTE') return step.description || '备注'
  if (!step.operatorId) return '未选择算子'
  const name = props.operatorNames[step.operatorId] ?? step.operatorId
  return `${name} · ${step.operatorVersionId ?? '未选版本'}`
}
</script>

<template>
  <div class="canvas-wrap">
    <svg
      ref="svgRef"
      class="dag-svg"
      @wheel.prevent="onWheel"
      @pointerdown="onSvgPointerDown"
      @dblclick="onSvgDblClick"
    >
      <defs>
        <pattern :id="GRID_ID" :width="GRID" :height="GRID" patternUnits="userSpaceOnUse">
          <path class="grid-line" :d="`M ${GRID} 0 L 0 0 0 ${GRID}`" />
        </pattern>
        <!-- 箭头用双色：环上的线换成失败色，一眼看出"这条线在环里" -->
        <marker :id="ARROW_ID" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
          <path class="arrow" d="M0 0L10 5L0 10z" />
        </marker>
        <marker :id="ARROW_BAD_ID" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
          <path class="arrow arrow-bad" d="M0 0L10 5L0 10z" />
        </marker>
      </defs>

      <rect class="dag-bg" x="0" y="0" width="100%" height="100%" :fill="`url(#${GRID_ID})`" />

      <g :transform="worldTransform">
        <!-- ── 连线层：先画线再画节点，节点自然盖住线头 ── -->
        <g class="edge-layer">
          <template v-for="ev in edgeViews" :key="`e${ev.index}`">
            <template v-if="ev.d">
              <path
                class="edge-hit"
                :d="ev.d"
                @pointerdown.stop="selectEdge(ev.index)"
                @dblclick.stop="onEdgeDblClick(ev.index)"
              />
              <path
                class="edge-line"
                :class="{ bad: ev.bad, sel: ev.selected }"
                :d="ev.d"
                :marker-end="`url(#${ev.bad ? ARROW_BAD_ID : ARROW_ID})`"
              />
            </template>
          </template>
          <path v-if="linkPreview" class="edge-line preview" :d="linkPreview" />
        </g>

        <!-- ── 节点层 ── -->
        <g class="node-layer">
          <g
            v-for="step in steps"
            :key="step.stepId"
            :transform="`translate(${pos(step).x},${pos(step).y})`"
            @pointerdown="onNodePointerDown($event, step)"
          >
            <!--
              foreignObject：把节点内容当 HTML 渲染，于是节点里可以放任何普通标记
              （以及 Vue 组件）。Vue 的模板编译器在 `foreignObject` 处**重置命名空间**
              回 HTML（compiler-dom 的 getNamespace），所以里面的 div 是真的 HTML 元素；
              注意标签必须写成 camelCase 的 `foreignObject`，编译器是逐字比对的。
            -->
            <foreignObject x="0" y="0" :width="nodeSize(step).w" :height="nodeSize(step).h">
              <div
                class="dag-node"
                :class="{
                  note: step.stepType === 'NOTE',
                  sel: selectedStepId === step.stepId,
                  err: issuesOf(step).length > 0,
                }"
              >
                <div class="row">
                  <span class="name">{{ step.stepName }}</span>
                  <span v-if="issuesOf(step).length" class="badge err" :title="issuesOf(step).map((i) => i.message).join('\n')">
                    {{ issuesOf(step).length }}
                  </span>
                  <span v-else-if="warningsOf(step).length" class="badge warn" :title="warningsOf(step).join('\n')">
                    !
                  </span>
                </div>
                <div class="op" :class="{ missing: step.stepType === 'TASK' && !step.operatorId }">
                  {{ operatorText(step) }}
                </div>
                <div v-if="chipsOf(step).length" class="meta">
                  <span v-for="c in chipsOf(step)" :key="c" class="chip">{{ c }}</span>
                </div>
              </div>
            </foreignObject>

            <!-- 端口：备注节点不参与执行链路，因此没有端口（见 canConnect 的说明）。
                 端口旁的“hit”圆半径更大，方便点中（视觉 4px、可点 11px） -->
            <template v-if="step.stepType !== 'NOTE' && !readonly">
              <circle
                class="port port-in"
                :cx="0"
                :cy="nodeSize(step).h / 2"
                r="4"
                @pointerdown.stop="selectStep(step.stepId)"
              />
              <circle
                class="port port-out"
                :cx="nodeSize(step).w"
                :cy="nodeSize(step).h / 2"
                r="4.5"
                @pointerdown.stop="onPortPointerDown($event, step)"
              />
              <circle
                class="port-hit"
                :cx="nodeSize(step).w"
                :cy="nodeSize(step).h / 2"
                r="11"
                @pointerdown.stop="onPortPointerDown($event, step)"
              />
            </template>
          </g>
        </g>
      </g>
    </svg>

    <div class="zoom-bar">
      <button title="缩小" @click="zoomBy(1 / 1.2)">−</button>
      <span class="mono k">{{ Math.round(view.k * 100) }}%</span>
      <button title="放大" @click="zoomBy(1.2)">＋</button>
      <button title="适应画布（把所有节点框进视口）" @click="fitView">适应</button>
    </div>

    <div class="canvas-hint">
      拖节点移动 · 从右侧圆点拖到另一个节点连线 · 双击空白新建 · 双击连线删除 · 滚轮缩放 · 拖空白平移
    </div>
  </div>
</template>

<style scoped>
.canvas-wrap {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 420px;
  overflow: hidden;
  background: var(--bg-inset);
  border: 1px solid var(--line-faint);
  border-radius: var(--r);
}

.dag-svg {
  display: block;
  width: 100%;
  height: 100%;
  cursor: grab;
  touch-action: none;
}

.grid-line {
  stroke: var(--line-faint);
  stroke-width: 1;
}

.arrow {
  fill: var(--pri);
}
.arrow-bad {
  fill: var(--fail);
}

.dag-bg {
  /* fill 由属性给 url(#grid)（图案引用只能走属性），这里只管光标 */
  cursor: default;
}

.edge-hit {
  fill: none;
  stroke: transparent;
  stroke-width: 14;
  /* 显式声明 hit 区是"描边"而不是"可见绘制区"：透明描边在某些渲染路径下
     会被排除在命中测试之外，靠 stroke 收窄命中域比靠颜色可靠 */
  pointer-events: stroke;
  cursor: pointer;
}

.edge-line {
  fill: none;
  stroke: var(--pri);
  stroke-width: 1.6;
  pointer-events: none;
}
.edge-line.bad {
  stroke: var(--fail);
  stroke-dasharray: 5 4;
}
.edge-line.sel {
  stroke: var(--pri-hi);
  stroke-width: 2.6;
}
.edge-line.preview {
  stroke: var(--pri-hi);
  stroke-dasharray: 4 4;
}

/* ── 节点 ── */
.dag-node {
  box-sizing: border-box;
  width: 100%;
  height: 100%;
  padding: 9px 11px;
  overflow: hidden;
  background: var(--bg-raise);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  cursor: move;
  user-select: none;
}
.dag-node.sel {
  border-color: var(--pri);
  box-shadow: var(--glow-pri);
}
.dag-node.err {
  border-color: var(--fail-line);
}
.dag-node.note {
  background: var(--bg-panel);
  border-style: dashed;
}
.dag-node .row {
  display: flex;
  gap: 6px;
  align-items: center;
}
.dag-node .name {
  flex: 1;
  overflow: hidden;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--t1);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dag-node .op {
  margin-top: 4px;
  overflow: hidden;
  font-family: var(--mono);
  font-size: 11px;
  color: var(--t2);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dag-node .op.missing {
  color: var(--fail);
}
.dag-node .meta {
  display: flex;
  gap: 4px;
  margin-top: 6px;
  overflow: hidden;
}
.dag-node .chip {
  padding: 1px 5px;
  font-size: 10px;
  color: var(--t3);
  white-space: nowrap;
  background: var(--bg-inset);
  border: 1px solid var(--line-faint);
  border-radius: var(--r-xs);
}
.dag-node .badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  font-size: 10px;
  font-weight: 700;
  border-radius: var(--r-full);
}
.dag-node .badge.err {
  color: var(--bg-void);
  background: var(--fail);
}
.dag-node .badge.warn {
  color: var(--bg-void);
  background: var(--warn);
}

/* ── 端口 ── */
.port {
  fill: var(--bg-void);
  stroke: var(--pri);
  stroke-width: 1.5;
}
.port-in {
  stroke: var(--t3);
}
.port-out {
  cursor: crosshair;
  fill: var(--pri-dim);
}
.port-hit {
  cursor: crosshair;
  fill: transparent;
}

/* ── 浮层 ── */
.zoom-bar {
  position: absolute;
  bottom: 10px;
  left: 10px;
  display: flex;
  gap: 4px;
  align-items: center;
  padding: 4px 6px;
  background: var(--bg-panel);
  border: 1px solid var(--line-faint);
  border-radius: var(--r-full);
}
.zoom-bar button {
  min-width: 24px;
  height: 22px;
  padding: 0 6px;
  font-size: 12px;
  color: var(--t2);
  background: transparent;
  border: 0;
  border-radius: var(--r-xs);
  cursor: pointer;
}
.zoom-bar button:hover {
  color: var(--pri-hi);
  background: var(--bg-hover);
}
.zoom-bar .k {
  min-width: 40px;
  font-size: 11px;
  color: var(--t3);
  text-align: center;
}

.canvas-hint {
  position: absolute;
  bottom: 10px;
  left: 50%;
  padding: 4px 10px;
  font-size: 11px;
  color: var(--t4);
  white-space: nowrap;
  background: var(--bg-panel);
  border: 1px solid var(--line-faint);
  border-radius: var(--r-full);
  transform: translateX(-50%);
  pointer-events: none;
}
</style>
