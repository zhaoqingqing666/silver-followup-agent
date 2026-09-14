# 项目进度记录

进度文件记录“当前事实”，不写大段过程描述。功能完成后由负责人更新，并附对应 Pull Request 或提交。

## 2026-09-13 确认与执行架构整理（第四阶段 4B + 4B 修复轮，未提交）

- 分支 `refactor/application-packages`，这一步只做 4B：**把「确认通过之后」那三类业务执行从 `confirm()` 里搬出去**（BOOKING / MEMO / CANCEL_MANAGED），CANCEL_APPOINTMENTS 上一轮已经归 `CancellationExecutor`，这一轮不动。不提交、不推送。
- **4B 首轮评审未通过，本轮只修确认边界，不扩业务。**评审否掉的是三条把「确认卡上的目标」与「实际执行的对象」分开的缝，加上一条「确认失败变成 HTTP 500」。修法见下一节「4B 修复轮」。
- **`confirm()` 现在只做统一确认流程**：校验会话与 `confirmationId` → 从 `ConfirmationService` 取出**签发时冻结**的 `PendingOperation` → 处理拒绝 → 确认时单次消费凭据 → 按 `Kind` 分派 → 汇总结果、走原来的出站对账。它不再读 `pendingAction`、`appointmentId` 这些可变字段来决定「执行哪件事」——那几个字段现在只剩「给界面看」的用途。
- 新增 `application/ConfirmationDispatcher`（121 行，包级私有 `@Component`）：持有五个执行器，`dispatch(state, operation, approved, support)` 是唯一入口。分发次序逐条与重构前对齐：备忘和代约取消各自连「拒绝」一起接管（它们被拒时也要把自己那份草稿收干净）；剩下两类先处理拒绝、再处理执行；预约那一条的**过期时段检查排在写库之前**（代他人办理也绕不过去，与旧代码一致）。
- 四个业务执行器，每个只管**一种**写操作，不建「大而全执行器」：`BookingExecutor`（129 行，开新预约 + 拒绝）、`MemoExecutor`（备忘的确认/拒绝/直写）、`ManagedCancelExecutor`（代约取消 + 通知安排者）、`CaregiverBookingExecutor`（代他人办理的开单与取消）。加上上一轮的 `CancellationExecutor`，`PendingOperation.Kind` 的六个取值各有归口，**没有任何一个取值会「落到」预约那条路上**——这条有专门的测试钉住。取值是六个而不是四个，是修复轮拆出来的（本人自办与代他人办理各成一类，见下）。
- **执行器不复制数据库安全校验。**归属、当前状态、事务原子性仍然只由现有业务工具负责（`MockAppointmentTool.cancelAll` 的 `@Transactional` + `user_id` + `status='CONFIRMED'`），执行器组织的是「**什么时候跑**」与「跑完之后会话停在哪一页」。这一点沿用 DEC-022 决定五，没有第二套查询口径。
- 新增 `application/ConfirmationSupport`（**包级私有抽象类**）：执行器要用到的那二十几个会话收尾动作（`respondWithPlan` / `advance` / `toolError` / `recordToolFailure` / `draftSlotExpired` / `resumeInterruptedReplies` …）在这里声明，`FollowupAgentService extends ConfirmationSupport` 并逐个 `@Override` 成包级私有的委派。选择抽象类而非接口是为了**不变宽可见性**：接口方法隐式 public，而 `FollowupAgentService` 是 public，DEC-018 明确禁止为了让拆分类能用而放宽封装。这个 support 对象是**分派时按参数传进去的**（`dispatcher.dispatch(state, operation, approved, this)`），不在构造器里注入——否则 `FollowupAgentService → Dispatcher → Executor → Support` 会绕成 Spring 循环依赖。
- 构造器**没变长**：`FollowupAgentService` 只是把 `CancellationExecutor` 这一个参数换成了 `ConfirmationDispatcher`，仍是 38 参；五个执行器的依赖都收在分派器里。
- 用户可见行为逐项保持：BOOKING 确认时**重新检查号源与时段是否仍有效**（号被人抢走则照旧走工具错误出口，凭据照样烧掉）；原有提醒、材料、出发建议、家属通知一字未改；MEMO 的确认/拒绝/重复确认行为未变；CANCEL_MANAGED 的归属校验、取消结果、通知与会话收尾未变；`confirmationId` 仍然**只能消费一次**；旧凭据、过期凭据、类型不可信的恢复状态继续 fail closed，一个写操作都不执行。
- Dev Container 验证（2026-09-13，`/workspace/backend`）：修复轮先跑专项（`ConfirmationDispatcherTests` + `ManagedCancelExecutorTests` + `ConfirmationDispatchFlowTests` + `MemoConfirmationRecoveryTests` + `MemoToolFailureTests` + `ConfirmationServiceTests` + `ConversationRecoveryTests` + `CancellationExecutorTests`、`CancelScopeTests`），**79 项全绿**；随后后端全量 `mvn test` **477 项，0 失败 0 错误，BUILD SUCCESS**（4B 首轮基线 462，修复轮净增 15 项）。
- 本阶段的测试覆盖（按 4B 要求逐条）：六个 `Kind` 各到各家、任意 `Kind` 不误落预约路（`ConfirmationDispatcherTests`）；改掉 `pendingAction`/`appointmentId` 换不掉已签发操作（分派器与端到端各一条）；`confirmationId` 重复确认不重复写库（预约、备忘、代约取消各一条）；会话恢复后仍执行原始 `Kind` 与完整目标（预约卡与代约取消卡各一条，走真实的库）；每类执行失败时凭据与会话状态的收场；拒绝确认不调用任何业务执行器。
- 行数与分包：`FollowupAgentService` 5327 → **5185 行**；`application/` 37 → **43 个类**，11244 → **11977 行**。**这一步降的是耦合，不是体积**——搬走的是三类执行的编排，留下的是分派层与端口声明；修复轮往 `ConfirmationService`（304 → 417 行）与几个执行器里补的是护栏与注释，那就更不是「拆」。别再拿它当「已经拆完了」。
- 修复轮改动的文件：`ConfirmationService`（Kind 拆六个、`requireTargetCount`、`payloadIntact`）、`ConversationStore`（快照加六个备忘草稿字段）、`FollowupAgentService`（四个签发点：代约取消冻住 id、按 `caregiving()` 选本人/代办的 `Kind`）、`ManagedCancelExecutor`（改写：只认冻结目标）、`CaregiverBookingExecutor`（按 id 取消）、`CareBookingService`（新增 `cancelAppointment`）、`MemoExecutor`（写异常收口）、`ConfirmationDispatcher`（按六个 `Kind` 分派，去掉 `caregiving()`）、`ConfirmationSupport`（删掉已无用的 `arrangedPlan` 端口）。业务语义、对外接口、`AgentTurnResponse` 字段、`confirmationId` 的取值与生命周期一律未变。
- 对外接口、`AgentTurnResponse` 字段、`confirmationId` 的取值与生命周期一律未变，`docs/05-api-contracts.md` 与 `INTERFACE_CHANGES.md` 无需改动。
### 4B 修复轮（2026-09-13，同分支，未提交）

首轮评审未通过，四条确认边界**本轮全部修掉**，不再列为「以后处理的风险」：

- **一、确认卡上的目标 == 实际执行的对象。**三处缝一起补：
  - `CANCEL_MANAGED`：签发时把 `plan.appointmentId()` 冻进 `PendingOperation.targetIds`（以前传空列表）；`ManagedCancelExecutor` 只取消凭据上那一条，**执行时不再重找"当前那一份代约安排"**。按 id 的查询只用来取展示与通知字段（谁安排的、约在哪天），归属/存在/可取消仍由 `appointmentTool.cancel` 那条 SQL（`id + user_id + status='CONFIRMED'`）判；原目标失效就**什么都不取消**，返回友好提示，绝不换一条顶替。
  - 照护端 `CANCEL_APPOINTMENTS_CAREGIVER`：`CaregiverBookingExecutor.cancel` 不再忽略 `operation.cancellationTargets()` 去调 `cancelUpcoming()`，改用新增的 `CareBookingService.cancelAppointment(caregiverId, elderUserId, appointmentId)` —— 按明确 appointmentId 的 `@Transactional` 入口，照护关系、预约归属、当前状态三项校验与原来的协同通知（含"通知原安排者"）都在里面。`cancelUpcoming` 保留：`CareBookingController` 那个"取消当前预约"的入口仍在用它。
  - **两侧成对的条数护栏**：签发侧 `ConfirmationService.requireTargetCount`（`CANCEL_MANAGED` 与 `CANCEL_APPOINTMENTS_CAREGIVER` 必须**恰好一条**；本人整批取消至少一条；不针对已有对象的类型必须为空），执行侧 `PendingOperation.singleTarget()`（不是恰好一条返回 `null`，执行器什么都不做）。**不静默取第一条**。
- **二、分派不再看 `caregiving()`。**`PendingOperation.Kind` 由四个拆成六个（`BOOKING` / `BOOKING_CAREGIVER` / `CANCEL_APPOINTMENTS` / `CANCEL_APPOINTMENTS_CAREGIVER` / `CANCEL_MANAGED` / `MEMO`），本人自办与代他人办理是**两类动作**而不是"一类动作加一个执行时再看一眼的开关"。`ConfirmationDispatcher` 的 switch 里现在一次都不出现 `caregiving()`，路由完全由签发时冻住的类型决定。
- **三、备忘确认的会话恢复。**`ConversationStore.Snapshot` 带上 `pendingMemoText / pendingMemoAt / pendingMemoRepeat / pendingMemoDay / memoReturnStage / memoNeedsApproval` 六个字段，与 `confirmationId / confirmationKind / confirmationTargetIds` 一起存、一起回。恢复后确认写入的就是卡片上那一条（正文与提醒时间逐字相同）。**旧快照缺这些草稿时 fail closed**：`restored()` 里加 `payloadIntact`，凭据在门口就当不可信、当场作废并请老人重说，绝不写一条残缺的备忘；签发侧 `issue()` 同样要求草稿已在（否则是调用点的编程错误）。
- **四、备忘写入异常不再变成 500。**`MemoExecutor.write` 把 `memoTool.create` 的异常收在 `try/catch` 里走统一 `toolError`。失败后的账是明确的：凭据在 `consume` 里**已经消费掉**、草稿已被 `leaveConfirmation` 清干净，所以不会重复写库，页面上也不会再留一张实际已经作废的确认卡。
- 补的回归（全部落在真实的库 / 真实 HTTP 上）：发卡后库里新增另一条更早代约，确认仍只取消卡上那一条；照护端发卡后列表顺序变化，仍只取消冻结目标（中途重启一次）；冻结目标已取消、或已不属于该就诊人时，一条替代的都不取消；备忘卡签发→落库→重启→确认，写入正文与原提醒时间；旧快照缺备忘草稿时在门口作废、库里零条；`memoTool.create` 抛异常时 HTTP 200 + 友好话术 + 库里零条 + 凭据已烧；拒绝确认不调用任何取消或备忘写工具。
- 未解决（本轮范围之外，如实列出）：`FollowupAgentService` 仍是 5173 行量级的大类（拆体积不是这一轮的目标）；`CaregiverBookingExecutor` 里那条 `"CANCEL".equals(pendingAction)` 死条件仍未删（删掉会让人以为这里曾有过一条分支，已在 Javadoc 写明它为什么可以忽略）。
- 反向验证的口径：修复轮里 `requireTargetCount` 判错的那一版是**真的红过**——`ConfirmationDispatchFlowTests` 的代约取消卡整条报 `TOOL_ERROR`（"目标集合必须为空，实际 1 条"）才被发现，改对之后转绿，这条是红的→绿的完整来回。其余判据是"断言的就是库里的那一行/那一张卡"，没有另做变异测试；其中"旧快照缺草稿在门口作废"这一条本想临时关掉 `payloadIntact` 验证它会变红，该改动被权限分类器按"削弱安全校验"拦下，遂放弃，**这一条只有正向验证**。
- 用户自己跑在 `:8080`/`:3000` 的实例未受影响。

## 2026-09-13 确认与执行架构整理（第四阶段 4A，未提交）

- 分支 `refactor/application-packages`，这一步**只做 4A：凭据与授权范围的数据正确性**，不提交、不推送。执行器迁移（预约 / 备忘 / 代约取消）留作 4B 单独一轮，避免一次改动过大。
- 新增 `application/ConfirmationService`（304 行，包级私有）：确认凭据的签发、校验、消费、废止、出站对账，全部只在这一处发生。`Decision` 是「这次请求过没过」的裁决，`PendingOperation`（嵌套 record）是「这件事是哪一类、对哪几条对象」。
- **一份授权＝凭据＋动作类型＋完整目标集合，三样都由这个类管，全部写在 `ConversationState` 上、全部随 `ConversationStore.Snapshot` 持久化。**三样同生共死：`issue` 一起写，`consume`、`clear`、`retire` 一起清（只清凭据是最容易犯、又最看不出来的错——凭据为空时所有判据都说"没有待确认的东西"，可状态里还躺着一个类型和一个目标集合）。
- **动作类型在签发时由调用方给死，确认时不再重算。**四处签发各自都知道自己在建哪张卡（取消预约 / 开新预约 / 代约取消 / 备忘），就在调用点写死传进去；`pendingAction` 这个可变字段不再参与分派。不这样做会留下一个真实的错配：卡是取消卡，签发之后中间某一步把 `pendingAction` 写成了别的值，确认时重算类型就会让**同一把钥匙去执行另一件事**——老人点头的是取消，执行的是开单。
- **认不出来的一律 fail closed。**从快照还原时认不出的类型名一律不认（`Kind.stored` 返回 `null`），**绝不退成 `BOOKING`**：`BOOKING` 恰好是全类里唯一会真去开一条新预约的类型，一个写坏或回滚出来的陌生类型名会静默变成一次开单。
- **会话恢复改成完整恢复。**原先一次批量取消重启后只剩一条目标（`pendingAppointmentIds` 不进快照）；现在目标集合进快照，恢复出来的那一批与签发时逐条相同。改这条的是评审——第一版把它当"既有降级"保留并写了单测钉住，判断错了：那条降级没有任何产品理由，只是目标集合当初没进快照的副作用，而卡片上写着两条、实际只取消一条时，老人看到的界面和"取消成功"完全一样，他会以为那个号已经退了（见 DEC-022 的「评审修订」）。
- **补发只在整份授权逐项相同时复用原凭据（评审第二轮收尾）。**原先的判据只是"手上有没有一份读得出来的凭据"，会漏掉最坏的一种组合：**屏幕上是一张新卡，钥匙却是上一件事的**（比如新的开新预约卡配着上一轮那张批量取消的凭据）——老人对着新卡点头，执行出来的是旧事，界面上没有任何东西提示他。现在 `ensureIssued` 逐项比对类型与目标集合，任何一处不同都签新的、旧的连同它的类型与范围一起作废；目标集合先取规范形态（去重、保留顺序，不排序——「先取消哪条」是调用方给的信息）再比。
- **签发侧不再把 `null` 当空列表。**`issue` 与 `ensureIssued` 的 `kind`、`targetIds` 都不接受 `null`，空目标必须显式传 `List.of()`。`null` 在这套字段里的含义已经定死是"旧快照缺字段 / 不可信"（见上一条），在这里悄悄转成空列表就等于用一个只有签发侧才会做的转换，盖住调用方少写的那件事——而这两者存进快照之后读起来一模一样，重启一回就再也说不清当初是"本来没有目标"还是"目标丢了"。宁可让调用点在编码时就炸。
- **旧快照整份作废，绝不部分执行。**旧快照只有 `confirmationId`，还原不出"当初授权了什么"，所以这份凭据当场作废、退出等待确认、请老人重新确认——**不按剩下的那一条凑合执行**。代价是旧快照里那张卡一律作废（哪怕是本来就没有目标的卡，因为看不出是"本来没有目标"还是"目标丢了"）。
- 新增 `application/CancellationExecutor`（94 行，包级私有）：把目标**原样**交给那条事务（只去重、不筛选——多一套口径就多一个把卡片上没写的那几条也取消掉的机会），清掉 `pendingAppointment*`，收拾会话手里已经消失的 `appointmentId`，并决定收在哪一页（执行完落「已取消」、保留预约落「已完成」、被打断过就回到打断它的那一步且**不动任务状态**）。
- 归属校验与批量原子**仍然刻意不搬进执行器**：`MockAppointmentTool.cancelAll` 是 `@Transactional`，那条 SQL 自带 `user_id` 与 `status='CONFIRMED'`，先整批查一遍再在同一个事务里逐条取消。在执行器里再查一遍只会多出第二套**没有事务**的口径，而先查后删之间正好就是别人改状态的那个窗口。执行器管的是「**什么时候跑**」，不是「这行是不是他的」；代他人办理的取消（`executeCaregiverBooking`）不走这里。
- 范围修订作废旧凭据、批量原子取消、归属校验、重复确认防重四条行为逐条保持不变，并都有测试钉住（`CancelScopeTests` 6 项 + 会话恢复端到端 2 项）。
- `respondConfirmation` 的参数校验统一到 `ToolContract` 一处，而且**查完只回绝、不回退**。若照抄澄清那条的「校验不过就按 intent 回退」，会踩到一个具体的坑：intent 是 `CONFIRM_ACTION` 时会话又停在等待确认时，`modelRoute` 把它接成 `CONFIRM_PENDING`——**一次参数写错的调用换来一次真的执行**。诚实地说，这一条**没有翻转任何用户可见的路由**（旧的手写 `toUpperCase` + 两个 `if` 判断同样会拒掉缺失与非法取值），它买到的是判罚只有一个出处、`decision` 一定是那两个枚举值之一、去掉一份重复的比较，以及**把这个坑写成会红的断言**（见 `PITFALLS.md` 同日条目）。
- `AgentRuntime.Outcome.acceptedToolCall()` 改名为 `hasContractCheckedToolCall()`（2 处调用点）：名字说的是「契约查过了」而不只是「有东西」。「非空 ⇒ 已校验」是产品路径给的性质（只有 `modelProposal` 会往 `proposedTools` 里放东西，放之前必须过 `ToolContract`），不是这个 record 自己保证的。
- `FollowupAgentService` 删掉 `pendingCancellationIds`（按状态推取消目标的第二份口径）与 `retireCancellationConfirmationForRevision`（范围修订时作废旧凭据），四处签发与十一处清空改为调 `ConfirmationService`，`finish` / `finishWithoutModel` 两个出口的开头各加一次 `confirmations.reconcile(...)`。
- Dev Container 验证（2026-09-13，`/workspace/backend`）：全量 `mvn test` **438 项，0 失败 0 错误**（只跑这一遍——`Snapshot` 是落库的格式，不是可反复重跑的东西）。4A 相关：`ConfirmationServiceTests` 28 项（凭据只认签发时定下的类型、认不出的类型不执行任何写操作、目标集合为空与读不出来是两回事、旧快照整份作废而不是部分执行、多目标逐条活过重启、恢复不出范围就不许复用、补发不继承旧范围、补发只在类型与目标都相同时复用（不同则换新且旧的消费不了）、`kind`/`targetIds` 传 `null` 是编程错误且不动原授权、三条退场路径都清干净三样）、`ConversationRecoveryTests` 2 项（真库端到端：批量卡重启后取消掉的仍是签发时那两条；把 `confirmationKind`/`confirmationTargetIds` 从 `state_json` 里抹掉冒充旧快照后，凭据失效、库里两条 `CONFIRMED` 一条不动）、`CancellationExecutorTests` 8 项、`CancelScopeTests` 6 项、`AgentRuntimeRoutingTests` 17 项（含非法 `decision` 被回绝、缺 `decision` 被回绝、`decision` 归一化三例）。
- **每条修复都做了反向复现**（能反向复现的修复才算修复）：把 `Snapshot.from` 强制写成 `null, null`，批量恢复那项立刻红（`expected: "CANCEL_APPOINTMENTS" but was: null`）；把 `restored` 退回旧的部分回填（类型退 `BOOKING`、目标退回 `pendingAppointmentId`/`appointmentId`），6 项红，含端到端的旧快照用例；把 `ensureIssued` 退回「只要读得出凭据就复用」，3 项红（不同类型不复用、不同目标集合不复用、只有完全相同才复用）。每次改完即回滚，测试复绿。
- 收尾那一轮（授权一致性）按评审要求只重跑确认卡相关的五个类，**61 项全绿**（`ConfirmationServiceTests` 28、`ConversationRecoveryTests` 2、`CancellationExecutorTests` 8、`CancelScopeTests` 6、`AgentRuntimeRoutingTests` 17），不重跑全量——`ConfirmationService` 的改动只影响签发与补发两处，全量基线仍是上面那次的 438。
- 用户自己跑在 `:8080`/`:3000` 的实例未受影响。
- 对外接口、`AgentTurnResponse` 的字段、`confirmationId` 的取值与生命周期一律未变，`docs/05-api-contracts.md` 与 `INTERFACE_CHANGES.md` 无需改动。
- 未解决 / 留给 4B 与评审：
  - **四类待确认动作里只有一类搬了家（4B）。**`PendingOperation.Kind` 有 `BOOKING / CANCEL_APPOINTMENTS / CANCEL_MANAGED / MEMO` 四个取值，但只有 `CANCEL_APPOINTMENTS` 真的走 `CancellationExecutor`；`MEMO`、`CANCEL_MANAGED` 与 `BOOKING` 仍在 `confirm()` 里各自回到原来的私有方法。`Kind` 是分派的唯一依据，但**别把这个类读成「它现在管所有写动作」**；按业务逐个抽执行器是 4B 的活，本轮按评审要求刻意不一起做。
  - **行数没降**（`FollowupAgentService` 5133 → 5327 行；抽走的是一件事的判罚与执行，换回来的是调用点上更长的说明）。构造器 36 参 → 38 参，已核实没有任何测试直接 `new FollowupAgentService(...)`，只影响 Spring 注入。这一步整理的是**边界**，不是体积。
  - **后续（2026-09-13，4B 已完成）**：上面第一条「四类待确认动作里只有一类搬了家」是 4A 当时的现状，作为历史原样保留；**现状以这一条为准**——`BOOKING`、`MEMO`、`CANCEL_MANAGED`，以及本人的预约取消与照护端的取消，现在**全部**由 `ConfirmationDispatcher` 按**签发时冻结的 `Kind`** 分派到各自的执行器，`confirm()` 不再按 `pendingAction`、`caregiving()` 或任何可变会话字段重新推断执行路径。落地决定见 `DECISIONS.md` 的 **DEC-023** 与 **DEC-024**。

