# 前后端 API 契约

> 文档版本：v0.2　更新日期：2026年9月11日

服务地址：http://localhost:8080

## 创建会话

POST /api/agent/conversations?userId=user-001&actorId=family-001，无请求体。

这段会话身份拆成两个轴：`userId` 是数据轴（这次会话服务谁），`actorId` 是能力轴（谁在操作）。两者都可省略。

- 不传 `actorId` = 本人自办，行为与改造前完全一致。
- 传了 `actorId` 且与 `userId` 不同 = 代他人办理。后端按 `care_relations` 表校验绑定关系，关系不存在或角色不是家属/志愿者时返回 400「没有权限查看这位就诊人的信息」。
- 两个参数只用于关系校验与话术，**不注入任何工具参数**。身份固化保存进 `ConversationState`，因为确认接口 `POST /api/agent/confirmations` 只带 `conversationId`。

`userId` 省略时读取 DEMO_USER_ID，默认 user-001。

新会话的 `task.status` 为 `NONE`，助手不立即创建预约草稿。用户点击“开始复诊办理”或明确表达预约目标后，状态变为 `ACTIVE`。普通交流可使任务变为 `PAUSED`，但 `currentStage` 和已收集字段保留。

## 自由语言消息

POST /api/agent/messages
请求：{"conversationId":"会话ID","message":"9月20日不行了，换到21日"}
自由文字和语音转文字使用此接口。启用模型时，后端规划器提出直接回答、补问、只读工具或工作流动作；Java 运行时完成工具白名单、参数、状态和副作用审核。只读工具校验后执行，写动作只进入原确认流程；回答节点根据真实结果生成回复。未启用或调用失败时自动使用规则规划器和模板。

## 明确按钮操作

POST /api/agent/actions
请求：{"conversationId":"会话ID","action":"SELECT_SLOT","value":"r-d001-20260916-0900","label":"9月16日 09:00"}
按钮使用此接口，不调用模型理解节点；完成 Java 状态处理或工具执行后，可以调用模型回答节点生成用户可读回复。
label 是可选的用户可读文字；value 可以继续使用 hospitalId、departmentId 或 slotId。号源 id 由后端按 `r-科室-日期-时刻` 生成（如 `r-d001-20260916-0900`），日期跟着今天滚，前端一律回传后端给的原值。

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
- 事项页的「取消这次复诊」按钮**不调用任何取消接口**，只是把「我想取消这次复诊预约」这句话交给助手页发出（`page.tsx` 的 `pendingAsk`），接下来仍旧走上面这条查询 → 确认卡 → 确认端点的老路。入口可以多，写路径只有一条。
- RESUME_INTERRUPTED：恢复进入查询/取消支线之前的流程节点。
- CHANGE_DEPARTMENT、CHANGE_TIME：清理受影响的下游选择，再进入对应节点。
- 自由语言 CONFIRM_ACTION、DENY_ACTION 只有在 AWAITING_CONFIRMATION 状态下有效。
- “取消当前办理”与“取消已确认预约”是不同路由；后者必须调用个人预约查询工具并经过确认端点。

## 角色、工具白名单与路由（2026-09-11）

- `AgentRole` 枚举取值为 `ELDER` / `FAMILY` / `VOLUNTEER`；`isCaregiver()` 即“非 ELDER”。
- `ToolRegistry` 共注册 **17 个只读工具**：15 个两端通用，另 2 个仅家属/志愿者可见（`care.timeline`、`care.notifications`）。模型可见的工具由 `plannerTools(role)` 按角色过滤，但**过滤不等于安全**：`ToolPolicy` 在执行时再校验一次角色与风险等级。
- 写操作（预约/取消/提醒/通知）不进入模型可自动执行的工具表，必须过确认门禁。
- `domain/tool/` 下共 **15 个领域工具接口**：v0.2 新增 `HealthRecordTool`、`MemoTool`，多模态移植新增 `DrugKnowledgeTool`。
- `AgentOrchestrator.Route` 共 **39 个值**。新增路由：`MANAGE_MEMO`、`RECORD_HEALTH_VALUE`、`SEND_HEALTH_REPORT`、`QUERY_CARE_TIMELINE`、`QUERY_CARE_NOTIFICATIONS`、`REMIND_ELDER`、`QUERY_DRUG_KNOWLEDGE`。其中 `REMIND_ELDER`（给长辈留提醒）与 `MANAGE_MEMO`（本人记账）是不同意图。

