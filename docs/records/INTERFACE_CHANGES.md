# 接口变更记录

## 2026-09-16 APPOINTMENT_SLOTS 医院与科室改为外键引用

- `appointment_slots` 移除 `hospital_name`；`hospital_id` 更名为 `hospital`，`department` 由中文科室名改存 `departments.id`。两列都加了外键：`FOREIGN KEY (hospital) REFERENCES hospitals(id)`、`FOREIGN KEY (department) REFERENCES departments(id)`。
- 旧库升级沿用 FAMILY_CONTACTS 那次的「临时兼容列」写法：先补出 `hospital_id`/`hospital_name`，把医院编号搬进 `hospital`、按「同院同名」把中文科室名折算成 `departments.id`，最后删掉两个旧列。每条 UPDATE 只处理尚未迁移的行，重复启动不会二次改写，新库和旧库共用同一份脚本。
- 折算不到的号源（科室已被删除或改过名）在没有预约引用时会被清掉；仍被预约引用的保留，让外键把启动拦下来报错，而不是静默抹掉一条真实预约对应的号源。
- **HTTP 接口和前端类型都没有变**：医院名、科室名改由查询 JOIN `hospitals`/`departments` 取回，`Slot`、`AppointmentView`、`AppointmentSummary`、`AppointmentTravelGuide` 等对外字段的名称、顺序、取值全部保持原样。`appointment.querySlots` 等工具的入参仍是中文科室名，调用方无需调整，前端 9 个文件零改动。
- 本次把「按中文科室名查号源」改成先 JOIN 再按 `d.name` 过滤，索引效果不如直接比编号；演示数据量（576 行）下无实际影响，记录在此以备后续优化时参考。
- 同步更新 `docs/14-database-table-data.md`（字段定义与 576 行快照已按新列标注）、`docs/06-mock-data-design.md`；`data.sql` 的 `clinic_location_id` 映射改为 `'loc-' || department` 直接拼接。
- 容器内验证尚未执行：按用户要求未在宿主机构建。后续可在开发容器内执行 `mvn -f backend/pom.xml test -Dagent.model.enabled=false`，并确认 `appointment_slots` 已无 `hospital_id`/`hospital_name` 列、两个外键存在、号源科室值形如 `d001`。

## 2026-09-16 CLINIC_LOCATIONS 表更名为 CLINICS

- `schema.sql` 把 `clinic_locations` 更名为 `clinics`，列定义、约束和 6 条诊室数据不变。`appointment_slots.clinic_location_id` 是**列名**，不在本次改名范围，保持原样。
- 旧库升级在建表前执行 `DROP TABLE IF EXISTS clinic_locations`：改名后该表已无外键与代码引用，数据由 `data.sql` 的 `MERGE INTO clinics` 按 id 全量重建，因此直接删旧表，不留两张同义表。对新建库和重复启动均为空操作，语句只认旧表名，不会误删已建好的 `clinics`。
- 查询侧只有 `H2FacilityGuideTool` 的 SQL 换表名。`hospital.locationGuide` 工具的入参、出参、HTTP 接口和前端类型均未变动，前端无需改动。
- 升级影响：旧表中 id 不在 `loc-d001`~`loc-d006` 范围内的自定义诊室会被删除；若 `appointment_slots.clinic_location_id` 引用了这些 id，升级后该预约查院内指引会报「这条预约还没有配置院内位置指引」。演示种子数据不涉及这种情况。
- 同步更新 `docs/06-mock-data-design.md`、`docs/14-database-table-data.md`、`docs/设计思路报告.md` 与快照导出脚本 `docs/tools/export_h2_snapshot.py`。表名变更的历史记录不改写。
- 容器内验证尚未执行：按用户要求未在宿主机构建。后续可在开发容器内执行 `mvn -f backend/pom.xml test -Dagent.model.enabled=false`，并确认启动日志里 schema 初始化成功、`clinics` 有 6 行。

## 2026-09-16 FAMILY_CONTACTS 用户关联

