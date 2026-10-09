/**
 * DAG 画布的纯函数工具（M3 §1.7，配套 D-14 自研 SVG 画布）。
 *
 * 【为什么这些逻辑必须从组件里搬出来】
 * 画布组件里最值钱的部分不是"怎么画"，而是"图上有什么问题"与"哪些连线允许建"。
 * 把它们写进 `.vue` 的 `computed` 里，就只能靠起浏览器点一遍来验证；
 * 写成本文件的纯函数（输入图、输出结论，无 Vue、无 DOM、无请求），
 * 就能用 vitest 逐条规则造用例 —— 与服务端 `DagValidator` 同一条思路。
 *
 * 【客户端自检 ≠ 服务端校验】
 * 本文件只镜像**保存草稿**这一时机跑的规则 **1 / 5 / 10**（docs/07 §9.2）。
 * 它的价值是"用户拖完连线当场看得见问题"，**不是**闸门：
 * 规则 2/3/4/6/7/8/9 要跨域数据（算子发布状态、参数模板、集群上限、并发配置），
 * 客户端拿不到也不该猜，只在**发布**时由服务端全量判定。
 * 所以：自检提示用弱语气（"待修正"），服务端返回的 errors[] 才是硬结论。
 */

import type { DagEdgeDef, DagStepDef, WorkflowVersionItem } from '@/api/types/workflow'

// ═══════════════════════════════════════════════════════════
// 一、几何常量（画布与服务端都只认"格"这一层抽象）
// ═══════════════════════════════════════════════════════════

/**
 * 节点尺寸。
 *
 * 【为什么尺寸是常量而不是"按内容自适应"】连线端点与吸附要按节点边框算，
 * 自适应尺寸会让"拖到哪儿、线接哪儿"变成运行期才知道的事（同一张图两次渲染
 * 可能不一样）。固定尺寸换来的是"布局是纯函数"——`autoLayout` 的输出可断言。
 */
export const NODE_W = 196
export const NODE_H = 78
/** 备注节点更扁：它只是一张便签，占同样高度会挤压主链路。 */
export const NOTE_W = 196
export const NOTE_H = 52

/** 网格步长（对齐背景网格；拖动吸附到它，画出来的图自然对齐）。 */
export const GRID = 20
/** 画布最小尺寸：比视口略大，避免"缩到最小还是拖不到新节点"。 */
export const CANVAS_MIN_W = 1200
export const CANVAS_MIN_H = 720

/** 节点尺寸查表（TASK / NOTE）。 */
export function nodeSize(step: Pick<DagStepDef, 'stepType'>): { w: number; h: number } {
  return step.stepType === 'NOTE' ? { w: NOTE_W, h: NOTE_H } : { w: NODE_W, h: NODE_H }
}

// ═══════════════════════════════════════════════════════════
// 二、结构自检（镜像服务端规则 1 / 5 / 10）
// ═══════════════════════════════════════════════════════════

/**
 * 一条待修正项。字段与服务端 `DagViolation` 逐字对齐（`rule` / `stepName` / `message`）——
 * 于是"客户端自检"与"服务端 errors[]"可以走**同一个渲染函数**，
 * 用户不会看到两套排版，我们也不必维护两份展示逻辑。
 */
export interface DagIssue {
  /** docs/07 §9.2 的规则编号（1~10）；前端只产 1/5/10 */
  rule: string
  /** 出问题的步骤名；工作流级问题为 null */
  stepName: string | null
  message: string
}

/** 邻接表：下标 → 下游下标列表（与服务端 DagValidator 同构，便于逐条对照）。 */
function adjacency(steps: DagStepDef[], edges: DagEdgeDef[]): { out: number[][]; inDegree: number[] } {
  const index = new Map<string, number>()
  steps.forEach((s, i) => index.set(s.stepId, i))
  const out: number[][] = steps.map(() => [])
  const inDegree = steps.map(() => 0)
  for (const e of edges) {
    const from = index.get(e.sourceStepId)
    const to = index.get(e.targetStepId)
    // 悬挂连线（端点不在本图里）**跳过而不是抛错**：它是编辑中途的常态
    // （刚删了节点、边还没清理），服务端也做同样的跳过（DagValidator 的越界分支）
    if (from === undefined || to === undefined) continue
    out[from].push(to)
    inDegree[to] += 1
  }
  return { out, inDegree }
}

