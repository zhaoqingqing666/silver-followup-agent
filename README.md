# 银龄复诊事项协同助手

面向银发群体的复诊协同 Agent 比赛 Demo。仓库包含可交互的手机端页面、Java 任务流、H2 模拟数据库、四类可执行模拟工具和团队协作文档。

> 所有医院、用户、号源、路线和通知均为模拟数据。本项目不提供疾病诊断或用药建议。

需求基线见 [Requirement.md](Requirement.md)。当前设计、源码与需求差距、待实现方案和验收优先级见 [Design.md](Design.md)。已有主流程不代表全部要求已验收，尤其需补齐紧急暂停、确认版本绑定和部分失败恢复。

## 技术栈与运行方式

三位成员已经学过 Java，主后端采用 **Java 17 + Spring Boot 3.5.6**，大模型通过标准 HTTP API 接入，不需要 Python。前端采用 **React 19 + TypeScript**（基于 vite 的 vinext）。

本项目同时在 **Docker 容器**里运行与开发：

- **日常团队开发**：VS Code + Dev Containers 插件，把整个仓库放进一个容器，
  前端、后端、JDK、Node 都在容器里，保证三台电脑结果一致（推荐，见下文）。
- **一键部署 / 给评委演示**：根目录 `docker compose up` 同时起前端（vinext 生产服务器）与后端（Java）。
- 项目依赖安装、构建和测试均在开发容器内执行，不使用宿主机工具链。

## 项目结构

```text
silver-followup-agent/
├─ frontend/        手机端界面；页面按业务拆分
├─ backend/         Java API；应用层、领域层、模拟工具分离
├─ docs/            需求、页面、Agent 流程、接口与协作规范
├─ .devcontainer/   VS Code 开发容器配置（含个人模型配置模板）
├─ .env.example     docker compose 部署配置示例
├─ compose.yml      docker compose：一键起 前端 + 后端
├─ Dockerfile       前后端多阶段构建镜像
└─ README.md
```

前端包含首页、复诊助手、复诊事项卡、出行与院内指引和适老设置。助手默认允许自然交流；用户明确表达预约复诊目标后才创建可暂停、可恢复的后台办理任务。对话过程中按需展示任务状态、计划卡、异常卡、明确确认卡和最终事项卡。后端执行“理解需求 → 补问缺失信息 → 查询号源 → 日程检查 → 计划出行 → 用户确认 → 提交预约 → 创建提醒 → 通知家属”。

## 一、团队开发：VS Code 开发容器（推荐）

不用在自己电脑上装 JDK / Node，只要求装 Docker Desktop（Windows 开启 WSL2）。

1. 用 VS Code 打开仓库根目录，装 **Dev Containers** 扩展。
2. `Ctrl+Shift+P` → **Dev Containers: Reopen in Container**。首次自动构建容器（几分钟）。
3. 选择 **Terminal → Run Task**，分别运行“Dev Container: 后端服务”和“Dev Container: 前端服务”。后端任务会自动读取个人的 `.devcontainer/.env`（没有时使用规则/模板回退模式）。

也可以在容器内开 VS Code 集成终端手动启动：

