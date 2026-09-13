# 技术与产品决定记录

重要决定不要只在群里口头说。每条决定说明背景、选择和影响。

## DEC-001 使用模块化单体

- 状态：已采用。
- 背景：三人均缺少完整项目经验，需要降低部署和集成复杂度。
- 决定：使用一个 Spring Boot 后端，工具按包和接口拆分，不建立多个微服务。
- 原因：能保持模块边界，同时只有一个后端进程。
- 影响：所有后端模块共享同一个 Maven 项目和统一 DTO。

## DEC-002 大模型不控制确认门禁

- 状态：已采用。
- 背景：提交预约、创建提醒和通知家属必须获得明确确认。
- 决定：确认状态和执行权限由 Java 工作流控制。
- 原因：避免模型误解、提示注入或上下文变化导致未确认执行。
- 影响：所有具有副作用的工具调用必须携带有效确认记录。

## DEC-004 模型负责理解与表达但不决定业务事实

- 日期：2026-09-08。
- 状态：已采用。
- 背景：固定回复无法承接害怕、疲惫和普通交流，同时项目后续可能更换模型厂商。
- 决定：通过通用 `ModelGateway` 接入模型；模型理解意图、字段和情绪，并根据 Java 形成的权威回答上下文生成自然回复。
- 原因：兼顾自然交流、模型可替换性、确认安全和工具结果真实性。
- 影响：Java继续决定状态转移、确认有效性和工具调用；回答若越权声称已经执行操作则回退模板。

## DEC-005 取消业务不等于关闭对话

- 日期：2026-09-08。
- 状态：已采用。
- 决定：业务阶段与对话模式分离；`CANCELLED` 禁止旧确认和旧执行动作，但允许支持性交流、普通聊天、事项查询和新建办理。
- 影响：支持性对话不自动改变预约参数，用户返回办理后继续原业务节点。

## DEC-006 开发容器按成员加载私有模型配置

- 日期：2026-09-09。
- 状态：已采用。
- 决定：VS Code 的“Dev Container: 后端服务”任务在启动 Java 前可选加载 `.devcontainer/.env`；仓库只提交 `.devcontainer/.env.example`，真实配置由每位成员本机保存。
- 原因：统一团队启动方式，同时避免密钥进入 Git，也避免在 `devcontainer.json` 中硬编码某个模型厂商或个人配置。
- 影响：没有私有配置的成员继续使用规则/模板回退；修改私有配置后只需重启后端任务，无需重建开发容器。

## DEC-007 采用受控混合智能体运行时

- 日期：2026-09-09。
- 状态：已被 DEC-010 替代。
- 决定：模型通过 `ConversationPlanner` 提出 `ANSWER`、`ASK_USER`、`CALL_READ_TOOL`、`CALL_READ_TOOLS` 或 `PROPOSE_WORKFLOW_ACTION`；Java 的 `AgentRuntime`、工具白名单和权限策略决定是否进入真实执行。
- 权限：办事知识、医院、科室、号源、本人预约和材料查询可由模型提出并经 Java 校验后执行；预约、取消、提醒和通知不注册为模型可执行工具，继续经过确认门禁。
- 原因：减少固定 intent 中控对普通交流和临时查询的束缚，同时保留医疗事务所需的安全、真实性、幂等和审计边界。
- 后续：一次提出最多三个只读工具和连续只读工具循环已落地；独立工作流和确认服务继续按架构文档分阶段提取。

## DEC-010 单一主模型驱动预约草稿和工具选择

- 日期：2026-09-10。
- 状态：已采用。
- 决定：只保留一个主模型和一份 `AgentSystemPrompt`。模型结合最近对话与结构化预约草稿判断聊天、办理、缺失信息、异常恢复和工具调用；工具结果继续由同一个模型解释。
- 路由：模型成功返回时不再运行 Java 关键词快速路由，`AgentOrchestrator` 不再依据 Stage 二次覆盖模型结论；Stage 仅兼容前端进度展示。模型不可用或返回无法解析时才进入规则回退。
- 工具：新增草稿完整性、医院/科室模糊检索、附近号源、重复预约和日程冲突等模型可选只读工具；真实结果仍来自 H2 和工具实现。
- 写操作：预约、取消、提醒和通知仍通过现有确认卡与 confirmationId 执行。这是工具接口的执行前置条件，不参与普通对话意图判断。
- 原因：解决“模型已理解，但 Java 固定节点仍重复提问”的问题，并减少多套提示词和中控规则互相覆盖。

## DEC-011 同一用户轮次内执行有界只读工具循环

- 日期：2026-09-11。
- 状态：已采用。
- 决定：只读工具返回后，把状态、实际工具轨迹、权威结果、预约草稿和允许的下一步动作交回同一个 `ConversationPlanner`；模型可继续选择只读工具或生成最终回答。
- 限制：单轮最多续跑 3 次；同名同参工具只执行一次；模型只能选择白名单只读工具。确认卡、完成态、部分完成态和紧急暂停立即终止循环。
- 异常：无号、冲突、重复预约、模糊医院或科室仍由既有业务处理生成真实候选和恢复动作。模型续写失败时直接返回最近一次权威工具结果，不重新查询。
- 写操作：预约、取消、提醒和通知不进入模型工具循环，仍必须经过确认卡与有效 `confirmationId`。
- 原因：让模型能依据真实中间结果自然处理“无号后查附近日期”“先查流程再查材料”等组合任务，同时保留现有异常覆盖、数据库真实性和确认安全。

## DEC-012 模型用结构化确认工具表达取消范围，Java保留执行门禁

- 日期：2026-09-12。
- 状态：已采用。
- 背景：用 Java 中文关键词同时判断“全部取消”“某日前取消”和“确认旧卡”会互相覆盖；用户修改范围时，旧实现可能把 `CANCEL_APPOINTMENT` 误当成确认并消费上一张卡。
- 决定：主模型通过 `interaction.requestConfirmation` 把自然语言归一为 `ALL`、`DATE_RANGE`、`SINGLE_FILTER` 或 `AMBIGUOUS`，通过 `interaction.respondConfirmation` 表达 `CONFIRM` 或 `DENY`。模型成功调用确认工具后，Java不再解析原句里的中文范围词；规则词仅保留为模型不可用时的兼容降级。
- 门禁：模型不能提供预约编号或确认编号，也不能直接调用取消写操作。Java按当前用户重新查库生成确认卡；用户改口时立即作废旧凭据；最终执行前再次校验 `confirmationId`、有效期、是否已消费、预约归属和当前状态，并以单事务取消整组预约。
- 影响：文字、语音和按钮共用同一个确认状态机；外部 HTTP 接口与前端响应结构不变。后续高风险动作可复用确认工具，但仍需各自的业务校验与执行器。

## DEC-008 复诊办事知识库与低延迟回答路径

- 日期：2026-09-09。
- 状态：已采用。
- 决定：使用 H2 `care_guide_articles` 保存流程、到院步骤、咨询渠道和改期说明，通过只读 `careGuide.search` 查询；不保存诊断、药物和治疗结论。普通聊天直接返回通过安全校验的规划回复，紧急与医疗边界使用 Java 权威回复。
- 原因：减少模型凭记忆编造办事信息，形成可展示的真实工具轨迹，同时避免普通聊天每轮连续调用两次模型。
- 影响：清晰的“流程＋材料”问题走单次规划/规则快速路由和批量只读查询；具体材料仍以医院、科室数据库为准。

## DEC-009 对话优先的可恢复任务与双层地图

- 日期：2026-09-09。
- 状态：已采用。
- 决定：新会话默认自由交流，明确预约目标后才创建复诊任务；对话模式和任务状态分开保存，任务暂停时保留原流程节点。院外路线使用可替换地图呈现和 H2 模拟回退，院内楼层诊室来自号源关联的位置数据。
- 原因：避免当前流程节点吞掉普通问题；同时保证路线、楼层和房间可验证、可追踪，不由模型编造。
- 影响：前端新增持续任务卡和统一地图详情页；后端新增只读路线与院内指引工具，写操作确认规则不变。

## 新决定模板

## DEC-003 使用 Java 作为主后端、React 作为移动端界面

- 日期：2026-09-02。
- 状态：已采用。
- 背景：三名成员都学过 Java，需要同时兼顾手机端演示效果与后续 Agent 接入。
- 最终决定：Java 17 + Spring Boot 3.5.6 后端；React 19 + TypeScript 前端。
- 原因：团队能维护业务层；模型可通过标准 HTTP API 接入，并不要求 Python。
- 影响：后端主要使用 IDEA，前端推荐 VS Code，也可用 IDEA 打开整个根目录。

