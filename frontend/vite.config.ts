import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// docs/04 §1.2：别名 @、代理 /api 与 /ws、分包策略
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      // 开发期后端：flowops-server（8080）。生产由 Nginx 同源反代（deploy/nginx.conf）
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
      },
      '/actuator': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    rollupOptions: {
      output: {
        // 手动分包只列实际存在的依赖；echarts 等 M4 引入后再加
        manualChunks: {
          'element-plus': ['element-plus'],
        },
      },
    },
  },
})
