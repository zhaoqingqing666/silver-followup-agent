# 项目进度记录

进度文件记录“当前事实”，不写大段过程描述。功能完成后由负责人更新，并附对应 Pull Request 或提交。

## 2026-09-15 业务时钟收成一份 `BusinessClock`；「这半天没号」说出真实理由（DEC-032）（未提交）

- 落实 **DEC-032**。演示里用户当场质疑：问「9月15日」（就是当天），助手答「只有下午有空位，具体是 15 点 30 分」，
  追问「啊上午没有吗」，答的是「**上午已经约满了**」——可库里上午那两格 `available` 全是 TRUE，没被别人占。
  真正的原因是**当天上午的时间已经走掉了**。
- **根因两层，要一起修**：① **时钟**：容器没配时区、JVM 默认 GMT（北京 −8h），而四个查号查询都用 H2 的
  `CURRENT_DATE/CURRENT_TIME` 滤「当天已过的时段」，于是当天上午整片被判成已过去；② **理由留白**：权威草稿里
  只写「共查到 N 个可预约时段，下午最早 15:30」，没说上午为什么没有——「约满」两个字**全仓零命中**，
  是模型给这个空档补的理由。
- ⚠️ **实测结论（新知识，已写进 `BusinessClock` 类注释）**：H2 2.3.232 的 `CURRENT_DATE/CURRENT_TIME`
  **不吃 `TimeZone.setDefault`**——同进程实测：改默认时区后 Java 的「现在」变了、H2 的没变。所以「设个环境变量」
  不是修法；`TZ=Asia/Shanghai` 与 JDBC URL 的 `;TIME ZONE=Asia/Shanghai` 都只是**环境/连接串上的巧合**。
- **修法一（时钟只认一份）**：新增 `application.BusinessClock`（`@Component`，时区取 `demo.zone`，默认
  `Asia/Shanghai`；`BusinessClock.DEMO_ZONE` 是**全仓唯一**的时区字面量）。业务意义上的「今天/现在」一律走它：
  `FollowupAgentService`（11 处 `LocalDate.now` + 5 处 `LocalDateTime.now`）、`CareBookingService`、`CareService`、
  `MockAppointmentTool` 全换成 `clock.today()/nowDateTime()`；静态上下文（`RollingAppointmentSlotInitializer`、
  `RollingUserScheduleInitializer`）用 `BusinessClock.demoToday()`；`MemoParser.DEMO_ZONE` 改为引用
  `BusinessClock.DEMO_ZONE`（备忘链路原本就有时区，这次只是不再各写一份）。`devcontainer.json` 的 `TZ=Asia/Shanghai`
  **保留**：它管 `created_at`/`last_active_at` 这类**纯审计时间戳**，业务判断不走 JVM 现在——这条边界是刻意的。
- **修法二（SQL 不再碰数据库时钟）**：`MockAppointmentTool` 的三个窗口查询与 `CareCatalogRepository.availableDates`
  去掉 `CURRENT_DATE/CURRENT_TIME`，改为取回后在 Java 侧按 `clock.isPast(...)` 过滤
  （`!moment.isAfter(now)`，**到点即算过**，与旧 SQL 同口径）。判据只此一处，容器/CI/开发机跑出来一样。
- **修法三（把「已经过了」和「没有号」分开说）**：新增 `AppointmentTool.queryDaySlots(...)`——「这一天仍可预约的
  号源，**不看时刻**」，**不登记进工具清单**（模型要的是「能不能约」，它回的是「为什么不能约」，属 Java 侧话术依据）。
  服务层据它产出三句话，都不再是笼统的「暂无号源」：`halfReason(period, slots, passed)`
  （`periodSummary`/`recommendPeriod`/`showPeriodSlots` 共用）、`noSlotLead(date, passed)`、`passedSlots(state)`；
  问句也跟着候选走——只有下午有号时不再问「您想上午去还是下午去」，改问「您看下午可以吗」。
- **修法四（重复预约的话术）**：`duplicateReply` 改成「〈今天 / 具体日期〉**这个时段您已经预约过了**」，
  当天那条说「今天」——老人脑子里装的正是「我今天上午不是约过了吗」。与日程冲突那句「这个时间与您的…冲突」
  分得开：一个是「您自己已经约了」，一个是「您那天有别的事」。
- **修法五（提示词）**：`AgentSystemPrompt` 的「异常情况」补两条（当天已过的时段说「已经过了」、**不许说「约满」**
  ——「约满」指名额被占完，与时间走掉是两件事；只有工具明说名额满才能说约满），`toolResultAnswer()` 补一句
  （结果里写明的理由要照说，工具没给理由就只说结果、**不要补理由**）。
- 测试：`SilverAgentApplicationTests` 新增 `aMorningThatAlreadyPassedIsExplainedAsElapsedNotAsFullyBooked` 与
  `aWholeDayThatAlreadyPassedIsExplainedWithTheElapsedTimes`——用 `@MockitoSpyBean BusinessClock` 把「现在」
  拨到当天 15:00 / 20:00（当天号源手工插：演示的「当天」多半不是心内科的放号日），断言回复含「已经过了」+ 具体
  钟点、且**不含**「约满」；既有两处重复预约断言随话术改成「这个时段您已经预约过了」（**只对话术，逻辑断言未动**）。
- 回归：**全量 427 例、0 失败 0 错误**（`mvn -o -B clean test "-Dagent.model.enabled=false"`，约 45s），
  `HealthReportTests` 12/12 全绿、`SilverAgentApplicationTests` 39/39（含两条新用例）、`HealthRecordFlowTests` 13/13。
  ⚠️ 但**中间那次只跑 11 个类的跑法是 2 例红**，都在 `HealthReportTests`
  （`recordsOlderThanTheWindowAreLeftOut`、`summaryCountsAveragesAndShowsRealReadings`，症状是「次数少一条」／
  窗口里一条不剩）；**紧接着单跑 `-Dtest=HealthReportTests` 也红 2 例、且换成了另外两个方法**
  （`aValueWithoutANumberStillGetsCounted:108`、`summaryCountsAveragesAndShowsRealReadings:98`）——
  **同一份代码在三次运行之间自己翻转（红 → 红 → 全绿）**，正是 PITFALLS 2026-09-13 那条既有时钟边界抖动
  （本次已追加复现记录）。判读回归结果时**别按方法名走**。
- 与本次改动相关的既有用例全绿：`SilverAgentApplicationTests` 39/39（含 `duplicateAppointmentIsExplainedBeforeAnotherConfirmationCanBeCreated`、
  `selectingAConflictingSlotIsBlockedBeforeCompanionOrTravelIsAsked`）、`MemoFlowTests` 45/45、
  `MemoParserClockTests` 33/33、`DoctorSlotModelTests` 15/15、`CareBookingServiceTests` 12/12、
  `CaregiverSessionTests` 11/11、`DoctorIntentTests` 10/10、`TravelAppointmentBindingTests` 10/10、
  `DepartmentScheduleAndCapacityTests` 8/8、`RememberedHospitalTests` 8/8、`AppointmentSubmitIdempotencyTests` 7/7、
  `ConversationLifecycleTests` 7/7、`DemoSeedDataTests` 6/6、`DemoScenarioTests` 5/5、`CancelScopeTests` 4/4、
  `ElderCancelEndpointTests` 3/3、`MockAppointmentAlternativesTests` 2/2。
