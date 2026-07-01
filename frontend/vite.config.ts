/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { fileURLToPath, URL } from 'node:url'

// mode 来自 Vite CLI --mode 参数（见下方 package.json 脚本）
//   npm run build        → mode = 'production'（默认）→ base: './'
//   npm run build:new    → mode = 'production-new'    → base: '/'
export default defineConfig(({ mode }) => ({
  base: mode === 'production-new' ? '/' : './',
  plugins: [
    vue(),
    tailwindcss(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    outDir: 'dist',
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    include: ['src/tests/**/*.test.ts'],
  },
}))