## 健康记录与健康备忘（2026-09-11）

健康记录是老人上报的实测数值（血压/血糖/心率等），写入只来自助手对话，页面侧只读：

- `GET /api/users/{userId}/health-records?limit=&offset=`：按测量时间倒序取记录，`limit` 缺省 3 条。
- `GET /api/users/{userId}/health-records/count`：总条数，供首页按钮显示“共N条”。
- 数值异常时助手先反问，而不是直接落库。

健康备忘是老人要做的事，支持重复规则（`DAILY`/`WEEKLY`/`MONTHLY`，`null` 表示只提醒一次）：

- `GET /api/users/{userId}/memos?kind=&limit=&offset=`：进行中的备忘列表，`kind` 取 `standing`（长期）/`timed`（到点提醒），缺省全都要。
- `GET /api/users/{userId}/memos/count`：两类各有几条进行中的备忘。
- `PUT /api/users/{userId}/memos/{memoId}`：改内容、改提醒时间或重复规则；`remindAt` 传 null 表示转成长期备忘。
- `POST /api/users/{userId}/memos/{memoId}/done`：标记完成。
- `DELETE /api/users/{userId}/memos/{memoId}`：删除。

健康记录汇总下发（页面入口与助手里“把这个月的血压发给女儿”、每周自动小结是同一条路）：

- `GET /api/users/{userId}/family-contact`：主联系人，供页面显示“发给女儿 小丽”；未配家属时 `contact` 为 null，电话为脱敏掩码。
- `POST /api/users/{userId}/health-report?range=week|month&item=`：把健康记录汇总后发给家属，`range` 缺省 `week`，`item` 可选只发某个项目。

## 协同照护端（2026-09-11）

家属/志愿者端助手接的是**同一个智能体、同一套工具和确认门禁**（同样走 `/api/agent/*`），不是另一套。只读接口：

- `GET /api/caregivers/{cid}/elders`：当前照护者协同的就诊人列表。
- `GET /api/caregivers/{cid}/elders/{uid}/timeline`：某位长辈的复诊动态时间线。
- `GET /api/caregivers/{cid}/notifications`：发给当前照护者的协同通知。

代长辈预约复诊由 `POST /api/caregivers/{cid}/elders/{uid}/book` 写入（另有 `/hospitals`、`/departments`、`/windows` 查询和 `/modify`、`/accompany`、`/cancel` 辅助子路径）。写入模型为 `CareBookingService.book(actor, subject, request)`，`appointments.arranged_by` 记为操作者。要点：

- 代他人办理时**不问“通知哪位家属”**：操作者本人就是被通知方，代约本身会通知其他照护者。
- 长辈名下已有进行中的预约时先拦下，并给出“先取消已有预约”的路。
- 确认卡列出“服务对象”与“代约归属”两项。

## 多模态：识图、药品知识、语音输入输出（2026-09-11）

- `POST /api/agent/images`：请求体 `{"conversationId": "…", "imageDataUrls": ["data:image/jpeg;base64,…"], "hint": "这是什么药"}`，最多取前 3 张，`hint` 可为空。
  响应仍是标准 `AgentTurnResponse`，**没有新增字段**——图片本体由前端自己持有并渲染，`reply` 装识别结论，`toolTraces` 里能看到 `vision.recognize`。
  视觉模型未启用时第一步就返回「图片识别功能暂时没有开启」，不调用任何模型，所以没有 key 也能演示其余功能。
  图片本体存 `conversation_attachments`，识别结论存 `vision_results`（只存文字）；图片消息在对话历史里只留纯文本，避免 base64 每轮灌进提示词。