- 接口零变化（新增的 `queryDaySlots` 是 Java 侧内部方法，**不进 HTTP、也不进工具清单**）、数据库结构未改
  ⇒ `INTERFACE_CHANGES.md` 无需登记。
- 文档同步：`DECISIONS.md`（新增 DEC-032）、`PITFALLS.md`（追加复现记录）、本文件。

## 2026-09-15 「这个时段约不上」在号源锁定时就说，不再等陪同、出行和通知都问完（DEC-031）（未提交）

- 落实 **DEC-031**。演示完整走办理时暴露：老人选定 9月15日 10:30 呼吸内科潘晓丽之后，助手接着问
  陪同 → 出行提醒 → 交通方式 → 「通知您的家属吗」→「就通知女儿小丽吗」，**这一整轮答完**才说
  「这个时间您已经有一条去市人民医院看骨科的预约了」。老人白答一轮，而且早就把这件事当成了定局。
- **根因**：两道「这个时段能不能约」的检查（`schedule.checkConflict` 查日程、`appointment.checkDuplicate`
  查同一时刻已有预约）都挂在 `ready()` 之后，而 `ready()` 要求陪同、出行、交通、通知、材料全部就位，
  所以它们必然排在最后。规则链路（`advance()` 的 companion→travel→transport→notify→materials→`checkSchedule`）
  和模型链路（`readyForMaterialLookup` 成立才 `checkSchedule`）**各有一份同样的顺序**，
  不是模型链路独有的毛病。
- **修法一（提前到号源锁定那一刻）**：`selectSlot` 是号源锁定的唯一出口（按钮 `SELECT_SLOT`、模型链路的
  `acceptRecommendedTime`、推荐后的确认三处都汇到它），在它末尾、`acceptAlternative` 落定之后立刻调
  新增的 `slotBlocked(state)`：查到日程冲突 → `conflictReply`（置 `CONFLICT`），查到同一时刻已有预约 →
  `duplicateReply`，都没有才照原样 `advance()` 去问陪同。
- **修法二（文案与按钮只有一份）**：冲突分支抽成 `conflictReply`、重复分支抽成 `duplicateReply`，
  预检和确认卡前检查两处共用；`checkSchedule` / `checkDuplicate` 的提示文字和按钮**一字未改**，
  它们在确认卡前仍是最后一道防线（老人中途改时间后还得靠它们）。
- **修法三（「仍保留」之后不能再弹第二次）**：预检提前弹冲突时 `ready()` 还不成立，老人按「仍保留这个时间」
  之后会回落到 `advance` 把剩下的问完，最后仍要走到 `checkSchedule`——那时冲突还在，会**第二次**弹同一个
  提示，老人永远到不了确认卡。收口：`ConversationState` 新增 `conflictKept`（随 `state_json` 走 Jackson，
  **不动数据库结构**，旧快照缺这个字段补 `false`，正好是「还没保留过」）；`keepConflict` 置 true 并在
  `!ready` 时继续 `advance`；`slotBlocked`（换号源）/ `conflictReply`（重新问）/ `clearDraft`（新建办理）
  三处置回 false；`checkSchedule` 开头按 `conflictKept && !conflicts.isEmpty()` 短路。
  `actInternal` 的 `KEEP_CONFLICT` 分支改为共用 `keepConflict` 一处实现（原先它自己另写了一遍）。
- **踩坑**：第一版短路判据借的是 `scheduleChecked`，被新加的用例当场抓出来——`actInternal` 里
  `invalidate(state)` 会被**每一个** `SET_*` 动作触发，老人一答「需要陪同」标记就没了，
  答完通知之后冲突照样弹第二次。所以 `invalidate` **刻意不清** `conflictKept`：
  陪同、出行和通知答了什么，都不改变「时间没变」这件事。
- 顺带收口一条以前走不到的路径：`keepConflict` 原来直接调 `checkDuplicate`（后者要求 `ready()`），
  不 ready 时会回落到 `validateDraftForModel` 报「还缺少：陪同需求」这种对老人没法执行的话；
  改动前冲突只在 `ready()` 之后弹，这条路径碰不到，提前预检之后它会变成必经之路。
  非冲突态的守卫两条路径本来就有（按钮路径在 `actInternal` 里已被「请先检查当前日程」拦下），**行为不变**。
- 演示脚本同步：`DemoScenarioService` 场景三的 steps 从「最后一步答完，助手提示冲突」改成
  「点完 10:30 当场提示」，并把「仍保留」之后的继续追问写进步骤。
- 接口零变化、数据库结构未改 ⇒ `INTERFACE_CHANGES.md` 无需登记。
- 测试：`SilverAgentApplicationTests` 新增 `selectingAConflictingSlotIsBlockedBeforeCompanionOrTravelIsAsked`
  （断言 `SELECT_SLOT` 当场返回 `CONFLICT`、`KEEP_CONFLICT` 后接着问陪同、答完直接进确认卡且不再重复弹冲突）；
  既有冲突用例 `noSlotAndConflictAcceptNaturalRecoveryInstructions`、
  `changingTimeClearsAnAcknowledgedConflictFromTheCard`、`rescheduleKeepsOriginalUntilConfirmed`、
  `failedRescheduleKeepsOriginalBookingAndReminders`、`missingInformationCannotBeBypassed`
  **断言一字未改、全部通过**。
- **全量 425 例，仅 `HealthReportTests` 2 例失败**（`summaryCountsAveragesAndShowsRealReadings`、
  `recordsOlderThanTheWindowAreLeftOut`）。该文件**单独跑 12 例全绿**；与 `HealthRecordFlowTests`
  同跑则失败 1 例，且三次跑下来失败的方法各不相同（三个方法轮流挂）——是既有的**窗口边界抖动**
  （⚠️ **不能**据此推出「跨类污染」：那两棵树都只跑了一次，PITFALLS 2026-09-13 条目已说明这条推论不成立），
  与本次改动零交集。详见 `PITFALLS.md`。

## 2026-09-14 「照旧」也算答了；同一时刻的冲突不再分医院科室（DEC-030）（未提交）

- 落实 **DEC-030**。同一段演示里又暴露两件事：① 助手问「还是像以前一样，去市人民医院吗？」，
  老人答「**和上次一样**」，助手仍回「我还没有确认您说的是哪家医院」（改口说「是的」才认下来）；
  ② 事项页上同一位老人名下两条 **9月15日 10:30**——骨科沈国安 / 呼吸内科潘晓丽，一个身子坐进两个科室。
- **根因一（认不了「照旧」）**：`confirmRememberedHospital` 的入口判据是 `isShortAffirmative`，
  整句只能由「是/要/好/行/可以/…」加语气词构成，且去标点后 ≤4 字。「和上次一样」五个字两条都不满足，
  于是掉回普通链路。**Java 手上已经有候选（记忆里那家），只是不认这句话**。
- **根因二（跨科室同一时刻放行）**：`checkDuplicate` 的检索把医院和科室一起传了下去
  （`myAppointmentTool.search(id, userId, date, state.hospital, state.department)`），所以只拦得住
  「同院同科同时间」。换个科室（甚至换家医院）同一时刻照样放行——那两条就是这么进来的。
  照护端反而拦得住（`CareBookingService.book` 有 `hasUpcoming`，同一位老人名下只留一个进行中预约），
  所以这两条只可能来自老人端。
