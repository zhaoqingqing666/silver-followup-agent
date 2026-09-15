# 踩坑记录

## 2026-09-14 给 `modelSuggestedReplies` 补按钮，顺带改掉了「任务挂起」的语义

- 现象：为了让「问医院 / 问科室」这两步也有快捷按钮，在 `modelSuggestedReplies` 里补了
  `ASK_HOSPITAL` / `ASK_DEPARTMENT` 两个分支，`SilverAgentApplicationTests.unrelatedConversation
  PausesTaskWithoutLosingItsStage` 立刻变红——老人说一句与办理无关的话，任务不再从
  `ACTIVE` 变成 `PAUSED`。
- 原因：这个方法的返回值**不只是「这一轮挂什么按钮」**。`chatInternalBody` 里还有第二处拿它当判据：
  办理正停在一个待答问题上时，`waitingReplies = modelSuggestedReplies(state)`，
  **非空就只把当前这一步再问一遍，空才把任务挂起**。于是「补两个按钮」等于顺手把「该不该挂起」
  也一起改了。两处用法对同一个空列表给了两种含义。
- 处理：撤回那两行，改为只动 `askHospital` / `askDepartment`——真正要修的是**按钮内容**
  （科室从 3 个变 6 个），不是**有没有按钮**。
- 结论：**一个方法被多处调用、且另一处用的是「空 / 非空」这种隐含契约时，改它之前先 grep 出
  全部调用点。** 返回列表的方法，空列表常常是一个语义信号，而不只是「没有数据」。

## 2026-09-14 从宿主机 `docker stop` 关开发容器，报 500 `check if the server supports the requested API version`

- 现象：宿主机执行 `docker stop <容器id>` 报
  `request returned 500 Internal Server Error for API route ... /v1.54/containers/<id>/stop`，
  附带一句 `check if the server supports the requested API version`。**这句话是误导**——它看着像「客户端比引擎新、要装/降版本」，实际是引擎当时处于「命名管道还在、引擎未就绪」的半死状态，Docker 把内部错误统一映射成了 500。
- 实查（可用于排除最常见的那个误判）：`where.exe docker` 只找到
  `C:\Program Files\Docker\Docker\resources\bin\docker.exe` 一份，CLI 29.5.3 / API 1.54 与安装包一致；`docker context ls` 是 `desktop-linux → npipe:////./pipe/dockerDesktopLinuxEngine`，地址正确。**版本没错位。**引擎整个退出之后，同一条命令的报错会变成 `open //./pipe/dockerDesktopLinuxEngine: The system cannot find the file specified`（管道不存在，是另一个阶段）。
- 原因：开发容器由 **VS Code 持有**。从外部 CLI 硬停它，很容易把引擎搞成半死状态。另外容器 id（如 `82402866bd7f`）在容器重建后就变了，本身也不该写进笔记——**容器名是固定的 `silver-followup-dev`**。
- 处理：① 启动 Docker Desktop，等托盘图标变绿；② 仍 500 则 `$env:DOCKER_API_VERSION="1.43"` 强行降版本重试；③ 完全退出 Desktop + `wsl --shutdown` + 重启（最常见有效）；④ **最后才** Troubleshoot → Purge。
- **顺序不能反**：先在 VS Code 里停容器 → 再退 Docker Desktop。反过来就是上面这个 500——引擎一退，`docker` 命令就没有后端了。
- 停容器的正确入口（VS Code，插件 0.469.0）：**远程资源管理器（Remote Explorer）→ Containers → 对正在运行的容器右键 →「停止容器」**。同一个右键菜单里还有「启动容器」「移除容器」「重新生成容器」。⚠️ **它不在命令面板里**：`remote-containers.stopContainer` 的 `commandPalette.when` 是 `false`（隐藏），只挂在 `view/item/context` 上，条件 `view == targetsContainers && viewItem =~ /^running(Dev|)Container/`。关掉连着远程的 VS Code 窗口并选 Stop Container 是等价入口。
- ⚠️ **第 ④ 步是唯一会丢数据的一步**：Purge 清掉 `silver-backend-data` 具名卷，而库里的预约都是走界面流程手工建出来的、`data.sql` 不种子 `appointments`，清掉就真没了。（号源例外：由 `RollingAppointmentSlotInitializer` 每次启动重排，不怕丢。）
- 结论：**这不是仓库代码问题，也管不了。**500 发生在 `docker` CLI ↔ Docker Desktop 引擎之间，比 `.devcontainer` 配置、比任何容器内代码都早；在仓库里加限流/锁/重试都碰不到这条路径。要沉淀就落在文档 + 人工约定，别写进 `.devcontainer/`（那是「三台电脑环境一致」的声明文件，不是运行时防护层）。
- 环境注记：本项目沙箱屏蔽了 `wsl.exe`（Security Center → Command Security → Program Blacklist），`wsl --shutdown` 只能由成员在自己的真实终端里执行。

## 2026-09-13 回归用例按「科室名字」分组，把两家医院的同名科室并成一组

- 现象：`DoctorSlotModelTests` 里「上午每个时刻都该是 1 位专家 + 1 位普通」失败，报 `内分泌科 2026-09-15 09:00:00（专家 2 / 普通 2）`；「下午同一时刻 ≤ 2 条」报 `心内科 2026-09-18 14:00:00 共 4 条`。数字看着都恰好翻了一倍。
- 原因：用例写的是 `GROUP BY s.department`——`department` 是科室**名字**。而 `departments` 里「心内科」有两份（`d001` 市第一医院、`d006` 市人民医院）、「内分泌科」也有两份（`d003` 市人民医院、`d005` 市第一医院）。按名字一分组，两家医院的两个科室被并成一个组，各自「1 专家 + 1 普通」加起来正好 2 + 2。
- 处理：`GROUP BY` 改成 `s.department_id`（唯一键）。
- 结论：**`appointment_slots` 上同时有 `department`（名字）和 `department_id`（外键）两列，按科室聚合或判重一律用 `department_id`。**科室名字跨医院不唯一，拿它当分组键会把两家静默合成一家，且数字以「恰好翻倍」这种看着正常的样子出现，不容易第一眼看出是分组错。