- 只读工具 `drug.queryKnowledge`（参数 `drugName`、`specification`，意图 `QUERY_DRUG`）：药名、规格、类别、用途、用药提醒全部来自 `drug-knowledge.json`；查不到就如实说没有并建议问药师，不给剂量建议、不判断该不该吃、不建议换药加量。
- `GET /api/vl/status`、`POST /api/asr/transcribe`、`POST /api/tts/synthesize`、`GET /api/tts/voices`、`GET /api/demo/channels?probe=true`：三个模态各自的可用性查询与调用入口。未配置 key 时 `enabled=false`，前端自动退回浏览器原生能力（浏览器语音识别 / 浏览器语音合成）——**识图是唯一没有本地替代的能力**。
- `PATCH /api/users/{userId}/appointments/{appointmentId}/materials/{materialId}` 的 `photoUrl` 允许直接传压缩后的 data URL：后端把图片本体存进 `conversation_attachments`（`kind='MATERIAL_PHOTO'`），`photo_url` 只写短引用 `attachment:<id>`——该列是 `VARCHAR(500)`，几十万字符的 base64 直接写会被截断。`confirmSource` 归一为 `USER`/`PHOTO`；非法状态、非图片、超限照片一律 400 `{"message": …}`。
- `GET /api/users/{userId}/appointments/{appointmentId}/materials/{materialId}/photo`：取回这项材料拍过的照片，返回 `{"dataUrl": "data:image/jpeg;base64,…"}`；没拍过（或引用已失效）返回 404，这是「还没有照片」而不是出错。
  归属校验在工具里完成：先确认这条预约属于这位用户，再只认这条材料自己记下的附件引用。**路径里不接受附件编号**，否则这个接口就成了「按编号取任意附件」的读取器；同理，`PATCH` 回传的 `attachment:<id>` 引用也要核对是不是本预约自己的照片，否则改个编号就能把别人的照片挂到自己材料上。
- 已知限制：**一次图片轮会占用该会话锁**，说明书 OCR 可能十几秒到一分钟，期间同一会话的其他请求要排队。
- 已知限制（见 DEC-012）：识图回复里的药品「用途/适应症」可能来自**视觉模型自己的预训练知识**，而不是 `drug-knowledge.json`——视觉模型在 `description` 里就会补一句「它主要用于治疗××」，系统把这段识别结果当既有事实放行。同一盒药因此可能两条路两种口径：发图得到适应症，打字问却查不到。这是暂不收紧的取舍，药品查询工具那条路的「必须来自工具返回」不覆盖视觉模型。
- 前端侧（后端不下发，纯前端态）：`ChatMessage` 追加全部可选字段 `imageDataUrls`、`isVoice`、`audioUrl`、`audioDuration`、`voiceState`；按住说话支持上滑取消、音量条与语音气泡回放原话；材料清单每行新增「拍照」与「相册」两个确认入口。

## 会话生命周期、历史记录与「新对话」（2026-09-11）

会话多了一列状态：`ACTIVE` / `CLOSED` / `EXPIRED`。它是一列，不是 `state_json` 里的字段，所以旧会话快照照常反序列化。

