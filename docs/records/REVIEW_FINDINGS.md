# 四场景审阅问题清单

审阅日期：2026-09-11。范围：赛题要求的四个演示场景（正常办理、预约失败、时间冲突、服务越界）在当前源码中的实际处理路径。

**验证口径**：本次为静态代码阅读，未启动后端、未连接 Dev Container、未执行 `mvn test` 或前端构建。所有"已实现/未实现"结论均指代码路径存在与否，不代表已通过端到端验收。与 `09-demo-acceptance-checklist.md` 的勾选项不互相替代。

问题按严重度排列。`状态` 列留空表示待处理；修复后请填写提交号并同步该行结论。

| ID | 严重度 | 场景 | 一句话问题 | 状态 |
|---|---|---|---|---|
| RV-01 | P0 | 场景三 | 自然语言路径无法触发冲突检查，必须点按钮 | 待处理 |
| RV-02 | P0 | 场景三 | 冲突场景的种子数据与测试日期会过期，9/18 后无法复现 | 待处理 |
| RV-03 | P0 | 场景四 | 越界词表覆盖不足，未命中时被当作普通信息静默忽略 | 待处理 |
| RV-04 | P1 | 场景一 | `acceptAlternative` 问在选号之后，正常流程多一轮无意义询问 | 待处理 |
| RV-05 | P1 | 场景三 | 确认卡不体现"已检测到冲突"，用户保留冲突时无二次提示 | 待处理 |
| RV-06 | P1 | 场景三 | 「仍保留这个时间」被前端分页隐藏到第 2 页 | 待处理 |
| RV-07 | P1 | 场景四 | 越界不产生状态、无专属视觉；`BoundaryAlert` 组件未实现 | 待处理 |
| RV-08 | P1 | 全部 | 没有 `scenarioId` 或场景重置入口，四场景无法按需稳定复现 | 待处理 |
| RV-09 | P2 | — | `respondWithCancelCard()` 为从未调用的死代码 | 待处理 |
| RV-10 | P2 | — | `sideTask` / `returnPolicy` 只写不读，却参与持久化快照 | 待处理 |
| RV-11 | P2 | 场景三 | 冲突检查窗口硬编码 60 分钟，未用科室实际时长 | 待处理 |
| RV-12 | P2 | 场景二 | `queryAlternatives` 窗口含当天，"附近日期"混入"同日其它时段" | 待处理 |

---

## RV-01 自然语言路径无法触发冲突检查

- **现象**：用户全程用语音/文字说"开始办理吧""继续""下一步"，助手会一直重复"号源已选择，材料已整理。请检查当前计划，下一步检查日程和出行。"，冲突检查永不执行。
- **触发步骤**：走完医院、科室、日期、选号、全部偏好，进入 `READY_TO_PLAN` 后，改用文字输入"开始办理"。
- **根因**：`checkSchedule` 只有两个调用点——`act("START_PLAN")`（`FollowupAgentService.java:265`）和 `confirm()` 内的兜底（`:361`）。而 `chat` 的 `CONTINUE` 走 `advance()`，`advance()` 在字段齐全时无条件返回 `READY_TO_PLAN` 并再次要求点「开始办理」（`:743-748`）。两个提取器的 intent 枚举里都没有对应值：`RuleFactExtractor.detectIntent`（`:46-69`）无匹配词，"开始办理"落到 `PROVIDE_INFORMATION`；`DeepSeekFactExtractor` 的 prompt 枚举（`:98-102`）也没有 `START_PLAN` / `START_EXECUTION`。
- **影响**：对主打语音输入的适老化产品是硬伤；场景三在语音路径下不可达。
- **建议**：二选一或都做——① `advance()` 在 `stage == READY_TO_PLAN` 且 `ready(state)` 时直接转 `checkSchedule(state)`；② 在两个提取器中加入 `START_EXECUTION` 意图，并在 `AgentOrchestrator` 路由到 `START_PLAN` 等价分支。
- **相关代码**：`FollowupAgentService.java:174-209, 265, 690-749`、`AgentOrchestrator.java:46-62`、`RuleFactExtractor.java:46-69`、`DeepSeekFactExtractor.java:98-102`

## RV-02 冲突场景种子数据与测试日期会过期

