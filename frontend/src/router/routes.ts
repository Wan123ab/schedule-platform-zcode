import type { RouteRecordRaw } from 'vue-router'
import { PERM } from '@/constants/permissions'

/**
 * 路由表 ↔ 原型 19 页映射（docs/04 §3.3，交付对照基准）。
 * meta.perm 一律取 PERM 常量（禁止字面量）；tests/permissions.spec.ts 断言无漂移。
 */
export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/auth/LoginView.vue'),
    meta: { title: '登录', public: true },
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    children: [
      { path: '', redirect: { name: 'dashboard' } },
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/dashboard/DashboardView.vue'),
        meta: { title: '工作台' }, // 登录即可（docs/04 §3.3 #2）
      },
      {
        path: 'projects',
        name: 'projectList',
        component: () => import('@/views/project/ProjectListView.vue'),
        meta: { title: '项目空间', perm: PERM.PROJECT_READ },
      },
      {
        path: 'clusters',
        name: 'clusterList',
        component: () => import('@/views/cluster/ClusterListView.vue'),
        meta: { title: '集群列表', perm: PERM.CLUSTER_READ },
      },
      {
        path: 'clusters/:clusterId',
        name: 'clusterDetail',
        component: () => import('@/views/cluster/ClusterDetailView.vue'),
        meta: { title: '集群详情', perm: PERM.CLUSTER_READ },
      },
      {
        path: 'executor-nodes/:nodeId',
        name: 'nodeDetail',
        component: () => import('@/views/cluster/NodeDetailView.vue'),
        meta: { title: '节点详情', perm: PERM.NODE_READ },
      },
      {
        path: 'credentials',
        name: 'credentialList',
        component: () => import('@/views/credential/CredentialListView.vue'),
        meta: { title: '凭据管理', perm: PERM.CREDENTIAL_READ },
      },
      {
        path: 'operators',
        name: 'operatorList',
        component: () => import('@/views/operator/OperatorListView.vue'),
        meta: { title: '算子列表', perm: PERM.OPERATOR_READ },
      },
      {
        path: 'operators/:operatorId',
        name: 'operatorDetail',
        component: () => import('@/views/operator/OperatorDetailView.vue'),
        meta: { title: '算子详情', perm: PERM.OPERATOR_READ },
      },
      {
        path: 'operators/:operatorId/versions/:versionId',
        name: 'operatorVersion',
        component: () => import('@/views/operator/OperatorVersionView.vue'),
        meta: { title: '算子版本', perm: PERM.OPERATOR_READ },
      },
      {
        path: 'workflows',
        name: 'workflowList',
        component: () => import('@/views/workflow/WorkflowListView.vue'),
        meta: { title: '工作流列表', perm: PERM.WORKFLOW_READ },
      },
      {
        path: 'workflows/new',
        name: 'workflowEditorNew',
        component: () => import('@/views/workflow/WorkflowEditorView.vue'),
        meta: { title: '新建工作流', perm: PERM.WORKFLOW_WRITE },
      },
      {
        path: 'workflows/:workflowId/edit',
        name: 'workflowEditor',
        component: () => import('@/views/workflow/WorkflowEditorView.vue'),
        meta: { title: '工作流编辑器', perm: PERM.WORKFLOW_WRITE },
      },
      {
        path: 'workflows/:workflowId',
        name: 'workflowDetail',
        component: () => import('@/views/workflow/WorkflowDetailView.vue'),
        meta: { title: '工作流详情', perm: PERM.WORKFLOW_READ },
      },
      {
        path: 'tasks',
        name: 'taskList',
        component: () => import('@/views/task/TaskListView.vue'),
        meta: { title: '任务列表', perm: PERM.TASK_READ },
      },
      {
        path: 'tasks/:taskId',
        name: 'taskDetail',
        component: () => import('@/views/task/TaskDetailView.vue'),
        meta: { title: '任务详情', perm: PERM.TASK_READ },
      },
      {
        path: 'backfills',
        name: 'backfill',
        component: () => import('@/views/backfill/BackfillView.vue'),
        meta: { title: '回填补数', perm: PERM.BACKFILL_READ },
      },
      {
        path: 'alerts',
        name: 'alertConfig',
        component: () => import('@/views/alert/AlertConfigView.vue'),
        meta: { title: '告警配置', perm: PERM.ALERT_READ },
      },
      {
        path: 'audit-logs',
        name: 'auditLog',
        component: () => import('@/views/audit/AuditLogView.vue'),
        meta: { title: '审计日志', perm: PERM.AUDIT_READ },
      },
      {
        path: 'platform/health',
        name: 'platformHealth',
        component: () => import('@/views/platform/PlatformHealthView.vue'),
        meta: { title: '平台健康度', perm: PERM.PLATFORM_HEALTH }, // 第 19 页（D-24）
      },
      {
        path: '403',
        name: 'forbidden',
        component: () => import('@/views/error/ForbiddenView.vue'),
        meta: { title: '无权限', public: true },
      },
      {
        path: ':pathMatch(.*)*',
        name: 'notFound',
        component: () => import('@/views/error/NotFoundView.vue'),
        meta: { title: '页面不存在', public: true },
      },
    ],
  },
]

/** 侧栏导航（docs/04 §3.4：由路由表生成，禁止手写 href —— F-12 预防）。 */
export interface NavItem {
  title: string
  to?: string
  perm?: string
  children?: { title: string; to: string; perm?: string }[]
}

export const navGroups: NavItem[] = [
  { title: '工作台', to: '/dashboard' },
  {
    title: '任务管理',
    children: [
      { title: '任务列表', to: '/tasks', perm: PERM.TASK_READ },
      { title: '回填补数', to: '/backfills', perm: PERM.BACKFILL_READ },
    ],
  },
  {
    title: '工作流管理',
    children: [{ title: '工作流列表', to: '/workflows', perm: PERM.WORKFLOW_READ }],
  },
  {
    title: '集群管理',
    children: [{ title: '集群列表', to: '/clusters', perm: PERM.CLUSTER_READ }],
  },
  {
    title: '算子管理',
    children: [{ title: '算子列表', to: '/operators', perm: PERM.OPERATOR_READ }],
  },
  {
    title: '系统管理',
    children: [
      { title: '项目空间', to: '/projects', perm: PERM.PROJECT_READ },
      { title: '凭据管理', to: '/credentials', perm: PERM.CREDENTIAL_READ },
      { title: '告警配置', to: '/alerts', perm: PERM.ALERT_READ },
      { title: '审计日志', to: '/audit-logs', perm: PERM.AUDIT_READ },
      { title: '平台健康度', to: '/platform/health', perm: PERM.PLATFORM_HEALTH },
    ],
  },
]
