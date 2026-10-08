import { describe, expect, it } from 'vitest'
import { deepCamel, deepSnake } from '@/utils/casename'

/**
 * 键名转换单测（D-15）。
 *
 * 【为什么这些用例值得存在】
 * `deepSnake` / `deepCamel` 是"网络边界"的唯一一处转换，它的正确性直接决定
 * 后端能不能认出入参。而它有一类**看起来对、实际错**的情况：把用户数据里的键
 * 也当成协议字段转了 —— 表现不是报错，而是后端说"参数不在模板里"，
 * 于是排查方向会被带到"用户是不是填错了参数名"上去（真实原因在转换器）。
 * 所以下面专设一组"用户 Map 的不透明性"用例把这条边界钉住。
 */
describe('deepSnake（请求出口）', () => {
  it('协议字段转 snake_case', () => {
    expect(deepSnake({ executorNodeId: 'EN-0001', timeoutSeconds: 60 })).toEqual({
      executor_node_id: 'EN-0001',
      timeout_seconds: 60,
    })
  })

  it('嵌套对象与数组元素一并转换', () => {
    const input = { steps: [{ stepName: 'a', targetClusterId: 'CL-0001' }], canvasWidth: 100 }
    expect(deepSnake(input)).toEqual({
      steps: [{ step_name: 'a', target_cluster_id: 'CL-0001' }],
      canvas_width: 100,
    })
  })

  it('params 内部的键是用户数据，一律原样保留', () => {
    // 后端 IDENTIFIER 允许大写，inputPath 是完全合法的 param_key
    expect(deepSnake({ params: { inputPath: '/data/in', partition_count: '8' } })).toEqual({
      params: { inputPath: '/data/in', partition_count: '8' },
    })
  })

  it('容器键名本身仍按协议转换（customParams → custom_params）', () => {
    expect(deepSnake({ customParams: { keepMe: 1 } })).toEqual({
      custom_params: { keepMe: 1 },
    })
  })

  it('其余用户容器键同样不透明', () => {
    expect(deepSnake({ runParams: { aB: 1 } })).toEqual({ run_params: { aB: 1 } })
    expect(deepSnake({ workflowParams: { aB: 1 } })).toEqual({ workflow_params: { aB: 1 } })
    expect(deepSnake({ defaultParams: { aB: 1 } })).toEqual({ default_params: { aB: 1 } })
  })

  it('容器嵌在数组元素里也不透明（工作流 steps[].params）', () => {
    expect(deepSnake({ steps: [{ stepName: 's1', params: { myKey: 'v' } }] })).toEqual({
      steps: [{ step_name: 's1', params: { myKey: 'v' } }],
    })
  })

  it('Date / FormData / null 原样返回（不能把二进制与时间戳拆掉）', () => {
    const at = new Date('2026-10-08T00:00:00Z')
    expect(deepSnake({ createdAt: at })).toEqual({ created_at: at })
    expect(deepSnake({ n: null })).toEqual({ n: null })
    // undefined 走 Object.entries 会被跳过（JSON 里本来也不会出现）
    expect(deepSnake({ a: undefined })).toEqual({})
  })
})

describe('deepCamel（响应入口）', () => {
  it('协议字段转 camelCase', () => {
    expect(deepCamel({ operator_id: 'OP-0042', version_count: 3 })).toEqual({
      operatorId: 'OP-0042',
      versionCount: 3,
    })
  })

  it('params 内部的键原样保留（否则读不出用户原始参数名）', () => {
    expect(deepCamel({ params: { inputPath: 'x', input_path: 'y' } })).toEqual({
      params: { inputPath: 'x', input_path: 'y' },
    })
  })

  it('试运行 CMD 帧的 sources 也是参数名→层名，键要原样保留', () => {
    // 这是从后端 DryRunFrames 的真实字段来的：sources 的键就是 param_key
    expect(deepCamel({ sources: { inputPath: '步骤参数', a_b: '项目参数' } })).toEqual({
      sources: { inputPath: '步骤参数', a_b: '项目参数' },
    })
  })

  it('分页响应的包裹字段照转', () => {
    expect(deepCamel({ total_capped: true, page_size: 20, records: [{ operator_name: 'n' }] })).toEqual({
      totalCapped: true,
      pageSize: 20,
      records: [{ operatorName: 'n' }],
    })
  })
})

describe('两个方向的往返一致性', () => {
  it('协议字段 snake→camel→snake 回到原样', () => {
    const wire = { operator_name: '清洗', default_timeout_seconds: 60, param_template: [{ param_key: 'k' }] }
    expect(deepSnake(deepCamel(wire))).toEqual(wire)
  })

  it('用户参数名的往返不引入任何改写', () => {
    const wire = { params: { inputPath: 'x' } }
    expect(deepSnake(deepCamel(wire))).toEqual(wire)
  })
})