/**
 * 结构自检：规则 1（有入口且都可达）、规则 5（无环）、规则 10（步骤名唯一）。
 *
 * **顺序固定 1 → 5 → 10**，与服务端收集顺序一致：同一条错在两端的编号与先后都一样，
 * 用户才不会怀疑自己看的是两套规则。
 */
export function checkStructure(steps: DagStepDef[], edges: DagEdgeDef[]): DagIssue[] {
  const issues: DagIssue[] = []
  const { out, inDegree } = adjacency(steps, edges)
  issues.push(...checkReachability(steps, out, inDegree))
  issues.push(...checkAcyclic(steps, out, inDegree))
  issues.push(...checkStepNameUnique(steps))
  return issues
}

/** 规则 1：至少一个入度 0 的步骤，且所有 TASK 步骤都从它可达。 */
function checkReachability(steps: DagStepDef[], out: number[][], inDegree: number[]): DagIssue[] {
  const n = steps.length
  if (n === 0) {
    // 空图是新建版本的常态（一条线都还没画）。docs/07 §9.2 的 10 条规则**都不覆盖**
    // "一个步骤都没有"，服务端也不报错（登记为 O-23），这里必须保持一致 ——
    // 否则前端会显示一个服务端从不返回的"错误"，用户改不掉也发布不了。
    return []
  }
  const sources = steps.map((_, i) => i).filter((i) => inDegree[i] === 0)
  if (sources.length === 0) {
    return [
      {
        rule: '1',
        stepName: null,
        message: '流程图没有任何入口步骤（每个步骤都有上游），无法确定从哪里开始执行',
      },
    ]
  }
  const reachable = new Set<number>(sources)
  const queue = [...sources]
  while (queue.length) {
    const cur = queue.shift() as number
    for (const next of out[cur]) {
      if (!reachable.has(next)) {
        reachable.add(next)
        queue.push(next)
      }
    }
  }
  const issues: DagIssue[] = []
  steps.forEach((s, i) => {
    // 备注节点不参与执行与终结判定（PRD §10.8 规则 1 明文"备注除外"），
    // 所以它不可达不是问题 —— 服务端同款判断
    if (!reachable.has(i) && s.stepType !== 'NOTE') {
      issues.push({ rule: '1', stepName: s.stepName, message: `步骤「${s.stepName}」不可达` })
    }
  })
  return issues
}

/** 规则 5：无环（Kahn 拓扑排序；未处理的节点就是环及其下游）。 */
function checkAcyclic(steps: DagStepDef[], out: number[][], inDegree: number[]): DagIssue[] {
  const n = steps.length
  const deg = [...inDegree]
  const queue = steps.map((_, i) => i).filter((i) => deg[i] === 0)
  const done = new Set<number>()
  while (queue.length) {
    const cur = queue.shift() as number
    done.add(cur)
    for (const next of out[cur]) {
      deg[next] -= 1
      if (deg[next] === 0) queue.push(next)
    }
  }
  if (done.size === n) return []
  return [{ rule: '5', stepName: null, message: `检测到循环依赖：${renderCycle(steps, out, done)}` }]
}

/**
 * 把环上的路径读成 `A → B → A`。
 *
 * 【为什么非要把路径打出来】只报"存在环"，用户面对 20 个节点要自己找；
 * 给出路径，他就知道该删哪条线。服务端 `DagValidator#renderCycle` 是同一份逻辑。
 */
function renderCycle(steps: DagStepDef[], out: number[][], done: Set<number>): string {
  // done.size < n 保证一定存在"未处理"的下标，故 start >= 0
  const start = steps.findIndex((_, i) => !done.has(i))
  const path: number[] = []
  const visited = new Set<number>()
  let cur = start
  while (cur >= 0 && !visited.has(cur)) {
    visited.add(cur)
    path.push(cur)
    cur = out[cur].find((c) => !done.has(c)) ?? -1
  }
  const names = path.map((i) => steps[i].stepName)
  // 收尾写回起点：环是闭合的，`B → C → B` 才读得懂。
  // （服务端 renderCycle 分了"回到环上"与"没回到"两个分支，但两支取的都是 path[0]，
  //   这里直接写一支，行为一致。）
  return `${names.join(' → ')} → ${names[0]}`
}

