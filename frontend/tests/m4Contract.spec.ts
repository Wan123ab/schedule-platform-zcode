import { describe, expect, it } from 'vitest'
import { ERROR_MESSAGES } from '@/utils/errorMessage'
import { ALERT_EVENT_TYPES } from '@/api/types/alert'
import {
  BACKFILL_STATUS_LABEL,
  BACKFILL_STATUS_TONE,
} from '@/types/enums'

/**
 * M4 四域（任务读侧/干预 · 回填 · 告警 · 工作台）的契约防漂移单测
 * （`workflowContract.spec.ts` 的 M4 续篇）。
 *
 * 【它挡的是什么】
 * ① **查询串命名风格**：`params` 不做键转换（拦截器只转 `config.data`），
 *    任务列表的 `bizDateFrom` / 告警历史的 `eventType` / 回填列表的 `status`
 *    都是后端 `@RequestParam` 的原样驼峰。写错不会编译失败，只会 40001
 *    「缺少必填参数」—— 只能用文本断言挡。
 * ② **回填两段式指纹**：提交体的键必须是 `confirmedPreviewHash`；
 *    预览请求的键是 `bizFrom` / `bizTo`（不是 bizDateFrom —— 两域命名不同源）。
 * ③ **事件类型口径**：9 值以 DDL CHECK + V5 seed 为准（CONTRACT §9 列 5 个
 *    是过时口径，README-M4 勘定）—— 前端下拉少一个值 = 该事件无法订阅。
 * ④ **M4 错误码文案**：42231~42238 / 42241 漏登记 → 用户看到开发视角的英文。
 */

/** 读源码文本（?raw —— 不能用 node:fs，vue-tsc 会连 tests 一起查且项目没装 @types/node）。
 *  import.meta.glob 的参数必须是字面量（Vite 编译期静态解析），不能传变量。 */