## 2026-09-13 统一业务时间（未提交）

- 分支 `refactor/application-packages`，本阶段**只统一业务时间**：不重构工具、不改第一阶段页面。
- 新增 `application/time/BusinessClock`：可注入、可测的 `Clock` + 业务时区。时区来自 `business.time.zone`（环境变量 `BUSINESS_TIME_ZONE`，默认 `Asia/Shanghai`），配一个不存在的时区会在启动时明确失败，不会被悄悄忽略。
- **业务钟面与审计钟面分开**，这是本阶段的核心决定。预约日期与时段截止、号源目录的 cutoff、「今天/明天」、提醒钟面、照护代约、号源与日程播种一律走 `BusinessClock`；`created_at` 这类历史时间戳仍按 JVM 默认时区写，**一个字都没改**——旧行是按那个口径写进去的，改了等于把历史数据凭空变老八小时（需求里「不直接批量给旧数据加八小时」说的就是这件事）。两者之间需要比较时经 `BusinessClock.toAuditClock` 换算。
- 号源与目录查询原先在 SQL 里用 `CURRENT_DATE`/`CURRENT_TIME`，那是**数据库连接的 JVM 默认时区**，和 Java 侧 `LocalDate.now()` 是两套钟。开发容器是 UTC，北京时间 00:00–08:00 之间，SQL 眼里的「今天」还停在昨天，昨天下午已经过去的时段会被当成可约号源端给老人。三处号源查询（`appointment.querySlots`、`queryUpcomingSlots`、`queryAlternatives`）与 `CareCatalogRepository.availableDates` 全部改成绑定业务时钟参数。
- `FollowupAgentService` 里 17 处 `LocalDate.now()` / `LocalDateTime.now()` / `MemoParser.nowInDemoZone()` 改走注入的时钟：上下文构建、日期范围校验、取消范围里「最近一次」的挑选、取消日期、出行改期、历史预约挑选、过期日期判断、备忘「现在」、可选日期。
- 每轮提示词新增【本轮运行信息】：当前日期（含星期与 ISO 写法）、现在时间、时区（中英文名）。日期取 `context.currentDate()`、时刻取同一个时钟，两边同源，不会出现「提示词说今天是 9 号、Java 按 8 号校验」。同一节写明：相对日期（明天、下周三、下个月）由模型自己按这个基准推算，算出来已经过去时**不许自己往后推年份**，用 `ASK_USER` 复述后请老人确认；今天已经过去的时段不是可预约时间。`AgentContext` 刻意不加字段（4 参 / 5 参构造一个没动，18 个 Spring 上下文零影响）。
- `RuleFactExtractor` 删掉两条「过去日期悄悄顺延到明年」的规则：「3月5日」一律按今天所在的这一年理解，原样返回，改由上层请老人重新说。替老人把 3 月 5 日定到明年，就是把一次询问换成一次八个月后的错约。
- 今天已经过去的时段不再可约，闸门是同一条判断的两个位置：建确认卡时、以及**确认时**。后者是关键——确认卡上没有时间闸门，老人可能隔夜才按确认，08:00 建的 09:00 卡到 10:30 就不该还能提交。被挡住时退回重选日期，医院、科室、陪同、出行这些已经问过的信息都留着。
- 两道口径必须与号源查询一致：**等于此刻也算过期**（查询用的是 `appointment_time > 当前时间`，差一毫秒都查不出来），所以判断写成 `!isAfter(now)` 而不是 `isBefore(now)`。确认时那道门**只挡「照草稿开新预约」**（建新预约/改期）：办完预约的会话仍留着当初那份 `selectedSlot`，拿它去拦取消，会让老人先重选一遍日期才能取消一条跟那个时段无关的预约（评审发现，见下）。
- 确认卡的绝对日期不因跨午夜改变：卡上存的是选号那一刻的 `Slot`（绝对日期 + 时刻），`confirm` 写的就是它，不重新解读「明天」。新增用例把这条钉住（23:59 建卡、00:01 确认，落库仍是 17 号 09:00）。
- 全库只找到一处跨钟面比较：`HealthReportService.weeklySentThisWeek` 的「本周一零点」是业务钟面，而 `family_notifications.created_at` 是审计钟面，改经 `toAuditClock` 换算。历史时间字段的语义逐个核实过：全部是审计钟面，只补注释不改写法；`data.sql` 的 `CURRENT_TIMESTAMP` 种子照旧。
- 新增配置写进 `compose.yml` 与 `.env.example`（`BUSINESS_TIME_ZONE`，默认上海）；`health-report.weekly.zone` 默认跟着它走。
- Dev Container 验证（2026-09-13，`/workspace/backend`）：完整回归 **341 → 361 项全部通过**（新增 20：业务时钟 5、相对日期 8、业务时间线 4、提示词时间 3），固定时钟覆盖明天/跨月/跨年/上海凌晨/今天已过时段/跨午夜确认卡。另在 `:8099` 用内存库单起一个后端冒烟：`/api/demo/health` 与 `POST /api/demo/scenarios/normal` 正常，演示日期与真实今天一致；把 `BUSINESS_TIME_ZONE` 设成非法值启动则明确失败（`Unknown time-zone ID: Not/AZone`），证明这个配置真的被读进 bean。用户自己跑在 `:8080`/`:3000` 的实例未受影响。
- 评审修复（2026-09-13，同一分支，未提交）——三项发现全部修掉，另把两处「配置改了不生效」的生产入口接上业务时钟：
  1. `confirm()` 里那道过期时段检查原先排在 `CANCEL_EXISTING` 之前，完成过预约的会话又留着完整 `selectedSlot`，于是「预约时间过去后再确认取消」会被错误送回选日期。加 `booksFromDraft(state)`（`pendingAction` 为 `null` 或 `CREATE` 才拦），取消类一律放行。
  2. `business.time.zone` 补上两个生产入口：`DemoScenarioService` 改注入 `BusinessClock`、用 `checkupDate(clock.today())`/`dinnerDate(clock.today())`（原先调无参静态版，钉死在 `DEFAULT_ZONE`，改了配置后步骤文案里的日期会和真正播种进 `user_schedules` 的日程对不上）；`MemoParser` 新增带业务时间锚点的重载（`detect(msg, now)`、`pastWeekdayDate(value, today)`、`resolveRemindAt(..., now)`、`resolveRepeatAnchor(..., today)`、`resolveDay(value, today)`，私有的 `pastWeekdayMention`/`remindAtOf`/`dayOf` 同步收锚点），`FollowupAgentService` 的 9 处生产调用改传 `clock.now()`/`clock.today()`。旧的无参重载全部保留、语义不变，继续给单元测试兜底（`DEMO_ZONE` 现在只服务这些兜底路径，注释已改写）。
  3. `slotAlreadyPassed` 改成 `!isAfter(clock.now())`，与号源查询口径对齐。
  - 同步修正 `application.yml` 里「静态方法也读取配置」的错误注释：无参静态日期方法钉在 `Asia/Shanghai`，不读配置；生产路径（含 `DemoScenarioService`）走注入的时钟。
  - 新增回归 **5 项**（`BusinessTimeFlowTests` +4、`MemoParserClockTests` +1），每条都先验证过「去掉修复即变红」：取消已完成预约不得被过期时段拦成 `ASK_DATE`（实际拿到 `CANCELLED`）；等于此刻的号源不得落库（`ASK_DATE`）；演示场景步骤日期跟着业务时钟（09-16 的「下周三」= 09-23，按真实系统时钟算会是 09-16）；「明天早上八点」的备忘按业务时钟存成 09-17 08:00（去掉锚点会存成系统时钟的 09-14 08:00）；锚点重载直接决定「今天」（钉 2026-09-14/09-17 两个与真实今天无关的锚点）。
  - Dev Container 验证（2026-09-13，`/workspace/backend`）：**只跑受影响的时间、取消与备忘专项 9 个类共 117 项，全部通过**（业务时间线 8、业务时钟 5、相对日期 8、提示词时间 3、取消范围 4、备忘解析钟点 34、备忘流程 45、备忘命令 5、演示场景 5）。按评审要求本轮未机械重跑 361 项完整回归；改动为纯追加（新增重载、Spring 注入的构造参数），`mvn test` 会先编译整个测试树，编译已通过。
- 未解决 / 留给评审：
  - `MemoParser` 里备忘提醒的「过去年份顺延」逻辑**这次仍未动**（600 行静态解析器、测试耦合重，而且它本来就用 `Asia/Shanghai`），要不要一起改需要单独评估。取「现在」那一处已按上面的评审修复接入业务时钟。
  - `RollingUserScheduleInitializer` 的**无参**静态日期方法（`checkupDate()` 等）仍固定跟着 `BusinessClock.DEFAULT_ZONE`，不随 `business.time.zone` 覆盖走——静态方法没有注入点。生产调用方 `DemoScenarioService` 已改走注入时钟；这几个无参方法现在只剩「不启动 Spring 的单元测试」在用。
  - 审计钟面留下的已知窄口径：`memos.created_at` 按 JVM 默认时区写，而长驻备忘在页面上**按月分组**（`memo-list-view.tsx` 用 `createdAt`）。于是北京时间月初 00:00–08:00 记下的备忘会被分到上个月。不改的原因见 DEC-019：改了会让新旧行混用两种语义。要不要给这一处单独做展示层换算，留待评审。

## 2026-09-13 老人端界面调整（未提交）

- 分支 `refactor/application-packages`，本阶段**只动老人端界面**，不碰模型路由、业务执行和全局语音导航；界面调整一律不改写库路径。
- 朗读设置从助手页搬到「我的」：助手页不再有自己的朗读设置块，语速只有一个权威来源（`app/page.tsx` 的 `voicePreference`）写回后端偏好，助手页、事项页、地图页、气泡喇叭读的都是它。`TtsSettings` 的入参从整个 `VoicePreference` 收成 `speechRate` 一个数字，就是不让它再管自动朗读开关。
- 助手页的「继续办理」从两颗收敛成一颗：办理大卡改成紧凑摘要（当前复诊办理 / 进行中 / `市第一医院 · 心内科 · 2026年9月15日` / 取消本次办理），恢复办理用顶部操作区那颗按钮（有任务时它就叫「继续办理」）。原大卡里那颗重复的「继续办理」删掉，但**没有**留下「点了取消就直接删数据」的捷径——取消仍旧只发一句话给助手，照常走确认门禁。
- 页头固定的根因不在助手页，在 `MobileShell`：那层 `overflow-hidden` 会把它自己变成滚动容器，而它的高度跟着内容长、自己永远不滚，于是**所有老人页**的 `sticky top-0` 都没有可粘的余量。改成 `overflow-x-clip`——保留横向裁切的本意，但不产生滚动容器。助手页原来自己写死 `sticky top-[76px]` 的次级栏一并并进 `PageHeader` 的 `children`，大字模式下标题行变高也不会露出缝。
- 预约记录默认只摆最近三次即将到来的复诊，其余按「过去的复诊 / 已取消的预约」分组，展开后才出现；分组在读到数据时算一次并连结果一起存进 state（渲染期不读时钟，顺带让分组在停留期间不自己跳）。「即将到来」只认 `status=CONFIRMED` 且时间未到——拿一条过期记录冒充「即将复诊」比少显示一条更坏。展开/收起的判据与「现在是展开还是折叠」无关，否则全部记录都是「即将到来」时展开后按钮会把自己藏掉（见 PITFALLS）。
- 开场麦克风提示气泡每次新进入老人端显示一次，应用内部切页不再弹；点它、点别处、按住麦克风都会收起，并同时掐掉正在播的提示音。气泡念不念由「自动朗读」开关决定（开着才念），念的时候用同一份语速与音量。
- 后端只改一行：`task.summary` 的日期从 ISO 改成 `DATE_LABEL`（`2026年9月15日`）。理由是这行字原样出现在老人端的办理卡上，`2026-09-15` 太像编号；改的是这一行的呈现，`plan.date` / `result.date` 本来就是中文，现在三处同源同格式。
- Dev Container 验证（2026-09-13，`/workspace`）：后端完整回归 **341/341 通过**；前端 `npm run build` 通过；`oxlint` 与 HEAD 基线逐条比对**未引入新命中**（仍是那 4 条既有命中，仅行号位移）。页面用 Playwright 的 chromium 按 390×844 手机视口实检 **39/39 通过**（气泡、朗读设置、页头固定普通/大字、预约记录默认三次与展开收起、真模型跑一遍预约流程后的紧凑卡），另跑一组边界数据 **5/5 通过**（10 条全是「即将到来的已确认」时展开后仍能收起）。
- 未解决：`mvn -o test` 在一次运行里出现过 218 个 `ApplicationContext` 报错（表现为 `USER_SCHEDULES` / `APPOINTMENT_SLOTS` 表不存在），重跑与在 HEAD 基线上重跑都是全绿，**没能复现，原因未知**。可以排除的方向：全部 18 个 `@SpringBootTest` 都显式覆盖成 H2 内存库，测试不与开发服务器共用 `backend/data/silver-agent` 文件库，两边不存在争用同一个数据库文件这回事。

## 2026-09-12 自然语言确认与批量取消预约（未提交）

- 新增独立的 `CALL_CONFIRMATION_TOOL` 模型动作，以及 `interaction.requestConfirmation`、`interaction.respondConfirmation` 两个确认工具。模型把自然语言归一成结构化范围或确认决定；Java只查真实预约、维护凭据和执行门禁。
- `requestConfirmation` 支持 `ALL`、`DATE_RANGE`、`SINGLE_FILTER`、`AMBIGUOUS`；模型模式不再依靠Java中文词表判断取消范围。`respondConfirmation` 只接收 `CONFIRM/DENY`，confirmationId由Java注入。
- 确认卡出现后再次提出日期、范围或对象会作废旧卡、重新查库并生成新卡；`CANCEL_APPOINTMENT` 不再自动等同于确认。批量取消仍先校验全部预约存在、属于当前用户且为已确认状态，再以单事务执行。
- 取消查询中的无年份月日按当年已有预约匹配，不复用新建预约“过去日期顺延到明年”的规则。
- 前端确认卡对取消动作使用红色确认按钮、绿色保留按钮，并保留清晰文字标签。
- 模型话术只允许作卡片前面一句**不提业务事实**的开场白；卡片标题改由 Java 固定生成（单条「是否取消这次复诊预约」，批量「是否取消这N条复诊预约」，N 取自真实预约对象）。闸门是「这句话不许提业务」这条结构性约束——出现业务词、数字或完成态说法就整句丢弃——而不是一张穷举不尽的完成态黑名单（黑名单被“已经帮您取消好了”击穿过，见 PITFALLS）。
- 原中文日期和范围解析只保留给模型不可用时的兼容降级路径，不参与模型成功工具调用后的范围决策。
- `AgentRuntime` 对「模型编了执行不了的工具」做受控回退：绝不执行，也不再静默降成一段没有卡片的回答。intent 能归到既有 Java 工作流就走那条流程，归不到则由新增的 `Route.REFUSE_UNSUPPORTED_TOOL` 明确回绝（不用 `DIRECT_ANSWER`，那条路会 `pauseActiveTask`，把正在办理的流程停掉）。
- 结构化通道最薄弱的一环是 `scope=ALL`：Java 不再拿关键词复核“用户这句话配不配全选”，防线落在确认卡上。`CancelScopeTests` 新增一条把新防线写下来的用例——模型对没圈定范围的话硬答 `ALL` 时，过宽的范围逐条列在卡上，且确认之前库里一条都不动。
- Dev Container 验证（2026-09-12，`/workspace/backend`）：确认与取消专项 `VoiceFirstP0Tests` 17 + `AgentRuntimeRoutingTests` 13 + `ConfirmationInteractionToolTests` 6 + `CancelScopeTests` 4 = 40/40 通过；完整后端回归 **341/341 通过**（0 failure、0 error、0 skipped）。
## 2026-09-12 新增四个演示场景的工作流文档（未提交）

- 新增 `docs/13-demo-scenarios-workflow.md`，编号接在 12 之后（10 号已随 `10-innovation-assessment.md` 并入设计思路报告而退役，不复用）。
- 内容为命题要求的四个演示场景在当前源码里的真实路径：场景一走 `advance` 直线补问 → `querySlots` → `checkSchedule` → `checkDuplicate` → `buildConfirmation` → `confirm`；场景二的分支在 `querySlots` 的 `NO_SLOT` 段，「附近日期」口径在 `MockAppointmentTool#queryAlternatives` 的 `date+1..date+3`；场景三的冲突由 `RollingUserScheduleInitializer` 的「社区体检」10:00–11:00 与工作日 10:30 号源必然相撞造出；场景四的判定收在 `MedicalBoundaryRules`，回复由 `medicalBoundary` 装配并**原样交回确认卡**。
- 同时登记到 `00-reading-order.md` 第 13 条与 v0.2 导航段。
- 文中记录一条与需求目标的差距：`SafetyGuard#precheck` 的紧急表达词表只在模型不可用时执行，模型模式下紧急判定依赖主模型的 `EMERGENCY` 分类，越界那侧有规则兜底、紧急这侧没有。对应 Design.md 的 P0「高优先级规则前置」尚未闭合，演示紧急场景前需实测模型输出。
- 该文档为纯说明，未改动任何代码与接口，不涉及前后端字段同步。

## 2026-09-11 会话生命周期、实时办理过程与长期记忆（未提交）

