import { describe, expect, it } from 'vitest'
import { ERROR_MESSAGES, messageOf } from '@/utils/errorMessage'
import { PERM } from '@/constants/permissions'
import { WORKFLOW_DEFAULT_SORT, WORKFLOW_SORTABLE, WORKFLOW_SORT_LABEL } from '@/api/modules/workflow'

/**
 * 工作流 / 算子 / 触发器域的契约防漂移单测（`assetContract.spec.ts` 的 M3 续篇）。
 *
 * 【它挡的是什么】
 * ① **错误码文案漏登记**：`ERROR_MESSAGES` 是 `Record<number, string>`，TS 只保证
 *    "写了的键是数字"，保证不了"该有的码写全了"。M3 落地了 42210~42218 九个码，
 *    漏一个用户看到的就是后端那句给开发看的英文 message（I-03）。
 * ② **排序白名单漂移**：`GET /workflows` 的 `orderBy` 是**唯一**进 ORDER BY 片段的
 *    查询参数（docs/07 §7.4），后端用白名单挡列名注入。前端把白名单的**取值集合**
 *    复制了一份做下拉选项 —— 这份复制一旦与后端不同步，用户选中一个"前端有、后端没有"
 *    的值就会拿到 40003，而且报错看起来像"用户传了非法排序字段"。
 * ③ **查询串的命名风格**：`params` 不做键转换（拦截器只转 `data`），所以
 *    `/triggers/cron-preview` 的参数名必须是蛇形 `cron_expression`。
 *    写成 `cronExpression` 不会编译失败、不会 lint 失败，只会 40001「缺少必填参数」——
 *    而那条消息完全不会提示"是命名风格的问题"。这类"改对了一半"的风险只能用文本断言挡。
 */
describe('M3 错误码文案覆盖（docs/07 §4.2）', () => {
  /** 只列 M3 已落地、且会到达前端的码。 */
  const M3_CODES = [
    42210, // 算子版本上传校验失败（含 dry-run 参数非法，O-32）
    42211, // 算子版本被工作流引用，禁止删除
    42212, // 非草稿版本不可编辑
    42213, // DAG 校验失败（结构）
    42214, // 变量引用无效
    42215, // 存在未发布草稿
    42216, // cron 表达式 / 周期配置非法
    42217, // 触发器生效窗口非法
    42218, // 引用了未发布的算子版本
  ]

  it('M3 九个码全部有中文文案', () => {
    M3_CODES.forEach((code) => {
      expect(ERROR_MESSAGES[code], `缺少 ${code} 的用户文案`).toBeTruthy()
    })
  })

  it('三个 DAG 相关码的文案互不相同（42213 结构 / 42214 变量 / 42218 算子版本）', () => {
    const set = new Set([messageOf(42213, ''), messageOf(42214, ''), messageOf(42218, '')])
    expect(set.size).toBe(3)
  })

  it('42212 的文案指向"新开草稿"这个正确动作，而不是"重试"', () => {
    expect(messageOf(42212, '')).toContain('草稿')
  })

  it('42215 的文案点明"已有草稿"（不是"创建失败"）', () => {
    expect(messageOf(42215, '')).toContain('草稿')
  })

  it('42216 的文案带出方言（Spring 6 段）——否则用户会按 Quartz 7 段反复试', () => {
    const text = messageOf(42216, '')
    expect(text).toContain('6 段')
    expect(text).toContain('秒')
  })
})

describe('工作流域权限点（docs/07 §5.2）', () => {
  it('工作流与触发器五个权限点都已在 PERM 常量表里', () => {
    const expected = [
      'schedule:workflow:read',
      'schedule:workflow:write',
      'schedule:workflow:publish',
      'schedule:trigger:read',
      'schedule:trigger:write',
    ]
    const values = new Set(Object.values(PERM))
    expected.forEach((p) => expect(values.has(p as never), `PERM 缺少 ${p}`).toBe(true))
  })

  it('工作流两页用到的权限点都取自 PERM，而不是字面量', () => {
    expect(PERM.WORKFLOW_READ).toBe('schedule:workflow:read')
    expect(PERM.WORKFLOW_WRITE).toBe('schedule:workflow:write')
    expect(PERM.WORKFLOW_PUBLISH).toBe('schedule:workflow:publish')
    expect(PERM.TRIGGER_WRITE).toBe('schedule:trigger:write')
  })
})

