# 银龄复诊事项协同助手

面向银发群体的复诊协同 Agent 比赛 Demo。仓库包含可交互的手机端页面、Java 任务流、H2 模拟数据库、四类可执行模拟工具和团队协作文档。

> 所有医院、用户、号源、路线和通知均为模拟数据。本项目不提供疾病诊断或用药建议。

## 技术栈与运行方式

三位成员已经学过 Java，主后端采用 **Java 17 + Spring Boot 3.5.6**，大模型通过标准 HTTP API 接入，不需要 Python。前端采用 **React 19 + TypeScript**（基于 vite 的 vinext）。

本项目同时在 **Docker 容器**里运行与开发：

- **日常团队开发**：VS Code + Dev Containers 插件，把整个仓库放进一个容器，
  前端、后端、JDK、Node 都在容器里，保证三台电脑结果一致（推荐，见下文）。
- **一键部署 / 给评委演示**：根目录 `docker compose up` 同时起前端（vinext 生产服务器）与后端（Java）。
- 也保留本地 IDEA + npm 的方式，给不想用 Docker 的成员备用。

## 项目结构

```text
silver-followup-agent/
├─ frontend/        手机端界面；页面按业务拆分
├─ backend/         Java API；应用层、领域层、模拟工具分离
├─ docs/            需求、页面、Agent 流程、接口与协作规范
├─ .devcontainer/   VS Code 开发容器配置（团队开发入口）
├─ .env.example     密钥与部署配置示例，真实密钥不得上传
├─ compose.yml      docker compose：一键起 前端 + 后端
├─ Dockerfile       前后端多阶段构建镜像
└─ README.md
```

前端包含首页、复诊助手、复诊事项卡和适老设置。对话过程中按需展示计划卡、异常卡、明确确认卡、工具调用记录和最终事项卡。后端执行“理解需求 → 补问缺失信息 → 查询号源 → 日程检查 → 计划出行 → 用户确认 → 提交预约 → 创建提醒 → 通知家属”。

## 一、团队开发：VS Code 开发容器（推荐）

不用在自己电脑上装 JDK / Node，只要求装 Docker Desktop（Windows 开启 WSL2）。

1. 用 VS Code 打开仓库根目录，装 **Dev Containers** 扩展。
2. `Ctrl+Shift+P` → **Dev Containers: Reopen in Container**。首次自动构建容器（几分钟）。
3. 在容器内开 VS Code 集成终端：

```bash
# 终端 1：后端（端口 8080）
cd backend && mvn spring-boot:run

# 终端 2：前端（端口 3000）
cd frontend && npm run dev
```

浏览器打开 `http://localhost:3000`。健康检查 `http://localhost:8080/api/demo/health`；
H2 控制台 `http://localhost:8080/h2-console`（数据放具名卷，不写进仓库目录）。

详细说明（端口转发、远程调试 5005、真机联调、换机器）见 **[README.devcontainer.md](README.devcontainer.md)**。

## 二、一键部署 / 演示：docker compose

根目录已有 `Dockerfile`（后端 Java 多阶段构建、前端 vinext 生产服务器）与 `compose.yml`。前端基于 vinext，浏览器与后端直接通信，中间不经过额外反向代理。

```bash
# 首次：构建并后台启动 前端(http://localhost:3000) + 后端(http://localhost:8080)
docker compose up -d --build

# 查看状态/日志
docker compose ps
docker compose logs -f backend

# 关闭（保留 h2-data 卷，便于重启不重建镜像）
docker compose down

# 彻底重置（清空卷 + 重建）
docker compose down -v && docker compose up -d --build
```

- H2 运行时文件放在具名卷 `h2-data`，不写进仓库；每次启动按 `schema.sql` + `data.sql`
  重建演示数据（与本地运行行为一致）。重置演示数据用上面的 `down -v`。
- 后端健康检查通过后，前端容器才会就绪（`depends_on: condition`）。
- 需要 DeepSeek 时，把 `.env.example` 复制为 `.env` 并填入密钥（见“可选：启用 DeepSeek”）。
- 到另一台机器演示、改过 `.env` 里 `NEXT_PUBLIC_API_BASE_URL` 时，前端要**重新构建**：

```bash
docker compose build frontend && docker compose up -d frontend
```

## 三、本地（不装 Docker，备用）

此方式与原 README 一致：后端用 IDEA 打开根目录运行
`backend/src/main/java/com/team/silveragent/SilverAgentApplication.java`（JDK 17），
前端用 `npm.cmd ci && npm.cmd run dev`（Node ≥22.13）。H2 数据会落在 `backend/data/`（已加入 .gitignore）。

## 可选：启用 DeepSeek

不配置密钥时，项目自动使用本地规则，四类工具仍会真实执行。需要模型理解自由表达时：

- 开发容器 / compose：在根目录把 `.env.example` 复制为 `.env`，填好密钥，重启对应进程或 `docker compose up -d --build`。
- 本地 IDEA：在运行配置的环境变量中填写。

```text
AGENT_LLM_ENABLED=true
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-v4-flash
DEEPSEEK_API_KEY=只填本机的新密钥
```

不要修改 `.env.example` 填入密钥，也不要把密钥提交 GitHub。`.env` 已被 .gitignore 排除。

## 三个人怎样配合

- A：负责 `frontend/features` 页面和交互，只依赖约定好的 API JSON。
- B：负责 `backend/domain/tool` 及 `infrastructure/mock`，每个工具独立测试。
- C：负责 `backend/application` 的 Agent/工作流编排和确认门禁。
- 所有人改接口前先更新 `docs/05-api-contracts.md`，再改代码。
- 每个功能使用独立分支，完成后发 Pull Request；不要三个人同时直接改 `main`。

开始协作前先读 `docs/00-reading-order.md` 和 `docs/07-collaboration-and-git.md`。每次完成或调整功能，都更新 `docs/records/PROGRESS.md`；重要取舍记入 `DECISIONS.md`；踩坑记入 `PITFALLS.md`；接口变化记入 `INTERFACE_CHANGES.md`。

## Agent 的控制边界

页面不直接调用大模型，大模型也不能直接提交预约。`agent/DeepSeekFactExtractor` 只提取医院、科室和日期；`application/FollowupAgentService` 决定任务状态和工具调用。预约、取消、创建提醒和通知家属都经过明确确认。

下一步按 `docs/08-beginner-implementation-guide.md` 打通一条完整竖向链路，再扩展语音、图片材料检查等锦上添花功能。