- 分支 `zhaotingfang_model-tool-loop-v2`，接在上一段多模态移植之后，功能增量全部围绕「评审要看见真实过程」和「老人能自己收尾」两件事。
- 会话生命周期：`conversation_sessions` 加一列 `status`（`ACTIVE`/`CLOSED`/`EXPIRED`）。`status` 是**列不是 `state_json` 字段**，所以旧会话快照照常反序列化；`ConversationStore.save()` 用的是显式列 `MERGE INTO ... KEY(id)`，加列不碰快照。`ConversationLifecycle` 只做标记不做清理，空闲超时把会话标成 `EXPIRED` 但不丢草稿——老人过一会儿再说话照常继续，待确认的卡也还在。
- 历史记录：`GET /api/agent/conversations`（只带六个列表字段，不读 `state_json`）、`POST /api/agent/conversations/{id}/close`（204，幂等）、恢复会话多返回 `status`。已结束的会话能翻看但写入一律被拒，且**拒绝刻意不落库**——落库的话，一个还开着确认卡的老页面每重试一次就多一条一模一样的「已经结束」，真正聊过的内容反倒被复读淹掉。
- 前端：历史记录浮层（相对时间、已结束徽标）、「新对话」按钮（先关旧的再建新的）、只读态横幅。刷新后用 `localStorage` 恢复上次会话，但刻意不恢复 `CLOSED` 的，不让人一进来就掉进一个打不了字的页面。
- 实时办理过程：新增 `TurnProgress`（内存、按会话、有界 40 条 / 500 会话、5 分钟无事件即不算进行中）与只读端点 `GET /api/agent/conversations/{id}/progress?afterSeq=`。事件分「理解 / 决定查什么 / 模型提出工具调用 / 工具真实返回 / 整理回答」五种，`parameters` 是模型真实生成的那份。前端在「查看办理过程」卡片上轮询增量渲染，跳动的步骤条，结束后自动收起。
- 脱敏在读取时做一次：图片 data URL 换「（图片内容已省略）」、手机号变 `138****1234`、`sk-` 串一律 `sk-***`、单字段截断 600 字。内存里保留原始值供排查，HTTP 出去的一律是脱敏后的。
- 长期记忆：`user_memories` 表（主键 `(user_id, memory_key)`）+ `MemoryStore`。**写入口只有一处**——确认门禁放行、预约真的落库之后，记下常去的医院、科室、习惯时段。草稿阶段一个字都不记；模型和前端都够不着这个写入路径。同一个 key 只留最新一版（换医院就覆盖，不堆历史，否则两版偏好同时进提示词）。`digest()` 无记忆时返回空串，提示词与没有这个功能时逐字相同。`GET`/`DELETE /api/agent/memories`，「忘掉」是软删除。
- 长期记忆前端露出：「我的」页新增「助手记住的事」，整段摆出来、逐条可忘。忘掉做成两问（「确定忘掉 / 再想想」）——不可逆的动作，老人手抖一下不该就没了。页面写明「助手只是拿它们少问您一句，真要办什么还是您点过确认才算数」。
- 取消预约入口：事项页新增「取消这次复诊」按钮，但它**不调任何取消接口**，只是把「我想取消这次复诊预约」交给助手页发出，仍旧走查询 → 确认卡 → 确认端点。入口可以多，写路径只有一条。
- 测试：后端 270 → **294 项**全部通过（新增会话生命周期与历史 7 项、长期记忆 7 项），前端 `tsc` 与生产构建成功，代码规范检查未引入新命中——改动过的文件逐条比对过 HEAD 的结果，**两个本次新增的文件单独补做**（首次比对漏了未跟踪文件，见 PITFALLS「无新增告警的比对漏掉了全新文件」）。`str(unknown)` 收敛了工具返回值里可能出现 `[object Object]` 的拼接；`camera-capture` 的开流逻辑改成链式回调，顺带补上「三路摄像头都打不开时放掉 ready」——原来那种情况快门按钮还是亮的。

## 2026-09-11 移植余柔欣分支的多模态能力（未提交）

- 分支 `zhaotingfang_model-tool-loop-v2`，取 `origin/yurouxin` 的能力模块接到我们的智能体结构上，**不动确认门禁**：她的 `confirmationId` 与 `ActionRequest.label` 被删过，`AgentController`、`agent-api.ts`、`FollowupAgentService`、`assistant-view.tsx` 一个都没对拷。
- 后端：新增 `DashScopeHttp` 统一超时/退避重试/解析；`VlService`/`AsrService`/`TtsService` 与三个状态接口；`POST /api/agent/images` 走 `chatInternal`，图片落 `conversation_attachments`、结论落 `vision_results`；只读工具 `drug.queryKnowledge`（数据来自 `drug-knowledge.json`，16 条）与意图 `QUERY_DRUG`；`AgentContext` 加 `vision`（保留 4 参构造，无图时提示词逐字不变）。
- 前端：`lib/asr-tts-api.ts` 串后端三模态；`lib/local-speech.ts` + `lib/tts-player.ts` 取代 `lib/speech-service.ts`（按 key 切换、本地优先云端降级）；`use-press-to-talk` + `level-meter` + 语音气泡（回放原话、上滑取消）；`camera-capture` + `image-compress`（canvas 双档压缩）接到助手页与材料清单。
- 材料拍照确认：`photo_url` 是 `VARCHAR(500)`，装不下 base64，所以照片本体进附件表（`kind='MATERIAL_PHOTO'`，归属键 `appointment:<id>`），该列只写 `attachment:<id>`；`H2MaterialPreparationTool` 新增护栏（状态仅三种、`confirmSource` 归一 `USER`/`PHOTO`、非图片与超限照片拒绝）。
- 材料照片回看：新增 `GET .../materials/{materialId}/photo`，清单里拍过照的行多一个「看照片」按钮。归属校验收在 `ConversationStore.findMaterialPhoto` 一处，`attachment:<id>` 引用每次读都要连带核对 `conversation_id` 与 `kind`——引用是客户端回传的，不核对就能指向别人的照片（`PATCH` 回传引用的那条路也按同一规则校验）。
- 测试：后端 227 → **270 项**全部通过（新增药品工具 11、多模态落库 11、材料拍照与回看 12、识图解析与路由若干），前端 `tsc` 与生产构建成功。

## 2026-09-11 文档 v0.2 同步协作照护端（提交 b27cfe5 之后）

- 功能已由 `feat: 照护端助手接入真实智能体，会话身份拆成能力轴与数据轴`（b27cfe5）落地，本次补齐文档侧。
- 两份比赛正文同步到 v0.2：`报名解决方案.md`、`设计思路报告.md`。核心增量是双轴会话身份（能力轴＝谁在操作，数据轴＝服务哪位长辈）、两端共用同一个智能体与同一道确认门禁、健康记录与健康备忘、代约归属与服务对象标注。
- `设计思路报告.md` 修正「当前尚未实现独立家属端」等已不成立的表述，工具数由「八项」改为 16 个只读工具（14 个两端通用 + `care.timeline`、`care.notifications` 仅家属/志愿者可见），测试数由 35 项更正为 227 项。
- 同步更新的次要文档：`00-reading-order.md`、`01-requirement-mapping.md`、`02-pages-and-interactions.md`、`03-agent-workflow.md`、`04-architecture-and-modules.md`、`05-api-contracts.md`、`06-mock-data-design.md`、`09-demo-acceptance-checklist.md`、`11-agent-architecture-and-controlled-tool-calling.md`、`user-manual.md`。
- `12-voice-first-p0-plan.md` 为历史任务书，加状态说明：P0 已实施完成、P1 主要项已落地，并标注其「已核实代码事实」表已漂移（`new AgentTurnResponse(...)` 由 11 处增至 16 处，硬编码完成回复已移除）。
- `08-beginner-implementation-guide.md` 去掉个人绝对路径，改为占位符，并注明覆盖范围为 v0.1 骨架。
- 验证：后端 227 项自动测试全部通过（0 失败 0 错误），前端类型检查与生产构建成功；文档内术语（代约归属、服务对象、双轴、16 个只读工具、227）已全量比对一致，未含 API Key、完整手机号或个人绝对路径。

## 2026-09-11 单一主模型同轮只读工具循环

- `ConversationPlanner` 与 `AgentRuntime` 新增工具结果续跑；真实结果返回同一个主模型，模型可在同一用户轮次内继续选择只读工具或回答。
- `FollowupAgentService` 增加最多 3 轮的只读工具循环和同名同参去重，复用既有无号、附近日期、冲突、重复预约、模糊匹配及合法按钮处理。
- 确认卡、完成、部分完成和紧急暂停立即停止循环；提交预约、取消、提醒和通知仍不开放给模型直接执行。
- 模型续写超时或解析失败时直接保留最近一次权威工具结果，避免重复查询或丢失候选项。
- Dev Container 验证：针对性测试 15/15 通过，覆盖无号结果回传模型和两项连续只读工具调用；全量后端测试 72/72 通过，0 failure、0 error、0 skipped。

## 2026-09-10 设计思路报告 v0.1

- 按命题规定的需求分析、用户角色、智能体架构、任务拆解、工具、确认、异常、适老化、安全、创新和应用价值重写唯一主稿。
- 报告同步受控混合智能体、对话任务双状态、办事知识库、院外路线与院内楼层诊室等当前实现，并将材料识别、备忘、家属端和评委视图区分为后续规划。
- 新增架构、任务状态和受控工具调用图；补充 35 项后端测试、前端类型检查和生产构建等验证证据。
- 采用“Markdown 主稿加版本化 PDF”的协作方式，停止维护两份重复正文；报名解决方案、后续 PPT 和录屏共享同一事实源。
- 根据交付用途将文档拆分为 `报名解决方案.md` 和 `设计思路报告.md`：前者回答项目如何解决命题与如何推广，后者说明具体设计和工程机制，二者不再混用标题与结构。

## 2026-09-09 对话任务解耦与出行院内指引

- 新会话默认自由交流；明确预约目标后创建 `TaskStatus.ACTIVE`，中途聊天改为 `PAUSED` 并保留原流程节点，取消草稿后清除任务标记。
- 安全 `ANSWER` 与不明确澄清不再因缺少 `SUPPORT/SMALL_TALK` 标签被强制退回当前槽位。
- 新增 `clinic_locations`、号源诊室关联、模拟坐标、路线距离/步骤/折线，以及 `travel.routePlan`、`hospital.locationGuide`。
- 新增统一“院外路线 / 院内指引”页面；事项页为主要入口，首页和助手结果卡提供快捷入口；可选高德底图，无 Key 时使用模拟地图。
- Dev Container 验证：后端 35/35 测试通过；前端 TypeScript 检查与生产构建成功。

## 2026-09-09 受控混合智能体第二阶段与办事知识库

- 新增 H2 `care_guide_articles` 与只读 `careGuide.search`，提供复诊流程、到院步骤、咨询渠道和改期说明；不存储诊断、用药或治疗结论。
- `PlannerDecision` 支持最多三个只读工具建议；已实现“复诊流程＋材料”一次批量查询，参数和结果进入工具轨迹。
- 增加 `EXPLAIN_PROCESS`、`HEALTH_CONCERN`，补齐“退掉/撤销预约”等口语；身体不适进入医疗边界，不再询问年龄和病史来推断疾病。
- 普通聊天直接返回已通过安全检查的规划模型回复，取消重复回答模型调用；紧急和医疗边界使用 Java 权威回复。
- 模型连接与读取增加可配置超时，规划及回答失败会记录警告后安全回退。
- Dev Container 验证：后端 32/32 测试通过，0 failure、0 error、0 skipped；真实模型联调中流程查询约 3.7 秒、流程加材料约 3.0 秒、身体不适边界约 0.6 秒、口语取消约 3.5 秒。

## 2026-09-09 智能体架构与受控工具调用方案

- 新增面向初学者和设计评审的智能体架构文档，解释模型、工具、中控、状态、上下文及模型工具调用循环。
- 对比聊天机器人、任务型状态机、工作流图、工具调用智能体、多智能体和受控混合智能体，并记录各自适用场景与取舍。
- 明确当前第一版为“模型理解与表达加 Java 固定编排”，建议后续只对低风险只读工具逐步开放模型自主选择，写操作继续由 Java确认门禁控制。
- 给出权限矩阵、目标 Java 模块、端到端案例、上下文策略、比赛需求映射、优缺点、分阶段改造计划及实施注意事项。

## 2026-09-09 受控混合智能体第一阶段落地

- 新增 `ConversationPlanner` 与 `PlannerDecision`，模型不再只输出单一 intent，可提出直接回答、自然补问、白名单只读工具或工作流动作；模型失败时回退规则规划器。
- 新增 `AgentRuntime`、`ToolRegistry`、`ToolPolicy` 和 `ActionValidator`。只开放医院、科室、号源、本人预约和材料查询；写操作仍进入原 Java 确认门禁。
- 支持性对话由模型直接生成受约束草稿并保持对话模式，回答模型不得擅自追回医院、科室和日期。
- 确定性安全规则补齐“心口疼、胸口疼、心前区疼、胸闷”等表达，命中后使旧确认失效并进入 `EMERGENCY_PAUSED`。
- `model-status` 新增受控混合架构与规划模式标识，前端响应结构未改变。
- Dev Container 验证：后端 26/26 测试通过。真实模型联调验证连续支持性聊天不会追回医院；“心口疼怎么办”暂停普通流程；“查询我的预约”由模型提出 `appointment.queryMine`，Java 放行并返回数据库结果。

## 2026-09-09 开发容器模型配置自动加载

- “Dev Container: 后端服务”任务启动时自动加载本机 `.devcontainer/.env`，未配置时仍安全回退到规则/模板模式。
- 新增可提交的 `.devcontainer/.env.example`；真实 `.devcontainer/.env` 保持 Git 忽略，队友各自配置，不共享密钥。
- README 与开发容器文档已区分 VS Code 开发配置和根目录 docker compose 部署配置，并补充重启与状态检查方法。

## 2026-09-08 可替换模型理解与回答流程

- 新增通用 `ModelGateway` 与 OpenAI-compatible 适配器，模型配置改为 `AGENT_MODEL_*`，业务层不再依赖具体厂商类名或环境变量。
- 模型职责由“只抽取字段”扩展为“理解意图、字段和情绪 + 根据 Java 权威事实生成自然回答”；调用失败分别回退本地规则与回答模板。
- 新增 `SafetyGuard`、`DialogueService`、`ReplyContextBuilder`，把安全前置、支持性交流和回答上下文从 `FollowupAgentService` 抽离。
- `CANCELLED` 只停止当前办理和旧操作，不再封锁支持性交流、普通聊天、查询事项与新建办理；业务阶段与对话模式分开保存。
- 增加取消后聊天、流程中支持性交流、模糊不适澄清和回答越权回退测试；已在 `silver-followup-dev` Dev Container 内执行 `mvn test`，22/22 通过（0 failure、0 error、0 skipped）。

## 2026-09-08 设计思路报告

- 新增 [设计思路报告](../设计思路报告.md)，覆盖需求分析、用户角色、智能体架构、任务拆解机制、工具设计、确认机制、异常处理、适老化设计、安全边界、创新点和应用价值。
- 依据需求和当前源码整理，附架构图、任务依赖表、工具表和异常处理表，区分模拟能力、实现边界与后续规划；新增阅读入口。
- 本次仅修改文档，未修改业务代码及接口，未运行项目测试或构建；历史容器验证结果仅作为既有记录引用。

## 2026-09-07 创新性评估与公开资料调研

- 新增 `10-innovation-assessment.md` 创新性与价值评估，对照源码、命题要求和八项公开资料，区分应用/工程亮点、已有行业能力及未实现建议。（该文件后已并入 `设计思路报告.md`，此链接不再有效。）
- 结论：当前有应用整合和可靠性实现价值，尚无原创算法、行业首创或真实用户收益的充分证据；优先验证变更解释与故障补办交互，其次考虑家属回执协商和适老出行约束。
- 报告记录源码证据、来源链接/日期、价值边界、候选方向和对照评测方法；新增阅读入口。
- 验证范围：本次仅静态审阅、公开资料检索和文档检查；未运行项目测试或构建。已有容器 16/16 测试及构建成功记录属于下方历史验收，本次不重复宣称重跑通过。业务代码与接口未修改。

## 2026-09-07 设计审阅与需求对齐

- 新增 [Design.md](../../Design.md)，以 [Requirement.md](../../Requirement.md) 为基线，记录当前模块、实际流程、逐节差距、目标状态机、确认/执行设计及验收优先级。
- 更新文档入口和早期设计的状态说明；修正 README 与模拟数据库文档中“每次启动清空”的过期描述。
- 下方历史 `DONE` 仅保留当时进度，不能视为当前需求全部验收通过。确认版本、紧急暂停、取消终态、部分失败恢复、完整计划复核仍有缺口。
- 本次仅修改文档，业务代码及接口未修改。
- 验证：历史规则模式测试共 3 项，1 项通过、2 项失败、0 项错误。正常/无号场景实际停在 `ASK_HOSPITAL`；测试医院名称与规则目录匹配不一致，正常测试还缺少当前流程步骤并使用旧通知工具名。详见 Design.md 第 5 节。
- 未进行浏览器交互和录屏验收；不将静态代码审阅当作端到端通过。

## 状态说明

### 2026-09-07 用户资料页数据源修正（容器验收通过）

- 设置页原写死“张阿姨/女儿/138****1234”，与 H2 演示用户“王阿姨/小丽（女儿）”不一致；`GET /api/users/{userId}` 返回体新增可选 `contacts`（脱敏电话），设置页改读后端。
- 结果卡出发时间在路线信息不足时返回明确占位文案（非空字符串），前端渲染加 `??` 兜底。
- 确认 `loadPrimaryContact` 为从未调用的死代码，记录到 PITFALLS，不删除、不影响现有行为。
- 接口变化与前端类型同步见 `INTERFACE_CHANGES.md`、`05-api-contracts.md`。
- 容器内验收：`mvn test` 16/16 通过；修复损坏的 `frontend/node_modules`（Docker 磁盘 I/O 损坏致 7452 个空文件）后 `npm run build` 成功；实测 `GET /api/users/user-001` 返回王阿姨与脱敏家属，助手对话主流程正常。

### 2026-09-07 需求对齐实现（待容器验收）

- 修改确认、终态、改期、偏好、部分完成补办及计划展示；接口变化见 `INTERFACE_CHANGES.md`。
- 增加至 16 项回归测试，涵盖会话重载补办、并发确认、改期失败保留原预约、缺路线恢复。
- 开发与验证环境限定 VS Code Dev Container，停止使用宿主机 Maven/npm 构建。本机尚未找到项目容器，等待连接信息；容器测试、前端构建和页面验收未完成。
- Dev Container 补齐 Maven 和工作目录，隔离 Linux 前端依赖及后端编译产物。

- `TODO`：尚未开始。
- `DOING`：已经有人负责并正在开发。
- `BLOCKED`：存在明确阻塞，必须写原因。
- `DONE`：满足完成定义并已合并到主分支。

## 功能清单

| 功能 | 状态 | 负责人 | 相关目录 | 验证结果 | PR/Commit | 备注 |
|---|---|---|---|---|---|---|
| 项目骨架 | DONE | Codex 初始生成 | `backend/`、`frontend/` | 前端构建通过 | 待首次提交 | Java + React 模块化单体 |
| 会话接口 | DONE | Codex | `backend/api/` | 创建会话、消息、确认接口已连接前端 | 待首次提交 |  |
| 信息收集工作流 | DONE | Codex | `backend/agent/`、`backend/application/` | 一次一问、规则回退、服务边界已实现 | 待首次提交 | 可替换大模型可选 |
| Agent 中控路由 | DONE | Codex | `backend/application/AgentOrchestrator.java` | 自然语言支线、确认、安全边界和主流程统一路由 | 待提交 | 支持打断后返回原节点 |
| 我的预约查询/取消工具 | DONE | Codex | `backend/domain/tool/MyAppointmentTool.java` | 隔离 H2 完成查询、指定日期取消确认与保留预约验证 | 待提交 | 结果来自 appointments，不读对话草稿 |
| 会话页面生命周期 | DONE | Codex | `frontend/app/page.tsx`、`frontend/features/assistant/` | 页面内切换保留，刷新或重新打开创建新会话 | 待提交 | 后端历史仍保留用于审计 |
| 预约工具 | DONE | Codex | `backend/domain/tool/` | 查询、替代日期、提交、取消均访问 H2 | 待首次提交 |  |
| 日程工具 | DONE | Codex | `backend/domain/tool/` | 冲突查询和提醒写入 H2 | 待首次提交 |  |
| 出行工具 | DONE | Codex | `backend/domain/tool/` | 根据交通方式倒推出发时间 | 待首次提交 |  |
| 家属通知工具 | DONE | Codex | `backend/domain/tool/` | 联系人查询和模拟通知记录已实现 | 待首次提交 |  |
| 材料清单 | DONE | Codex | backend、frontend/tasks | 清单与必带标记均来自 H2 | 待提交 | 不再按前端下标判断必带 |
| 材料准备状态持久化 | DONE | Codex | backend、frontend/materials | 事项页与助手页共用数据库状态，刷新不丢失 | 待提交 | 已预留拍照确认状态 |
| 语音自动播报 | DONE | Codex | backend/preferences、frontend/speech | 设置写入H2，只朗读新助手回复，可手动停止 | 待提交 | 历史恢复不自动重播 |
| 确认门禁 | DONE | Codex | `backend/application/` | 提交、取消、提醒和通知只在确认端点执行 | 待首次提交 |  |
| Agent工作台 | DONE | Codex | `frontend/features/assistant/` | TypeScript 检查通过 | 待首次提交 | 对话、计划、确认、结果和工具日志已拆组件 |
| 最终事项卡片 | DONE | Codex 初始生成 | `frontend/features/tasks/` | 页面构建通过 | 待首次提交 | 含材料、出发与通知状态 |
| 正常场景 | DOING | 待联调 | 全链路 | 代码路径已完成 |  | 需在 IDEA 启动后端验收 |
| 无号源场景 | DOING | 待联调 | 全链路 | 9月19日无号，返回附近日期 |  |  |
| 冲突场景 | DOING | 待联调 | 全链路 | 10:20 与社区体检冲突 |  |  |
| 越界场景 | DOING | 待联调 | 全链路 | 诊断/用药问题拒绝并建议就医 |  |  |

