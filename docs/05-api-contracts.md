# 前后端 API 契约

服务地址：http://localhost:8080

## 创建会话

POST /api/agent/conversations?userId=user-001，无请求体。

userId 可省略；省略时读取 DEMO_USER_ID，默认 user-001。

## 自由语言消息

POST /api/agent/messages
请求：{"conversationId":"会话ID","message":"9月20日不行了，换到21日"}
自由文字和语音转文字使用此接口，后端携带上下文调用一次模型。

## 明确按钮操作

POST /api/agent/actions
请求：{"conversationId":"会话ID","action":"SELECT_SLOT","value":"slot-0920-1500","label":"9月20日 15:00"}
按钮使用此接口，不调用大模型。
label 是可选的用户可读文字；value 可以继续使用 hospitalId、departmentId 或 slotId。

## 确认关键操作

POST /api/agent/confirmations
请求：{"conversationId":"会话ID","approved":true}
只有确认接口可以触发提交或取消预约、创建提醒和通知家属。

## 查询用户资料

GET /api/users/user-001

返回姓名、模拟住址和偏好交通方式；首页不得写死用户姓名。

## 查询真实事项

GET /api/users/user-001/appointments
未预约返回空数组，确认预约后返回数据库记录。

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
