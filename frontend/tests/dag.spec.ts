import { describe, expect, it } from 'vitest'
import type { DagEdgeDef, DagStepDef, WorkflowVersionItem } from '@/api/types/workflow'
import {
  CANVAS_MIN_H,
  CANVAS_MIN_W,
  autoLayout,
  blankStep,
  canConnect,
  canvasSizeFor,
  checkStructure,
  extractIssues,
  groupIssues,
  nextStepKey,
  nodeSize,
  renderStepOutputRef,
  toEditorModel,
  variableCandidates,
} from '@/utils/dag'

/**
 * DAG 画布纯函数的单测（M3 §1.7，配套 D-14）。
 *
 * 【它挡的是什么】
 * 画布的**手势**只能靠人眼验收，但画布的**判断**不该靠人眼 ——
 * "这张图有没有问题""这条线能不能连""哪些变量能引用"三件事都在 `utils/dag.ts` 里，
 * 于是可以像服务端 `DagValidatorTest` 一样逐条规则造用例，而不是起个浏览器点一遍。
 *
 * 【与服务端的关系（§5-14 那条纪律的延伸）】
 * 这里断言的规则 1/5/10 是**服务端规则的镜像**。镜像会漂移，所以：
 * ① 文案刻意与服务端 `DagValidator` 逐字一致（同一句话用户看到两次不该有两种说法）；
 * ② 真正产生歧义的地方（比如"空图算不算错"）单独写一条用例把口径钉住 ——
 *    那一条对应 README-M3 的 O-23。
 */

/** 造一个步骤（只写关心的字段，其余留空 —— 与服务端"留空=继承"的语义一致）。 */
function step(partial: Partial<DagStepDef> & { stepId: string; stepName: string }): DagStepDef {
  return {
    stepType: 'TASK',
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
    failureStrategy: null,
    mutexGroup: null,
    posX: 0,
    posY: 0,
    ...partial,
  }
}

const edge = (sourceStepId: string, targetStepId: string): DagEdgeDef => ({ sourceStepId, targetStepId })

// ═══════════════════════════════════════════════════════════
// 规则 1 / 5 / 10（镜像服务端）
// ═══════════════════════════════════════════════════════════

describe('checkStructure · 规则 1（有入口且都可达）', () => {
  it('空图不报错 —— 与服务端一致（docs 的 10 条规则不覆盖"一个步骤都没有"，O-23）', () => {
    expect(checkStructure([], [])).toEqual([])
  })

  it('单节点图没有入口问题', () => {
    expect(checkStructure([step({ stepId: 's1', stepName: 'A' })], [])).toEqual([])
  })

  it('链上的每个节点都可达时不报错', () => {
    const steps = [step({ stepId: 'a', stepName: 'A' }), step({ stepId: 'b', stepName: 'B' })]
    expect(checkStructure(steps, [edge('a', 'b')])).toEqual([])
  })

  it('**孤立节点不算「不可达」** —— 入度 0 的节点自己就是源（服务端口径如此）', () => {
    const steps = [
      step({ stepId: 'a', stepName: 'A' }),
      step({ stepId: 'b', stepName: 'B' }),
      step({ stepId: 'lonely', stepName: '孤岛' }),
    ]
    // 孤岛没有入边 → 它自己就是一个入口 → 从入口可达集合里有它。
    // 这条用例专门钉住这个反直觉的口径：镜像一旦与服务端不同，
    // 用户就会看到"本地说没问题、保存却报错"（或反之）
    expect(checkStructure(steps, [edge('a', 'b')])).toEqual([])
  })

  it('被环拖住的一串节点报「不可达」（有入边但到不了任何入口）', () => {
    const steps = [
      step({ stepId: 'a', stepName: '入口' }),
      step({ stepId: 'x', stepName: 'X' }),
      step({ stepId: 'y', stepName: 'Y' }),
    ]
    // A 是唯一入口；X ↔ Y 自成孤环，入度都 > 0 且从 A 到不了
    const issues = checkStructure(steps, [edge('x', 'y'), edge('y', 'x')])
    const unreachable = issues.filter((i) => i.rule === '1')
    expect(unreachable.map((i) => i.stepName)).toEqual(['X', 'Y'])
    expect(unreachable[0].message).toContain('不可达')
    // 环本身也要报出来（规则 5），否则用户只知道"到不了"，不知道"为什么到不了"
    expect(issues.some((i) => i.rule === '5')).toBe(true)
  })

  it('备注节点不可达不算问题（PRD §10.8 规则 1 明文"备注除外"）', () => {
    const steps = [
      step({ stepId: 'a', stepName: 'A' }),
      step({ stepId: 'n', stepName: '便签', stepType: 'NOTE' }),
    ]
    expect(checkStructure(steps, [])).toEqual([])
  })

  it('整张图都是环（没有入度 0 的节点）报「没有入口步骤」，且带上位步骤名之外的工作流级标记', () => {
    const steps = [step({ stepId: 'a', stepName: 'A' }), step({ stepId: 'b', stepName: 'B' })]
    const issues = checkStructure(steps, [edge('a', 'b'), edge('b', 'a')])
    expect(issues).toHaveLength(2)
    expect(issues[0].rule).toBe('1')
    expect(issues[0].stepName).toBeNull()
    expect(issues[0].message).toContain('入口')
    // 规则 1 早早 return 之后仍会继续跑规则 5 —— 环的信息不能丢
    expect(issues[1].rule).toBe('5')
  })

  it('悬挂连线（端点不在图里）被跳过，不影响其它规则', () => {
    const steps = [step({ stepId: 'a', stepName: 'A' })]
    // 编辑中途"刚删了节点、边还在"是常态，服务端也做同样的跳过
    expect(checkStructure(steps, [edge('a', 'ghost')])).toEqual([])
  })
})