### DEC-XXX 标题

- 日期：
- 状态：提议 / 采用 / 废弃 / 被替代。
- 背景：
- 可选方案：
- 最终决定：
- 原因：
- 影响：
- 相关 PR/提交：

## 2026-09-03 Agent上下文与真实事项重构

- 采用“LLM理解 + Java编排确认 + H2真实状态”的混合Agent。
- 自由语言调用模型，无歧义按钮跳过模型。
- 关键确认卡和结果卡只使用工具及数据库数据。
- 材料清单来自科室模板，照片识别作为后续独立扩展工具。

## 2026-09-03 时段选择与持久化

- 大模型只理解用户时段偏好和确认意图；可选时间必须由 H2 号源工具返回并由 Java 校验。
- 重复预约采用追加记录，不覆盖已有预约。
- 基础模拟数据启动时只补齐，不重置可变业务数据；录屏重置应做成单独功能。
- 前端曾用 localStorage 保存会话编号，真实消息和流程状态仍以后端 H2 为准；该页面恢复策略已被下方“会话生命周期”决定替代。

## 2026-09-06 业务数据以 H2 为唯一来源

- 欢迎语、确认话术、安全边界和任务步骤保留为代码规则。
- 医院、科室、用户、家属、号源、材料、地址和路线不得散落写死在页面或流程代码中。
- 会话状态保存 hospitalId、departmentId、userId，展示名称由目录数据解析。
- 时间选择采用两次确认：先确认候选号源，最终确认卡通过后才写预约、提醒和通知。

## 2026-09-06 推荐由数据库事实约束

- 医院推荐由医院资料工具和科室资料工具共同提供事实，大模型只识别用户意图。
- 推荐依据使用科室复诊范围、客观特色和适老设施，不使用“最好”等无法验证的结论。
- 用户未说明原复诊科室时先询问，不根据症状推断疾病或科室。
- 查询类问题不推进或清空预约任务流，回答后继续原来的流程节点。

## 2026-09-06 采用有边界的混合中控

- 可替换模型负责逐轮理解自然语言并输出结构化意图与参数，不直接选择数据库结果或执行有副作用的工具。
- AgentOrchestrator负责安全优先级、主任务/支线任务切换和返回策略；FollowupAgentService执行状态校验与业务编排。
- 预约、日程、出行、家属、目录、材料和“我的预约”均保持独立工具接口，真实结果以H2为准。
- 查询、医院资料等支线不得清空主流程；取消预约等支线结束后由用户决定是否回到被打断节点。
- 这仍是面向复诊事项的领域智能体，不扩展成可以任意调用工具的开放型通用助手。

## 2026-09-06 会话生命周期改为页面内短会话

- 同一次网页运行期间，助手组件保持挂载，切换首页、事项和我的页面不丢聊天。
- 浏览器刷新、关闭后重新打开时创建新会话，不自动把历史长对话重新铺满页面。
- 后端 H2 仍保存会话与工具轨迹用于审计；模型每轮只读取最近8条消息和结构化业务状态。

## 2026-09-11 身份拆成能力轴与数据轴

- `role` 是能力轴：决定提示词片段、工具可见性与权限判定；`subject` 是数据轴：决定工具里注入的 userId。
- 操作者身份只用于关系校验和措辞，不注入工具；工具执行时以会话身份为准，模型给出的 `elderUserId` 参数不参与取值，因此无法被提示词绕过。
- 身份随会话持久化：确认请求只带 conversationId，身份必须在 `ConversationState` 里，不能只放在单轮上下文。
- 工具可见性在运行时按角色过滤，`ToolRegistry` 仍是纯能力目录；过滤不等于安全，`ToolPolicy` 在执行时再校验一次。
- 代办的写操作复用本人流程的确认卡与 `pendingAction` 分发，不新开一套并发会话；`CareBookingService` 仍是照护端唯一的预约写入口。

## DEC-012 识图回复里的医学知识暂不设来源护栏

- 日期：2026-09-11。
- 状态：**暂不收紧**，赛前重新评估。
- 背景：`POST /api/agent/images` 把视觉模型的 `description` 当成识别结论放行。`qwen3-vl-plus` 会在里面补充图外知识——实拍二甲双胍恩格列净片的原始 description 写着「它主要是用于治疗2型糖尿病的口服药」，而这句话既不在 OCR 里（药盒上没印），也不在 `drug-knowledge.json` 里，出自模型自己的预训练。
- 现状：同一盒药两条路两种口径——发图会得到适应症，打字问 `drug.queryKnowledge` 会得到「知识库里没有这条」。原因是我们「不得凭记忆」的护栏画在工具那一层：`AgentSystemPrompt` 对药品查询要求「药名、规格、用途、用药提醒必须全部来自工具返回」，对图片只要求「图片上的名称、规格、数字、用法必须照抄」，而「这药治什么」两边都不属于，于是在视觉模型那一步就进来了。
- 决定：暂不改动。识图回复信息更完整、演示观感更好；开口子的是「图外常识」，不是剂量与用法建议，就医嘱的核心边界（不给剂量、不判断该不该吃、不建议换药加量）仍由提示词守住。
- 代价：知识来源在两条路之间不一致。被问到「这些医学知识从哪来」时要如实解释：能查到的一律来自我们标注的 16 条慢病用药知识库，识图这条路上模型可能补充其预训练知识。
- 备选（赛前若要收紧）：① 视觉提示词加约束，只描述图上看得见的内容，适应症一律交给知识库工具；② 把二甲双胍恩格列净片等常用复方药补进 `drug-knowledge.json`；③ 两者都做。评估结论补写在这里。

## DEC-013 长期记忆只记「办成的事」，且只由确认门禁写

- 日期：2026-09-11。
- 背景：老人每次复诊都要重说一遍常去的医院和科室。记住它们能实打实省掉一轮问答，是「少问一句」最直接的来源。
- 决定：记忆的写入路径**只有一条**——确认门禁放行、预约真的落进 `appointments` 之后，由服务端记下常去的医院、科室与习惯时段。模型不能凭空写，前端也不能。
- 理由：草稿阶段记下来的是「想法」不是「习惯」。他这次只是随口问了问骨科，就记成常去骨科，下次助手会拿着一个过期的偏好替他做决定——这比少问一句的收益危险得多。
- 同一 key 只留最新一版（`user_memories` 主键是 `(user_id, memory_key)`，不是自增 id）。换医院就覆盖，不堆历史：两版偏好同时进提示词，模型只会更糊涂。
- 读法：拼成一句话接在 `knownFacts` 末尾，措辞明确「以前办过的，仅供参考，不要当成这次已经定好的安排」。**记住不等于可以替他办事**，写操作仍然要过确认门禁。
- 用户控制：`GET`/`DELETE /api/agent/memories`，「我的」页整段摆出来、逐条可忘。忘掉是软删除（`active=FALSE`）——这是老人自己按的按钮，但「他什么时候让我忘掉的」本身也是个事实。
- 兜底：`digest()` 在无记忆时返回空串，提示词与没有这个功能时逐字相同，既有测试与老会话不因多出一个空段而漂移。

## DEC-014 会话结束是「只读」，不是「删除」

- 日期：2026-09-11。
- 背景：原先刷新即开新会话（见 2026-09-06「会话生命周期改为页面内短会话」），老人回头找不到刚才聊过的内容。现在补上历史记录与「新对话」按钮，随之要回答「结束的会话还能不能动」。
- 决定：会话加一列 `status`（`ACTIVE`/`CLOSED`/`EXPIRED`）。结束的会话**能翻看，不能写**：`/messages`、`/actions`、`/confirmations` 一律被拒。空闲超时只标记 `EXPIRED`，**不丢草稿**，老人过一会儿再说话照常继续，待确认的卡也还在。
- `status` 是一列，不是 `state_json` 里的字段。`ConversationStore.save()` 用的是显式列 `MERGE INTO ... KEY(id)`，加列不碰快照，旧会话照常反序列化。
- 拒绝**不落库**。理由见 PITFALLS：落库的话，一个还开着确认卡的老页面每重试一次就多一条一模一样的「已经结束」。
- 前端只读态由 `status` 驱动，不去字符串匹配后端那句拒绝文案——匹配文案等于把界面绑在措辞上，改一个字就失灵。
- 刷新后尝试恢复上次会话，但**刻意不恢复 `CLOSED` 的**：不让人一进来就掉进一个打不了字的页面。
- 这一条部分修订了 2026-09-06 的「刷新即新会话」：刷新仍不自动铺满长对话，但恢复的是**上次那一段**而不是从零开始。

## DEC-015 医疗越界：Java 画的是「不接什么」，不是「用户想干什么」