## 2026-09-13 H2 的 `DAY_OF_WEEK` 是 ISO 起点，`IN (1,7)` 数到的是周一

- 现象：`DoctorSlotModelTests` 里「周末一律不生成号源」失败，`expected: 0 but was: 90`。但生成器只在周一到周五的循环里插号源，周末根本进不去。
- 原因：用例用 SQL 的 `DAY_OF_WEEK(appointment_date) IN (1, 7)` 判周末，**前提「7 = 周六」是错的**。H2 的 `DAY_OF_WEEK` 按 ISO 编号：**1 = 周一 … 6 = 周六、7 = 周日**，所以 `IN (1,7)` 命中「周一 + 周日」。库里周一有号的科室正好 3 个（`d001` / `d003` / `d004`）、一个月里 5 个周一、每次 6 条 → **恰好 90**。（H2 另有 `ISO_DAY_OF_WEEK`；别的数据库这个函数起点还不一样，`1` 到底是周日还是周一必须逐个查。）
- 处理：周末判断挪进 Java：`rs.getDate(1).toLocalDate().getDayOfWeek().getValue() >= 6`，与生成器、`DemoSeed` 共用 `LocalDate.getDayOfWeek()` 这一套口径。
- 结论：**「星期几」的判断别写进 SQL。**用例与被测代码各用一套星期编号，失败时数字还「像模像样」（90 条，不像乱数），排查成本远高于把判断放进 Java。

## 2026-09-13 `HealthReportTests` 间歇性失败：与改动无关的时钟边界抖动

- 现象：`HealthReportTests.summaryCountsAveragesAndShowsRealReadings`（有时还有 `recordsOlderThanTheWindowAreLeftOut`）**间歇性**失败，症状永远是「汇总里少一条」——例如实际 `血糖 1 次，7.1 mmol/L。` 而期望 `血糖 2 次，平均 6.8…`；或者 `recordsOlderThanTheWindowAreLeftOut` 直接 `text()` 为 null（窗口里一条不剩）。同一份代码连跑 6 次，有时全绿、有时 1~2 个失败。**不是编译产物残留**（那是 PITFALLS 里另一条，症状是整批 Bean 冲突）。
- 排查方法（值得照抄）：`git worktree add --detach <tmp> HEAD` 拉一份**完全不含当前改动**的干净检出，两棵树各跑 6 次同一用例。结果：**干净检出 3/6 次失败**（其中两次是 2 个失败），带改动的树 2/6 次失败。→ 失败率与改动无关，是这条用例自己不稳。**别花时间在自己的 diff 里找原因**；先做这个对照。
- 根因（形状）：`HealthReportService.summarize()` 用 `to = MemoParser.nowInDemoZone()`、`from = to.minusDays(window.days())` 现算窗口，而 `HealthReportTests.seed()` 也用 `nowInDemoZone().minusDays(daysAgo)` 现造数据——**两边读的都是真实系统时钟**。用例把记录造在 `daysAgo = 0`（就是「此刻」），再去和几毫秒之后算出来的 `to` 比，正好压在 `recorded_at <= to` 这个边界上。触发点未见得更精确（曾怀疑 H2 `TIMESTAMP(6)` 微秒取整、也怀疑窗口下限，都对不上），但**边界依赖本身是确凿的**：断言依赖"造数据和算窗口是同一个瞬间"。
- 处理：本次不改（不属于预约/号源范围，动了会碰到健康报告的时间口径）。**判读回归结果时把它当已知抖动**：只要失败项是 `HealthReportTests` 且症状是「次数少一次」，原样重跑一次即可，不要顺着它去改预约或号源代码。
- 教训：**凡是把用例数据造在「现在」、又用「现在」现算窗口的断言，天生踩边界。**要稳，就把 `daysAgo` 从 0 挪到 1，或者给窗口留一天余量，让它不依赖"两次 `now()` 是同一瞬间"。
- 复现记录（2026-09-13，排班重做那次完整回归）：又失败一次，症状仍同形但**丢的条数不同**——这次是 `血压 2 次`＋`血糖 1 次`，上次是 `血压 3 次`＋`血糖 1 次`。两次都是「造在 `daysAgo=0` 的那条被关在窗口外」，丢哪几条随亚秒取整而变。**这条症状本身就是这个抖动的指纹**：看到「次数比预期少一两条」且失败项是 `HealthReportTests`，直接重跑，别去查号源/预约的代码。单跑 `-Dtest=HealthReportTests` 那次是 12/12 全绿，也说明它不是顺序污染。
- 复现记录（2026-09-14，科室扩充＋号源加名额那次完整回归）：全量 **393 例、失败 3 例，且三条同时挂**——`aValueWithoutANumberStillGetsCounted:108`、`recordsOlderThanTheWindowAreLeftOut:118`、`summaryCountsAveragesAndShowsRealReadings:98`（三个方法名都在上面出现过）。同一次运行里挂到 3 条是首次出现（此前最多 2 条），**但判据不变**：同一份代码、同一个 `target/`，前一次全量跑挂的是 `DoctorIntentTests` 的一条真失败 + `HealthReportTests` 一条，这一次数值又变了。次数从 1 → 2 → 3 随亚秒边界漂移，**恰恰是这条抖动的特征**，不是改动放大的。**看到 `HealthReportTests` 一次性挂多条不要惊慌**：先原样重跑一次全量；若 `HealthReportTests` 之外还有失败，那才是要查的真问题。
- 复现记录（2026-09-14，号源聚合那次完整回归）：全量跑挂的是 **`sendingWritesOneNotificationToThePrimaryContact:129`**（`assertThat(result.sent()).isTrue()` → false），**方法名不在上面列举的两个里**。单跑 `-Dtest=HealthReportTests` 连跑 4 次得到 **2 绿 2 红**，红的那两次又换成 `aValueWithoutANumberStillGetsCounted:108`（`report.text()` 为 null）。→ **挂哪个方法每次都换**，不要因为「方法名对不上」就当新问题去翻号源代码。`sent()` 为 false 只是同一根因的另一种落点：窗口里一条不剩 → **没什么可发**。判据是「同一份代码在两次运行间自己翻转」，不是方法名。
- 补充：`HealthReportTests` 只碰 `health_records` / `family_contacts` / `family_notifications` 三张表；号源/排班类改动碰的是 `appointment_slots` / `doctors` / `departments` / `clinic_locations`，**零重叠**。要证明某次失败与自己的改动无关，除了上面的双树对照，还可以用这条「表重叠为零」来快速排除。
- 复现记录（2026-09-15，冲突校验提前那次完整回归）：全量 **425 例、失败 2 例**——`summaryCountsAveragesAndShowsRealReadings` 与 `recordsOlderThanTheWindowAreLeftOut`（后者 `text()` 为 null，即窗口里一条不剩，与上面各次的落点同形）。同一次改动下**单跑 `-Dtest=HealthReportTests` 是 12/12 全绿**；再单独跑 `-Dtest=HealthRecordFlowTests,HealthReportTests` 又挂 1 例，且**换成了 `aValueWithoutANumberStillGetsCounted`**。三个方法在三次运行里轮流挂，仍是同一个边界指纹。⚠️ 这次试的「和某个类同跑才挂」**不足以推出顺序污染**：那两棵树都只跑了一次，而 `HealthRecordFlowTests` 自身要跑 13 秒、大量读写数据库，只是把 `HealthReportTests` 推到另一个亚秒时刻而已——判据仍是「同一份代码在两次运行间自己翻转」。本次改动碰的是会话状态（`ConversationState` / `ConversationStore` 的 `state_json`）与预约冲突流程，与上面三张表**零重叠**。

