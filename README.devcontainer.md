# 用 VS Code 开发容器（Docker）开发

团队统一用 **Docker 容器**做开发环境，前端、后端、JDK、Node 全在容器里，
不依赖成员宿主机装了什么东西，保证三台电脑结果一致。

构建、安装依赖和测试均在 VS Code Dev Container 的终端内执行，不在 Windows 宿主机执行 Maven/npm。工作目录固定为 `/workspace`；`frontend/node_modules` 和 `backend/target` 使用容器卷，避免混入宿主机平台文件。更新 `.devcontainer` 配置后需在 VS Code 执行 **Dev Containers: Rebuild Container**。

容器内验证：`mvn -f backend/pom.xml test -Dagent.model.enabled=false`，然后在 `frontend` 目录执行 `npx tsc --noEmit` 和 `npm run build`。

也可选择 **Terminal → Run Task**，运行“Dev Container: 后端服务”“Dev Container: 前端服务”或“Dev Container: 验证项目”。这些任务仅用于容器内终端。“Dev Container: 后端服务”会在每次启动时自动读取本机私有的 `.devcontainer/.env`；没有该文件时按默认的规则/模板回退模式启动。

> 需要本机已装 Docker Desktop（Windows）并开启 WSL2，VS Code 装
> “Dev Containers”扩展（`ms-vscode-remote.remote-containers`）。

## 一、第一次打开

1. 用 VS Code 打开仓库根目录 `silver-followup-agent`。
2. `Ctrl+Shift+P` → 输入并选择：**Dev Containers: Reopen in Container**。
3. 第一次会拉取基础镜像并构建（几分钟），之后秒开。
4. 构建完成后，VS Code 会在容器内自动执行 `npm ci` 与 Maven 依赖预拉取。

容器配置在 `.devcontainer/`：`devcontainer.json` 声明端口与 VS Code 插件，
`Dockerfile` 安装 JDK 17、Maven 与 Node 22；`setup.sh` 自动安装锁定的前端依赖并缓存 Maven 依赖。

## 二、日常启动

推荐通过 **Terminal → Run Task** 启动：

- `Dev Container: 后端服务`：自动加载 `.devcontainer/.env` 后启动 Spring Boot。
- `Dev Container: 前端服务`：启动 vite 开发服务器。

也可以在容器内开 VS Code 集成终端手动启动（三个终端各跑一个）：

```bash
# 终端 1：后端（Spring Boot，端口 8080）
cd backend
set -a
if [ -f ../.devcontainer/.env ]; then source ../.devcontainer/.env; fi
set +a
mvn spring-boot:run

# 终端 2：前端（vite 开发服务器，端口 3000）
cd frontend
npm run dev

# 终端 3（可选）：需要热重载 + 远程调试时，用下面参数重启后端
cd backend
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
```

浏览器访问：

| 地址 | 用途 |
|---|---|
| http://localhost:3000 | 手机端页面 |
| http://localhost:8080/api/demo/health | 后端健康检查 |
| http://localhost:8080/h2-console | H2 数据库控制台 |

`vite` 的 `dev` 配置允许在容器内监听，改前端代码自动热更新；
后端 `mvn spring-boot:run` 默认不自动重启，改完 Java 代码后
`Ctrl+C` 再运行一次即可（本仓库未启用 devtools，保持一致、行为可预期）。

## 三、开发容器启用大模型（可选）

在 Windows 宿主机的仓库目录中复制配置模板：

```powershell
Copy-Item .devcontainer/.env.example .devcontainer/.env
```

然后只在 `.devcontainer/.env` 中填写自己的模型地址、模型名和密钥，并把 `AGENT_MODEL_ENABLED` 改为 `true`。该文件已被 `.gitignore` 排除，不会随 Git 分享给队友；每位成员可以保留自己的模型服务和密钥。

默认模型连接超时为 2500ms、单次响应超时为 12000ms，可通过 `AGENT_MODEL_CONNECT_TIMEOUT_MS` 和 `AGENT_MODEL_READ_TIMEOUT_MS` 调整。`AGENT_MODEL_HISTORY_LIMIT` 默认是 16 条消息（约 8 轮），允许在 4 到 40 之间调整；不建议直接发送全部历史。超时只会让本轮回退规则或权威模板，不会绕过确认门禁。

配置保存后，停止旧的后端进程，再次运行 **Terminal → Run Task → Dev Container: 后端服务**。只修改这个文件不需要重建开发容器，后端任务每次启动都会重新读取它。可访问 `http://localhost:8080/api/agent/model-status` 检查：成功启用时不应再显示 `RULE_FALLBACK` / `TEMPLATE_FALLBACK`，接口也不会返回密钥。

注意：根目录 `.env` 只供 `docker compose` 部署读取；`.devcontainer/.env` 只供 VS Code 的后端启动任务读取，两者用途不同。

## 四、可选显示高德底图

地图详情页默认显示 H2 中的比赛模拟路线，不需要联网或地图密钥。需要叠加高德底图时，在 `.devcontainer/.env` 中填写：

```ini
NEXT_PUBLIC_AMAP_JS_KEY=你的Web端JS API Key
NEXT_PUBLIC_AMAP_SECURITY_CODE=你的安全密钥
```

保存后停止并重新运行 **Dev Container: 前端服务**。前端启动任务会读取这两个变量。浏览器变量不可用于保存高德 Web 服务端 Key；需要服务端在线算路时应另加后端私有变量和适配器。

## 五、VS Code 直接点按钮（可选）

安装容器内的扩展后：

- 左侧 **Spring Boot Dashboard** 面板 → 选中 `silver-agent-api` → ▶ 运行（Debug 就是带断点的调试）。
- 前端直接 `npm run dev` 即可，无需额外配置。

## 五、数据与调试端口

- H2 数据保存在具名卷 `silver-backend-data`，不会写进仓库，删除容器后仍在；
  重置演示数据：清空卷再重启（`docker volume rm silver-backend-data`）。
- 5005 是后端远程调试端口（JDWP），在 VS Code 里加一个 Java 调试配置即可断点调试，
  与 IDEA 的体验一致。

## 六、跟宿主机 / 局域网真机联调

页面要调用后端时，前后端都在本容器内开发，直接 `http://localhost:8080` 即可。
需要把页面给手机真机/其他人演示时，把后端与前端临时都放到宿主映射端口：
在浏览器地址里把 `localhost` 换成电脑局域网 IP 即可（端口仍是 3000 / 8080）。

## 七、换机器、换成员

- 提交 `package-lock.json` 与 `pom.xml`，其他人“在容器中重新打开”后依赖自动一致。
- 容器配置改动要进仓库：在 VS Code 执行 **Dev Containers: Rebuild Container**；这里的开发容器不由根目录的部署 compose 管理。
- 具体启动步骤、跨域、密钥配置与 IDEA 方式对比，见根目录 `README.md`。

## 常见问题

- **端口被占**：关掉宿主机上占 8080/3000 的进程，或在 `devcontainer.json` 里改端口映射。
- **改 pom/package.json 后不生效**：重启对应进程；改了 Dockerfile/依赖版本才需要重建容器。
- **填写 `.devcontainer/.env` 后仍是规则模式**：确认是通过“Dev Container: 后端服务”任务启动，并先停止旧后端。已运行的 Java 进程不会自动重新读取环境变量。
- **停止后端后前端是否会关闭**：不会。开发容器里的前端和后端是两个独立任务，需要分别启动和停止。
- **Windows 防火墙弹窗**：首次转发端口放行即可。