- 日期：2026-09-11。
- 背景：评审发现越界词表太窄，未命中就被当普通信息静默忽略。修法有两种：全靠模型判，或把规则口径放宽。追问是「又在用 Java 画界限吗」——这个取舍需要写清楚，否则下次还会被问。
- 决定：**医疗这一根轴上由 Java 兜底，业务轴上仍然只认模型。**`SafetyGuard.evaluateModel(message, facts)` 的顺序是：模型判出医疗语义就照模型的办；模型没判出，再用 `MedicalBoundaryRules` 判一次。模型说「改到周四」不会因为规则里有「头晕」就被推翻，规则也从不参与决定走哪条业务路由。
- 理由一（可行性）：默认配置 `agent.model.enabled=false`（`application.yml`），本地演示跑的是纯规则链路。越界若只交给模型，默认跑法下**一句都拦不住**。
- 理由二（失败模式不对称）：越界漏判不是「少答一句」，是整句话被当成普通信息咽下去，老人以为得到了答复；多判一次的代价只是重复一句「我不能诊断，请咨询医生」，并且回复里带着「继续办理复诊」的路回去。
- 代价：规则会**多拦**。「我血压高，下周三去复查可以吗」这种把办事说成疑问句的会被拦一次（「我血压有点高，想下周三去复查」这种陈述句不会）。演示话术按陈述句说即可。
- 放宽与收窄的两处刻意取舍：① 「住院/手术/化疗/输液/打针」单独出现不算越界，要配疑问语气——「那天我要做手术，帮我把复诊改到下周」是办事；② 「我的血压是100」「我最近的血压是多少」是我们自己的健康记录能力，明确放行，不与问诊混为一谈。
- 词表位置：`agent/MedicalBoundaryRules`，一条规则两处调用（`SafetyGuard` 与 `RuleFactExtractor`）。原先散在三处的同名词表已删除，避免「只剩一份是对的」。

## DEC-016 越界提示块只多占一屏，不动办理

- 日期：2026-09-11。
- 背景：越界回复以普通聊天气泡呈现，顶部步骤标签不变，**用户无法看出这条消息比平时重要**——一句被拒绝的回答看起来和一句正常的回答一模一样，老人会以为助手只是答得不好。
- 决定：给回包加一个可选 `notice` 字段（`{type, title, message}`），前端把已知类型渲染成一块独立的橙色提示卡。提示块**只影响展示**：不切 `stage`、不清 `confirmationId`、不落库、不加按钮。
- 理由：越界与「办理被中断」是两件事。老人问一句「这个药还能吃吗」不等于想放弃正在办的事；如果提示块顺带把流程打断，我们等于用一次安全提示惩罚了一次正常的提问，下次他就不敢在办理中间提问了。
- 连带修掉一个真问题：越界回答原先返回 `confirmation = null`，前端按 `turn.confirmation` 渲染确认卡、按 `turn.confirmation.confirmationId` 发确认——卡片会凭空消失，老人只能靠打字说「确认办理」才办得下去。现在这一轮把原确认卡**原样带回**（同一个 `confirmationId`），并抽 `confirmationCard(state, at)` 做单一来源；取消类确认卡不在其中，保持原行为。
- `notice.message` 不是 `reply` 的复制：`reply` 与 `speechText` 是权威回答，照常进对话记录、照常朗读；提示卡只说「这条为什么不一样、要办的事没被打断」。两处照抄同一段话会让屏幕上的同一句话看起来像渲染了两遍。
- 代价：每加一种提示类型，前端就要多认一个 `type`；后端加类型而前端没跟上时按未知类型**不渲染**（不是渲染成空白卡），所以旧版前端不会因此白屏。

## DEC-017 音量动画全屏只留一条，按钮只变色

- 日期：2026-09-11。
- 背景：按住说话时屏幕上同时跑两条音量动画——圆形按钮里一条、大浮层里一条。评审一眼问「搞两个干啥」。除了重复，两处还各写一套尺寸：浮层那条 9 根 14–72px，容器 80px，自洽；按钮那条用默认的 5 根 8–36px，而 `size-16` 减去 `border-4` 只剩 **56px 内圆**，5 根 `w-2`(8px) + 4 个 `gap-1.5`(6px) 要 **64px**，两边各 4px 正压在白色描边上。作者拿 `scale-90` 去补，缩完仍是 57.6px；真正让它「看着还行」的是按住期间按钮自己的 `active:scale-95` 也在生效（0.9×0.95=0.855），纯属巧合，换个不触发 `:active` 的路径就露馅。
- 决定：**音量动画全屏只保留浮层里那一条**。按钮按住时只变红、取消预备时变琥珀色，图标保持 `size-8` 不变，不再放第二条动画。
- 理由一（看得见）：老人的手指正压在按钮上，按钮里的条子大半被自己的指头挡住，露出来的那截还要和浮层那条抢注意力；浮层那条约 120×72px，位置在视线中心的屏幕上，还和实时字幕、上滑取消提示写在一起。
- 理由二（不会再漂）：两套刻度天然会各走各的——同一个音量，按钮那条是 4.5 倍、浮层那条是 5.1 倍，同一时刻两个动画对不上。组件注释里写着「两处的『声音越大条越高』必须完全一致」，而参数恰恰不一致；把一致性交给注释，不如交给「只有一处」。
- 连带收口：`LevelMeter` 的根数、粗细、间距、高度区间改成组件内常量，不再作为入参（唯一还需要按状态变的是颜色）。尺寸只有一处定义，就不存在「某个调用点传漏一个参数」这种错法。
- 代价：`VoiceMicButton` 的 `variant="inline"` 一并删除——没有任何地方渲染它（`app/page.tsx` 写明助手输入框不再重复放第二个麦克风），且它的默认尺寸同样对不上容器（`h-6` 盒子 + 36px 上限）。组件从此只有悬浮这一种形态；将来真要在别处放第二条音量条，得先想清楚它和浮层那条的关系，而不是再传一套尺寸进去。
- 当日修正（同一天实机发现的浮层错位）：上面那条浮层第一版写成 `fixed` + `w-[calc(100%-40px)]`，而 `app/page.tsx` 给麦克风居中用的祖先 `div` 带 `-translate-x-1/2`（transform）。祖先一旦有 transform，后代 `fixed` 的包含块就从视口变成那个 64×64 的盒子，宽度算成 24px，汉字一个字一行，浮层被拉成一条竖条。改法：居中改用不产生 transform 的 `-ml-8`，宽度改成按视口的 `w-[calc(100vw-40px)]`。**结论：浮层的定位不要建立在「包含块是视口」这个假设上，居中也不要顺手用 transform**——这条约束现在写在 `app/page.tsx` 与浮层自己的注释里。详见 PITFALLS 同日条目。

## DEC-018 分包按业务域切，编排簇留在根包

- 日期：2026-09-12。
- 背景：`application/` 下 30 个类平铺在一个包里。要拆，先得定两件事：按什么切、切到哪一层。
- 决定一：**按业务域切**（`care/ health/ memo/ longterm/ preference/ travel/ demo/`），不按技术分层切（如 `runtime/ policy/ workflow/ tool/ response/`）。
- 理由一：这一层的类本来就一个业务一件事——`CareBookingService`、`HealthRecordStore`、`MemoStore`。按域分，找「代约」直接进 `care/`，不需要先判断它算 workflow 还是 service。技术分层在「30 个类、单一业务场景」的规模下不划算：同一件事的代码会被拆到三四个包里，改一处要跨包跳。`11-agent-architecture...` 第八节那份按技术分层的方案属早期设想，落地的形态以本节为准。
- 决定二：**编排簇 15 个类留在 `application/` 根包，不跟着拆**（`FollowupAgentService`、`AgentOrchestrator`、`AgentRuntime`、`ToolRegistry`、`ToolPolicy`、`ActionValidator`、`SafetyGuard`、`ConversationState`、`ConversationStore`、`ConversationLifecycle`、`AppointmentRecordStore`、`CatalogEntityResolver`、`DialogueService`、`ReplyContextBuilder`、`TurnProgress`）。
- 理由二：`ConversationState` 的 ~35 个字段与四个嵌套枚举、外加 `ToolPolicy`/`AgentRuntime`/`SafetyGuard`/`ActionValidator` 等 11 个类都是**包级私有**，它们与 `ConversationState` 互相引用（一个字段的类型、一个枚举的取值都在里面）。拆开就得把这些字段和类逐个提成 `public`。那样拆出来的不是「更清晰的边界」，而是**更松的封装**——为了让目录好看而把内部状态对外敞开，方向反了。
- 决定三：本次**只搬家、不切类**。
- 理由三：切包的行为变更风险接近零（只改 `package` 与 `import`，diff 里没有别的行），可以独立验收、独立回滚。切类要动状态流转，风险是另一个量级；而且切类的边界到底怎么划，取决于前端需要什么形状的接口，而前端那波大改还没定。先切包、后切类，等于先用几乎零代价把「找文件」的问题解决掉。
- 代价：`FollowupAgentService` 仍是 4883 行、占这一层 9408 行的 52%；根包仍然混着编排、门禁、状态、进度四类东西。**分包没有解决这个问题**，只是把周围的邻居归了位——不要把它当成「结构问题已解决」。真要动它，等前端接口定形后按业务切。
- 代价二：子包之间目前只剩一条真实依赖（`HealthReportService` → `MemoParser` 的演示时区时钟）。那个时钟本来就该独立出来，因为它被塞在 `MemoParser` 里，健康报告才「依赖」备忘。这属于切类范畴，本次不动，只记在这里。
- 后续（2026-09-13）：这条依赖已由 `DEC-019` 消掉，那个演示时区时钟独立成了 `application/time/BusinessClock`。