## 阻塞项

| 日期 | 阻塞内容 | 影响 | 已尝试 | 需要谁协助 | 状态 |
|---|---|---|---|---|---|
|  |  |  |  |  |  |

## 2026-09-03 Agent上下文与真实事项重构

- 自由语言每轮携带状态与最近对话调用可替换大模型。
- 快捷按钮改为结构化action，不再重复调用模型。
- 四类工具拆分，并增加材料清单工具。
- 会话状态、消息和工具轨迹写入H2。
- 首页和事项页移除写死预约。
- 已验证从0条事项到预约完成后1条事项。

## 2026-09-03 会话与多次预约改造

- 聊天会话、消息和最后一张卡片持久化，返回助手页可恢复。
- 后端启动不再清空预约、会话和工具日志。
- 事项页展示个人全部预约记录，重复预约新增记录而不是覆盖。
- 日期确定后先调用预约查询工具，再询问上午/下午并推荐该时段最早号源。
- 模拟出行地址、医院地址和路线耗时均来自 H2。
- 已验证连续两次预约生成两个预约编号，第二次推荐会排除已占用号源。

## 2026-09-06 数据目录与预约时段重构

- 医院按钮从 hospitals 表生成，按钮值使用 hospitalId。
- 科室根据所选医院查询 departments 表，不再展示不属于该医院的科室。
- 日期快捷项根据当前医院、科室的可用号源动态生成。
- 用户已表达上午、下午或具体时间时，查询后直接推荐对应号源，不重复提问。
- 用户姓名、地址、偏好交通方式和家属称呼均从 H2 获取。
- 路线未配置时返回明确工具错误，不再使用虚构地址或默认耗时。
- 事项接口增加 requiredMaterials，前端不再用“前四项必带”的固定判断。
- 已通过 Java 源码编译、TypeScript 检查和隔离 API 全链路验证。

## 2026-09-06 医院推荐与适老化页面调整

- hospitals 和 departments 增加客观介绍、特色标签、适老服务及复诊范围等模拟资料。
- 新增医院资料查询工具和科室资料查询工具，调用参数与结果写入工具日志。
- 智能体可回答“有哪些医院”“有哪些科室”和“你有推荐的吗”，回答后保留原办理进度。
- 推荐只使用数据库中的科室服务范围和适老设施，不作疾病诊断或“最好医院”判断。
- 办理计划每个会话只展示一次；顶部标题和当前步骤固定，人工帮助按钮加大。
- 快捷按钮增加展示标签，恢复聊天记录时不再展示 h001、d002 等内部 ID。
- 已通过 Java 源码编译、前端生产构建和临时 H2 接口验证。

## 2026-09-06 材料状态与语音播报

- 新增 appointment_materials，预约成功后为每份材料生成独立状态记录。
- 历史预约首次读取材料时自动从原清单补齐，不影响已有预约数据。
- 事项页和助手完成卡片使用同一材料接口，支持未准备、已准备和拍照确认三种状态。
- 新增 user_preferences 保存自动朗读、语速和音量；关闭后立即停止播报。
- 语音服务统一管理播放，新回复会停止上一段，恢复历史记录不会自动朗读。

## 2026-09-06 模型意图路由与一个月号源

- 自由语言仍逐轮调用模型理解节点；新增 QUERY_AVAILABLE_SLOTS 意图，由 Java 校验状态后调用预约工具。
- 支持“9.17”“9/17”等短日期，不再沿用上一轮旧日期。
- “有哪些时间可预约”会查询当前医院和科室的真实模拟号源，不再误入医院资料分支。
- 每个启用科室自动维护从今天到一个月后的工作日号源，上午、下午各两个时段。
- 周末保留无号日期，用于展示赛题要求的预约失败、原因说明和附近日期替代方案。
- 已验证：后端构建成功；9月17日返回上午/下午；9月20日无号并提供附近日期。

## 2026-09-06 中控、预约支线与短上下文

- 新增 AgentOrchestrator：模型/规则节点负责理解意图，中控负责安全优先级、任务路由和流程状态。
- 新增 appointment.queryMine 工具，从 H2 查询当前用户已确认预约，支持按日期、医院和科室筛选。
- “取消当前办理”和“取消数据库中的已确认预约”拆成两种意图；取消已确认预约仍必须经过确认卡。
- 查询或取消已有预约会保存原流程节点，支线结束后可选择“继续刚才办理”，恢复到被打断位置。
- 支持查询我的预约、重新开始、继续原任务、修改医院/科室/日期/时间以及自由文本确认/拒绝。
- 模型上下文只携带最近 8 条消息；长期办理事实继续使用结构化 ConversationState，不把整段聊天反复发给模型。
- 前端不再用 localStorage 恢复旧聊天：同一次页面运行中切换首页/事项/助手会保留，刷新或重新打开后显示新会话。
- 助手页不再展示不断累加的工具调用面板；接口只附带最近12条，工具日志仍完整保存在 TOOL_CALL_LOGS，供调试和 Demo 取证。
- 已通过前端生产构建、Java 编译与隔离 H2 API 实测；自动测试受本机 JDK24/Mockito agent 兼容问题影响。

## 2026-09-10 语音优先 P1 流程收口

- 底部中央全局麦克风覆盖首页、事项、助手和出行页面；页面无法本地处理的问题自动转交助手继续对话。
- 用户通过麦克风发起对话时，本轮回复会直接朗读；识别失败改为临时提示，不再反复写入聊天记录。
- 收集完联系人等最后一项必要信息后，系统自动执行只读日程检查并进入确认，不再增加一次“开始办理”。
- 确认阶段的回复和 `speechText` 由 Java 权威状态确定性生成，完整复述日期时间、医院科室、陪同、出行、材料、提醒和通知对象。
- 确认阶段请求打开地图时保留原确认卡和原 `confirmationId`，不允许页面跳转绕过写操作门禁。

## 2026-09-10 模型语义路由收口

- 保持受控混合架构不变：普通语义以规划模型结论为主，Java 中控只做安全、权限和权威状态映射。
- `CREATE_FOLLOWUP` 在已有未完成任务时映射为恢复，在无任务时映射为新建；`RESUME_TASK` 无可恢复任务时明确澄清，不凭空创建。
- 工作流意图即使被模型错误包装成 `ANSWER`，也必须进入 Java 状态审核，不能以自然回复绕过流程。
- 规划提示词按语义理解“继续、接着来、往下办、我要办理、回到刚才”等表达；规则仅在模型不可用时提供同义回退。
- 模型上下文窗口由固定 8 条改为可配置的默认 16 条，并补充被打断阶段、支线任务、待确认动作和确认卡状态。
- 未识别表达不再统一回复“我在听”，而是说明办理进度是否保留并给出明确的继续或新建说法。

## 2026-09-10 异常分支与模糊表达引导

- 医院、科室输入先由模型保留自然语言，再通过真实目录工具解析，避免只靠名称完全相等。
- 支持“市一”“神内”等简称；唯一近似候选必须复述并让用户确认，确认前不写入办理状态。
- “同济医院”等目录中不存在的名称会明确说明查不到，并列出当前系统真实支持的医院；“内科”等多候选表达会列出候选继续追问，不擅自选择。
- 无号源时可自然表达“看看附近几天、换一天、换医院、稍后再查”；日程冲突时可改时间、改日期、改医院或明确保留。
- 明确保留冲突时段只生成最终确认卡，预约写入仍受 confirmationId 和确认门禁保护。
- 后端完整测试共 64 项通过，覆盖正常流程、模糊目录、无号恢复、日程冲突、医疗越界、语音与地图绑定。

## 2026-09-10 单一主模型工具架构重构

- 新增 `AgentSystemPrompt`，规划和工具结果回答复用同一角色、记忆、异常与医疗边界说明。
- 模型可用时，普通意图、页面指令和医疗语义不再经过 Java 关键词快速路由；Java Stage 不再覆盖成功的模型结论。
- 预约信息以结构化草稿累计，支持一轮提取医院、科室、日期、时间偏好、陪同、交通、提醒、通知和家属联系人。
- 工具目录新增医院/科室模糊检索、草稿完整性、附近日期号源、日程冲突和重复预约检查。
- 新增重复预约前置检查：即使号源端再次返回相同时段，也不会生成第二张提交确认卡。
- 普通回答只调用一次主模型；需要工具时由主模型选工具，真实结果再交给同一个主模型回答。模型失败时保留原规则流程作为可用性回退。

## 2026-09-11 家属/志愿者端助手接入同一个智能体

- 家属端与志愿者端的助手不再是演示对话：接 `POST /api/agent/conversations?userId=<长辈>&actorId=<本人>`，与就诊人端同一套工具和确认门禁。
- 支持三类事：查询长辈的复诊安排/材料/就诊动态、代长辈预约复诊（走确认卡）、给长辈留一条提醒（落进长辈自己的备忘，并写明是谁留的）。
- 代约确认卡先写“服务对象：某长辈（您以某身份代为办理）”，代约成功后预约记录记在操作者名下（`appointments.arranged_by` 为操作者），长辈的助手开场会主动告知这次安排。
- 代他人办理时不再问“需要通知哪位家属”：操作者本人就是被通知的那一方，代约本身也会通知其他照护者。
- 长辈已有一份进行中的预约时会先拦下并给出“先取消已有预约”的正路，不再让人填到最后一屏才被拒。
- 后端完整测试 227 项通过，含代办的查询隔离、越权拒绝、代约归属、提醒落点与老人端回归。

## 2026-09-11 演示数据不再写死日期（号源与日程滚动生成）

- 演示号源与日程改为滚动生成：`RollingAppointmentSlotInitializer` 按「今天起一个月内的每个工作日」给每个启用科室排 09:00 / 10:30 / 14:00 / 15:30 四格，id 形如 `r-d001-20260916-0900`；**周末刻意留空**，「指定日期没有号源」这个场景长期可复现。
- `RollingUserScheduleInitializer` 把 `user-001` 的两条日程排在「下周三（社区体检 10:00–11:00）」和「下周六（和家人吃饭）」。口径与口语解析一致（`RuleFactExtractor` 取「本周一 + 1 周 + 2 天」），所以演示话术「我下周三想去市第一医院心内科复诊」正好落在体检那天，当天 10:30 的号源必然被判为冲突，09:00 / 14:00 / 15:30 则是不冲突的对照组。
- 删掉 `data.sql` 里写死的 09-17～09-21 号源与两条日程；删除第三个重复的号源补种器 `SlotAvailabilityInitializer`；两个补种器都收成幂等的 `seed()`，为后面的「场景重置」接口留好入口。
- 修掉一处把过期号源当可约的判断：`appointment_date > CURRENT_DATE OR appointment_time > CURRENT_TIME` 会把「过去某天里更晚的时刻」算成可约，改为「未来的某天，或今天但时刻更晚」（`MockAppointmentTool` 三处、`CareCatalogRepository.availableDates`）。
- 回归用例不再写死日期：新增 `support/DemoSeed` 收口演示日期与号源名，新增 `DemoSeedDataTests` 把「口语的下周三 = 体检那天」「10:30 冲突、14:00/15:30 不冲突」「周末没有号源」「每个启用科室都有号」钉成断言；9 个已有测试类改为从 `DemoSeed` 取值，另删掉两个只声明未使用的日期辅助方法。
- 后端完整测试 **300 项**通过（0 失败 0 错误）。

## 2026-09-11 材料清单跨页面同步与办理过程面板空值守卫

- `silver-agent-materials-updated` 事件原先只有派发没有监听：助手页的确认卡常驻不卸载，事项页每次切回来都重新挂载，同一份预约的材料清单会同时存在两份实例——在事项页勾完，助手页那份还显示「未准备」。现在 `useAppointmentMaterials` 订阅该事件，在 `appointmentId` 相符、且事件不是自己派发时**静默**重新拉取（后台同步不切「正在读取」，避免闪一下）。
- `ToolTracePanel` 的 `traces` 改为可选并按空数组兜底：后端每轮都会返回，但历史消息与旧快照里可能没有这个字段，直接读 `length` 会白屏。
- 保留 `silver-agent-appointments-updated`（`tasks-view.tsx` 仍在监听）；材料这次是补齐监听，不是删广播。

## 2026-09-11 医疗越界判定：词表收敛成一份，补上模型漏判的兜底

- 原先「越界」在不同地方各判一遍：`SafetyGuard` 一张 11 词的表、`RuleFactExtractor` 一张一样的表、`FollowupAgentService` 里还有第三张；表都只列了「怎么用药/药量/诊断/检查结果/是不是得了」这类现成动词，**没命中就落回普通信息**——「我血压有点高，要不要紧？」会被当成「用户提供了信息」，助手接着问去哪家医院。
- 新增 `agent/MedicalBoundaryRules`，口径改成「默认怀疑」：症状/药物/报告名词 + 疑问语气即判越界（「这个药还能继续吃吗」「阿司匹林一天吃几片」「帮我看看这个化验单」「我是不是该住院」），混合句式也判得出来（「9月18日，我最近头晕是不是血压高了」——日期照常解析，但越界那半句不会再被当空气）。三张表删成一张，`SafetyGuard` 与 `RuleFactExtractor` 都调它。
- 模型侧同样兜底：`SafetyGuard.evaluateModel(message, facts)` 在模型没判出医疗语义时，用规则再判一次。业务意图仍只认模型的结论（改医院、改日期、取消、确认卡一个字没动），只有医疗这一根轴上加规则。
- 两条**刻意放行**：① 老人报自己量到的数、查自己记过的数（「我的血压是100」「我最近的血压是多少」）走 `HealthRecordParser`，不能被当成问诊吞掉；② 「那天我要做手术，帮我把复诊改到下周」是办事，所以「住院/手术/化疗/输液/打针」这类处置词要配疑问语气才算越界。
- `AgentSystemPrompt` 的【医疗安全】补了用例与一条明确的边界：带日期的混合句不能只接办事情那半句；报数/查数用 `RECORD_HEALTH_VALUE` 而不是 `MEDICAL_ADVICE`。这样模型路径与规则路径口径一致。
- 删掉 `FollowupAgentService` 里那段已经够不到的重复判定（`chatInternalBody` 开头的 `precheck` 先拦），`RuleFactExtractor` 里重复的 `physicalDiscomfort` 一并删除。
- 新增 `MedicalBoundaryRulesTests`（7 项：要拦住的 5 句 + 混合句、不能误拦的办理/备忘/健康记录话术），`SilverAgentApplicationTests` 的越界用例扩到 11 句，`VoiceFirstP1Tests` 新增「模型漏判时规则兜底、且不多调一次模型」。后端完整测试 **308 项**通过。

## 2026-09-11 越界专属提示块，与冲突确认卡的四处细节

- 越界回复原先就是一条普通聊天气泡，顶部步骤标签也不变，老人看不出「这条和别的不一样」。`AgentTurnResponse` 新增可空字段 `notice`（第 12 个分量，旧的 11/9/8 参构造原样保留），越界时带 `{type: MEDICAL_BOUNDARY, title, message}`；前端 `BoundaryAlert` 渲染成一块橙色提示卡，未知 type 一律不渲染。**`notice` 只影响展示**：不切 `stage`、不新建待办、不落任何库，`notice.message` 也不是 `reply` 的复制——回复照常进对话记录并朗读，卡上只补一句「这条为什么不一样」。
- 顺着这条占位发现一个真问题：越界回答返回时 `confirmation` 是 null，前端 `confirm()` 又直接读 `turn.confirmation.confirmationId`——老人问到一半药，正要按的「确认办理」会跟着消失，而服务端那张卡其实一直有效。现在 `medicalBoundary` 用 `pendingConfirmation(state)` 把**原来那张卡连同同一个 `confirmationId` 原样带回**；卡片内容抽成 `confirmationCard(state, at)` 单一来源，避免两边各拼一份。取消类确认卡（`CANCEL_EXISTING`/`CANCEL_MANAGED`）不在这条路上，行为与加提示块之前一致。
- 冲突确认卡补一条「已知冲突：与『社区体检』（… 至 …）时间重叠，您已选择保留」：用户点「仍保留这个时间」之后，提交前最后一次提醒和事后审计都该看得到这件事，而不是只在上一轮的回复里说过一次。冲突随手存进 `state.conflicts`（`Snapshot` 一并持久化），确认卡按它渲染。
- 冲突分支的按钮改成**当天候选只给一个**（`slotReplies(...).limit(1)`）：加上「重新选择日期」「仍保留这个时间」正好三个，首页放得下——前端一屏就渲染 3 个（`assistant-view.tsx` 的 `slice(choicePage * 3, choicePage * 3 + 3)`），第四个按钮藏在「查看更多选项」后面，冲突这种要当场做决定的场景不该让人再点一次。
- 新增常量 `APPOINTMENT_DURATION = 60`：日程冲突按「间隔一小时」判重叠，和演示号源的排布（09:00 / 10:30 / 14:00 / 15:30）对得上；原先写死的分钟数散在判断里。科室层面的实际时长还没有数据，先按演示口径统一。
- 「附近日期」候选改成**只往后看**：`queryAlternatives` 的窗口从 `date-1 ~ date+3` 收成 `date+1 ~ date+3`。往前找会捞出已经过去的时段，把当天其它时段也算进来还会跟上一句「这一天暂无号源」自相矛盾；当天之内换时段由「上午没有下午有」那条路负责。新增 `MockAppointmentAlternativesTests` 2 项钉住这个口径。
- 后端完整测试 **312 项**通过（新增 2，扩展 2）；前端 `tsc` 与生产构建成功，改动过的文件与新增文件都单独比对过规范检查结果。

## 2026-09-11 演示场景一键重置

- 新增 `POST /api/demo/scenarios/{normal|no-slot|conflict|boundary}`：清掉上一场演示留下的可变数据、放开被占用的号源、按「今天」重排号源与日程，然后开一段全新的会话，并返回**照着念就能复现该场景的步骤**。四个场景原先要靠演示文档口头说明「选哪一天」，而演示日期是滚动的（下周三体检、下周六无号），文档里的固定日期过一周就对不上——步骤由后端现算，跟着今天走。
- 重置表清单里除了预约相关那几张，还包含**本分支自己加的表**：`conversation_attachments`、`vision_results`（多模态）、`memos`、`health_records`（健康记录与备忘）、`user_memories`（长期记忆）、`care_notifications`（照护通知）。不回一起清的话，场景二会踩着场景一留下的习惯记忆少问一句，看起来像流程漏了一步。医院、科室、材料模板、出行路线、家属联系人等目录数据一条不动。
- 内存里那份会话状态一起清：`FollowupAgentService.forgetAllSessions()`。`requireSession` 命中内存就不再回查数据库，不清的话旧会话 id 还能继续说话，而它对应的库记录已经没了——等于在一个不存在的草稿上办事。
- 重置后旧会话 id 一律回「会话不存在或已过期，请重新开始」，返回的新会话可以立刻开始；未知编号回 **400** 并列出可选值（手敲 curl 最容易拼错编号）。这是**破坏性**接口，不提供前端按钮，演示开场前调一次即可。
- 新增 `DemoScenarioTests` 5 项：清空清单逐表断言（含本分支独有的几张表、且目录数据不变）、被占号源重置后可再约、旧会话 id 失效、步骤里的日期确实按本次运行现算、HTTP 层返回结构与 400 分支。其中 HTTP 断言用 MockMvc（本仓库第一次用，之前全是服务层用例）——这个接口是给评委手敲的，返回结构和状态码值得按契约钉住。
- 文档同步：`05-api-contracts.md` 新增「演示场景重置」、`06-mock-data-design.md` 第十一节把「当前没有场景选择 / 重置 API」改成实际状态、`09-demo-acceptance-checklist.md` 补越界提示块与冲突卡的三条验收项、`设计思路报告.md` 11.5 在只读接口表后单列这个破坏性接口。后端完整测试 **317 项**通过。