function globRaw(key: string): string {
  const modules = import.meta.glob('../src/api/modules/*.ts', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>
  return modules[key] ?? ''
}

const TASK_API = globRaw('../src/api/modules/task.ts')
const BACKFILL_API = globRaw('../src/api/modules/backfill.ts')
const ALERT_API = globRaw('../src/api/modules/alert.ts')
const DASHBOARD_API = globRaw('../src/api/modules/dashboard.ts')

/** 查询键的**类型声明**在 types 文件（modules 里只有透传），命名防漂移要看类型源 */
function globTypeRaw(key: string): string {
  const modules = import.meta.glob('../src/api/types/*.ts', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>
  return modules[key] ?? ''
}

const TASK_TYPES = globTypeRaw('../src/api/types/task.ts')
const BACKFILL_TYPES = globTypeRaw('../src/api/types/backfill.ts')

describe('M4 查询串命名（params 不做键转换 —— README-M3 既定口径）', () => {
  it('任务列表：bizDateFrom / bizDateTo / triggerType 必须是原样驼峰（键名声明在 types）', () => {
    expect(TASK_TYPES).toContain('bizDateFrom')
    expect(TASK_TYPES).toContain('bizDateTo')
    expect(TASK_TYPES).toContain('triggerType')
    // 蛇形变体不允许出现（防"顺手矫正"）
    expect(TASK_TYPES).not.toContain('biz_date_from')
    expect(TASK_TYPES).not.toContain('biz_date_to')
    expect(TASK_TYPES).not.toContain('trigger_type')
    // 视图取值处同样是驼峰（date-picker 绑定到 params 的路径）
    expect(TASK_API).not.toContain('biz_date_from')
  })

  it('告警历史：eventType / from / to（后端无 level/result 过滤参数）', () => {
    expect(ALERT_API).toContain('eventType')
    expect(ALERT_API).not.toContain('event_type')
    // 这两个参数后端 history 端点不存在 —— 传了也不会生效，钉住防止假过滤
    expect(ALERT_API).not.toContain('level?:')
    expect(ALERT_API).not.toContain('result?:')
  })

  it('工作台趋势：days 参数（后端 cap 30）', () => {
    expect(DASHBOARD_API).toContain('days')
  })

  it('回填列表：status / page / pageSize', () => {
    expect(BACKFILL_API).toContain('pageSize')
  })
})

describe('M4 回填两段式指纹（CONTRACT §8）', () => {
  it('提交体的键必须是 confirmedPreviewHash（不符 → 42231 预览失效）', () => {
    expect(BACKFILL_API).toContain('confirmedPreviewHash')
    expect(BACKFILL_API).toContain("url: '/backfills', method: 'POST'")
  })

  it('预览请求的键是 bizFrom / bizTo（不是 bizDateFrom —— 与任务列表命名不同源）', () => {
    expect(BACKFILL_TYPES).toContain('bizFrom')
    expect(BACKFILL_TYPES).toContain('bizTo')
  })

  it('批次操作的三个端点路径与后端一致（pause/resume/retry-failed）', () => {
    expect(BACKFILL_API).toContain('/pause')
    expect(BACKFILL_API).toContain('/resume')
    expect(BACKFILL_API).toContain('/retry-failed')
  })
})

describe('M4 事件类型口径（DDL CHECK 9 值 + V5 seed 为准，README-M4 勘定）', () => {
  it('前端单一来源必须是 9 值', () => {
    expect(ALERT_EVENT_TYPES).toHaveLength(9)
  })

  it('CONTRACT §9 列举的 5 个过时口径值全部包含在内', () => {
    for (const t of ['TASK_FAILED', 'TASK_TIMEOUT', 'NODE_OFFLINE', 'CONCURRENCY_LIMIT', 'PLATFORM_ERROR']) {
      expect(ALERT_EVENT_TYPES).toContain(t)
    }
  })

  it('scheduler 已接线的 4 个 MVP 事件全部在列（S4 交付）', () => {
    for (const t of ['TASK_FAILED', 'TASK_TIMEOUT', 'STEP_RETRY_EXHAUSTED', 'NODE_OFFLINE']) {
      expect(ALERT_EVENT_TYPES).toContain(t)
    }
  })
})

describe('M4 错误码文案覆盖（docs/07 §4）', () => {
  const M4_CODES = [
    42231, // 预览已失效（指纹不符）
    42232, // 回填范围超限
    42233, // 任务当前状态不可停止
    42234, // 停止原因过短
    42235, // 任务非终态不可重跑
    42236, // 重试次数已达上限（步骤级自动重试链路，O-53）
    42237, // 任务不在等待态不可插队
    42238, // 回填批次状态不允许该操作
    42241, // 告警条件 JSON 不合法
  ]

  it('M4 会到达前端的九个码全部有中文文案', () => {
    M4_CODES.forEach((code) => {
      expect(ERROR_MESSAGES[code], `缺少 ${code} 的用户文案`).toBeTruthy()
    })
  })

  it('42231 的文案指向"重新预览"这个正确动作', () => {
    expect(ERROR_MESSAGES[42231]).toContain('预览')
  })

  it('42233 与 42235 文案互不混淆（停止 vs 重跑的状态门槛）', () => {
    expect(ERROR_MESSAGES[42233]).not.toBe(ERROR_MESSAGES[42235])
    expect(ERROR_MESSAGES[42233]).toContain('停止')
    expect(ERROR_MESSAGES[42235]).toContain('重跑')
  })
})

describe('M4 状态映射全覆盖（Record 缺项 = vue-tsc 编译失败，这里再钉语义）', () => {
  it('回填批次状态 5 值（V1 CHECK）齐全', () => {
    expect(Object.keys(BACKFILL_STATUS_LABEL).sort()).toEqual(
      ['CANCELLED', 'DONE', 'FAILED', 'PAUSED', 'RUNNING'].sort(),
    )
    expect(Object.keys(BACKFILL_STATUS_TONE).sort()).toEqual(
      ['CANCELLED', 'DONE', 'FAILED', 'PAUSED', 'RUNNING'].sort(),
    )
  })
})

describe('M4 端点路径（与后端 @RequestMapping 一一对应）', () => {
  it('任务域：diagnosis 与四个干预动作', () => {
    expect(TASK_API).toContain('/diagnosis')
    expect(TASK_API).toContain('/stop')
    expect(TASK_API).toContain('/retry')
    expect(TASK_API).toContain('/rerun-failed')
    expect(TASK_API).toContain('/enqueue-front')
  })

  it('工作台：7 个 /dashboard/* 端点 + /platform/health', () => {
    for (const p of ['/summary', '/task-trend', '/resource-usage', '/cluster-health', '/queue-backlog', '/recent-failures', '/latest-alerts']) {
      expect(DASHBOARD_API).toContain(`/dashboard${p}`)
    }
    expect(DASHBOARD_API).toContain("url: '/platform/health'")
  })

  it('告警域：/alert-rules + /alert-channels + /alert-history', () => {
    expect(ALERT_API).toContain("url: '/alert-rules'")
    expect(ALERT_API).toContain("url: '/alert-channels'")
    expect(ALERT_API).toContain("url: '/alert-history'")
  })
})