## DEC-019 业务钟面与审计钟面分开，历史时间戳不动

- 日期：2026-09-13。
- 背景：统一业务时间时发现，同一个进程里的「现在」有两套来源：SQL 的 `CURRENT_DATE`（数据库连接的默认时区）与 Java 的 `LocalDate.now()`（进程默认时区）；而库里还有一批按 JVM 默认时区写下的历史时间戳。一刀切成业务时区会把历史数据也改掉。
- 决定一：**分两个钟面。**业务钟面＝预约日期与时段截止、「今天/明天」、提醒钟面、照护代约、周报的周一八点、号源与日程播种，一律走注入的 `BusinessClock`（时区取 `business.time.zone`，默认 `Asia/Shanghai`）。审计钟面＝各类 `created_at` / `notified_at` 落库，保持 `LocalDateTime.now()`（JVM 默认时区）——**旧行就是按这个口径写的，改了等于把历史数据凭空变老八小时。**两钟面需要比较时经 `BusinessClock.toAuditClock` 换算，目前只有 `HealthReportService.weeklySentThisWeek` 一处。
- 决定二：**跨到 SQL 的判断不再用 `CURRENT_DATE`/`CURRENT_TIME`**，把业务时钟的今天/现在作为绑定参数传进去（三处号源查询 + `CareCatalogRepository.availableDates`）。同一句 SQL 里的「今天」与 Java 里的「今天」从此只有一个来源。
- 决定三：**时区从配置来，不在代码里写死**（`business.time.zone`，环境变量 `BUSINESS_TIME_ZONE`）。配了不存在的时区就在启动时失败，而不是回退到默认值——静默回退会让人以为改动生效了。`BusinessClock` 构造器收一个 `Clock`，测试可以钉死时间：这批判断全是相对「现在」的，不用固定时钟就没法稳定回归（上海凌晨那八小时尤其）。
- 决定四：**不给模型算日期。**提示词每轮给出真实的日期、星期、时刻、时区，相对日期（明天、下周三）由模型自己按照这个基准推算；Java 只做两件事——把日期算实的地方（`RuleFactExtractor`、`askDate` 的候选日期）也用同一个时钟，以及校验合法性。算出来已经过去的日期**不顺延年份**，由 Java 请老人重说（模型侧被要求复述后确认）。替老人把「3月5日」定到明年，就是把一次询问换成一次八个月后的错约。
- 代价一：`MemoParser` 这个 600 行静态解析器里那种「过去年份顺延」的逻辑这次没动，它是规则链路的既有行为，要单独评估。**取「现在」这一处已改**（评审修复）：解析器新增接收业务时间锚点的重载，`FollowupAgentService` 的 9 处生产调用传 `clock.now()`/`clock.today()`；旧的无参重载保留给单元测试，内部按 `BusinessClock.DEFAULT_ZONE` 兜底。
- 代价二：`RollingUserScheduleInitializer` 的**无参**静态日期方法（`checkupDate()` 等）固定跟着 `BusinessClock.DEFAULT_ZONE`，不随 `business.time.zone` 覆盖走——静态方法没有注入点。生产调用方 `DemoScenarioService` 已改走注入的 `BusinessClock.today()`（评审修复），这几个无参方法现在只剩不启动 Spring 的单元测试在用。
- 决定五（评审补充）：**「时段已经过去」的边界与号源查询严格互补**，等于此刻也算过去（`!isAfter(now)` 对 `appointment_time > now`）；且这道门只约束创建/改期，不拦取消——取消针对的是库里已有的预约，草稿里残留的 `selectedSlot` 过期与否与它无关。门禁条件要从「本轮要做什么」（`pendingAction`）推，不能从状态里碰巧还留着的值推。
- 影响：`FollowupAgentService`、`MockAppointmentTool`、`CareCatalogRepository`、`CareBookingService`、`CareService`、`HealthReportService`、两个播种器、`RuleFactExtractor`、`AgentSystemPrompt` 的构造器各加了一个时钟依赖；`AgentContext` 刻意不加字段（4 参 / 5 参构造原样保留），提示词的时刻与 Java 校验的时刻因此同源。

## DEC-020 工具参数声明成强类型，判罚只在一处，判罚结果说清是哪种情形

- 日期：2026-09-13。
- 背景：此前工具的「参数合法性」是散的——`ToolRegistry` 只声明参数**名字**，至于哪个必填、取值范围是什么、哪几个参数要一起给，全写在各处 `dispatch` 分支的 if 里。同一个规则写两遍就会漂，漏写一处就是一个能被模型踩出来的洞；而且模型看到的说明里也没有这些信息，它只能猜。
- 决定一：**参数的「形状」由声明决定，不由解析代码决定。**每个工具的参数声明升级成 `ToolArgument(name, type, required, values, description)`，取值类型有 `string / date / time / enum / boolean` 五种；再加 `ToolConstraint` 描述「哪几个字段要一起给」（`REQUIRES_ALL`）或「这几个里至少给一个」（`AT_LEAST_ONE`）。类型、必填、枚举取值、字段组合，全在 `ToolRegistry` 里一处声明，模型看到的工具说明就是这份声明的直接投影。
- 决定二：**判罚集中在一处。**新增包级私有的 `ToolContract`，`check(tool, call)` 是所有工具调用的唯一判罚入口，五种判罚各有自己的 `Code`：`UNKNOWN_TOOL`、`MISSING_ARGUMENT`、`INVALID_FORMAT`、`INVALID_ENUM`、`CONSTRAINT_VIOLATED`。判罚通过后由 `normalize(...)` 产出规范化的实参：**只保留声明过的字段**（模型多塞的字段一律丢弃，不进任何下游）、丢掉空串（空字符串算「没给」而不是「给了个空值」）、枚举统一转大写。日期与时段先按严格 ISO 解析，不成再退回 `yyyy-M-d` / `H:mm` 这类宽松写法——老人说得到的写法要收下，收下之后统一成一种。
- 决定三：**判罚结果要能分开说。**`ToolOutcome.Kind` 定为七种：`SUCCESS`、`NO_RESULT`、`MISSING_INFO`、`NEEDS_CLARIFICATION`、`NEEDS_CONFIRMATION`、`STATE_CHANGED`、`FAILURE`。`ToolOutcome.Step` 把这个 kind 带出执行栈。加这一层不是为了好看：**「没查到」和「你没说清」要给老人两套完全不同的话，还要做两套不同的事**——前者如实说没有、不摆按钮，后者问一句、把真实候选摆出来。以前这两件事都落进同一个 `null` 里，只能靠调用点各自猜。
- 决定四：**判罚的种类映射到七种情形时要收敛，不能扩散。**`UNKNOWN_TOOL` 归 `FAILURE`（模型说了个不存在的工具，这是错的），其余四种归 `MISSING_INFO`（模型说得不完整或不合法，请它补或请老人补）。这条映射写在 `ToolContract.Rejection.kind()` 里，改一处就全改。
- 决定五：**不动 `AgentRuntime` 的构造器，也不放宽它的封装。**`ToolContract`（`application` 包）在 `agent.planning` 包的 `PlannerToolCall` 与 `AgentRuntime` 之间，`AgentRuntime` 通过两个包级私有的方法 `rejection(...)` / `acceptedArguments(...)` 转交，那个给测试用的 6 参构造器逐字未动。**新能力不靠加参数、加 public 来接入**。
- 代价一：`PlannerTool` 的入参从 `List<String>` 变成 `List<ToolArgument>`，于是只能保留一个收 `List<String>` 的 4 参兼容构造器供旧测试使用（泛型擦除下两个 4 参构造器无法共存）。这个兼容构造器是这阶段唯一一处弱校验入口，已在注释里写明只服务旧测试——详见 PITFALLS 同日条目。
- 代价二：参数一旦声明进 `ToolArgument`，就**进了模型看得到的说明**。`interaction.requestConfirmation` 因此**刻意不声明 `appointmentId`**：它不是模型该填的东西，声明出来等于邀请模型去指定取消哪一条、绕开 Java 的候选匹配。少声明一个字段，就是少一条模型能插手的路。
- 影响：`ToolRegistry` 的每一个工具都补齐了类型/必填/枚举/组合约束并新增 `interaction.askClarification`；`AgentRuntime` 的只读工具循环与确认工具分支都在执行前过 `ToolContract.check`；`AgentSystemPrompt` 新增「工具参数是怎么声明的」与「工具结果的七种情形」两节，把判据交给模型。新增 `ToolContractTests` 17 项覆盖五种判罚、空串、未声明字段、宽松日期时段写法、以及模型看到的说明里确实带 `"type":"enum"` / `"required":true` / `REQUIRES_ALL` 且**不含 `appointmentId`**。

