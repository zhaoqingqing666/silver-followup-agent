# 团队开发容器镜像：JDK 17 + Node 22 + git + 常用工具。
# VS Code“在容器中重新打开”时自动构建并启动本镜像。
FROM mcr.microsoft.com/devcontainers/base:ubuntu-22.04

# 国内网络下默认使用可访问性更好的 Ubuntu 镜像；需要时可在构建参数中替换。
ARG UBUNTU_MIRROR=https://mirrors.aliyun.com/ubuntu

# 版本与仓库约定一致：Java 17（后端）、Node 22.13+（前端）。
# 先装 JDK 17 再装 Maven，避免 Ubuntu 为 Maven 额外拉取默认的 Java 11。
RUN printf 'Acquire::Retries "5";\nAcquire::http::Timeout "30";\nAcquire::https::Timeout "30";\n' \
        > /etc/apt/apt.conf.d/80-retries \
    && sed -i \
        -e "s|http://archive.ubuntu.com/ubuntu|${UBUNTU_MIRROR}|g" \
        -e "s|http://security.ubuntu.com/ubuntu|${UBUNTU_MIRROR}|g" \
        /etc/apt/sources.list \
    && apt-get update && export DEBIAN_FRONTEND=noninteractive \
    && apt-get install -y --no-install-recommends curl wget git openjdk-17-jdk-headless \
    && apt-get install -y --no-install-recommends maven \
    && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && npm install -g npm@10 \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"
