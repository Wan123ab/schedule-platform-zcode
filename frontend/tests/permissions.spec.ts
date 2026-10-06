import { describe, expect, it } from 'vitest'
import { PERM, PERM_COUNT } from '@/constants/permissions'
import { routes } from '@/router/routes'

/**
 * 权限点防漂移单测（docs/04 §5.2 配套工程手段）：
 * ① 路由表出现的每个 meta.perm 必须 ∈ PERM；
 * ② PERM 条目数 = 49（docs/07 §5.2）。
 */
describe('permissions registry', () => {
  it('PERM 与 docs/07 §5.2 的 49 点一致', () => {
    expect(PERM_COUNT).toBe(49)
  })

  it('路由表 meta.perm 全部来自 PERM 常量表', () => {
    const perms = new Set<string>()
    const walk = (records: typeof routes) => {
      records.forEach((r) => {
        if (r.meta?.perm) {
          perms.add(r.meta.perm as string)
        }
        if (r.children) {
          walk(r.children as typeof routes)
        }
      })
    }
    walk(routes)
    const permValues = new Set(Object.values(PERM))
    expect(perms.size).toBeGreaterThan(0)
    perms.forEach((p) => expect(permValues.has(p as never)).toBe(true))
  })

  it('路由表覆盖 19 个业务页面', () => {
    const names = new Set<string>()
    const walk = (records: typeof routes) => {
      records.forEach((r) => {
        if (r.name) {
          names.add(String(r.name))
        }
        if (r.children) {
          walk(r.children as typeof routes)
        }
      })
    }
    walk(routes)
    const expected = [
      'login', 'dashboard', 'projectList', 'clusterList', 'clusterDetail', 'nodeDetail',
      'credentialList', 'operatorList', 'operatorDetail', 'operatorVersion',
      'workflowList', 'workflowEditor', 'workflowEditorNew', 'workflowDetail',
      'taskList', 'taskDetail', 'backfill', 'alertConfig', 'auditLog', 'platformHealth',
    ]
    expected.forEach((n) => expect(names.has(n)).toBe(true))
  })
})