## DEC-021 澄清是第三条通道，不是确认的另一种写法

- 日期：2026-09-13。
- 背景：需要一种「这事我判断不了，先问老人一句，并把真实候选摆出来让他点」的能力。最省事的做法是复用确认卡那套（同一张卡、同一个 `confirmationId`、同一个「确认办理」按钮）——模型问个问题，老人点了就等于确认。这个做法必须否掉。
- 决定一：**风险通道分成三条，各自独立判定。**`ToolPolicy` 现在是 `evaluate`（`READ_ONLY`）、`evaluateConfirmation`（`CONFIRMATION_ONLY`）、`evaluateClarification`（`CLARIFICATION_ONLY`）三个入口；`interaction.askClarification` 的风险等级是 `CLARIFICATION_ONLY`，只有 `evaluateClarification` 认它。**澄清走不到发凭据那条路上**——不是「走了但被拦住」，是压根没有那条分支。
- 决定二：**澄清轮不发 `confirmationId`、不建卡、不进 `AWAITING_CONFIRMATION`。**`ClarificationInteractionTool` 只做一件事：把**数据库里查出来的真实候选**（不是模型编的、不是提示词里带的）摆成至多 4 条 `SELECT_APPOINTMENT_TO_CANCEL` 快捷按钮，配一句不超过 80 字的自然提问。库里没有可取消的已确认预约时，它给的是 `NO_RESULT`（「我没有查到可以取消的已确认预约」）**并且一个按钮都不摆**——摆一张空问题比不摆更让人慌。
- 决定三：**模型可以写问题，但写什么要过结构门。**模型给的 `question` 要满足：≤80 字、不含数字（防止它把候选里的日期时间编进话里说错）、不含「已完成 / 已取消」这类完成态字样、且看起来确实是个问句。**过不了就用 Java 固定的那一句**，并如实记下 `questionFromModel=false`。这不是不信任模型，是这层本来就不该有第二个事实来源——候选是 Java 查的，话也就由 Java 兜底；模型留在环里是因为它的措辞更自然，而不是因为它是权威。
- 决定四：**澄清不能生成执行授权，这一点要在链路上可见。**模型问完一轮之后，老人就算直接说「确认」，凭据也还是空的，那句话只能被判成「确认已失效」。已落一条 `@SpringBootTest`（`aConfirmRightAfterAClarificationCancelsNothing`）钉住这个后果：澄清完再说「确认」，回复含「失效」、`confirmation` 仍为空、库里两条预约一条没动。
- 决定五：**澄清轮的回复不交给润色模型。**走 `respondWithoutModel(...)`，`speechText == reply`。按钮上的文字和口播的话必须逐字一致——老人听的是「您想取消的是哪一条」，看到的按钮就得是那两条真实预约；让一个模型去改写这段，改出来的日期与按钮上的不一致时，他会以为自己点错了。
- 决定六：**这次只接预约取消一条链路。**备忘与健康记录这次不动（要求 8），`candidateTool` 的枚举里目前只有 `appointment.queryMine` 一个取值。要接第二条链路，得先在 `ToolRegistry.registerClarification(...)` 里把 `candidateTool` 的枚举扩上，而不是让模型自由填一个只读工具名——枚举是一个允许名单。
- 代价：前端多一个工具标签（`interaction.askClarification` 与另两条 `interaction.*` 一起在 `tool-trace-describe.ts` 里有中文说明），澄清轮的 `stage` 保持原样（**已知未解决**：这一轮不改 stage，冷会话下可能停在上一件事的 stage 上；前端只在 `turn?.task?.active` 为真时才显示那个阶段标签，目前不影响功能）。
- 影响：新增 `ClarificationInteractionTool`、`ToolOutcome`、`AgentOrchestrator.Route.ASK_CLARIFICATION`（注释写明「不发凭据、不建卡」）；`FollowupAgentService` 的取消支线重写为 `beginCancellationStep` + `clarifyCancellationTargets`，把「库里没得取消」「范围说不清」「说得清」三种情形分别接出去。新增 `ClarificationFlowTests` 6 项。

## DEC-022 确认凭据只有一个出处，执行器不重做归属校验