/** 规则 10：步骤名唯一（步骤名是变量引用的键，重名会让 `${step.X.output.y}` 无法解释）。 */
function checkStepNameUnique(steps: DagStepDef[]): DagIssue[] {
  const seen = new Map<string, number>()
  for (const s of steps) {
    seen.set(s.stepName, (seen.get(s.stepName) ?? 0) + 1)
  }
  const issues: DagIssue[] = []
  // Map 保持插入顺序 = 首次出现的顺序，与服务端 LinkedHashMap 一致：错误顺序稳定
  seen.forEach((count, name) => {
    if (count > 1) {
      issues.push({ rule: '10', stepName: name, message: `步骤名「${name}」重复` })
    }
  })
  return issues
}

/**
 * 服务端 `errors[]` → 按步骤名归组。
 *
 * 工作流级问题（规则 5、8 等 `stepName` 为 null）单独放 `global` ——
 * 它们没法挂到某个节点上，混进节点列表会让用户以为"是这个节点的问题"。
 */
export function groupIssues(issues: DagIssue[]): {
  byStep: Map<string, DagIssue[]>
  global: DagIssue[]
} {
  const byStep = new Map<string, DagIssue[]>()
  const global: DagIssue[] = []
  for (const issue of issues) {
    if (!issue.stepName) {
      global.push(issue)
      continue
    }
    const list = byStep.get(issue.stepName)
    if (list) list.push(issue)
    else byStep.set(issue.stepName, [issue])
  }
  return { byStep, global }
}

/**
 * 从服务端错误对象里把 `errors[]` 安全地取出来。
 *
 * 形状是**约定的**（`BizException` 的 `data.errors`），不是类型系统能保证的 ——
 * 交付方可能是网关或旧版本后端。取出非数组一律当"没有明细"，
 * 让调用方退回通用文案，而不是渲染出 `undefined`。
 */
export function extractIssues(payload: unknown): DagIssue[] {
  const raw = (payload as { errors?: unknown } | undefined)?.errors
  if (!Array.isArray(raw)) return []
  return raw.map((it) => {
    const rec = it as { rule?: unknown; stepName?: unknown; message?: unknown }
    return {
      rule: String(rec.rule ?? ''),
      stepName: rec.stepName === null || rec.stepName === undefined ? null : String(rec.stepName),
      message: String(rec.message ?? ''),
    }
  })
}

// ═══════════════════════════════════════════════════════════
// 三、连线守卫（"这条线能不能建"）
// ═══════════════════════════════════════════════════════════

/**
 * 判断一条新连线是否可建。
 *
 * 【为什么要在拖拽时先拦一道】把明显非法的线放进去、等保存时被 40001/42213 打回，
 * 用户得到的是"我拖了一下，然后报错"，看不出是哪一步错了。
 * 这里拦的是**三类请求体本身就不合法**的线（服务端 `DagAssembler#indexByKey` 的口径）：
 * 自环、重复连线；成环则是规则 5（42213），在此提前预判同样是为了即时反馈。
 *
 * ⚠️ 拦截不是权威判定：**服务端才是闸门**（同样的三种情况它都会拦住）。
 */
