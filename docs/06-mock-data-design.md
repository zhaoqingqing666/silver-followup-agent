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
- `departments`：`d001`~`d012`，**两家医院各 6 个科室、科室名集合完全相同**：心内科 / 神经内科 / 内分泌科 / 骨科 / 呼吸内科 / 消化内科。h001（市第一医院）持 `d001` / `d002` / `d005` / `d007` / `d008` / `d009`，h002（市人民医院）持 `d003` / `d004` / `d006` / `d010` / `d011` / `d012`。2026-09-14（DEC-026）之前只有 6 个科室、且两院科室名互补；`d001`~`d006` 的 id 与语义**一个都没动**，只追加 `d007`~`d012`，所以 `DemoSeed` 的常量与既有回归不受影响——代价是 id 顺序不再连续。
- `clinic_locations`：`loc-d001`~`loc-d012`，与上面科室一一对应，记录门诊楼、入口、楼层、诊室、报到点、地标和无障碍路线，供「找不到诊室」的指引演示。

## 五、号源与既有日程（滚动生成，冲突是设计好的）

号源与日程都**不写死日期**，否则演示期一过，「时间冲突」就再也造不出来。两者都在启动时按当天的日期生成：

- `doctors`：`doc-<科室id>-<序号>`，12 个科室共 **48** 名医生（**每科室 4 人**：01 主任医师 / 02 主治医师 / 03 主治医师 / 04 副主任医师）。`slot_type` **手工指定**号别（主任医师 / 副主任医师 = `EXPERT`，主治医师 = `NORMAL`），职称只用于展示、不参与任何匹配。**出诊日不再落在表上**——排班是生成器的纯函数（见下），这张表只回答「这人是谁、放什么号别、挂号费多少」。每科室 4 位医生正好一人一格坐满：**2 位专家 + 2 位普通**。新增医生的姓氏一律避开同科室已有的姓——`matchDoctorId` 的规则是「全名精确，或姓氏唯一才认」，同科室两个姓会触发反问。`data.sql` 少一位 `RollingAppointmentSlotInitializer.doctorOf()` 直接抛错，不会静默少排一班。
- `appointment_slots`：由 `RollingAppointmentSlotInitializer` 按「今天起一个月内」生成，id 形如 `r-doc-d001-01-20260916-0900`（`r-医生id-日期-时刻`）。排班口径（DEC-026）：**每个科室每周固定放 3 个工作日的号**（同一家医院内各科室错开、周末一律不放），放号日**一天 4 格、每格 1 位医生**——09:00 / 14:00 是**专家格**，10:30 / 15:30 是**普通格**，于是上午、下午都各有一格专家号和一格普通号。新增两列 `capacity` / `booked`（DEC-026）：一条号源不再是「一个号」，而是「一位医生在一个时刻里的一班」，`capacity` 是这一班放几个名额（**专家号 3 / 普通号 4**），`booked` 是已占几个，`available` **降级为派生位**（`available = booked < capacity`）。初始化是幂等的，重复启动只会补齐缺的格子；末尾还有一次 `reconcileCapacities()` 按实际预约数回填 `booked`、按号别修正 `capacity`、重算 `available`。每周每科室产出 **2 条专家 + 2 条普通**（按**名额**是 6 : 8）；全院一个月 ≈ 624 条号源、≈ 2184 个名额。
- 排班是**算出来的、不是抽出来的**：`openWeekdays` / `expertAt` / `normalAt` / `doctorAt` / `doctorsAt` 都是纯函数，同一个「科室 + 日期」在任何一次启动、任何一台机器上都得到同一份，不用 `Math.random()`。演示要能提前彩排、回归要按号源 id 断言，靠的就是这一点——`DoctorSlotModelTests` 把「重跑一次 `seed()` 号源集合一点不变」钉成了回归。
- 「同一时刻只有一条号源」这个前提**兜了一圈又回来了**：DEC-022 曾让它失效（上午 09:00 天然两条），DEC-026 改成「每格 1 位医生」之后**重新成立**。但界面上仍然必须带「医生 + 号别」——一天 4 格里坐的是 4 位不同医生，只给一个时刻分不出是谁。助手列号与家属代约都要把医生名字和号别一并带出来。
- 「**普通号名额总量 &gt; 专家号名额总量**」仍然是供给侧硬约束（演示口径「专家号经常约满、普通号相对好挂」），但**口径与实现都变了**（DEC-026）：比的是**名额**不是条数——按条数是 2 : 2 相等，按名额才是 6 : 8。名额定在 `capacity` 上（专家 3 / 普通 4），所以这条约束**与「下午排不排专家号」已经无关**。由 `DoctorSlotModelTests.everyDepartmentHasMoreNormalCapacityThanExpertCapacity` 钉住。
- ⚠️ **「下午没有专家号」这个演示场景已经不成立**（DEC-026 废止了 DEC-023 决定二 / DEC-024 决定三）：14:00 现在**就是**专家格。要演 R1 的时段级降级，只能**手工把那一格约满**（`booked=capacity`）——`DoctorIntentTests.soldOutMorningStillOffersTheNearestExpertWithinSevenDays` 走的正是这条路。上一轮新增的 `MockAppointmentTool.requireAfternoonIsNotExpert()` 与整份 `AfternoonExpertGuardTests`（4 例）已一并删除。
- 放号日有两处是**留给演示场景的**，不能随手改：**d001（市第一医院·心内科）的放号日固定含周三**——冲突演示把「下周三体检 + 当天 10:30 心内科号源」钉在那天；**同时必须含周五**——「下周六无号 → 往后三天的附近日期」这条降级路径才取得到号。两点都写在 `RollingAppointmentSlotInitializer.openWeekdays` 的注释里，并由 `DemoSeedDataTests` 与 `MockAppointmentAlternativesTests` 兜住。另外，「各科室放号日互不相同」的口径是**同一家医院内**（每院 6 科、从 `{周一..周五}` 的 C(5,3)=10 种组合里挑 6 种；跨院允许撞同一天）——全院 12 科装不下「全院两两不同」，那是数学上不可能的（DEC-026）。
- 排班口径换过**三次**（① 「每位医生错开出诊日」→「每科室每周固定 3 天 + 上午/下午半天制」；② 03 号从「副主任医师 / 专家号」改成「主治医师 / 普通号」、下午从「部分专家」改为「全部普通」；③ DEC-026 改成「一天 4 格、每格 1 位医生、号别按格固定」，并**废止**了 ②里的「下午不排专家号」），所以启动时会把**没有预约在用的 `r-%` 号源整批清掉重建**；**有预约挂着的保留**——删了那条预约的 `slot_id` 就指向不存在的行，而预约列表是 JOIN 号源出来的，页面上会直接看不到它、连取消都取消不了。留下来的那几条没有医生信息，前端如实显示「医生信息未记录」；它们会停在建表默认的 `capacity=1`，由 `reconcileCapacities()` 兜回来。
- `user_schedules`：由 `RollingUserScheduleInitializer` 给 `user-001` 排在**下周三 / 下周六**：`schedule-001` 社区体检（下周三 10:00–11:00）、`schedule-002` 和家人吃饭（下周六 12:00–13:30）。