- 日期：2026-09-13。
- 背景：确认凭据（`confirmationId`）是全部写操作的唯一钥匙，但它此前是**散着维护**的——四处签发各写一遍 `state.confirmationId = UUID...`、十一处出口各写一遍 `= null`、确认入口用三个 `equals` 分别判「是不是备忘 / 是不是代约取消 / 是不是取消预约」。散着写不是风格问题，是**「什么时候该作废」会有好几种口径**，而其中最危险的一种是「凭据作废了、那条待确认的登记还在」——下一轮拿到同一个会话的人以为手上还举着一件待办。执行那一段同样散在 `confirm()` 里：把整批交给那条事务、清理 `pendingAppointment*`、收拾会话手里的 `appointmentId`、决定停在「已取消」还是回到被打断的那一步。
- 决定一：**凭据的签发、校验、消费、废止、出站对账，全部只发生在 `ConfirmationService` 一处。**`confirmationId` 仍然写在 `ConversationState` 上、仍然进快照——不是把它挪到别处，而是**只让一个类碰它**。`issue` 只签发，`retire`（退卡，同时退出等待确认）与 `clear`（只丢凭据、阶段不动）分开：四张卡里有两张会把任务标成等待确认、两张不会，这个差别属于各自的业务语义，留给调用方。
- 决定二：**一份凭据的"授权范围"由三样东西组成，全部写进 `ConversationState` 并随快照持久化：凭据本身、动作类型、完整的目标集合。**三样同生共死——一起签、一起清。检查方式是"三样能不能一起读出来"，任何一处缺失都当这份凭据不存在。这里刻意**不设内存索引**：一开始的写法是在服务里放一张「会话 → 目标集合」的内存表当缓存，那是第二份真值，而且丢一次就会退化成"按会话状态重新推一遍"——推出来的集合和当初给老人看的那张卡是不是同一批，就成了一个需要证明的问题。目标集合进了快照之后，这份索引没有任何东西可补，于是删掉：**同一件事只有一份数据**。
- 决定三：**动作类型在签发时由调用方给死，绝不在确认时按 `pendingAction` 现算，认不出来一律 fail closed。**四处签发各自都知道自己在建哪张卡（取消预约 / 开新预约 / 代约取消 / 备忘），就在调用点写死传进去。不这样做会留下一个真实的错配：卡是取消卡，签发之后中间某一步把 `pendingAction` 写成了别的值，确认时再拿这个可变字段重算类型，**同一把钥匙就会去执行另一件事**——老人点头的是取消，执行的是开单。从快照还原时认不出的类型名同样不认（`Kind.stored` 返回 `null`），**绝不退成 `BOOKING`**：`BOOKING` 恰好是全类里唯一会真去开一条新预约的类型，一个写坏或回滚出来的陌生类型名会静默变成一次开单。
- 决定四：**"重启后批量目标缩成一条"不是可以保留的行为，改成完整恢复；还原不出完整目标集合的旧快照整份作废、请老人重新确认。**（这一条是评审否掉第一版做法之后的结论，见本条目末尾的「评审修订」。）旧快照里只有 `confirmationId`，没有另外两样，还原不出"当初授权了什么"——那就作废它，**绝不按剩下的一部分目标凑合执行**。卡片上写着三条、实际取消一条，比"什么都没发生"严重得多：老人会以为那个号已经退了，而他根本看不出少了哪两条。代价是旧快照里那张卡一律作废（哪怕是开新预约那种本来就没有目标的卡，因为看不出它是"本来没有目标"还是"目标丢了"）——宁可让他再说一遍，也不要按一个我们推出来的范围写库。
- 决定五：**执行器不重做归属校验，也不自己保证批量原子性。**取消族的那条 SQL 自带 `user_id` 与 `status='CONFIRMED'`，并且先整批查一遍再在**同一个事务**里逐条取消（`MockAppointmentTool.cancelAll`，`@Transactional`）。在 `CancellationExecutor` 里再查一遍只会多出第二套**没有事务**的口径；两套不一致时以哪套为准都说不清，而先查后删之间正好就是别人改状态的那个窗口。所以执行器把目标**原样**交给 `cancelAll`（只去重、不筛选——多一套口径就多一个把卡片上没写的那几条也取消掉的机会），它管的是「**什么时候跑**」，不是「这行是不是他的」。代他人办理的取消（`executeCaregiverBooking`）不走这里。
- 决定六：**`respondConfirmation` 的参数校验统一到 `ToolContract`，而且查完只回绝、不回退。**三条通道里只有它没有下游可依赖：它直接翻成「确认」或「拒绝」，再往下就是执行。若照抄澄清那条的「校验不过就按 intent 回退」，会踩到一个具体的坑——intent 是 `CONFIRM_ACTION` 时 `modelRoute` 会把它接成 `CONFIRM_PENDING`，等于**一次参数写错的调用换来一次真的执行**。诚实地说：这一条**没有翻转任何用户可见的路由**（旧的手写 `toUpperCase` + 两个 `if` 判断同样会拒掉缺失和非法取值），它买到的是「判罚只有一个出处」「decision 一定是那两个枚举值之一」「去掉一份重复的比较」，以及**把这个坑写进测试**（`aConfirmationResponseWithAnUnknownDecisionIsRefusedInsteadOfExecuting` 断言 `REFUSE_UNSUPPORTED_TOOL` 且 `isNotEqualTo(CONFIRM_PENDING)`）。
- 决定七：**`AgentRuntime.Outcome.acceptedToolCall()` 改名为 `hasContractCheckedToolCall()`。**理由：它表达的是「这一轮有一个**过了契约校验**的工具调用，可以照它的意思办」，不只是「有东西」。「非空 ⇒ 已校验」是**产品路径**给的性质，不是这个 record 自己保证的——只有 `modelProposal` 会往 `proposedTools` 里放东西，而它放之前必须过 `ToolContract`。名字对不上含义，下一个人就会把它当成一个普通的非空判断用。
- 决定八：**补发只在整份授权逐项相同时才复用原 `confirmationId`；类型或目标集合任何一处不同，一律换一把新的，旧的连同它的类型与范围一起作废。**（评审第二轮的收尾要求。）原先的判据只是"手上有没有一份能读出来的凭据"，那会漏掉最坏的一种组合：**屏幕上是一张新卡，钥匙却是上一件事的**。老人对着「确认办理」点头，执行出来的是上一轮那件取消——而屏幕上没有任何东西提示他这一点。所以复用的条件是 `kind` 相同 **且** 目标集合逐条相同（都取规范形态后比较，见下）。另外：`issue` 与 `ensureIssued` 的 `kind`、`targetIds` **都不接受 `null`**，空目标必须由调用方显式传 `List.of()`。这条不是洁癖——`null` 在这套字段里的含义已经定死是"旧快照缺字段 / 不可信"（决定四），签发侧再把它悄悄转成空列表，就等于用一个只有这里才会做的转换，盖住调用方少写的那件事；而这两者存进快照之后**读起来一模一样**，重启一回就再也说不清当初是"本来就没有目标"还是"目标丢了"。宁可让调用点在编码时就炸。
- 目标集合的**规范形态是"去重、保留顺序"**（`[a, a, b]` 与 `[a, b]` 指同一批要取消的预约）。比较与存取都过这一道，是为了让"同一批目标"只有一种写法：写法上的一点差别就换钥匙，只会让老人屏幕上那个按钮平白失效一次。**刻意不排序**——接口上「先取消哪条」是调用方给的信息，不是可以顺手抹掉的东西。
- 评审修订（2026-09-13）：本条目第一版把「重启之后批量取消卡缩成一条」当成**既有降级**保留下来，还写了一条单测把这个降级钉住。**这个判断是错的，已改。**错在两处：一是"它以前就这样"不等于"它可以留着"——那条降级没有任何产品上的理由，只是目标集合当初没进快照的副作用，把它写成测试等于把副作用升格成规格；二是后果被低估了，卡片上写着两条、实际只取消一条时，老人看到的界面和"取消成功"完全一样，他会以为那个号已经退了。现在：目标集合进快照（决定二）、旧快照还原不出就整份作废（决定四），那条钉降级的单测已删除，换成「完整恢复」与「旧快照安全失效」两条——前者走真实的库（存 `state_json` 再读回来），后者把一张真卡的快照删掉授权范围来构造成旧格式，断言库里两条预约一条都没被取消。
- 代价一：`FollowupAgentService` 的构造器从 36 参变 38 参。已核实没有任何测试直接 `new FollowupAgentService(...)`，只影响 Spring 注入。
- 代价二：**行数没降，几乎没降。**抽走的是一件事的判罚与执行，不是分支的编排；换回来的是调用点上更长的说明。这一步整理的是**边界**，不是体积——不要拿它当「`FollowupAgentService` 已经拆完了」。
- 代价三：**四类待确认动作里只有一类搬了家。**`PendingOperation.Kind` 有 `BOOKING / CANCEL_APPOINTMENTS / CANCEL_MANAGED / MEMO` 四个取值，但只有 `CANCEL_APPOINTMENTS` 真的走 `CancellationExecutor`；`MEMO`、`CANCEL_MANAGED` 和 `BOOKING` 仍在 `confirm()` 里各自回到原来的私有方法（`confirmMemo` / `confirmManagedCancel` / 预约漏斗）。`Kind` 是分派的唯一依据，但**别把这个类读成「它现在管所有写动作」**。按业务逐个抽执行器是后续工作（4B），`15-agent-flexibility-and-elder-ui-plan.md` 第 9 节第 4 项已按此更新。
  - **后续（2026-09-13，4B 已完成）**：上面这条「只有一类搬了家」是 4A 当时的现状，作为历史背景原样保留；**现状以这一条为准**。`BOOKING`、`MEMO`、`CANCEL_MANAGED`，以及本人的预约取消（`CANCEL_APPOINTMENTS`）与照护端的取消（`CANCEL_APPOINTMENTS_CAREGIVER`），现在**全部**由 `ConfirmationDispatcher` 按**签发时冻结的 `PendingOperation.Kind`** 分派到各自的执行器——`BookingExecutor` / `MemoExecutor` / `ManagedCancelExecutor` / `CancellationExecutor`，代他人办理的开单与取消另走 `CaregiverBookingExecutor`，六个取值各有归口，没有任何一个会落到 `BOOKING` 那条路上。**确认入口不再根据 `pendingAction`、`caregiving()` 或任何可变会话字段重新推断执行路径**：走到哪一家完全由签发那一刻写死的类型决定。那句「别把这个类读成它现在管所有写动作」的警示随之换了对象——现在要防的是**新增动作类型时忘了给它归口**，而不是现状缺失。最终落地决定见 **DEC-023**（执行器迁移与 `ConfirmationSupport` 为何是包级私有抽象类）与 **DEC-024**（`Kind` 由四拆六、目标在签发时冻进凭据、签发与执行两侧成对的条数护栏、备忘草稿随凭据一起进快照）。
