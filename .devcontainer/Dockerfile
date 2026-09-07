# 团队开发容器镜像：JDK 17 + Node 22 + git + 常用工具。
# VS Code“在容器中重新打开”时自动构建并启动本镜像。
FROM mcr.microsoft.com/devcontainers/base:ubuntu-22.04

# 版本与仓库约定一致：Java 17（后端）、Node 22.13+（前端）
RUN apt-get update && export DEBIAN_FRONTEND=noninteractive \
    && apt-get install -y --no-install-recommends curl wget git openjdk-17-jdk maven \
    && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && npm install -g npm@10 \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"
