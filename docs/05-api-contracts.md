# 前后端 API 契约

服务地址：http://localhost:8080

## 创建会话

POST /api/agent/conversations?userId=user-001，无请求体。

userId 可省略；省略时读取 DEMO_USER_ID，默认 user-001。

新会话的 `task.status` 为 `NONE`，助手不立即创建预约草稿。用户点击“开始复诊办理”或明确表达预约目标后，状态变为 `ACTIVE`。普通交流可使任务变为 `PAUSED`，但 `currentStage` 和已收集字段保留。

## 自由语言消息

POST /api/agent/messages
请求：{"conversationId":"会话ID","message":"9月20日不行了，换到21日"}
自由文字和语音转文字使用此接口。启用模型时，后端规划器提出直接回答、补问、只读工具或工作流动作；Java 运行时完成工具白名单、参数、状态和副作用审核。只读工具校验后执行，写动作只进入原确认流程；回答节点根据真实结果生成回复。未启用或调用失败时自动使用规则规划器和模板。

## 明确按钮操作

POST /api/agent/actions
请求：{"conversationId":"会话ID","action":"SELECT_SLOT","value":"slot-0920-1500","label":"9月20日 15:00"}
按钮使用此接口，不调用模型理解节点；完成 Java 状态处理或工具执行后，可以调用模型回答节点生成用户可读回复。
label 是可选的用户可读文字；value 可以继续使用 hospitalId、departmentId 或 slotId。

## 确认关键操作

POST /api/agent/confirmations
请求：{"conversationId":"会话ID","approved":true,"confirmationId":"当前确认卡返回的凭据"}
只有确认接口可以触发提交或取消预约、创建提醒和通知家属。

`confirmation.confirmationId` 是当前计划的随机确认凭据，修改后失效，确认执行前即消费；`approved=false` 也必须携带凭据。未另设数字 `planVersion`。旧客户端缺少凭据将被拒绝，前后端必须一同更新。通知工具日志名为 `family.notify`。

新增阶段：`EMERGENCY_PAUSED`、`PARTIAL`、`TOOL_ERROR`。新增操作：`SET_CONTACT`（当前用户联系人 ID）、`EDIT_PREFERENCES`、`EDIT_BOOKING`、`RETRY_EXECUTION`。`plan.taskStatuses` 与 `plan.tasks` 按下标对应。`PARTIAL` 也可能返回事项卡，前端不得一律标为全部完成。

支持性对话新增操作 `RETURN_TO_FLOW`，只返回保留的业务节点，不执行预约、提醒或通知。`CANCELLED` 只终止当前办理，仍允许支持性交流、普通聊天、查询事项和新建办理。

`AgentTurnResponse` 新增 `task`：

```json
{
  "active": true,
  "status": "ACTIVE",
  "currentStage": "ASK_DEPARTMENT",
  "summary": "市第一医院 · 待选择科室",
  "missingField": "科室"
}
```

`status` 可取 `NONE`、`ACTIVE`、`PAUSED`、`AWAITING_CONFIRMATION`、`COMPLETED`、`CANCELLED`。前端用它展示持续任务卡，不能根据聊天文字猜测任务是否存在。

## 模型状态

GET /api/agent/model-status

返回 `understandingMode`、`planningMode`、`architecture`、`promptMode`、`answerMode`、`provider`、`model` 和 `secretStored`。模型启用时 `architecture=MODEL_ORCHESTRATED_TOOL_AGENT`、`promptMode=SINGLE_MAIN_AGENT_PROMPT`；模型不可用时 `architecture=RULE_WORKFLOW_FALLBACK`。`understandingMode` 暂作为 `planningMode` 的兼容别名。接口不返回密钥。模型配置使用通用的 `AGENT_MODEL_*` 环境变量，不绑定具体厂商。

## 查询用户资料

GET /api/users/user-001

返回姓名、模拟住址、偏好交通方式和展示用家属联系人（`contacts`，电话为脱敏掩码）。首页与设置页均从此接口读取，不得在页面写死用户姓名或家属信息。

```json
{
  "id": "user-001",
  "name": "王阿姨",
  "homeAddress": "幸福小区（模拟）",
  "preferredTransport": "家属开车",
  "contacts": [{ "id": "family-001", "name": "小丽", "relationship": "女儿", "maskedPhone": "138****1234" }]
}
```

`contacts` 为 2026-09-07 新增的可选字段；后端只返回脱敏电话，不含明文。Agent 流程中家属候选人仍走内部联系人查询，与此接口无耦合。