describe('checkStructure · 规则 5（无环）', () => {
  it('有入口又有环时报出环，并把路径打出来（用户能照着删线）', () => {
    const steps = [
      step({ stepId: 'a', stepName: 'A' }),
      step({ stepId: 'b', stepName: 'B' }),
      step({ stepId: 'c', stepName: 'C' }),
    ]
    const issues = checkStructure(steps, [edge('a', 'b'), edge('b', 'c'), edge('c', 'b')])
    const cycle = issues.find((i) => i.rule === '5')
    expect(cycle).toBeDefined()
    expect(cycle?.stepName).toBeNull()
    expect(cycle?.message).toContain('循环依赖')
    // 闭环要读得出来是闭环：`B → C → B`，而不是 `B → C → B → C …`
    expect(cycle?.message).toContain('B → C → B')
  })

  it('菱形（分叉再汇合）不是环', () => {
    const steps = ['a', 'b', 'c', 'd'].map((k) => step({ stepId: k, stepName: k.toUpperCase() }))
    expect(checkStructure(steps, [edge('a', 'b'), edge('a', 'c'), edge('b', 'd'), edge('c', 'd')])).toEqual([])
  })
})

describe('checkStructure · 规则 10（步骤名唯一）', () => {
  it('重名报一次，名字写进 stepName（按首次出现顺序）', () => {
    const steps = [
      step({ stepId: 'a', stepName: '清洗' }),
      step({ stepId: 'b', stepName: '清洗' }),
      step({ stepId: 'c', stepName: '清洗' }),
    ]
    const issues = checkStructure(steps, [])
    const dup = issues.filter((i) => i.rule === '10')
    // 出现三次也只报一条：服务端用 LinkedHashMap 计数，前端镜像同一口径
    expect(dup).toHaveLength(1)
    expect(dup[0].stepName).toBe('清洗')
    expect(dup[0].message).toContain('重复')
  })

  it('问题顺序固定为 1 → 5 → 10（两端顺序一致，用户才不会怀疑看的是两套规则）', () => {
    const steps = [
      step({ stepId: 'a', stepName: 'X' }),
      step({ stepId: 'b', stepName: 'X' }),
      step({ stepId: 'b2', stepName: 'B' }),
      step({ stepId: 'c', stepName: 'C' }),
      step({ stepId: 'c2', stepName: 'D' }),
    ]
    // 环：B → C → D → B；孤立：X、X（重名且都不可达）
    const issues = checkStructure(steps, [
      edge('b2', 'c'),
      edge('c', 'c2'),
      edge('c2', 'b2'),
      edge('b2', 'c'),
    ])
    const rules = issues.map((i) => i.rule)
    // 顺序固定：规则 1 的两条（B / C / D 被环拖住）→ 规则 5 → 规则 10
    expect(rules).toEqual(['1', '1', '1', '5', '10'])
  })
})

// ═══════════════════════════════════════════════════════════
// 连线守卫
// ═══════════════════════════════════════════════════════════

