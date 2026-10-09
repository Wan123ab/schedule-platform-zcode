import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

/**
 * 单测配置（与 `vite.config.ts` 分开：构建产物与测试不需要同一套插件链）。
 *
 * 【为什么要加 plugin-vue】`tests/dagCanvas.spec.ts` 要**真的挂载**画布组件 ——
 * 而 `.vue` 单文件组件必须先经 plugin-vue 编译成 JS，否则 vitest 会把
 * `<script setup>` 当成普通 JS 解析并报 "invalid JS syntax"。
 * 纯函数测试（dag / casename / format / contract）用不到它，加了也无副作用。
 */
export default defineConfig({
  plugins: [vue()],
  test: {
    // 默认 node 环境（快）。需要 DOM 的用例在文件头用 `// @vitest-environment jsdom`
    // 单独声明 —— 全局换成 jsdom 会让所有纯函数测试白白多背一个 DOM 实现
    environment: 'node',
    include: ['tests/**/*.spec.ts'],
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
})