## 2026-09-11 死代码清查：删掉的每一处都先确认过没有活路径

- 起因是评委那句「核心流程没有写在一个超长类里」的反面——`FollowupAgentService` 已经长到四千多行，里面混着早期版本留下来、后来改了路子却没删的私有方法。清之前逐个 grep 全仓（含测试）确认零引用，删完再看有没有编译不过的调用点。
- 后端删掉：`memoRepeatDayReplies(String)`（追问「每周几/每月几号」的快捷回复，实际走的是 `memoNoTimeReply("MEMO_REPEAT_DAY")`）、`respondWithCancelCard(ConversationState)`（取消的确认卡由 `cancelTask` 与确认门禁那条路负责）、`actInternal` 的 `switch (action)` 里够不到的 `case "NEW_BOOKING"`（方法开头已经 `return restartInCurrentConversation(state)`）、`ConversationState(String)` 单参构造（全部调用点都是两参或五参）、旧版占位类 `MockCareTools`、`FollowupPlan` record（对外下发的计划卡是 `PlanCard`，这个只被自己引用）。
- `RuleFactExtractor.detectIntent` 里第二条 `CANCEL_APPOINTMENT` 也删了：「取消预约 / 取消这次预约 / 取消已经预约 / 取消已预约」四条都同时含「预约」和「取消」，已被上面那条更宽的规则完整覆盖。**这是纯删除，不改判定结果**——留着两条一模一样的返回，读的人会以为中间藏着区别。
- 科室搜索三件套一并删（接口 `DepartmentCatalogTool.searchDepartments` + 实现 + `CareCatalogRepository.searchDepartments`）：`ToolRegistry` 里根本没有对应工具，科室匹配走的是 `catalog.departmentNames()` 的口语片段比对，这个 LIKE 查询没有任何调用点。前端工具追踪面板里那条 `catalog.searchDepartments` 的中文映射与 `05-api-contracts.md` 的对应条目一起删掉，否则面板上永远有一条谁也触发不了的说明。
- 前端删掉：`types/domain.ts` 的 `PlanStep` / `PlanStepStatus`（页面渲染的是后端下发的 `PlanCard`）、`profile-view.tsx` 里那个没有 `onClick` 的「隐私与安全」按钮。后者不算纯死代码——它看着能点，点了什么都不发生，老人会以为是自己没按对；数据使用说明本来就写在页面最下面，要保留入口就得真接上，接不上先不摆。
- 删除后跑全量：后端 **317 项**全绿、`tsc --noEmit` 与生产构建通过、改动过的前端文件 `oxlint` 零告警。测试一条没红，正是「这些路径确实没有活人走」的旁证。

## 2026-09-11 语音音量动画只留一条，按钮只变色

- 按住说话时屏幕上跑着**两条**音量动画：圆形按钮里一条（`LevelMeter` 的默认参数，5 根 8–36px），大浮层里一条（9 根 14–72px）。评审一眼看出重复——同一个声音两处刻度还不一样，按钮那条 4.5 倍、浮层那条 5.1 倍，同一时刻对不上。
- 尺寸也确实放不下：`size-16` 减去 `border-4` 只剩 56px 内圆，默认那套 5 根 `w-2`(8px) + 4 个 `gap-1.5`(6px) 要 64px，两边各 4px 压在白色描边上；`scale-90` 只把它缩到 57.6px，仍然越界，且 transform 不改布局盒。真正让它「看着还行」的是按住期间按钮自己的 `active:scale-95` 也在生效（0.855），换个不触发 `:active` 的路径就会看到红条压白圈。行内变体更直接：`heightClass="h-6"`(24px) 配默认 36px 上限，条子长到容器外 12px——而这一支今天没有任何地方在渲染。
- 改法：**全屏只保留浮层里那一条**。`voice-mic-button.tsx` 去掉按钮内的迷你条与整个 `inline` 变体（连同 `variant` 入参、行内 `label`、外层 `floating ?` 分支），按钮按住只变红、取消预备变琥珀色，图标保持 `size-8` 不变，不再有「按住瞬间墨迹从 32px 跳到 58px」；`level-meter.tsx` 把根数、粗细、间距、高度区间收成组件内常量（只留 `barClass` 随取消状态换颜色），顺手把 `className="scale-90"` 这个补丁删掉——尺寸只有一处定义，就再没有「某处传漏一个参数」的错法。
- 为什么留浮层那条：手指正压在按钮上，按钮里的条子大半被自己的指头挡住；浮层那条约 120×72px，在视线所及处，还和实时字幕、上滑取消提示挨着。`h-20`(80px) 容器配 72px 上限、120px 宽落在窄屏 232px 内宽里，三项都对得上。
- 文档同步：`设计思路报告.md` 第 12 节把「五根音量条」改成「浮层里一排、全屏只有这一条」，`user-manual.md` 4.2 节补上浮层与音量条的实际形态（原先写的是「按钮变色并显示正在听…」，那句提示其实来自浮层），`09-demo-acceptance-checklist.md` 的语音项写成可对照验收的一句话。决定记在 DEC-017。
- 验证：`tsc --noEmit` 与 `oxlint`（`features/voice`、`app/page.tsx`）零告警，生产构建通过。前端行为要起来看效果——后端与前端当前都是我按你的要求停着的。

## 2026-09-11 同一处语音浮层的当日修正：一行只放得下一个汉字

- 上一版把录音浮层写成 `fixed bottom-[132px] left-1/2 w-[calc(100%-40px)] max-w-[420px]`，实机一按就现原形：浮层变成一条又高又窄的琥珀色竖条，「松开手指，取消发送」一个汉字一行往下排，从屏幕中段一直垂到底部，把号源列表整片盖住。`tsc`、`oxlint`、生产构建全是绿的——这类错误它们一个都看不见。
- 根因不在浮层自己，在它的祖先：`app/page.tsx` 用 `fixed bottom-[46px] left-1/2 -translate-x-1/2` 给麦克风居中，**那是一个 transform**。CSS 里祖先带 transform 时，后代的 `position: fixed` 不再以视口为包含块，而是以那个祖先的盒子为包含块——也就是包着 64px 圆按钮的那个 64×64 的盒子。于是 `calc(100% - 40px)` 算成 **24px**（`px-6` 左右各 24px 内边距还比它宽），汉字只能一个字一行；`bottom-[132px]` 也从「屏幕底部往上 132px」变成「这个小盒子上方 132px」，浮层因此贴到了屏幕上半部。同一个 transform 还解释了它为什么偏偏居中在按钮正上方。
- 改法：`page.tsx` 的居中改用 `-ml-8`（按钮 `size-16` 的一半，不产生 transform），浮层的宽度从百分比改成按视口算的 `w-[calc(100vw-40px)]`——包含块哪天真被谁再改变一次，宽度也不会跟着塌掉。两处都写了注释说明为什么不能用 `-translate-x-1/2`。
- 验证：`tsc --noEmit`、`oxlint`、`npm run build` 通过；已核对全仓其余 `fixed` 元素，暂无第二个「fixed 后代落在带 transform 的祖先里」的组合。真正算数的还是实机按一次。
- 教训与上一版恰好对称：上一轮删掉的是「同一件事做了两遍」，这一轮踩的是「把定位交给了一个会变的包含块」。两者都不是类型检查能挡的，只能靠起来看一眼。

## 2026-09-12 `application/` 包按职责拆分（切包，一行逻辑没动）

- 起因：`application/` 下 30 个类平铺在一个包里，找一个类要顺着 30 个文件名扫。这一步只解决「找文件」，**不解决耦合**——真正的病是 `FollowupAgentService` 4883 行、占这一层 9408 行的 **52%**，那要靠切类，本次一行没动。
- 做法：按职责把 15 个类移进 7 个子包——`care/`（CareService、CareBookingService、CareCatalogRepository）、`health/`（HealthRecordStore、HealthRecordParser、HealthReportParser、HealthReportService）、`memo/`（MemoStore、MemoParser、MemoCommandParser）、`longterm/`（MemoryStore）、`preference/`（UserPreferenceStore）、`travel/`（TravelGuideService）、`demo/`（DemoScenario、DemoScenarioService）。
- 编排簇 15 个类留在根包，不是懒得挪：`ConversationState` 的 ~35 个字段和四个嵌套枚举是**包级私有**，另有 `ToolPolicy`、`AgentRuntime`、`SafetyGuard`、`ActionValidator` 等 11 个类也是包级私有，它们与 `ConversationState` 互相引用。真要分家就得把这些字段和类逐个提成 `public`——那是**放宽**封装，不是整理结构。所以拆到哪一层是算出来的，不是拍脑袋定的。
- 纯搬家：diff 里除 `package` 行和 `import` 行外没有任何增删。
- 复查时改掉三处不对味（第二个提交）：
  - `health/` 看起来依赖 `memo/` 四条，实际只有一条是真的。`HealthReportService` 调 `MemoParser.nowInDemoZone()` 是真的（那是个演示时区时钟，塞在 MemoParser 里属历史原因）；另三条是迁移脚本按类名匹配误补的 import，只服务注释里的 `{@link}`。改成注释里用全限定名，删掉这三行。
  - `memory/` 里 `MemoryStore`（跨对话长期记忆）和 `UserPreferenceStore`（朗读开关、语速、音色，属**设置**）不是一回事，之前只是按「都属于某个用户」放在一起。拆成 `longterm/` 和 `preference/`。
  - 包名 `memory/` 与 `memo/` 只差一个字母却指两回事（备忘是「要做的事、可完成可删除」，长期记忆是「常去的医院、科室、习惯时段」），看错一次就找错地方。长期记忆的包定名 `longterm/`。
- 验证：`target/` 清空后**全新构建**，后端 **317 项全绿**（0 失败 0 错误）；前端零改动。队长本人起服务手测，四条主流程功能正常。
- 文档同步：`04-architecture-and-modules.md` 的「当前实际分包」换成本次的新布局并写明 `longterm` 的命名理由；`11-agent-architecture-and-controlled-tool-calling.md` 第八节那份「建议拆分」是按技术分层（`runtime/ policy/ workflow/`），与本次按业务域的实际切法不是一回事，加了指引；决定记在 DEC-018，踩坑记在 PITFALLS 同日两条。
- 留给下一步：切类（把 `FollowupAgentService` 按业务拆开）等前端大改定了接口形状再做，否则边界容易划错。**别把这次当成「结构问题已解决」**——它只把邻居归了位。

## 2026-09-13 第三阶段：工具契约强类型化 + 通用澄清能力（先接预约取消链路）

- 范围：为模型工具的声明补上参数类型、必填项、枚举与字段组合约束，Java 统一校验；把「成功 / 无结果 / 缺少信息 / 需要澄清 / 需要确认 / 状态变化 / 失败」七种结果说清楚；把「模型提个自然问题 + 真候选来自数据库 + 支持文字/语音/点选」的澄清交互做出来，并且**澄清不等于确认、不生成执行授权**；堵住「模型给了合法工具调用、Java 还拿原句关键词覆盖意图」这个洞。**只接预约取消一条链路**，备忘与健康记录这次没动。
- 新增（后端）：`agent/planning/ToolArgument.java`、`agent/planning/ToolConstraint.java`（`REQUIRES_ALL` / `AT_LEAST_ONE`）、`application/ToolContract.java`、`application/ToolOutcome.java`（七种 `Kind` + 带进度的 `Step`）、`application/ClarificationInteractionTool.java`。
- 改动（后端）：`agent/planning/PlannerTool.java`（诊断声明升成强类型，保留只服务旧测试的 `List<String>` 兼容构造器）、`application/ToolRegistry.java`（所有工具补齐声明并注册 `interaction.askClarification`；`interaction.requestConfirmation` 刻意不声明 `appointmentId`）、`application/ToolPolicy.java`（新增 `evaluateClarification`，风险等级新设 `CLARIFICATION_ONLY`）、`application/AgentOrchestrator.java`（`Route.ASK_CLARIFICATION`）、`application/AgentRuntime.java`（执行前统一过 `ToolContract.check`；`Outcome.acceptedToolCall()`；包级私有 `rejection(...)` / `acceptedArguments(...)`，6 参测试构造器逐字未动）、`application/FollowupAgentService.java`（取消支线重写：`beginCancellationStep` / `clarifyCancellationTargets` / `modelCancellationSelection`；`Outcome.acceptedToolCall()` 守门禁）、`agent/planning/AgentSystemPrompt.java`（新增「工具参数是怎么声明的」「工具结果的七种情形」「范围说不清时先问，不要硬填」三节）。
- 改动（前端，纯展示）：`features/assistant/tool-trace-describe.ts` 新增 `describeCancelScope(req)` 与三条 `interaction.*` 的中文标签。
- 关键取舍：**风险分成三条互不相通的通道**——只读走 `evaluate`、确认走 `evaluateConfirmation`、澄清走 `evaluateClarification`。澄清工具因此**根本走不到发凭据那条分支上**（不是被拦住，是没有那条路）。澄清轮的回复走 `respondWithoutModel`，`speechText == reply`，按钮上的字和口播的话逐字一致，润色模型碰不到。决定记在 DEC-020 / DEC-021。
- 新增测试：`ToolContractTests` 17 项（五种判罚、空串算没给、未声明字段被丢弃、宽松日期/时段写法、模型看到的说明里带 `"type":"enum"` / `"required":true` / `REQUIRES_ALL` 且**不含 `appointmentId`**、参数名无重复）；`ClarificationFlowTests` 6 项（模型提问 + 真候选 + 无执行授权；澄清后说「确认」一条也不动；无候选时说清「没查到」且不摆按钮；`DATE_RANGE` 没给方向 → 追问不执行；范围不在枚举里 → 不执行；**合法工具调用不被「都取消」关键词覆盖**）。既有批量取消测试全部保留。
- 验证：`target/` 清空后全新构建，后端 **389 项全绿**（0 失败 0 错误，比阶段前的 317 项多出 72 项）；`tsc --noEmit` 与 `oxlint` 通过（改动文件零告警）；接口文档与前端类型已同步。
- 回归守卫有效性实测：把 requirement-6 的守卫改回旧写法重跑，那张**莫须有的 2 条批量取消卡立刻复现**，加回即消失——这个洞是真的，不是只为测试而加的。
- 后续（2026-09-13，第四阶段）：上面的 `Outcome.acceptedToolCall()` 已改名为 `hasContractCheckedToolCall()`（名不符实，见 DEC-022 决定六），本条目里的旧名字按当时写法保留。`interaction.respondConfirmation` 的参数校验也在那一阶段统一到了 `ToolContract`。
- 真实模型评测（deepseek-v4-flash，独立端口 8099 + 内存 H2，全程没碰你跑着的 :8080 / :3000）：
  - 模糊指代：一次跑出 `interaction.askClarification`，`questionFromModel=true`，2 条真候选摆成 `SELECT_APPOINTMENT_TO_CANCEL`，**无卡、无 `confirmationId`**；另一次模型判成 `scope=ALL` 直接出卡（卡上逐条列出目标）。**模型在这两条路之间摇摆**，都还在安全边界内。也见到过一次 `questionFromModel=false`（模型写的话被结构门拦下，用了 Java 固定问句）。
  - 澄清 → 点选候选 → 单条确认卡 `092e0540` → 确认 → `stage=CANCELLED`，另一条预约仍在库里。这是要求 4 / 5 端到端的实证。
  - 修改取消范围：「全都要」出卡 `48f726b7` → 改口只取消下午那条 → 新卡 `76a968be`，新旧 id 不同；拿旧 id 确认 → 「刚才那份确认已经失效或者已经办理过了」。
  - 非法参数：「9月32号」→「您说的『9月32号』这个日期不存在，九月只有30天…」，无卡、库里没动。
  - **工具失败这一路真模型没被触发**（不注入故障没法稳定构造），只有单测覆盖，如实记在这里。
- 未解决风险（两条都是展示层面的，功能与安全边界不受影响）：
  1. **澄清轮不改 `stage`**：这一轮沿用上一件事的 stage（冷会话下见过 `ASK_HOSPITAL`，也见过 `COMPLETED`）。前端只在 `turn?.task?.active` 为真时才显示阶段标签，目前看不出问题，但语义上仍然是「澄清不动状态」的副作用。
  2. **澄清 / 追问轮不下发仍有效的那张待确认卡**：见到的例子是「9月32号」那一轮 `stage` 报 `AWAITING_CONFIRMATION` 而 `confirmation` 为空，于是先前发出去的那张卡的按钮从这次响应里消失了。这与 DEC-016 给越界提示块修过的是同一类问题（回复另起一件事时，老人正要按的按钮不能跟着不见），值得下一阶段按同样思路处理。
- 未提交、未推送。接口文档同步：`05-api-contracts.md` 新增「工具契约与通用澄清（2026-09-13）」，`INTERFACE_CHANGES.md` 同日一条，决定记在 DEC-020 / DEC-021，踩坑记在 PITFALLS 同日两条。等审查。

## 2026-09-13 第五阶段 B：号源查询的参数执行一致性（查了就按查的算，查询不动草稿）

- 范围：`appointment.querySlots` / `appointment.queryNearbySlots` 两个只读工具——**通过 `ToolContract` 校验的显式参数必须决定本次查询条件**；参数省略时才沿用会话里已有且明确的条件；显式参数非法、医院/科室不唯一或条件不足时停下澄清，不得退回旧条件查询后装作回答了本次问题。本轮不动健康记录、家属报告、通用澄清、预约历史排序、循环轮数与确认执行架构，不合并工具。
- 改动（后端**两个主文件** + 一个新增测试文件；此前这条误写成「3 个文件」，实为同一文件列了两次，2026-09-14 更正）：
  - `agent/planning/LlmConversationPlanner.java`：只读工具（`CALL_READ_TOOL(S)`）不再把 `arguments` 兜底成 facts。那段兼容是给办理/确认类动作写的（参数就是老人刚说的条件），对只读查询有害——老人问一句「市二院下周三有号吗」，参数里的医院和日期会被当成改草稿的意愿写进待办理的预约。
  - `application/FollowupAgentService.java`：
    - 号源查询条件解析整块（`SlotQuery` / `SlotQueryResolution` / `resolveSlotQuery` / `querySlotsFor` / `nearbySlotsFor` / `answerSlotQueryReadOnly` / `answerReadOnly` / `slotText`）：显式医院/科室必须唯一精确，日期经 `ToolContract.parseDate` 解析，过去的日期不拿去查，缺项就停下问；查询一律走「只读回答」——不推进阶段、不写候选、不碰确认卡。单工具与多工具路径共用同一套解析（`toolArguments` 按工具名取参数）。
    - `queryNearbySlots` 删掉 `state.acceptAlternative = true`：查一次附近日期不等于老人答应了「可以换日期」。
    - 工具循环：**模型发起的号源查询（`appointment.querySlots` / `appointment.queryNearbySlots`）只查不改**——每轮进业务流程前过一遍 `withoutDraftWrites(...)`（医院/科室/日期/时间/陪同/通知清空，致谢与情绪照旧），收口统一走 `finishToolLoopDecision`（只交付真实结果、模型那句回答或兜底说明，不写草稿、不重进业务流）。草稿只由两处改动：模型明确提出业务动作（`PROPOSE_WORKFLOW_ACTION`）的那一轮，以及完全不经过模型的按钮/规则流程。
    - **审查后简化（2026-09-14）**：删掉 5B 初版的 `SlotQuery.conflictsWith`（按「查询条件与草稿冲不冲突」决定写不写草稿）、`applyExplicitQueryConditions`、`queriedConditions` + `draftFacts`（按参数名逐个剥 facts）、`restatesTheQueryJustDelivered`、`readOnlyAnswerDelivered` ThreadLocal 与 `withFacts`。判据从「冲突与否」换成一条结构性不变式，跨轮状态归零。