- **现象**：场景三依赖 `data.sql` 中写死的 `2026-09-18 10:00-11:00 社区体检`，该日期过后无法再复现冲突；同一批硬编码日期也让大量测试用例失效。
- **触发步骤**：2026-09-18 之后启动后端，再尝试演示冲突场景。
- **根因**：`RollingAppointmentSlotInitializer`（`:28-60`）只滚动生成**号源**，不滚动 `user_schedules`；而 `MockAppointmentTool.queryAvailableSlots`（`:35`）带 `appointment_date > CURRENT_DATE OR appointment_time > CURRENT_TIME` 过滤，过期号源查不出来，也就永远撞不上那条静态日程。
- **影响**：场景三的演示寿命只到 2026-09-18。同类问题见测试中 15 处硬编码 `2026-09-18` / `slot-0918-*`（`SilverAgentApplicationTests.java:40, 123, 143-150, 188-195`）以及 `:30` 的 `SET available=FALSE WHERE appointment_date='2026-09-19'`。
- **建议**：把 `user_schedules` 一并做成滚动种子（例如"下周三 10:00-11:00 社区体检"），与号源档位 10:30 保持可撞；测试改用相对日期。`SilverAgentApplicationTests.java:244-255` 已经有 `nextWeekday()` / `nextWeekendWithoutSeed()` 两个现成辅助方法，**但从未被任何用例调用**，看意图是准备改造未完成——直接接线即可。
- **相关代码**：`data.sql:58-60`、`RollingAppointmentSlotInitializer.java:28-60`、`MockAppointmentTool.java:28-40`

## RV-03 越界词表覆盖不足，失败模式是静默忽略

- **现象**：以下提问不会被识别为越界，而是被当作"用户提供了信息"，助手接着追问医院：
  "我血压有点高，要不要紧？" / "这个药还能继续吃吗？" / "阿司匹林一天吃几片？" / "帮我看看这个化验单" / "我是不是该住院？"
- **触发步骤**：`agent.llm.enabled=false`（本地 Demo 默认值，见 `application.yml`）时输入上述任一句。
- **根因**：越界判定只有两层关键词。编排器兜底 5 个词（`AgentOrchestrator.java:28`：怎么用药/药量/诊断/检查结果/是不是得了），规则提取器 12 个词（`RuleFactExtractor.java:48`）。未命中即落到 `PROVIDE_INFORMATION` → `CURRENT_FLOW` → `advance()`。
- **加重情形**：混合句式会被"部分吞掉"。例如 **"9月18日，我最近头晕是不是血压高了"** —— `parseDate` 正常解析出日期并写入 `state.date`，越界部分无任何提示，用户会以为得到了答复。
- **影响**：把医疗问题当空气比明确拒绝更危险，且违反 Requirement 4.7 与 `01-requirement-mapping.md` 的 S-02/S-03。
- **建议**：从"关键词命中才拒绝"改成"**默认怀疑**"——在 `RuleFactExtractor` 中对「症状词/药物名词 + 吗/呢/要不要/能不能/几片/多久/要紧」这类模式做匹配，并把判定**提到与紧急情况同级**（在 `AgentOrchestrator.decide` 顶部，先于业务意图）。同时补齐 `DeepSeekFactExtractor` 的 `MEDICAL_ADVICE` 用例集。
- **相关代码**：`AgentOrchestrator.java:25-29`、`RuleFactExtractor.java:47-48`、`FollowupAgentService.java:135-138, 147-149`、`DeepSeekFactExtractor.java:119-120`

## RV-04 `acceptAlternative` 问在选号之后

- **现象**：用户已经选好某个具体号源后，才被问"如果这一天没有号，您接受前后几天的其他时间吗？"——而这一天明明有号且已选中。
- **根因**：`advance()` 中该问题的位置排在 `state.selectedSlot != null` 检查**之后**（`:696` vs `:702-707`）。正常路径下 `querySlots` 拿到空列表时用户走不到这一步，所以功能上由 `NO_SLOT` 分支（`:768`）兜住。
- **影响**：正常办理（场景一）多一轮无意义问答，违反"一次只问一件事、少打扰"的适老化原则。需求 U-06 确实要求问这个，但位置应在选日期前或查号前。
- **建议**：把该问题前移到日期确定后、`querySlots` 之前；`NO_SLOT` 分支保留兜底（用于用户中途改日期的情况）。
- **相关代码**：`FollowupAgentService.java:690-707, 751-786`、`01-requirement-mapping.md` U-06