export function canConnect(
  steps: DagStepDef[],
  edges: DagEdgeDef[],
  sourceId: string,
  targetId: string,
): { ok: boolean; reason?: string } {
  if (!sourceId || !targetId) return { ok: false, reason: '连线端点不完整' }
  if (sourceId === targetId) return { ok: false, reason: '不能连到自己' }
  const byId = new Map(steps.map((s) => [s.stepId, s]))
  const source = byId.get(sourceId)
  const target = byId.get(targetId)
  if (!source || !target) return { ok: false, reason: '连线端点已不在画布上' }
  // 备注节点不参与执行链路（PRD §10.8 规则 1"备注除外"），连上去只会得到一条
  // 永远不生效的线 —— 不如直接不让连。已有历史连线仍会照常渲染（见 DagCanvas 注释）
  if (source.stepType === 'NOTE' || target.stepType === 'NOTE') {
    return { ok: false, reason: '备注节点不参与执行链路，不能连线' }
  }
  if (edges.some((e) => e.sourceStepId === sourceId && e.targetStepId === targetId)) {
    return { ok: false, reason: '这两个步骤之间已经有连线了' }
  }
  if (hasPath(steps, edges, targetId, sourceId)) {
    return { ok: false, reason: '这条线会形成环（目标步骤已经是当前步骤的上游）' }
  }
  return { ok: true }
}

/** 从 `fromId` 沿下游能否走到 `toId`（有向可达）。 */
export function hasPath(
  steps: DagStepDef[],
  edges: DagEdgeDef[],
  fromId: string,
  toId: string,
): boolean {
  const { out } = adjacency(steps, edges)
  const index = new Map(steps.map((s, i) => [s.stepId, i]))
  const start = index.get(fromId)
  const goal = index.get(toId)
  if (start === undefined || goal === undefined) return false
  const seen = new Set<number>([start])
  const queue = [start]
  while (queue.length) {
    const cur = queue.shift() as number
    if (cur === goal) return true
    for (const next of out[cur]) {
      if (!seen.has(next)) {
        seen.add(next)
        queue.push(next)
      }
    }
  }
  return false
}

/**
 * 某步骤的全部（传递）上游 —— 变量引用器的数据来源。
 *
 * 【为什么只列上游】`${step.X.output.y}` 只有在 X 已经跑完时才有值，
 * 所以服务端规则 4 会拒绝"引用了不可达的变量"（42214）。插入器里只给可达上游，
 * 用户就没有机会写出那条注定失败的引用。
 */
export function ancestorsOf(steps: DagStepDef[], edges: DagEdgeDef[], stepId: string): Set<string> {
  const { out } = adjacency(steps, edges)
  const index = new Map(steps.map((s, i) => [s.stepId, i]))
  const target = index.get(stepId)
  const result = new Set<string>()
  if (target === undefined) return result
  // 反向遍历：从每个节点出发能到 target 的，都是它的上游
  for (const [id, i] of index) {
    if (i === target) continue
    const seen = new Set<number>([i])
    const queue = [i]
    while (queue.length) {
      const cur = queue.shift() as number
      if (cur === target) {
        result.add(id)
        break
      }
      for (const next of out[cur]) {
        if (!seen.has(next)) {
          seen.add(next)
          queue.push(next)
        }
      }
    }
  }
  return result
}

// ═══════════════════════════════════════════════════════════
// 四、变量引用（D-20 语法）
// ═══════════════════════════════════════════════════════════

/** 合法标识符（与服务端 `VariableRefParser.IDENT` 同一条正则）。 */
const IDENT = /^[a-zA-Z_][a-zA-Z0-9_]*$/
/**
 * 纯中文名。注意是 **`+` 整体匹配**：`清洗2` 既不是标识符也不是"纯中文"，
 * 必须走引号路径 —— 服务端 `VariableRefParser` 用的是同一把尺子（`\p{IsHan}+`）。
 */
const HAN = /^\p{Script=Han}+$/u

/**
 * 渲染一条步骤输出引用。
 *
 * 【为什么要自动加引号】步骤名允许含空格、连字符、中文数字混合，而未加引号的引用
 * 只接受"纯标识符或纯中文"（D-20）。用户在插入器里点一下就能拿到合法写法，
 * 比让他在 42214 的报错里学习语法好得多。
 */
export function renderStepOutputRef(stepName: string, varName: string): string {
  const bare = IDENT.test(stepName) || HAN.test(stepName)
  return bare ? `\${step.${stepName}.output.${varName}}` : `\${step."${stepName}".output.${varName}}`
}

