import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 后端地址：本地联调若后端换了端口，用环境变量覆盖
const target = process.env.VITE_API_TARGET || 'http://127.0.0.1:8088'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/admin': { target, changeOrigin: true },
      '/api': { target, changeOrigin: true },
      '/files': { target, changeOrigin: true }
    }
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        // 拆开三块体积最大的依赖：它们很少变，能长期命中浏览器缓存，
        // 只改业务代码时用户不用把 1MB 的 Element Plus 重新下一遍
        manualChunks: {
          echarts: ['echarts'],
          element: ['element-plus', '@element-plus/icons-vue'],
          vendor: ['vue', 'vue-router', 'pinia', 'axios']
        }
      }
    }
  }
})
