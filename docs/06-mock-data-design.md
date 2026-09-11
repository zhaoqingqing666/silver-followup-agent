# 模拟数据库设计

H2地址：jdbc:h2:file:./data/silver-agent；用户名sa；密码留空。

基础数据：USERS、HOSPITALS、DEPARTMENTS、APPOINTMENT_SLOTS、USER_SCHEDULES、FAMILY_CONTACTS、MATERIAL_TEMPLATES。
业务结果：APPOINTMENTS、REMINDERS、FAMILY_NOTIFICATIONS。
Agent审计：CONVERSATION_SESSIONS、CONVERSATION_MESSAGES、TOOL_CALL_LOGS。

当前 `schema.sql` 使用 `IF NOT EXISTS`，`data.sql` 只补齐医院、科室、家属、材料模板和路线等目录数据，不随启动清空业务结果、会话或已占用号源。新建空数据库首次进入时无预约；复用数据库时应恢复原有记录。

基础数据还包括 `TRAVEL_ROUTES`，用于按模拟住址、医院地址和交通方式查询耗时。

## 滚动种子（2026-09-11）

`APPOINTMENT_SLOTS` 和 `USER_SCHEDULES` 不在 `data.sql` 里写死日期，改由两个 `ApplicationRunner` 在每次启动时按“今天”生成，避免演示脚本随日期过期：

- `RollingAppointmentSlotInitializer`：为每个启用科室生成今天到一个月后的工作日号源（09:00、10:30、14:00、15:30），并清掉周末号源，用来复现“指定日期无号源”。号源编号规则为 `r-<科室ID>-<yyyyMMdd>-<HHmm>`；同科室、日期、时间的号源只保留一条。
- `RollingUserScheduleInitializer`：写入 `schedule-001`「社区体检」（下周三 10:00-11:00，与 10:30 档位必然重叠）和 `schedule-002`「和家人吃饭」（下周六 12:00-13:30，用于验证不误报冲突）。

测试同样不写死日期：`SilverAgentApplicationTests` 用与初始化器一致的规则推算日期与号源编号。

## 场景重置 API（2026-09-11）

`POST /api/demo/scenarios/{scenarioId}` 把四场景从口头指导变成一次调用：清空预约、提醒、通知、工具记录和全部会话，按“今天”重新生成号源与用户日程，然后返回一个全新的会话与演示步骤。

- 可用 `scenarioId`：`normal`、`no-slot`、`conflict`、`boundary`。
- 这是破坏性接口，只用于录屏与评审查验，不参与正常业务流程。
- 目录数据（医院、科室、材料模板、路线）不会被清空。

目标数据关联与执行结果设计见 [Design.md](../Design.md)。
