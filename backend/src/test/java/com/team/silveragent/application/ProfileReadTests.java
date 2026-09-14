package com.team.silveragent.application;

import com.team.silveragent.agent.AgentContext;
import com.team.silveragent.agent.AgentRole;
import com.team.silveragent.agent.model.ModelGateway;
import com.team.silveragent.agent.model.ModelRequest;
import com.team.silveragent.agent.planning.AgentSystemPrompt;
import com.team.silveragent.application.longterm.MemoryStore;
import com.team.silveragent.application.profile.ProfileQueryService;
import com.team.silveragent.application.time.BusinessClock;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.support.DemoSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 画像与预约历史的受控只读读取。
 *
 * <p>这一轮只做「查」，不做完整推荐，也不做偏好写入。用例围绕三件事：
 * <ol>
 *   <li><b>身份由 Java 注入、范围由 care_relations 决定</b>：本人只看本人，
 *       照护者只看绑定过的长辈，越权不给任何摘要、也不回答「库里有没有这个人」。</li>
 *   <li><b>事实、统计、明确偏好、当轮要求分得开</b>：一次预约不能说成习惯，
 *       预约成功不能说成到过医院，取消过的不能说成还有效。</li>
 *   <li><b>只读就是只读</b>：模型调它不写草稿、不选时段、不动待确认的卡；
 *       想沿用历史，得下一轮由老人明说，走正常办理动作。</li>
 * </ol>
 *
 * <p>断言看的是工具留痕里的真实 SQL 结果与回到模型的那段证据，而不是模型润色过的话术——
 * 事实由 Java 给，措辞由模型定，混在一起就分不清是哪一步出了问题。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-test;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=true"})
class ProfileReadTests {

    /** 目录里真实的医院名带「（模拟）」，预约记录里存的就是全名。 */
    private static final String FIRST_HOSPITAL = "市第一医院（模拟）";
    private static final String PEOPLE_HOSPITAL = "市人民医院（模拟）";
    /** 只属于另一个老人（user-002）的医院：一旦出现在本人的结果里，就是越权读到了别人的数据。 */
    private static final String OTHERS_HOSPITAL = "市第三医院（模拟）";
    /** 按钮流里写医院用的是目录名（不带「（模拟）」也认），断言一律用子串比较。 */
    private static final String FIRST_HOSPITAL_INPUT = "市第一医院";
    private static final String PEOPLE_HOSPITAL_INPUT = "市人民医院";
    private static final String CARDIOLOGY = "心内科";
    private static final String ENDOCRINOLOGY = "内分泌科";

    private static final LocalDate UPCOMING = DemoSeed.checkupDay();
    private static final LocalDate PAST = DemoSeed.checkupDay().minusDays(30);

    @Autowired FollowupAgentService service;
    @Autowired ProfileQueryService profileQuery;
    @Autowired MemoryStore memories;
    @Autowired AgentSystemPrompt systemPrompt;
    @Autowired BusinessClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired ScriptedModelGateway gateway;

    /**
     * 按脚本回答的规划模型。第一轮提哪个工具由用例决定；工具结果回到模型时固定回一句普通回答，
     * 让最终话术不干扰对事实的断言（要看 Java 摆了什么，就看回到模型的那段证据）。
     */
    static class ScriptedModelGateway implements ModelGateway {
        private static final String ANSWER = """
                {"actionType":"ANSWER","intent":"UNKNOWN","toolName":null,"arguments":{},
                 "replyDraft":"我按您说的查过了，上面就是这次的结果。",
                 "dialogueMode":"FOLLOWUP_FLOW","facts":{}}
                """;

        volatile String planning = "";
        volatile String continuation = ANSWER;
        /** 工具结果回到模型时它实际看到的那段证据（含 Java 摆出的事实与草稿）。 */
        volatile String lastToolPhasePrompt = "";
        final AtomicInteger calls = new AtomicInteger();

        /**
         * 模型收到过的每一条消息（含 system），按发生顺序。
         *
         * <p>「默认上下文里到底有没有塞进画像」这件事只能在**完整提示词**上验：只看工具那一轮
         * 的证据，等于默认自己已经知道别的段落没写——而这一整轮修的恰恰是「另一段偷偷写了」。
         */
        final List<ModelRequest.Message> messages = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        @Override public String complete(ModelRequest request) {
            calls.incrementAndGet();
            messages.addAll(request.messages());
            String latest = request.messages().isEmpty() ? ""
                    : request.messages().get(request.messages().size() - 1).content();
            if (latest.contains("同一用户轮次内刚刚执行完成的真实只读工具结果")) {
                lastToolPhasePrompt = latest;
                return continuation;
            }
            return planning;
        }

        @Override public boolean available() { return true; }
        @Override public String providerName() { return "test"; }
        @Override public String modelName() { return "test"; }
    }

    @TestConfiguration
    static class ScriptedModelConfig {
        @Bean @Primary
        ScriptedModelGateway scriptedModelGateway() { return new ScriptedModelGateway(); }
    }

