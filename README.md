# 银龄复诊事项协同助手

面向银发群体的复诊协同 Agent 比赛 Demo。仓库包含可交互的手机端页面、Java 任务流、H2 模拟数据库、四类可执行模拟工具和团队协作文档。

> 所有医院、用户、号源、路线和通知均为模拟数据。本项目不提供疾病诊断或用药建议。

## 为什么选 Java，而不是 Python

三位成员已经学过 Java，因此主后端采用 **Java 17 + Spring Boot 3.5.6**。大模型通过标准 HTTP API 接入，不需要 Python。前端采用 **React 19 + TypeScript**；IDEA 和 VS Code 都能打开整个仓库。

## 项目结构

```text
silver-followup-agent/
├─ frontend/        手机端界面；页面按业务拆分
├─ backend/         Java API；应用层、领域层、模拟工具分离
├─ docs/            需求、页面、Agent 流程、接口与协作规范
├─ .env.example     密钥示例，真实密钥不得上传
└─ README.md
```

前端包含首页、复诊助手、复诊事项卡和适老设置。对话过程中按需展示计划卡、异常卡、明确确认卡、工具调用记录和最终事项卡。后端执行“理解需求 → 补问缺失信息 → 查询号源 → 日程检查 → 计划出行 → 用户确认 → 提交预约 → 创建提醒 → 通知家属”。

## 第一次运行

### 1. 先启动后端（推荐 IDEA）

安装 JDK 17。在 IDEA 中打开根目录，等待 Maven 读取 `backend/pom.xml`，运行：

```text
backend/src/main/java/com/team/silveragent/SilverAgentApplication.java
```

看到 `Started SilverAgentApplication` 后，访问 `http://localhost:8080/api/demo/health`。H2 数据保存在 `backend/data/`，数据库控制台为 `http://localhost:8080/h2-console`。

### 2. 再启动前端（VS Code 或 IDEA 终端）

需要 Node.js 22.13 或更高版本。

```bash
cd frontend
npm.cmd ci
npm.cmd run dev
```

浏览器打开 `http://localhost:3000`。构建检查使用 `npm run build`。

### 3. 可选：启用 DeepSeek

不配置密钥时，项目自动使用本地规则，四类工具仍会真实执行。需要模型理解自由表达时，在 IDEA 后端运行配置的环境变量中填写：

```text
AGENT_LLM_ENABLED=true
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-v4-flash
DEEPSEEK_API_KEY=只填本机的新密钥
```

不要修改 `.env.example` 填入密钥，也不要把密钥提交 GitHub。

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