- 复现记录（2026-09-15，业务时钟收口那次回归）：11 个类共 **185 例、失败 2 例**，仍是
  `summaryCountsAveragesAndShowsRealReadings` 与 `recordsOlderThanTheWindowAreLeftOut`。**关键新数据：这一次
  `-Dtest=HealthReportTests` 单跑也红 2 例**（此前单跑多为 12/12 全绿），并且换成了
  `aValueWithoutANumberStillGetsCounted:108` 与 `summaryCountsAveragesAndShowsRealReadings:98`
  （这次 `血压 3 次` 对了，换成 `血糖 1 次`）。⇒ **单跑同样会红、方法名在两次运行之间互换**，
  这把「顺序污染」彻底排除（2026-09-14 那条只到「不足以推出」为止）。本次改动碰的是业务时区
  （`BusinessClock`）与号源查询的过滤位置，`HealthReportService` / `HealthRecordStore` **一字未动**
  （`MemoParser` 只是把时区常量改为引用 `BusinessClock.DEMO_ZONE`，语义不变），与健康三张表**零重叠**。
  同一次改动**收尾时全量 427 例全绿**（含 `HealthReportTests` 12/12）——**红 → 红 → 全绿，三次运行三次翻转**，
  正好演示这条判据：先看「失败项是不是只有 `HealthReportTests`、症状是不是次数少一两条」，再重跑一次收工。

## 2026-09-13 `target/` 里的残留 class 让 218 个用例一起报 Bean 冲突

- 现象：`mvn test` 里**所有** `@SpringBootTest` 用例（218 个）整批 `Errors`，却一个 `Failures` 都没有；报的是 `ConflictingBeanDefinitionException: Annotation-specified bean name 'memoryStore' for bean class [com.team.silveragent.application.MemoryStore] conflicts with existing, non-compatible bean definition of same name and class [com.team.silveragent.application.longterm.MemoryStore]`。而源码里只有 `application/longterm/MemoryStore.java` 这一个文件，怎么看都不该重名。
- 原因：DEC-018 的分包重构把 `MemoryStore` 搬进了 `application/longterm/`，而**旧位置编译出来的 `MemoryStore.class` 留在了 `target/classes/`**。Spring 扫的是编译产物所在的那个包，于是同一个 bean 名出现了两份定义。增量编译不负责删除「源码已不存在」的 class，所以 `mvn compile` 一路 `Nothing to compile - all classes are up to date`，看着完全正常。
- 处理：`mvn clean test`。清理后同一套用例 **344/344 通过**，那 218 个 error 一个不剩。
- 结论：**分包、改名、删类这类重构之后，必须先 `clean` 再跑测试。**只看编译 `BUILD SUCCESS` 是不够的——症状会伪装成源码里的 Bean 重名，去源码里找只会找瞎。

  ⚠️ 容器里同理，而且更容易撞上：`backend/target` 是具名卷 `silver-backend-target`，跨容器重建一直留着。谁在容器里做过一次搬家式重构，下一个人 `mvn test` 就会中招。遇到「源码里明明只有一个类，却说 Bean 冲突」，先 `mvn clean`。