## RV-05 确认卡不体现"已检测到冲突"

- **现象**：用户在冲突分支点「仍保留这个时间」后，确认卡的 `operations` 列表里没有任何冲突提示，只说"请核对本次实际执行内容"。
- **根因**：`buildConfirmation()`（`:957-986`）拼接 operations 时只写医院科室/时间/陪同/出发/材料/提醒/通知，不读取冲突信息；`KEEP_CONFLICT`（`:270`）只是把 `scheduleChecked` 置 true，没有把冲突事项存进 `state`。
- **影响**：用户最后一道防线缺少关键上下文；从审计和家属通知角度也应当留痕。
- **建议**：`checkSchedule` 命中冲突时把 `conflicts` 存入 `ConversationState`；`buildConfirmation` 在有冲突且用户选择保留时追加一行，例如：`已知冲突：与"社区体检"(10:00-11:00) 时间重叠，您已选择保留`。
- **相关代码**：`FollowupAgentService.java:270, 937-955, 957-986`、`ConversationState.java`

## RV-06 「仍保留这个时间」被分页隐藏

- **现象**：冲突分支返回 4 个选项（当天其它号源 2 个 + 重新选择日期 + 仍保留这个时间），前端每页只渲染 3 个，`KEEP_CONFLICT` 落在第 2 页。
- **根因**：`assistant-view.tsx:182-186` 的 `slice(choicePage * 3, choicePage * 3 + 3)` 通用分页。
- **影响**：对老年用户不满足"重要操作始终可发现"（验收清单"适老化"一节）。
- **建议**：为 `KEEP_CONFLICT` 这类确认性动作保留固定位置，或把冲突分支的候选压到 1 个以保证第一页能放下全部四类动作。
- **相关代码**：`FollowupAgentService.java:947-949`、`assistant-view.tsx:182-186`

## RV-07 越界不产生状态、无专属视觉，`BoundaryAlert` 未实现

- **现象**：越界回复以普通聊天气泡呈现，顶部步骤标签不变，用户无法看出这条消息比平时重要。
- **对比**：紧急情况会切到 `EMERGENCY_PAUSED`，顶部显示"已暂停，请及时求助"（`assistant-view.tsx:25`），`stopped()` 拦截后续所有入口，且 `emergency()` 中 `state.confirmationId = null` 让旧确认失效（`:455`）。越界分支（`:147-149`）三者都没有。
- **根因**：`MEDICAL_BOUNDARY` 分支调用 `respond()` 时不改 `stage`；`02-pages-and-interactions.md:56` 设计的 `BoundaryAlert` 组件（"医疗越界和紧急情况提示"）在 `assistant-cards.tsx` 中没有对应实现——该文件只有 `PlanCard` / `ConfirmationCardView` / `ResultCardView`。
- **待定的设计取舍**：越界后是否应让待确认的 `confirmationId` 失效？本文倾向**不失效**（用户问诊断不等于想中断办理），但应在文档中写明该结论，而不是靠"没写"默认。
- **建议**：越界时切一个 `BOUNDARY_NOTICE` stage 或增加 `noticeType` 字段，前端渲染为独立视觉块；同时把上述取舍写进 `Design.md` 或 `DECISIONS.md`。
- **相关代码**：`FollowupAgentService.java:147-149, 454-459`、`assistant-view.tsx:21-26`、`assistant-cards.tsx`

## RV-08 没有可编程的场景触发/重置入口

- **现象**：四个场景无法按需稳定复现，只能靠"选对日期"这类口头指导。
- **根因**：`DemoController` 只有健康检查；`06-mock-data-design.md:11` 已承认"当前没有场景选择/重置 API"，但 `01-requirement-mapping.md:122` 明确要求"这些场景不能靠临时修改代码制造，应由 `scenarioId` 或模拟数据开关稳定复现"。
- **影响**：录屏与评审查验依赖人工记忆；与 RV-02 叠加后场景三尤其脆弱。
- **建议**：增加 `POST /api/demo/scenarios/{id}` 重置接口，把四场景所需的种子（含 `user_schedules` 的相对日期）作为参数注入，返回可复现的起始会话 ID。
- **相关代码**：`DemoController.java`、`06-mock-data-design.md:11`、`01-requirement-mapping.md:113-122`

