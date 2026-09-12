# Agent 工作流

> 文档版本：v0.2　更新日期：2026年9月11日

> 当前实际流程及兼容状态字段见 [Design.md](../Design.md)。2026-09-10 已改为单一主模型驱动的工具智能体：模型负责理解、预约草稿补全、追问顺序、异常恢复和工具选择；Java Stage 只用于旧前端进度展示，不再覆盖成功的模型意图。

## 一轮处理

系统不使用无上限的持续循环。用户每发送一次自由语言，就触发一个有界轮次：

1. 根据 conversationId 加载会话状态与最近消息（默认 16 条，可配置）；长期业务事实由 ConversationState 保存。
2. 模型可用时，`ConversationPlanner` 使用 `AgentSystemPrompt` 统一理解普通交流、医疗边界、预约草稿、异常情况和页面目标；模型不可用时才启用规则回退。
3. 主模型输出 `ANSWER`、`ASK_USER`、`CALL_READ_TOOL`、`CALL_READ_TOOLS` 或 `PROPOSE_WORKFLOW_ACTION`，并一次提取本轮提供的全部预约信息。
4. `AgentRuntime` 直接采用成功的模型结论，不再叠加 Java 关键词快速路由，也不再让 `AgentOrchestrator` 按 Stage 覆盖模型意图。
5. 模型选择的真实查询由 `ToolRegistry` 映射到医院、科室、号源、预约、材料、日程、路线等工具；工具结果写入 H2 和 TOOL_CALL_LOGS。
6. 每批只读工具结果会作为结构化证据交回同一个 `ConversationPlanner`。模型可以直接回答，也可以根据结果再选下一项只读工具；相同工具与参数禁止重复，单个用户轮次最多继续 3 轮。
7. 无号、冲突、重复预约、模糊名称等结果仍由现有 Java 异常处理生成合法候选项和 `allowedNextActions`，模型只在这些真实边界内解释和选择下一步。模型续写失败时直接返回权威工具结果，不重新执行查询。
8. `ConversationState` 作为结构化预约草稿保存事实；Stage 只兼容前端进度条。写操作继续使用确认卡和 confirmationId 执行，模型不能把一句文本当成数据库成功。

新会话先处于自由交流模式，不自动进入 `ASK_HOSPITAL` 的用户交互。只有用户明确提出预约复诊目标或点击开始按钮时，Java 才把 `taskStatus` 设为 `ACTIVE`。用户中途聊天时任务转为 `PAUSED`，原 `stage` 继续保存；任务卡负责提示仍有待办，回答不再强制追回当前缺失字段。取消未提交办理后状态为 `CANCELLED`，不影响数据库中已经确认的预约。
9. 保存本轮状态与消息，等待下一次输入。

自由文字和语音转文字走 POST /api/agent/messages：先规划和权限审核，再在业务处理后生成回答。明确按钮和确认按钮跳过规划节点，但可在执行后调用回答节点。模型不能改变 Java 已确定的状态、确认和工具结果。

支持性对话与办理状态分开保存。害怕、疲惫、孤单、担心和普通聊天不会清空办理字段；取消办理后仍可交流，但旧确认和旧执行动作继续失效。用户选择“继续刚才的办理”后返回原业务节点。

## 预约主流程

识别复诊意图 → 补齐信息 → 展示任务计划和材料清单 → 查询号源 → 用户选择 slotId → 收集出行与家属信息 → 自动检查日程冲突并计算出发时间 → 用文字和语音完整复述确认摘要并展示确认卡 → 用户明确确认 → 提交预约 → 创建提醒 → 通知家属 → 事项页显示真实记录。只读的计划检查不会产生预约写入，因此信息收集完成后不再要求用户额外点击一次“开始办理”。

## 异常分支

- 医院或科室表达不完全匹配：规划模型保留用户原话，Java 再查询真实目录。唯一简称候选要向用户复述确认；多个候选只展示真实候选并追问；查不到时明确说明并列出当前支持项，不把不存在的名称写进流程。
- 用户不知道准确科室：只询问转诊单、挂号记录或医生告知的科室名称，也可以提示到导诊台咨询；不能依据症状替用户诊断并自动选科室。
- 无号且接受附近日期：只往后查 3 天（往前会捞出已经过去的时段，当天其它时段也不算「附近日期」），展示日期和时间。
- 无号且不接受换日期：理解“换一天”“换医院”“稍后再查”等自然表达，提供相应恢复路线。
- 日程冲突：说明冲突事项并提供同日其他号源、重新选日期或明确保留；“仍然保留”只进入最终确认，不能直接提交预约。
- 修改医院或日期：清除受影响的号源和出行结果。
- 询问地图或怎么去医院：调用 `travel.routePlan`；询问楼层、诊室或院内怎么走：调用 `hospital.locationGuide`。
- 紧急情况和医疗越界：安全规则拦截，不进入普通预约工具流。

## 中控与支线任务

