# 接口变更记录

任何前后端共享字段、接口路径、枚举或日期格式变化都记录在这里。

## API-CHANGE-001 建立对话式智能体接口

- 日期：2026-09-03
- 提出人：Codex
- 影响接口：`/api/agent/**`
- 新结构：创建会话、发送消息、明确确认、模型状态四组接口。
- 变更原因：页面必须根据结构化卡片和任务状态渲染，不能播放预写对话。
- 是否破坏兼容：是，替换最初的计划接口骨架。
- 前端负责人：待认领。
- 后端负责人：待认领。
- 文档是否已更新：是。
- 完成提交：待首次提交。

## 变更模板

### API-CHANGE-XXX 标题

- 日期：
- 提出人：
- 影响接口：
- 旧结构：
- 新结构：
- 变更原因：
- 是否破坏兼容：是 / 否。
- 前端负责人：
- 后端负责人：
- 文档是否已更新：
- 完成提交：

## 兼容性原则

- 增加可选字段：通常兼容。
- 删除字段：破坏兼容。
- 字段改名：破坏兼容。
- 字符串改为对象或数组：破坏兼容。
- 枚举新增值：前端必须提供未知值兜底。
- 日期格式改变：破坏兼容。

## 2026-09-03 Agent上下文与真实事项重构

- 新增 POST /api/agent/actions。
- quickReplies 改为 label、action、value 对象数组。
- 新增 GET /api/users/{userId}/appointments。
- POST /api/agent/messages 明确为自由语言入口。

## 2026-09-03

- 新增 GET /api/agent/conversations/{conversationId}：恢复消息、流程阶段及最后响应卡片。
- GET /api/users/{userId}/appointments 改为返回全部个人预约记录，并增加 status、createdAt。
- 新增结构化动作 SET_PERIOD、SHOW_PERIOD_SLOTS。