- `user_id` 改为 `"USER"`；移除 `name/relationship/phone`；新增 `contact`。`"USER"` 与 `contact` 均为非空的 `users.id` 外键，`id` 保留以兼容历史通知引用。
- 旧表升级通过复制 owner 到 `"USER"` 完成列改名，并根据已有照护关系和姓名唯一匹配联系人用户。无法唯一匹配时在删除旧资料前停止启动；需先为对应记录填写有效 contact 后重启，不能猜测联系人身份。
- 旧手机号在无冲突且目标 users.phone 为空时迁移；演示联系人 family-001 关联 user-f001，演示号码仅补空值。
- 联系人查询从 users 读取姓名、号码，从 care_relations 读取称谓，缺失称谓显示“家属”。资料、通知和健康报告查询同步调整；HTTP 响应结构和前端类型保持兼容。
- 新增旧表升级、重复初始化、关联外键测试，并调整健康报告测试数据。已执行静态差异检查；按用户要求未启动 Docker，容器测试未执行，实际数据库尚未迁移。

## 2026-09-16 USERS 手机号码及字段调整

- `schema.sql` 对新旧库统一移除 `home_longitude`、`home_latitude`，移除 `family_member` 及其外键，新增 `phone VARCHAR(32)` 保存手机号码，允许 NULL，不设置唯一约束，不填充虚构号码；不改变原有照护授权关系。
- 初始化数据不再写家庭经纬度；路线起点改为 `travel_routes.polyline` 首点，无坐标时报配置错误。
- HTTP 接口及前端类型、调用方式不变，未对外新增手机号码编辑接口。
- 新增 `UserSchemaTests`，覆盖列变更、手机号码读写与空值、旧外键移除、迁移重复执行及路线起点。
- 容器内验证尚未执行：按用户要求不启动 Docker。后续如需验证，可在开发容器内执行 `mvn -f backend/pom.xml test -Dagent.model.enabled=false`。

任何前后端共享字段、接口路径、枚举或日期格式变化都记录在这里。

## 2026-09-12 取消预约的自然语言确认与批量确认（内部接口）

- 外部 HTTP 路径和 `AgentTurnResponse` 结构不变；现有 `confirmation` 卡片字段继续承载按钮与 `confirmationId`。
- `PlannerActionType` 新增内部动作 `CALL_CONFIRMATION_TOOL`，与只读工具调用分离。外部HTTP接口和前端响应结构不变。
- `ToolRegistry` 新增 `interaction.requestConfirmation` 与 `interaction.respondConfirmation` 两个 `CONFIRMATION_ONLY` 工具；工具清单由17个只读工具扩展为17个只读工具加2个确认交互工具。
- `requestConfirmation` 使用结构化取消选择器（全部、日期范围、单条筛选、不明确），不接受 appointmentId；`respondConfirmation` 只接受确认/拒绝，confirmationId由Java注入。模型修改范围时旧卡立即失效并生成新卡，不能再把 `CANCEL_APPOINTMENT` 自动映射成确认旧卡。
- `AgentRuntime` 对「模型编了一个执行不了的工具」做了受控回退：未注册、当前角色无权限或写工具一律不执行，其 `intent` 能归到既有 Java 工作流就按 intent 走那条流程（`CREATE_FOLLOWUP`→`RESUME_TASK`/`RESTART_TASK`、`CANCEL_APPOINTMENT`→`CANCEL_EXISTING_APPOINTMENT`、备忘/健康数值/周报→对应路由），归不到任何业务链路则新增 `AgentOrchestrator.Route.REFUSE_UNSUPPORTED_TOOL` 由 Java 明确回绝。之所以不复用 `DIRECT_ANSWER`：那条路会 `pauseActiveTask`，把正在办理的预约流程停掉；这里只是“这条工具我不认”，不该动任务状态。任何情况下这个工具都不会出现在 `proposedTools` 执行列表里。
- 模型成功返回确认工具调用时，Java不再解析原句里的中文范围词；原来的日期和范围解析仅作为模型不可用时的兼容降级。
- `AppointmentTool` 新增内部方法 `cancelAll(conversationId, appointmentIds, userId)`。实现先校验整组预约的存在性、状态和用户归属，再在同一事务中取消，避免批量操作只成功一半。
- 前端没有新增共享类型；确认卡根据取消动作显示红色确认按钮、绿色保留按钮，文字标签仍是必要信息，不仅靠颜色区分。
- 兼容性：不破坏旧客户端。旧客户端继续通过 `/api/agent/confirmations` 点击确认；文字和语音确认只是新增入口，最终仍消费同一个 `confirmationId`。

## 2026-09-11 越界提示块与演示场景重置

