# 模拟数据库设计

H2地址：jdbc:h2:file:./data/silver-agent；用户名sa；密码留空。

基础数据：USERS、HOSPITALS、DEPARTMENTS、APPOINTMENT_SLOTS、USER_SCHEDULES、FAMILY_CONTACTS、MATERIAL_TEMPLATES。
业务结果：APPOINTMENTS、REMINDERS、FAMILY_NOTIFICATIONS。
Agent审计：CONVERSATION_SESSIONS、CONVERSATION_MESSAGES、TOOL_CALL_LOGS。

当前 `schema.sql` 使用 `IF NOT EXISTS`，`data.sql` 补齐目录和缺少的号源，不随启动清空业务结果、会话或已占用号源。新建空数据库首次进入时无预约；复用数据库时应恢复原有记录。

基础数据还包括 `TRAVEL_ROUTES`，用于按模拟住址、医院地址和交通方式查询耗时。当前没有场景选择/重置 API；`DemoController` 仅提供健康检查。可重复验收应使用隔离测试数据库，并增加固定时钟及工具故障注入，避免通过删除日常演示记录制造场景。目标数据关联与执行结果设计见 [Design.md](../Design.md)。
