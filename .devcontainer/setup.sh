#!/usr/bin/env bash
set -euo pipefail

cd /workspace
sudo mkdir -p backend/data backend/target frontend/node_modules /home/vscode/.m2 /home/vscode/.npm
sudo chown -R vscode:vscode backend/data backend/target frontend/node_modules /home/vscode/.m2 /home/vscode/.npm
npm --prefix frontend ci
mvn -B -f backend/pom.xml dependency:go-offline
