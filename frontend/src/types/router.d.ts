import 'vue-router'

/**
 * 路由 meta 类型（docs/04 §3.2 的 interface RouteMeta）。
 */
declare module 'vue-router' {
  interface RouteMeta {
    title: string
    /** 免登录页（/login /403 /404） */
    public?: boolean
    /** 权限点，如 schedule:task:stop（一律取 PERM 常量） */
    perm?: string
    scope?: 'ALL' | 'PROJECT' | 'CLUSTER' | 'SELF'
    /** 列表页返回时保留筛选与滚动（默认 true） */
    keepAlive?: boolean
  }
}

export {}
