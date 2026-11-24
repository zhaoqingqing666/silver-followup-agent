# 模拟数据库设计

> 文档版本：v0.3　更新日期：2026年9月13日

本文描述比赛演示所用的**模拟数据**：它不连接真实医院、地图或短信平台，所有医院、科室、号源、路线、联系人和照护关系都是造出来的样例数据，仅用于跑通「银龄复诊事项协同助手」的完整流程并支撑录屏验收。数据定义在 `backend/src/main/resources/schema.sql`（建表）与 `backend/src/main/resources/data.sql`（种子数据）。

需要逐表核对字段和开发数据库当前的全部记录时，查看 [H2 数据库全表与数据快照](14-database-table-data.md)；该快照同时包含启动时滚动生成的数据和运行过程中产生的业务数据。

> v0.3 新增第十二节，说明四类骨架工具（预约查询 / 日程管理 / 出行规划 / 家属通知）**具体怎么实现**：数据怎么读、工具怎么被调用、并发与幂等怎么保证。前十一节讲「有哪些数据」，第十二节讲「工具怎么用这些数据」。

## 一、运行环境

H2 地址：`jdbc:h2:file:./data/silver-agent`；用户名 `sa`；密码留空。数据落在后端工作目录下的 `./data/` 文件中，重启后端不丢失。

## 二、表清单（按用途分组）

**基础目录类**：`users`、`user_preferences`、`hospitals`、`departments`、`clinics`、`appointment_slots`、`user_schedules`、`family_contacts`、`material_templates`、`care_guide_articles`、`travel_routes`。

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
- `clinics`：`loc-d001`~`loc-d006`，与上面科室一一对应，记录门诊楼、入口、楼层、诊室、报到点、地标和无障碍路线，供「找不到诊室」的指引演示。

## 五、号源与既有日程（滚动生成，冲突是设计好的）

号源与日程都**不写死日期**，否则演示期一过，「时间冲突」就再也造不出来。两者都在启动时按当天的日期生成：

- `appointment_slots`：由 `RollingAppointmentSlotInitializer` 按「今天起一个月内的每个工作日」生成，每个启用科室每天四格——09:00 / 10:30 / 14:00 / 15:30，id 形如 `r-d001-20260916-0900`（`r-科室-日期-时刻`）。**周末刻意不排**，「指定日期没有号源」这个场景因此长期可复现。初始化是幂等的，重复启动只会补齐缺的格子。
- 号源只存 `hospital` 和 `department` 两个编号（分别外键指向 `hospitals` / `departments`），医院名和科室名不再冗余在这张表里：改名只需改一处，也不会留下对不上的旧副本。展示用的中文名由查询 JOIN 取回，所以对外接口和前端都不受影响。
- `user_schedules`：由 `RollingUserScheduleInitializer` 给 `user-001` 排在**下周三 / 下周六**：`schedule-001` 社区体检（下周三 10:00–11:00）、`schedule-002` 和家人吃饭（下周六 12:00–13:30）。

下周三 10:00 的体检**故意与当天 10:30 的心内科号源重叠**，用于稳定复现「时间冲突」场景；同一天 09:00 / 14:00 / 15:30 的号源不与体检重叠，是「正常办理」的对照组。这里的「下周三」与口语解析同一口径（`RuleFactExtractor` 取「本周一 + 1 周 + 2 天」），所以演示话术「我下周三想去市第一医院心内科复诊」落到的正是体检那天。`backend/src/test/java/com/team/silveragent/DemoSeedDataTests.java` 把这几条前提钉成了回归用例——哪条不再成立，先在那里失败。

`family_contacts` 只造了一条：`family-001` 的 `"USER"` 为 `user-001`，`contact` 为 `user-f001`，两列均关联 `users.id`。姓名、手机号从联系人用户读取，称谓从 `care_relations` 读取；手机号保存在 `users.phone`，对外仅展示脱敏号码。

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
- **演示话术避开「步行」**：`travel_routes` 只配了「家属开车 / 打车 / 公交」三种交通方式，而口语解析认得「步行」这个词。说「我走过去」会命中未配置路线而走异常分支（回复仍是可继续的，但演示不连贯）。细节见 12.2 第③小节。
- 目标数据关联与执行结果设计见 [Design.md](../Design.md)。

> 已实现：上表结构与种子数据、`care_relations` 权限校验、备忘 / 健康记录的读写接口与存储、演示场景一键重置。
> 后续规划：工具故障注入与固定时钟，用于录屏时复现异常链路。

