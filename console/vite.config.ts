import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// 产物直接进 Spring Boot 的 static 目录由 Dockerfile 拷贝，这里只负责产 dist。
// 开发时用 vite dev server 代理 /admin/api，避免为本地调试去配 CORS。
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const proxyTarget = env.CONSOLE_PROXY || 'http://127.0.0.1:8090'

  return {
    plugins: [vue()],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    build: {
      outDir: 'dist',
      emptyOutDir: true,
      // 单机后台，没必要为一两百 KB 的 chunk 告警
      chunkSizeWarningLimit: 1200,
      rollupOptions: {
        output: {
          manualChunks(id) {
            if (id.includes('node_modules/naive-ui') || id.includes('node_modules/@css-render')) return 'naive'
            if (id.includes('node_modules/vue') || id.includes('node_modules/vue-router')) return 'vue'
            return undefined
          },
        },
      },
    },
    server: {
      port: 5173,
      proxy: {
        '/admin/api': { target: proxyTarget, changeOrigin: false },
      },
    },
    // 预览构建产物时也要能打后端，否则只能看个空壳
    preview: {
      port: 4173,
      proxy: {
        '/admin/api': { target: proxyTarget, changeOrigin: false },
      },
    },
  }
})
