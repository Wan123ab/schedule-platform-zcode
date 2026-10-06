<script setup lang="ts">
// App 根：仅 router-view（docs/04 §1.2）
</script>

<template>
  <router-view />
</template>

<style>
/* 状态标记动画（StatusTag 使用） */
@keyframes pulse {
  0% { opacity: 1; }
  50% { opacity: 0.35; }
  100% { opacity: 1; }
}

@keyframes st-spin {
  to { transform: rotate(360deg); }
}
</style>