下周三 10:00 的体检**故意与当天 10:30 的心内科号源重叠**，用于稳定复现「时间冲突」场景；同一天 09:00 / 14:00 / 15:30 的号源不与体检重叠，是「正常办理」的对照组。这里的「下周三」与口语解析同一口径（`RuleFactExtractor` 取「本周一 + 1 周 + 2 天」），所以演示话术「我下周三想去市第一医院心内科复诊」落到的正是体检那天。`backend/src/test/java/com/team/silveragent/DemoSeedDataTests.java` 把这几条前提钉成了回归用例——哪条不再成立，先在那里失败。

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
- 场景一键重置：`POST /api/demo/scenarios/{scenarioId}`（编号 `normal` / `no-slot` / `conflict` / `boundary`）。它清掉上一场演示留下的可变数据（预约、提醒、家属通知、工具记录、会话与附件、识图结论、健康记录、备忘、长期记忆、照护通知），放开被占用的号源，按「今天」重排号源与日程，然后开一段全新的会话，并返回**照着念就能复现的步骤**（步骤里的日期每次现算，不抄一份会过期的文稿）。目录数据（医院、科室、材料模板、出行路线、家属联系人）一条不动。
- 这是**破坏性**接口，只用于录屏与评审查验：它会删除数据，不该出现在正常业务流程里，演示开场前调一次即可。展开说明见 [05-api-contracts.md](05-api-contracts.md) 的「演示场景重置」一节。
- 四组场景依赖的种子数据已全部改为滚动生成（见第五节），**任何一天都能复现**，不再依赖「演示那几天」或固定时钟：冲突看的是「下周三」的体检，无号看的是周末的空档。
- 目标数据关联与执行结果设计见 [Design.md](../Design.md)。

> 已实现：上表结构与种子数据、`care_relations` 权限校验、备忘 / 健康记录的读写接口与存储、演示场景一键重置。
> 后续规划：工具故障注入与固定时钟，用于录屏时复现异常链路。