## 2026-09-12 `CANCEL_APPOINTMENT` 不能等同于确认当前取消卡

- 现象：当前卡准备取消6条预约时，用户说“还是只取消12号之前的”，模型已识别为修改取消范围，Java却直接消费旧卡并取消6条。
- 原因：等待确认阶段把任何 `CANCEL_APPOINTMENT` 意图都映射成 `CONFIRM_PENDING`，丢掉了本轮新的日期和范围。
- 处理：模型通过 `CALL_CONFIRMATION_TOOL` 明确调用申请/修改范围或确认/拒绝工具；修改范围作废旧凭据并生成新卡，只有 `respondConfirmation(CONFIRM)` 可以确认当前卡。
- 结论：业务意图和确认决定是两个维度。“想取消哪些对象”绝不能隐式等于“确认执行上一张卡”。

## 2026-09-12 `npm run build` 报 EACCES：`dist/` 被 root 建过一次

- 现象：`npm run build` 失败，`Error: EACCES: permission denied, rmdir '/workspace/frontend/dist/client'`。`dist/` 是 `vscode:vscode` 的时候正常，不知何时变成 `root:root`。
- 原因：构建脚本会先 `rm -rf dist/` 再重建。只要有人在容器里以 root 身份跑过一次构建（例如 `docker exec` 没带 `-u`），`dist/` 里就会留下 root 属主的目录，之后以 `vscode` 身份构建就删不掉它们。`dist/` 已在 `frontend/.gitignore` 里，删了不会丢任何源码。
- 处理：`docker exec silver-followup-dev chown -R vscode:vscode /workspace/frontend/dist`（只改属主，不删内容），之后构建即通过。后续构建统一走 `docker exec -u vscode silver-followup-dev sh -lc 'cd /workspace/frontend && npm run build'`。
- 结论：容器里跑构建**一律带 `-u vscode`**。以 root 建出来的产物目录会让下一个人（或下一次 CI）构建失败，而报错信息只提一个目录，看不出是属主问题。

## 2026-09-12 模型补全的日期把“都取消”缩成了一天

- 现象：老人说“都取消”，本意是取消候选里的全部预约，结果一张批量卡都没生成——筛选后一条都没剩。
- 原因：`cancellationCandidates` 一直无条件用 `facts.date()` 当范围边界。`facts` 是模型抽取的，它的 `date` 可能来自上一轮闲聊、也可能只是它自己的补全。用户这句话里根本没有日期，模型却给了一个日子，整批取消就被悄悄缩成了那一天。
- 处理：新增 `mentionsCancellationDate(message)`——先看用户这一句**自己**提没提日期（完整日期、`9.12号`、`今天/明天/后天`、`下周/本周/周X/星期X`，与 `RuleFactExtractor.parseDate` 同口径），没提就一律不用模型的日期。补 `CancelScopeTests` 2 例：`@MockitoBean` 假规划器给出一个库里绝不会有的日期，断言“都取消”仍选中全部候选；另一例断言用户真说了日期时模型日期照用，守住修复没矫枉过正。
- 结论：**模型抽出来的槽位不是证据，用户原话才是**。拿模型的字段去收窄一个批量写操作的范围，等于把一个“全部”悄悄变成“一条”。范围边界必须回原文核对。

## 2026-09-12 回绝模型时不能用 DIRECT_ANSWER

- 现象：模型编了一个执行不了的工具（未注册、角色无权限，或写工具）时，原先的代码只是把它从 `approved` 里滤掉。若列表滤空，就落进最后的 `DIRECT_ANSWER`——于是**没有任何报错、没有卡片、也没有拒绝**，只有一段普通回答。用户以为事情办了。
- 原因：`DIRECT_ANSWER` 在 `FollowupAgentService` 里不是中性的“回答一下”。`DialogueService.modelReply` 会调 `pauseActiveTask(state)`，把正在办理的预约流程暂停掉，草稿、阶段和按钮都停在原地。
- 处理：新增 `AgentOrchestrator.Route.REFUSE_UNSUPPORTED_TOOL`，由 Java 用固定话术明确回绝，不复用模型话术、不动任务状态。同时先做一次受控回退：模型的 `intent` 能归到既有 Java 工作流（`CANCEL_APPOINTMENT`→`CANCEL_EXISTING_APPOINTMENT` 等）就按 intent 走那条流程，写操作仍会被翻译成确认卡。工具在任何情况下都不进 `proposedTools`。补 `AgentRuntimeRoutingTests` 3 例（编造写工具+合法写意图、编造工具+UNKNOWN、角色无权限）。
- 结论：**“拒绝”和“回答”是两条路**。回绝一个不受支持的动作不能借道会改任务状态的回答分支；该回绝就回绝，任务状态原地不动。

## 2026-09-12 等待确认时，普通取消路由抢走了用户的语音确认

- 现象：取消卡已经出现后，用户说“取消”“是的”仍会重新查询预约或号源；“取消 9.12 号前的预约”也会被拆成选日期/选单条，始终无法执行。
- 原因：确定性取消路由在 `AWAITING_CONFIRMATION` 之前运行，而且状态只保存一个 `pendingAppointmentId`。Java 关键词判断既覆盖了模型的上下文理解，又无法表达一组已圈定预约。
- 处理：等待确认上下文优先路由；新增只建卡不写库的 `interaction.requestConfirmation`；状态保存整组预约 ID；最终仍由 Java 校验并原子取消。文本、语音和按钮统一消费同一 `confirmationId`。
- 结论：不要靠不断扩充“是的/好的/取消吧”词表解决开放表达。模型负责把自然语言归一成确认、拒绝或修改对象，Java 负责验证当前卡片和执行业务不变量。

