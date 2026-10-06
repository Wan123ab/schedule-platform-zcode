<script setup lang="ts">
import AppSidebar from './components/AppSidebar.vue'
import AppTopbar from './components/AppTopbar.vue'
</script>

<template>
  <!-- 原型踩坑预防（docs/04 §3.1）：app 用 width:100% + height:100vh；
       .content 已含 min-height:0 + overflow-y:auto（base.css） -->
  <div class="app">
    <AppSidebar />
    <div class="main">
      <AppTopbar />
      <main class="content">
        <router-view />
      </main>
    </div>
  </div>
</template>