## 十二、模拟工具的实现方式

前面十一节讲的是**有哪些数据**，本节讲**工具怎么用这些数据**。核心一句话：不是「返回假的硬编码结果」，而是**用真数据库跑真 SQL**——所有工具都是 Spring Bean + `JdbcTemplate`，读写上一节那些 H2 表，参数与结果全部落 `tool_call_logs`。

### 12.1 三类数据的填充方式

数据分三层填充，这个划分决定了「哪些能随便改、哪些一改就坏」：

| 层 | 位置 | 内容 | 特点 |
| --- | --- | --- | --- |
| **结构** | `schema.sql` | 全部建表与加列 | `CREATE TABLE IF NOT EXISTS` + `ALTER TABLE ADD COLUMN IF NOT EXISTS`，幂等，便于旧库升级 |
| **静态目录** | `data.sql` | 医院、科室、诊室位置、路线、材料模板、家属、指南文章 | `MERGE ... KEY(id)` 或 `INSERT ... WHERE NOT EXISTS`，**只补齐不清空** |
| **滚动数据** | 两个 `ApplicationRunner` | 号源、既有日程 | **不写死在 SQL 里**，启动时按「今天」现场生成（见第五节） |

第三层是这套设计里最要紧的一处：号源与日程如果写死日期，演示期一过，「时间冲突」就再也造不出来——冲突检查只看预约当天，一条落在过去的日程永远不会命中。改由 `RollingAppointmentSlotInitializer` 与 `RollingUserScheduleInitializer` 现算之后，四个场景**任何一天都能复现**。

### 12.2 四类工具的实现

`domain/tool/` 下是接口，`infrastructure/mock/` 下是实现。四类骨架工具的对应关系如下。

#### ① 预约查询 —— `MockAppointmentTool`

```java
List<Slot> queryAvailableSlots(conversationId, hospitalId, department, LocalDate date)
```

三个参数正好对应「指定医院、科室和日期」，SQL 落在 `appointment_slots`：

```sql
WHERE hospital_id=? AND department=? AND appointment_date=? AND available=TRUE
  AND (appointment_date > CURRENT_DATE
       OR (appointment_date = CURRENT_DATE AND appointment_time > CURRENT_TIME))
```

最后那个 `OR` 分支过滤掉**当天的已过时段**——不然上午十点打开助手，还会看到九点的号。

三个查询方法的差别只在日期窗口：

| 方法 | 窗口 | 用途 |
| --- | --- | --- |
| `queryAvailableSlots` | 单日 | 选日期后的实时查询 |
| `queryUpcomingSlots` | 调用方指定区间 | 未来若干天的号源概览 |
| `queryAlternatives` | `date+1 ~ date+3` | 指定日期无号时的附近候选 |

`queryAlternatives` **只往后看三天**，代码注释写明了原因：往前找会捞出已经过去的时段，把当天其它时段算进「附近日期」还会跟上一句「这一天暂无号源」自相矛盾；当天之内换时段由另一条路（`SELECT_PERIOD`）负责，两条路不重叠。

#### ② 日程管理 —— `MockScheduleTool`

两个方向，命题说的「查询已有日程」与「创建提醒」都在：

```java
List<Conflict> findConflicts(conversationId, userId, start, end)   // 查已有日程
String createReminder(conversationId, userId, title, remindAt)     // 创建提醒
```

冲突检查用**区间相交**判据，不是「同一天就报错」：

```sql
WHERE user_id=? AND start_at < ? AND end_at > ?
```

调用方传入的是 `start` 与 `start + 60 分钟`（预约时长）。这条判据解释了场景三那个对照组：09:00 的号到 10:00 结束，正好不碰 10:00 开始的体检；10:30 的号才与体检重叠。

**两类提醒都真的创建**（在 `FollowupAgentService#confirm` 里，即用户点过确认之后）：

| 提醒 | 触发时刻 |
| --- | --- |
| 复诊材料准备提醒 | 复诊**前一天** |
| 复诊出发提醒 | 建议出发时间**前 10 分钟** |

#### ③ 出行规划 —— `MockTravelTool`

```java
TravelPlan plan(conversationId, userId, hospital, appointmentAt, transport)
```

按「模拟地址 + 预约时间 + 交通方式 → 建议出发时间」实现：

1. 起点取 `users.home_address`，终点取 `hospitals.address`（先 `REPLACE(name,'（模拟）','')` 清洗）
2. 按 `origin + destination + transport` 查 `travel_routes`，得到 `duration_minutes`
3. `departure = appointmentAt.minusMinutes(duration + 20)`

