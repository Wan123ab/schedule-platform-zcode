// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import type { DagEdgeDef, DagStepDef } from '@/api/types/workflow'
import DagCanvas from '@/components/biz/dag/DagCanvas.vue'
import type { DagIssue } from '@/utils/dag'

/**
 * 画布组件的渲染断言（M3 §1.7）。
 *
 * 【为什么必须真的挂一次组件，而不是继续读源码文本】
 * 画布里最危险的一个假设是**命名空间**：节点内容靠 `<foreignObject>` 嵌 HTML，
 * 而 Vue 的 `compiler-dom` 是**逐字**比对 `parent.tag === "foreignObject"` 才把命名空间
 * 从 SVG 切回 HTML。一旦有人把它写成小写 `<foreignobject>`：
 * 编译通过、lint 通过、`vue-tsc` 通过、页面也不报错 —— 只是节点里生成了一堆
 * 浏览器不认识的 SVG 元素，**节点是空的**。文本断言只能防住"写法"，
 * 这里直接断言 `namespaceURI`，防的是"结果"。
 *
 * 【jsdom 的边界】`getBoundingClientRect()` 在 jsdom 里全返回 0，所以"适应画布"
 * 这类**几何**行为在这里测不了（它会走 `rect.width === 0` 的兜底分支）。
 * 几何相关的纯计算在 `tests/dag.spec.ts` 里测 —— 这正是把几何抽成纯函数的收益。
 */
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

/**
 * 用组件**自己的** props 类型做形参，而不是 `Record<string, unknown>`。
 *
 * 后者看着更"灵活"，实则把 `vue-tsc` 的类型检查整个关掉：`mount()` 收不到
 * 必需的 `steps`/`edges` 也不会报错，于是"测试写错了一个 prop 名"这件事
 * 只有等到运行期断言失败才发现。这里让 tsconfig 直接拿组件的契约校验，
 * 少写一个必填 prop 当场就是 TS 报错。
 */
type CanvasProps = InstanceType<typeof DagCanvas>['$props']

const mountCanvas = (props: CanvasProps) => mount(DagCanvas, { props, attachTo: document.body })

describe('DagCanvas · 渲染', () => {
  const steps = [
    step({ stepId: 'a', stepName: '清洗', operatorId: 'OP-1', operatorVersionId: 'OPV-1-01', cpu: 2, timeoutSeconds: 300 }),
    step({ stepId: 'b', stepName: '汇总' }),
    step({ stepId: 'n', stepName: '便签', stepType: 'NOTE' }),
  ]

  it('节点内容是 HTML 命名空间（foreignObject 的命名空间重置生效）', () => {
    const wrapper = mountCanvas({ steps, edges: [] })
    const node = wrapper.find('.dag-node')
    expect(node.exists()).toBe(true)
    // 小写 <foreignobject> 时这里会是 SVG 命名空间（节点渲染成空）
    expect(node.element.namespaceURI).toBe('http://www.w3.org/1999/xhtml')
  })

  it('每个步骤画一个节点', () => {
    const wrapper = mountCanvas({ steps, edges: [] })
    expect(wrapper.findAll('.dag-node')).toHaveLength(3)
  })

  it('节点上显示步骤名、算子名与运行约束小片', () => {
    const wrapper = mountCanvas({ steps, edges: [], operatorNames: { 'OP-1': '数据清洗算子' } })
    const first = wrapper.findAll('.dag-node')[0]
    expect(first.text()).toContain('清洗')
    // 显示算子名而不是 OP-1：画布上读编号毫无意义
    expect(first.text()).toContain('数据清洗算子')
    expect(first.text()).toContain('OPV-1-01')
    expect(first.text()).toContain('CPU 2')
    expect(first.text()).toContain('超时 300s')
  })

  it('未选算子时标红提示（发布时规则 2 会拦下，提前在画布上说出来）', () => {
    const wrapper = mountCanvas({ steps, edges: [] })
    const second = wrapper.findAll('.dag-node')[1]
    expect(second.find('.op.missing').exists()).toBe(true)
  })

  it('每条连线画一条路径', () => {
    const wrapper = mountCanvas({ steps, edges: [edge('a', 'b')] })
    expect(wrapper.findAll('path.edge-line')).toHaveLength(1)
  })

  it('悬挂连线不画（端点已不在图上时静默跳过，而不是画一条指向原点的线）', () => {
    const wrapper = mountCanvas({ steps, edges: [edge('a', 'b'), edge('a', 'ghost')] })
    expect(wrapper.findAll('path.edge-line')).toHaveLength(1)
  })

  it('环上的连线带 bad 类（规则 5 的错要能一眼看到是哪条线）', () => {
    const wrapper = mountCanvas({ steps, edges: [edge('a', 'b'), edge('b', 'a')] })
    expect(wrapper.findAll('path.edge-line.bad')).toHaveLength(2)
  })

  it('选中的节点与连线有独立样式', () => {
    const wrapper = mountCanvas({ steps, edges: [edge('a', 'b')], selectedStepId: 'b', selectedEdgeIndex: 0 })
    expect(wrapper.findAll('.dag-node.sel')).toHaveLength(1)
    expect(wrapper.findAll('path.edge-line.sel')).toHaveLength(1)
  })

  it('待修正项挂在对应节点上（按步骤名匹配，服务端 errors[] 就是这么给的）', () => {
    const issues = new Map<string, DagIssue[]>([
      ['汇总', [{ rule: '3', stepName: '汇总', message: '缺少必填参数' }]],
    ])
    const wrapper = mountCanvas({ steps, edges: [], issuesByStep: issues })
    const node = wrapper.findAll('.dag-node')[1]
    expect(node.classes()).toContain('err')
    expect(node.find('.badge.err').text()).toBe('1')
    // 另一条消息走 title（悬停可见），不在视觉上占位
    expect(node.find('.badge.err').attributes('title')).toContain('缺少必填参数')
  })

  it('没有错误但有警告时给一个浅色角标（规则 2/7 的成因提前可见）', () => {
    const warnings = new Map<string, string[]>([['b', ['未选择算子（发布时规则 2：42213）']]])
    const wrapper = mountCanvas({ steps, edges: [], warningsByStepId: warnings })
    const node = wrapper.findAll('.dag-node')[1]
    expect(node.find('.badge.warn').exists()).toBe(true)
    expect(node.find('.badge.err').exists()).toBe(false)
  })

  it('只读模式不画端口（拉不出线，就不该给一个能按的圆点）', () => {
    const editable = mountCanvas({ steps, edges: [] })
    expect(editable.findAll('circle.port').length).toBeGreaterThan(0)
    const readonly = mountCanvas({ steps, edges: [], readonly: true })
    expect(readonly.findAll('circle.port')).toHaveLength(0)
  })

  it('备注节点没有端口（它不参与执行链路）', () => {
    const wrapper = mountCanvas({ steps: [step({ stepId: 'n', stepName: '便签', stepType: 'NOTE' })], edges: [] })
    expect(wrapper.findAll('circle.port')).toHaveLength(0)
  })

  it('缩放条显示当前比例（画布状态的可见锚点）', () => {
    const wrapper = mountCanvas({ steps, edges: [] })
    expect(wrapper.find('.zoom-bar .k').text()).toBe('100%')
  })

  it('空图也能渲染（刚新建的版本一个节点都没有，这不是错误状态）', () => {
    const wrapper = mountCanvas({ steps: [], edges: [] })
    expect(wrapper.find('svg.dag-svg').exists()).toBe(true)
    expect(wrapper.findAll('.dag-node')).toHaveLength(0)
  })
})
