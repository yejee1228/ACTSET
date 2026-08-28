import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 프런트 빌드 산출물은 Spring Boot static/에 내장한다(docs/09) — 개발 중에는
// dev 서버(5173)에서 /api 요청을 8080(Spring Boot web 프로필)으로 프록시한다.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
  },
});
