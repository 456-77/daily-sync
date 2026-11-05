import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// 开发代理：/api 转发到后端。默认 8080；测试实例用 API_PROXY=http://localhost:8081 覆盖
export default defineConfig({
  plugins: [react()],
  // M9：站点根让给 quick-note 门户页，本 SPA 部署在 /app 子路径（Dockerfile 把
  // dist 拷到 static/app）。开发态地址随之变为 http://localhost:5173/app/
  base: "/app/",
  server: {
    proxy: {
      "/api": {
        target: process.env.API_PROXY || "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
});