- **修法一**：新增 `SAME_AS_BEFORE`（「和上次一样 / 跟以前一样 / 还是那家 / 老样子 / 照旧 / 老规矩」，
  整句匹配、去标点后 ≤8 字、带否定词一律不认）与 `confirmsRememberedHospital`，换掉那处判据。
  `isShortAffirmative` **一字未动**——它还被完成播报和确认卡共用，放宽它会顺手改掉那两处的语义。
  没有记忆时「和上次一样」照样无处可落，回落成问名字（新测试钉住）。
- **修法二**：`checkDuplicate` 改按「**患者 + 日期 + 时刻**」判——检索传 `null, null`，只按日期取回
  这位老人的全部已确认预约，再按时刻相等过滤。**这是本次唯一的产品规则变更**，
  推翻了 DEC-026「明确不做 3」里那句「仍是患者 + 日期 + 医院 + 科室 + 时段」（那只是当时的阶段边界）。
  **同一天换时段仍然放行**：一天看两个科室是正常需求，只有时刻撞上才自相矛盾；
  改期仍排除 `originalAppointmentId` 自己。照护端不动（它的规则更严）。
- **话术随之改**：提示从「您已经有一条**相同**的复诊预约：…」改成「这个时间您已经有一条复诊预约：…。
  同一个人同一时刻只能看一个科室，我没有重复提交。」——跨科室时「相同」是错的。三个按钮不变，
  **没有新增「仍保留这个时间」**（同一时刻看两个科室不存在「保留」的合理场景）。
- 工具说明同步：`ToolRegistry` 里 `appointment.checkDuplicate` 的描述改成「同一就诊人同一天同一时刻、
  不分医院科室」——那份说明是给模型看的，不能留旧口径。
- 接口零变化 ⇒ `INTERFACE_CHANGES.md` 无需登记。
- 测试：`RememberedHospitalTests` 5 → 8 例；`AppointmentSubmitIdempotencyTests` 新增
  `theSameMomentIsRejectedEvenInAnotherDepartment`（手工插一条 d007 骨科 09:00 的号源，断言跨科室也被拦下），
  `draft` 加科室参数重载；`SilverAgentApplicationTests` 的重复预约断言随话术改成 `"已经有一条"`。
- **全量 423 例 0 失败**（基线 420 + 新增 3，`mvn -B clean test "-Dagent.model.enabled=false"`，须带 `clean`）。

## 2026-09-14 问医院那句话由 Java 说、也由 Java 认；科室候选一次摆全；助手页卡片收敛（DEC-029）（未提交）

- 落实 **DEC-029**。修的是同一段演示里的三件事：① 助手问医院时说了「还是像以前那样去市人民医院吗？」，
  老人答「是的」，助手却回「我还没有确认您说的是哪家医院」；② 科室按钮的「查看更多选项」翻开只有一个
  「我自己说科室」；③ 助手页同一屏出现两处在讲办理进度，办成后的事项卡又被后来的消息顶着走。
- **根因（一句话）**：写那句回填的环节读得到记忆，认领的环节却拿不到候选。演示开头的「开始复诊办理」
  是按钮动作，`act("CONTINUE")` 直接进 `advance → askHospital`（**不经过规划模型**），话术再由
  `finish()` 里的 `answerGenerator` 润色；润色模型看到 `knownFacts` 里的 `memoryNote`，顺手就把医院名补上了。
- **修法：把候选交回 Java 手上**。`askHospital` 命中 `user_memories.habit.hospital` 时，
  在问句后追加「还是像以前那样去X吗？」，并给「是的，X」/「换一家医院」两个按钮（走既有
  `SET_HOSPITAL`，不新造动作）；`chatInternalBody` 在 `handlePendingEntityConfirmation` **之后**
  插 `confirmRememberedHospital` 兜底认领——仍在等医院、老人给的是短肯定、记忆里确实有一家，
  三者同时成立才采用。**没有记忆时一个字不多说**，链路与改前逐字相同（两条测试分别钉住
  「不多说」和「仍要问名字」）。
- 读写措辞对齐：`MemoryStore.remember` 写「常去的医院是X」，读回来按 `MEMORY_HOSPITAL_PREFIX`
  取医院名再回目录核对（目录返回的是清洗过的名字，如「市第一医院」）。目录里没有这家（记忆过期）
  就返回空——**记忆只是少问一句的线索，落库仍过确认门禁**（DEC-013：记得住 ≠ 可以替他办事）。
- **科室按钮一次摆全**：`askDepartment` 从「前 3 个科室 + 我自己说科室」改为「目录前 6 个」
  （现在每院正好 6 科）。老人本来就能直接打字或说科室名，「我自己说科室」让出位置，
  「查看更多选项」的翻页这才有意义。**前端零改动**。
- ⚠️ **一处实施时撤回（记一笔，免得下次又走一遍）**：曾打算在 `modelSuggestedReplies` 里补
  `ASK_HOSPITAL` / `ASK_DEPARTMENT` 两处按钮，当场打红
  `SilverAgentApplicationTests.unrelatedConversationPausesTaskWithoutLosingItsStage`——那两处按钮会让
  「没被接住的话先重复问一遍」分支的 `waitingReplies` 变成非空，于是**任务不再挂起（PAUSED）**。
  要修的是**按钮内容**（3 个科室变 6 个），不是**有没有按钮**。已撤回，`modelSuggestedReplies` 一字未动。
- 前端（`assistant-view.tsx`）：删掉与顶部状态行重复的「复诊办理待继续」卡，「取消本次办理」挪到
  状态行右侧（只在办理中出现）；绿色的「复诊事项卡」移出对话流、摆在原卡片位置，不再被后来的消息往上顶。
- 接口零变化（`QuickReply` 仍三分量）⇒ `INTERFACE_CHANGES.md` 无需登记。照护端
  （`care-assistant-view.tsx`）本次未动，它保留自己的卡片与按钮。
- 测试：新增 `RememberedHospitalTests`（5 例）——有记忆时说出那家并给两个按钮、老人口头「是的」由 Java
  认领、没有记忆时一个字不多说、没有记忆时仍要问名字、科室候选 6 个且不含「我自己说科室」。
  **全量 420 例 0 失败**（基线 415 + 新增 5，`mvn -B clean test "-Dagent.model.enabled=false"`，须带 `clean`）。
  期间 `HealthReportTests` 时红时绿、每轮换一个方法，属既有抖动（见 PITFALLS），重跑即绿。

## 2026-09-14 模型链路与规则链路对齐：日期/半天按钮、口语时间补槽、钟点口径共用（DEC-028）（未提交）

- 落实 **DEC-028**（方案 `docs/proposals/模型链路与规则链路对齐方案.md`，范围由用户拍板 A+B+C）。
  修的是演示里三件事：日期清单**没有按钮**；说了「下午的三点半的」**记不住**（下一轮被退回「我还没有确认
  您想要的时间」）；回一句「可以」被弹回上一问。**不改排序规则、不改排班/名额、不改照护端、不重构巨类。**
- **A1 日期那一步改由 Java 出清单**：`modelWorkflowReply` 插分支——医院、科室已定而日期为空 → `showAvailableSlots`
  （自带 `SET_DATE` 按钮）。**日期是权威数据，不能让模型复述**：它编错一天，老人白跑一趟。
  代价是这一轮改用 Java 文案，模型给的过渡语不再使用，安全优先。
- **A2 半天档位接到模型链路**：`modelSuggestedReplies` 在原「有号源就给号源按钮」的分支**之前**插
  `SELECT_PERIOD → periodReplies`（上午 / 下午 / 直接选择具体时间）。**不是新做按钮**，用的是规则链路早就有的那一套。