那个 **+20 分钟是刻意预留的取号时间**，返回文案会明说「预计 X 分钟，并预留 20 分钟取号时间」。

> **演示注意（重要）**：`travel_routes` 只配了三种交通方式——家属开车 / 打车 / 公交（见第七节）。而 `RuleFactExtractor.transport()` 的口语识别**认得「步行」这个词**（`List.of("家属开车","打车","公交","步行")`）。老人一说「我走过去」，`MockTravelTool.duration()` 会返回 `null` 并抛 `IllegalStateException("没有配置该交通方式的模拟路线")`——回复仍可继续（走 `toolError` 兜底），但演示不连贯。**演示时请用「家属开车」「打车」「公交」。**
>
> 另外起点是精确匹配 `users.home_address`，所以只有 `user-001` / `user-002`（住址均为「幸福小区（模拟）」）能查到路线；`user-f001` / `user-v001` 的住址为 `NULL`，会先抛「没有配置用户出发地址」。

#### ④ 家属通知 —— `MockFamilyNotificationTool`

```java
Contact findPrimaryContact(conversationId, userId)   // 查主联系人，不产生任何写操作
String notify(conversationId, contactId, message)    // 发送通知
```

「发送通知」就是往 `family_notifications` 插一行、`status='SENT'`——**没有任何短信、邮件或即时通信网关**。

通知正文由 `FollowupAgentService#notificationMessage` 确定性拼装，**命题要求的两个信息都在**：

```java
"复诊安排：" + hospital + " " + department + "，" + date + " " + time
    + (needCompanion ? "，需要陪同。" : "，不需要陪同。")
```

两个实现细节：

- **电话在 Java 层脱敏**（`maskPhone`），进 `Contact` 之前就变成 `138****1234`，调用方拿不到明文完整号码。
- `family_contacts` 只有一条（`family-001` 小丽，属 `user-001`），所以只有 `user-001` 能被通知到家属。

### 12.3 工具怎么被调用：两层守门

工具不是让模型随便调的。链路是「模型提出 → 后端校验 → Java 执行」：

```text
① 模型输出 PlannerDecision
   actionType ∈ {ANSWER, ASK_USER, CALL_READ_TOOL, CALL_READ_TOOLS,
                 CALL_CONFIRMATION_TOOL, PROPOSE_WORKFLOW_ACTION}
        ↓
② AgentRuntime#modelProposal 逐个校验
   ToolRegistry#find(name)  →  ToolPolicy#evaluate(role, tool)
        ↓
③ 校验通过才产生 Route，由 FollowupAgentService 真正执行
```

`ToolPolicy` 的判定有四种结果：`DENY_UNKNOWN_TOOL`（工具不存在）/ `DENY_SIDE_EFFECT`（有副作用）/ `DENY_ROLE`（角色不符）/ `ALLOW`。

**两种 risk 的区别是这套设计的核心**：

| | `READ_ONLY` | `CONFIRMATION_ONLY` |
| --- | --- | --- |
| 注册入口 | `ToolRegistry#register` | `ToolRegistry#registerInteraction` |
| 校验方法 | `ToolPolicy#evaluate` | `ToolPolicy#evaluateConfirmation` |
| 能做什么 | 校验通过后**可直接自动执行** | **绝不自动执行**，只进入 Java 确认状态机 |
| 调用动作 | `CALL_READ_TOOL` / `CALL_READ_TOOLS` | `CALL_CONFIRMATION_TOOL` |

`ToolPolicy` 的类注释点破了为什么要设第二道：按角色过滤工具清单**只影响模型「看得见」什么，不构成安全边界**——模型仍可能吐出一个它看不见的工具名。所以执行前要按当前会话的操作者身份再判一次，两道都得有。

`AgentRuntime` 对「模型编了一个执行不了的工具」也做受控处理：绝不执行，也不再静默降成一段没有卡片的回答。intent 能归到既有流程就走那条流程，归不到则由 `Route.REFUSE_UNSUPPORTED_TOOL` 明确回绝（不走 `DIRECT_ANSWER`，那条路会 `pauseActiveTask`，把正在办理的流程停掉）。

#### `callTool`：一层薄壳

所有业务调用点都经过这个方法：

```java
private <T> T callTool(ConversationState state, String name,
                       Map<String, ?> parameters, Supplier<T> operation) {
    try { return operation.get(); }
    catch (RuntimeException error) {
        traces.record(state.id, name, parameters,
                Map.of("error", String.valueOf(error.getMessage())), false);
        throw error;   // 记完再抛，不吞异常
    }
}
```