## 2026-09-12 “之前那个”不能只凭“之前”判成批量范围

- 现象：候选列表后说“之前那个”，系统可能把全部预约装进一张批量取消卡。
- 原因：批量判断把“之前/以后”单独当范围词，但没有要求同一句里存在日期；范围过滤没有日期时又不会缩小候选。
- 处理：范围批量必须同时出现可解析日期；“都取消/这些预约”等明确整体说法仍可直接批量。只有指代而无法唯一定位时重新展示候选。
- 结论：范围词必须由边界值约束。“之前”既可能表示时间范围，也可能是对上一项的指代，不能脱离日期单独决定写操作对象。

## 2026-09-12 黑名单挡不住“已经帮您取消好了”

- 现象：确认卡的模型话术闸门列了“已经取消 / 取消成功 / 已为您取消 / 号源已释放”，看起来够用。但模型写“已经帮您取消好了。”时四条全部漏过，这句会跟着口播念给老人听。
- 原因：中文完成态是组合式的（`已经|已` + `帮您|为您` + `取消` + `好了|成功|完成`），逐条列举一定列不全。更要紧的是当时还有一条“开场话术正文”的路径，它只查黑名单、不要求问句，陈述句可以直接进入回复。
- 处理：闸门改成一条**结构性约束**——模型这句话**不得提及任何业务事实**：出现“取消/预约/号源/提醒”等业务词、数字，或“已/成功/完成/好了/释放”等完成态说法，就整句丢弃。它一个业务词都说不了，自然也就编不出“已经帮您取消好了”。**卡片标题同时收归 Java 固定生成**（单条「是否取消这次复诊预约」，批量带真实条数），模型的话只出现在卡片前面那一句开场白里。补 `ConfirmationInteractionToolTests` 6 例钉住（含数字、含完成结论、干净开场白、批量条数、提业务的开场白被丢、字段来源）。
- 结论：安全闸门要有一条**结构性约束**兜底，黑名单只当补强。只靠黑名单，漏一个组合说法就是一句假结论念给老人听。更根本的一条：**凡是老人据以决策的字段（标题、条数、日期、影响、按钮），都不要交给看不见数据库的模型写**——模型写过“已经帮您取消预约了吗？”这种标题。

## 2026-09-11 「无新增告警」的比对漏掉了全新文件

- 现象：核对「前端代码规范检查是否引入新命中」时，只把**改动过的、且在 HEAD 里存在**的文件取出来逐一比对，结论是「与改动前一致」。但本轮新增的 `tool-trace-describe.ts`（未跟踪文件）实际带着 40 条 `no-base-to-string` / `restrict-template-expressions`，`camera-capture.tsx` 还带着 1 条 `EffectSetState`——两者都是本次新写的代码，等于一次也没被比对过。
- 原因：`git show HEAD:<path>` 对未跟踪文件直接报错，脚本把这种情况当成「基线为空」，而不是「基线不存在」。于是新文件在比对表里整体缺席——第一轮比对里那两条 `unicorn(no-empty-file)` 就是空基线留下的线索，顺着它才发现有两个文件根本没进比对。
- 处理：`str(value: unknown)` 统一把工具返回值收敛成能显示的文字（对象/数组给空串，而不是 `[object Object]` 写进「办理过程」）；`camera-capture` 的开流逻辑改成链式回调，`setState` 一律落在异步回调里。
- 结论：比对「有没有变差」时，**新增文件必须单独列出来**——它们没有基线，任何命中都是新的。脚本里遇到「取不到基线」要显式报错，不要静默跳过。

## 2026-09-11 已结束会话的「拒绝」被当成一轮对话落了库

- 现象：会话关掉之后，一个还开着确认卡的老页面每重试一次，历史里就多一条一模一样的「这段对话已经结束了」。连试三次后，这段历史里三句复读，真正聊过的内容反倒被淹掉。
- 原因：`/messages`、`/actions`、`/confirmations` 被拒时走的是 `respondWithoutModel()` → `finishWithoutModel()`，这条公共出口会顺手把这句话 `addMessage` 进会话。对正常回答这是对的，对一句拒绝就是错的——拒绝只是一次失败的调用，不是一轮对话。
- 处理：`closedResponse()` 直接构造 `AgentTurnResponse` 返回，不落库。拒绝照常返回，界面上照常看得见，只是不再进历史。
- 结论：写历史这件事要按语义分，不能挂在「所有出口」上。凡是「这句话不代表一次交流」的地方（拒绝、限流、重放），都要绕开落库那一层。

## 2026-09-11 `pkill -f` 的匹配串把执行它的那个 shell 也一起杀了

- 现象：`pkill -f "server.port=8099"` 之后命令以退出码 144 结束，自己的 shell 也被杀掉；`pkill -f "spring-boot:run"` 同理——`-f` 匹配的是整条命令行，而执行它的 `bash -c` 命令行里正好就带着这个字符串。
- 原因：`pkill -f` 拿模式去匹配 `/proc/*/cmdline`，执行命令的 shell 自己也在那张表里。
- 处理：先用 `pgrep -af` 看清目标再按 PID 杀；或用 `[s]erver.port=8099` 这样的字符类写法让模式本身不自匹配。
- 结论：这台机器上还跑着用户自己的 8080 后端（本会话中一直没动它）。杀进程前必须先 `pgrep -af` 确认，绝不拿一个可能自匹配的 `-f` 模式直接扫。

## 2026-09-11 在 effect 里同步 setState，被 lint 拦下也顺便修掉了一个竞态