- 代价四：**`ConversationState` 多了两个字段，快照格式变了。**读方向上对旧快照是兼容的（缺字段即 `null`，而 `null` 正是"这份凭据不可信"的标记，不抛异常）；**写方向上不兼容**：新快照里多两个字段，旧版本的服务读到之后不认识——不过我们的读路径是 Jackson 反序列化到 record，未知字段默认忽略，所以回滚一版也不会炸，只会回到旧行为（读不出授权范围 → 凭据作废 → 请老人重新确认）。代价就是回滚一次会作废手上所有待确认的卡。
- 影响：新增 `application/ConfirmationService`（304 行，含嵌套的 `PendingOperation` / `Decision`）与 `application/CancellationExecutor`（94 行），两者都是包级私有的根包类（`ConversationState` 的字段是包级私有，理由同 DEC-018）。`ConversationState` 新增 `confirmationKind` 与 `confirmationTargetIds`，`ConversationStore.Snapshot` 同步扩两个分量（**只加字段、不加回填**：旧快照读出来就是 `null`）。`FollowupAgentService` 删掉 `pendingCancellationIds`（按状态推取消目标的第二份口径）与 `retireCancellationConfirmationForRevision`（范围修订时作废旧凭据），四处签发与十一处清空改为调 `ConfirmationService`，`finish`/`finishWithoutModel` 两个出口的开头各加一次 `confirmations.reconcile(...)`。对外接口、`AgentTurnResponse` 字段、`confirmationId` 的取值与生命周期一律未变（`docs/05-api-contracts.md` 与 `INTERFACE_CHANGES.md` 无需改动）。新增 `ConfirmationServiceTests` 28 项、`CancellationExecutorTests` 8 项、`ConversationRecoveryTests` 2 项（走真实库的完整恢复与旧快照失效），`CancelScopeTests` 与 `AgentRuntimeRoutingTests` 各补端到端回归；后端全量 438 项，0 失败 0 错误（决定八那一轮按评审要求只重跑确认卡相关的五个类 61 项）。
- 反向复现（第二轮）：把 `ensureIssued` 退回「只要读得出凭据就复用」，`ConfirmationServiceTests` 立刻红 3 项（不同类型不复用、不同目标集合不复用、只有完全相同才复用）。第一轮那两条复现见 PROGRESS 同日条目。

## DEC-023 确认之后的三类执行各自成家，分派只认签发时的 Kind

- 日期：2026-09-13。
- 背景：4A 把**凭据**收进了 `ConfirmationService`，但 `confirm()` 里剩下的三类业务执行（BOOKING / MEMO / CANCEL_MANAGED）还是各自散在原地：备忘卡确认调 `confirmMemo`、代约取消卡确认调 `confirmManagedCancel`、预约卡确认走预约漏斗，而且**这三条分支各自都在读可变的会话字段**判断自己该不该跑。凭据已经被冻结了，执行却还在现算，等于把 4A 修好的那件事在出口处又放开一次。
- 决定一：**`confirm()` 只做统一确认流程，不碰任何业务细节。**它负责：校验当前会话与 `confirmationId`、从 `ConfirmationService` 取出签发时冻结的 `PendingOperation`、处理拒绝、确认时单次消费凭据、按 `PendingOperation.Kind` 分派、汇总执行结果并完成会话出站对账。它**不再根据 `pendingAction`、`appointmentId` 或其他可变会话字段重新推断授权类型和目标范围**；执行依据只能来自签发时持久化的 `PendingOperation`。至此 `pendingAction` 只剩「给界面看」的用途。
- 决定二：**四个 `Kind` 各有执行器，由 `ConfirmationDispatcher` 统一持有。**`BookingExecutor`（开新预约 + 拒绝）、`MemoExecutor`（备忘的确认/拒绝/直写）、`ManagedCancelExecutor`（代约取消 + 通知安排者）、`CaregiverBookingExecutor`（代他人办理的开单与取消），加上 4A 已有的 `CancellationExecutor`。加一层分派器不是为了好看，是两件具体的事：`FollowupAgentService` 的构造器已经 38 参，每抽一个执行器就再加一个参数，加下去会失控；而分派次序本身是有语义的（备忘与代约取消连「拒绝」一起接管，因为它们被拒时也要收自己的草稿；预约那条的过期时段检查必须排在写库之前），这个次序需要有且只有一个地方写。
- 决定三：**不建「大而全执行器」，每个执行器只处理一种业务写操作。**四个执行器的行数分别是 129 / 81 / 76 / 115 行，谁都不足以承担「所有写操作的公共部分」。公共的确认协议（凭据的签发/校验/消费/废止/分派）在 `ConfirmationService` 与 `ConfirmationDispatcher`，业务写操作各回各家——把两者合成一个大类，就是把 4A 刚分开的两件事重新粘起来。
- 决定四：**执行器不复制数据库安全校验。**归属、当前状态、事务原子性继续由现有业务工具负责（`MockAppointmentTool.cancelAll` 是 `@Transactional`，SQL 自带 `user_id` 与 `status='CONFIRMED'`）；执行器组织的是「**什么时候跑**」与会话收尾。这一条是 DEC-022 决定五的直接延伸，此处不再另立第二套查询口径——两套口径不一致时以哪套为准都说不清，而先查后写之间正好就是别人改状态的那个窗口。
- 决定五：**执行器要用到的那二十几个会话收尾动作，走一个包级私有的抽象类 `ConfirmationSupport`。**
  - **为什么是抽象类不是接口**：接口的方法隐式 public，而 `FollowupAgentService` 本身是 public——实现一个接口就等于把这些方法**顺便**变成对外 API。DEC-018 的原则是「新能力不靠加参数、加 public 来接入」，为了让拆出去的类能回调进来而放宽封装，是把结构整理的代价转嫁给封装。抽象类的抽象方法可以声明成包级私有，`FollowupAgentService extends` 之后逐个 `@Override` 成同样的可见性，接口一个字都不出现在对外面上。
  - **为什么按参数传、不在构造器里注入**：如果 `ConfirmationSupport` 是注入进执行器的 bean，依赖链就是 `FollowupAgentService → Dispatcher → Executor → Support`，而 Support 的实现正是 `FollowupAgentService` 自己——Spring 直接报循环依赖。改成 `dispatcher.dispatch(state, operation, approved, this)` 把它当**方法参数**带过去，依赖成了一条直线，「谁在执行这一刻代表会话」也变得显式。
  - 端口方法名刻意与 `FollowupAgentService` 内部的私有助手名字错开（`draftComplete` ↔ `ready`、`arrangedPlan` ↔ `upcomingArranged`、`resumeInterruptedReplies` ↔ `resumeReplies`）：同名同参会在 `@Override` 处与内部那个方法**解析到自己**，变成无限递归。其中 `resumeInterruptedReplies` 就是为了避开这个才改的名。
- 决定六：**照护端那一支按 `Kind` 分派，不再按 `pendingAction` 分派。**核实过两者在这一支上等价：`pendingAction = "CANCEL"` 这个值**全代码库从未被赋值**（死条件），而 `"CANCEL_EXISTING"` 只由 `prepareExistingCancellation` 设置、它紧接着就签发 `Kind.CANCEL_APPOINTMENTS`。所以「按 `Kind` 走」是行为保持，不是新规矩。死条件本身**没有顺手删**（那会改变这条链路读起来的样子），只在类的 Javadoc 里写明它为什么可以忽略。
- 决定七：**分类执行仍然不重做「什么时候该作废凭据」。**凭据在 `ConfirmationService.consume` 里当场作废，走到分派这一步就意味着不会再有第二次；执行到一半失败也是**已经用掉了**——再点一次不能凭空重来一件新事。这条在端到端里钉住（写库失败后凭据失效、库里一条不留）。
- 代价一：**顺带证实了两条既有缺陷，按 4B 要求 9 只记录不修。**①备忘确认卡活不过会话重启——`ConversationStore.Snapshot` 不带 `pendingMemo*` 那六个字段（`git diff` 确认本轮只往快照加了 4A 的两个字段），所以重启后点备忘卡会得到「这条备忘内容已失效」。这是既有行为，4B 没有改变它；发现它是因为第一版写了一条「重启后仍能落备忘」的测试。②代他人办理的 `Kind.CANCEL_APPOINTMENTS` 不读冻结的目标集合，一律走 `CareBookingService.cancelUpcoming`——不越权（照护服务自己会重查归属与状态），但卡片上写的范围与真正取消的那一份原则上可能不一致。两条都写进了 PROGRESS 的「未解决」。
- 代价二：**行数只降了一点。**`FollowupAgentService` 5327 → 5173 行；`application/` 从 37 个类 11244 行变成 **43 个类 11720 行**（根包 27 个类 8871 行，`FollowupAgentService` 占比 47% → 44%）。抽走的是三类执行的编排，换回来的是分派层与端口声明。**这一步降的是耦合，不是体积**——与 4A 一样，别拿它当「已经拆完了」。
- **后续（同日修复轮已改）**：本条决定里的「四个 `Kind`」已按评审拆成**六个**（本人自办与代他人办理各成一类），决定五里的 `arrangedPlan` 端口已删，两条「只记录不修」的缺陷（①备忘卡活不过重启、②照护端取消不读冻结目标）已修。见 **DEC-024**。
- 影响：新增 `application/ConfirmationSupport`（108 行）、`ConfirmationDispatcher`（121 行）、`BookingExecutor`（129 行）、`MemoExecutor`（81 行）、`ManagedCancelExecutor`（76 行）、`CaregiverBookingExecutor`（115 行），全部包级私有、落在根包（理由同 DEC-018）。`FollowupAgentService` 变成 `extends ConfirmationSupport`，删掉 `executeCaregiverBooking` / `confirmManagedCancel` / `confirmMemo` / `arrangerRescheduleMessage` / 自己的私有 `q`（改用继承来的 `static ConfirmationSupport.q`），15 个私有方法放宽到包级私有并加 `@Override`；构造器把 `CancellationExecutor` 换成 `ConfirmationDispatcher`，**仍是 38 参**。对外接口、`AgentTurnResponse` 字段、`confirmationId` 的取值与生命周期一律未变（`docs/05-api-contracts.md` 与 `INTERFACE_CHANGES.md` 无需改动）。新增 `ConfirmationDispatcherTests` 10 项、`ConfirmationDispatchFlowTests` 6 项、`ManagedCancelExecutorTests` 4 项；后端全量 462 项，0 失败 0 错误（专项先跑 160 项全绿）。

