import { describe, expect, it } from 'vitest'
import { ERROR_MESSAGES, messageOf } from '@/utils/errorMessage'
import { PERM } from '@/constants/permissions'

/**
 * 文案与权限点的防漂移单测（对应后端 PermissionMatrixTest 的前端一半）。
 *
 * 【它挡的是什么】
 * `ERROR_MESSAGES` 是 `Record<number, string>` —— TS 只能保证"写了的值是数字"，
 * 保证不了"该有的码写全了"。于是 docs/07 §4.2 新增一个错误码、后端开始返回它，
 * 前端却没人补文案时，用户会看到后端那句给开发看的英文 message。
 * 这里把 M2 资产域**已实现**的码钉住：漏一个就红。
 *
 * 注意范围：只断言 M2 已落地的码。M3/M4 的码（4221x/4223x/4224x/53210 等）
 * 在对应里程碑落地时再往这里加，避免"为了过测试先占位一堆没用的文案"。
 */
describe('资产域错误码文案覆盖（docs/07 §4.2）', () => {
  const ASSET_DOMAIN_CODES = [
    40300, // 无权限执行该操作
    40301, // 超出数据范围（M2 接管 40400/40301 语义区分）
    40400, // 资源不存在
    42200, // 业务规则不满足
    42201, // 项目负责人不可移除
    42202, // 凭据被引用
    42203, // 项目仍有运行中任务
    42204, // 集群下仍有节点/队列
    42205, // 节点被运行中任务占用
    42206, // 凭据过期时间非法
    42207, // 项目并发额度超集群上限
  ]

  it('M2 资产域错误码全部有中文文案', () => {
    ASSET_DOMAIN_CODES.forEach((code) => {
      expect(ERROR_MESSAGES[code], `缺少 ${code} 的用户文案`).toBeTruthy()
    })
  })

  it('42202/42204/42205 的文案分别点明"凭据/集群/节点"，不写通用句', () => {
    // 这三条是 M2 最容易混淆的三类"仍被引用"闸门，文案必须能自解释
    expect(messageOf(42202, '')).toContain('凭据')
    expect(messageOf(42204, '')).toContain('集群')
    expect(messageOf(42205, '')).toContain('节点')
  })

  it('40301 与 40400 文案语义不同（越权 ≠ 不存在）', () => {
    expect(messageOf(40301, '')).not.toBe(messageOf(40400, ''))
    expect(messageOf(40301, '')).toContain('数据范围')
  })

  it('未登记的码回退到后端 message（而不是显示 undefined）', () => {
    expect(messageOf(99999, '后端原话')).toBe('后端原话')
  })
})

describe('资产域权限点（docs/07 §5.2）', () => {
  it('集群/节点/队列/凭据四域的读写点都已在 PERM 常量表里', () => {
    const expected = [
      'schedule:cluster:read',
      'schedule:cluster:write',
      'schedule:node:read',
      'schedule:node:write',
      'schedule:node:test',
      'schedule:queue:read',
      'schedule:queue:write',
      'schedule:credential:read',
      'schedule:credential:write',
      'schedule:credential:rotate',
    ]
    const values = new Set(Object.values(PERM))
    expected.forEach((p) => expect(values.has(p as never), `PERM 缺少 ${p}`).toBe(true))
  })

  it('四个 M2 页面用到的权限点都取自 PERM，而不是字面量', () => {
    expect(PERM.CLUSTER_WRITE).toBe('schedule:cluster:write')
    expect(PERM.NODE_TEST).toBe('schedule:node:test')
    expect(PERM.QUEUE_WRITE).toBe('schedule:queue:write')
    expect(PERM.CREDENTIAL_ROTATE).toBe('schedule:credential:rotate')
  })
})