- 现象：`profile-view.tsx` 里 `useEffect(() => { void load(); }, [])`，`load()` 第一句是 `setLoading(true)`，oxlint 报 `react(react-compiler) EffectSetState`。
- 原因：effect 体内同步调用 setState 会触发级联渲染。
- 处理：改成只在 promise 回调里 setState（`listMemories().then(ok, fail)`），用一个 `cancelled` 标志在清理函数里丢弃迟到结果；「正在读取」这个状态改用 `memories === null` 表达，少一个可能对不上的布尔。重试按钮走 `reloadToken` 递增重新触发 effect，setState 落在事件处理里不受这条规则约束。
- 结论：这条 lint 规则不只是形式要求——它逼出来的写法顺手修掉了「重试连点两下、先发的那次后到把新结果盖回旧的」这个真实竞态。`cancelled` 守卫在这里不是摆设。

## 2026-09-11 `probe=true` 把一条正常的模型通道自检成「密钥无效」

- 现象：`GET /api/demo/channels?probe=true` 返回 `probe.ok=false`、`reason="调用抛异常：密钥无效、余额不足或网络不通"`；但用同一把 key 直接 curl DeepSeek 是 200，回复也正常。
- 原因：自检写死了 `max_tokens=16`。`deepseek-v4-flash` 是推理模型，会先输出 `reasoning_content`，16 个 token 被思考链吃光后 `content` 为空、`finish_reason=length`；网关重试三次仍拿不到正文，抛出的空内容异常里其实带着 `finish_reason` 和 `usage`，却被 probe 的 catch 折成了一句与真实原因无关的「密钥无效」。
- 处理：自检预算提到 512（`PROBE_MAX_TOKENS`），空内容与调用异常分开报，异常细节写服务端日志、写前抹掉 `sk-` 片段。
- 结论：推理模型的 token 预算不能按普通模型给；诊断信息已经在异常消息里时，别在 catch 里丢掉它，否则排查方向会被带偏。

## 2026-09-11 固定的系统提示被回答模型改写，测试也照不出来

- 现象：关掉视觉模型后发图片，回复本应是「图片识别功能暂时没有开启……」，实际却是「图片识别功能**目前**没有开启……」外加一段编出来的替代建议，还多花了 4 秒。
- 原因：该分支走的是 `respond()` → `finish()`，而 `finish()` 会调回答模型润色每一句话。测试跑在 `agent.model.enabled=false` 下，模板路径逐字返回，所以断言 `contains("图片识别功能暂时没有开启")` 一直是通过的——它测不到「模型开着」的那个环境。
- 处理：改成 `respondWithoutModel()`，固定说明逐字出去、不再为此调用模型（实测 4.15s → 0.13s）。
- 结论：教老人「换个办法」的系统提示必须原样送达；判断某个分支要不要润色，要按「模型开着」的环境去看，不能只看规则模式的测试。

## 2026-09-09 `.env` 已填写但开发后端仍走规则模式

- 现象：`.devcontainer/.env` 中已设置 `AGENT_MODEL_ENABLED=true`，重启旧的 `mvn spring-boot:run` 命令后，模型状态仍是 `RULE_FALLBACK` / `TEMPLATE_FALLBACK`。
- 原因：环境变量只会由启动它的 shell 传给 Java 进程；普通 Maven 命令不会自动读取 `.env`。根目录 `.env` 则是 docker compose 的配置文件，也不会被开发容器中的 Maven 自动读取。
- 处理：统一使用 VS Code 的“Dev Container: 后端服务”任务；该任务启动前可选加载 `.devcontainer/.env`。修改配置后停止并重新运行后端任务，不需要重建容器。
- 结论：前端与后端在开发容器中是两个独立进程，停止或重启后端不会自动停止前端。

## 2026-09-02 脚手架默认部署依赖产生安全告警

- 现象：初始站点脚手架带入 Cloudflare/Wrangler 依赖，`npm audit` 报告多项上游漏洞。
- 原因：本项目只需本地 Demo，却保留了不需要的云部署插件。
- 处理：移除 Cloudflare 与站点部署插件，升级 React、vinext、Vite 及间接依赖；重新执行 `npm run build` 和 `npm audit`。
- 结果：前端构建通过，`npm audit` 为 0 个漏洞。
- 避免复发：新增依赖后同时提交 `package-lock.json`，合并前执行构建和审计；不要使用 `npm audit fix --force` 强行跨版本升级。

只记录能够帮助下一个人复现和解决的问题，不写“今天很累”“代码有问题”之类无法行动的内容。

## 2026-09-07 用户资料与家属联系人展示

- 现象：设置页曾写死“张阿姨 / 女儿 / 138****1234”，而 H2 中的演示用户实为“王阿姨”，家属为“小丽（女儿）”；联系人电话也并非真实可读号。
- 根因：早期实现把用户资料散落在页面，违背“业务数据以 H2 为唯一来源”（DECISIONS 2026-09-06）。
- 处理：`GET /api/users/{userId}` 返回体新增 `contacts`（脱敏电话），设置页改为读取后端；不在页面写死用户或家属。
- 复现提醒：任何页面出现用户名、家属、医院、号源、地址、材料等展示文字，都应来自 H2，不要写死在组件里。

## 2026-09-07 Docker 虚拟磁盘损坏污染依赖卷