它只做一件事：**保证任何工具异常都必有一条失败 trace**。异常转成人话是 `toolError` 的事（把 stage 切到 `TOOL_ERROR` / `PARTIAL` 并给出可继续的回复）。

#### `ToolTraceStore`：全项目唯一的下沉点

```java
jdbc.update("INSERT INTO tool_call_logs(...)");
progress.toolResult(conversationId, toolName, requestJson, responseJson, success);
```

它的注释说明了这个位置的价值：所有工具调用都经过它落 `tool_call_logs`，**所以实时进度也只在这一个地方打点**——不必去三十多个调用点各改一遍，也不会漏。

这是「工具有没有真被调用」最硬的证据链：评委在前端「办理过程」里看到每一步的工具名、参数和结果，来源就是这张表。

### 12.4 数据一致性：并发与幂等

#### 号源占用用条件更新，不是「先查后改」

```java
int changed = jdbc.update(
    "UPDATE appointment_slots SET available=FALSE WHERE id=? AND available=TRUE", slotId);
if (changed != 1) throw new IllegalStateException("该号源刚刚已不可用，请重新选择");
```

**用更新影响行数判断是否抢到**。两个并发请求抢同一个号源，行锁保证只有一个能把 `TRUE` 改成 `FALSE`，另一个 `changed=0` 得到明确失败。这是标准的 CAS 语义；若写成「先 `SELECT` 看 available，再 `UPDATE`」，就会有 TOCTOU 竞态。

所有写方法都标 `@Transactional`，保证「置号源 + 插预约」原子完成，中途异常整体回滚，号源不会漏放。

#### 幂等：两种范式

**业务键查重**（`MockAppointmentTool#submit`）：

```java
SELECT id FROM appointments WHERE conversation_id=? AND user_id=? AND status='CONFIRMED';
if (!existing.isEmpty()) return existing.get(0);   // 已有就返回老 id，不二次占号
```

同一会话同一用户重复提交 → 不会插入第二行。这与上层提交前的重复检查（`appointment.checkDuplicate`）配合，构成「一个会话一次成功预约」。

**确定性 UUID**（`MockFamilyNotificationTool#notify`、`MockScheduleTool#createReminder`）：

```java
String id = "NT-" + UUID.nameUUIDFromBytes((conversationId + contactId + message).getBytes(UTF_8));
if (查得到) return id;   // 同内容重复发送 → 去重
```

同样的会话 + 同样的联系人 + 同样的文案 → 永远算出同一个 id，先查重再插入。`createReminder` 用同一范式（`"RM-" + conversationId + title + remindAt`）。

`cancel` 与 `cancelAll` 同样用条件更新防重复取消与越权取消；`cancelAll` 先对整批做归属与状态预校验，任一条失效则**整批不动**。`reschedule` 先抢占新号源，失败则抛「新号源不可用，原预约保留」，**失败不影响原预约**。

### 12.5 关于 `Mock*` 与 `H2*` 两种命名

`infrastructure/mock/` 包里两类前缀并存：`MockAppointmentTool`、`MockTravelTool`、`MockFamilyNotificationTool` 等，与 `H2MyAppointmentTool`、`H2CareGuideTool`、`H2RouteGuideTool`、`H2FacilityGuideTool`、`H2MaterialPreparationTool`。

**这是历史遗留命名，没有技术差异。** 两类都是 `@Component` + 构造注入 `JdbcTemplate` + 手写 SQL，实现范式一致，差别只是各自查的表不同——较早写的目录/办理查询类倾向 `H2*`（强调「这是查 H2 库」），较晚写的写操作/工具类倾向 `Mock*`（强调「这是模拟工具」）。**没有任何代码按前缀做条件判断**：`@Component` 不带 value，全项目无 `@Qualifier`，一律按接口类型注入。

理解「模拟实现」的语义边界应当看**包名 `infrastructure/mock/`**，而不是类名前缀。

补充一点：`CareCatalogRepository`（`application/care/`）是医院与科室目录的仓储层，被 `MockHospitalCatalogTool` / `MockDepartmentCatalogTool` 消费，也供 `AgentOrchestrator` 判断句子里是否点名了医院或科室。它对模型不可见，是 mock 包目录类工具背后的「数据访问 + 展示脱敏」层——`familyMembers()` / `maskPhone()` 负责脱敏，`clean()` 负责去掉名称里的「（模拟）」后缀。