- 新增测试：`application/SlotQueryArgumentTests.java` **16 项**——草稿无日期时按参数查且草稿日期仍为空、草稿已有 A 日时查 A 日或 B 日都不动草稿、显式医院科室压过草稿、`arguments.date` 与 `facts.date` 同时出现的只读调用不写草稿、续跑轮误标 `CHANGE_DATE` 也不改草稿、歧义医院不回退查旧条件（含「原草稿与确认卡都不变」一条）、下一轮明确改期仍生效且旧卡作废、非模型的按钮流程仍能把老人明确选择写进草稿、参数省略沿用草稿、过去日期不回退、附近日期不自动置 `acceptAlternative`、查询期间确认卡凭据与内容不变且不写库。
- 验证：`mvn -o test` **491 项，490 通过，1 失败**——失败的是 `DemoSeedDataTests.spokenNextWednesdayLandsOnTheSeededCheckupDay`，**既有问题，不是本次回归**：容器时区还停在 09-13（算出「下周三」= 09-23），业务时钟（北京）已过午夜算 09-14（= 09-16，即 `DemoSeed.checkupDay()`）。基线复现证据：`git stash` 退回未改动的两个主文件后单跑 `DemoSeedDataTests`，**同样 6 项里 1 失败、报错逐字相同**（expected 2026-09-23 / but was 2026-09-16），随后 `git stash pop` 复原。本轮不顺手修，也不称「全量通过」。
  - 注：`mvn -o clean test` 跑不了——`:8080` 上你跑着的实例占着 `target/classes`，clean 报 `Device or resource busy`，所以本次是清不了 target 的全量构建，如实记在这里。
- 真实模型评测（deepseek-v4-flash，独立 8099 + 内存 H2，全程没碰你跑着的 :8080 / :3000）：
  - 场景 A（草稿无日期，模型参数带日期）：模型给出 `{"hospital":"市第一医院","department":"心内科","date":"2026-09-16"}`，实际查的就是 09-16。**这一轮「草稿采纳」是当时 5B 的行为，审查后已按最终规则改为草稿日期保持为空**——真实模型未重跑，本轮只由 `SlotQueryArgumentTests` 的「草稿为空时查询 B 日，草稿日期仍为空」钉住。
  - 场景 B（草稿是 9月19日，问「下周三有没有号」）：**连续 4 次**都是只查 09-16、草稿稳定保持 `2026年9月19日`、回复为模型原话、留痕里 `appointment.querySlots` 只有一次。修复前同一场景 3 次跑出 3 种坏结果（草稿被改成 09-16 / 草稿日期被 `resetAfterDate` 抹掉 / 回复没有按钮）。这条的行为与最终规则一致，仍然有效。
  - 「参数省略」这一项真模型没被触发：模型每次都把三项参数写全，该项只有桩测试覆盖。
- 未解决（按你的要求，按钮问题只记不改）：
  1. `modelSuggestedReplies` 对 `SELECT_SLOT`、`ASK_DATE`、`ASK_HOSPITAL` 等阶段 `default -> List.of()`，Java 交付的答复可能一个按钮都没有（真模型下观察到 `stage=SELECT_SLOT, quickReplies=[]`）。**本轮不动**。
  2. 只读轮里模型 facts 的写法仍不统一（有的模型把查询条件同时写进 `arguments` 和 `facts`）；现在靠「模型发起的号源查询不写草稿」这一条结构性规则兜住，提示词只是提醒，没有从根上让模型别这么写。
- 下次从这里继续：② 按上面未解决项 1 处理按钮（提示词收口与文档同步已在同日收尾完成）。
- 未提交、未推送，工作树停在 `zhaotingfang` 分支（`gitStatus` 里写的 `refactor/application-packages` 与实际 HEAD 不一致，以 `git rev-parse` 为准）。

### 同日收尾（2026-09-14）：提示词收口 + 文档同步

- 提示词收口（`agent/planning/AgentSystemPrompt.java`，新增一节，**JSON 规划格式、字段、工具清单一律不变，不新增工具**）：只读查询条件写对应工具的 `arguments`；`facts` 只表示用户明确要求写入或修改办理草稿的业务事实；用户只是询问别处医院/科室/日期/号源时不要同时写进 `facts`；查询结果不得被描述成已经预约、已经改期或已经取消。
- 新增 `agent/planning/AgentSystemPromptQueryFactsTests.java` **4 项**，把上述四条规则与「JSON 骨架不变」钉在提示词文本上。
- 文档同步：`docs/15-agent-flexibility-and-elder-ui-plan.md` 第五节新增「现状（2026-09-13 已实施 5B）」小节，并更正两处过期数字——工具总数 17 只读 + 2 确认 + 1 澄清（共 20，此前漏了 `interaction.askClarification`）、`FollowupAgentService` 5597 行（此前写 5173）；`records/DECISIONS.md` 新增 DEC-025。两处都写明「本轮只做了号源查询两个工具，不代表所有工具已完成参数改造」。
- 核对结果：`records/PITFALLS.md` 同日两条与实现一致，无需改动；`records/PROGRESS.md` 5B 条目的「3 个文件」为笔误（同一文件列了两次），已更正为两个主文件 + 一个新增测试文件。其余文档（`03`/`04`/`05`/`11`/`设计思路报告`）里的「17 个只读工具」指的是只读工具数，仍然准确，未动。
- 专项验证：`SlotQueryArgumentTests` 14 + `AgentSystemPromptQueryFactsTests` 4 + `AgentSystemPromptTimeTests` 3 + `AgentSystemPromptVisionTests` 5 + `AgentRuntimeRoutingTests` 17 + `LlmConversationPlannerTests` 6 = **49 项，0 失败 0 错误**。后端全量沿用 5B 的 **491 项 490 通过 1 失败**（失败为既有跨午夜用例），本轮不重复跑；真实模型 A/B 结果沿用，不重复消耗额度。
- 本轮不处理：`modelSuggestedReplies` 空按钮、`DemoSeedDataTests` 跨午夜、健康记录与家属报告、SQL 分页与整轮超时、`ConfirmationService` 与执行器、其他工具的参数改造。

### 同日审查后简化（2026-09-14）：把「冲突判据」换成一条结构性规则

- 起因：审查认为 5B 的实现边界过重——用「查询条件与草稿冲不冲突」决定写不写草稿，引入了跨轮状态（`readOnlyAnswerDelivered` ThreadLocal）和四层剥离逻辑，判断点多、状态难跟。
- 新规则（结构性，不再逐个判冲突）：**只要本轮是模型发起的号源查询（`appointment.querySlots` / `appointment.queryNearbySlots`），工具参数只用于这次查询，永远不写入预约草稿**；草稿为空就保持为空；要改草稿必须走既有业务动作，或下一轮老人的明确选择（并作废旧确认卡）；工具续跑轮不是新的用户输入，其 facts 不写草稿，没有新的合法工具调用时只交付查询结果/澄清/模型答复，不重进预约业务流；老人一句「先查，有号再改」时，本轮先查并问「要按这个来吗」，下一轮明确同意后再改。
- 改动（`application/FollowupAgentService.java`）：删 `SlotQuery.conflictsWith` 与 `conflicts(...)`、`applyExplicitQueryConditions`、`queriedConditions` + `draftFacts`、`restatesTheQueryJustDelivered`、`readOnlyAnswerDelivered` ThreadLocal、仅作结果重建用的 `withFacts`；保留 `resolveSlotQuery`、严格实体解析、`answerSlotQueryReadOnly` 与不经过模型的既有预约流程。执行集中到两处——循环里一次 `withoutDraftWrites(...)`、`querySlotsFor` 空参数分支的 `!outcome.modelDriven()` 守卫；交付集中到 `finishToolLoopDecision`（去掉 `originalMessage` 参数，不再派发业务流）。
- 行为变化：模型驱动的 `appointment.querySlots` 若带 `acceptAlternative:true`，不再串联 `appointment.queryAlternatives`（facts 已被剥离）；查询结果不再推进流程、不再写草稿；只读回答末尾改为征询「要按这个来吗？」。`VoiceFirstP1Tests` 的无号续跑用例按新规则重写（草稿日期保持「待确认」）。
- 验证：专项批 99 项 0 失败 0 错误（`SlotQueryArgumentTests` 16、`AgentRuntimeRoutingTests` 17、`LlmConversationPlannerTests` 6、三个 `AgentSystemPrompt*` 4+3+5、`VoiceFirstP1Tests` 7、`VoiceFirstP0Tests` 17、`ClarificationFlowTests` 12、`DemoScenarioTests` 5、`MultimodalImageTurnTests` 7）；后端全量 `mvn -o test` **497 项 0 失败 0 错误 0 跳过，BUILD SUCCESS**。跨午夜用例这次也通过，属容器时钟越过边界的偶然，不是修复。
- 本轮不处理（与前一轮一致）：`modelSuggestedReplies` 空按钮、`DemoSeedDataTests` 跨午夜、健康记录与家属报告、SQL 分页与整轮超时、`ConfirmationService` 与执行器、其他工具的参数改造。
- 未提交、未推送，等审查。

### 同日收尾（2026-09-14）：只读出口的分界从「参数给没给」改成「谁发起的」

- 起因：审查发现上面那条简化留了个口子——分界写成了「**参数非空**才走只读出口」。模型只说了「帮我看看号」「还有别的时间吗」、把条件留给草稿，于是传 `arguments:{}`，`toolArguments` 取回空 Map，代码掉回 `showAvailableSlots` / `queryNearbySlots` 那条**会改状态**的旧路：阶段被推到 `NO_SLOT`、附近号源写进 `state.alternatives`、回复里带出代表草稿已推进的 `SELECT_SLOT` 按钮。
- 改动（`application/FollowupAgentService.java`，只动两个入口的一句分界）：`querySlotsFor` / `nearbySlotsFor` 一律先看 `outcome.modelDriven()`。模型发起的（含 `arguments` 为空）都走 `resolveSlotQuery(state, arguments, ...)` —— 缺的项本来就从草稿里取；够用就 `answerSlotQueryReadOnly`，不够就追问；不推进阶段、不动 `alternatives` / `selectedSlot` / `acceptAlternative` / 确认卡。只有 `modelDriven()==false` 的按钮/规则流程才继续走 `showAvailableSlots` / `queryNearbySlots`。
- 测试：改写把「`arguments={}` → `NO_SLOT` + `SELECT_SLOT` 按钮」当成正确行为的 `queryingNearbyDatesDoesNotClaimTheElderAcceptsOtherDates`，拆成 `modelQueryWithNoArgumentsUsesTheDraftConditionsAndMovesNothing`（querySlots 空参数：条件回草稿取、阶段与草稿不动、不出现代表草稿推进的选择按钮）与 `modelNearbyQueryWithNoArgumentsUsesTheDraftConditionsAndMovesNothing`（queryNearbySlots 空参数：`alternatives` / `acceptAlternative` / 阶段都不动）；`theButtonFlowStillWritesWhatTheElderExplicitlyChose` 加强成反面对照——非模型按钮流程仍要写草稿、仍要摆出 `SET_PERIOD`，选中的时段仍要落进 `selectedSlot`。`SlotQueryArgumentTests` 因此 16 → 17 项。
- 措辞收窄（按审查要求）：代码注释与文档不再写「所有只读工具只查不改」，统一改成「**本轮完成 `appointment.querySlots` 与 `appointment.queryNearbySlots` 的模型调用只查不改**」；其余只读工具尚未逐个核对——`hospital.search` 仍可能更新会话里的解析状态。
- 验证：`SlotQueryArgumentTests` 17 + `VoiceFirstP1Tests` 7 + `AgentRuntimeRoutingTests` 17 = **41 项 0 失败 0 错误**；邻近专项 `LlmConversationPlannerTests` 6 + `VoiceFirstP0Tests` 17 + `DemoScenarioTests` 5 + `ClarificationFlowTests` 12 + `MultimodalImageTurnTests` 7 + `AgentSystemPromptQueryFactsTests` 4 = **51 项 0 失败 0 错误**。按要求不跑全量、不跑真实模型、不修跨午夜用例、不处理按钮问题。
- 环境备注：中途遇到一次 `TestEngine with ID 'junit-jupiter' failed to discover tests` / `NoClassDefFoundError: AgentTurnResponse`——`target/test-classes` 下的 `SlotQueryArgumentTests*.class` 是 IDE（ecj）以「Unresolved compilation problems」容错形态写出来的坏产物。删掉这几个 class 后 `mvn -o test` 正常，不是源码问题。`mvn -o clean` 仍不可用（`:8080` 上的实例占着 `target/classes`）。
- 未提交、未推送，等审查。

## 2026-09-14 第八阶段 8A：画像与预约历史的受控读取（只读，不动写入侧）

- 范围：让模型能**先真的查库、再说话**——复用现有 `application/longterm/MemoryStore`（医院/科室/习惯时段的长期记忆，**不是** `application/preference/UserPreferenceStore`：那个存的是语速/朗读开关/音色这类应用设置），为模型提供受控的预约历史与画像摘要读取能力。**只做只读**：不做完整推荐排序、不做偏好写入。第六阶段的健康记录/健康备忘/家属报告一律没动；旧第七阶段「统一确认执行」已由 4A/4B 与修复轮完成，未重做。
- 新增（后端）：`application/profile/ProfileQueryService.java`（约 330 行）——`history(actorUserId, actorRole, subjectUserId, HistoryQuery)` 与 `memorySummary(actorUserId, actorRole, subjectUserId)` 两个入口，参数化 SQL（只拼死片段，取值一律走 `?`）、字段白名单、`HISTORY_LIMIT = 5`、`TENDENCY_SCAN_LIMIT = 200`、`TENDENCY_MIN_COUNT = 2`。
- 改动（后端）：`application/ToolRegistry.java`（注册 `appointment.history`、`profile.memorySummary`，并在注释里写清「为什么是新工具而不是扩 `appointment.queryMine`」「`limit` 为什么不做成参数」）；`application/AgentOrchestrator.java`（`Route.QUERY_APPOINTMENT_HISTORY` / `QUERY_PROFILE_MEMORY`，只读、不走写链路、不带 intent 映射）；`application/FollowupAgentService.java`（两个 handler + 文案装配 `appointmentHistoryText` / `profileMemoryText` / `currentDraftNote` / `factLabel` / `tendencyLabel` / `historyTrace`，一律走 `answerReadOnly`；三处 `"CONFIRMED_BOOKING"` 字面量改为 `MemoryStore.SOURCE_CONFIRMED_BOOKING`）；`application/longterm/MemoryStore.java`（新增 `SOURCE_USER_STATED` / `SOURCE_CONFIRMED_BOOKING` 常量与 javadoc）；`agent/planning/AgentSystemPrompt.java`（新增一节「画像与预约历史：先查，再说话，只能说『预约过』」）。**对外 HTTP 接口与 `AgentTurnResponse` 字段零改动**，`docs/05-api-contracts.md` 与 `INTERFACE_CHANGES.md` 无需改动。
- 不加 intent 是故意的：参数写坏的调用在按 intent 回退时不会落到这两条上（`rejectedByIntent` → `REFUSE_UNSUPPORTED_TOOL`），宁可回绝，也不装作答了。
- **身份与数据范围**：`requireReadable(actorUserId, actorRole, subjectUserId)` —— 代办（`actorRole.isCaregiver() && subject != actor`）必须 `CareService.bound(actor, subject)` 为真；本人只能读本人。**越权与「这个人不存在」返回逐字相同的 `ProfileQueryService.REFUSED`**（否则拒绝语就是「库里有没有他」的探针）；拒绝时一条 SQL 都不执行、一条留痕都不落。模型不连数据库、不执行 SQL、不遍历表。`userId` 由 Java 按当前会话注入并写进留痕的 `request_json`，模型传不进来。
- **四类信息分开**：当轮要求（草稿里已有的条件，Java 单独一段 + 「优先级最高」）、明确偏好（`SOURCE_USER_STATED`）、最近预约事实（`FactStatus`：`CONFIRMED_UPCOMING` / `CONFIRMED_PAST` / `CANCELLED` / `UNKNOWN`）、统计倾向（扫已确认预约聚合，`confirmedTotal >= 2` 才出，每条明说是「历史统计，不是老人明确说过的偏好」）。优先级：当轮要求 > 明确偏好 > 统计倾向。**Java 侧刻意只产出纯事实标签，不产出「去过 / 看过 / 就诊过 / 到院」**——措辞红线写在 `AgentSystemPrompt`，这样 Java 文本可以做干净的 `doesNotContain` 断言。
- **只读是结构性的**：两条都走 `answerReadOnly`，不推进阶段、不动 `alternatives` / `selectedSlot` / 草稿、确认卡原样带回；查询结果不回写草稿，用户下一轮明确说「这次还选它」时走正常业务动作。沿用 5B 的结构性规则，不新增按「查询条件与草稿冲不冲突」判断的分支。**不新增 Java 中文同义词表**——「上次那家」「还是原来的」由主模型理解并输出结构化工具调用或业务动作。
- 新增测试：`application/ProfileReadTests.java` **19 项**，覆盖要求的 17 条——本人只能读自己 / 家属读已绑定长辈 / 越权拒绝且不泄露存在性 / 最近预约带医院科室日期状态 / 已取消不能说成仍有效 / 已预约不能说成已到院已就诊 / 一次预约不是习惯 / 四类来源分得开 / 当轮要求压过历史 / 忘掉的记忆不当当前事实 / 字段白名单与条数上限 / 模型查询不动草稿 / 查询期间确认卡不被消费 / 无结果不编造 / 可基于真实结果问「还考虑吗」/ 拒绝沿用不动草稿 / 同意沿用仍走正常预约动作。
- 验证：`ProfileReadTests` 19 + 受影响既有 `SlotQueryArgumentTests` 17 + `UserMemoryTests` 7 + `AgentRuntimeRoutingTests` 17 + `DemoSeedDataTests` 6 = **66 项 0 失败**；后端全量 **517 项 0 失败 0 错误**（`mvn -o test`）。
- 真实模型评测（deepseek-v4-flash，独立端口 **8099** + `/tmp` 隔离 H2 文件库，**全程没碰你跑着的 :8080 / :3000**；种子为 3 条模拟预约：市第一医院心内科 ×2 已确认 + 市人民医院内分泌科 ×1 已取消）：
  - 场景 1（询问上次预约医院）：「我上次是在哪家医院看的来着？」→ 命中 `appointment.history`（success），回复「您最近一次预约的是市第一医院（模拟）心内科，2026年8月12日上午9点；…这次还考虑去市第一医院（模拟）心内科吗？」——**用「预约的是」，没有出现「去过 / 看过 / 就诊」**；草稿仍为空，只问不写。
  - 场景 2（当前明确要求与历史不一致）：「就去市人民医院内分泌科，别管我以前约过哪儿。」→ 草稿 `hospital=市人民医院`（当轮要求生效），历史那家没有被拿来覆盖。
  - 场景 3（用户拒绝沿用）：「不去那家了，我想换别的医院。」→ 先查了历史，随后尊重拒绝、改推另一家；草稿 `hospital=待确认`，**没有把历史值写进去**。
  - 场景 4（无历史记录）：user-002「我以前都约的是哪家医院？」→ 「系统里没有查到您以前在这边的预约记录，所以没法告诉您常去哪家医院…这次复诊您想去哪家医院呢？」——**没有编造医院、没有编造偏好**。
  - 附加：家属（user-f001，care_relations 已绑定 user-001）能读到被服务长辈的记录；同一会话里试图改查 user-002 被回绝（「这个会话里我只能查看王阿姨的复诊记录」）。评测期间库里 `appointments` **始终只有种子那 3 条**，`interaction.requestConfirmation` 一次都没出现——没有产生任何预约、没有建过或消费过确认卡。