- `GET /api/agent/conversations/{conversationId}`：恢复会话时多返回 `status`。前端靠它决定这段历史是「还能接着聊」还是「只能翻看」——已结束的会话不该把输入框摆在那儿，让人打完字才被拒绝。
- `GET /api/agent/conversations?userId=&limit=`：最近聊过的会话，新的在前，每行只有 `conversationId`、`title`、`status`、`stage`、`messageCount`、`updatedAt`。**不含会话内容**：一次拉一整页会话不该把每条 `state_json` 都读出来反序列化，想看某一条再走上面那个按 id 的接口。
- `POST /api/agent/conversations/{conversationId}/close`：结束一段对话，204，**幂等**。前端「新对话」按钮先关旧的再建新的。
- 已结束的会话：能翻看，但 `/messages`、`/actions`、`/confirmations` 一律被拒。**拒绝不落库**——那只是一句拒绝，不是一轮对话；落库的话，一个还开着确认卡的老页面每重试一次就多一条一模一样的「已经结束」，真正聊过的内容反倒被复读淹掉。`reply` 与 `speechText` 照常返回，界面上照常看得见。
- 空闲超时只把状态标记为 `EXPIRED`，**不丢草稿**：老人过一会儿再说话，会话照常继续，待确认的卡也还在。`ConversationLifecycle` 只做标记，不做清理。
- 前端刷新后用 `localStorage` 里的上一次会话 id 尝试恢复；刻意不恢复 `CLOSED` 的会话，不让人一进来就掉进一个打不了字的页面。

## 一轮办理的实时进度（2026-09-11）

`GET /api/agent/conversations/{conversationId}/progress?afterSeq=0`

给「查看办理过程」卡片用：一轮进行中按序号增量拉取，把智能体真实生成参数、真的调工具、真的拿到结果的过程画出来。只靠回合结束后返回的 `toolTraces` 看不出过程，那已经是结论了。

- 返回 `{"active": true|false, "events": [...]}`，`active` 为 false 表示当前没有正在进行的轮次。事件字段：`seq`、`kind`、`text`、`tool`、`parameters`、`result`、`success`、`at`。
- `kind` 取 `UNDERSTANDING`（正在理解）/ `PLANNING`（正在决定查什么）/ `TOOL_PROPOSED`（模型提出了工具调用，`parameters` 是模型真实生成的那份）/ `TOOL_RESULT`（工具真的返回了，`result` 是真实返回体）/ `ANSWERING`（整理成回答）。
- **只在内存里**，进程重启即消失——进度是「此刻正在发生什么」，落库反而会把工具参数多留一份；真正的调用记录仍由 `tool_call_logs` 负责，两者互不替代。
- **有界**：每会话最多 40 条事件、最多同时跟踪 500 个会话；5 分钟没有新事件就不再认为「进行中」，兜住没走到收尾的异常路径，免得前端一直转圈。
- **脱敏在读取时做一次**：图片 data URL 换成「（图片内容已省略）」，手机号按项目约定变 `138****1234`，`sk-` 开头的串一律 `sk-***`，单字段截断 600 字。内存里保留原始值供排查，HTTP 出去的一律是脱敏后的。
- 这个接口是**只读**的，不参与任何写路径；「一次图片轮会占用该会话锁」的限制同样适用于它——图片轮的进度会一直停在最后一条直到识别返回。

## 长期记忆（2026-09-11）

助手跨对话记住的、关于这位老人的事。表 `user_memories` 的主键是 `(user_id, memory_key)`，不是自增 id。

