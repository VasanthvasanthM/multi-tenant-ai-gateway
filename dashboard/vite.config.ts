import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],

  server: {
    port: 5173,
    // Proxy /v1 to the gateway in development.
    //
    // Without it the browser blocks every request as cross-origin, and the fix
    // people reach for first is permissive CORS on the gateway - which means
    // shipping a production service that accepts credentialed requests from any
    // origin, to make a dev server work.
    proxy: {
      '/v1': {
        target: process.env.VITE_GATEWAY_URL ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },

  build: {
    outDir: 'dist',
    sourcemap: true,
  },

  test: {
    environment: 'jsdom',
    // jsdom defaults to "about:blank", whose origin is "null" - so
    // window.location.origin would give a base URL that cannot build a
    // Request. A concrete URL makes the test environment behave like a page.
    environmentOptions: {
      jsdom: { url: 'http://localhost:5173' },
    },
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    coverage: {
      reporter: ['text', 'lcov'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/main.tsx', 'src/test/**'],
    },
  },
});