- 未解决风险：
  1. `repository` 层统计倾向的扫描上限是 200 条、倾向阈值是 2 次，都是 Java 侧写死的常量，未做成配置；真实使用量级下够用，但没有评测依据。
  - 说明（2026-09-14 修复轮改写）：初版这里还列过两条「未解决风险」——`knownFacts` 里的 `memoryNote → digest` 旁路把全部 `active` 记忆不分来源拼进提示词、以及写入措辞本身就是「常去的医院是 X」。**这两条的定性是错的，不是「潜在风险、将来才会出问题」，它们当时就已经在违反「一次预约不能自动成为长期习惯」。** 已在同日的修复轮条目里修掉；判断为什么错见 `PITFALLS.md` 同日条目。
- 与队友改动的文件冲突：业务专属文件没有重叠——**没有碰** HealthRecord*/HealthReport*/Memo* 这些备忘与家属报告相关的文件（`MemoExecutor.java` 只是在 IDE 里被打开看过，未改动），也没有碰 `ConfirmationService` / `ConfirmationDispatcher` / 既有 `PendingOperation.Kind`、前端页面与全局麦克风。但本轮修改了 `FollowupAgentService`、`ToolRegistry`、`AgentSystemPrompt` 三个共享文件，**存在 Git 合并冲突可能；提交前需与负责备忘和家属报告的队友确认**。开工前核对过起点：分支 `zhaotingfang`、HEAD `3d15804bd994e65d19b7fce4c9f7576eb3c96fbf`、工作区干净（5B 那一轮的改动已在 3d15804 里，不存在别人的未提交改动可被覆盖）。
- 未提交、未推送，等审查。不进入 8B、第九阶段或其他功能。

## 2026-09-14 第八阶段 8A 修复轮（审查后：记忆旁路、偏好时效措辞、反向日期范围）

- 起因：8A 审查未通过，指出三处问题。**保留现有实现、不回退**，只修这三处；同样未提交、未推送、不进入 8B。
- **修复一：旧的长期记忆旁路（这是本轮的实质缺陷）**。原实现里 `knownFacts(state)` 调 `memoryNote(state)`，后者把 `MemoryStore.digest(userId)` 拼进 `knownFacts` 末尾，而 `digest` 会把全部 `active` 记忆**不分来源**原样塞进**每一轮**主模型提示词。配合写入侧 `rememberBookingPreferences` 一次预约就写「常去的医院是 X」「常去的科室是 X」「习惯上午/下午复诊」，等于**一次预约当场就被当成长期习惯送进了模型**——遵守「一次预约不能自动成为长期习惯」这条要求，写作**中性历史事实**。改法：
  - `FollowupAgentService.knownFacts` **删掉 `memoryNote(state)` 调用**，`memoryNote` 方法整体删除；默认上下文里一个字记忆都不再注入。画像改为一律经 `profile.memorySummary` 按需读取。这是**结构性**做法（默认提示词里干脆没有记忆），而不是给 `digest` 加来源过滤——过滤只在这一条路径上成立，结构性做法让「未经画像查询就不能把记忆当偏好用」不可能被绕过。
  - `rememberBookingPreferences` 写入措辞改为「最近一次确认预约的医院是……」「……科室是……」「……时段是……」，**不出现「常去」「习惯」**；`kind` 一律用新常量 `MemoryStore.KIND_HISTORY`（不再伪装成 HABIT/PREFERENCE）。
  - `MemoryStore.digest` **保留**（不打断「我的」页那一侧与既有测试），但 javadoc 降级为「**已经不是主模型画像入口了，新代码不要用**」；新增 `KIND_HISTORY` 常量并写明它与 HABIT/PREFERENCE 的区别不是显示分组、而是**不许被读成偏好**。
  - **修正初版的判断错误**：初版把这条旁路记成「既有旁路、现实中还打不起来、留给以后」，量错了标准——当时看的是「新写的来源分类会不会被它干扰」，而要求原文说的是「一次预约不能自动成为长期习惯」。旁路当场就在生效。已在 `PITFALLS.md` 与 `DECISIONS.md` DEC-026 中改写这段表述，并删掉「该旁路目前还不会产生实际问题」。
- **修复二：明确偏好的时效措辞**。`profileMemoryText` 原文案「这些是老人自己说过要让记着的，**可以直接当作他的偏好**」会把旧偏好说成现在仍然有效。改为历史事实口径：「这些是老人曾明确表达的偏好，**附带更新时间；如果时间较久或与当轮要求不一致，需要询问现在是否仍适用**」。**本轮刻意不发明「多少天算过期」的阈值**——那是个需要产品依据的数字，编一个反而会变成新的、没人验证过的规则。软删除记录继续不返回；active 但时间较久的不覆盖当轮明确要求，也不说成现在仍确定有效。
- **修复三：写反的日期范围**。`appointment.history` 的 `from` 晚于 `to` 时，原实现把它当普通空结果，会答成「没有查到历史记录」——把用户的口误说成事实。现在 `ProfileQueryService.HistoryQuery` 新增 `rangeReversed()`，`showAppointmentHistory` 在**执行任何历史 SQL 之前**返回「您说的开始日期 X 晚于结束日期 Y，这两个日期是反的，我没法按这个范围查。请重新说一下要查哪一段时间。」不执行 SQL、不回答成「没有历史记录」、不改草稿/阶段/确认卡。
- 测试：`ProfileReadTests` 19 → **23 项**（测试 8 重写为对**整段模型提示词**的端到端断言；新增 4 项：一次预约不进模型上下文 / 旧明确偏好按历史表达 / 旧偏好不覆盖当轮要求 / 反向日期范围被拒不算空结果）。**审查特别要求的「不得通过把断言改到 `ProfileQueryService` 返回值上来绕开整段模型提示词」已照办**：`ScriptedModelGateway` 现在记录每一条 `ModelRequest.Message`，断言落在模型实际收到的**每一条非 system 消息**上（不过滤 system，是因为提示词里本身带着「不许说常去／习惯」这条禁令，禁的是模型说出去，不是禁止提示词教它别说）。`UserMemoryTests` 7 → **8 项**（改写期望措辞，新增「一次预约被存成历史事实而不是习惯」：措辞不含「常去」「习惯」，`kind` 必须是 `KIND_HISTORY`，**没有降低「一次预约不是习惯」的要求**）。
- 验证：`ProfileReadTests` 23 + `UserMemoryTests` 8 + `SlotQueryArgumentTests` 17 + `AgentRuntimeRoutingTests` 17 + `LlmConversationPlannerTests` 6 + `AgentSystemPromptQueryFactsTests` 4 + `AgentSystemPromptTimeTests` 3 + `AgentSystemPromptVisionTests` 5 + `DemoSeedDataTests` 6 = **89 项 0 失败 0 错误**；后端全量 **522 项 0 失败 0 错误**（`mvn -o test`，修复前 517 项）。
- **真实模型评测本轮没有重跑**：本轮改动确实动到了提示词内容（删掉 `knownFacts` 的记忆段、改画像偏好文案）与写入措辞，理论上会影响模型输出。**如实标记：这三处修复只有桩测试（`ScriptedModelGateway`）覆盖，没有真实模型复测，不冒充已验证。** 8A 原版的四个真实模型场景是在**修复前**的代码上跑的，其结论不能直接平移到现在这份代码上。
- **收口轮（同日）明确记录：8A 修复后的真实模型与真机测试尚未执行，留到全局语音整合和最终验收阶段；不得把修复前的真实模型结果当作修复后结果。** 本轮不重跑真实模型、不做真机测试。
- **现有正确行为未被破坏**（第 4 项，全部由 `ProfileReadTests` 原有测试守住并按上述结果全绿）：服务端注入身份、`care_relations` 权限检查、字段白名单与 `HISTORY_LIMIT`、只说「预约过」不说「去过/看过/就诊过」、只读查询不动草稿与确认卡、`appointment.history` 与 `appointment.queryMine` 分开、当轮要求优先于历史。
- **措辞收口**：`docs/05-api-contracts.md:253`、`docs/03-agent-workflow.md:132`、`docs/04-architecture-and-modules.md:125`、`docs/02-pages-and-interactions.md:189-196`、`docs/09-demo-acceptance-checklist.md:132` 五处仍把记忆内容描述成「常去的医院是……」的文档已同步成新的中性口径；`frontend/features/profile/profile-view.tsx:124` 的「我的」页空态文案「记下常去的医院和科室」也改成了「记下最近一次确认预约的医院和科室，您可以随时让它忘记」。**前端只动了这一处用户可见文案**：不改页面结构、不加交互、不动接口，也没把「预约历史」说成实际就诊历史。
- 未提交、未推送，等审查。不进入 8B。

## 2026-09-14 第八阶段 8B-1：基于真实数据的个性化推荐（只读，不写偏好）

- 范围：老人说「根据我以前的情况推荐一下」「我不知道去哪家医院」「帮我看看哪个时间合适」「上次预约的那家这次还有号吗」「有没有不和我日程冲突的时间」「给我推荐几个可以预约的选择」时，**读真实信息、给 2—3 个选择**。**不做偏好保存/修改/忘记**（8B-2 尚未开始），不做完整推荐排序，不自动选医院/科室/号源，不做全局麦克风，不碰队友负责的备忘与家属报告。
- **一个工具、一个路由都没新增。**推荐证据全部由既有只读工具提供：`hospital.list` / `hospital.search`、`department.list` / `department.search`、`appointment.querySlots` / `appointment.queryNearbySlots`、`appointment.history`、`profile.memorySummary`、`schedule.checkConflict`、`travel.routePlan`。刻意不做「万能推荐工具」——那会把工具选择、参数校验与结果编排一起收进一个 Java 方法，等于把「模型决定查什么」又收回 Java。有依赖的调用串行、无依赖的可并行、同工具同参数不重复调，继续用既有的有界只读循环（`MAX_MODEL_TOOL_ROUNDS = 3`、`Set<String> executed` 去重）。
- **只补了两条编排缝，都是「既有只读路径在推荐轮里会顺手写状态」造成的**：
  1. `schedule.checkConflict`：模型发起的调用以前落到 `checkSchedule(state)`（办理那条），会推进阶段、写 `conflicts` / `alternatives`、置 `scheduleChecked`，并可能经 `checkDuplicate` 生成确认卡——而 `finalizeToolEvidence` 收口时**真的会落盘**。现在入口按 `outcome.modelDriven()` 分开：模型发起的走新增的 `checkConflictReadOnly(state, outcome)`，只回答冲不冲突；按钮/规则流程逐字保持原样。`date` / `time` 由本次 `arguments` 决定，缺了才回草稿取；`start` 早于 `clock.now()` 时如实说「已经过去了」而不去查；冲突**点名说出**「有冲突」并最多列 `CONFLICT_DISPLAY_LIMIT = 3` 条，不把冲突时段混进候选里当普通时段。
  2. `travel.routePlan`：以前无论怎么调都走 `showTravelGuide`，它只能解析**已有的**确认预约、并会发 `UiDirective.travel(...)`（推荐轮里替老人「打开地图」等于把他从比较的半路上带走）。现在给这条工具加了结构化的 `mode`（`APPOINTMENT` / `CANDIDATE`，见同日「审查收尾」一节）与 `hospital` / `date` / `time` / `transport` 四个**可选**候选参数，模型带候选条件来时走新增的 `routeEstimateReadOnly`：不写 `travelPlan`、不发导航指令、保留「（模拟路线，供参考）」措辞；查不到路线就如实说查不到，**绝不按距离或经验编一个用时**。
- **Java 保证的（可断言，`RecommendationTests` 钉住）**：模型发起的推荐轮**一个草稿字段都不写**（`hospital` / `department` / `departmentId` / `date` / `requestedTime` / `selectedSlot` / `alternatives` / `conflicts` / `travelPlan` / `stage`）、**不建也不替换确认卡**、不发导航指令、`appointments` 表不新增行；候选清单一律 `limit(3)`；条件冲突时按 5B 口径「这一次的参数决定这一次查什么」，缺条件就如实追问；拿到真工具结果才说事实。**顺带修掉一个既有缺陷**：`recommendHospitals` 原来无条件把 `departmentId` 清空（同一个科室再推荐一次就会抹掉院内指引依赖的目录 ID），现在只在**科室真的换了**时才走既有重置口径；并且在**模型发起的轮次里干脆不写草稿**（`modelDriven` 为真时整段跳过），不给模型留一条「说是推荐、其实顺手把科室定了」的缝。
- **提示词保证的（只降低概率，不是护栏，报告与文档都必须这么写）**：`AgentSystemPrompt` 新增一节「推荐：先查真实数据，再给两三个选择」——六句触发说法 + `intent=REQUEST_RECOMMENDATION`、七类可用证据、五级优先级（当前要求 > 当前排除 > 仍适用的明确偏好 > 统计倾向 > 单次历史）、十一条规则（2—3 条上限 / 理由必须能追溯到本轮工具结果 / 无可靠距离不说「最近」/ 无真实号源不说「可以预约」/ 一次预约不说「常去·习惯·一直选择」/ 已取消不说成仍有效 / 只说「预约过」不说「去过·看过·就诊过·到过医院」/ 不按症状选科 / 不评价「最好·最专业·治得最好」/ 模拟数据保留「模拟」/ 条件不足只追问一个最关键的问题）、以及只读约束与「下一轮明确选择走正常业务动作」。审查收尾时新增第 12 条：排除目标要放进 `facts.excludedHospitals`（字符串数组，或只有一家时写一个字符串），不要只写在 `replyDraft` 里当作已经排除，也不要用「、」把两家拼成一个字符串。
- **依据的优先级由 Java 摆、模型读**：`currentDraftNote` 把草稿里已定的条件单独成段并标明「优先级最高，历史与统计都不能覆盖它」；`appointmentHistoryText` 把「事实」与「统计（多次记录形成，不是老人明确说过的偏好）」分开；`profileMemoryText` 把「曾明确表达的偏好（带更新时间）」与「系统归纳的历史事实」分开。**「当前明确排除」由 Java 落成了状态**：模型在 `facts.excludedHospitals` 里给出排除目标，Java 拿去和真实医院目录比对、在生成推荐文字与快捷按钮**之前**过滤掉（见同日「审查收尾」一节）。
- 新增测试：`application/RecommendationTests.java` **38 项**（26 + 审查收尾 12），覆盖要求的 24 条（当轮医院压过历史 / 当轮日期时段压过统计倾向 / 已排除的不推荐 / 一次预约不成「常去」/ 多次预约才出统计 / 旧明确偏好不覆盖当轮 / 忘掉的不当当前事实 / 没号的不能说可预约 / 只有已取消⇒没有当前有效预约 / 无距离数据不说最近 / 路线理由必须有真结果 / 冲突时段不默认排在无冲突前面 / 条数上限 / 医院科室日期时段都来自真工具结果 / 推荐轮不写草稿 / 不建不替换不消费确认卡 / 拒绝后不自动写历史选择 / 下一轮明确选择走既有业务动作 / 家属只能读已绑定长辈的证据 / 越权目标不给推荐与历史摘要 / 同工具同参数不无限循环 / 工具失败⇒不编造推荐 / 模型不得输出医疗效果排名或按症状选科 / 模拟标识保留）。其中「模型不得输出医疗效果排名或按症状选科」仍然是**提示词层断言**，测试文件头部的类注释里逐条写明了哪些是 Java 层保证、哪些只是提示词里有这条规则；「已排除的不推荐」在审查收尾后已经变成 Java 层保证（`withExclusionsRemoved` 在拼推荐文字与按钮之前过滤）。另新增 `application/CheckConflictBoundaryTests.java` **2 项**，把业务时钟钉死在北京时间 2026-09-16 09:00 整，验整点边界。
- 验证（Dev Container `/workspace`）：先跑新增专项 `RecommendationTests` 26 项全绿；再跑 `ProfileReadTests` 23 + `SlotQueryArgumentTests` 17 + `AgentRuntimeRoutingTests` 17 + `UserMemoryTests` 8 = **65 项 0 失败 0 错误**；随后**重跑后端全量 `mvn -o test`：548 项，0 失败 0 错误，BUILD SUCCESS**（本轮改动前为 522 项）。改动落在 `FollowupAgentService` 的 `dispatchOutcome` 这个**无 default 的 switch** 上，且 `recommendHospitals` 的草稿写入行为有变，所以这轮按实际影响跑了全量而不是只跑专项。
- **本轮没有跑真实模型。**如实标记：**8B-1 真实模型语义尚未验证，自动测试不能证明模型一定会选择正确工具，留待用户手动测试及最终验收。** 全部 26 项都是脚本模型（`ScriptedModelGateway`）驱动的桩测试——它证明的是「Java 侧的只读保证、条数上限、证据来源与措辞规则都在」，**不能证明真模型会去查 `appointment.history` 而不是凭上下文猜**。也没有做真机测试（本轮不需要）。
- 未解决风险：
  1. **「当前明确排除」已由 Java 落地，但排除目标的解析要靠模型给对名字。**老人说「别推荐市第一医院」之后，Java 会把 `facts.excludedHospitals` 里的目标拿到真实医院目录里比对并过滤，认不准就澄清、不给推荐。**这条约束现在活着穿过整个只读工具循环**（见同日「第二轮审查收尾」）：先查历史、查画像、下一轮才推荐，同样过滤。残留风险仍在模型侧：它得把老人那句话里的目标正确地写进结构化字段；写错名字时 Java 只会说「没找到这一家」，不会替它猜。
  2. **模拟标识的去向要说清楚**：`CareCatalogRepository` 会剥掉目录里医院名/地址的「（模拟）」，所以「模拟医院资料如下」这句限定语是 Java 唯一保留的模拟标识（路线那侧是「（模拟路线，供参考）」）。
  3. 五级优先级的**排序**仍然是模型读证据自己排，Java 只保证这几类信息带着来源出现在同一段证据里，没有排序逻辑。
  4. **工具循环的预算：已有固定 3 轮上限与同签名去重**（`MAX_MODEL_TOOL_ROUNDS = 3`、`Set<String> executed`），**尚未实现的是整轮超时、可配置的调用预算这类能力**——不是「都还没做」。两者都是硬编码常量而非配置项，改上限要改代码。
- 与队友改动的文件冲突：业务专属文件零重叠——**没有碰** `HealthRecord*` / `HealthReport*` / `Memo*` / 家属报告发送 / 自动周报 / `ConfirmationService` / `ConfirmationDispatcher` / `PendingOperation.Kind` / `ConversationState` 的确认字段 / `ConversationStore` 的确认快照 / 全局麦克风 / 前端页面 / `MemoryStore` 的写入 API / 外部 HTTP 接口。本轮只改了 `FollowupAgentService`、`ToolRegistry`、`AgentSystemPrompt` 三个**共享**文件（与 8A 同一批），**存在 Git 合并冲突可能；提交前需与负责备忘和家属报告的队友确认**。对外 HTTP 接口与 `AgentTurnResponse` 字段零改动，`docs/05-api-contracts.md` 与 `records/INTERFACE_CHANGES.md` 无需改动（工具清单不进 HTTP 契约）。
- 未提交、未推送，等审查。不进入 8B-2，不做全局麦克风，不做偏好写入。

### 同日审查收尾（未提交）：双模式契约、结构化排除、整点边界

- **背景**：8B-1 的第一次实现把 `travel.routePlan` 的两种语义用「带了哪些参数」猜，并且「当前明确排除」只写在提示词里。审查指出这两处都不成立，另有一处时间边界不统一、一处文档表述含混。
- **其一，`travel.routePlan` 加结构化 `mode`（`APPOINTMENT` / `CANDIDATE`）。**两种模式都可能带交通方式，「参数形状」根本不是可靠的分界：
  - `APPOINTMENT` 必须给 `appointmentId`，Java 拿它去 `TravelGuideService.forAppointment` 的 SQL（`WHERE a.user_id=? AND a.id=?`）校验**归属**，越权和不存在回同一句话（不说它在不在），然后照旧打开地图、发 `UiDirective.travel(...)`。
  - `CANDIDATE` 用 `hospital` / `date` / `time` / `transport`（缺的回退到草稿），只回估算，不发页面指令、不写草稿。
  - 不给 `mode` 时按参数唯一推断（只给 `appointmentId` → APPOINTMENT；只给候选条件 → CANDIDATE；一个参数都不给 → 既有导航流程原样）；**混着给、或 `mode` 与参数对不上，一律澄清**，不猜。
  - `ToolRegistry` 的描述不再写「不打开地图」这种一概而论的话，改成把两种模式分别写清；提示词同步。
