# 语音优先交互改造 · P0 任务书

> 面向实施者（含 AI 助手）的 P0 范围任务说明。**本文件只描述 P0，不包含 P1/P2。**

## 0. 范围与红线（必须先看）

**本阶段只做 P0，且必须遵守：**

- ❌ 不做全局 `VoiceProvider` 大重构（语音仍留在助手模块内）。
- ❌ 不修改评委视图、不调整整体 UI 风格。
- ❌ **不要提交 Git**（不 commit、不 push）。
- ❌ **禁止 `git reset` / `git checkout` / 覆盖无关文件**。当前工作区约有 70 项未提交改动，必须完整保留。
- ❌ **禁止对整个文件运行格式化工具**（尤其 `oxfmt`）。`FollowupAgentService.java` 是未提交改动的热点文件，整文件格式化会把 diff 炸成不可读并可能覆盖他人重构。**只改必要行。**
- ✅ 改动应集中在本文列出的文件中；新增方法优先用私有方法，不新建大型 Builder。

**必读文件（修改前先读，勿凭猜测）：**

后端
- `backend/src/main/java/com/team/silveragent/domain/model/AgentTurnResponse.java`
- `backend/src/main/java/com/team/silveragent/application/FollowupAgentService.java`
- `backend/src/main/java/com/team/silveragent/application/ConversationState.java`
- `backend/src/main/java/com/team/silveragent/application/SafetyGuard.java`
- `backend/src/main/java/com/team/silveragent/application/ToolPolicy.java`
- `backend/src/main/java/com/team/silveragent/agent/TemplateAnswerGenerator.java`
- `backend/src/main/java/com/team/silveragent/agent/planning/LlmConversationPlanner.java`
- `backend/src/main/java/com/team/silveragent/agent/RuleFactExtractor.java`

前端
- `frontend/app/page.tsx`
- `frontend/features/assistant/assistant-view.tsx`
- `frontend/features/travel/travel-guide-view.tsx`
- `frontend/lib/speech-service.ts`
- `frontend/types/domain.ts`

## 1. 已核实的代码事实（实施依据）

这些是核对过的现状，实施时以它们为准，不要重新臆测：

| 事实 | 位置 |
|---|---|
| `AgentTurnResponse` 为 record，已有字段 `conversationId, stage, reply, quickReplies, plan, confirmation, result, toolTraces, task`；另有 8 参兼容构造与 `static message(...)` | `AgentTurnResponse.java` |
| `ResultCard` 字段：`appointmentId, hospital, department, date, time, materials, departureTime, reminderStatus, familyStatus` | `AgentTurnResponse.java` |
| **`ResultCard` 没有交通方式 / 耗时 / 距离 / 楼层 / 诊室** → 预约完成口播**不得虚构**这些 | 同上 |
| 完成回复硬编码：`executionResult(state, "办理完成，请查看复诊事项卡。")` | `FollowupAgentService.java:518` |
| `prepareExistingCancellation(state, String id)` 与 `prepareExistingCancellation(state, AppointmentSummary)` 两个重载已存在 | `FollowupAgentService.java:663 / :673` |
| `state.sideTask = "CANCEL_EXISTING_APPOINTMENT"` 在取消支线中设置 | `FollowupAgentService.java:676` |
| 单条候选时已直接进入确认：`if (rows.size() == 1) return prepareExistingCancellation(...)` | `FollowupAgentService.java:653` |
| `new AgentTurnResponse(...)` 共 **11 处**（FollowupAgentService 10 处 + record 内 1 处）→ 兼容构造函数很重要 | 后端 |
| `SafetyGuard` 是 **package-private** `final class`，方法 `precheck(String)` / `evaluate(String, ExtractedFacts)`，返回 `Decision { NONE, EMERGENCY, MEDICAL_BOUNDARY }` | `SafetyGuard.java` |
| `AppointmentTravelGuide` 类型真实存在（后端 `TravelGuideService` / `ToolModels` / `TravelGuideController`，前端 `types/domain.ts` / `lib/appointment-api.ts`） | 前后端 |
| 前端语音服务已有 `speakText / stopSpeech / subscribeSpeech` | `frontend/lib/speech-service.ts` |
| `AssistantView` 在 `active` 变 false 时 `stopSpeech()`；另有 `voicePreference.autoSpeakEnabled` 开关与卸载时 `stopSpeech()` | `assistant-view.tsx:81 / :85 / :88` |
| 前端**没有测试框架**（scripts 仅 `dev/build/start/lint/format`，devDependencies 无 vitest/jest），**没有 typecheck 脚本** | `frontend/package.json` |

---

## 2. 一、扩展响应协议

在 `AgentTurnResponse` **末尾**增加两个字段：

1. `speechText`：本轮需要朗读的权威文本，可空。
2. `uiDirective`：可空的页面指令。

### 2.1 UiDirective 定义

使用**封闭枚举**，字段至少包含 `type`、`appointmentId`、`focus`：