- 主流程：收集复诊信息、选时段、展示计划、检查冲突、最终确认和执行工具。
- 支线：查询我的预约、取消某条已确认预约、查询医院/科室/材料等。
- 进入预约管理支线前保存 interruptedStage 和待确认动作；支线结束后提供“继续刚才办理”。
- 用户选择继续后恢复原节点；用户直接提出修改医院、科室、日期或时间时，中控转入对应修改路由。
- “我不想预约了”只取消未提交的当前办理；“取消9月18日的预约”查询 appointments 后必须再次确认。

## 关键文件

- ConversationState.java：当前流程字段；并用 `actorUserId` / `actorRole` / `relationLabel` 保存会话身份。
- AgentRole.java：`ELDER` / `FAMILY` / `VOLUNTEER` 身份枚举，`isCaregiver()` 即非 `ELDER`。
- ConversationStore.java：会话状态和对话持久化。
- LlmConversationPlanner.java：通过可替换模型网关提出结构化动作、只读工具和自然回答。
- AgentRuntime.java：协调首轮模型建议、工具结果续跑、权限审核和工作流路由。
- ToolRegistry.java / ToolPolicy.java：只读工具白名单和风险策略。
- CareGuideTool.java / H2CareGuideTool.java：查询复诊办理流程、到院步骤和咨询渠道，不提供医疗判断。
- ActionValidator.java：阻止模型在直接回答中声称未执行的写操作。
- LlmAnswerGenerator.java：为无需继续规划的旧业务响应提供自然表达；模型工具循环由 `LlmConversationPlanner.continueAfterTools` 续跑。
- SafetyGuard.java：模型模式执行主模型给出的医疗分类；关键词检查只用于模型不可用时的回退。
- DialogueService.java：处理支持性交流和普通聊天，不执行有副作用的工具。
- AgentOrchestrator.java：仅保留规则降级与兼容动作映射；模型成功时不参与自然语言二次裁决。
- FollowupAgentService.java：API 协调、现有业务状态机和确认执行；后续继续拆薄。
- MyAppointmentTool.java / H2MyAppointmentTool.java：查询数据库中的个人已确认预约。
- HealthRecordTool.java / MemoTool.java：健康记录与健康备忘的领域工具，写入只来自助手对话。
- CareService.java / CareBookingService.java：协同照护端的只读查询与代约写入（`appointments.arranged_by` 记操作者）。

## 日期后的时段选择细化（2026-09-03）

日期确认后立即调用 appointment.querySlots。有号时先汇总上午/下午数量和最早时间，再询问用户偏好；模型理解自然语言偏好，Java 只能从工具返回列表中推荐和选择。选定具体号源后再补齐陪同、提醒、交通、家属通知等信息，展示任务计划并进入最终确认。

## 动态目录与时段短路（2026-09-06）

医院、科室和推荐日期均从 H2 查询。用户已经在自然语言中说明上午、下午或具体时间时，中控在号源查询后直接推荐匹配或最接近的真实号源，不重复询问时段。候选号源确认只确定 slotId；最终确认卡通过后才执行预约写入、提醒和家属通知。

## 上下文长度与页面显示（2026-09-06）

- 模型理解与回答节点只接收最近 `history-limit` 条消息（默认 **16 条**，约八轮，由 `AGENT_MODEL_HISTORY_LIMIT` 在 4 到 40 之间配置）；长期事实由 ConversationState 结构化保存。
- 在一次网页运行中切换页面，助手保持挂载，聊天不丢失。
- 刷新或重新打开网页会**恢复上次那一段会话**（含当前计划）；若上次那段已结束，则直接开一段新的，不把人停在只读页面上。想从头开始点「新对话」。
- 后端数据库保留历史会话和工具轨迹，便于调试、审计和录屏证明真实调用。

## 会话身份：数据轴与能力轴（2026-09-11）

- 建会话 `POST /api/agent/conversations?userId=<服务对象>&actorId=<操作者，可选>`。`userId` 是数据轴（这次会话服务谁），`actorId` 是能力轴（谁在操作）。
- 不传 `actorId` = 本人自办，行为与改造前完全一致；传了且与 `userId` 不同 = 代他人办理，后端按 `care_relations` 校验，未绑定返回 400「没有权限查看这位就诊人的信息」。
- 身份由 `AgentRole`（`ELDER` / `FAMILY` / `VOLUNTEER`，`isCaregiver()` 即非 `ELDER`）表示，固化进 `ConversationState`，因为确认接口 `POST /api/agent/confirmations` 只带 `conversationId`。
- `actorId` 只用于关系校验与话术，**不注入任何工具参数**：服务对象始终是 `userId`。

## 工具可见性与模型工具循环（2026-09-11）

