import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // Allow access from any host on the LAN (e.g. mobile devices on 192.168.x.x)
    host: true,
    proxy: {
      // Proxy all /api requests to the Spring Boot backend during development.
      // changeOrigin: true rewrites the Host header to match the target.
      // The configure hook removes the Origin header so Spring Security's
      // exact-origin CORS check sees a same-origin request (no Origin = no CORS
      // preflight, Spring treats it as a direct backend call and allows it).
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => {
            proxyReq.removeHeader('origin')
          })
        },
      },
    },
  },
  build: {
    outDir: 'dist',
    // Relative asset paths so the build can be served from Spring Boot's /static
    assetsDir: 'assets',
  },
})