- **A3 收紧前置分支**：原分支不看 stage，「还没选号 + 有候选号源」就发号源按钮；现在只有
  `SELECT_SLOT` / `CONFIRM_SLOT` 才发。**⚠️ 更正一处判断错误**：方案初稿说这会让五个「死 case」复活，
  **不成立**（那五个阶段的前提是 `selectedSlot != null`，与原分支的 `selectedSlot == null` 互斥）。
  「问陪同却挂号源按钮」的真实成因是 B 那个坑——`selectedSlot` 为空使 stage 停在选时间，
  而模型嘴上在问陪同。A3 的实际作用是让 `stage` 与按钮严格对齐，见 DEC-028 决定四。
- **B 口语时间在模型链路补槽**：落点 **`AgentRuntime.plan`**（不是 4800 行的巨类，且它已注入
  `RuleFactExtractor`）。**只补空、绝不覆盖**：模型给了 `selectedTime` / `timePreference` 就一字不动。
  配套 `ExtractedFacts.withSelectedTime`。原因是模型链路里 `facts` 是唯一事实源，漏一个字段没有任何东西去补。
- **C 钟点口径与备忘共用**：`MemoParser` 的钟点解析提为公开入口 `MemoParser.clockIn`，
  `RuleFactExtractor` 的抽取收敛成 `spokenTime`（`parseTime` 委托）。**不新写正则**。
  细节两条：「**下午**」按**整句**判（老人说「我要下午的三点半的」，「下午」与「三点半」隔着「的」，
  只看紧邻词会算成凌晨 3:30）；「3点半」与「三点半」对齐。
- **机制澄清（不是改动）**：模型/规则**不是两条并行模式**，是同一入口上的开关，每轮现算；
  运行环境里 `.devcontainer/.env` 的 `true` **覆盖** `devcontainer.json` 的 `containerEnv` 注入值 `false`
  ⇒ 演示环境实际是**模型链路**。⚠️ `echo $AGENT_MODEL_ENABLED` 看到的是注入值，**不能当判据**。
- 接口**零变化**（`QuickReply` 仍三分量）⇒ `INTERFACE_CHANGES.md` **无需登记**；**前端零变化**。
- 测试：`DoctorIntentTests` 9 → **10 例**（新增中文钟点落到 15:30 那条）；新增 `RuleFactExtractorClockTests`
  （6 例）与 `AgentRuntimeSpokenTimeFallbackTests`（4 例，假规划器注入 `AgentRuntime`，
  **现有测试里第一条走模型链路的用例**）。三类合跑 20/20 绿；**全量 415 例 0 失败**
  （`mvn -B clean test "-Dagent.model.enabled=false"`，须带 `clean`）。

## 2026-09-14 助手推荐话术与时段可见性（R7~R9，DEC-027）（未提交）

- 落实 **DEC-027**（接 `docs/proposals/选医交互方案（阶段2）.md` 的第二批，方案见
  `docs/proposals/推荐标记与时段可见性方案.md`）。用户原话：「推荐是指在科室日期确定之后给用户**说**
  『推荐…』，记得医生信息顺带时间」「不要让老人觉得只有推荐的几个时段」——
  **排序规则与候选上限（`MAX_OFFERED_SLOTS = 2`）一行没改**（用户：「基本还是按照原来的规则」）。
- **「推荐」定为话术，不是接口字段**：**否**掉方案初稿的 `QuickReply` 第 4 分量 `recommended` + 前端徽标。
  ⇒ **接口一个字段都没加**，`quickReplies` 仍是 `label / action / value`，`INTERFACE_CHANGES.md` **无需登记**
  （按钮与回复的**文案**变了，但那不是契约）；**前端一行都没改**。
- **只有自动推荐轮说「推荐」**：`recommendPeriod` 按 `state.doctorId == null` 分流到新的 `autoRecommendReply`。
  点名医生之后是「按您说的给」，指定具体时刻（`recommendSpecificTime`）、清单页（`showPeriodSlots`）
  同理，都**不说**，且非自动轮**沿用原文案一字不改**。措辞按用户要求「医生信息顺带时间」——
  医生在前、时间跟在后面：`我先推荐王建华副主任医师（专家号），09:00；另一位是陈凤兰主治医师（普通号），10:30。`
  **不报排序依据**（老人要的是结论，不是算法说明）。
- **报「当天的另一半」而不是「这个半天有几个」**：一天 4 格、每格 1 位医生，未点名时半天的候选数（≤2）
  **恒等于**该半天的号源数——「上午一共 2 个可约时段」只是把老人已看到的两个又数一遍，没有信息量；
  真正被藏起来的是**另一个半天**。新增 `dayScopeHint()` 报 `今天下午还有2个时段。`，
  条数**按 `state.date` 现数、不写死**。**取代 DEC-024 决定二**的「这位医生还有其它时段」
  （现行排法每位医生每天只 1 格，那句已不可能触发，`otherSlotHint()` 删除）。
  只在未点名时加——点名后 `alternatives` 已收缩到该医生，再数出来的是**别的医生**的号。
  明确**不做**「我挑了其中 M 个」：当前排班下该子句永远不成立，写进去就是无法回归的死分支。
- **推荐轮按钮从 4 个收到 3 个**：前端每屏**写死 3 个**（`slice(choicePage * 3, +3)`），
  双候选时后端原本发 4 个，被折进「查看更多选项」的第 4 个恰好是「改选另一半」——**另一个半天那两个时段
  的唯一入口**。现在只发「2 个候选 + `看全部{上午|下午}时间`」，`SET_PERIOD` 移出推荐轮；
  **出口一个没丢**（`showPeriodSlots` 那页本来就带「改选X」，半天 2 条 + 改选 1 条 = 3 条）。
- **口播**：`candidatesSpeech` 加开关，自动推荐轮在最前面加**一次**「我推荐，」——逐条念会把
  「这几条是我替您挑的」读成「每条都更值得约」；**口播不带条数**（数字只落卡片，R3 的短句口径不变）。
- 测试：`DoctorIntentTests` 7 → **9 例**，全绿。原推荐用例补 R7/R8/R9 断言；点名医生那条补
  「不说推荐、不报别的医生的号」负向断言；新增单候选用例（手工把 09:00 那格约满，
  **必须先约满再定日期**——`alternatives` 是定日期那步查出来缓存的）与指定时刻用例
  （「下午3点的号」→ 不说「推荐」，落到最接近的 15:30）。
- 文档：`docs/user-manual.md` 第 4 步「选时间」的按钮名与话术同步；方案文档状态行改为「已实施」。

## 2026-09-14 科室扩至两院各 6 科；每科室 4 位医生、一天 4 格、号源加名额（未提交）

- 落实 **DEC-026**（用户需求：两院都要有心内科 / 神经内科 / 内分泌科 / 骨科 / 呼吸内科 / 消化内科；
  每科室要两位主任；一天 4 格上午下午都要有专家号和普通号；专家号一场放 3 个名额、普通号 4 个）。
  **分期方式与 DEC-021 那次一致：只落数据层，助手交互逻辑先不动。**
- **科室**：`departments` 追加 `d007`~`d012`，补上原先缺的 6 个 (医院, 科室) 组合 ⇒ 两院科室名集合完全相同。
  现有 `d001`~`d006`、`loc-*`、`doc-*`、所有测试常量**一个都没动**（`DemoSeed.CARDIOLOGY`=d001、
  `ENDOCRINOLOGY`=d003 不受影响）；代价是 id 顺序不再连续。
