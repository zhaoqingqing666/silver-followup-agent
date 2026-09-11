# 用 Docker / Dev Container 打开本仓库（隔离环境，不污染你的电脑）

本仓库 = **后端 Spring Boot（Java 17）** + **前端 vinext/React**。Java、Node、H2、Maven 全部在容器里跑，**你的电脑不会被装任何依赖**——这正是老师说的「隔离、不污染环境」的用意。

两种用法任选其一：

| 用法 | 适合 | 命令 / 操作 |
|---|---|---|
| **A. VS Code「在容器中重新打开」** | 团队日常**开发**（推荐，见下） | 安装 Dev Containers 扩展 → `Ctrl+Shift+P` → `Dev Containers: Reopen in Container` |
| **B. `docker compose up`** | **演示 / 交付**成品 | `docker compose up --build`，然后浏览器开 `http://localhost:3000` |

需要先装好：**Docker Desktop（开 WSL2 后端）**；用法 A 还需 **VS Code + Dev Containers 扩展**。

> 模型接口默认**关闭**（`AGENT_LLM_ENABLED=false`）。纯本地规则能跑通全部功能；要开千问对话，见文末「接阿里云百炼 DashScope」。

---

## 用法 A：VS Code 开发容器（日常开发推荐）

1. 用 VS Code 打开**仓库根目录**（含 `.devcontainer/`、`backend/`、`frontend/` 的那一层）。
2. 安装微软官方扩展 **Dev Containers**（`ms-vscode-remote.remote-containers`）。
3. `Ctrl+Shift+P` → 输入并选择 **`Dev Containers: Reopen in Container`**。
4. 首次会自动构建镜像（JDK 17 + Node 22 + Maven），并跑 `setup.sh` 装好前后端依赖；**耗时几分钟，只此一次**。

进去之后左下角显示 `>< 开发容器: silver-followup-dev`。装好的东西都在**具名卷**里：
H2 数据库 `silver-backend-data`、前端依赖 `silver-frontend-dependencies`、后端构建 `silver-backend-target`、Maven 缓存 `silver-maven-cache`、npm 缓存 `silver-npm-cache`——**不会写进仓库、不会污染宿主机**。

### 在容器里开三个终端跑

**终端 1 — 后端（8080）：**
```bash
cd backend && mvn spring-boot:run
```
看到 `Started ...` 即成功，健康检查：<http://localhost:8080/api/demo/health>（返回 `{"status":"ok"}`）。

**终端 2 — 前端（3000）：**
```bash
cd frontend && npm run dev
```
浏览器开 <http://localhost:3000>。

**终端 3（可选）— H2 控制台 / 调试：**
- H2 控制台：浏览器开 <http://localhost:8080/h2-console>，JDBC URL 填 `jdbc:h2:file:/workspace/backend/data/silver-agent`（用户名 `sa`，密码留空）。页面与数据库在同一个容器里，登录不会跨域。
- 后端远程调试（5005 已预留）：IDE 里配 Remote JVM Debug 连 `localhost:5005` 即可，无需改代码。

### 验证是否成功
在容器里执行 `docker ps`，能看到 `silver-followup-dev`；三个端口 3000/8080/5005 已自动转发。

### 重置数据（可选）
数据都存 H2 文件卷里，想从零开始：
```bash
docker volume rm silver-backend-data
```
（先 `docker compose down` 或 VS Code 里 `Dev Containers: Rebuild Container`，让容器停掉再删。）

---

## 用法 B：一键容器演示 / 交付（`docker compose up`）

在**装了 Docker 的机器**上，仓库根目录执行：

```bash
# 生产构建：后端打 jar、前端 npm ci + build 后由 vinext 生产服务器提供
docker compose up --build
```

启动顺序已编排好：**先起后端并等健康检查通过（GET /api/demo/health 返回 200），再起前端**。全部就绪后：