- 现象：Dev Containers 无法启动开发容器，报 `hosts: input/output error`；`docker system df` 连镜像列表都读不出，同样 I/O error。容器内前端依赖卷 `frontend/node_modules` 出现 7452 个 0 字节文件（含 96 个 package.json），`npm run build` 报 `Invalid package config @jridgewell/sourcemap-codec`。
- 根因：Docker Desktop（WSL2）底层虚拟磁盘在异常关机/升级后损坏，containerd 元数据与依赖卷文件被截断成空文件。docker-desktop 发行版内 `/var/lib/docker`、`/var/lib/desktop-containerd` 均不存在，属数据盘未正确挂载。
- 解决：① `docker system df` 先排除空间不足；② 重启 Docker Desktop / `wsl --shutdown` 后数据盘恢复；③ 依赖卷内损坏文件无法逐个修，清空 `node_modules` 内容（挂载点不能 rm 整目录）后 `npm ci` 重建，0 漏洞。
- 无效尝试：`docker rm` 损坏容器在磁盘损坏期间会二次 I/O 失败，需先重启 Docker 再删。
- 是否需要修改文档/测试：`PROGRESS.md` 需在容器验收后把“待验收”改为通过。

## 2026-09-07 默认主联系人加载为死代码

- 现象：`FollowupAgentService.loadPrimaryContact`（会把一个主联系人放入 state）已定义但**从未被调用**，属死代码。
- 处理：2026-09-07 已删除整条死代码链（`loadPrimaryContact`、接口 `FamilyNotificationTool.findPrimaryContact`、mock 实现及 `mask` 私有方法），容器内 `mvn test` 16/16 通过。提交见 `refactor/remove-dead-primary-contact`。
- 结论：删除前后都不存在“默认联系人被当作已确认对象”的路径——`SET_NOTIFY=false` 清空 contact，`SET_CONTACT` 要求从候选人显式选择，`ready()` 也要求 `notifyFamily && contact != null`。
- 留意点：容器内开启模型联调（`agent.model.enabled=true`）时，若模型自由语言路径能影响 notify/contact 字段，仍需验证不会把主联系人误读为用户指定。

## 记录模板

### PIT-XXX 简短标题

- 日期：
- 环境：操作系统、Java、Node、浏览器等。
- 现象：完整错误信息或用户可见表现。
- 触发步骤：怎样稳定复现。
- 根因：真正原因；未知时写“待确认”。
- 解决办法：实际有效的步骤。
- 无效尝试：避免别人重复浪费时间。
- 是否需要修改文档/测试：
- 相关 PR/提交：

## 示例：前端跨域失败

- 现象：Vue 请求后端时浏览器提示 CORS 错误。
- 触发步骤：前端开发服务器直接访问后端地址。
- 根因：后端未允许本地前端来源，或前端代理未配置。
- 解决办法：统一采用前端开发代理，避免每个 Controller 单独配置跨域。
- 是否需要修改文档/测试：在 README 中写明前端代理与后端端口。

## 2026-09-03 Agent上下文与真实事项重构

- 模型提示词缺少当前日期会把9月20日解析为错误年份。
- 只用按钮文字匹配号源会导致重复询问，按钮必须携带slotId。
- 前端mockAppointment会造成未预约也显示事项。

## 2026-09-03

- 不要在 data.sql 中随应用启动删除预约和会话，否则重启即丢数据。
- 不要只把聊天放在 React useState；页面卸载后必须通过 conversationId 从后端恢复。
- 不要让大模型直接生成号源时间；模型解析偏好，工具查询和业务代码负责最终校验。
- 预约列表不能固定读取第一条作为唯一事项，重复预约必须提供记录选择。

## 2026-09-11 演示数据写死日期，过期后场景演不出来

- 现象：演示号源写死在 2026-09-17～09-21、日程写在 09-18 与 09-20。这些日子一过，「时间冲突」再也造不出来（冲突只在预约当天比对日程），而一批回归用例要等到那天之后才开始集体变红——发现时已经离演示很近了。
- 根因：种子数据用了**绝对日期**，但场景依赖的是**相对当天的时间关系**（「下周三有体检」「周末没有号」）。
- 解决办法：把种子数据改成按当天滚动生成（号源覆盖今天起一个月的工作日、日程排在「下周三 / 下周六」），并新增 `DemoSeedDataTests` 把场景前提钉成断言：哪天前提不成立，测试先红，而不是等到录屏当天。
- 无效尝试：只把用例里的日期批量替换成新的一天——下一次过期的只是换了个日子，问题原样留着。
- 结论：凡是「场景必须成立」的种子数据，一律用相对今天的算法生成，并配一条断言把它钉住；`data.sql` 只留目录类数据。

## 2026-09-11 音量动画做了两条，尺寸靠缩放硬凑

- 现象：按住说话时，圆形按钮里一条音量条、按钮上方的浮层里又一条。同一句话两处起伏不一样高（按钮那条 8→36px，浮层那条 14→72px），按钮里那条还压到白色描边上。
- 根因一（重复）：`LevelMeter` 把「几根、多粗、多高」做成了入参，两个调用点各传一套。代码注释里写着「两处必须完全一致」，而参数恰恰不一致——一致性靠注释叮嘱，就迟早会漂。
- 根因二（尺寸）：`size-16` 减去 `border-4` 描边只剩 56px 内圆，而当时那套 5 根条要 64px。补法是加 `scale-90`。`transform` 只缩视觉不改布局盒，缩完仍是 57.6px；真正让它「看着还行」的是按住时按钮自己的 `active:scale-95` 也在同时生效（0.9 × 0.95 = 0.855），换个不触发 `:active` 的路径就露馅。
- 解决办法：屏幕上的音量动画只留浮层里那一条（那条本身是对的：9 根、120px 宽、72px 上限放进 80px 容器），按钮只变色；`LevelMeter` 的尺寸收成组件内常量，只留颜色可变。
- 结论：**同一件事不要做两遍**；一处能定义的东西，不要做成两个调用点各传一份参数——注释管不住漂移，「只有一处」才管得住。放不下就改尺寸，别拿 `scale` 去缩一个本来就超框的元素：`transform` 骗得过眼睛，骗不过布局，而且它会和 `:active` 之类的状态缩放叠在一起，算不清最终多大。

