import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// 开发代理：/api 转发到后端。默认 8080；测试实例用 API_PROXY=http://localhost:8081 覆盖
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": {
        target: process.env.API_PROXY || "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
});
