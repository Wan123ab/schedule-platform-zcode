<script setup lang="ts">
/** 页面骨架占位：M0 交付 19 页路由骨架；各页面功能按里程碑逐页落地（docs/09）。 */
defineProps<{
  subtitle?: string
  milestones?: string
}>()
</script>

<template>
  <div>
    <div class="page-header">
      <h1>{{ $route.meta.title }}</h1>
      <div class="sub">
        <span>{{ subtitle ?? 'M0 工程骨架占位页' }}</span>
        <span v-if="milestones" class="mono">· 计划落地：{{ milestones }}</span>
      </div>
    </div>

    <div class="panel" style="padding: 42px 20px; text-align: center">
      <div style="font-size: 30px; margin-bottom: 10px">🏗️</div>
      <div style="font-size: 13.5px; color: var(--t1); margin-bottom: 6px">
        页面结构已就位，交互随里程碑落地
      </div>
      <div style="font-size: 12px; color: var(--t3)">
        路由与权限点已按 docs/04 §3.3 挂载；页面交互以 prototype/ 同名页为验收基准
      </div>
    </div>
  </div>
</template>

<style scoped>
.mono {
  font-family: var(--mono);
}
</style>
