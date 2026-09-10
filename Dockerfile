# ---------------------------------------------------------------
# daily-sync 镜像：三阶段构建
#   1. frontend-build  npm build 前端 → dist
#   2. backend-build   dist 塞进 Spring Boot static 后 mvn package
#   3. runtime         仅 JRE + jar，运行时配置全部走环境变量（见 docker-compose.yml）
# ---------------------------------------------------------------

# ---------- 阶段 1：前端 ----------
FROM node:20-alpine AS frontend-build
WORKDIR /frontend
# 先拷 package*.json 装依赖，利用 Docker 层缓存（源码改动不重装 node_modules）
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ---------- 阶段 2：后端（前端产物进 static，同源部署免 CORS/代理） ----------
FROM maven:3.9-eclipse-temurin-17 AS backend-build
WORKDIR /backend
COPY docker/settings.xml /root/.m2/settings.xml
COPY backend/pom.xml ./
# 先下依赖（pom 未变时命中缓存层）
RUN mvn -q dependency:go-offline
COPY backend/src ./src
COPY --from=frontend-build /frontend/dist ./src/main/resources/static
RUN mvn -q package -DskipTests

# ---------- 阶段 3：运行时 ----------
FROM eclipse-temurin:17-jre
# 与 MySQL/业务时区对齐：审计时间戳按应用本地时间落库（M5 的时区坑）
ENV TZ=Asia/Shanghai
WORKDIR /app
COPY --from=backend-build /backend/target/*.jar app.jar
EXPOSE 8080
# MaxRAMPercentage 让 JVM 感知容器内存限制（compose 限 480m → 堆约 290m），而非吃满宿主机
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60.0", "-jar", "app.jar"]