- 前端：<http://localhost:3000>
- 后端健康：<http://localhost:8080/api/demo/health>
- H2 控制台：<http://localhost:8080/h2-console>，JDBC URL 填 `jdbc:h2:file:/app/data/silver-agent`

停止：`Ctrl+C`；彻底移除容器（保留数据卷）：`docker compose down`；连数据卷一起删：`docker compose down -v`。

> `docker compose up` 生成的 `silver-api:dev` / `silver-web:dev` 是**演示镜像**（生产模式、代码已构建进镜像）。想边改边看、断点调试，用用法 A 的开发容器。

---

## 配置文件速查

| 文件 | 作用 |
|---|---|
| `.devcontainer/devcontainer.json` | 开发容器定义：卷、端口转发、容器环境变量、启动后自动跑 `setup.sh` |
| `.devcontainer/Dockerfile` | 开发镜像：Ubuntu 22.04 + JDK 17 + Maven + Node 22（含阿里云镜像加速） |
| `.devcontainer/setup.sh` | 容器首次启动：装前端依赖 + 预拉 Maven 依赖 + 修数据卷权限 |
| `Dockerfile` | 演示/交付镜像：多阶段（`silver-api` 后端 jar、`silver-web` 前端生产服务） |
| `compose.yml` | 一键编排：后端+前端+服务健康检查+H2 数据卷，环境变量默认值全在此 |
| `.dockerignore` | 构建上下文排除：node_modules/target/数据/密钥一律不进镜像 |

环境变量都带默认值（见 `compose.yml`），不设也能跑纯本地规则模式；写进容器的是**运行时**变量，改 `compose.yml`/`.env` 后 `docker compose up` 会重新拉起。

---

## 接阿里云百炼 DashScope（可选：开模型对话 / 语音）

本仓库模型走 **DashScope（千问 Qwen）**，一把 key 覆盖 Agent 对话 + ASR + TTS + VL。纯本地规则模式**不需要 key**；要开 AI 能力，在宿主仓库根目录建 `.env`：

```bash
AGENT_LLM_ENABLED=true

# 阿里云百炼 API Key（https://bailian.console.aliyun.com/）
DASHSCOPE_API_KEY=sk-你的key

# 以下都可省略，走默认值：
# DASHSCOPE_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
# DASHSCOPE_MODEL=qwen3.6-flash
# DASHSCOPE_ASR_MODEL=qwen3-asr-flash
# DASHSCOPE_TTS_MODEL=qwen3-tts-flash
# DASHSCOPE_TTS_VOICE=Cherry
# DASHSCOPE_TTS_SPEED=1.0
# DASHSCOPE_VL_MODEL=qwen3-vl-plus
```

`docker compose` 自动读取根目录 `.env` 注入容器（`compose.yml` 里 `${DASHSCOPE_API_KEY:-}` 等占位）。**`.dockerignore` 已排除 `.env`，key 不会被打进交付镜像**。

开发容器里同理：在容器终端 `export DASHSCOPE_API_KEY=sk-xxx AGENT_LLM_ENABLED=true` 后重启后端即可（或用根目录 `.env` + 重建容器）。`DASHSCOPE_API_KEY` 只在后端容器环境里存在，不入代码。

---

## 常见问题

- **首次构建很慢 / 网络超时**：镜像已配置阿里云 Ubuntu 源与下载重试；Maven/npm 依赖只在首启拉一次。
- **3000 被占**：改宿主映射 `FRONTEND_PORT=3001 docker compose up`（仅用法 B）；用法 A 直接在容器里换端口跑。
- **想彻底重来**：`docker compose down -v && docker volume rm silver-backend-data silver-frontend-dependencies silver-backend-target silver-maven-cache silver-npm-cache`（删具名卷，用法 A/B 各自的前缀卷一并清掉后 Rebuild Container）。
- **没装 Docker**：无法用容器。退路是照 README.md 在本机装 Java 17 + Node 22 直接跑（会改到你本机环境）。