- **医生编制 18 → 48**：`MAX_DOCTORS_PER_DEPARTMENT` 3 → 4。每科室 = 01 主任医师 `EXPERT` +
  02 / 03 主治医师 `NORMAL` + **04 副主任医师 `EXPERT`**。新增 30 位 = 现有 6 科各补 1 位 04 号 +
  新增 6 科各 4 位。姓氏一律避开同科室已有的姓（`matchDoctorId` 同姓会触发反问）。
- **排班形状换成「一天 4 格、每格 1 位医生、号别按格固定」**：09:00 / 14:00 是**专家格**，
  10:30 / 15:30 是**普通格**。`RollingAppointmentSlotInitializer` 里 `morningDoctors` / `afternoonDoctors`
  （按**半天**返回两位）**下线**，换成 `expertAt` / `normalAt`（按**时刻**取人）、`doctorAt` / `doctorsAt`
  （按时刻返回单序号）。**每半天的第 2 个放号日（`role == 1`）两组医生上下午对调**，于是四位医生
  各出 3 格、每天恰好 1 格，**工作量完全相等**——主任不再比主治忙（正是用户提这一点的原因）。
  `openWeekdays` 扩到 12 个科室，「各科室放号日不重复」的口径放宽为**同一家医院内**（每院 6 科，
  从 C(5,3)=10 种组合里挑 6 种；全院 12 科装不下「全院两两不同」）；`d001` 仍锁死**含周三 + 含周五**。
- **号源加名额（`capacity` / `booked`）**：`appointment_slots` 加两列，专家号 3、普通号 4。
  一条号源从「一个号」变成「一位医生在一个时刻里的一班」。**`available` 保留但降级为派生位**
  （`available = booked < capacity`）——这是「只改数据层、助手侧零改动」能成立的**唯一**做法：
  4 处查询（`queryAvailableSlots` / `queryUpcomingSlots` / `queryAlternatives` /
  `CareCatalogRepository.availableDates`）都按 `available=TRUE` 过滤，**一行都没改**。
- **写入全仓只有 4 处，全在 `MockAppointmentTool`**：`submit` 扣减（`booked+1`，`WHERE id=? AND booked<capacity`，
  `changed != 1` 仍抛「该号源刚刚已不可用」，语义从「被抢了」变成「**这一班约满了**」，仍是一条并发安全的乐观锁）、
  `cancel` 释放、`reschedule` 扣新 / 释旧；外加 `DemoScenarioService` 的重置改成 `SET booked=0, available=TRUE`
  （不改这句就是真 bug：`booked` 留着，重置后再约就变 2，不变式当场破掉）。
- **一个必须做的启动自愈** `reconcileCapacities()`：号源 id 拼法**没变**，所以上一版排班生成、又**有预约挂着**
  因而被 `removeStaleGeneratedSlots` 留下的那几行会停在建表默认 `capacity=1`（长得一样但只放得出一个名额）。
  四句幂等 UPDATE：按实际 `CONFIRMED` 预约数回填 `booked` → 按号别修正 `capacity` → 重算 `available` 两个方向。
- **废止「下午不排专家号」**：14:00 现在就是专家格，所以 DEC-023 决定二/六、DEC-024 决定三建立的这条规则
  被本次需求直接推翻。`MockAppointmentTool.requireAfternoonIsNotExpert()` 及其两处调用**删除**；
  上一轮新增的 `AfternoonExpertGuardTests`（4 例）**整份删除**。R1 的**时段级**降级能力本身还在
  （`expertOnlyInOtherPeriodReply` 原样），只是演示它要改成**手工把某格约满**。
- **供给侧约束改口径**：`everyDepartmentHasMoreNormalSlotsThanExpertSlots` → 改名
  `everyDepartmentHasMoreNormalCapacityThanExpertCapacity`，按**名额**比（8 : 6）而不是条数（2 : 2 相等）。
  另新增 `everyEnabledDepartmentHasFourDoctorsIncludingTwoExperts`、`everyMorningHasExactlyOneExpertAndOneNormal`
  （按**半天跨格**合计）、`theAfternoonNeverHasMoreThanTwoSlotsAtTheSameMoment`（按半天口径保留，守「某格被排了两位医生」）。
- **演示场景的两处代价**（皆由「4 格必须 4 人坐满」导出，非掩盖问题）：① 「**下午没有专家号**」消失；
  ② 「**点名本科室医生、他当天不出诊**」也消失——每位医生每个放号日都在岗，所以该用例改为
  **把他当天那班约满**（`booked=capacity`）来构造「他今天没得约」，`doctorNotFoundReply` 的
  「7 天内最近出诊日」分支照旧走通，且 `reset` 会自动还原。
- **接口字段一个都没动**：`Slot` / `AppointmentView` / `DateWindow.SlotOption` **不加** `capacity` / `remaining`，
  所以 `INTERFACE_CHANGES.md` 本次**无需登记**；名额要露给前端属阶段 2。
- 测试：全量 **393 例**（原 389）。`DoctorSlotModelTests` 4 条按新口径改写；`DoctorIntentTests` 6 条
  （体检日周三上午的专家由张建国换成 **04 号王建华 · 副主任医师**，候选 0 与口播断言随之改；
  `namingADoctorOnDutyScopesTheCandidatesToThatDoctor` 现按**号源 id** 断言——一天 4 格 4 人，
  点名医生当天只剩 **1 条**号源，走**单候选**路径，按钮 label 是固定的「这个时间可以」而非医生名）；
  `AppointmentSubmitIdempotencyTests` 2 条（「09:00 排两位医生」的前提不再成立）；
  `SilverAgentApplicationTests` 1 处手工占号改 `booked=capacity`；`@BeforeEach` 里
  `SET available=TRUE` → `SET booked=0, available=TRUE` 共 **12 个测试文件**；
  `CareBookingServiceTests` 2 处、`DemoScenarioTests` 1 处、`ElderCancelEndpointTests` 3 处改按 `booked` 读断言。
  新增 `DepartmentScheduleAndCapacityTests`（8 例）钉住新口径本身：每放号日 4 格 4 位不同医生、号别按格固定、
  两位主任轮流坐专家格、名额 3 / 4 落库、名额的扣减与释放、全表不变式、R1 时段级降级的新演法、
  两院科室名集合相同。删除 `AfternoonExpertGuardTests`（整份）。
- **DEC-025 的前提变了但结论没变**：那条「一条号源就是一个号」现已是「一班多名额」，不过
  「同一会话 + 同一就诊人 + 同一号源」判幂等依然正确——同一班的重复提交本就不该建第二条。
- 待后续：① 名额露给前端（阶段 2）；② 照护端「一个进行中预约」与老人端「同一天可约多个时段」的口径差
  仍需产品拍板；③ `FollowupAgentService` 仍是 4800+ 行的编排巨类（DEC-018 遗留）。
- **原待办 ④ 已做（同日）**：`CatalogEntityResolver.DEPARTMENT_ALIASES` 补上「呼吸科 → 呼吸内科」
  「消化科 → 消化内科」两对（`心血管内科 → 心内科`、`心脏内科 → 心内科` 本来就有，不必再加）。
  这两个科室是本次扩科室（DEC-026）才有的，而简称和全称**互不为子串**（`呼吸内科` 不含 `呼吸科`、
  `呼吸科` 也不含 `呼吸内科`），包含匹配两个方向都兜不住，只能落到 `NOT_FOUND`——所以必须进别名表。
  属**目录别名**，不是交互逻辑：别名只是「一级先行跳转」，命中后仍要回真实目录核对**恰好一条**同名科室，
  目录里没有或有两条就落空、照旧走包含匹配或 `NOT_FOUND`。因此**补别名不放宽科室校验**，也编不出
  一个目录里不存在的科室。新增 `CatalogEntityResolverTests`（9 例）把「两对别名生效」「既有别名与全称
  精确匹配没被碰坏」「目录里没有该科室时仍 `NOT_FOUND`」「多条候选不代选」「`内科` 仍 `AMBIGUOUS`」
  「不知道/随便仍 `UNCLEAR`」一并钉住。