## 查询真实事项

GET /api/users/user-001/appointments
未预约返回空数组，确认预约后返回数据库记录。

## 预约材料准备状态（2026-09-06）

- GET /api/users/{userId}/appointments/{appointmentId}/materials：读取该预约每项材料及准备状态。
- PATCH /api/users/{userId}/appointments/{appointmentId}/materials/{materialId}：更新单项材料。
- 请求示例：{"status":"PREPARED","confirmSource":"USER","photoUrl":null}。
- status 可取 NOT_PREPARED、PREPARED、PHOTO_CONFIRMED；PHOTO_CONFIRMED 为后续拍照检查预留。
- 事项页和助手完成卡片必须共用这些接口，不再把勾选状态仅保存在前端。

## 出行地图与院内指引（2026-09-09）

- `GET /api/users/{userId}/appointments/{appointmentId}/travel-guide`：返回预约时间、院外路线、坐标折线、建议出发时间、楼栋、入口、楼层、诊室、报到点、无障碍路线和咨询位置。
- 内部只读工具 `travel.routePlan` 与 `hospital.locationGuide` 的参数和结果写入 `tool_call_logs`。
- `route.source=SIMULATED` 时前端必须明确显示比赛模拟数据；不能伪装成实时路况。
- 号源通过 `appointment_slots.clinic_location_id` 关联诊室，避免把整个科室永久写死在同一个房间。
- 新快捷动作 `OPEN_TRAVEL` 的 `value` 为 appointmentId；它只用于前端打开地图详情，不调用后端写操作。

## 用户语音偏好（2026-09-06）

- GET /api/users/{userId}/preferences：读取自动播报、语速和音量。
- PUT /api/users/{userId}/preferences：保存语音偏好；未提供的字段保持原值。
- 请求示例：{"autoSpeakEnabled":true}。

## 快捷按钮结构

quickReplies：[{"label":"市第一医院","action":"SET_HOSPITAL","value":"h001"}]

医院和科室按钮的 value 使用数据库 ID；日期按钮和号源按钮都由数据库结果动态生成。
前端可以调整布局和样式，但不能私自更改字段。

## 医院与科室资料查询工具（2026-09-06）

- catalog.queryHospitals：读取启用的医院、等级、地址、特色和适老服务。
- catalog.queryDepartments：按 hospitalId 查询该医院的科室及复诊服务范围。
- catalog.searchDepartments：按科室或特色关键词查询科室。
- catalog.recommendHospitals：按明确的复诊科室筛选候选医院。
- 以上均为后端内部模拟工具，执行参数和数据库结果记录在 tool_call_logs。

## 会话恢复与预约记录（2026-09-03）

- GET /api/agent/conversations/{conversationId}：返回历史消息、当前阶段和最后一次卡片响应。
- GET /api/users/{userId}/appointments：按创建时间倒序返回全部预约，包含已确认和已取消状态。
- SET_PERIOD 的值为 MORNING 或 AFTERNOON；具体号源仍通过 SELECT_SLOT 提交。

## 可预约号源范围与意图（2026-09-06）

- 用户自由询问“有哪些时间可预约”“哪天有号”时，模型返回 QUERY_AVAILABLE_SLOTS。
- 后端随后调用 appointment.queryUpcomingSlots，参数包含 hospitalId、department、from、to。
- from 为当天，to 为一个月后；返回结果来自 appointment_slots，不由模型编造。
- SHOW_AVAILABLE_DATES 可作为按钮动作重新查询可预约日期。
- 模拟数据按工作日生成 09:00、10:30、14:00、15:30 四个时段；周末无号用于异常流程演示。

## 中控、个人预约与恢复动作（2026-09-06）

- QUERY_APPOINTMENTS：查询当前用户在 appointments 中的已确认预约。
- SELECT_APPOINTMENT_TO_CANCEL：value 必须为工具查询返回的 appointmentId；只生成取消确认卡，不直接取消。
- RESUME_INTERRUPTED：恢复进入查询/取消支线之前的流程节点。
- CHANGE_DEPARTMENT、CHANGE_TIME：清理受影响的下游选择，再进入对应节点。
- 自由语言 CONFIRM_ACTION、DENY_ACTION 只有在 AWAITING_CONFIRMATION 状态下有效。
- “取消当前办理”与“取消已确认预约”是不同路由；后者必须调用个人预约查询工具并经过确认端点。