- `AgentTurnResponse` 新增可选分量 `notice`（第 12 个，`{type, title, message}`），旧的 11 / 9 / 8 参构造原样保留，前端可忽略。当前只有 `type=MEDICAL_BOUNDARY` 一种取值，未知 `type` 前端**不渲染**（不是渲染成空白卡）。`notice` 只影响展示：不切 `stage`、不改 `confirmationId`、不新增待办、不落库；越界那一轮仍把原来的确认卡连同同一个 `confirmationId` 带回，前端按老逻辑照常确认。
- 新增 `POST /api/demo/scenarios/{scenarioId}`（编号 `normal` / `no-slot` / `conflict` / `boundary`）：**破坏性**，清空可变业务数据、放开被占用的号源、按「今天」重排号源与日程，开一段新会话，返回 `DemoScenarioResponse{scenarioId, title, steps[], availableScenarios[], turn}`；未知编号返回 400 `{"message": …}` 并列出可选值。旧会话 id 在重置后不再可用（400「会话不存在或已过期」）。仅追加，正常业务接口未变动。
- 工具追踪里不再出现 `catalog.searchDepartments`：该方法（含 `DepartmentCatalogTool.searchDepartments`、`CareCatalogRepository.searchDepartments`）从未被调用，`ToolRegistry` 里也没有对应工具，已一并删除。`ToolRegistry` 的工具清单不变，仍是 17 个只读工具。`tool_call_logs` 里的历史记录不受影响。
- 前端：`VoiceMicButton` 去掉 `variant` 入参（只剩悬浮这一种形态），`LevelMeter` 的根数/粗细/间距/高度入参收成组件内常量——**纯前端改动，后端与共享字段不变**。接口无变化，旧前端不受影响。

## 2026-09-11 多模态：识图、药品知识、语音输入输出、材料拍照确认

- 新增 `POST /api/agent/images`，请求体 `{conversationId, imageDataUrls[], hint}`，最多取 3 张。响应仍是标准 `AgentTurnResponse`，**未新增任何字段**：图片本体由前端自己持有并渲染，`reply` 装识别结论，`toolTraces` 里多一条 `vision.recognize`。
- 视觉模型未启用时该接口第一步就返回友好提示，不调用任何模型；图片本体落 `conversation_attachments`，识别结论落 `vision_results`（只存文字）。对话历史里图片只留纯文本。
- 新增只读工具 `drug.queryKnowledge`（`drugName`、`specification`），自由语言意图新增 `QUERY_DRUG`，路线新增 `QUERY_DRUG_KNOWLEDGE`。药名、规格、用途、提醒全部来自 `drug-knowledge.json`，查不到如实说没有。
- 新增 `GET /api/vl/status`、`POST /api/asr/transcribe`、`POST /api/tts/synthesize`、`GET /api/tts/voices`、`GET /api/demo/channels?probe=true`。未配置 key 时 `enabled=false`，前端退回浏览器原生识别与浏览器语音合成。
- `PATCH /api/users/{userId}/appointments/{appointmentId}/materials/{materialId}` 的 `photoUrl` 现在允许直接传压缩后的 data URL：后端把图片本体存进 `conversation_attachments`（`kind='MATERIAL_PHOTO'`），`photo_url` 只写短引用 `attachment:<id>`（该列是 `VARCHAR(500)`，直接写 base64 会截断）。`confirmSource` 归一为 `USER`/`PHOTO`，非法状态 / 非图片 / 超限照片返回 400 `{"message": …}`（本次为该控制器新增了 `IllegalArgumentException` → 400 的处理器，此前会变成 500）。
- 表结构：新增 `conversation_attachments`、`vision_results`；`conversation_messages` 增加 `message_type`、`attachment_id`。全部为追加，旧数据与旧接口字段不变。
- 前端共享类型：`ChatMessage` 追加全部可选字段 `imageDataUrls`、`isVoice`、`audioUrl`、`audioDuration`、`voiceState`——后端不下发这些，纯前端态。
- 前端删除了 `lib/speech-service.ts` 与 `features/voice/use-voice-input.ts`，由 `lib/tts-player.ts` + `lib/local-speech.ts` + `features/voice/use-press-to-talk.ts` 取代；`speakText(key, text, options?)` 是按 key 切换，与旧的 `speakText(text, key, options?)` 参数顺序相反。
- 新增 `GET /api/users/{userId}/appointments/{appointmentId}/materials/{materialId}/photo`：取回这项材料拍过的照片，`{"dataUrl": "…"}`；没拍过返回 404（是「还没有照片」，不是出错）。归属校验在工具里：先确认预约属于这位用户，再只认这条材料自己记下的附件引用。**路径里不接受附件编号**，否则就成了「按编号取任意附件」的读取器；`PATCH` 回传 `attachment:<id>` 引用的那条路同样核对归属，否则改个编号就能把别人的照片挂到自己材料上。
- 已知限制：一次图片轮会占用该会话锁，说明书 OCR 可能十几秒到一分钟。

