# 模拟数据库设计

> 文档版本：v0.2　更新日期：2026年9月11日

本文描述比赛演示所用的**模拟数据**：它不连接真实医院、地图或短信平台，所有医院、科室、号源、路线、联系人和照护关系都是造出来的样例数据，仅用于跑通「银龄复诊事项协同助手」的完整流程并支撑录屏验收。数据定义在 `backend/src/main/resources/schema.sql`（建表）与 `backend/src/main/resources/data.sql`（种子数据）。

## 一、运行环境

H2 地址：`jdbc:h2:file:./data/silver-agent`；用户名 `sa`；密码留空。数据落在后端工作目录下的 `./data/` 文件中，重启后端不丢失。

## 二、表清单（按用途分组）

**基础目录类**：`users`、`user_preferences`、`hospitals`、`departments`、`clinic_locations`、`appointment_slots`、`user_schedules`、`family_contacts`、`material_templates`、`care_guide_articles`、`travel_routes`。

**身份与协同关系类（v0.2 新增）**：`care_relations`。表达「哪位照护者协同哪位就诊人」，一对多；`role` 列为 `FAMILY` 或 `VOLUNTEER`，`relationship` 是称谓（如「女儿」「社区志愿者」）。后端据它判定会话身份（`AgentRole`：ELDER / FAMILY / VOLUNTEER）并校验代他人办理的权限。

**业务结果类**：`appointments`（含 `arranged_by` 代约归属、`accompanied_by` 陪同者、`conversation_id`）、`appointment_materials`、`reminders`、`family_notifications`。

**协同与健康类（v0.2 新增）**：`care_notifications`（定向发给照护者的协同通知，`kind` 区分类型）、`memos`（老人健康备忘，含 `repeat_rule`）、`health_records`（老人上报的实测数值）。

**Agent 审计类**：`conversation_sessions`、`conversation_messages`、`tool_call_logs`。

## 三、模拟用户与身份绑定

`users` 造了 4 个账号，覆盖三种身份：

| id | 姓名 | 身份 | 说明 |
|---|---|---|---|
| `user-001` | 王阿姨 | 就诊人（老人） | 主演示对象，有住址与出行偏好 |
| `user-002` | 张伯伯 | 就诊人（老人） | 第二位老人，便于演示志愿者跨老人协同 |
| `user-f001` | 小丽 | 家属 | 王阿姨的女儿 |
| `user-v001` | 李阿姨 | 社区志愿者 | 同时协同王阿姨与张伯伯 |

身份绑定写在 `care_relations`：

- `rel-f001`：`user-f001` → `user-001`，`FAMILY`，女儿。
- `rel-v001`：`user-v001` → `user-001`，`VOLUNTEER`，社区志愿者。
- `rel-v002`：`user-v001` → `user-002`，`VOLUNTEER`，社区志愿者。

因此用 `user-f001` / `user-v001` 作为操作者、`user-001`（或 `user-002`）作为服务对象即可演示代他人办理；换成未绑定的组合会被后端拒绝，返回「没有权限查看这位就诊人的信息」。

`user_preferences` 只为 `user-001` 造了一条（关闭自动朗读、语速 0.9、音量 1.0）。

## 四、医院、科室与诊室位置

- `hospitals`：`h001` 市第一医院（模拟，健康路1号）、`h002` 市人民医院（模拟，人民路88号），均为三级甲等，带头图经纬度和主入口名。
- `departments`：`d001`~`d006`，覆盖 h001 的心内科 / 神经内科 / 内分泌科和 h002 的内分泌科 / 骨科 / 心内科。
- `clinic_locations`：`loc-d001`~`loc-d006`，与上面科室一一对应，记录门诊楼、入口、楼层、诊室、报到点、地标和无障碍路线，供「找不到诊室」的指引演示。

## 五、号源与既有日程（冲突是设计好的）

`appointment_slots` 造了两批号源，日期都落在演示期（2026-09-17 至 09-21）：