describe('工作流列表排序白名单（docs/07 §7.4）', () => {
  /**
   * 与后端 `WorkflowService.SORTABLE` 逐字对应。
   * 核对命令：`grep -A6 'SORTABLE = Map.of' backend/flowops-server/src/main/java/com/flowops/modules/workflow/service/WorkflowService.java`
   */
  const BACKEND_WHITELIST = ['updated_at', 'created_at', 'last_run_at', 'workflow_name', 'status']

  it('与后端白名单取值集合完全一致（顺序也一致，便于人眼比对）', () => {
    expect([...WORKFLOW_SORTABLE]).toEqual(BACKEND_WHITELIST)
  })

  it('每一个取值都是 snake_case（写成 updatedAt 会静默 40003）', () => {
    WORKFLOW_SORTABLE.forEach((key) => {
      expect(key, `${key} 不是 snake_case`).toMatch(/^[a-z][a-z0-9_]*$/)
    })
  })

  it('默认排序必须在白名单内（否则首屏就 40003）', () => {
    expect(WORKFLOW_SORTABLE).toContain(WORKFLOW_DEFAULT_SORT)
  })

  it('每个取值都有中文选项名（Record 已由类型约束，这里防的是运行期被改成部分映射）', () => {
    WORKFLOW_SORTABLE.forEach((key) => {
      expect(WORKFLOW_SORT_LABEL[key], `${key} 缺少选项文案`).toBeTruthy()
    })
  })
})

describe('查询串命名风格（params 不做键转换）', () => {
  /**
   * 把源码当**文本**读进来断言，而不是断言"函数被调用后的行为"。
   *
   * 【为什么非要读文本】这些参数名根本走不到运行期：写错不会编译失败、不会 lint 失败，
   * 只会在真调后端时拿到 40001「缺少必填参数」，而那条消息不会提示"是命名风格的问题"。
   * 本项目在后端已有同款做法（`MapperXmlSchemaConsistencyTest` 读迁移脚本与 Mapper XML），
   * 这里把同一条思路用到前端：**把"两端必须逐字一致的字面量"钉在文本上**。
   *
   * 【为什么用 import.meta.glob 而不是 node:fs】`vue-tsc --noEmit` 会把 tests/ 一起检查，
   * 而项目没装 `@types/node`（tsconfig 的 types 只有 vite/client）—— 用 node:fs 直接编译失败。
   * `?raw` 是 Vite 原生的"把文件当字符串导入"，无需额外类型包。
   */
  const WORKFLOW_API_SOURCE: string = (
    import.meta.glob('../src/api/modules/workflow.ts', {
      query: '?raw',
      import: 'default',
      eager: true,
    }) as Record<string, string>
  )['../src/api/modules/workflow.ts']

  it('源码确实被读进来了（读不到时下面的断言会恒真，等于没测）', () => {
    expect(WORKFLOW_API_SOURCE).toContain('export const workflowApi')
  })

  it('cron-preview 的参数名是蛇形 cron_expression', () => {
    // 后端 @RequestParam("cron_expression")；写成 camelCase 只会 40001
    expect(WORKFLOW_API_SOURCE).toContain('cron_expression: cronExpression')
  })

  it('排序参数名保持驼峰 orderBy / orderDir（写成 order_by 只会 40001）', () => {
    // 这两个是"名字驼峰、值蛇形"的组合，最容易互相污染：
    // 把名字一并转成 snake（order_by）或把值转成 camel（updatedAt）都会坏，且都不报编译错
    expect(WORKFLOW_API_SOURCE).not.toContain('order_by:')
    expect(WORKFLOW_API_SOURCE).not.toContain("'updatedAt'")
  })
})