- **其二，「当前明确排除」由 Java 落地，不新增任何中文关键词或同义词表。**排除目标由主模型结构化给出（`facts.excludedHospitals`，JSON 数组；只有一家时也接受一个字符串——**不做分隔符切分**），Java 用既有的 `CatalogEntityResolver` 和真实医院目录比对，**在拼推荐文字与快捷按钮之前**过滤：精确命中直接排除；唯一近似（「市一」）排除并**把理解结果说出来**让他有机会纠正；对不上目录或对上多家则**这一轮不给推荐**，说清是哪一步没认准。全被排除时说「没有别家了」并请他换条件，**绝不把被排除的医院悄悄放回来**；排除只在当轮生效，不落草稿、不进长期偏好。
  - 为此 `ExtractedFacts` 增加第 17 个组件 `List<String> excludedHospitals`（保留 16 参兼容构造，不波及规则抽取、确认动作与测试脚手架），由 `LlmConversationPlanner` 从规划 JSON 的 `facts` 节点读数组。
  - **取舍记录**：工具参数那一层是 `Map<String,String>`，表达多个排除目标只能靠分隔符切分，而医院名本身就可能带顿号、逗号、括号（「市第一医院（门诊部）」）——切错等于 Java 替模型猜它排除了谁。规划 JSON 的 `facts` 节点本来就是 `JsonNode`，直接读数组是假设最少的表达，所以选它，而不是给 `arguments` 发明一套转义约定。
- **其三，整点边界统一。**`checkConflictReadOnly` 里原来写 `start.isBefore(clock.now())`，于是**正好等于此刻**的整点会被判成「还没到」，答复「不冲突」；而号源查询（`appointment_time > 当前时间`）和确认闸门（`slotAlreadyPassed`，`!时间.isAfter(now)`）都把它算作已过去——同一个整点，一边说能约一边说过期了。改成 `!start.isAfter(clock.now())`。
- **文档修正**：此前几处「整轮超时与工具调用上限仍是配置项，未实现」的含混表述已改准确——**已有**固定 3 轮上限（`MAX_MODEL_TOOL_ROUNDS = 3`）与同签名去重（`Set<String> executed`）；**尚未实现**的是整轮超时、可配置的调用预算这类能力。
- 新增测试：`RecommendationTests` 12 项（APPOINTMENT 导航 / CANDIDATE 不导航 / 只给交通方式回退草稿 / 参数为空的老路径 / 混着给澄清 / 越权与不存在的 `appointmentId`；单个排除 / 多排除即排除所有候选 / 对不上目录 / 唯一近似的说明 / 不成为长期偏好 / 历史偏好也压不过排除）+ `CheckConflictBoundaryTests` 2 项（固定时钟整点）。
- **边界测试是双向验过的**：把 `!start.isAfter(...)` 临时改回 `isBefore` 重跑，`theExactCurrentInstantCountsAsAlreadyPassed` 如期变红（09:00 被答「与您已有的日程不冲突」），改回来后 2 项全绿。
- **验证**（Dev Container `/workspace/backend`）：专项 `RecommendationTests` 38 项、`CheckConflictBoundaryTests` 2 项全绿；**重跑后端全量 `mvn -o test`：562 项，0 失败 0 错误 0 跳过，BUILD SUCCESS**（本轮改动前为 548 项，净增 14）。这轮按用户要求本可只跑专项，但改动落在 `FollowupAgentService.dispatchOutcome` 这个**无 default 的 switch**、`ToolRegistry` 的工具描述与 `ExtractedFacts` 这个 record 上，都属公共分派与共享契约，所以决定跑全量。
- **真实模型仍未跑**，本节的结论与原来一样不覆盖模型语义：**8B-1 真实模型语义尚未验证，自动测试不能证明模型一定会选择正确工具，留待用户手动测试及最终验收。**
- 未提交、未推送，不进入 8B-2。

### 同日第二轮审查收尾（未提交）：排除穿过工具循环、transport 归位

- **背景**：第一轮审查收尾把排除做成了 Java 过滤，但只在「模型第一跳就要推荐」那条路上生效。审查指出：`runModelToolLoop` 先执行 `appointment.history`、`profile.memorySummary` 之后，`finishToolLoopDecision` 会直接交付模型的 `replyDraft`——首轮的排除集合没带过来，最终推荐也没经过 Java 校验，老人明确排除的医院照样会回来。
- **其一，排除成为「本次用户消息的轮内约束」，一路带着穿过整个循环。**新增 `FollowupAgentService.TurnReadConstraint`（科室 + 排除目标两样读条件，**只是循环里的一个局部变量**）：进循环时从首轮 facts 建起来，每次 `continueAfterTools` 之后把新 facts 并进来（排除目标取并集，只增不减；科室取第一次说定的那个）。不落 `ConversationState`、不写 `MemoryStore`，方法返回就没了——**老人下一条消息不再提，就是新的一轮、新的空约束**，用例里验了「下一轮两家医院都回来」。
- **其二，推荐路由的收口一律由 Java 从真实候选重装。**`finishToolLoopDecision` 在 `decision.route() == RECOMMEND_HOSPITAL` 时改走 `recommendHospitals(state, constraint.facts(), true)`：查本轮真实医院目录、应用排除、生成文字与按钮，**模型这一轮的 `replyDraft` 不作为给老人看的那句话**。理由是这段自由措辞没人能校验（Java 不解析中文，认不出里面点了哪几家医院），而「先查历史再推荐」恰恰是这个循环最常见的走法。照旧不写草稿、不建卡、不消费确认卡。
  - 残留缺口如实记下：回答阶段（`LlmAnswerGenerator`）还会拿 Java 的草稿去润色，那一层理论上能改写措辞、塞进名字——这一层本来就是「只改措辞不改问题」，且对**直接进推荐**那条老路同样成立，本轮不动它，写进报告。
- **其三，`transport` 归 CANDIDATE 专有。**第一版说明写「两种模式都可能带 transport」，`hasRouteCandidateArguments` 却把 transport 当候选专属——于是 `mode=APPOINTMENT + appointmentId + transport` 被误判成「混用」。现在：`transport` 只属于 CANDIDATE（用于候选路线比较）；APPOINTMENT 用预约和用户资料里已经存着的交通方式（`travelGuides.forAppointment` 自己取），**明确忽略这次传的 transport**，多带它不算混用、也不报错。混用检测只看 `appointmentId` 与 `hospital`/`date`/`time` 三项（新增 `hasRouteCandidateLocation`）。`ToolRegistry` 描述、系统提示词、Java 注释、测试四处口径一致。
- **其四，`routeModeAmbiguity` 拆成三句话，按错误类型给。**原来三种情形共用一句「请告诉我具体的日期和时间」——缺的是哪一条预约时，这句话把老人指错了方向。现在：APPOINTMENT 缺 `appointmentId` → 问「请从您的预约里选一条」；CANDIDATE 一个条件都没给 → 问「要去的医院或者时间」；`appointmentId` 与候选位置条件混用（或 CANDIDATE 却给了 `appointmentId`）→ 请他说明「看已有预约的路线，还是估算候选的路线」。都不摆候选。
- 测试：`RecommendationTests` 38 → **45 项**。新增路线 4 项（APPOINTMENT 忽略同调 transport / APPOINTMENT 缺编号问哪一条 / CANDIDATE 缺条件问医院或时间 / 工具声明与提示词都写明 transport 归 CANDIDATE），并改写混用那条断言新的三句话。排除部分把 `anExcludedHospitalNeverComesBackThroughTheHistoricalPreference`（原断言 `appointment.history` 为 0，证明不了「穿过历史工具后不回来」）**替换**成四条真跑工具循环的用例：先 `appointment.history` 再推荐（历史的常去医院正好被排除，断言 `traceCount == 1`、按钮只剩 h002、模型那句推荐原文一个字都没出去、草稿没写、下一条消息两家都回来）、history + profile.memorySummary 两轮后再推荐、模型点名推荐被排除与目录里没有的医院、查完历史发现候选全被排除。脚本网关加了一个 `answerReply`（回答阶段要说的话），只有回答层真说了话，`result.reply()` 才是可信的「老人实际看到的那句」。
- **两条双向验过**：把 `finishToolLoopDecision` 里的推荐重装分支临时短路后重跑，上面那 4 条循环用例如期变红、其余 41 条全绿；把 `hasRouteCandidateLocation` 临时改回含 transport 后重跑，只有 `anAppointmentRouteIgnoresTheTransportPassedInTheSameCall` 变红。两处随后都复原。
- 验证（Dev Container `/workspace/backend`）：专项 `RecommendationTests` 45 项 + 邻近 `CheckConflictBoundaryTests` 2 / `SlotQueryArgumentTests` 17 / `AgentRuntimeRoutingTests` 17 / `LlmConversationPlannerTests` 6 / `VoiceFirstP0Tests` 17 / `VoiceFirstP1Tests` 7 / `ClarificationFlowTests` 12 / `MultimodalImageTurnTests` 7 / `DemoScenarioTests` 5 / `CancelScopeTests` 6 = **141 项 0 失败 0 错误**；随后**重跑后端全量 `mvn -o test`：569 项，0 失败 0 错误 0 跳过，BUILD SUCCESS**（本轮改动前 562 项，净增 7）。改动落在 `runModelToolLoop` / `finishToolLoopDecision` 这个公共分派上，所以照例跑了全量。
- **真实模型仍未跑**，本节结论不覆盖模型语义：**8B-1 真实模型语义尚未验证，自动测试不能证明模型一定会选择正确工具，留待用户手动测试及最终验收。**
- 未提交、未推送，不进入 8B-2。

### 同日第三轮（2026-09-14，8B-1 定稿）：推荐改由模型结构化产出，Java 只验证

- **背景**：第二轮把推荐收口改成「Java 从真实候选重装、模型那轮 `replyDraft` 不作数」。这条把「排除被绕过」堵住了，但代价是**模型说的话一个字都不算数**：Java 拼出来的推荐只能按目录字段做固定陈述，说不了「您上次预约的就是这家、这次要不要继续」这类真正需要组织语言的话。而为了让它像人话，后面还挂着一层 `LlmAnswerGenerator` 润色——**那一层没人校验**，理论上可以改掉名单，Java 认不出来（DEC-029 的「代价一」把这条如实记成了残留缺口）。本轮按用户要求改掉这个架构。
- **新流程**：模型完成历史、画像、号源、路线等工具查询后，**在最后一次模型调用里同时产出结构化 `recommendations` 与最终话语 `answering`**。Java 只逐条验证结构化那一半；验证通过就把同一次响应里的 `answering` **原样**交给老人，**不再调用 `LlmAnswerGenerator`**。
  - `recommendations` 每项 `hospitalId` / `reason` / `evidenceRefs`（新增 `agent/planning/HospitalRecommendation`，与 `PlannerToolCall` 同级——它是模型契约的一部分，不是业务模型）。
  - `PlannerDecision` 与 `AgentRuntime.Outcome` 各追加两个组件，并保留 8 参 / 10 参兼容构造，其余调用点一处都不用改。
  - `LlmConversationPlanner`：`recommendations` 从 `root.path("recommendations")` 读数组，条数硬上限 8（**故意不按 3 截断**——超过 3 由校验层判 `TOO_MANY` 退回给模型自己删，Java 不替它砍前三条）；`answering` 取 `first(answering, replyDraft)`，别名只在这里收一次，其余路由照旧读 `replyDraft`。
- **Java 验证什么**（`checkRecommendations`）：医院属于本轮真实候选；未被 `excludedHospitals` 排除；本轮有科室时该医院真有这个科室；「有号、距离、用时」等理由具有对应工具证据；条数 ≤ 3 且 `hospitalId` 不重复；`answering` 非空。**Java 不根据画像排序、不自行决定替代医院、不重新生成推荐理由**——排序就是模型给的顺序，`deliverRecommendation` 只按这个顺序生成 `q("选择"+name, "SET_HOSPITAL", id)`，文字是 `note + answering`（`note` 只有唯一近似排除时非空）。
- **证据从哪来**：`traces.since(state.id, evidenceMarker)`，`evidenceMarker = traces.latestId(state.id)` 在**进循环时**取。判据是「本轮真的调用过该工具」∧「`success=true` 且 `response_json` 不是空的」∧（对号源/路线/科室/院内指引这类**关于某一家医院**的证据）「那一次调用的参数或结果里带着这家医院」。查了但没查到不算证据——`response_json` 是唯一能回答这件事的地方，会话里那段 `outcomeKind` 几乎恒为 `SUCCESS`，拿它当「有证据」等于没查；而 `{"total":0,...}` 这种非空对象要靠工具自报的条数（`total`/`returned`/`count`/`size`）识别。
  - 映射表与医院级工具集合都是**工具名集合**（`TRACE_TOOL_NAMES` / `HOSPITAL_SCOPED_TOOLS`），不是中文词表。
- **失败时不给 `answering`**：最多允许模型根据结构化错误修正一次（这次修正会消耗一轮，它可能先去点只读工具）；再失败返回一句中性安全提示（`recommendationUnavailable`），不出现任何医院名、不复述模型那句。**排除认不准 / 排除后没有候选**这两类是 Java 的终结句（`check.stopped()`），不交给模型再改一遍。
- **模型驱动这条路上 Java 自己写的终结句全部改走 `respondWithoutModel`**（推荐交付、排除理解说明、没有候选、安全提示）——这些是安全措辞，不能交给润色层改写。**不经过模型的规则路径（`modelDriven=false`，老人自己点「推荐」）保持原样**走 `respond`，行为一字不变。
- **`TurnReadConstraint` 原样保留**，仍是「本次用户消息内的排除条件」，只增不减、不落草稿不落记忆、方法返回就没了。
- **不写草稿**：整条路一个 `ConversationState` 字段都不写，不建卡、不消费确认卡、不推进阶段。**`AgentTurnResponse` 字段与对外 HTTP 接口零改动**，前端与 `docs/05-api-contracts.md`、`INTERFACE_CHANGES.md` 无需改动。
- **提示词**（`AgentSystemPrompt`）：JSON 骨架加 `"recommendations":[],"answering":null`；新增「推荐轮的最后一次调用：recommendations 和 answering 一起给」一节，写明三个字段的契约、条数上限、`hospitalId` 必须是本轮工具结果里出现过的真实 id、医院级证据的参数里要带上那家医院、`answering` 里只准出现 `recommendations` 里那几家、推荐轮 `replyDraft` 留空；并说明「Java 会逐条校验，校验不过会把结构化错误发回来，按错误改一次；再不行这一轮就不给推荐了——所以先查真实数据、再开口」。**第 12 条末句改写**：原文「推荐路由上给老人看的医院清单和按钮由 Java 拿真实候选重装，不要指望自己写在 replyDraft 里的医院名会被原样展示」与新契约直接矛盾，换成「`answering` 会原样给老人看（前面可能带一句 Java 的排除理解说明），所以只能提校验通过的那几家；不再有第二次润色」。
- 测试：`RecommendationTests` 45 → **56 项**。改写 11 条（推荐轮不再经过回答模型，断言从 `authoritativeDraft()` 换成 `result.reply()`），新增 11 条：校验通过逐字展示且**这次没有回答模型调用**；首轮一条工具都没查就给推荐 → 修正提示明说「你还没有调用过任何只读工具」→ 修正里真去查 → 下一轮合法推荐正常展示；修正后仍不合法 → 安全提示（不含模型那句、不含医院名、无 `SET_HOSPITAL` 按钮）；`hospitalId` 目录里没有；指向本轮没跑过的工具；指向跑过但没结果的工具（空 `appointment.history`）；医院级证据不是关于这家的；`answering` 提了被排除的医院；`answering` 提了目录里真实存在但不在已校验名单里的医院；超过 3 条退回而不是裁剪；按钮顺序与条数跟着 `recommendations` 走且草稿/确认卡/阶段一字未动。
  - 这轮踩的两个测试坑写进了 `PITFALLS.md` 同日两条：**Java 自己那次目录查询会变成模型的「证据」**（所以 `since()` 必须在 `prepareCandidates` 之前调用）、以及**「模型说话了」要看整个 messages 而不只是最后一个提示词**（修正轮之后往往还有一轮工具轮，`lastToolPhasePrompt` 会被覆盖）；还有一条更基础的：`appointment.history` 在 `resetData()` 清空预约后返回 `{"total":0,...}`，**「查了」和「查到了」是两回事**。
- **双向验证两条**（改完复原并 `grep -rn "TEMP-CHECK\|if (true" src/` 确认无残留）：
  1. 把 `recommendationUnavailable` 换成「照旧展示校验不过的 `answering`」→ `whenTheRepairAlsoFailsNothingOfTheModelSentenceIsShown` 如期变红（`"那还是推荐协和医院，真的很近。"` 里被排除的医院名漏了出去）。把它换成**整体短路校验**（无条件信模型）→ **14 项**变红，覆盖排除、候选、条数、证据、`answering` 包含检查与两条 Java 终结句。
  2. 把 `evidenceProblem` 短路成恒返回 `null` → **正好 3 项**变红：`evidenceFromAToolThatNeverRanIsRejected` / `evidenceFromAToolThatRanButFoundNothingIsRejected` / `evidenceAboutAnotherHospitalIsRejected`。
- 验证（Dev Container `/workspace/backend`）：专项 `RecommendationTests` **56 项 0 失败**；邻近 `RecommendationTests` + `CheckConflictBoundaryTests` + `ProfileReadTests` + `SilverAgentApplicationTests` = **117 项 0 失败 0 错误**；随后**重跑后端全量 `mvn -o test`：580 项，0 失败 0 错误 0 跳过，BUILD SUCCESS**（本轮改动前 569 项，净增 11）。改动落在 `runModelToolLoop` 收口与 `AgentRuntime` 的 outcome 契约上，属公共分派，所以照例跑了全量。
- **真实模型仍未跑**，本章结论不覆盖模型语义：**8B-1 真实模型语义尚未验证，自动测试不能证明模型一定会选择正确工具，留待用户手动测试及最终验收。**
- 未提交、未推送，不进入 8B-2。

## 2026-09-14 第九阶段演示版：全局麦克风与服务端导航指令（未提交）

- **范围**：只搭老人端全局语音和页面导航主干，不进入 8B-2，不合并健康记录、备忘和家属报告，也不修改后端业务与 HTTP 契约。
- **公共语音入口**：语音录制、识别、发送、会话、TTS、提示和导航提升到老人端公共层；公共容器包住全部老人端页面分支和记录二级页，切页不卸载。助手页改为复用公共能力，全站只挂载一个麦克风入口。
- **语义边界**：页面本地只保留返回和重复朗读两类纯界面命令；其他语音全部进入统一主智能体。前端不根据“地图、路线、预约”等中文词判断业务，不从回复文字中解析预约编号。
- **导航边界**：前端只认服务端返回的预约路线和院内指引指令。指令带真实预约编号时打开对应路线；没有指令时进入助手展示同一轮回复，多预约由后端继续澄清，候选路线估算不会被前端当成已确认预约地图。
- **并发与播报**：按下麦克风先停止正在播放的 TTS；进行中锁避免重复发送；递增请求序号保证旧响应不朗读、不执行指令、不抢导航。旧响应仍可进入文字记录的产品口径尚未做浏览器时序验收。
- **改动边界**：本轮只改 7 个前端文件（4 个既有文件、3 个新增公共语音文件），后端零改动，未触碰队友业务文件，未新增中文意图词表。
- **离线验证**：`npx tsc --noEmit` 通过；生产构建通过；临时 3411 端口启动成功并返回 HTTP 200；构建产物含公共语音、会话恢复和导航指令逻辑。临时服务已按端口停止。
- **尚未完成**：未在真实浏览器进入老人页面逐页点击；未验证麦克风权限、真机安全区域、大字模式和手机软键盘；预约地图与多预约澄清未做真实模型测试；当前页面、选中预约与路线模式尚未作为结构化上下文传给后端；请求乱序没有自动化时序用例；队友模块尚未联合验收。因此本轮只能称“演示版框架完成”，不能称第九阶段完整验收通过。
- **下一步**：有开发时间后先做浏览器和真实模型验收，再根据失败样本做最小修复；随后补页面上下文、并发自动测试和队友模块联调。不得用新增前端中文关键词替代主模型语义判断。
