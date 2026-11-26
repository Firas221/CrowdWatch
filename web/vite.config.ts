import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// The built console goes into the Spring Boot jar (server/src/main/resources/static).
// `npm run dev` serves it on http://localhost:5173 and passes API calls to a CrowdWatch server on 8080.
const server = process.env.CROWDWATCH_SERVER ?? 'http://localhost:8080';
const passOn = ['/api', '/legacy', '/console', '/console.css', '/console.js', '/i18n.js', '/classic', '/scout', '/help', '/health'];

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../server/src/main/resources/static',
    emptyOutDir: true,
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        // the big libraries change rarely, so browsers keep them between console updates
        manualChunks(id) {
          if (id.includes('node_modules/echarts') || id.includes('node_modules/zrender')) return 'charts';
          if (id.includes('node_modules/@mui') || id.includes('node_modules/@emotion')) return 'mui';
          if (id.includes('node_modules')) return 'vendor';
        },
      },
    },
  },
  server: {
    proxy: {
      ...Object.fromEntries(passOn.map((p) => [p, server])),
      '/ws': { target: server.replace(/^http/, 'ws'), ws: true },
    },
  },
  test: { environment: 'node' },
});