```text
NONE
OPEN_ASSISTANT
OPEN_TASKS
OPEN_MATERIALS      // 见下方说明，本阶段就加入枚举
OPEN_TRAVEL
SHOW_OUTSIDE_ROUTE
SHOW_INSIDE_GUIDE
FOCUS_CONFIRMATION
```

> **注意（修正项）**：`OPEN_MATERIALS` 本阶段虽然不一定触发，但**现在就加进封闭枚举**，避免 P1「查看清单直接跳页」时再次改动协议。

### 2.2 兼容性要求

- **保留兼容构造函数**：现有 11 处 `new AgentTurnResponse(...)` 调用不得被迫全部修改。
- 旧调用默认：`speechText = reply`，`uiDirective = null`。
- 前端**遇到未知 `uiDirective.type` 必须安全忽略**：不报错、不执行任意路由、不跳空白页。

### 2.3 持久化兼容（写进实现，勿多做）

`AgentTurnResponse` 会被序列化存入 `conversation_sessions.last_response_json`。**新增字段后旧数据反序列化为 `null` 即可，无需任何数据迁移逻辑。**

> **备注（已知取舍）**：`speechText` 默认等于 `reply`，意味着历史调用产生的长列表回复（例如号源 6 个日期）也会被完整朗读。P0 接受此行为；**列表类回复的 `speechText` 精简留待 P2**，本阶段不要顺手改。

---

## 3. 二、预约成功必须完整播报

当前完成路径只回复：`办理完成，请查看复诊事项卡。`（`FollowupAgentService.java:518`）。

**改法：**

1. 先构造 `ResultCard`，再**根据 ResultCard 中的权威字段**生成 `reply` 与 `speechText`。
2. P0 **直接在 `FollowupAgentService` 中增加私有方法**完成拼接，**不新增大型 Builder**。
3. `speechText` **不得经过 `LlmAnswerGenerator` 改写**；必要时让该完成路径走**确定性生成**。

**播报必须包含：**

- 完整日期（年月日）
- 具体时间（上午/下午 + 时刻）
- 医院
- 科室
- 材料清单
- 建议出发时间
- 提醒状态
- 家属通知状态
- 结尾询问是否查看地图与院内指引

**示例（用词可调，事实字段必须来自 ResultCard）：**

> 已经为您预约成功。复诊时间是 2026年9月10日 上午9点，医院是市第一医院，科室是神经内科。请携带身份证、医保卡、门诊病历、既往检查报告和用药清单。建议早上8点10分出发。出发提醒已创建，已经通知女儿小丽。需要我现在打开地图，告诉您怎么走吗？

**约束：**

- 不得虚构 `ResultCard` 中不存在的字段（交通方式、耗时、距离、楼层、诊室）。这些由地图页拿到 `AppointmentTravelGuide` 后再播报。

---

## 4. 三、自然语言直接打开页面

用户明确说出以下内容时：

- 查看地图 / 打开地图 / 我想看地图 / 怎么去医院
- 到医院后怎么走

后端在查到对应 `appointmentId` 后，除返回文字与快捷按钮外，**还要返回 `uiDirective`**：

| 用户话术 | uiDirective |
|---|---|
| 查看地图 / 打开地图 / 我想看地图 / 怎么去医院 | `type = OPEN_TRAVEL`，`appointmentId = 当前或最近的有效预约` |
| 到医院后怎么走 | `type = SHOW_INSIDE_GUIDE`，`appointmentId = 当前或最近的有效预约`，`focus = inside` |

**执行要求：**

- 前端收到指令后**自动执行** `onOpenTravel`，不要求用户再点一次按钮。
- **原有按钮必须保留**。
- 页面跳转**不是工具调用，不经过 `ToolPolicy`**；但自然语言**必须先经过现有 `SafetyGuard`**。
- **顺序必须是：先 `safetyGuard.evaluate(message, facts)`，判定为 `NONE` 后，才匹配页面意图。** 不能因为用户说「我胸口疼」而触发普通页面跳转。
- 新增的页面意图识别代码**必须落在 `application` 包内**（`SafetyGuard` 是 package-private，包外无法调用）。

**新增要求（修正项）：**

- **必须写死「当前或最近的有效预约」的确定性规则**（建议：优先本会话当前 `appointmentId`；否则取预约时间距离当前最近且未过期的有效记录）。
- **必须补空场景兜底**：一条有效预约都没有时，**只回复说明文字并保留按钮，不返回 `uiDirective`**。

---

## 5. 四、地图页加载后自动朗读

`TravelGuideView` 获得 `AppointmentTravelGuide` 后，**根据真实 route 与 facility 数据**拼接口播：

预约日期和时间 / 医院和科室 / 交通方式 / 距离与预计用时 / 建议出发时间 / 入口 / 楼栋 / 报到点 / 楼层与诊室 / 找不到时到哪求助。

**示例：**

> 路线已经打开。您上午9点在市第一医院神经内科复诊。家属开车预计30分钟，大约8.7公里，建议8点10分出发。到院后请从东门进入门诊楼，先到一楼自助机报到，再乘电梯到五楼506诊室。找不到时，请到五楼护士站询问。

