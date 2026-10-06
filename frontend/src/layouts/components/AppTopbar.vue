<script setup lang="ts">
import { useRoute } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { usePermission } from '@/composables/usePermission'
import { PERM } from '@/constants/permissions'

/** AppTopbar（docs/04 §3.1：62px，含视图标识 + 明暗开关；渐变分割线来自 base.css .topbar::after）。 */
const route = useRoute()
const app = useAppStore()
const { can } = usePermission()

const canOps = can(PERM.PLATFORM_VIEW_OPS)
</script>

<template>
  <header class="topbar">
    <div class="crumb">
      <b>{{ route.meta.title }}</b>
    </div>
    <div class="topbar-right">
      <div v-if="canOps" class="view-switch" :class="{ disabled: !canOps }">
        <button :class="{ on: app.viewMode === 'business' }" @click="app.setViewMode('business')">业务视图</button>
        <button :class="{ on: app.viewMode === 'ops' }" @click="app.setViewMode('ops')">运维视图</button>
      </div>
      <el-switch
        :model-value="app.theme === 'light'"
        inline-prompt
        active-text="浅"
        inactive-text="深"
        @change="(v: string | number | boolean) => app.setTheme(v ? 'light' : 'dark')"
      />
    </div>
  </header>
  <div class="view-banner">
    <span class="vt-dot" />
    当前为运维视图 · 日志与参数默认脱敏
  </div>
</template>