## 2026-09-14 修复 `submit` 的会话级幂等：同一段会话换个时段不再被旧预约顶掉（未提交）

- 落实 **DEC-025**。`MockAppointmentTool.submit` 开头那句查重原本是
  `WHERE conversation_id=? AND user_id=? AND status='CONFIRMED'`，找到就返回它——把「这段会话里已经约过一次」
  当成了「这次提交是重复的」。老人端「新建办理」（`restartInCurrentConversation` → `clearDraft`）会清空
  `appointmentId`，所以第二次办理确实会走到 `submit`：前置的 `checkDuplicate`（患者 + 日期 + 时段）
  已经放行 10:30，`submit` 却把 09:00 那条原样返回——**页面提示办好了，库里还是旧那一条**。
- 改法（一句 SQL）：幂等键补上号源这一维，`WHERE conversation_id=? AND user_id=? AND slot_id=? AND status='CONFIRMED'`。
  **一条号源就是一个号**，所以「同一位就诊人 + 同一个号源」只可能是同一次提交，不会误伤「同一天换时段再约一次」。
  换号源 => 真的新建一条、返回新 id；同一次提交重复落下来 => 返回已存在那条。
- **没有新增幂等键**：全链路不存在 `requestId` / `toolCallId` 这类能标识「同一次提交」的字段
  （`CareBookingService` 的 `bookingId` 是每次调用现生成的追踪号，标识不了重复请求）。当前键够用，不加库表字段。
- **没有改产品规则**：`患者 + 日期 + 时段` 的冲突判定仍在 `FollowupAgentService.checkDuplicate`，未搬动、未削弱；
  照护端 `CareBookingService` 一行未动（`hasUpcoming` 与 `secondBookingWhileUpcomingExistsIsRejected` 原样，
  照护端每次一个现生成的 `bookingId` 当 `conversationId`，新键在那边与旧键行为一致，无行为变化）。
- 生产代码只两处：`MockAppointmentTool.submit`（SQL + 注释）、`AppointmentTool.submit` 补一段契约 javadoc。
- 测试：新增 `AppointmentSubmitIdempotencyTests` **6 例，全为新增，未改动任何既有断言**。
  **修复前先跑过一遍，3 例红**（端到端那条复现的正是「第二次也提示办好、库里只有第一条」）；修好后 6/6 绿。
- 回归：后端全量 **389 例，仅余 1 例已知抖动**（`HealthReportTests`，PITFALLS 2026-09-13/14 的时钟边界抖动）。
  相关既有用例全绿：`SilverAgentApplicationTests` 36/36（含 `concurrentConfirmationWritesOnce`、
  `duplicateAppointmentIsExplainedBeforeAnotherConfirmationCanBeCreated`）、`CareBookingServiceTests` 12/12、
  `CaregiverSessionTests` 11/11、`AfternoonExpertGuardTests` 4/4、`DoctorSlotModelTests` 15/15、
  `CancelScopeTests` 4/4、`DemoScenarioTests` 5/5。
- 文档同步：`DECISIONS.md`（新增 DEC-025；DEC-024 的「待后续 ②」标注为已修复）、本文件。

## 2026-09-14 号源聚合落点＋下午专家号第二道闸门＋照护端号源带医生（未提交）

- 落实 **DEC-024**（业务层小范围优化，**数据库结构未改**）。排班时段一行都没动，仍是 09:00 / 10:30 / 14:00 / 15:30 四格（用户明确保留，不改需求稿里的三格）。
- **推荐去重上移为共享纯函数**：`SlotRecommender.bestPerDoctor(List<Slot>)` 从 `FollowupAgentService` 的私有方法上移，`recommendPeriod` 改调它、删除私有实现。规则本身没变（原本就在那行三元开关里），变的是**出处唯一**——原先这条路埋在 4883 行的编排类私有方法里，外部拿不到、也没法单独钉住。未点名医生 → 每位医生只留排序最靠前一条；点名医生 → 这个函数**根本不调用**，直接给该医生全部时段。
- **推荐卡片补「还有其他时段」提示**：单候选回包时，若该医生当天还有别的可约时段就补一句。推荐卡片上那条时段只是**该医生的最优时段预览**，不是唯一时段；点进详情才展开全部。
- **下午专家号加第二道闸门**：`MockAppointmentTool` 新增 `requireAfternoonIsNotExpert(slotId)`，在 `submit` / `reschedule` **写入前**拦截「下午 + EXPERT」。第一道闸门仍排在排班侧（`afternoonDoctors` 只排普通号医生）；第二道保证即使绕过排班（脚本 / SQL 插脏数据），业务层也不接受。判断只看号源自身的 `slotType` 与时刻（上下下午分界复用 `RollingAppointmentSlotInitializer.NOON` = 12:00），**不看医生职称**——因此这条约束与「下午排几格」无关，是**整个下午**的约束。
- **照护端号源列表修复真实缺陷**：`CareBookingService.DateWindow.SlotOption` 补 `doctorName / doctorTitle / slotType / feeCents`；前端按钮从「09:00」改成「09:00 / 张建国 主任医师 · 专家号 · 挂号费 ¥40」（拼法复用 `doctorLine()`）。原来只给 `time`，同一上午两位医生各一条 09:00，页面上并排两个一模一样的「09:00」按钮，家属分不清点哪个（DEC-022 之后「同一时刻只有一条号源」已不成立）。属**追加可选字段**，已登记 `INTERFACE_CHANGES.md` 2026-09-14 条。
- 新增测试 **9 例，未改动任何既有断言**：`AfternoonExpertGuardTests`（新文件 4 例：下午专家号被拒、上午专家号放行、14:00 普通号放行、改期同样被拦）、`SlotRecommenderTests` 追加 4 例（含 Case 3「主任医师 + NORMAL 仍是普通号」、同名同刻不同医生不合并、无医生信息的旧号源各自独立）、`CareBookingServiceTests` 追加 1 例（windows 带出医生与号别）。
- 测试：后端完整回归 **383 例**，除 1 例已知抖动外全绿——`HealthReportTests.sendingWritesOneNotificationToThePrimaryContact`（单跑 4 次得到 2 绿 2 红、挂的方法还在两个方法间跳，属 PITFALLS 2026-09-13 那条时钟边界抖动，与本次改动无关；本次挂的**方法名不在**原文列举里，已在 PITFALLS 同日条目补记）。本次改动涉及的类全绿：`DoctorSlotModelTests` 15/15、`SlotRecommenderTests` 12/12、`CareBookingServiceTests` 12/12、`AfternoonExpertGuardTests` 4/4。前端 `tsc --noEmit` 通过，0 错误。
- 文档同步：`DECISIONS.md`（新增 DEC-024）、`INTERFACE_CHANGES.md`、本文件。
- **明确不做**（均记在 DEC-024「明确不做」里，非遗漏）：① 不引入 `stock` / `capacity` 容量字段——需求稿假设的「同一医生 + 同一天 + 同一时刻 3 条 slot」在本项目**物理上插不进去**（`slotId` 拼法已含医生，第二条撞主键，`DoctorSlotModelTests.noDoctorHasTwoSlotsAtTheSameMoment` 钉着）；② 不改照护端「就诊人名下只保留一个进行中预约」这条产品规则（需求 Case 5 要求「同一天可约两个时段」，与老人端助手路径的口径有差异，改它等于改产品规则 + 改已有用例）；③ 不改排班时段的格数。
- 待确认（**未开工**）：① 上述第 ② 条的口径冲突怎么收；② ~~`MockAppointmentTool.submit` 开头的**会话级幂等**查询——同一段会话里先约 09:00 再约 10:30 时，`checkDuplicate` 放行、`submit` 却返回旧预约 id，确认卡显示新时间而实际没落库~~ **已修复，见 DEC-025**；③ DEC-021/022/023 一直挂着的**阶段 2**（助手选医交互 / 排序规则 / 口播文案）。