**约束：**

- **必须使用 `AppointmentTravelGuide` 数据，不得由大模型生成。**
- **（修正项）自动播报必须先判断用户的 `voicePreference.autoSpeakEnabled` 开关**，关闭时不得朗读。

### 5.1 修正语音生命周期

现状：`AssistantView` 在 `active` 变为 false 时调用 `stopSpeech()`（`assistant-view.tsx:81`），会导致自动切到地图页后**新播报被误取消**。

**要求：**

- 页面切换**不得**误取消地图页的新播报。
- 但**用户主动开始新的语音输入时，仍应停止旧播报**。

> 实现提示：区分「因路由切换导致的 `active=false`」与「用户主动发起语音/发送新消息」，只在后者调用 `stopSpeech()`。

---

## 6. 五、修复「最近的一次」

现状：进入取消预约支线、系统返回多条候选后，用户说「最近的一次」会掉进普通兜底回复。

**P0 只做确定性修复：**

当 `state.sideTask` 为 `CANCEL_EXISTING_APPOINTMENT` 时，**优先识别**：

- 最近的一次
- 最早的一次
- 上午那个
- 某日期的（如「9月10日的」）
- 某医院那条（如「市第一医院那条」）

**规则：**

- 「最近的一次」定义为：**候选中预约日期时间距离当前时间最近的有效预约**。
- 「最早的一次」定义为：**候选中日历时间最早的一条**。
- 选中后**只调用 `prepareExistingCancellation(...)` 生成确认卡**（复用 `FollowupAgentService:663 / :673` 的现有重载）。
- **绝对不能直接取消。**
- 确认卡与语音**必须复述完整日期、时间、医院、科室**。
- 「好的」「继续」**不得**被当作取消确认。
- 语义有歧义或无法唯一确定时，**明确询问用户**，不要猜。

> P0 允许在待取消状态下**重新查询有效候选**来确定目标；更完整的 `candidateAppointmentIds` 状态管理留给 P2。

---

## 7. 六、语音确认红线（本阶段不实现模糊确认）

本阶段**不实现语音模糊确认**。如该部分会扩大 P0 范围，**可暂时只保留按钮确认**，但**不得降低现有确认门禁**。

若实现语音确认，必须**同时**满足：

- 当前存在 `ConfirmationCard`；
- 携带**当前** `confirmationId`；
- 用户说出**明确动作**，例如「确认预约」「确认取消预约」；
- 「好的」「可以」「继续」**不能**作为确认；
- 确认成功后 `confirmationId` **立即失效**；
- 页面上「确认」与「返回修改」按钮**永久保留**。

---

## 8. 七、测试与验收

### 8.1 后端（可自动化，至少覆盖）

1. 预约成功后 `reply` 与 `speechText` 均包含日期、时间、医院、科室、材料。
2. `speechText` 的关键业务事实与 `ResultCard` 一致。
3. 「我想看地图」返回 `OPEN_TRAVEL` 及正确 `appointmentId`。
4. 「到医院后怎么走」返回 `SHOW_INSIDE_GUIDE`。
5. 多条待取消预约时，「最近的一次」生成对应预约的确认卡，**不执行取消**。
6. 「好的」「继续」不能执行预约或取消。
7. 紧急表达仍优先经过 `SafetyGuard`，不能触发地图或普通预约流程。

### 8.2 前端（修正：无测试框架，改用手动 + 静态检查）

> 前端**没有测试框架、没有 typecheck 脚本**。**不要为此引入 vitest**（超出 P0）。

- 第 8 条「未知 `uiDirective` 安全忽略」、第 9 条「自动打开地图后完成路线与院内指引播报」→ **人工验收**，并记录验收步骤。
- 自动化部分改用：`npx tsc --noEmit` + `npm run lint` + `npm run build`。

### 8.3 完成后必须运行

- 后端全部测试：`mvn test`（在开发容器内执行，勿用宿主机工具链）
- 前端类型检查：`npx tsc --noEmit`
- 前端生产构建：`npm run build`

---

## 9. 交付报告格式（只报告这几项）

1. 修改了哪些文件
2. 每个问题如何修复
3. 测试结果（含证据：命令 + 输出摘要）
4. 仍留给 P1/P2 的内容

---

## 10. 明确不在本阶段（P1 / P2 预告）

- **P1**：全局麦克风入口（底部导航中间大麦克风，悬浮而非路由）、语音识别后自动发送、把语音从 `AssistantView` 抽到 `frontend/features/voice/`、本地页面指令不经大模型、地图页与事项页语音操作。
- **P2**：`ConversationState` 增加 `selectionContext` 与 `candidateAppointmentIds`、列表类回复的 `speechText` 精简、完整语音回归测试。

**P0 验收标准：**

> 除首次授权浏览器麦克风外，老人**不触摸屏幕**即可完成：预约 → 听完整结果 → 看地图 → 听院内指引 → 查材料 → 发起取消；所有关键写操作仍需**明确确认**。
