#!/usr/bin/env bash
set -euo pipefail

cd /workspace

# 把 H2 数据目录、target 与前端依赖目录挂进具名卷（不占仓库磁盘），并保证 vscode 用户可写
sudo mkdir -p backend/data backend/target frontend/node_modules /home/vscode/.m2 /home/vscode/.npm
sudo chown -R vscode:vscode backend/data backend/target frontend/node_modules /home/vscode/.m2 /home/vscode/.npm

# 预装前端依赖（对应 frontend/package-lock.json）
npm --prefix frontend ci

# 预拉 Maven 依赖，后面 IDE 里启动就不用再下载
mvn -B -f backend/pom.xml dependency:go-offline