## DEC-024 卡上写哪一条，执行就只能动那一条；授权三样之外还要带上执行时用的草稿

- 日期：2026-09-13。
- 背景：4B 首轮评审未通过。否掉的是同一类缝的三处写法——**授权（凭据＋类型＋目标集合）已经冻住了，执行时却各自按可变的会话状态或"再查一遍当前对象"重新决定动谁**。按 DEC-023 的说法分派只认 `Kind`，但 `Kind` 只有四个取值，本人自办与代他人办理合在一起，于是分派里仍然要看 `state.caregiving()`；`CANCEL_MANAGED` 签发时传空目标、执行时现找"当前那份代约安排"；照护端取消干脆忽略冻结的目标集合、调 `cancelUpcoming()`；备忘卡的正文与提醒时间不在授权三样里，也从来没进过快照。四件事的共同后果只有一个：**老人对着卡片上写的 A 点了头，执行的是 B**。另外 `confirm()` 没有 catch，备忘写库的异常会一路穿过 Controller 变成 HTTP 500。
- 决定一：**`Kind` 从四个拆成六个，路由判据里不再出现 `caregiving()`。**`BOOKING` / `BOOKING_CAREGIVER` / `CANCEL_APPOINTMENTS` / `CANCEL_APPOINTMENTS_CAREGIVER` / `CANCEL_MANAGED` / `MEMO`。本人自办与替他人代办是**两类动作**，不是"一类动作加一个执行时再看一眼的开关"——那一眼用的字段（`caregiving()`）恰好是可变的。选拆分而不是"把执行上下文另存一份随凭据持久化"，是因为前者让判据仍然只有一个（看一眼 switch 就能确认没有别的输入），后者要在授权三样之外再维护第四个字段。两条测试把两个方向都钉住：会话说是本人、类型说是代办 → 走代办；会话说是代办、类型说是本人 → 走本人。
- 决定二：**目标条数在签发与执行两侧成对校验，不许静默取第一条。**签发侧 `ConfirmationService.requireTargetCount`：`CANCEL_MANAGED` 与 `CANCEL_APPOINTMENTS_CAREGIVER` 必须**恰好一条**（这两条业务都只处理单条——`cancelUpcoming` 与"当前那张预约"都是单条语义；照护端要从一键代办升级成批量，改的应是业务而不是这道护栏），`CANCEL_APPOINTMENTS` 至少一条，不针对已有对象的类型必须为空。执行侧 `PendingOperation.singleTarget()` 在不为一条时返回 `null`，执行器立刻收场、一个写操作都不做。**只写一侧，另一侧换个入口就能绕过去**，所以两侧同一条规矩。校验排在"复用已有凭据"之前——不然一份不合法的请求会被手上正好内容相同的旧凭据静默满足。
- 决定三：**要取消的对象在签发时冻进凭据，执行时不再重找。**`confirmManagedCancelCard` 把查到的 `plan.appointmentId()` 写进 `targetIds`；`ManagedCancelExecutor` 只认 `operation.singleTarget()`。按 id 的查询**只用于取展示与通知字段**（谁安排的、约在哪天），归属、是否还存在、是否还能取消全部由 `appointmentTool.cancel` 那条 SQL（`id + user_id + status='CONFIRMED'`）判——**不另开第二套口径**。原目标失效（已取消、或已不属于这位就诊人）就什么都不取消，返回"原预约保留"，绝不找一条"当前的"顶替。
- 决定四：**照护端按明确 id 取消，新增事务入口 `CareBookingService.cancelAppointment(caregiverId, elderUserId, appointmentId)`。**它内部照旧校验照护关系（`requireBound`）、预约归属与当前状态，并保留原有的协同通知（含"通知原安排者"）。**`cancelUpcoming` 保留**：`CareBookingController` 那个"取消当前预约"的入口还在用它，那条链路的语义本来就是"取当前那一条"，与确认卡无关。把两者合并会重新制造"卡上写 A、取消 B"。
- 决定五：**授权三样之外，这一类动作执行时还要用的草稿也随凭据一起存、一起回。**`ConversationStore.Snapshot` 增加 `pendingMemoText / pendingMemoAt / pendingMemoRepeat / pendingMemoDay / memoReturnStage / memoNeedsApproval`，与 `confirmationId / confirmationKind / confirmationTargetIds` 同进同出。`ConfirmationService` 里加 `payloadIntact`：读回一份凭据时，这类动作执行时需要的草稿必须也在，缺了就当凭据不可信——**旧快照正好缺这一段，照着它执行只能拿一个 `null` 正文去写库，或者按会话里别的什么凑一段出来，两条路都是"写进去一条他从来没在卡上看到过的备忘"**。失效时凭据当场作废、请他重说一遍。签发侧同样要求草稿已在（少了就是调用点的编程错误，直接炸）。
- 决定六：**备忘写库的异常收在执行器里，走统一 `toolError`。**确认那条路的调用点是 `confirm()`，它没有 catch：异常漏出去就是一个 HTTP 500——老人看到白屏或"服务器开小差了"，而这一轮到底办没办成谁也说不清。收口之后失败账是清楚的：凭据在 `consume` 里**已经消费掉**（走到执行就意味着授权用过了，失败不会把它还回来），草稿已被 `leaveConfirmation` 清干净，所以库里不会多出第二条，页面上也不会再留一张实际已经作废的确认卡。
- 决定七：**修复轮不改用户可见行为。**预约确认仍重新检查号源与时段、提醒/材料/出发建议/家属通知不变、备忘的确认与拒绝不变、本人取消仍是整批原子、`confirmationId` 仍只能消费一次、旧凭据与类型不可信的恢复状态继续 fail closed。改的是"这次动的是谁"的依据来源。
- 踩坑一条值得单独记：**`requireTargetCount` 第一版把 `CANCEL_MANAGED` 判进了"必须为空"那一支**——因为它用 `!cancelsAppointments()` 兜底，而 `CANCEL_MANAGED` 既不属于 `cancelsAppointments()` 也不是"无目标"。表现是每一张代约取消卡都报 `TOOL_ERROR`。发现它**不是靠单元测试**（那些用例给的目标集合恰好是空的，正好落在旧契约上），而是靠端到端把整条链路走通。所以护栏要按"这一类要不要求目标"穷举三分，不能拿另一个布尔谓词的反面兜底。
- 影响：`ConfirmationService` 304 → 417 行（六个 `Kind`、`requireTargetCount`、`payloadIntact`），`ManagedCancelExecutor` 重写（76 → 98 行），`CaregiverBookingExecutor` 137 行（取消改走按 id 的入口），`MemoExecutor` 96 行（写异常收口），`ConfirmationDispatcher` 142 行（按六个 `Kind` 分派），`ConfirmationSupport` 105 行（删掉 `arrangedPlan` 端口），`ConversationStore.Snapshot` 加六个字段（**不做回填**：旧快照读出来是 `null`，正是要它被判成不可信），`CareBookingService` 372 行（新增 `cancelAppointment`），`FollowupAgentService` 5185 行（四个签发点）。对外接口、`AgentTurnResponse` 字段、`confirmationId` 的取值与生命周期一律未变（`docs/05-api-contracts.md` 与 `INTERFACE_CHANGES.md` 无需改动）。测试：新增 `MemoConfirmationRecoveryTests` 2 项（走真实库的快照恢复与旧快照失效）、`MemoToolFailureTests` 2 项（走 HTTP 的失败收场），`ConfirmationDispatchFlowTests` 6 → 11 项、`ManagedCancelExecutorTests` 4 → 7 项、`ConfirmationDispatcherTests` 10 → 11 项、`ConfirmationServiceTests` 28 → 30 项；上述八类再加 `CancelScopeTests` 6 项，专项 79 项全绿，后端全量 **477 项，0 失败 0 错误**。