describe('canConnect（拖拽时的即时守卫）', () => {
  const steps = [
    step({ stepId: 'a', stepName: 'A' }),
    step({ stepId: 'b', stepName: 'B' }),
    step({ stepId: 'n', stepName: '便签', stepType: 'NOTE' }),
  ]

  it('正常连线放行', () => {
    expect(canConnect(steps, [], 'a', 'b').ok).toBe(true)
  })

  it('自环被拒（服务端 indexByKey 对自环报 40001，DDL 也有 ck_edge_no_self_loop）', () => {
    const v = canConnect(steps, [], 'a', 'a')
    expect(v.ok).toBe(false)
    expect(v.reason).toContain('自己')
  })

  it('重复连线被拒（否则同一条线画两遍，服务端保存时也会重复插两条边）', () => {
    const v = canConnect(steps, [edge('a', 'b')], 'a', 'b')
    expect(v.ok).toBe(false)
    expect(v.reason).toContain('已经有连线')
  })

  it('会成环的连线被拒，且理由说清是哪一步', () => {
    // 已有 A → B，再连 B → A 就成环
    const v = canConnect(steps, [edge('a', 'b')], 'b', 'a')
    expect(v.ok).toBe(false)
    expect(v.reason).toContain('环')
  })

  it('传递成环也被拒（A→B→C 之后连 C→A）', () => {
    const chain = [
      step({ stepId: 'a', stepName: 'A' }),
      step({ stepId: 'b', stepName: 'B' }),
      step({ stepId: 'c', stepName: 'C' }),
    ]
    const v = canConnect(chain, [edge('a', 'b'), edge('b', 'c')], 'c', 'a')
    expect(v.ok).toBe(false)
  })

  it('备注节点不参与执行链路，两个方向都拒', () => {
    expect(canConnect(steps, [], 'a', 'n').ok).toBe(false)
    expect(canConnect(steps, [], 'n', 'a').ok).toBe(false)
  })

  it('端点已不在画布上时拒绝而不是抛错', () => {
    const v = canConnect(steps, [], 'a', 'ghost')
    expect(v.ok).toBe(false)
    expect(v.reason).toContain('不在画布上')
  })
})

// ═══════════════════════════════════════════════════════════
// 锚点与节点
// ═══════════════════════════════════════════════════════════

describe('nextStepKey / blankStep', () => {
  it('锚点键在一个请求内唯一（服务端会重新发号，这里只要求请求内不撞）', () => {
    const steps = [step({ stepId: 's1', stepName: 'A' }), step({ stepId: 's3', stepName: 'B' })]
    const key = nextStepKey(steps)
    expect(steps.map((s) => s.stepId)).not.toContain(key)
  })

  it('锚点键长度在服务端 @Size(max=32) 之内', () => {
    const steps = Array.from({ length: 200 }, (_, i) => step({ stepId: `s${i + 1}`, stepName: `T${i}` }))
    expect(nextStepKey(steps).length).toBeLessThanOrEqual(32)
  })

  it('新建的步骤名避开已有名字（否则一建出来就命中规则 10）', () => {
    const steps = [step({ stepId: 's1', stepName: '步骤1' }), step({ stepId: 's2', stepName: '步骤2' })]
    // 删掉"步骤3"再新建会撞回同一个名字 —— 这里直接让"步骤3"已在图上
    steps.push(step({ stepId: 's3', stepName: '步骤3' }))
    expect(blankStep(steps, 0, 0, 'TASK').stepName).toBe('步骤4')
  })

  it('新建的步骤默认全部留空 = 继承（不替用户拍一个数字）', () => {
    const created = blankStep([], 40, 60, 'TASK')
    expect(created).toMatchObject({
      stepType: 'TASK',
      operatorId: null,
      operatorVersionId: null,
      cpu: null,
      memory: null,
      timeoutSeconds: null,
      retryCount: null,
      failureStrategy: null,
      posX: 40,
      posY: 60,
    })
  })

  it('备注节点与任务节点的尺寸不同（备注是便签，占同样高度会挤压主链路）', () => {
    expect(nodeSize(step({ stepId: 'a', stepName: 'A' })).h).not.toBe(
      nodeSize(step({ stepId: 'n', stepName: 'N', stepType: 'NOTE' })).h,
    )
  })
})

// ═══════════════════════════════════════════════════════════
// 布局与画布尺寸
// ═══════════════════════════════════════════════════════════

