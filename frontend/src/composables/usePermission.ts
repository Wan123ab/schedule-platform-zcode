import { useAuthStore } from '@/stores/auth'

/** 权限判定（D-16，docs/04 §5.2）：按权限点判定，不硬编码角色。 */
export function usePermission() {
  const auth = useAuthStore()

  const can = (perm?: string): boolean =>
    !perm || auth.permissions.includes(perm) || auth.permissions.includes('*')

  const canOn = (
    perm: string,
    target: { projectId?: string; clusterId?: string; ownerId?: string },
  ): boolean => can(perm) // 数据范围判定 M2 接后端 DataScope 后补齐（40301）

  return { can, canOn }
}