/** 引用来源前缀 —— 与 `VariableRefParser.SOURCES` 一一对应（docs/07 §9.1）。 */
export const REF_SOURCES: { prefix: string; label: string }[] = [
  { prefix: 'step', label: '上游步骤输出' },
  { prefix: 'trigger', label: '触发时参数' },
  { prefix: 'project', label: '项目参数' },
  { prefix: 'platform', label: '平台变量' },
  { prefix: 'task', label: '任务系统量（与平台变量同源）' },
]

/** 插入器的一个候选项。 */
export interface VarCandidate {
  /** 可直接写进参数值的完整引用串 */
  ref: string
  /** 显示名（步骤名 · 变量名） */
  label: string
  /** 归属说明（"上游步骤输出"） */
  hint: string
}

/**
 * 为某个步骤生成可插入的变量引用候选。
 *
 * @param outputDecls 步骤 id → 该步骤算子版本**已声明**的输出变量名。
 *   拿不到声明时不要凭空造变量名 —— 服务端规则 4 对"声明为空集"的算子**跳过**
 *   变量存在性判定，但一旦有声明，引用未声明的变量就是 42214。
 */
export function variableCandidates(
  steps: DagStepDef[],
  edges: DagEdgeDef[],
  stepId: string,
  outputDecls: Map<string, string[]>,
): VarCandidate[] {
  const upstream = ancestorsOf(steps, edges, stepId)
  const out: VarCandidate[] = []
  for (const step of steps) {
    if (!upstream.has(step.stepId)) continue
    for (const varName of outputDecls.get(step.stepId) ?? []) {
      out.push({
        ref: renderStepOutputRef(step.stepName, varName),
        label: `${step.stepName} · ${varName}`,
        hint: '上游步骤输出',
      })
    }
  }
  return out
}

// ═══════════════════════════════════════════════════════════
// 五、编辑器模型 ⇄ 请求体
// ═══════════════════════════════════════════════════════════

/**
 * 客户端锚点键。
 *
 * 【它的语义要说清楚】`DagStepDef.stepId` 在**请求**里只是"本次保存会话内的锚点"，
 * 服务端保存时会重新发号（`WFS-…`，因为 `uk_wstep_step_id` 是全表唯一索引，
 * 十几个工作流都用 `s1` 会当场撞索引），并把新号回填到响应里。
 * 所以这里生成的键只要能**保证同一次请求内唯一**即可，不必追求全局唯一。
 *
 * 实现上仍做一次碰撞检查：从服务端回读的图里锚点是 `WFS-…`，正常不会撞上 `sN`，
 * 但"正常不会"不是"不可能"（用户可以手改接口存一个 `s1`），一次 Set 查询很便宜。
 */
export function nextStepKey(steps: DagStepDef[]): string {
  const used = new Set(steps.map((s) => s.stepId))
  let n = steps.length + 1
  while (used.has(`s${n}`)) n += 1
  return `s${n}`
}

/** 新建一个节点（默认值全部"留空" = 继承，而不是替用户拍一个数字）。 */
export function blankStep(
  steps: DagStepDef[],
  posX: number,
  posY: number,
  stepType: 'TASK' | 'NOTE',
): DagStepDef {
  // 名字要避开已有名字：直接取"当前个数 + 1"会立刻造出一条规则 10 的重复名
  // （删掉"步骤2"再新建，就会又得到一个"步骤2"）
  const base = stepType === 'NOTE' ? '备注' : '步骤'
  const usedNames = new Set(steps.map((s) => s.stepName))
  let seq = 1
  while (usedNames.has(`${base}${seq}`)) seq += 1
  return {
    stepId: nextStepKey(steps),
    stepName: `${base}${seq}`,
    stepType,
    description: null,
    operatorId: null,
    operatorVersionId: null,
    params: null,
    customParams: null,
    targetClusterId: null,
    targetQueueId: null,
    osConstraint: null,
    tagConstraint: null,
    cpu: null,
    gpu: null,
    memory: null,
    disk: null,
    timeoutSeconds: null,
    retryCount: null,
    retryIntervalSeconds: null,
    // 与后端 DDL 的 CHECK 对齐（TERMINATE / RETRY；一期无 IGNORE），
    // 但**不预填**：留空表示"继承工作流级默认值"（覆盖链第 3 层）
    failureStrategy: null,
    mutexGroup: null,
    posX,
    posY,
  }
}