```bash
# 终端 1：后端（端口 8080）
cd backend
set -a
if [ -f ../.devcontainer/.env ]; then source ../.devcontainer/.env; fi
set +a
mvn spring-boot:run

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

- H2 运行时文件放在具名卷 `h2-data`，不写进仓库；启动时按 `schema.sql` + `data.sql`
  补齐表结构和模拟数据，保留已有预约、会话及号源占用。上面的 `down -v` 会删除卷中全部演示记录，仅在确需重置时使用。
- 后端健康检查通过后，前端容器才会就绪（`depends_on: condition`）。
- 需要启用大模型时，把 `.env.example` 复制为 `.env` 并填写兼容服务配置（见“可选：启用大模型”）。
- 到另一台机器演示、改过 `.env` 里 `NEXT_PUBLIC_API_BASE_URL` 时，前端要**重新构建**：

```bash
docker compose build frontend && docker compose up -d frontend
```

## 三、容器内验证

在 VS Code 的容器窗口选择 **Terminal → Run Task → Dev Container: 验证项目**，执行后端测试、前端类型检查和生产构建。首次打开或更新开发容器配置时，选择 **Dev Containers: Rebuild and Reopen in Container**。

## 可选：启用可替换大模型

不配置模型时，项目自动使用本地规则和回答模板，四类工具仍会真实执行。启用后，`ConversationPlanner` 可以提出普通回答、自然补问、只读工具调用或工作流动作；`AgentRuntime` 与 Java 权限策略审核后才进入真实工具和业务状态机，最终回答仍受权威工具结果约束。

- VS Code 开发容器：把 `.devcontainer/.env.example` 复制为 `.devcontainer/.env`，填写个人配置后，停止并重新运行“Dev Container: 后端服务”。不需要重建容器。
- docker compose 部署：把根目录 `.env.example` 复制为 `.env`，填写部署配置后重新创建后端容器。
- 本地 IDEA：在运行配置的环境变量中填写。

```text
AGENT_MODEL_ENABLED=true
AGENT_MODEL_PROVIDER=openai-compatible
AGENT_MODEL_BASE_URL=兼容服务地址
AGENT_MODEL_NAME=模型名称
AGENT_MODEL_API_KEY=只填本机的密钥
AGENT_MODEL_CONNECT_TIMEOUT_MS=2500
AGENT_MODEL_READ_TIMEOUT_MS=12000
AGENT_MODEL_HISTORY_LIMIT=16
```

不要直接修改两个 `.env.example` 模板，也不要把密钥提交 GitHub。根目录 `.env` 和 `.devcontainer/.env` 都已被 `.gitignore` 排除。开发容器配置属于每位成员本机，不会覆盖队友的模型地址或密钥。

地图页面无需 Key 也能显示数据库中的比赛模拟路线。若要显示高德底图，可在对应 `.env` 中配置 `NEXT_PUBLIC_AMAP_JS_KEY` 与 `NEXT_PUBLIC_AMAP_SECURITY_CODE`；它们仅用于浏览器 JS API。后端 Web 服务密钥不得使用 `NEXT_PUBLIC_` 前缀。

启动后访问 `http://localhost:8080/api/agent/model-status` 检查当前模式；模型主导工具架构会显示 `architecture=MODEL_ORCHESTRATED_TOOL_AGENT`、`promptMode=SINGLE_MAIN_AGENT_PROMPT` 和规划模式。该接口不返回密钥。

## 三个人怎样配合

- A：负责 `frontend/features` 页面和交互，只依赖约定好的 API JSON。
- B：负责 `backend/domain/tool` 及 `infrastructure/mock`，每个工具独立测试。
- C：负责 `backend/application` 的 Agent/工作流编排和确认门禁。
- 所有人改接口前先更新 `docs/05-api-contracts.md`，再改代码。
- 每个功能使用独立分支，完成后发 Pull Request；不要三个人同时直接改 `main`。

开始协作前先读 `docs/00-reading-order.md` 和 `docs/07-collaboration-and-git.md`。每次完成或调整功能，都更新 `docs/records/PROGRESS.md`；重要取舍记入 `DECISIONS.md`；踩坑记入 `PITFALLS.md`；接口变化记入 `INTERFACE_CHANGES.md`。

## Agent 的控制边界

页面不直接调用大模型，大模型也不能直接提交预约。`LlmConversationPlanner` 可提出回答、一个或多个只读工具及工作流动作，`AgentRuntime`、`ToolRegistry`、`ToolPolicy` 和 `ActionValidator` 负责白名单、权限和回复边界；办事流程来自 `careGuide.search`，材料、路线、楼层和诊室来自数据库工具。普通聊天不再重复调用回答模型；预约、取消、创建提醒和通知家属仍全部经过 Java 明确确认。

下一步按 `docs/08-beginner-implementation-guide.md` 打通一条完整竖向链路，再扩展语音、图片材料检查等锦上添花功能。
