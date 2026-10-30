import path from 'node:path'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

/**
 * `/ui/` only for the production build.
 *
 * The backend serves the built bundle from `src/main/resources/static/ui/`, so every
 * asset URL is absolute under that prefix. In dev the app lives at the server root
 * (`localhost:5173/`) because Vite owns routing there and `/ui/` would just be noise.
 */
export default defineConfig(({ command }) => ({
  base: command === 'build' ? '/ui/' : '/',
  plugins: [react()],
  resolve: {
    alias: { '@': path.resolve(__dirname, 'src') },
  },
  server: {
    port: 5173,
    // Same-origin `/v1/**` in dev, so the API layer never needs a base URL and the
    // production build needs no CORS at all.
    proxy: {
      '/v1': { target: 'http://localhost:8080', changeOrigin: true },
      '/actuator': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
    rollupOptions: {
      output: {
        /**
         * Split by library, not by route.
         *
         * AntD and Recharts together are ~1.6 MB minified in one chunk, which means any
         * change to one page invalidates the whole bundle for every visitor. Split this
         * way, a page fix only invalidates the app chunk; the vendor chunks keep their
         * hashes and stay cached.
         *
         * Route-level `React.lazy` splitting is the next step, not this one: three of the
         * four pages are opened within a minute of each other in normal use.
         */
        manualChunks: {
          'vendor-react': ['react', 'react-dom', 'react-router-dom'],
          'vendor-antd': ['antd', '@ant-design/icons'],
          'vendor-charts': ['recharts'],
          'vendor-data': ['@tanstack/react-query', 'axios', 'zustand'],
        },
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    /**
     * 60s, not the 5s default and not the 30s this used to be.
     *
     * Opening a drawer that contains a table, a list and a form takes a couple of seconds
     * in jsdom, and v8 coverage instrumentation roughly doubles that; the same test that
     * passes in 7s locally times out at 5s, then again at 15s, in `test:coverage`. On
     * GitHub's 2-core `ubuntu-latest` runner the slowest create/drawer tests exceeded the
     * 30s ceiling (CI runs 13, 14 and 15), while the same suite stays well under it
     * locally. A per-test override would have to be re-discovered by whoever adds the
     * next page test, so the budget is stated once here; the two create test files that
     * already carried an explicit 30s argument use 60s too. Every assertion is explicit,
     * so a genuine hang still fails — it just takes longer to say so.
     */
    testTimeout: 60_000,
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/test/**', 'src/main.tsx', 'src/vite-env.d.ts', '**/*.d.ts'],
    },
  },
}))