describe('autoLayout', () => {
  it('按拓扑层级横排：A→B→C 落在三个不同的 x 上', () => {
    const steps = ['a', 'b', 'c'].map((k) => step({ stepId: k, stepName: k.toUpperCase() }))
    const laid = autoLayout(steps, [edge('a', 'b'), edge('b', 'c')])
    // `posX` 的类型是 `number | null`（DTO 允许不填）；布局是纯函数，必定给出数字，
    // 所以这里的 `?? -1` 同时充当一句断言：一旦真返回 null，三个 x 会一起变成 -1，
    // 下面的 `size === 3` 立刻失败
    const xs = laid.map((s) => s.posX ?? -1)
    expect(new Set(xs).size).toBe(3)
    expect(xs[0]).toBeLessThan(xs[1])
    expect(xs[1]).toBeLessThan(xs[2])
  })

  it('最长路径定层：菱形里的汇合点在第 2 层，不会被 BFS 拉到第 1 层', () => {
    // A → B → D 与 A → D。若按 BFS 深度，D 会是第 1 层（走了 A → D 那条短路），
    // 于是 D 跑到 B 左边、连线交叉
    const steps = ['a', 'b', 'd'].map((k) => step({ stepId: k, stepName: k.toUpperCase() }))
    const laid = autoLayout(steps, [edge('a', 'b'), edge('a', 'd'), edge('b', 'd')])
    const x = (id: string) => laid.find((s) => s.stepId === id)?.posX ?? -1
    expect(x('d')).toBeGreaterThan(x('b'))
  })

  it('同一层的节点纵向错开（不会叠在一起）', () => {
    const steps = ['a', 'b', 'c'].map((k) => step({ stepId: k, stepName: k.toUpperCase() }))
    const laid = autoLayout(steps, []) // 三个都是入度 0 → 同层
    expect(new Set(laid.map((s) => s.posX)).size).toBe(1)
    expect(new Set(laid.map((s) => s.posY)).size).toBe(3)
  })

  it('纯环图（没有入度 0 的节点）也能给出布局而不是原地不动', () => {
    const steps = [step({ stepId: 'a', stepName: 'A' }), step({ stepId: 'b', stepName: 'B' })]
    const laid = autoLayout(steps, [edge('a', 'b'), edge('b', 'a')])
    expect(new Set(laid.map((s) => s.posX)).size).toBe(2)
  })

  it('空图原样返回', () => {
    expect(autoLayout([], [])).toEqual([])
  })
})

describe('canvasSizeFor', () => {
  it('空图给最小尺寸（不能是 0 —— canvas_width 有 @Min(0)，但 0 宽的画布没法用）', () => {
    const size = canvasSizeFor([])
    expect(size.canvasWidth).toBeGreaterThanOrEqual(CANVAS_MIN_W)
    expect(size.canvasHeight).toBeGreaterThanOrEqual(CANVAS_MIN_H)
  })

  it('节点摆得越远画布越大', () => {
    const near = canvasSizeFor([step({ stepId: 'a', stepName: 'A', posX: 0, posY: 0 })])
    const far = canvasSizeFor([step({ stepId: 'a', stepName: 'A', posX: 4000, posY: 3000 })])
    expect(far.canvasWidth).toBeGreaterThan(near.canvasWidth)
    expect(far.canvasHeight).toBeGreaterThan(near.canvasHeight)
  })
})

// ═══════════════════════════════════════════════════════════
// 变量引用（D-20）
// ═══════════════════════════════════════════════════════════

describe('renderStepOutputRef（自动加引号，避免用户撞 42214）', () => {
  it('纯标识符不加引号', () => {
    expect(renderStepOutputRef('clean_step', 'rows')).toBe('${step.clean_step.output.rows}')
  })

  it('纯中文不加引号（变量引用器的中文判定是整体匹配 `\\p{IsHan}+`）', () => {
    expect(renderStepOutputRef('清洗', 'rows')).toBe('${step.清洗.output.rows}')
  })

  it('中文 + 数字要加引号（既不是标识符也不是"纯中文"）', () => {
    expect(renderStepOutputRef('清洗2', 'rows')).toBe('${step."清洗2".output.rows}')
  })

  it('含空格 / 连字符要加引号', () => {
    expect(renderStepOutputRef('data-clean', 'rows')).toBe('${step."data-clean".output.rows}')
    expect(renderStepOutputRef('数据 清洗', 'rows')).toBe('${step."数据 清洗".output.rows}')
  })
})