## 2026-09-13 排班重做：每科室固定 3 个放号日 + 半天制，全院 18 位医生（未提交）

- 落实 DEC-022，**撤销 DEC-021 决定五**（「每位医生错开出诊日、同科室同一天只有一位医生」）。原因：新需求要「选好科室和日期后，列出这天有哪几位医生可约」，一天只有一位医生等于没有可选性；「指定日期要专家号、当天恰好没有」也缺一个真实存在的日子来演示。
- 排班形状（各科室一致，只有「哪 3 天」按科室错开）：每周固定放 **3 个工作日**的号，周末一律不放；放号日的**上午排 1 位专家 + 1 位普通**（09:00 / 10:30 各一条），**下午排 1~2 位**（14:00 / 15:30）。下午刻意留了**一天只排普通号**，专门给「指定日期要专家号、当天没有」当演示素材。各科室放号日：`d001` 一/三/五、`d002` 二/四/五、`d003` 一/二/五、`d004` 一/二/四、`d005` 二/三/四、`d006` 三/四/五。
- **「同一时刻只有一条号源」这个旧不变量作废**：上午 09:00 天然两条，界面与助手改由「医生 + 号别」区分。`DoctorSlotModelTests` 里依赖该不变量的那条用例改写为三条：上午恰好 1 专家 + 1 普通、下午同一时刻 ≤ 2 条、同一位医生同一时刻不重复。助手列号与家属代约的展示口径尚未改（属阶段 2）。
- **确定性是硬约束**：`openWeekdays` / `morningDoctors` / `afternoonDoctors` / `doctorsAt` 都是纯函数，同一「科室 + 日期」每次启动、每台机器都得到同一份，不用 `Math.random()`。放号日**固定、不随周次变**——曾想做成「每周重抽」，但那会让「体检日 = 下周三」「改期日 = 下周五」随机漂移，连带 `DemoSeedDataTests` 的口播断言与 `MockAppointmentAlternativesTests` 的三天窗口一起变红。
- 两处放号日**不能随手改**：`d001` **必须含周三**（冲突演示钉在「下周三体检 + 当天 10:30 心内科号源」）、**且含周五**（「下周六无号 → 往后三天」要取得到号）。已写进 `openWeekdays` 的注释。
- 医生编制 16 → **18**：每科室 3 位（01 主任医师 `EXPERT` / 02 主治医师 `NORMAL` / 03 副主任医师 `EXPERT`），**不再设住院医师**；`d004` / `d005` 各补一位副主任医师。少一位时 `doctorOf()` 直接抛 `IllegalStateException`，不静默少排一班。
- `doctors.clinic_weekdays` **下线**（`schema.sql` 配 `DROP COLUMN IF EXISTS` 兜旧库，`data.sql` 的 doctors MERGE 同步去掉该列）：出诊日是算出来的，不落库。
- 旧号源清理口径放宽为「删掉**所有 `id LIKE 'r-%' 且没有预约占用**的号源」（不再只删「无 `doctor_id`」那批）——排班口径整体换过，旧号源既可能落在不再放号的日子上，也可能和新排班撞时刻。有预约挂着的仍然保留。
- `DemoSeed.laterDay()` 改为「从体检日起找第一个**心内科与内分泌科同时放号**的日子」；`DemoSeed.slot()` 改用 `doctorsAt()` 反推号源 id，**反推不到直接抛错**（不再悄悄编一条）。
- `DoctorSlotModelTests` 11 → **14 例**：新增/改写「上午恰好 1 专家 + 1 普通」「下午同一时刻 ≤ 2 条」「同一位医生同一时刻不重复」「每科室恰好 3 位医生且至少 2 位专家」「重跑 `seed()` 号源集合一点不变」「每科室恰好 3 个放号日、各科室错开、周末无号」。
- 顺手修掉两条**用例自身**的坑（不是产品缺陷，已登记 PITFALLS）：① 按科室**名字**分组会把两家医院同名的「心内科」「内分泌科」并成一组（要用 `department_id`）；② H2 的 `DAY_OF_WEEK` 是 **ISO** 起点，`IN (1,7)` 数到的是周一而不是周末（周末判断改到 Java 里算）。
- 测试：完整后端回归 **358 例**，除 1 例**已知抖动**外全绿——那 1 例是 `HealthReportTests.summaryCountsAveragesAndShowsRealReadings`，与本次改动无关（`git worktree` 干净检出对照已证），症状是「次数少一两条」的时钟边界抖动，见 PITFALLS 2026-09-13。本次改动的 `DoctorSlotModelTests` **14/14 通过**，`DemoSeedDataTests` 6/6、`MockAppointmentAlternativesTests` 2/2、`DemoScenarioTests` 5/5 均未受影响。前端 `tsc --noEmit` 通过。
- 文档同步：`DECISIONS.md`（新增 DEC-022，DEC-021 决定五标注作废）、`06-mock-data-design.md`（号源一节整段重写）、`PITFALLS.md`（新增两条 + 补抖动复现记录）。
- 待确认：阶段 2（助手推荐医生 / 老人直呼姓名 / 家属指定医生）仍等本次数据层验收通过后再动。

## 2026-09-13 医生与号别数据模型（阶段 1：只做数据与展示，不动交互）（未提交）

- 落实 DEC-021。号源从「科室 × 日期 × 时间」扩成「**医生** × 日期 × 时间」，号别挂在号源上、跟着医生走。**本阶段不动任何交互**：不新增选医生的路由、动作或界面，只让医生/号别/挂号费在数据与页面上看得见。阶段 2（助手推荐医生 / 老人直呼姓名）另行确认。
- `schema.sql`：新增 `doctors`（id/hospital_id/department_id/name/title/good_at/introduction/**slot_type**/**clinic_weekdays**/enabled）；`appointment_slots` 加 `doctor_id / slot_type / department_id / fee_cents`；`appointments` 加 `doctor_name / doctor_title / slot_type / fee_cents`（医生**快照**，不在读取时回连号源）。
- `data.sql`：`MERGE INTO doctors KEY(id)` 种 16 位医生（6 个启用科室，每个 2–3 人，d006/d001/d002/d003 各 3 人、d004/d005 各 2 人），职称拉开档次。`slot_type` **手工写**：主任医师/副主任医师 = `EXPERT`、主治医师 = `NORMAL`（职称只用于展示，不参与推导）。`clinic_weekdays` 按序号错开：01 → `1,3`、02 → `2,4`、03 → `5`。
- **号源 id 拼法变了**（DEC-021 决定一，本次唯一的跨模块约定变更）：`r-<科室id>-<日期>-<时刻>` → **`r-<医生id>-<日期>-<时刻>`**。拼法仍只有 `RollingAppointmentSlotInitializer.slotId()` 一处；`DemoSeed.slot()` 改为**调用它**而不是各写一份，避免两处分叉。
- `RollingAppointmentSlotInitializer` 重写循环维度：科室 → **该科室医生** → 出诊日（跳过周末、跳过非 `clinic_weekdays`）→ 4 时段。因为出诊星期按序号错开，**同一科室同一天最多只有一位医生出诊**，同一时刻不会出现两条号源——助手的「共查到 N 个可预约时段」和家属代约的候选按钮都按「一个时刻一条号源」写，重复时刻会把界面上搞出一对一模一样的按钮。总量因此压在**约 500 条**（2026-09-13 实测 496 条；原来 601 条），没有翻到上千。周末仍一律不排，保留「当天暂无号源」的演示场景。
- 旧号源**整批重建**（DEC-021 决定三）：启动时删掉 `id LIKE 'r-%' AND doctor_id IS NULL` **且没有预约占用**的旧号源，重新生成带医生的新号源，**不回填默认医生**。有预约挂着的旧号源保留——预约列表是 `JOIN appointment_slots` 出来的，清掉它这条预约会在页面上直接消失、连取消都点不到（`DoctorSlotModelTests` 有一条专门钉这个例外）。旧预约的四个新列仍是 `NULL`，前端如实显示「医生信息未记录」。
- `ToolModels.Slot` 加 `doctorId / doctorName / doctorTitle / slotType / feeCents`；`AppointmentTool` 的三个查询改走带 `LEFT JOIN doctors` 的 `SLOT_SELECT`，`submit()`/`reschedule()` 写医生快照，新增私有 `doctorSnapshotOf(slotId)`（查不到医生时四个值全 null）。`AppointmentRecordStore.AppointmentView` 加同样 4 个字段，列表接口带出来。
- **前端展示口径收在一处纯函数** `doctorLine()`（新文件 `frontend/lib/appointment-display.ts`），输出「张建国 主任医师 · 专家号 · 挂号费 ¥40」。事项页列表卡、详情区与取消确认框，以及首页的预约卡都走它。两条硬规矩：`slotType` 遇**未知值原样显示**（后端以后加新号别也不会显示成空白）；没有任何医生信息时显示「医生信息未记录」，**绝不编一位医生**（与 `appointmentNarration` 既有的「不虚构」约定一致）。挂号费后端存**分**，前端转成 `¥40`；`feeCents` 为 null 时不显示「¥0」。
- 新增 `DoctorSlotModelTests` **11 例**，把上面每条约定钉成回归：生成的号源都带医生/号别/科室外键/挂号费；id 里嵌的医生就是这条号源实际挂的那位；同一科室同一天同一时刻不出现两条；专家号最便宜 > 普通号最贵；挂号费以分存（不会出现个位数元）；预约落库写快照且列表接口带得出来；无预约的旧号源被清、**有预约的旧号源留下且预约仍在列表里**；种子 `clinic_weekdays` 与生成器规则一致；每个启用科室至少 2 位医生；号源总量留在几百条。
- 测试：完整后端回归 **355 例**（= HEAD 341 + 本次 11 + 上一条取消入口 3），新增的 11 例全绿。**另有 1 例 `HealthReportTests` 间歇性失败，已用干净检出（`git worktree` HEAD `63851be`）证明与本次改动无关**：同一用例在干净检出上 6 次里失败 3 次、在本次改动上 6 次里失败 2 次，根因是用例自己的时钟边界抖动，见 PITFALLS 同日条目。前端 `tsc --noEmit` 通过。
- 文档同步：`DECISIONS.md`（DEC-021）、`INTERFACE_CHANGES.md`、`05-api-contracts.md`、`06-mock-data-design.md`、`13-demo-scenarios-workflow.md`、`PITFALLS.md`，以及 `proposals/医生与号别数据模型方案.md` 状态改为「阶段 1 已实施」。
- 待确认：阶段 2（新增 `QUERY_DOCTORS` 意图 / `SET_DOCTOR` 动作、接进助手与家属代约、补推荐规则）要等阶段 1 验收通过后再动。

## 2026-09-13 老人端取消入口：首页同步、垃圾桶直连、两条路并存（未提交）

- 首页的「下一次复诊」摘要改成「我的复诊预约」列表：列出**全部**已预约记录，按预约时间正序，最上面一条就是最近要去的。顺手修掉一个现成的 bug——原来取的是「最近创建的一条」（接口按 `created_at` 倒序），不一定是最近要去的。
- 事项页列表只留 `CONFIRMED`，同样按预约时间正序；标题从「我的复诊预约记录」改成「我的复诊预约」（已取消的不再列出来，「记录」二字不实）。取消过的记录后端仍然留着，只是这两个页面不再显示它。
- 新增 `POST /api/users/{userId}/appointments/{appointmentId}/cancel`：复用 `AppointmentTool.cancel` 那条链（释放号源 → 关联提醒置 `CANCELLED` → 预约置 `CANCELLED`），**不物理删除**。归属与状态由工具第一句带 `id + user_id + status='CONFIRMED'` 的查询把关；三种「查不到」的情况给**同一句 400 文案**、不回 404——分开说就得先暗示这条记录存不存在，那是探测别人预约的口子。
- 事项页保留**两条**取消入口，区别只在「谁来办」：每张已预约卡片右侧的垃圾桶走页内二次确认后直连上面那个端点；详情区「让助手帮我取消」仍旧把一句话交给助手页走确认卡。两条都**先确认才写库**，且落到同一个业务方法（`AppointmentTool.cancel`），不存在第二套取消逻辑。
- 两条入口必须在**文字上**分清：垃圾桶从纯图标改成图标 + 「取消」，详情区按钮从「取消这次复诊」改成「让助手帮我取消」+ 副标题「助手会先和您核对一次」。两条都只写「取消」，等于让老人在两个都不敢按的按钮之间选。配色刻意不做区分——同屏只突出一个主按钮（A-06），取消归次级。
- 取消成功后由事项页广播 `silver-agent-appointments-updated`，事项页与首页都挂这个事件；助手确认取消后（`assistant-view.tsx`）也广播同一个事件，所以两边互相同步。
- 新增 `ElderCancelEndpointTests` 3 例：一次取消把三件事一起做完且记录不删（列表接口照样读得到 `CANCELLED` 那条）、重复取消给 400 且不产生副作用、换个 userId 拿不到数据也不泄露这个 id 存不存在。断言不写死提醒条数（复诊一条、出发一条），只钉「取消前是活的、取消后一条不剩」。
- 测试：后端 **344/344 通过**（0 failure、0 error、0 skipped；新增 3 例）。前端 `tsc --noEmit` 通过，`oxlint` 无新增命中。
- ⚠️ 跑测试前先 `mvn clean`：`target/` 里残留的旧 class 会让**全部** `@SpringBootTest` 用例报 Bean 冲突（本次撞到 218 个），见 PITFALLS 同日条目。
- 文档同步：`05-api-contracts.md`（新端点）、`03-agent-workflow.md`、`02-pages-and-interactions.md`、`user-manual.md`、`设计思路报告.md`、`报名解决方案.md`、`09-demo-acceptance-checklist.md`，以及 `DECISIONS.md` 的 DEC-020。

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