- `GET /api/agent/memories?userId=`：返回 `[{key, kind, content, source, updatedAt}]`，最近确认的在前。`userId` 省略时用默认就诊人。
- `DELETE /api/agent/memories?key=&userId=`：忘掉一条，204，**幂等**（再删一次也是 204）。做成软删除 `active=FALSE` 而不是 `DELETE`——这是老人自己按的按钮，但「他什么时候让我忘掉的」本身也是个事实，硬删就查不到了。
- **只记「实际办成的事」**：写入口只有一处——确认门禁放行、预约真的落进 `appointments` 之后，记下常去的医院、科室和习惯的时段（key 分别是 `habit.hospital`、`habit.department`、`habit.period`）。草稿阶段一个字都不记：那是想法，不是习惯，记下来下次就会拿着一个过期的偏好去替他做决定。模型和前端都够不着这个写入路径。
- **同一个 key 只留最新一版**：换了医院就覆盖，不堆历史；两版偏好同时进提示词，模型只会更糊涂。覆盖时保留第一次记住的时间，因为「什么时候开始知道的」和「最近一次确认」是两件事。
- **怎么用**：读的时候拼成一句话（`MemoryStore.digest`）接在 `knownFacts` 末尾进提示词，措辞是「这位老人以前办过的复诊情况（仅供参考，不要当成这次已经定好的安排，拿它少问一句就好）」。记住不等于可以替他办事，写操作仍然要过确认门禁。
- 没有记忆时 `digest` 返回空串，提示词与没有这个功能时**逐字相同**——老会话和既有测试都不会因为多出一个空段而漂移。
- 前端露出在「我的」页的「助手记住的事」：整段摆出来、逐条可忘（点一下变成「确定忘掉 / 再想想」两问，忘掉不可逆，老人手抖一下不该就没了）。页面底部写明「助手只是拿它们少问您一句。真要办什么，还是您点过『确认』才算数」。

## 演示场景重置（2026-09-11）

`POST /api/demo/scenarios/{scenarioId}`，编号 `normal` / `no-slot` / `conflict` / `boundary`。

- **破坏性接口**：清掉上一场演示留下的可变数据（预约、提醒、家属通知、工具记录、会话与附件、识图结论、健康记录、备忘、长期记忆、照护通知），放开被占用的号源（`available=TRUE`），按「今天」重排号源与日程，然后开一段全新的会话。医院、科室、材料模板、出行路线、家属联系人这些目录数据**一条不动**。
- 返回 `{scenarioId, title, steps, availableScenarios, turn}`：`steps` 是照着念就能复现该场景的步骤，`turn` 是新建会话的开场轮，可以直接拿 `conversationId` 开始说话。步骤里的日期每次现算，不写死。
- `availableScenarios` 是全部四个编号，前端/脚本不用自己维护清单。
- 未知编号 → **400** `{"message": "未知演示场景：…，可选值：[normal, no-slot, conflict, boundary]"}`。
- 内存里那份会话状态一起清空（`FollowupAgentService.forgetAllSessions()`）：`requireSession` 命中内存就不再回查数据库，不清的话旧会话 id 还能继续说话，而它对应的库记录已经没了。重置后旧 id 一律回「会话不存在或已过期，请重新开始」。
- 它**不在正常业务流程里**，只用于录屏与评审查验，演示开场前调一次即可；不提供前端按钮。

## 越界提示块 notice（2026-09-11）

`AgentTurnResponse` 多一个可空字段 `notice`，没有提示时为 `null`：

```json
{"type": "MEDICAL_BOUNDARY", "title": "超出我的服务范围", "message": "这类问题我不能回答，所以用这张提示卡单独说明。…"}
```

- **只影响展示**：不切 `stage`、不清 `confirmationId`、不新建待办、不落任何库。前端把已知 `type` 渲染成一块独立的橙色提示卡（老人端与照护端一致），未知 `type` 一律不渲染，不跳空白页也不报错。
- `message` **不是 `reply` 的复制**：`reply`（及 `speechText`）是权威回答，照常进 `conversation_messages`、照常朗读；`notice.message` 只回答「这条为什么长得不一样、要办的事没被打断」。
- 停在确认卡上时，这一轮**原样带回同一张 `ConfirmationCard`**（`confirmationId` 不变，逐条内容与上一版一致）——老人问完一句药，正要按的「确认办理」不能跟着消失。取消类确认卡（`CANCEL_EXISTING` / `CANCEL_MANAGED`）不在这条路上。
- 兼容：字段是 record 的第 12 个分量，旧的 11 / 9 / 8 参构造全部保留，缺席即 `null`；旧会话 `last_response_json` 缺这个字段，反序列化照常。