describe('variableCandidates / groupIssues / extractIssues', () => {
  const steps = [
    step({ stepId: 'a', stepName: '清洗' }),
    step({ stepId: 'b', stepName: '汇总' }),
    step({ stepId: 'c', stepName: '导出' }),
  ]

  it('候选 = 可达上游 + 该上游已声明的输出', () => {
    const decls = new Map([
      ['a', ['rows', 'file_path']],
      ['b', ['total']],
    ])
    const list = variableCandidates(steps, [edge('a', 'b'), edge('b', 'c')], 'c', decls)
    expect(list.map((c) => c.ref)).toEqual([
      '${step.清洗.output.rows}',
      '${step.清洗.output.file_path}',
      '${step.汇总.output.total}',
    ])
  })

  it('下游步骤的输出不出现在候选里（引用了也会被规则 4 判无效）', () => {
    const decls = new Map([['c', ['x']]])
    expect(variableCandidates(steps, [edge('a', 'b'), edge('b', 'c')], 'b', decls)).toEqual([])
  })

  it('没有声明输出的上游不产生候选（不凭空造变量名）', () => {
    const list = variableCandidates(steps, [edge('a', 'b')], 'b', new Map([['a', []]]))
    expect(list).toEqual([])
  })

  it('groupIssues 把工作流级问题单独分出来（混进节点列表会让人以为是节点的问题）', () => {
    const { byStep, global } = groupIssues([
      { rule: '1', stepName: 'A', message: '不可达' },
      { rule: '5', stepName: null, message: '有环' },
      { rule: '1', stepName: 'A', message: '另一条也不可达' },
    ])
    expect(global).toHaveLength(1)
    expect(global[0].rule).toBe('5')
    expect(byStep.get('A')).toHaveLength(2)
    expect(byStep.get('B')).toBeUndefined()
  })

  it('extractIssues 对非数组 / 缺字段安全降级（形状是约定而非类型系统能保证的）', () => {
    expect(extractIssues(undefined)).toEqual([])
    expect(extractIssues({})).toEqual([])
    expect(extractIssues({ errors: 'nope' })).toEqual([])
    expect(extractIssues({ errors: [{ rule: '4', stepName: null, message: 'x' }] })).toEqual([
      { rule: '4', stepName: null, message: 'x' },
    ])
  })
})

// ═══════════════════════════════════════════════════════════
// 编辑模型
// ═══════════════════════════════════════════════════════════

describe('toEditorModel（深拷贝出还原点）', () => {
  const version = {
    versionId: 'WFV-0042-01',
    workflowId: 'WF-0042',
    workflowName: '日增量清算',
    versionNo: 'v1',
    publishStatus: 'DRAFT',
    stepCount: 1,
    steps: [
      step({
        stepId: 'WFS-0042-01-01',
        stepName: '清洗',
        params: { input_path: '${platform.base_dir}' },
        customParams: { note: 'x' },
        tagConstraint: ['gpu'],
      }),
    ],
    edges: [],
    workflowParams: [{ bizDate: '2026-10-08' }],
    canvasWidth: 1200,
    canvasHeight: 720,
    publisher: null,
    publishedAt: null,
    createdAt: '2026-10-08T00:00:00Z',
    updatedAt: '2026-10-08T00:00:00Z',
    hasDraftChanges: true,
  } as unknown as WorkflowVersionItem

  it('改编辑模型不会影响服务端返回的那份真相（用户取消编辑要能回到基线）', () => {
    const model = toEditorModel(version)
    model.steps[0].stepName = '改过了'
    model.steps[0].params!.input_path = '改过了'
    model.steps[0].tagConstraint!.push('ssd')
    model.workflowParams.push({ extra: 1 })

    expect(version.steps[0].stepName).toBe('清洗')
    expect(version.steps[0].params?.input_path).toBe('${platform.base_dir}')
    expect(version.steps[0].tagConstraint).toEqual(['gpu'])
    expect(version.workflowParams).toHaveLength(1)
  })

  it('坐标为 null 时补 0（服务端落库也会补 0，前端先补齐免得画布算出 NaN）', () => {
    const model = toEditorModel({
      ...version,
      steps: [step({ stepId: 'a', stepName: 'A' })],
    } as unknown as WorkflowVersionItem)
    expect(model.steps[0].posX).toBe(0)
    expect(model.steps[0].posY).toBe(0)
  })

  it('steps / edges / workflowParams 缺字段时按空集合处理（不抛错，画布画空图）', () => {
    const model = toEditorModel({ ...version, steps: undefined, edges: undefined } as unknown as WorkflowVersionItem)
    expect(model.steps).toEqual([])
    expect(model.edges).toEqual([])
  })
})