## 2026-09-11 同轮只读工具续跑（内部接口）

- `ConversationPlanner` 新增 `continueAfterTools(originalMessage, context, allowedTools, toolResults)`，用于把只读工具证据返回同一主模型继续决策。
- `/api/agent/messages` 的外部请求与 `AgentTurnResponse` 结构不变，前端无需同步修改。
- 查询轮次可能在一次 HTTP 请求内发生多次模型调用；上限为 3 轮工具续跑，并拦截同名同参重复调用。
- 模型续写失败时返回现有 Java 业务处理产生的权威结果；确认与写操作接口没有变化。

## 2026-09-09 对话任务状态与地图指引

- `AgentTurnResponse` 新增 `task`，包含 `active/status/currentStage/summary/missingField`；前端已同步。
- 新建会话默认 `task.status=NONE`；`CONTINUE` 可创建新任务，`RETURN_TO_FLOW` 恢复暂停任务。
- 新增 `GET /api/users/{userId}/appointments/{appointmentId}/travel-guide`。
- 新增只读工具 `travel.routePlan`、`hospital.locationGuide` 和前端动作 `OPEN_TRAVEL`。
- 新增 `clinic_locations`，`appointment_slots` 增加 `clinic_location_id`；医院和用户增加模拟坐标，路线增加距离、步骤和折线。
- 兼容性：响应仅追加字段；旧前端可忽略。新前端依赖 `task` 展示后台任务状态。

## 2026-09-09 受控规划器状态字段

- `GET /api/agent/model-status` 新增 `planningMode` 与 `architecture`；模型启用时架构值为 `MODEL_ORCHESTRATED_TOOL_AGENT`。
- `GET /api/agent/model-status` 新增 `promptMode=SINGLE_MAIN_AGENT_PROMPT`，用于 Demo 证明规划和工具结果回答复用同一主提示词。
- `understandingMode` 保留为规划模式的兼容别名，现值可为 `MODEL_PLANNER_WITH_RULE_FALLBACK` 或 `RULE_PLANNER_FALLBACK`。
- `AgentTurnResponse`、页面动作和确认接口没有变化，现有前端无需同步修改。
- `/api/agent/messages` 内部改为“模型提出动作 → Java 权限审核 → 只读工具/工作流 → 回答模型”；写工具仍只能由确认接口触发。

## 2026-09-08 模型状态与支持性对话

- `GET /api/agent/model-status` 返回字段由 `mode/model/secretStored` 调整为 `understandingMode/answerMode/provider/model/secretStored`；当前前端未消费该接口。
- 新增快捷动作 `RETURN_TO_FLOW`，用于支持性交流后返回保留的业务节点；不产生业务写入。
- `/api/agent/messages` 在模型启用时可发生理解和回答两次模型调用；`/actions` 与 `/confirmations` 跳过理解节点，但可调用回答节点。
- 模型环境变量统一改为 `AGENT_MODEL_*`；旧 `AGENT_LLM_*` 和厂商专用变量不再读取。
- 兼容性：AgentTurnResponse 结构未变化；模型状态接口字段与环境变量属于破坏性配置变更。

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

## 2026-09-11 照护端助手会话身份与角色限定工具

- `POST /api/agent/conversations` 新增可选参数 `actorId`：不传即本人自办（老人端行为不变），传入则按 `care_relations` 校验操作者与就诊人的绑定关系，未绑定一律 400「没有权限查看这位就诊人的信息」。
- `userId` 语义明确为「本次会话服务的就诊人」；`actorId` 只用于关系校验与话术，不会被注入任何工具参数。
- 工具目录新增两个只读工具，仅家属/志愿者可见：`care.timeline`、`care.notifications`。
- 自由语言意图新增 `REMIND_ELDER`（给长辈留提醒，区别于本人记账的 `MANAGE_MEMO`）。
- 结构化动作新增 `QUERY_CARE_TIMELINE`、`QUERY_CARE_NOTIFICATIONS`；代约确认后返回 `result` 卡（归属与陪同人取自代约记录）。
- 兼容性：未传 `actorId` 的旧请求字段与行为完全不变。
