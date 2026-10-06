import type { Router } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { usePermission } from '@/composables/usePermission'

/**
 * 全局路由守卫（docs/04 §3.2）：
 * 标题 → 白名单 → 登录态 → 权限点（403 视图，非静默拦截）。
 */
export function setupGuard(router: Router) {
  router.beforeEach((to) => {
    document.title = `${to.meta.title ?? 'FlowOps'} · FlowOps`

    if (to.meta.public) {
      return true
    }
    const auth = useAuthStore()
    if (!auth.isLoggedIn) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
    // 权限点判定（D-16）；meta.perm 缺失 = 登录即可
    const { can } = usePermission()
    if (to.meta.perm && !can(to.meta.perm as string)) {
      return { name: 'forbidden' }
    }
    return true
  })
}
