import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { dash, formatDateTime, formatHeartbeat, formatMb } from '@/utils/format'

/**
 * 展示层格式化单测（M2 新增工具）。
 *
 * 【为什么这些函数值得测】
 * 它们全是纯函数，但踩坑点很集中：
 * ① MB→GB 的切换点（1024）与小数位策略，是"看起来一样、算出来不同"的典型；
 * ② 时间显示依赖"本地时区往返"，写死在 UTC 上会时好时坏 —— 这里用
 *    `new Date(本地分量)` 构造再交给被测函数，断言与运行机器时区无关；
 * ③ 相对时间依赖 `Date.now()`，必须用假时钟固定，否则测试会随执行速度飘。
 */
describe('formatMb', () => {
  it('小于 1024 MB 原样显示 MB', () => {
    expect(formatMb(0)).toBe('0 MB')
    expect(formatMb(512)).toBe('512 MB')
    expect(formatMb(1023)).toBe('1023 MB')
  })

  it('1024 MB 起换算成 GB，并按量级选小数位', () => {
    expect(formatMb(1024)).toBe('1.0 GB')
    expect(formatMb(1536)).toBe('1.5 GB')
    // ≥100 GB 去掉小数：102400 MB = 100 GB
    expect(formatMb(102400)).toBe('100 GB')
  })

  it('null/undefined 显示 —（避免表格出现 "null" 或 "NaN"）', () => {
    expect(formatMb(null)).toBe('—')
    expect(formatMb(undefined)).toBe('—')
  })
})

describe('formatDateTime / dash', () => {
  it('ISO 串 → 本地 YYYY-MM-DD HH:mm（时区无关的往返断言）', () => {
    // 用本地分量构造 → toISOString 转 UTC → 被测函数再转回本地，墙钟应一致
    const local = new Date(2026, 9, 7, 9, 5)
    expect(formatDateTime(local.toISOString())).toBe('2026-10-07 09:05')
  })

  it('空值与非法串都退化成 —，不抛异常', () => {
    expect(formatDateTime(null)).toBe('—')
    expect(formatDateTime('')).toBe('—')
    expect(formatDateTime('not-a-date')).toBe('—')
  })

  it('dash 只对 null/undefined/空串 降级', () => {
    expect(dash(null)).toBe('—')
    expect(dash(undefined)).toBe('—')
    expect(dash('')).toBe('—')
    expect(dash(0)).toBe('0')
  })
})

describe('formatHeartbeat（与 HeartbeatScanner 三段阈值 15s/45s/5min 对齐）', () => {
  const NOW = new Date(2026, 9, 7, 12, 0, 0)

  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(NOW)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  const agoSeconds = (s: number) => new Date(NOW.getTime() - s * 1000).toISOString()

  it('从未上报（null）给语义化文案，而不是 —', () => {
    expect(formatHeartbeat(null)).toBe('从未上报')
  })

  it('一分钟内显示秒，且不下"已超阈值"结论', () => {
    expect(formatHeartbeat(agoSeconds(15))).toBe('15 秒前')
    expect(formatHeartbeat(agoSeconds(45))).toBe('45 秒前')
  })

  it('超过一分钟显式提示已越过 45s 离线阈值（避免误读成健康）', () => {
    expect(formatHeartbeat(agoSeconds(60))).toBe('1 分钟前（已超离线阈值）')
    expect(formatHeartbeat(agoSeconds(600))).toBe('10 分钟前（已超离线阈值）')
  })

  it('一小时以上退化成绝对时间，不再讲"多久以前"', () => {
    expect(formatHeartbeat(agoSeconds(3600))).toBe('1 小时前')
    // 25 小时前 → 走绝对时间分支
    expect(formatHeartbeat(agoSeconds(25 * 3600))).toBe(formatDateTime(agoSeconds(25 * 3600)))
  })
})