## 2026-09-11 祖先的 transform 把 fixed 浮层关进 64px 的盒子

- 现象：按住麦克风时，本该在按钮上方居中的浮层变成一条又高又窄的竖条，「松开手指，取消发送」一个汉字一行往下排，从屏幕中段垂到底部，把整片号源列表盖住。类型检查、代码规范检查、生产构建全绿。
- 根因：浮层是 `position: fixed` + `w-[calc(100%-40px)]` + `bottom-[132px]`，看着是「视口宽度减 40px、离屏幕底 132px」。但它的祖先 `div` 为了给麦克风居中带了 `-translate-x-1/2`——**祖先只要有一个 transform，后代的 `fixed` 就不再以视口为包含块，而是以那个祖先的盒子为包含块**。那个盒子只包着一个 `size-16` 的圆按钮，64×64：`calc(100% - 40px)` = 24px（比 `px-6` 的左右内边距还窄），`bottom: 132px` 变成「这个小盒子上方 132px」，浮层于是被顶到屏幕上半部。它看起来仍然水平居中在按钮上方，正是因为包含块本来就是按钮自己——这个巧合掩盖了「位置算错了」这件事。
- 解决办法：`page.tsx` 的居中改用 `-ml-8`（按钮 `size-16` 的一半，不产生 transform），浮层宽度从百分比改成按视口的 `w-[calc(100vw-40px)]`，两处都写了注释说明为什么不能用 `-translate-x-1/2`。顺手全仓核对了一遍其余 `fixed` 元素，暂无第二个同款组合。
- 无效尝试：只调 `max-w`、只加 `whitespace-nowrap`——都在改「多宽」这个结果，而真正错的是「以谁为基准算宽度」。
- 结论：`fixed` 不等于「相对视口」，它相对的是**最近的、带 transform / filter / will-change / contain 的祖先**（构建工具、动画库、居中小技巧都可能顺手加上）。要断言「相对视口」，就别让祖先带这些属性；宽度也别只写百分比——同一个表达式在两种包含块下算出来的数字能差十倍，而 `tsc`、`oxlint`、构建检查一样都不会报警，只能起来看。

## 2026-09-12 批量改 import 的脚本把注释里的类名当成真引用

- 现象：切包后编译报 `SafetyGuard is not public in com.team.silveragent.application; cannot be accessed from outside package`。报错的两个文件（`agent/MedicalBoundaryRules.java`、`agent/RuleFactExtractor.java`）正文里一次都没用过 `SafetyGuard`，只在**注释**里提到它。
- 根因：迁移脚本判断「这个文件要用哪些类」用的是 `grep -E "\b类名\b"`，注释里的提及和真调用一视同仁，于是给两个文件补了 import。`SafetyGuard` 是包级私有，从别的包 import 它直接编译不过——这次是编译器替我们发现了。
- 更隐蔽的是同一次脚本的另一半：它还给 `health/` 下三个文件补了 `import ...memo.MemoParser` / `MemoStore`，同样只服务注释里的 `{@link}`。这三个**编译得过**，于是悄无声息地让子包依赖图多出三条根本不存在的边（`health → memo`）。下一个做结构分析的人（包括写这段脚本的人）会照着这张假图去理解代码。
- 解决办法：注释里的跨包引用改用全限定名 `{@link com.team.silveragent.application.memo.MemoParser}`，不再需要 import；三条假边删掉。真调用的那一条（`HealthReportService` 里的 `MemoParser.nowInDemoZone()`）保留。
- 结论：**「文本上出现了」和「真的引用了」是两回事**，批量改写 import 的脚本必须把注释排除在外（先剥注释，或只匹配 `类名.` / `new 类名` 这类用法形态）。另外这一轮的运气不错——批量改写如果**编译不过**，那反而是好事；真正要怕的是它编译得过。

## 2026-09-12 移了源码却没清 `target/`，Spring 报 bean 重名

- 现象：把 `MemoryStore` 从 `application/memory/` 移到 `application/longterm/` 后跑测试，**317 项里 207 个错误**，报 `ConflictingBeanDefinitionException: bean name 'memoryStore' for [com.team.silveragent.application.memory.MemoryStore] conflicts with existing, non-compatible bean definition of same name and class [com.team.silveragent.application.longterm.MemoryStore]`。看着像代码改错了。
- 根因：`git mv` 只动源码，`target/classes/` 里旧的 `application/memory/MemoryStore.class` 还在。Spring 扫的是**编译输出目录**，两个 simpleName 相同的类都在 → 默认 bean 名（`memoryStore`）撞车。整个过程**编译完全通过**，所以上一步的「编译过了」并不能说明运行时状态是一致的。
- 附带的坑：`mvn clean` 删不掉 `target/`，报 `Device or resource busy`——占用它的不是 maven，是 **VS Code 的 Java 语言服务器**（`redhat.java`）在盯着这个目录。不用去杀 IDE：`clean` 失败之前已经把内容删空了，接着跑 `mvn test` 就是全新构建。
- 解决办法：清空 `target/` 后重跑，317 项全绿。
- 结论：**改包名或类名之后，唯一算数的验证是「清空 `target/` 再构建」**；增量编译的绿只证明源码能编译，不证明运行时加载到的类是对的。还有一个现成的判据：Spring 应用里同一个 simpleName 的类若同时存在于两个包，bean 名必然冲突、启动必然炸——**没炸就说明没有重复**，可以用它反过来确认历史那几次验证是干净的。