    @BeforeEach void resetData() {
        gateway.planning = "";
        gateway.continuation = ScriptedModelGateway.ANSWER;
        gateway.lastToolPhasePrompt = "";
        gateway.calls.set(0);
        gateway.messages.clear();
        jdbc.update("DELETE FROM appointments");
        jdbc.update("DELETE FROM appointment_slots WHERE id LIKE 'hist-%'");
        // 确认一笔预约会把那个号源置成 available=FALSE。有一条用例真的会确认（确认之后才有记忆），
        // 号源不复原的话，排在它后面的所有 bookedCard() 都会卡在选时段那一步。
        jdbc.update("UPDATE appointment_slots SET available=TRUE");
        jdbc.update("DELETE FROM reminders");
        jdbc.update("DELETE FROM family_notifications");
        jdbc.update("DELETE FROM user_memories WHERE user_id IN ('user-001','user-002','user-f001')");
        jdbc.update("DELETE FROM tool_call_logs WHERE tool_name IN ('appointment.history','profile.memorySummary')");
    }

    // ---------------------------------------------------------------- 身份与范围

    @Test void elderReadsOnlyOwnHistory() {
        seedAppointment("a-mine", "user-001", FIRST_HOSPITAL, CARDIOLOGY, UPCOMING, DemoSeed.MORNING, "CONFIRMED");
        seedAppointment("a-theirs", "user-002", OTHERS_HOSPITAL, ENDOCRINOLOGY, UPCOMING, DemoSeed.MORNING, "CONFIRMED");
        String id = service.start().conversationId();
        plan("appointment.history", "{}");

        AgentTurnResponse result = service.chat(id, "我上次是在哪家医院看的？");

        String trace = traceResponse(result.conversationId(), "appointment.history");
        assertThat(trace).as("查到的是本人的预约").contains(FIRST_HOSPITAL).contains(CARDIOLOGY);
        assertThat(trace).as("另一个人的预约一个字都不该出现")
                .doesNotContain(OTHERS_HOSPITAL).doesNotContain(ENDOCRINOLOGY);
        assertThat(traceRequest(result.conversationId(), "appointment.history"))
                .as("查的是谁由 Java 按会话注入，留痕里能看见它没被别人指定")
                .contains("\"userId\":\"user-001\"");
    }

    @Test void caregiverReadsTheElderTheyAreBoundTo() {
        seedAppointment("a-mine", "user-001", FIRST_HOSPITAL, CARDIOLOGY, UPCOMING, DemoSeed.MORNING, "CONFIRMED");
        // user-f001 是 user-001 的女儿（care_relations 里有关系）
        String id = service.start("user-001", "user-f001").conversationId();
        plan("appointment.history", "{}");

        AgentTurnResponse result = service.chat(id, "我妈上次是在哪家医院看的？");

        String trace = traceResponse(result.conversationId(), "appointment.history");
        assertThat(trace).as("代办的家属读到的是被服务长辈的记录").contains(FIRST_HOSPITAL);
        assertThat(traceRequest(result.conversationId(), "appointment.history"))
                .as("查的仍然是长辈，不是操作者自己")
                .contains("\"userId\":\"user-001\"");
    }

