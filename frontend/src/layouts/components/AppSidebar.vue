<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useAppStore } from '@/stores/app'
import { usePermission } from '@/composables/usePermission'
import { navGroups, type NavItem } from '@/router/routes'

/**
 * AppSidebar（docs/04 §3.1：230px，导航 + 激活态发光；样式基类来自 base.css 的 .sidebar/.nav-item）。
 * 导航项按权限过滤；由路由表生成，禁止手写 href（F-12）。
 */
const router = useRouter()
const auth = useAuthStore()
const app = useAppStore()
const { can } = usePermission()

const groups = computed(() =>
  navGroups
    .map((g: NavItem) => ({
      ...g,
      children: g.children?.filter((c) => can(c.perm)),
      visible: g.to ? can(g.perm) : (g.children?.filter((c) => can(c.perm)).length ?? 0) > 0,
    }))
    .filter((g) => g.visible),
)

function logout() {
  auth.reset()
  router.push({ name: 'login' })
}
</script>

<template>
  <aside class="sidebar">
    <div class="brand">
      <span class="brand-mark">◆</span>
      <span class="brand-txt">
        <div class="brand-name">FlowOps</div>
        <div class="brand-sub">SCHEDULE PLATFORM</div>
      </span>
    </div>

    <nav class="nav">
      <template v-for="group in groups" :key="group.title">
        <div v-if="group.children" class="nav-title">{{ group.title }}</div>
        <template v-for="item in group.children ?? []" :key="item.to">
          <router-link class="nav-item" :to="item.to">
            <span class="nav-label">{{ item.title }}</span>
          </router-link>
        </template>
        <router-link v-if="group.to" class="nav-item" :to="group.to">
          <span class="nav-label">{{ group.title }}</span>
        </router-link>
      </template>
    </nav>

    <div class="sidebar-foot">
      <div class="env-card">
        <span class="env-dot" />
        <span class="env-txt">
          <b>{{ auth.displayName || auth.username || '未登录' }}</b>
          <span class="tiny">{{ app.viewMode === 'ops' ? '运维视图' : '业务视图' }}</span>
        </span>
      </div>
      <el-button text size="small" style="width: 100%; margin-top: 8px" @click="logout">退出登录</el-button>
    </div>
  </aside>
</template>

<style scoped>
.nav-label {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
