# 一键 Docker 部署（frontend 生产服务 + backend Java API）。
# 团队日常开发请用 .devcontainer（见 README.devcontainer.md）；本文件用于演示/交付镜像。
#
# docker compose 会自动构建本文件并运行前端 + 后端两个服务。
# 手动构建单个镜像：
#   docker build --target silver-api --tag silver-api:dev .
#   docker build --target silver-web --tag silver-web:dev .

# ---------- 阶段 1：构建后端 jar ----------
FROM maven:3.9-eclipse-temurin-17 AS api-build
WORKDIR /src
COPY backend/pom.xml ./backend/pom.xml
# 先拉依赖（充分利用构建缓存），再拷源码编译
RUN mvn -B -f backend/pom.xml dependency:go-offline
COPY backend/src ./backend/src
RUN mvn -B -f backend/pom.xml clean package -DskipTests

# ---------- 阶段 2：后端运行时 ----------
FROM eclipse-temurin:17-jre AS silver-api
WORKDIR /app
# H2 文件库放到挂载卷的持久化目录（相对路径会让数据落到容器内不确定位置）
ENV SPRING_DATASOURCE_URL=jdbc:h2:file:/app/data/silver-agent
COPY --from=api-build /src/backend/target/*.jar /app/app.jar
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080; printf 'GET /api/demo/health HTTP/1.0\\r\\n\\r\\n' >&3; grep -q '200' <&3"]
# 本地运行与容器部署的默认值保持一致，便于排查
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# ---------- 阶段 3：前端（构建 + 生产运行于同一镜像） ----------
# 前端基于 vinext（Vite 的 RSC/SSR 框架），`vinext start` 是官方生产服务器，
# 不能用静态文件托管，故不拆 nginx。
FROM node:22-alpine AS silver-web
WORKDIR /app
# 浏览器访问后端的地址在构建期就写入 JS 产物；部署到别的机器用 build-arg 覆盖
ARG NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
ARG NEXT_PUBLIC_AMAP_JS_KEY=
ARG NEXT_PUBLIC_AMAP_SECURITY_CODE=
ENV NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL}
ENV NEXT_PUBLIC_AMAP_JS_KEY=${NEXT_PUBLIC_AMAP_JS_KEY}
ENV NEXT_PUBLIC_AMAP_SECURITY_CODE=${NEXT_PUBLIC_AMAP_SECURITY_CODE}
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend ./
RUN npm run build
EXPOSE 3000
# vinext/Next 兼容：PORT 为 3000、HOSTNAME 为空即监听所有网卡
ENV PORT=3000 HOSTNAME=0.0.0.0
CMD ["npm", "run", "start"]