- `ToolRegistry` 共注册 17 个只读工具：15 个两端通用，另 2 个仅家属/志愿者可见（`care.timeline`、`care.notifications`）。
- 模型可见工具由 `plannerTools(role)` 按角色过滤；过滤不等于安全，`ToolPolicy` 在执行时再按角色与风险等级校验一次。
- 单个用户轮次内，模型可依据只读工具结果继续选下一项工具，最多 `MAX_MODEL_TOOL_ROUNDS = 3` 轮；相同工具与参数禁止重复。
- 写操作（预约/取消/提醒/通知）不进入模型可自动执行的工具表，必须过确认门禁。
- `domain/tool/` 下共 15 个领域工具接口，本次新增 `HealthRecordTool`、`MemoTool`。

## 健康记录与健康备忘（2026-09-11）

- `HealthRecordTool`：老人实测数值（血压/血糖/心率等）的读写，写入只来自助手对话；数值异常时先反问，不直接落库。
- `MemoTool`：备忘支持 `DAILY`/`WEEKLY`/`MONTHLY` 重复规则，传 null 表示只提醒一次；可查、改时间、删。
- 新增路由：`MANAGE_MEMO`、`RECORD_HEALTH_VALUE`、`SEND_HEALTH_REPORT`。
- 接口：`/api/users/{userId}/health-records`、`/api/users/{userId}/memos`，健康汇总下发为 `/api/users/{userId}/health-report`。

## 协同照护端（2026-09-11）

- 家属/志愿者端助手接的是**同一个智能体、同一套工具和确认门禁**，不是另一套。
- 可做三类事：查询长辈的复诊安排/材料/就诊动态；代长辈预约复诊（走确认卡）；给长辈留一条提醒（落进长辈自己的备忘并标明是谁留的）。
- 代约由 `CareBookingService.book(actor, subject, request)` 写入，`appointments.arranged_by` 记为操作者；确认卡列出「服务对象」与「代约归属」。
- 代他人办理时**不问“通知哪位家属”**：操作者本人就是被通知方，代约本身会通知其他照护者。
- 长辈名下已有进行中的预约时会先拦下，并给出「先取消已有预约」的路。
- 新增路由：`QUERY_CARE_TIMELINE`、`QUERY_CARE_NOTIFICATIONS`（对应仅照护端可见的两条工具）、`REMIND_ELDER`（给长辈留提醒，区别于本人记账的 `MANAGE_MEMO`）。

## 会话生命周期与历史记录（2026-09-11）

- 会话状态 `ACTIVE` / `CLOSED` / `EXPIRED` 存在 `conversation_sessions.status` **列**里，不进 `state_json`，旧快照照常反序列化。
- 结束的会话**只读**：能翻看，`/messages`、`/actions`、`/confirmations` 一律被拒；`POST /conversations/{id}/close` 幂等。
- 空闲超时只标记 `EXPIRED`，不丢草稿，也不清理数据；老人过一会儿再说话照常继续，待确认的卡还在。
- 被拒的那句话**不落库**（见 PITFALLS「已结束会话的拒绝被当成一轮对话落了库」）。前端只读态由 `status` 驱动，不匹配后端文案。

## 实时办理过程（2026-09-11）

- `TurnProgress` 按会话记一串带序号的事件，分五种：理解 → 决定查什么 → 模型提出工具调用 → 工具真实返回 → 整理回答。
- `TOOL_PROPOSED` 的 `parameters` 是**模型真实生成的那份**，`TOOL_RESULT` 的 `result` 是工具的真实返回体——这正是评审要看的「参数由智能体生成、工具真的执行了」。
- 只读端点 `GET /api/agent/conversations/{id}/progress?afterSeq=`，前端增量轮询渲染。只在内存、有界（40 条 / 500 会话）、5 分钟无事件即不算进行中。
- 读取时脱敏一次：图片 data URL 省略、手机号 `138****1234`、`sk-` 串一律 `sk-***`、单字段截断 600 字。真正的调用记录仍由 `tool_call_logs` 负责，两者互不替代。

## 长期记忆（2026-09-11）

- 表 `user_memories`，主键 `(user_id, memory_key)`；`MemoryStore` 负责读写，`digest()` 拼成一句话。
- **写入口只有一处**：确认门禁放行、预约真的落进 `appointments` 之后，记下常去的医院、科室与习惯时段（`habit.hospital` / `habit.department` / `habit.period`）。草稿阶段一个字都不记，模型和前端都够不着这条路径。
- 读的时候接在 `knownFacts` 末尾进提示词，措辞是「以前办过的，仅供参考，不要当成这次已经定好的安排」。**记住不等于可以替他办事**，写操作仍要过确认门禁。
- 同一 key 只留最新一版（换医院就覆盖）；无记忆时 `digest()` 返回空串，提示词逐字不变。
- 用户控制：`GET`/`DELETE /api/agent/memories`，「我的」页整段可见、逐条可忘；忘掉是软删除。
- 取消预约的入口在事项页，但它不调取消接口，只把一句话交给助手页发出——**入口可以多，写路径只有一条**。