    @Test void anUnauthorizedElderIsRefusedWithoutRevealingWhetherTheyExist() {
        // user-f001 只绑定了 user-001，没有绑定 user-002——不论 user-002 存不存在，答案都得一样。
        assertThatThrownBy(() -> profileQuery.history("user-f001", AgentRole.FAMILY, "user-002",
                ProfileQueryService.HistoryQuery.none()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(ProfileQueryService.REFUSED);
        assertThatThrownBy(() -> profileQuery.memorySummary("user-f001", AgentRole.FAMILY, "user-999"))
                .as("人不存在时说的话必须与越权时逐字相同，否则就成了「库里有没有他」的探针")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(ProfileQueryService.REFUSED);

        // 走智能体这条路也一样：SQL 一次都不执行、一条留痕都不落，交回给模型的是 Java 那句拒绝。
        ConversationState state = injectedSession("user-002", "user-f001", AgentRole.FAMILY);
        seedAppointment("a-theirs", "user-002", OTHERS_HOSPITAL, ENDOCRINOLOGY, UPCOMING, DemoSeed.MORNING, "CONFIRMED");
        String stageBefore = state.stage.name();
        plan("appointment.history", "{}");

        service.chat(state.id, "看看这个老人以前约过哪家医院");

        assertThat(gateway.lastToolPhasePrompt)
                .as("Java 把拒绝作为权威结果交给模型，措辞由这一份定")
                .contains("没有权限查看这位就诊人的信息");
        assertThat(gateway.lastToolPhasePrompt)
                .as("越权时连「库里有没有这个人」都不回答，更不返回任何摘要")
                .doesNotContain(OTHERS_HOSPITAL).doesNotContain(ENDOCRINOLOGY);
        assertThat(liveState(state.id).stage.name()).as("拒绝不动任务阶段").isEqualTo(stageBefore);
        assertThat(traceResponse(state.id, "appointment.history"))
                .as("被拒绝的查询不落留痕，也就不会有一条看起来像查到了什么的记录").isNull();
    }

    // ---------------------------------------------------------------- 事实本身

    @Test void recentAppointmentCarriesHospitalDepartmentDateAndStatus() {
        seedAppointment("a-1", "user-001", FIRST_HOSPITAL, CARDIOLOGY, UPCOMING, DemoSeed.MORNING, "CONFIRMED");
        String id = service.start().conversationId();
        plan("appointment.history", "{}");

        service.chat(id, "我上次约的是哪家？");

        assertThat(gateway.lastToolPhasePrompt)
                .contains(FIRST_HOSPITAL).contains(CARDIOLOGY)
                .contains(DemoSeed.chineseDay(UPCOMING)).contains(DemoSeed.MORNING.toString())
                .contains("已预约，还没到日子");
    }

    /** 模型给的条件只决定这一次查什么：状态筛和时间范围都得真的落到 SQL 上。 */
    @Test void historyQueryHonoursTheFiltersTheModelGave() {
        seedAppointment("a-1", "user-001", FIRST_HOSPITAL, CARDIOLOGY, UPCOMING, DemoSeed.MORNING, "CONFIRMED");
        seedAppointment("a-2", "user-001", PEOPLE_HOSPITAL, ENDOCRINOLOGY, PAST, DemoSeed.AFTERNOON, "CANCELLED");
        String id = service.start().conversationId();

        plan("appointment.history", "{\"status\":\"CANCELLED\"}");
        service.chat(id, "我取消过哪一次？");
        assertThat(historyTrace(id)).as("只看已取消的").contains(PEOPLE_HOSPITAL).doesNotContain(FIRST_HOSPITAL);

        plan("appointment.history", "{\"from\":\"" + UPCOMING + "\"}");
        service.chat(id, "最近的呢？");
        assertThat(historyTrace(id)).as("只看这一天及以后的").contains(FIRST_HOSPITAL).doesNotContain(PEOPLE_HOSPITAL);
        assertThat(historyTrace(id)).contains("\"total\":1");
    }

    @Test void cancelledAppointmentIsNeverDescribedAsStillValid() {
        seedAppointment("a-1", "user-001", FIRST_HOSPITAL, CARDIOLOGY, UPCOMING, DemoSeed.MORNING, "CANCELLED");
        String id = service.start().conversationId();
        plan("appointment.history", "{}");

        service.chat(id, "我上次约的是哪家？");

        assertThat(gateway.lastToolPhasePrompt).contains("已取消");
        assertThat(gateway.lastToolPhasePrompt)
                .as("取消掉的不能说成现在还有效").doesNotContain("还没到日子");
    }

    @Test void bookedAppointmentIsNeverDescribedAsAttended() {
        seedAppointment("a-1", "user-001", FIRST_HOSPITAL, CARDIOLOGY, PAST, DemoSeed.MORNING, "CONFIRMED");
        String id = service.start().conversationId();
        plan("appointment.history", "{}");

        service.chat(id, "我上次是在哪家医院看的？");

        assertThat(gateway.lastToolPhasePrompt).contains("已预约，日子已经过了");
        // 日子过去只说明那一天过去了，不说明人真的去了。这几个词一个都不能由 Java 说出口——
        // 连免责声明都不行：写上去就等于把词放回了提示词，模型照着说的风险反而更大。
        assertThat(gateway.lastToolPhasePrompt)
                .doesNotContain("去过").doesNotContain("看过").doesNotContain("就诊")
                .doesNotContain("到院").doesNotContain("到过");

        // 措辞这条硬红线本身写在提示词里，这里顺手钉住它还在。
        String system = systemPrompt.planning(
                new AgentContext("ASK_HOSPITAL", "", clock.today(), List.of()), "[]");
        assertThat(system).contains("只能说“预约过”").contains("不能说“去过”")
                .contains("只有一条记录时不能说“您经常去这家医院”");
    }

    @Test void aSingleAppointmentIsNotAHabit() {
        seedAppointment("a-1", "user-001", FIRST_HOSPITAL, CARDIOLOGY, UPCOMING, DemoSeed.MORNING, "CONFIRMED");
        String id = service.start().conversationId();
        plan("appointment.history", "{}");

        service.chat(id, "我是不是常去市第一医院？");

        assertThat(gateway.lastToolPhasePrompt).contains("还谈不上习惯");
        assertThat(gateway.lastToolPhasePrompt)
                .as("一条记录推不出「最常去」这种统计结论").doesNotContain("最常预约的医院");
        assertThat(historyTrace(id)).contains("\"total\":1").contains("\"confirmedTotal\":1")
                .contains("\"tendencies\":[]");
    }

    @Test void repeatedBookingsDoBecomeATendencyAndAreLabelledAsHistory() {
        seedAppointment("a-1", "user-001", FIRST_HOSPITAL, CARDIOLOGY, UPCOMING, DemoSeed.MORNING, "CONFIRMED");
        seedAppointment("a-2", "user-001", FIRST_HOSPITAL, CARDIOLOGY, PAST, DemoSeed.MORNING, "CONFIRMED");
        String id = service.start().conversationId();
        plan("appointment.history", "{}");

        service.chat(id, "我常去哪家医院？");

        assertThat(gateway.lastToolPhasePrompt)
                .contains("历史统计").contains("不是老人明确说过的偏好")
                .contains("最常预约的医院").contains(FIRST_HOSPITAL);
        assertThat(historyTrace(id)).contains("\"confirmedTotal\":2");
    }

    // ---------------------------------------------------------------- 四类信息分得开

    @Test void explicitPreferenceBookingFactTendencyAndCurrentRequestAreToldApart() {
        seedAppointment("a-1", "user-001", FIRST_HOSPITAL, CARDIOLOGY, UPCOMING, DemoSeed.MORNING, "CONFIRMED");
        seedAppointment("a-2", "user-001", FIRST_HOSPITAL, CARDIOLOGY, PAST, DemoSeed.MORNING, "CONFIRMED");
        memories.remember("user-001", "pref.hospital", "PREFERENCE",
                "老人要求记住：复诊只去市人民医院", MemoryStore.SOURCE_USER_STATED, "c-1");
        memories.remember("user-001", "habit.hospital", MemoryStore.KIND_HISTORY,
                "最近一次确认预约的医院是" + FIRST_HOSPITAL,
                MemoryStore.SOURCE_CONFIRMED_BOOKING, "c-2");
        memories.remember("user-001", "legacy.thing", "HABIT", "来路不明的一条记录", "LEGACY_IMPORT", "c-3");

        String id = service.start().conversationId();
        // 草稿里已经说了别的医院：这一段就是「当轮要求」
        action(id, "SET_HOSPITAL", PEOPLE_HOSPITAL_INPUT);
        plan("appointment.history", "{}");

        service.chat(id, "我以前都约的哪家？");

        String historyPrompt = gateway.lastToolPhasePrompt;
        assertThat(historyPrompt).as("预约事实").contains(FIRST_HOSPITAL).contains("已预约");
        assertThat(historyPrompt).as("统计倾向要说明是统计").contains("历史统计")
                .contains("不是老人明确说过的偏好");
        assertThat(historyPrompt).as("当轮要求单独一段，并标明优先级最高")
                .contains("本轮办理中已经明确的条件").contains("优先级最高").contains(PEOPLE_HOSPITAL_INPUT);
        assertThat(historyPrompt).as("没查 memorySummary 的这一轮，一条记忆都不许出现在上下文里")
                .doesNotContain("老人要求记住：复诊只去市人民医院")
                .doesNotContain("最近一次确认预约的医院是");
        assertNoHabitWordingInModelContext();

        plan("profile.memorySummary", "{}");
        service.chat(id, "系统里记着我什么？");

        String memoryPrompt = gateway.lastToolPhasePrompt;
        assertThat(memoryPrompt).as("明确偏好与预约沉淀分开两摞，各说各的来历")
                .contains("老人曾明确表达的偏好")
                .contains("老人要求记住：复诊只去市人民医院")
                .contains("系统根据已确认预约沉淀的历史事实")
                .contains("最近一次确认预约的医院是" + FIRST_HOSPITAL)
                .contains("不是老人明确说过的偏好");
        assertThat(memoryPrompt).as("来源不明的记录一个字都不摆进提示词，只说明有多少条")
                .contains("来源不明的记录").doesNotContain("来路不明的一条记录");
        assertThat(traceResponse(id, "profile.memorySummary"))
                .contains("\"explicitPreferences\":1").contains("\"bookingHistory\":1")
                .contains("\"unverifiableCount\":1");
    }

    /**
     * 只有一次预约时，**整段模型提示词**里不能出现「常去」「习惯」，也不能出现那条记忆的原文。
     *
     * <p>这条是端到端断言，落在模型实际收到的每一条消息上（含 system），不是落在某一段证据上。
     * 8A 初版这里会失败：`knownFacts(state)` → `memoryNote(state)` → `memories.digest(userId)`
     * 会把全部 active 记忆不分来源拼进每一轮提示词，而 `rememberBookingPreferences` 又把它写成
     * 「常去的医院是……」「习惯上午复诊」——一次预约，到了模型眼里就是一条习惯。
     *
     * <p>系统提示词里确实有「不许说常去／习惯」这条禁令，所以断言只查**非 system** 的消息：
     * 禁的是模型把一次预约当习惯说出去，不是禁止提示词教它别说。
     */
    @Test void oneBookingNeverReachesTheModelLabelledAsAHabit() {
        // 真走一遍完整办理并确认——这才是那条写入路径的现实入口
        AgentTurnResponse prepared = bookedCard();
        service.confirm(prepared.conversationId(), true, prepared.confirmation().confirmationId());
        assertThat(service.memories("user-001")).as("确认之后确实写了三条记忆").hasSize(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE status='CONFIRMED'", Integer.class))
                .isEqualTo(1);

        // 下一段对话，一句和画像完全无关的话：不该有任何记忆被默认摆到模型面前
        gateway.messages.clear();
        gateway.planning = "";
        service.chat(service.start().conversationId(), "你好");

        assertThat(gateway.messages).as("模型确实被调用过").isNotEmpty();
        assertThat(gateway.messages).filteredOn(message -> !"system".equals(message.role()))
                .as("默认上下文里连记忆的原文都不能有，更不要说被归纳成习惯")
                .allSatisfy(message -> assertThat(message.content())
                        .doesNotContain("常去").doesNotContain("习惯")
                        .doesNotContain("最近一次确认预约的医院是")
                        .doesNotContain("最近一次确认预约的科室是")
                        .doesNotContain("最近一次确认预约的时段是"));
        assertThat(traceResponse(prepared.conversationId(), "profile.memorySummary"))
                .as("这一轮根本没查过画像，所以它不是「查过了才看到的」").isNull();
    }

    /**
     * 很久以前的明确偏好：仍然作为「老人曾明确表达过」展示，但必须带更新时间、
     * 并且要求与当轮要求不一致时回头问一句——不能被说成现在仍然确定有效。
     */
    @Test void anOldExplicitPreferenceIsShownAsAHistoricalStatementNotAsACurrentOne() {
        memories.remember("user-001", "pref.hospital", "PREFERENCE",
                "老人要求记住：复诊只去市人民医院", MemoryStore.SOURCE_USER_STATED, "c-1");
        LocalDateTime longAgo = clock.now().minusDays(400);
        jdbc.update("UPDATE user_memories SET updated_at=? WHERE user_id='user-001' AND memory_key='pref.hospital'",
                Timestamp.valueOf(longAgo));

        String id = service.start().conversationId();
        plan("profile.memorySummary", "{}");
        service.chat(id, "系统里记着我什么？");

        String prompt = gateway.lastToolPhasePrompt;
        assertThat(prompt).as("很久以前说过的明确表达仍然要被展示出来，不能因为旧就吞掉")
                .contains("老人曾明确表达的偏好")
                .contains("老人要求记住：复诊只去市人民医院");
        assertThat(prompt).as("必须带更新时间，读的人才有依据判断它还算不算数")
                .contains("更新于 " + longAgo.toLocalDate());
        assertThat(prompt).as("必须带上复核语义")
                .contains("如果时间较久或与当轮要求不一致，需要询问现在是否仍适用");
        assertThat(prompt).as("不许再说成「可以直接当作他的偏好」")
                .doesNotContain("可以直接当作")
                .doesNotContain("就是现在的偏好");
    }

    /** 很久以前的明确偏好也不能压过当轮已经说定的要求。 */
    @Test void anOldExplicitPreferenceCannotOverrideTheCurrentRequest() {
        memories.remember("user-001", "pref.hospital", "PREFERENCE",
                "老人要求记住：复诊只去市人民医院", MemoryStore.SOURCE_USER_STATED, "c-1");
        jdbc.update("UPDATE user_memories SET updated_at=? WHERE user_id='user-001' AND memory_key='pref.hospital'",
                Timestamp.valueOf(clock.now().minusDays(400)));

        String id = service.start().conversationId();
        action(id, "SET_HOSPITAL", FIRST_HOSPITAL_INPUT);
        String before = liveState(id).hospital;
        String stageBefore = liveState(id).stage.name();
        plan("profile.memorySummary", "{}");

        service.chat(id, "系统里记着我什么？");

        assertThat(gateway.lastToolPhasePrompt)
                .as("当轮要求仍然标着优先级最高，并且摆在那一段里")
                .contains("本轮办理中已经明确的条件（优先级最高，历史与统计都不能覆盖它）")
                .contains(FIRST_HOSPITAL_INPUT);
        assertThat(liveState(id).hospital).as("查画像不动当轮已经定下的医院").isEqualTo(before);
        assertThat(liveState(id).stage.name()).as("查画像也不推进阶段").isEqualTo(stageBefore);
    }

    /**
     * 开始日期晚于结束日期：这是「你这两句话对不上」，不是「那段时间没有记录」。
     *
     * <p>当成空结果回答最危险——老人会以为自己那段真的没有预约，实际上他那段根本没被查过。
     */
    @Test void aReversedDateRangeIsRejectedInsteadOfLookingLikeNoHistory() {
        seedAppointment("a-1", "user-001", FIRST_HOSPITAL, CARDIOLOGY, PAST, DemoSeed.MORNING, "CONFIRMED");
        String id = service.start().conversationId();
        String stageBefore = liveState(id).stage.name();
        plan("appointment.history",
                "{\"from\":\"" + UPCOMING + "\",\"to\":\"" + PAST + "\"}");

        AgentTurnResponse result = service.chat(id, "查一下从后面那天到前面那天的记录");

        assertThat(gateway.lastToolPhasePrompt)
                .as("说清楚是范围本身反了，请重新说")
                .contains("晚于结束日期").contains("请重新说");
        assertThat(gateway.lastToolPhasePrompt)
                .as("绝不能答成「没有历史记录」")
                .doesNotContain("没有查到").doesNotContain("共 0 条")
                .doesNotContain(FIRST_HOSPITAL);
        assertThat(traceResponse(id, "appointment.history"))
                .as("范围反了就不该走到 SQL 上，也不该落留痕").isNull();
        assertThat(liveState(id).stage.name()).as("不动阶段").isEqualTo(stageBefore);
        assertThat(liveState(id).hospital).as("不动草稿").isNull();
        assertThat(result.confirmation()).as("也不建卡").isNull();
    }

    /**
     * 模型收到的每一条非 system 消息里都不许出现「常去」「习惯」。
     *
     * <p>系统提示词里写着「不许说常去／习惯」这条禁令本身会命中这两个词，所以只查非 system 消息。
     */
    private void assertNoHabitWordingInModelContext() {
        assertThat(gateway.messages).filteredOn(message -> !"system".equals(message.role()))
                .allSatisfy(message -> assertThat(message.content())
                        .doesNotContain("常去").doesNotContain("习惯"));
    }

    @Test void theCurrentRequestOutranksHistory() {
        seedAppointment("a-1", "user-001", FIRST_HOSPITAL, CARDIOLOGY, UPCOMING, DemoSeed.MORNING, "CONFIRMED");
        String id = service.start().conversationId();
        gateway.planning = """
                {"actionType":"PROPOSE_WORKFLOW_ACTION","intent":"CHANGE_HOSPITAL","toolName":null,
                 "arguments":{},"toolCalls":[],"replyDraft":"好的，我们这次去市人民医院。",
                 "dialogueMode":"FOLLOWUP_FLOW","facts":{"hospital":"%s"}}
                """.formatted(PEOPLE_HOSPITAL);
        service.chat(id, "这次去市人民医院");
        // 当轮要求已经落进草稿；这一轮再来查历史
        plan("appointment.history", "{}");

        service.chat(id, "以前约的是哪家？");

        assertThat(gateway.lastToolPhasePrompt)
                .as("历史归历史，当轮要求归当轮要求，两段都写明，谁压过谁都交代清楚")
                .contains("本轮办理中已经明确的条件").contains("优先级最高")
                .contains(PEOPLE_HOSPITAL_INPUT).contains(FIRST_HOSPITAL);
        assertThat(liveState(id).hospital).as("查历史不动当轮已经定下的医院")
                .contains(PEOPLE_HOSPITAL_INPUT);
    }

    @Test void aForgottenMemoryIsNotUsedAsACurrentFact() {
        memories.remember("user-001", "habit.hospital", MemoryStore.KIND_HISTORY,
                "最近一次确认预约的医院是" + FIRST_HOSPITAL,
                MemoryStore.SOURCE_CONFIRMED_BOOKING, "c-2");
        memories.remember("user-001", "habit.department", MemoryStore.KIND_HISTORY,
                "最近一次确认预约的科室是" + ENDOCRINOLOGY,
                MemoryStore.SOURCE_CONFIRMED_BOOKING, "c-2");
        assertThat(memories.forget("user-001", "habit.hospital")).as("老人自己按了「忘掉」").isTrue();
        String id = service.start().conversationId();
        plan("profile.memorySummary", "{}");

        service.chat(id, "系统里记着我什么？");

        assertThat(gateway.lastToolPhasePrompt).contains(ENDOCRINOLOGY);
        assertThat(gateway.lastToolPhasePrompt)
                .as("忘掉的记忆不能再当成当前事实拿出来").doesNotContain(FIRST_HOSPITAL);
    }

    // ---------------------------------------------------------------- 只读的边界

    @Test void historyQueryHasAFieldWhitelistAndAHardLimit() {
        for (int index = 1; index <= 7; index++) {
            seedAppointment("a-" + index, "user-001", FIRST_HOSPITAL, CARDIOLOGY,
                    UPCOMING.plusDays(index), LocalTime.of(9, 0), "CONFIRMED");
        }
        // 这条记录带着办理细节：它们属于办理过程，不属于画像，一个都不该跟着历史一起出去。
        jdbc.update("""
                UPDATE appointments SET departure_time=?,transport=?,materials=?,family_status=? WHERE id=?
                """, Timestamp.valueOf(LocalDateTime.of(UPCOMING, LocalTime.of(8, 0))),
                "打车", "身份证、医保卡", "PENDING", "a-1");
        String id = service.start().conversationId();
        plan("appointment.history", "{}");

        service.chat(id, "我以前都约过哪几次？");

        String trace = historyTrace(id);
        assertThat(trace).as("总数照实说").contains("\"total\":7");
        assertThat(trace).as("一次最多摆 5 条，上限由 Java 定，模型改不了").contains("\"returned\":5");
        assertThat(trace).as("字段白名单：只有日期、时刻、医院、科室、状态")
                .doesNotContain("departure").doesNotContain("transport")
                .doesNotContain("materials").doesNotContain("family_status").doesNotContain("familyStatus");
        assertThat(gateway.lastToolPhasePrompt).as("回到模型的那段也带上了「共几条、显示几条」")
                .contains("共 7 条").contains("列出 5 条");
    }

    @Test void aModelHistoryQueryDoesNotTouchTheDraft() {
        seedAppointment("a-1", "user-001", FIRST_HOSPITAL, CARDIOLOGY, UPCOMING, DemoSeed.MORNING, "CONFIRMED");
        String id = draft(FIRST_HOSPITAL_INPUT, DemoSeed.CARDIOLOGY, DemoSeed.checkupDay());
        // 会话状态是活的，必须当场把值取出来；拿对象引用再比就是自己跟自己比。
        String hospitalBefore = liveState(id).hospital;
        String departmentBefore = liveState(id).department;
        LocalDate dateBefore = liveState(id).date;
        Object slotBefore = liveState(id).selectedSlot;
        Object alternativesBefore = liveState(id).alternatives;
        String stageBefore = liveState(id).stage.name();
        plan("appointment.history", "{}");

        AgentTurnResponse result = service.chat(id, "以前约的是哪家？");

        assertThat(liveState(id).hospital).isEqualTo(hospitalBefore);
        assertThat(liveState(id).department).isEqualTo(departmentBefore);
        assertThat(liveState(id).date).isEqualTo(dateBefore);
        assertThat(liveState(id).selectedSlot).as("不替老人选时段").isEqualTo(slotBefore);
        assertThat(liveState(id).alternatives).as("不碰候选号源").isEqualTo(alternativesBefore);
        assertThat(liveState(id).stage.name()).as("不推进任务阶段").isEqualTo(stageBefore);
        assertThat(result.quickReplies()).as("查询不是办理入口：不摆「按这家约」的按钮")
                .noneMatch(item -> "SELECT_SLOT".equals(item.action()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE status='CONFIRMED'", Integer.class))
                .as("查询不产生任何预约").isEqualTo(1);
    }

    @Test void aModelHistoryQueryKeepsTheConfirmationCardIntact() {
        // 历史上这一条要落得离即将办的那次足够远：否则 bookedCard() 会被重复预约或时间冲突拦下，
        // 根本走不到确认卡，这条测试就测不到「查询会不会动那张卡」。
        seedAppointment("a-1", "user-001", FIRST_HOSPITAL, CARDIOLOGY, PAST, DemoSeed.MORNING, "CONFIRMED");
        AgentTurnResponse prepared = bookedCard();
        String id = prepared.conversationId();
        String subjectBefore = liveState(id).userId;
        plan("appointment.history", "{}");

        AgentTurnResponse result = service.chat(id, "以前约的是哪家？");

        assertThat(result.confirmation()).as("老人手上的卡还在").isNotNull();
        assertThat(result.confirmation().confirmationId())
                .isEqualTo(prepared.confirmation().confirmationId());
        assertThat(result.confirmation().operations()).isEqualTo(prepared.confirmation().operations());
        assertThat(result.stage()).as("还在等确认，没有被这次查询消费掉").isEqualTo("AWAITING_CONFIRMATION");
        assertThat(liveState(id).confirmationId).isEqualTo(prepared.confirmation().confirmationId());
        assertThat(liveState(id).userId).as("查询也没有换服务对象").isEqualTo(subjectBefore);
        assertThat(result.reply()).as("有卡在手时，事实由 Java 直说，不再交给模型润色")
                .contains(FIRST_HOSPITAL).contains("已预约");
    }

    @Test void noHistoryMeansNoInventedHospitalOrPreference() {
        String id = service.start().conversationId();
        plan("appointment.history", "{}");
        service.chat(id, "我以前都约的哪家医院？");

        assertThat(gateway.lastToolPhasePrompt).contains("没有查到符合条件的记录");
        assertThat(gateway.lastToolPhasePrompt)
                .as("查不到就如实说，不编一家医院出来")
                .doesNotContain(FIRST_HOSPITAL).doesNotContain(PEOPLE_HOSPITAL)
                .doesNotContain("最常预约的医院").doesNotContain("常去");

        plan("profile.memorySummary", "{}");
        service.chat(id, "系统里记着我什么？");
        assertThat(gateway.lastToolPhasePrompt).contains("没有查到记录");
        assertThat(gateway.lastToolPhasePrompt).doesNotContain(FIRST_HOSPITAL).doesNotContain("常去");
    }

    // ---------------------------------------------------------------- 沿用历史：只是问，不是替老人定

    @Test void theModelMayAskWhetherToReuseTheLastBooking() {
        seedAppointment("a-1", "user-001", FIRST_HOSPITAL, CARDIOLOGY, UPCOMING, DemoSeed.MORNING, "CONFIRMED");
        String id = service.start().conversationId();
        plan("appointment.history", "{}");
        gateway.continuation = """
                {"actionType":"ANSWER","intent":"UNKNOWN","toolName":null,"arguments":{},
                 "replyDraft":"您上次预约的是市第一医院心内科，这次还考虑这家医院吗？",
                 "dialogueMode":"FOLLOWUP_FLOW","facts":{}}
                """;

        AgentTurnResponse result = service.chat(id, "我想约复诊，不知道约哪家");

        assertThat(result.reply()).as("这句询问建立在真实记录上，所以可以说").contains("上次预约的是");
        assertThat(result.reply()).contains(FIRST_HOSPITAL_INPUT).contains(CARDIOLOGY)
                .contains("还考虑这家医院吗");
        assertThat(liveState(id).hospital).as("问一句不等于替老人定下来").isNull();
        assertThat(liveState(id).date).isNull();
    }

    @Test void refusingToReuseTheLastBookingLeavesTheDraftAlone() {
        seedAppointment("a-1", "user-001", FIRST_HOSPITAL, CARDIOLOGY, UPCOMING, DemoSeed.MORNING, "CONFIRMED");
        String id = service.start().conversationId();
        plan("appointment.history", "{}");
        gateway.continuation = """
                {"actionType":"ANSWER","intent":"UNKNOWN","toolName":null,"arguments":{},
                 "replyDraft":"好的，那您想约哪家医院？",
                 "dialogueMode":"FOLLOWUP_FLOW","facts":{}}
                """;
        service.chat(id, "我想约复诊，不知道约哪家");

        // 下一轮老人明确说不沿用：这一轮只是一句话，不写草稿、不建预约。
        gateway.planning = """
                {"actionType":"ANSWER","intent":"UNKNOWN","toolName":null,"arguments":{},
                 "replyDraft":"好的，那您说一家医院，我帮您看有没有号。",
                 "dialogueMode":"FOLLOWUP_FLOW","facts":{}}
                """;
        service.chat(id, "不去那家了");

        assertThat(liveState(id).hospital).as("拒绝之后不强行把历史写进草稿").isNull();
        assertThat(liveState(id).date).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE user_id='user-001'", Integer.class))
                .as("只读那一轮也没有偷偷替老人约上").isEqualTo(1);
    }

    @Test void agreeingToReuseTheLastBookingStillGoesThroughTheNormalBookingFlow() {
        seedAppointment("a-1", "user-001", FIRST_HOSPITAL, CARDIOLOGY, UPCOMING, DemoSeed.MORNING, "CONFIRMED");
        String id = service.start().conversationId();
        plan("appointment.history", "{}");
        service.chat(id, "我想约复诊，不知道约哪家");
        assertThat(liveState(id).hospital).as("第一轮只查历史，草稿仍然是空的").isNull();

        // 老人明说「这次还选它」：下一轮走正常的办理动作，而不是靠那次只读查询写草稿。
        gateway.planning = """
                {"actionType":"PROPOSE_WORKFLOW_ACTION","intent":"CHANGE_HOSPITAL","toolName":null,
                 "arguments":{},"toolCalls":[],"replyDraft":"好的，这次还去市第一医院。",
                 "dialogueMode":"FOLLOWUP_FLOW","facts":{"hospital":"%s"}}
                """.formatted(FIRST_HOSPITAL);
        service.chat(id, "对，就那家");

        assertThat(liveState(id).hospitalId).as("草稿由正常的业务动作写，不由只读工具写")
                .isEqualTo("h001");
        assertThat(liveState(id).hospital).contains(FIRST_HOSPITAL_INPUT);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM tool_call_logs WHERE conversation_id=? AND tool_name='appointment.history'",
                Integer.class, id)).as("第二轮没有再查一次历史").isEqualTo(1);
    }

    // ---------------------------------------------------------------- 脚手架

    private void seedAppointment(String id, String userId, String hospital, String department,
                                 LocalDate day, LocalTime time, String status) {
        jdbc.update("""
                INSERT INTO appointment_slots(id,hospital_id,hospital_name,department,appointment_date,appointment_time,available)
                VALUES (?,?,?,?,?,?,TRUE)
                """, "hist-slot-" + id, "h001", hospital, department, Date.valueOf(day), Time.valueOf(time));
        jdbc.update("INSERT INTO appointments(id,slot_id,user_id,status,created_at) VALUES (?,?,?,?,?)",
                id, "hist-slot-" + id, userId, status, Timestamp.valueOf(LocalDateTime.now()));
    }

    /** 模型这一轮点哪个工具、带哪几个参数。 */
    private void plan(String toolName, String argumentsJson) {
        gateway.planning = """
                {"actionType":"CALL_READ_TOOL","intent":"QUERY_APPOINTMENTS","toolName":"%s",
                 "arguments":%s,"replyDraft":null,"dialogueMode":"FOLLOWUP_FLOW","facts":{}}
                """.formatted(toolName, argumentsJson);
    }

    /** 起一段会话并把草稿摆成指定条件（这一步本身也会调号源工具，留痕按工具名区分）。 */
    private String draft(String hospital, String departmentId, LocalDate day) {
        String id = service.start().conversationId();
        action(id, "SET_HOSPITAL", hospital);
        action(id, "SET_DEPARTMENT", departmentId);
        action(id, "SET_DATE", day.toString());
        gateway.planning = "";
        gateway.lastToolPhasePrompt = "";
        return id;
    }

    /** 走完整办理漏斗，停在确认卡上（还没确认）。 */
    private AgentTurnResponse bookedCard() {
        String id = service.start().conversationId();
        action(id, "SET_HOSPITAL", "h001");
        action(id, "SET_DEPARTMENT", DemoSeed.CARDIOLOGY);
        action(id, "SET_DATE", DemoSeed.checkupDay().toString());
        action(id, "SELECT_SLOT", DemoSeed.morningSlot());
        action(id, "SET_ALTERNATIVE", "true");
        action(id, "SET_COMPANION", "true");
        action(id, "SET_TRAVEL", "true");
        action(id, "SET_TRANSPORT", "打车");
        action(id, "SET_NOTIFY", "true");
        AgentTurnResponse turn = action(id, "SET_CONTACT", "family-001");
        assertThat(turn.stage()).as(turn.reply()).isEqualTo("AWAITING_CONFIRMATION");
        gateway.planning = "";
        gateway.lastToolPhasePrompt = "";
        return turn;
    }

    private AgentTurnResponse action(String id, String action, String value) {
        return service.act(id, action, value, action);
    }

    private ConversationState liveState(String id) {
        return sessions().get(id);
    }

    /** 直接摆一段会话状态，用来构造正常情况下「越权目标」根本不该出现的场景。 */
    private ConversationState injectedSession(String userId, String actorId, AgentRole role) {
        ConversationState state = new ConversationState(
                UUID.randomUUID().toString(), userId, actorId, role, "女儿");
        sessions().put(state.id, state);
        return state;
    }

    @SuppressWarnings("unchecked")
    private Map<String, ConversationState> sessions() {
        return (Map<String, ConversationState>) ReflectionTestUtils.getField(service, "sessions");
    }

    /** 该会话最近一次这个工具的返回留痕（JSON 字符串）；没有就返回 null。 */
    private String traceResponse(String conversationId, String toolName) {
        List<String> rows = jdbc.queryForList("""
                SELECT response_json FROM tool_call_logs
                WHERE conversation_id=? AND tool_name=? ORDER BY id
                """, String.class, conversationId, toolName);
        return rows.isEmpty() ? null : rows.get(rows.size() - 1);
    }

    /** 同上，取入参那一侧：用来确认「查的是谁」确实由 Java 注入，而不是模型给的。 */
    private String traceRequest(String conversationId, String toolName) {
        List<String> rows = jdbc.queryForList("""
                SELECT request_json FROM tool_call_logs
                WHERE conversation_id=? AND tool_name=? ORDER BY id
                """, String.class, conversationId, toolName);
        return rows.isEmpty() ? null : rows.get(rows.size() - 1);
    }

    private String historyTrace(String conversationId) {
        String trace = traceResponse(conversationId, "appointment.history");
        assertThat(trace).as("这一轮没有调用 appointment.history").isNotNull();
        return trace;
    }
}