## RV-09 `respondWithCancelCard()` 为死代码

- **现象**：`FollowupAgentService.java:1004-1011` 定义后从未被任何位置调用。
- **附带风险**：方法内直接访问 `state.selectedSlot`（`:1007-1008`，经 `slotLabel`）和 `state.hospital`，若被误接入且此时 `selectedSlot == null`，`slotLabel` 为 null 安全但 `state.hospital` 可能为空串，展示不准确。
- **建议**：删除；若确需该卡片，改为在 `prepareExistingCancellation` 中复用并加空值守卫。
- **相关代码**：`FollowupAgentService.java:1004-1011, 539-552`

## RV-10 `sideTask` / `returnPolicy` 只写不读

- **现象**：两个字段被赋值、被 `ConversationStore` 写入快照并读回（`ConversationStore.java:110, 123, 155-156`），但**没有任何控制流读取它们**——`sideTask` 只在 `queryMyAppointments`（`:490`）、`beginCancelExistingAppointment`（`:515`）、`prepareExistingCancellation`（`:542`）被写，以及 `clearInterruption`（`:657`）被清；`returnPolicy` 只在 `rememberInterruptedTask`（`:639`）被赋 `"ASK_TO_RESUME"`。
- **影响**：状态快照多两个无效字段；后续维护者可能误以为它们参与路由。
- **建议**：确认无意向后删除字段及其快照槽位；若确有规划用途，在 `DECISIONS.md` 注明。
- **相关代码**：`ConversationState.java:46-47`、`ConversationStore.java:100-165`

## RV-11 冲突检查窗口硬编码 60 分钟

- **现象**：`checkSchedule` 用 `start.plusMinutes(60)` 作为检查区间终点（`:940-941`），所有科室统一 60 分钟。
- **影响**：模拟场景下可接受，但 departments 表已有 `followup_scope` 等资料，时长若将来进入模拟数据，此处会成为不一致来源。
- **建议**：低优先级。若引入科室时长，改为从科室资料或 slot 定义读取。
- **相关代码**：`FollowupAgentService.java:937-955`、`MockScheduleTool.java:25-35`

## RV-12 `queryAlternatives` 窗口含当天

- **现象**：`MockAppointmentTool.queryAlternatives` 查询 `date.minusDays(3)` 到 `date.plusDays(3)`（`:62, 71`），区间**包含 date 本身**，因此"附近日期"的候选里会混入同一天的其它时段。
- **影响**：周末无号场景不受影响（当天本就没有任何号），但若某天只是部分时段被占，标注为"附近日期"的候选实际是同日时段，文案与内容不符。
- **建议**：若要保持"换日期"语义清晰，排除当天（`date.plusDays(1)` 起），或把返回结果按日期分组后分别呈现。
- **相关代码**：`MockAppointmentTool.java:59-74`、`FollowupAgentService.java:766-785`

---

## 附：本次已确认无问题的部分

以下结论同样基于静态阅读，作为后续修改时**不应破坏**的既有性质记录：

- 写操作（`appointmentTool.submit` / `scheduleTool.createReminder` / `familyTool.notify`）全部集中在 `confirm()`（`:345-393`）内，且校验 `stage == AWAITING_CONFIRMATION` 与 `confirmationId` 双重条件。
- 冲突检查为纯只读：`MockScheduleTool` 对 `user_schedules` 无任何 UPDATE/DELETE，结构上不存在"覆盖原日程"的路径。
- 无号源返回空列表而非抛异常（`querySlots` `:758`），符合验收清单"指定日期返回无号源，而不是工具异常"。
- 用户拒绝替代日期时不调用 `queryAlternatives`，由 `noSlotRespectsRefusalOfOtherDates`（`SilverAgentApplicationTests.java:119-127`）断言覆盖。
- 越界命中后走固定受控话术（`:147-149`），不生成模型自由文本，不调用任何工具。
- 改日期/医院/科室/选号后 `invalidate()` 清除 `scheduleChecked`、`travelPlan`、`confirmationId`（`:461-466`），强制重新检查与重新确认。
