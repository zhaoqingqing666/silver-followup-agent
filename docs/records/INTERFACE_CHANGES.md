# 接口变更记录

任何前后端共享字段、接口路径、枚举或日期格式变化都记录在这里。

## 2026-09-07 用户资料返回家属联系人

- `GET /api/users/{userId}` 返回体新增可选字段 `contacts`（数组，含 id/name/relationship/maskedPhone）。
- 兼容性：追加可选字段，不破坏旧客户端；未配置联系人的用户返回空数组。
- 后端只返回脱敏电话，不含明文；`maskedPhone` 统一为 `138****1234` 样式。
- 前端 `UserProfile` 类型增加 `contacts?: FamilyContact[]`；设置页改从此接口读取用户与家属信息。

## 2026-09-07 确认凭据与失败恢复

- `/api/agent/confirmations` 必须传 `confirmationId`，来自当前确认卡；返回修改也需要凭据。属于破坏兼容变更，前后端须同步更新。
- 新增 `EMERGENCY_PAUSED`、`PARTIAL`、`TOOL_ERROR` 阶段，以及联系人选择、偏好修改、已预约修改和补办操作。
- `plan.taskStatuses` 与七项任务对应；`PARTIAL` 的结果卡仅表示预约已保留，并非全部成功。
- 对话卡片日期现在带年份。数据库预约接口日期仍使用 ISO 格式。
- 容器内联调和构建待完成。

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

## 2026-09-06 动态目录与用户资料

- POST /api/agent/conversations 新增可选查询参数 userId。
- 新增 GET /api/users/{userId}，返回 UserProfile。
- GET /api/users/{userId}/appointments 新增 requiredMaterials 字段。
- SET_HOSPITAL 和 SET_DEPARTMENT 的 value 改为数据库 ID。
- 兼容性：前端和后端已同步修改；旧页面若仍发送名称，后端医院/科室解析仍提供兼容。

## 2026-09-06 医院资料查询与按钮展示标签

- POST /api/agent/actions 增加可选字段 label，用于保存用户看得懂的按钮文字。
- 新请求示例：{"conversationId":"会话ID","action":"SET_HOSPITAL","value":"h001","label":"市第一医院"}。
- value 仍作为稳定数据库 ID，label 只负责展示；未提供 label 的旧请求仍可使用。
- 自由语言意图新增 QUERY_HOSPITALS、QUERY_HOSPITAL_INFO、QUERY_DEPARTMENTS、REQUEST_RECOMMENDATION。

## 2026-09-06 材料状态与语音偏好

- 新增 GET /api/users/{userId}/appointments/{appointmentId}/materials。
- 新增 PATCH /api/users/{userId}/appointments/{appointmentId}/materials/{materialId}。
- 材料状态枚举：NOT_PREPARED、PREPARED、PHOTO_CONFIRMED。
- 新增 GET、PUT /api/users/{userId}/preferences，保存自动播报、语速和音量。
- 兼容性：仅新增接口；原预约列表和智能体接口未删除字段。

## 2026-09-06 可预约号源查询

- 自由语言意图新增 QUERY_AVAILABLE_SLOTS。
- 结构化动作新增 SHOW_AVAILABLE_DATES，无 value。
- 预约工具新增 queryUpcomingSlots(conversationId, hospitalId, department, from, to)。
- 查询范围为当天至一个月后；已过去的当天时段不会返回。
- 兼容性：仅新增意图、动作和内部工具方法，原接口字段不变。

## 2026-09-06 中控与我的预约支线

- 自由语言意图新增 QUERY_APPOINTMENTS、RESTART_TASK、RESUME_TASK、CHANGE_DEPARTMENT、CHANGE_TIME、CONFIRM_ACTION、DENY_ACTION。
- POST /api/agent/actions 新增 QUERY_APPOINTMENTS、SELECT_APPOINTMENT_TO_CANCEL、RESUME_INTERRUPTED、CHANGE_DEPARTMENT、CHANGE_TIME。
- 新增后端内部工具 appointment.queryMine，可按 userId、date、hospital、department 查询已确认预约。
- AgentTurnResponse 未新增或删除字段；查询到单条预约时复用 result 卡，多条时返回摘要和预约ID绑定的快捷操作。
- 兼容性：仅增加动作与意图，原前端字段结构不变。