- h001 心内科：09-18 有 09:00 / 10:20 / 14:30 / 16:00 四个时段，另有 09-17 09:00、09-19 14:30 各一个。
- h002 内分泌科：09-20 有 09:00 / 10:30 / 14:00 / 15:00 四个时段，另有 09-21 09:30 一个。

`user_schedules` 给 `user-001` 造了两条既有日程：`schedule-001` 社区体检（09-18 10:00–11:00）、`schedule-002` 和家人吃饭（09-20 12:00–13:30）。其中 09-18 10:00 的体检**故意与 09-18 10:20 的心内科号源重叠**，用于稳定复现「时间冲突」场景；无号源场景则靠查询没有号源的日期触发。

`family_contacts` 只造了一条：`family-001` 属于 `user-001`，小丽，女儿，电话按脱敏形式展示（不落明文完整号码）。

## 六、材料模板与照护指南

- `material_templates`：`m001`~`m008`，含 5 条通用材料（身份证、医保卡、上次病历、既往检查报告、药物清单）和心内科 / 内分泌科各一条专科材料，带必填标记与排序。
- `care_guide_articles`：4 篇指南，`guide-process` 流程、`guide-arrival` 到院步骤、`guide-help` 求助渠道、`guide-change` 改期取消，用于「怎么办、找谁问」类问答。

## 七、出行路线

`travel_routes`：`route-001`~`route-006`，覆盖「幸福小区（模拟）」到两家医院、三种交通方式（家属开车 / 打车 / 公交）的耗时、距离、分步路线和折线坐标，`route_source` 标为 `SIMULATED`，供出发时间建议与地图引导演示。

## 八、健康备忘与健康记录（运行时产生）

`memos` 与 `health_records` 是 v0.2 新增能力，`data.sql` **不预置任何记录**，都由老人在对话中现场产生，语义刻意分开：

- `memos` 记「要做的事」：有 `remind_at`（下一次到点）和 `repeat_rule`（`null` 只提醒一次 / `DAILY` 每天 / `WEEKLY` 每周 / `MONTHLY` 每月），`status` 为 `ACTIVE` / `DONE` / `DELETED`。`WEEKLY` 用 `remind_at` 的星期几做锚点，`MONTHLY` 用几号做锚点。可查、改时间、标记完成、删除。
- `health_records` 记「已经量到的数」，只增不删：有数值记 `value_num`（如血压 100/60 取 100），只有说法（「有点高」）时 `value_num` 为空、记 `value_text`，另有 `unit`、`raw_text` 和来源 `conversation_id`，用于回查最近几次。

## 九、协同通知与业务结果（运行时产生）

`care_notifications`、`appointments`、`appointment_materials`、`reminders`、`family_notifications` 同样不预置种子数据，随演示流程写入。代他人办理时，预约会在 `appointments.arranged_by` 记为操作者；协同通知落到 `care_notifications`，定向发给对应照护者账号。

## 十、数据装载策略

`data.sql` 只做**补齐**：用 `MERGE` 或 `INSERT ... WHERE NOT EXISTS` 写入目录类数据，并补齐缺少的号源；**不随启动清空**业务结果、会话或已占用号源。`schema.sql` 建表用 `IF NOT EXISTS`，新增列用 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`，便于旧库平滑升级。新建空数据库首次进入时没有预约；复用旧库时原有记录应保留。

## 十一、演示与验收注意

- 上述均为比赛演示用模拟数据，不可当作真实医疗信息，页面也应明确标注「模拟环境」。
- 当前没有场景选择 / 重置 API；`DemoController` 仅提供 `/api/demo/health` 健康检查。要稳定复现四组场景（正常办理、无号源、时间冲突、服务越界），可重复验收应使用隔离的测试数据库与固定时钟，而不是靠删除日常演示记录来制造场景。
- 目标数据关联与执行结果设计见 [Design.md](../Design.md)。

> 已实现：上表结构与种子数据、`care_relations` 权限校验、备忘 / 健康记录的读写接口与存储。
> 后续规划：独立的场景选择 / 重置接口、工具故障注入与固定时钟，用于录屏时一键复现场景。