/** 画布尺寸：由节点外接矩形推出（留出右侧与下方余量），不小于最小值。 */
export function canvasSizeFor(steps: DagStepDef[]): { canvasWidth: number; canvasHeight: number } {
  let maxX = 0
  let maxY = 0
  for (const s of steps) {
    const { w, h } = nodeSize(s)
    maxX = Math.max(maxX, (s.posX ?? 0) + w)
    maxY = Math.max(maxY, (s.posY ?? 0) + h)
  }
  return {
    canvasWidth: Math.max(CANVAS_MIN_W, Math.ceil((maxX + 240) / GRID) * GRID),
    canvasHeight: Math.max(CANVAS_MIN_H, Math.ceil((maxY + 160) / GRID) * GRID),
  }
}

/**
 * 分层自动布局：按拓扑层级排布，同层按出现顺序纵向排列。
 *
 * 【为什么值得写】手工拖出 20 个节点是画布上最费时间的事；一个"排一下"按钮
 * 就能把图变得可读。层级用**最长路径**而不是 BFS 深度：BFS 深度会让
 * "A → B → C" 与 "A → C" 里的 C 落在第 1 层（因为 A 是最短路径），
 * 于是 C 跑到 B 左边，连线交叉。
 */
export function autoLayout(steps: DagStepDef[], edges: DagEdgeDef[]): DagStepDef[] {
  if (steps.length === 0) return steps
  const { out, inDegree } = adjacency(steps, edges)
  const level = steps.map(() => 0)
  const deg = [...inDegree]
  const queue = steps.map((_, i) => i).filter((i) => deg[i] === 0)
  // 空队列（纯环图）时把 0 号节点当起点，保证布局总能给出结果而不是原地不动
  if (queue.length === 0) {
    deg[0] = 0
    queue.push(0)
  }
  const seen = new Set<number>()
  while (queue.length) {
    const cur = queue.shift() as number
    if (seen.has(cur)) continue
    seen.add(cur)
    for (const next of out[cur]) {
      level[next] = Math.max(level[next], level[cur] + 1)
      deg[next] -= 1
      if (deg[next] <= 0) queue.push(next)
    }
  }
  // 逐层累计 y：备注节点比任务节点矮，按"上一行下沿 + 间距"推进才不会重叠
  const nextY = new Map<number, number>()
  return steps.map((s, i) => {
    const lv = level[i]
    const { h } = nodeSize(s)
    const y = nextY.get(lv) ?? 40
    nextY.set(lv, y + h + 36)
    return { ...s, posX: 40 + lv * (NODE_W + 80), posY: y }
  })
}

/** 编辑器内部模型（= 服务端 VO 的 DAG 部分）。 */
export interface EditorModel {
  steps: DagStepDef[]
  edges: DagEdgeDef[]
  workflowParams: Record<string, unknown>[]
}

/**
 * 版本详情 → 编辑器模型（**深拷贝**）。
 *
 * 【为什么必须深拷贝】`steps[].params` / `customParams` 是用户数据（JSONB），
 * 直接改响应对象的成员等于改了"服务端返回的那份真相"。用户取消编辑时就没有
 * 可回滚的基线，也无法用"改前 vs 改后"判断脏状态 —— 深拷贝换来一个干净的
 * 还原点，代价是每次打开版本多一份内存拷贝（图很小）。
 */
export function toEditorModel(version: WorkflowVersionItem): EditorModel {
  return {
    steps: (version.steps ?? []).map((s) => ({
      ...s,
      params: s.params ? { ...s.params } : null,
      customParams: s.customParams ? { ...s.customParams } : null,
      tagConstraint: s.tagConstraint ? [...s.tagConstraint] : null,
      posX: s.posX ?? 0,
      posY: s.posY ?? 0,
    })),
    edges: (version.edges ?? []).map((e) => ({ ...e })),
    workflowParams: (version.workflowParams ?? []).map((p) => ({ ...p })),
  }
}
