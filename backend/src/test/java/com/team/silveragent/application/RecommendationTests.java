package com.team.silveragent.application;

import com.team.silveragent.agent.AgentContext;
import com.team.silveragent.agent.AgentRole;
import com.team.silveragent.agent.model.ModelGateway;
import com.team.silveragent.agent.model.ModelRequest;
import com.team.silveragent.agent.planning.AgentSystemPrompt;
import com.team.silveragent.application.longterm.MemoryStore;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 8B-1：基于真实数据的个性化推荐。
 *
 * <p>这一轮只做「读真实信息 + 给 2—3 个选择和理由」，不做偏好写入、不做全局麦克风。
 * 用例围绕四件事：
 * <ol>
 *   <li><b>依据有优先级</b>：当轮要求、当前明确排除、仍适用的明确偏好、统计倾向、单次历史，
 *       低的一律不能压过高的一级。</li>
 *   <li><b>事实不编</b>：没有号源不能说可以预约，没有距离数据不能说最近，
 *       没有路线数据就不给用时，工具失败就说失败。</li>
 *   <li><b>推荐不替人做决定</b>：模型发起的推荐轮只读——不写草稿、不推进阶段、
 *       不建卡也不动手上那张卡；老人真的选了，下一轮走正常办理。</li>
 *   <li><b>话术的红线写在提示词里</b>：不评价医疗水平、不按症状选科、模拟数据保留「模拟」。</li>
 * </ol>
 *
 * <p>8B-1 收尾之后，推荐轮的分工是：模型在最后一次调用里同时给出<b>结构化</b>
 * {@code recommendations} 与最终话语 {@code answering}，Java 只校验结构化那一半，
 * 通过就把 {@code answering} 逐字交付——不再有第二次润色。所以这里的断言分两层：
 * 校验<b>不过</b>时看 Java 退回去的结构化问题（{@code lastToolPhasePrompt}），
 * 校验<b>通过</b>时看交付给老人的那句话（{@code result.reply()}）。
 *
 * <p><b>哪些是 Java 兜住的，哪些只是提示词兜住的，这里分得很清楚</b>：
 * 凡是断言落在 Java 写出来的那段文本、工具入参、数据库状态或返回条数上的，是 Java 层的保证；
 * 落在系统提示词里的那几条（排除项、医疗水平措辞、按症状选科），是**模型层**的保证——
 * 自动测试只能证明提示词里写了这条规则，证明不了模型一定照做。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-recommendation;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=true"})
class RecommendationTests {

    private static final String FIRST_HOSPITAL = "市第一医院（模拟）";
    private static final String FIRST_HOSPITAL_INPUT = "市第一医院";
    private static final String PEOPLE_HOSPITAL = "市人民医院（模拟）";
    private static final String PEOPLE_HOSPITAL_INPUT = "市人民医院";
    /** 目录里根本没有这家医院：用来验证「查不到就如实说」，不拿别的医院顶上。 */
    private static final String UNKNOWN_HOSPITAL = "协和医院";
    private static final String CARDIOLOGY = "心内科";

    private static final LocalDate UPCOMING = DemoSeed.checkupDay();
    private static final LocalDate PAST = DemoSeed.checkupDay().minusDays(30);
    /**
     * travel_routes 里只配了「家属开车 / 打车 / 公交」三种。
     * 用「步行」是构造一次真实的工具失败：路线查不到，推荐理由里就不该出现任何用时。
     */
    private static final String NO_ROUTE_TRANSPORT = "步行";

    @Autowired FollowupAgentService service;
    @Autowired AgentSystemPrompt systemPrompt;
    @Autowired ToolRegistry registry;
    @Autowired MemoryStore memories;
    @Autowired BusinessClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired ScriptedModelGateway gateway;

    /**
     * 按脚本回答的规划模型。
     *
     * <p>第一轮提哪个工具由用例决定；工具结果回到模型之后逐轮从 {@link #continuations} 里取下一句，
     * 取完就一直用最后的默认回答。这样「模型查一次就答」「模型连着查三次」两种节奏都能编出来。
     */
    static class ScriptedModelGateway implements ModelGateway {
        private static final String ANSWER = """
                {"actionType":"ANSWER","intent":"UNKNOWN","toolName":null,"arguments":{},
                 "replyDraft":"我按您说的查过了，上面就是这次的结果。",
                 "dialogueMode":"FOLLOWUP_FLOW","facts":{}}
                """;

        volatile String planning = "";
        /** 工具结果回到模型时逐轮要说的话。用完就落到 {@link #fallback}。 */
        final Deque<String> continuations = new ArrayDeque<>();
        volatile String fallback = ANSWER;
        /** 工具结果回到模型时它实际看到的那段证据（含 Java 摆出的事实与草稿）。 */
        volatile String lastToolPhasePrompt = "";
        /**
         * 回答阶段模型实际看到的那段话（含 Java 拼好的权威草稿）。
         *
         * <p>推荐轮<b>不再</b>经过这一层：模型自己那句 {@code answering} 就是最终话语。
         * 所以这条字段现在的用处是「证明它没被调用」——一次正常的推荐轮里它应当是空的。
         */
        volatile String lastAnswerPrompt = "";
        /**
         * 回答阶段要说的话。留空就沿用 {@link #planning}（既有用例都是这么写的）。
         *
         * <p>推荐轮的用例把它设成一句明显不相干的话：交付出去的必须还是模型的 {@code answering}，
         * 一旦被润色层改写，断言立刻就能看出来。
         */
        volatile String answerReply = null;
        final AtomicInteger calls = new AtomicInteger();
        final List<ModelRequest.Message> messages = Collections.synchronizedList(new ArrayList<>());

        @Override public String complete(ModelRequest request) {
            calls.incrementAndGet();
            messages.addAll(request.messages());
            String latest = request.messages().isEmpty() ? ""
                    : request.messages().get(request.messages().size() - 1).content();
            if (latest.contains("同一用户轮次内刚刚执行完成的真实只读工具结果")) {
                lastToolPhasePrompt = latest;
                return continuations.isEmpty() ? fallback : continuations.poll();
            }
            if (latest.contains("权威回复草稿")) {
                lastAnswerPrompt = latest;
                if (answerReply != null) return answerReply;
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
        gateway.fallback = ScriptedModelGateway.ANSWER;
        gateway.answerReply = null;
        gateway.continuations.clear();
        gateway.lastToolPhasePrompt = "";
        gateway.lastAnswerPrompt = "";
        gateway.calls.set(0);
        gateway.messages.clear();
        jdbc.update("DELETE FROM appointments");
        jdbc.update("DELETE FROM appointment_slots WHERE id LIKE 'rec-%'");
        // 确认一笔预约会把号源置成 available=FALSE，后面的 useExistingCard() 就再也选不到那一格。
        jdbc.update("UPDATE appointment_slots SET available=TRUE");
        jdbc.update("DELETE FROM reminders");
        jdbc.update("DELETE FROM family_notifications");
        jdbc.update("DELETE FROM user_memories WHERE user_id IN ('user-001','user-002','user-f001')");
        jdbc.update("""
                DELETE FROM tool_call_logs WHERE tool_name IN
                ('appointment.history','profile.memorySummary','schedule.checkConflict','travel.routePlan')
                """);
    }

    // ---------------------------------------------------------------- 依据与优先级

    /** 当轮明确说的医院压过历史：推荐依据里它得排在「优先级最高」那一段。 */
    @Test void theHospitalTheElderJustNamedOutranksTheHistoryBehindIt() {
        seedAppointment("rec-old", "user-001", FIRST_HOSPITAL, CARDIOLOGY, PAST, DemoSeed.MORNING, "CONFIRMED");
        String id = service.start().conversationId();
        action(id, "SET_HOSPITAL", PEOPLE_HOSPITAL_INPUT);
        resetGateway();

        plan("appointment.history", "{}");
        service.chat(id, "根据我以前的情况推荐一下");

        String evidence = gateway.lastToolPhasePrompt;
        assertThat(evidence).as("历史照实摆出来，但标明它是历史")
                .contains(FIRST_HOSPITAL).contains("已预约，日子已经过了");
        assertThat(evidence).as("当轮要求单独一段，并写明优先级最高")
                .contains("本轮办理中已经明确的条件（优先级最高，历史与统计都不能覆盖它）")
                .contains(PEOPLE_HOSPITAL_INPUT);
        assertThat(evidence).as("查历史不动当轮已经定下的医院")
                .doesNotContain("最常预约的医院");
        assertThat(liveState(id).hospital).contains(PEOPLE_HOSPITAL_INPUT);
    }

    /** 当轮说的时段压过历史时段倾向：下午是他现在要的，上午只是过去几次的记录。 */
    @Test void thePeriodTheElderJustNamedOutranksTheHistoricalPeriodTendency() {
        seedAppointment("rec-1", "user-001", FIRST_HOSPITAL, CARDIOLOGY, PAST, DemoSeed.MORNING, "CONFIRMED");
        seedAppointment("rec-2", "user-001", FIRST_HOSPITAL, CARDIOLOGY, PAST.minusDays(7), DemoSeed.MORNING, "CONFIRMED");
        // 先有一个推到选时段的草稿：老人改时段这一步要拿真实号源当候选，空草稿时它只会先问日期。
        String id = draft(FIRST_HOSPITAL_INPUT, DemoSeed.CARDIOLOGY, UPCOMING);

        // 这一轮：老人自己说了「这次要下午」
        gateway.planning = """
                {"actionType":"PROPOSE_WORKFLOW_ACTION","intent":"CHANGE_TIME","toolName":null,
                 "arguments":{},"toolCalls":[],"replyDraft":"好，这次帮您安排下午的复诊。",
                 "dialogueMode":"FOLLOWUP_FLOW","facts":{"timePreference":"AFTERNOON"}}
                """;
        service.chat(id, "这次想下午去");
        assertThat(liveState(id).timePreference).as("当轮说的下午落在草稿上").isEqualTo("AFTERNOON");
        resetGateway();

        plan("appointment.history", "{}");
        service.chat(id, "帮我看看哪个时间合适");

        String evidence = gateway.lastToolPhasePrompt;
        assertThat(evidence).as("上午确实被统计出来了，而且写明是统计出来的")
                .contains("历史统计").contains("预约时段偏是上午")
                .contains("不是老人明确说过的偏好");
        assertThat(evidence).as("当轮的下午写在优先级最高那一段里，压过统计倾向")
                .contains("本轮办理中已经明确的条件（优先级最高，历史与统计都不能覆盖它）")
                .contains("时段=下午");
    }

    /**
     * 「当前明确排除」在优先级表里仅次于当轮要求。
     *
     * <p><b>这条是模型层的保证，不是 Java 层。</b>排除项只存在于对话里：老人在哪一轮说了
     * 「别推荐市第一医院」，Java 没有为它建任何状态，{@code recommendHospitals} 这类
     * Java 自己拼候选的路径照样会把目录里的医院都列出来。所以这里只能钉住「规则确实写进了提示词」，
     * 钉不住模型一定照做——真实模型语义留待手动测试。
     */
    @Test void theModelIsToldThatAnExplicitlyExcludedOptionOutranksHistory() {
        String system = systemPrompt.planning(
                new AgentContext("ASK_HOSPITAL", "", clock.today(), List.of()), "[]");
        assertThat(system).contains("当前明确排除");
        assertThat(system).contains("优先级从高到低");
        assertThat(system).as("排除项要排在偏好和统计之前")
                .containsSubsequence("当前明确要求", "当前明确排除", "统计倾向");
        assertThat(system).as("并且明确说了低优先级不能覆盖高优先级")
                .contains("低优先级的一律不能覆盖高优先级的");
    }

    /** 只查到一次预约时，它推不出任何习惯式的推荐理由。 */
    @Test void aSingleBookingNeverBecomesAHabitStyleRecommendation() {
        seedAppointment("rec-1", "user-001", FIRST_HOSPITAL, CARDIOLOGY, UPCOMING, DemoSeed.MORNING, "CONFIRMED");
        String id = service.start().conversationId();
        plan("appointment.history", "{}");

        service.chat(id, "根据我以前的情况推荐一下");

        String evidence = gateway.lastToolPhasePrompt;
        assertThat(evidence).contains("只查到 1 次已确认的预约，还谈不上习惯");
        assertThat(evidence).as("一条记录推不出「最常去」，更推不出「习惯」")
                .doesNotContain("最常预约的医院").doesNotContain("习惯上午")
                .doesNotContain("您经常去");
    }

    /** 多次预约形成的统计只能当次级参考，必须标明是统计、不是老人说过的偏好。 */
    @Test void repeatedBookingsAreOnlySecondaryEvidenceAndAreLabelledAsStatistics() {
        seedAppointment("rec-1", "user-001", FIRST_HOSPITAL, CARDIOLOGY, UPCOMING, DemoSeed.MORNING, "CONFIRMED");
        seedAppointment("rec-2", "user-001", FIRST_HOSPITAL, CARDIOLOGY, PAST, DemoSeed.MORNING, "CONFIRMED");
        String id = service.start().conversationId();
        plan("appointment.history", "{}");

        service.chat(id, "我以前都约的哪家？现在推荐几个");

        String evidence = gateway.lastToolPhasePrompt;
        assertThat(evidence).contains("历史统计").contains("最常预约的医院").contains(FIRST_HOSPITAL)
                .contains("不是老人明确说过的偏好");
        assertThat(evidence).as("统计和当轮要求同时在，谁压过谁说清楚")
                .contains("本轮办理中还没有确定医院、科室、日期或时间");
        assertThat(evidence).as("统计不能顶上明确偏好那条说法")
                .doesNotContain("老人曾明确表达的偏好");
    }

    /** 很久以前说过的明确偏好：可以展示，但不能当成「现在还成立」去压当轮要求。 */
    @Test void anOldExplicitPreferenceCannotOverrideWhatTheElderIsAskingForNow() {
        memories.remember("user-001", "pref.hospital", "PREFERENCE",
                "老人要求记住：复诊只去市人民医院", MemoryStore.SOURCE_USER_STATED, "rec-c1");
        jdbc.update("UPDATE user_memories SET updated_at=? WHERE user_id='user-001' AND memory_key='pref.hospital'",
                Timestamp.valueOf(clock.now().minusDays(400)));
        String id = service.start().conversationId();
        action(id, "SET_HOSPITAL", FIRST_HOSPITAL_INPUT);
        String hospitalBefore = liveState(id).hospital;
        String stageBefore = liveState(id).stage.name();
        resetGateway();

        plan("profile.memorySummary", "{}");
        service.chat(id, "根据我以前的情况推荐一下");

        String evidence = gateway.lastToolPhasePrompt;
        assertThat(evidence).as("很久以前说过的偏好仍然展示，但不许说成现在仍然确定有效")
                .contains("老人曾明确表达的偏好").contains("老人要求记住：复诊只去市人民医院")
                .contains("更新于 " + clock.now().minusDays(400).toLocalDate())
                .contains("需要询问现在是否仍适用")
                .doesNotContain("可以直接当作");
        assertThat(evidence).as("当轮要求标着优先级最高，并且摆在那一段里")
                .contains("本轮办理中已经明确的条件（优先级最高，历史与统计都不能覆盖它）")
                .contains(FIRST_HOSPITAL_INPUT);
        assertThat(liveState(id).hospital).as("查偏好不动当轮已经定下的医院").isEqualTo(hospitalBefore);
        assertThat(liveState(id).stage.name()).as("也不推进阶段").isEqualTo(stageBefore);
    }

    /** 已经忘掉的记忆不参与推荐。 */
    @Test void aForgottenPreferenceIsNotUsedWhenRecommending() {
        memories.remember("user-001", "pref.hospital", "PREFERENCE",
                "老人要求记住：复诊只去市人民医院", MemoryStore.SOURCE_USER_STATED, "rec-c1");
        memories.remember("user-001", "pref.department", "PREFERENCE",
                "老人要求记住：复诊挂心内科", MemoryStore.SOURCE_USER_STATED, "rec-c2");
        assertThat(memories.forget("user-001", "pref.hospital")).as("老人自己按了「忘掉」").isTrue();
        String id = service.start().conversationId();
        plan("profile.memorySummary", "{}");

        service.chat(id, "根据我以前的情况推荐一下");

        assertThat(gateway.lastToolPhasePrompt).contains("复诊挂心内科");
        assertThat(gateway.lastToolPhasePrompt)
                .as("忘掉的那条不能再当成当前事实拿出来当推荐依据")
                .doesNotContain("复诊只去市人民医院");
    }

    // ---------------------------------------------------------------- 事实不能编

    /** 没有号源就不能说成「可以预约」。 */
    @Test void aHospitalWithoutSlotsIsNeverOfferedAsBookable() {
        String id = service.start().conversationId();
        // 下周六：一条号源都没有
        plan("appointment.querySlots",
                "{\"hospital\":\"" + FIRST_HOSPITAL + "\",\"department\":\"" + CARDIOLOGY
                        + "\",\"date\":\"" + DemoSeed.emptyDay() + "\"}");

        service.chat(id, "上次预约的那家这次还有号吗？");

        String evidence = gateway.lastToolPhasePrompt;
        assertThat(evidence).contains("没有可预约时段");
        assertThat(evidence).as("没有真实号源时不能说成可以预约")
                .doesNotContain("可以预约").doesNotContain("要按这个来吗");
        assertThat(liveState(id).selectedSlot).as("查到没号也不替老人选时段").isNull();
    }

    /** 只有取消过的记录时，不能说存在当前有效的预约。 */
    @Test void anOnlyCancelledBookingIsNeverPresentedAsACurrentAppointment() {
        seedAppointment("rec-1", "user-001", FIRST_HOSPITAL, CARDIOLOGY, UPCOMING, DemoSeed.MORNING, "CANCELLED");
        String id = service.start().conversationId();
        plan("appointment.history", "{}");

        service.chat(id, "根据我以前的情况推荐一下");

        String evidence = gateway.lastToolPhasePrompt;
        assertThat(evidence).contains("已取消").contains(FIRST_HOSPITAL);
        assertThat(evidence).as("取消掉的那条不能说成现在还有效")
                .doesNotContain("还没到日子").doesNotContain("已预约，");
    }

    /**
     * 没有距离数据时不能给出「最近」这种说法，也不能编一个用时出来。
     *
     * <p>用「步行」触发真实的工具失败：travel_routes 里没有这种交通方式。
     */
    @Test void withoutDistanceDataNoNearestOrTravelTimeIsInvented() {
        String id = service.start().conversationId();
        plan("travel.routePlan", """
                {"hospital":"%s","date":"%s","time":"09:00","transport":"%s"}
                """.formatted(FIRST_HOSPITAL, UPCOMING, NO_ROUTE_TRANSPORT));

        service.chat(id, "这家医院远不远，路上要多久？");

        String evidence = gateway.lastToolPhasePrompt;
        assertThat(evidence).as("查不到路线就说查不到，并给出可恢复的下一步")
                .contains("没有查到从您登记的住址到").contains("没法给出预计用时");
        assertThat(evidence).as("不许编距离、用时，也不许说「最近」")
                .doesNotContain("最近").doesNotContain("公里").doesNotContain("米")
                .doesNotContain("预计约").doesNotContain("分钟");
    }

    /** 查得到路线时，用时和距离必须来自工具结果，并且继续保留「模拟」标识。 */
    @Test void aRouteEstimateUsesOnlyRealToolFactsAndStaysReadOnly() {
        String id = service.start().conversationId();
        plan("travel.routePlan", """
                {"hospital":"%s","date":"%s","time":"09:00","transport":"打车"}
                """.formatted(FIRST_HOSPITAL, UPCOMING));

        AgentTurnResponse result = service.chat(id, "去这家医院打车要多久？");

        String evidence = gateway.lastToolPhasePrompt;
        assertThat(evidence).as("用时和距离来自 travel_routes 的真实行").contains("预计约35分钟");
        assertThat(evidence).as("模拟路线必须自己说出来").contains("模拟路线");
        assertThat(evidence).as("这一轮只是比较，说明没有改动手上的预约").contains("没有改动手上的预约");
        assertThat(result.uiDirective()).as("比较用的估算不该把老人带到地图页").isNull();
        assertThat(liveState(id).travelPlan).as("不写草稿里的出行计划").isNull();
        assertThat(liveState(id).selectedSlot).as("不替老人选时段").isNull();
    }

    /** 日程冲突要当面点名，不能悄悄混进候选里。 */
    @Test void aConflictingTimeIsFlaggedInsteadOfSlidingIntoTheChoices() {
        String id = service.start().conversationId();
        plan("schedule.checkConflict",
                "{\"date\":\"" + UPCOMING + "\",\"time\":\"10:30\"}");

        service.chat(id, "这天的十点半和我日程冲突吗？");

        String evidence = gateway.lastToolPhasePrompt;
        assertThat(evidence).as("冲突的时段必须写明有冲突，并点出是哪件事")
                .contains("与您已有的日程有冲突").contains("社区体检");
        assertThat(evidence).as("冲突只是比较结果，不是替老人改期的授权")
                .contains("没有改动手上的预约").doesNotContain("已改到");
        assertThat(liveState(id).conflicts).as("只读查询不写草稿里的冲突").isEmpty();
        assertThat(liveState(id).alternatives).as("也不写候选号源").isEmpty();
    }

    /** 不冲突的时间也要明确说「不冲突」，老人才敢选。 */
    @Test void aFreeTimeIsExplicitlyCalledFree() {
        String id = service.start().conversationId();
        plan("schedule.checkConflict",
                "{\"date\":\"" + UPCOMING + "\",\"time\":\"14:00\"}");

        service.chat(id, "这天下午两点呢？");

        String evidence = gateway.lastToolPhasePrompt;
        assertThat(evidence).as("不冲突要说清楚是哪一天的几点，老人才敢选")
                .contains("不冲突").contains(DemoSeed.chineseDay(UPCOMING)).contains("14:00");
        assertThat(evidence).as("不冲突也只是比较结果").contains("没有改动手上的预约");
    }

    // ---------------------------------------------------------------- 条数与来源

    /**
     * 推荐清单的条数上限由 Java 兜：老人一次不该面对一大排选择。
     *
     * <p>Java 自己能拼候选的几条路（医院目录、科室目录、按科室找医院）一律 limit(3)；
     * 模型最后怎么措辞、怎么给出 2—3 条，属提示词层，不在这个断言范围里。
     */
    @Test void javaCapsEveryRecommendationListAtThree() {
        String first = service.start().conversationId();
        plan("hospital.list", "{}");
        AgentTurnResponse hospitals = service.chat(first, "有哪些医院？");
        assertThat(hospitals.quickReplies()).as("医院候选最多三个").hasSizeLessThanOrEqualTo(3);

        String second = draft(FIRST_HOSPITAL_INPUT, DemoSeed.CARDIOLOGY, DemoSeed.checkupDay());
        plan("department.list", "{}");
        AgentTurnResponse departments = service.chat(second, "这家医院有哪些科室？");
        assertThat(departments.quickReplies()).as("科室候选最多三个").hasSizeLessThanOrEqualTo(3);

        // 推荐轮：按钮数跟着模型给的清单走，Java 不替它加，也不许超过 3 条
        String third = service.start().conversationId();
        planRecommendationWithEvidence("hospital.list", "{}", "{}",
                "两家都有心内科，可以都看看。",
                rec("h001", "心内科可以复诊", "hospital.list"),
                rec("h002", "心内科可以复诊", "hospital.list"));
        AgentTurnResponse recommended = service.chat(third, "给我推荐几个可以预约的选择");
        assertThat(recommended.quickReplies()).as("推荐候选最多三个").hasSizeLessThanOrEqualTo(3);

        assertThat(systemPrompt.planning(new AgentContext("ASK_HOSPITAL", "", clock.today(), List.of()), "[]"))
                .as("条数这条规则同时写在提示词里，模型层的 2—3 条由它兜")
                .contains("一次只给 2—3 个选择");
    }

    /** 目录里没有的医院：说没找到，绝不拿别的医院顶上。 */
    @Test void anUnknownHospitalIsReportedAsNotFoundInsteadOfSubstituted() {
        String id = service.start().conversationId();
        plan("appointment.querySlots",
                "{\"hospital\":\"" + UNKNOWN_HOSPITAL + "\",\"department\":\"" + CARDIOLOGY
                        + "\",\"date\":\"" + UPCOMING + "\"}");

        service.chat(id, "帮我看看这家有没有号");

        String evidence = gateway.lastToolPhasePrompt;
        assertThat(evidence).contains("我没在可办理的医院里找到").contains(UNKNOWN_HOSPITAL);
        assertThat(evidence).as("不能拿目录里的别的医院代替它去查")
                .doesNotContain(FIRST_HOSPITAL).doesNotContain(PEOPLE_HOSPITAL);
        assertThat(traceCount(id, "appointment.querySlots"))
                .as("都没找到这家医院，就不该走到号源查询上").isZero();
    }

    // ---------------------------------------------------------------- 只读边界

    /**
     * 一轮推荐里的三次只读查询，一样都不许落到草稿上。
     *
     * <p>三个工具横跨三条不同的编排路径（历史、日程冲突、路线），只要其中任何一条顺手写了
     * {@code stage}、{@code conflicts}、{@code alternatives} 或 {@code travelPlan}，
     * 工具循环收口时的 {@code finalizeToolEvidence} 就会把它存下去——所以这里一次性验三条。
     */
    @Test void aRecommendationRoundDoesNotTouchTheDraft() {
        seedAppointment("rec-old", "user-001", FIRST_HOSPITAL, CARDIOLOGY, PAST, DemoSeed.MORNING, "CONFIRMED");
        String id = draft(FIRST_HOSPITAL_INPUT, DemoSeed.CARDIOLOGY, DemoSeed.checkupDay());
        String hospitalBefore = liveState(id).hospital;
        String departmentBefore = liveState(id).department;
        LocalDate dateBefore = liveState(id).date;
        Object slotBefore = liveState(id).selectedSlot;
        Object alternativesBefore = liveState(id).alternatives;
        String stageBefore = liveState(id).stage.name();

        plan("appointment.history", "{}");
        gateway.continuations.add(toolOnly("schedule.checkConflict",
                "{\"date\":\"" + UPCOMING + "\",\"time\":\"10:30\"}"));
        gateway.continuations.add(toolOnly("travel.routePlan", """
                {"hospital":"%s","date":"%s","time":"09:00","transport":"打车"}
                """.formatted(FIRST_HOSPITAL, UPCOMING)));

        AgentTurnResponse result = service.chat(id, "根据我以前的情况推荐几个可以预约的选择");

        assertThat(traceCount(id, "appointment.history")).as("三次查询真的都跑了").isEqualTo(1);
        assertThat(traceCount(id, "schedule.checkConflict")).isEqualTo(1);
        assertThat(traceCount(id, "travel.routePlan")).isEqualTo(1);
        assertThat(liveState(id).hospital).isEqualTo(hospitalBefore);
        assertThat(liveState(id).department).isEqualTo(departmentBefore);
        assertThat(liveState(id).date).isEqualTo(dateBefore);
        assertThat(liveState(id).selectedSlot).as("不替老人选时段").isEqualTo(slotBefore);
        assertThat(liveState(id).alternatives).as("不碰候选号源").isEqualTo(alternativesBefore);
        assertThat(liveState(id).conflicts).as("不写冲突").isEmpty();
        assertThat(liveState(id).travelPlan).as("不写出行计划").isNull();
        assertThat(liveState(id).stage.name()).as("不推进任务阶段").isEqualTo(stageBefore);
        assertThat(result.confirmation()).as("推荐不建卡").isNull();
        assertThat(result.uiDirective()).as("推荐不跳页面").isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE status='CONFIRMED'", Integer.class))
                .as("只读推荐不产生任何预约").isEqualTo(1);
    }

    /**
     * 模型发起的推荐轮里说出的科室，也不写进草稿。
     *
     * <p>这条专门盯 {@code recommendHospitals}：它自己会按科室列医院，最容易顺手把「这次看的
     * 是心内科」记成草稿条件——那样推荐就从「建议」变成了「替老人定了科室」。医院的候选照给，
     * 但草稿一个字不动；老人点了「选择市第一医院」之后才由那一次的业务动作写进去。
     */
    @Test void aModelDrivenRecommendationRoundDoesNotRecordTheDepartment() {
        String id = service.start().conversationId();
        String stageBefore = liveState(id).stage.name();
        planRecommendationWithEvidence("hospital.list", "{}", """
                {"department":"%s"}
                """.formatted(CARDIOLOGY),
                "心内科的话，这两家都可以看看。",
                rec("h001", "心内科可以复诊", "hospital.list"),
                rec("h002", "心内科可以复诊", "hospital.list"));

        AgentTurnResponse recommended = service.chat(id, "推荐一下心内科的医院");

        assertThat(recommended.quickReplies()).as("该给的候选照给").isNotEmpty();
        assertThat(liveState(id).department).as("推荐不替老人定科室").isNull();
        assertThat(liveState(id).departmentId).as("目录 ID 也不写").isNull();
        assertThat(liveState(id).hospital).as("医院更不该写").isNull();
        assertThat(liveState(id).stage.name()).as("不推进阶段").isEqualTo(stageBefore);
    }

    /** 老人手上已经有一张待确认的卡时，推荐查询要把它原样带回来。 */
    @Test void aRecommendationRoundKeepsThePendingCardIntact() {
        AgentTurnResponse prepared = useExistingCard();
        String id = prepared.conversationId();
        // 不带参数：条件回草稿取（已选 09:00），这正是最容易顺手写 stage 的那条路。
        plan("schedule.checkConflict", "{}");

        AgentTurnResponse result = service.chat(id, "这个时间和我日程冲突吗？");

        // 手上已经有待确认卡时，只读循环会在拿回卡的那一步直接收口，不再叫模型，
        // 所以证据段落是空的——结论只能从 Java 自己写的回复上验。
        assertThat(result.reply()).as("09:00 与体检不重叠").contains("不冲突");
        assertThat(result.confirmation()).as("老人手上的卡还在").isNotNull();
        assertThat(result.confirmation().confirmationId())
                .isEqualTo(prepared.confirmation().confirmationId());
        assertThat(result.confirmation().operations()).isEqualTo(prepared.confirmation().operations());
        assertThat(result.stage()).as("还在等确认，没有被这次查询消费掉").isEqualTo("AWAITING_CONFIRMATION");
        assertThat(liveState(id).confirmationId).isEqualTo(prepared.confirmation().confirmationId());
        assertThat(liveState(id).conflicts).as("只读查询不往卡上挂冲突").isEmpty();
    }

    /** 老人把推荐都否掉：不写草稿，也不偷偷记一笔历史选择。 */
    @Test void refusingEveryRecommendationWritesNothing() {
        seedAppointment("rec-old", "user-001", FIRST_HOSPITAL, CARDIOLOGY, PAST, DemoSeed.MORNING, "CONFIRMED");
        String id = service.start().conversationId();
        plan("appointment.history", "{}");
        gateway.fallback = """
                {"actionType":"ANSWER","intent":"UNKNOWN","toolName":null,"arguments":{},
                 "replyDraft":"好，那这次想去哪家医院、看哪个科？您说了我再帮您查。",
                 "dialogueMode":"FOLLOWUP_FLOW","facts":{}}
                """;

        service.chat(id, "根据我以前的情况推荐一下");
        assertThat(liveState(id).hospital).as("第一轮只查历史，草稿仍然是空的").isNull();

        resetGateway();
        gateway.planning = """
                {"actionType":"ANSWER","intent":"UNKNOWN","toolName":null,"arguments":{},
                 "replyDraft":"好，那您说一家医院，我帮您看有没有号。",
                 "dialogueMode":"FOLLOWUP_FLOW","facts":{}}
                """;
        service.chat(id, "都不喜欢");

        assertThat(liveState(id).hospital).as("拒绝之后不强行把历史写进草稿").isNull();
        assertThat(liveState(id).date).isNull();
        assertThat(liveState(id).selectedSlot).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE user_id='user-001'", Integer.class))
                .as("也只读那一轮没有偷偷替老人约上").isEqualTo(1);
    }

    /** 老人下一轮明确选中某个推荐：走既有预约业务动作改草稿，不由只读查询代劳。 */
    @Test void theNextTurnExplicitChoiceGoesThroughTheNormalBookingFlow() {
        seedAppointment("rec-old", "user-001", FIRST_HOSPITAL, CARDIOLOGY, PAST, DemoSeed.MORNING, "CONFIRMED");
        String id = service.start().conversationId();
        plan("appointment.history", "{}");
        service.chat(id, "根据我以前的情况推荐一下");
        assertThat(liveState(id).hospital).as("推荐那一轮不写草稿").isNull();

        gateway.planning = """
                {"actionType":"PROPOSE_WORKFLOW_ACTION","intent":"CHANGE_HOSPITAL","toolName":null,
                 "arguments":{},"toolCalls":[],"replyDraft":"好的，这次还去市第一医院。",
                 "dialogueMode":"FOLLOWUP_FLOW","facts":{"hospital":"%s"}}
                """.formatted(FIRST_HOSPITAL);
        service.chat(id, "就那家吧");

        assertThat(liveState(id).hospitalId).as("草稿由正常业务动作写，不由只读查询写")
                .isEqualTo("h001");
        assertThat(liveState(id).hospital).contains(FIRST_HOSPITAL_INPUT);
    }

    // ---------------------------------------------------------------- 权限与循环

    /** 家属只能为已绑定的长辈读推荐依据。 */
    @Test void aCaregiverReadsRecommendationEvidenceOnlyForBoundElders() {
        seedAppointment("rec-1", "user-001", FIRST_HOSPITAL, CARDIOLOGY, UPCOMING, DemoSeed.MORNING, "CONFIRMED");
        String id = service.start("user-001", "user-f001").conversationId();
        plan("appointment.history", "{}");

        service.chat(id, "帮我妈推荐一下以前去过的那家");

        assertThat(gateway.lastToolPhasePrompt).as("读到的是被服务长辈的记录").contains(FIRST_HOSPITAL);
        assertThat(traceRequest(id, "appointment.history"))
                .as("服务对象由 Java 注入，模型改不了").contains("\"userId\":\"user-001\"");
    }

    /** 未授权的服务对象：不给任何推荐依据，也不回答「库里有没有这个人」。 */
    @Test void anUnauthorizedSubjectYieldsNoRecommendationOrSummary() {
        seedAppointment("rec-theirs", "user-002", PEOPLE_HOSPITAL, CARDIOLOGY,
                UPCOMING, DemoSeed.MORNING, "CONFIRMED");
        ConversationState state = injectedSession("user-002", "user-f001", AgentRole.FAMILY);
        String stageBefore = state.stage.name();
        plan("profile.memorySummary", "{}");

        service.chat(state.id, "给这位老人推荐几家医院");

        assertThat(gateway.lastToolPhasePrompt).contains("没有权限查看这位就诊人的信息");
        assertThat(gateway.lastToolPhasePrompt)
                .as("越权时连一家医院都不摆出来").doesNotContain(PEOPLE_HOSPITAL).doesNotContain(CARDIOLOGY);
        assertThat(traceResponse(state.id, "profile.memorySummary"))
                .as("被拒绝的查询不落留痕").isNull();
        assertThat(liveState(state.id).stage.name()).as("拒绝不动任务阶段").isEqualTo(stageBefore);
    }

    /** 模型重复点同一个工具同一份参数：不重复执行，也不无限循环。 */
    @Test void duplicateRecommendationToolCallsDoNotLoopForever() {
        seedAppointment("rec-1", "user-001", FIRST_HOSPITAL, CARDIOLOGY, UPCOMING, DemoSeed.MORNING, "CONFIRMED");
        String id = service.start().conversationId();
        plan("appointment.history", "{}");
        gateway.continuations.add(toolOnly("appointment.history", "{}"));
        gateway.continuations.add(toolOnly("appointment.history", "{}"));

        AgentTurnResponse result = service.chat(id, "根据我以前的情况推荐一下");

        assertThat(traceCount(id, "appointment.history")).as("同名同参数只执行一次").isEqualTo(1);
        assertThat(result.reply()).as("停下来了，并告诉老人怎么接着走")
                .contains("我已经完成了这项查询");
    }

    /** 工具失败时，失败就是失败：不编推荐，也不拿另一种交通方式的用时顶上。 */
    @Test void aFailedToolIsReportedAsAFailureInsteadOfSmoothedOver() {
        String id = service.start().conversationId();
        plan("travel.routePlan", """
                {"hospital":"%s","date":"%s","time":"09:00","transport":"%s"}
                """.formatted(FIRST_HOSPITAL, UPCOMING, NO_ROUTE_TRANSPORT));

        service.chat(id, "这家医院远不远？");

        Integer failed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tool_call_logs
                WHERE conversation_id=? AND tool_name='travel.routePlan' AND success=FALSE
                """, Integer.class, id);
        assertThat(failed).as("失败照实落痕，不留一条看起来成功的记录").isEqualTo(1);
        String evidence = gateway.lastToolPhasePrompt;
        assertThat(evidence).contains("没法给出预计用时");
        assertThat(evidence).as("不许悄悄换成打车或公交的用时顶上")
                .doesNotContain("预计约35分钟").doesNotContain("预计约30分钟").doesNotContain("预计约50分钟");
        assertThat(evidence).as("失败要给出可恢复的下一步").contains("换一种交通方式");
    }

    // ---------------------------------------------------------------- 提示词的红线

    /**
     * 不评价医疗水平、不按症状替老人选科——这两条只能靠提示词。
     *
     * <p>Java 看不住模型自由发挥的措辞，所以这里钉的是「规则确实交给模型了」。
     * 模型会不会照做，自动测试证明不了。
     */
    @Test void theModelIsToldNotToRankMedicalQualityOrPickADepartmentBySymptom() {
        String system = systemPrompt.planning(
                new AgentContext("ASK_HOSPITAL", "", clock.today(), List.of()), "[]");
        assertThat(system).contains("不评价哪家医院更好")
                .contains("不按症状替老人判断该挂哪个科")
                .contains("只能说“预约过”").contains("不能说“去过”");
    }

    /** 模拟医院与模拟路线的说法要保留，别把模拟数据说成真实情况。 */
    @Test void simulatedHospitalsAndRoutesKeepTheirSimulatedLabel() {
        String id = service.start().conversationId();
        plan("hospital.list", "{}");
        service.chat(id, "有哪些医院？");
        // 目录读出来的医院名里「（模拟）」已经被去掉了（CareCatalogRepository.stripSimulated），
        // 所以这个标识只能由 Java 自己的措辞兜住：说清楚这是模拟资料，而不是真实医院名单。
        assertThat(gateway.lastToolPhasePrompt)
                .as("摆医院候选时要说明这是模拟资料").contains("模拟医院资料");

        resetGateway();
        plan("travel.routePlan", """
                {"hospital":"%s","date":"%s","time":"09:00","transport":"打车"}
                """.formatted(FIRST_HOSPITAL, UPCOMING));
        service.chat(id, "去这家打车要多久？");
        assertThat(gateway.lastToolPhasePrompt).contains("模拟路线");
        assertThat(systemPrompt.planning(new AgentContext("ASK_HOSPITAL", "", clock.today(), List.of()), "[]"))
                .as("这条规则同时写在提示词里，模型转述时也带着它")
                .contains("模拟医院和模拟路线继续保留“模拟”的说法");
    }

    // ---------------------------------------------------------------- 路线：两种模式的分界

    /**
     * APPOINTMENT：看自己已确认的那一次预约的路线，照旧打开地图。
     *
     * <p>候选估算和它是同一条工具，分界线是结构化的 {@code mode}，不是「带了哪些参数」——
     * 两种模式都可能带交通方式，靠参数形状猜早晚会猜错。
     */
    @Test void anAppointmentRouteOpensTheMapForMyOwnAppointment() {
        seedAppointment("rec-1", "user-001", FIRST_HOSPITAL, CARDIOLOGY, UPCOMING, DemoSeed.MORNING, "CONFIRMED");
        String id = service.start().conversationId();
        plan("travel.routePlan", """
                {"mode":"APPOINTMENT","appointmentId":"rec-1"}
                """);

        AgentTurnResponse result = service.chat(id, "帮我看看那条预约怎么走");

        assertThat(gateway.lastToolPhasePrompt).as("真的按这条预约查了路线")
                .contains("我为您打开").contains("预计约");
        assertThat(result.uiDirective()).as("APPOINTMENT 模式才给页面跳转指令").isNotNull();
        assertThat(traceCount(id, "travel.routePlan")).isEqualTo(1);
    }

    /** CANDIDATE：候选条件的估算，只回预估，不打开地图、不写草稿。 */
    @Test void aCandidateEstimateWithAnExplicitModeNeverOpensTheMap() {
        String id = service.start().conversationId();
        plan("travel.routePlan", """
                {"mode":"CANDIDATE","hospital":"%s","date":"%s","time":"09:00","transport":"打车"}
                """.formatted(FIRST_HOSPITAL, UPCOMING));

        AgentTurnResponse result = service.chat(id, "去这家打车要多久？");

        assertThat(gateway.lastToolPhasePrompt).as("估算照给，模拟标识照说")
                .contains("预计约").contains("模拟路线").contains("这一轮只是在帮您比较");
        assertThat(result.uiDirective()).as("候选估算不打开地图").isNull();
        assertThat(liveState(id).transport).as("不改草稿里的交通方式").isNull();
        assertThat(liveState(id).travelPlan).as("不写草稿里的出行计划").isNull();
    }

    /**
     * 只给交通方式也算候选估算：医院、日期、时刻回退到草稿里已经明确的条件。
     *
     * <p>这条专门盯「transport-only」——它是两种模式唯一重叠的参数，如果还按「带了哪些参数」
     * 分模式，它一定会把这一次带进导航那条路。
     */
    @Test void candidateArgumentsMayBeJustTheTransportAndFallBackToTheDraft() {
        String id = loadedDraft();
        plan("travel.routePlan", """
                {"mode":"CANDIDATE","transport":"公交"}
                """);

        AgentTurnResponse result = service.chat(id, "坐公交去要多久？");

        assertThat(gateway.lastToolPhasePrompt).as("医院和日期回退到草稿，交通方式用这一轮说的")
                .contains("从您登记的住址到").contains("公交").contains("预计约");
        assertThat(result.uiDirective()).as("仍然是候选估算，不把老人带走").isNull();
        assertThat(liveState(id).transport).as("这一轮的交通方式不落草稿").isNotEqualTo("公交");
    }

    /** 一个参数都没给的老调用（老人点了「查看地图」）仍然走既有导航流程。 */
    @Test void aLegacyCallWithoutAnyArgumentKeepsTheNavigationFlow() {
        seedAppointment("rec-1", "user-001", FIRST_HOSPITAL, CARDIOLOGY, UPCOMING, DemoSeed.MORNING, "CONFIRMED");
        String id = service.start().conversationId();
        plan("travel.routePlan", "{}");

        AgentTurnResponse result = service.chat(id, "帮我看看地图");

        assertThat(gateway.lastToolPhasePrompt).as("照旧按已确认预约打开地图").contains("我为您打开");
        assertThat(result.uiDirective()).as("老路径的地图指令不能丢").isNotNull();
        assertThat(traceCount(id, "travel.routePlan")).isEqualTo(1);
    }

    /**
     * 预约编号和候选条件混着给：分不清是哪一种，就说分不清，绝不替模型挑一种。
     *
     * <p>问的这句话必须让人知道该往哪边补条件——「看已有预约的路线」和「估算候选的路线」
     * 是两件不同的事，只说「请告诉我日期和时间」等于把这一轮问错了方向。
     */
    @Test void mixingAnAppointmentIdWithCandidateArgumentsIsClarifiedInsteadOfGuessed() {
        seedAppointment("rec-1", "user-001", FIRST_HOSPITAL, CARDIOLOGY, UPCOMING, DemoSeed.MORNING, "CONFIRMED");
        String id = service.start().conversationId();
        plan("travel.routePlan", """
                {"mode":"APPOINTMENT","appointmentId":"rec-1","hospital":"%s"}
                """.formatted(PEOPLE_HOSPITAL_INPUT));

        AgentTurnResponse result = service.chat(id, "那条预约去市人民医院要多久？");

        assertThat(gateway.lastToolPhasePrompt).as("混着给就明说没分清，并请他说清是哪一种")
                .contains("没分清").contains("看已有预约的路线").contains("估算候选的路线");
        assertThat(gateway.lastToolPhasePrompt).as("不替模型挑一种，也不要问错方向")
                .doesNotContain("请告诉我要去的医院或者时间")
                .doesNotContain("请从您的预约里选一条");
        assertThat(gateway.lastToolPhasePrompt).as("混着给时不摆任何一次路线结果")
                .doesNotContain("我为您打开").doesNotContain("预计约");
        assertThat(result.uiDirective()).as("没分清就不打开地图").isNull();
        assertThat(traceCount(id, "travel.routePlan")).as("一次路线查询都没执行").isZero();
    }

    /**
     * transport 只属于 CANDIDATE：APPOINTMENT 用预约和用户资料里存着的交通方式，
     * 不接受这次传的 transport 覆盖。所以「APPOINTMENT + appointmentId + transport」
     * <b>不是混用</b>，不该被拦下来问「您到底想看哪一种」。
     */
    @Test void anAppointmentRouteIgnoresTheTransportPassedInTheSameCall() {
        // 预约里的交通方式是「家属开车」；这一轮模型多带了一个「公交」，不能被它带偏。
        jdbc.update("""
                INSERT INTO appointment_slots(id,hospital_id,hospital_name,department,appointment_date,appointment_time,available)
                VALUES ('rec-slot-rec-1','h001',?,?,?,?,TRUE)
                """, FIRST_HOSPITAL, CARDIOLOGY, Date.valueOf(UPCOMING), Time.valueOf(DemoSeed.MORNING));
        jdbc.update("""
                INSERT INTO appointments(id,slot_id,user_id,status,created_at)
                VALUES ('rec-1','rec-slot-rec-1','user-001','CONFIRMED',?)
                """, Timestamp.valueOf(LocalDateTime.now()));
        String id = service.start().conversationId();
        plan("travel.routePlan", """
                {"mode":"APPOINTMENT","appointmentId":"rec-1","transport":"公交"}
                """);

        AgentTurnResponse result = service.chat(id, "帮我看看那条预约怎么走，我想坐公交");

        assertThat(gateway.lastToolPhasePrompt).as("照旧打开这一条预约的地图")
                .contains("我为您打开").contains("家属开车");
        assertThat(gateway.lastToolPhasePrompt).as("多带一个 transport 不算混用，不该被拦下来问")
                .doesNotContain("没分清");
        assertThat(result.uiDirective()).as("APPOINTMENT 的页面跳转照旧").isNotNull();
        assertThat(traceCount(id, "travel.routePlan")).isEqualTo(1);
    }

    /** mode 说是看预约，却没给 appointmentId：缺的是「哪一条」，就问哪一条，不问日期和时间。 */
    @Test void anAppointmentModeWithoutAnAppointmentIdAsksWhichBooking() {
        String id = service.start().conversationId();
        plan("travel.routePlan", """
                {"mode":"APPOINTMENT"}
                """);

        AgentTurnResponse result = service.chat(id, "帮我看看那条预约怎么走");

        assertThat(gateway.lastToolPhasePrompt).as("问的是「哪一条预约」")
                .contains("没告诉我是哪一次").contains("请从您的预约里选一条");
        assertThat(gateway.lastToolPhasePrompt).as("缺的不是日期和时间")
                .doesNotContain("请告诉我要去的医院或者时间");
        assertThat(result.uiDirective()).isNull();
        assertThat(traceCount(id, "travel.routePlan")).isZero();
    }

    /** mode 说是估算，却什么条件都没给：缺的是医院或时间，就问这两样，不问「哪一条预约」。 */
    @Test void aCandidateModeWithoutAnyConditionAsksForTheHospitalOrTime() {
        String id = service.start().conversationId();
        plan("travel.routePlan", """
                {"mode":"CANDIDATE"}
                """);

        AgentTurnResponse result = service.chat(id, "去一趟要多久？");

        assertThat(gateway.lastToolPhasePrompt).as("问的是「去哪家医院、哪一天几点」")
                .contains("我还需要知道去哪家医院").contains("请告诉我要去的医院或者时间");
        assertThat(gateway.lastToolPhasePrompt).as("缺的不是哪一条预约")
                .doesNotContain("请从您的预约里选一条");
        assertThat(result.uiDirective()).isNull();
        assertThat(traceCount(id, "travel.routePlan")).isZero();
    }

    /**
     * transport 的归属在工具声明和提示词里都写死成 CANDIDATE 专有。
     *
     * <p>参数契约是模型唯一看得到的那份说明；只把 Java 改对、说明里仍写成「两种模式都可能带」，
     * 模型就会照着说明给 APPOINTMENT 带 transport，而它其实会被忽略。
     */
    @Test void theToolContractAndThePromptBothSayTransportIsForCandidatesOnly() {
        String description = registry.find("travel.routePlan").orElseThrow().definition().description();
        assertThat(description).as("工具说明里写清 transport 只属于 CANDIDATE")
                .contains("不接受 transport 覆盖").contains("transport 只在 CANDIDATE 里有意义");

        String system = systemPrompt.planning(
                new AgentContext("ASK_HOSPITAL", "", clock.today(), List.of()), "[]");
        assertThat(system).as("提示词里同样写清，两条路不能混着用")
                .contains("transport 只在 mode=CANDIDATE 里有意义")
                .contains("不要给 APPOINTMENT 带它");
    }

    /**
     * 不是自己的预约编号，一个字的路线也拿不到，页面也不会被带走。
     *
     * <p>归属由 {@code TravelGuideService.forAppointment} 的 SQL 把关；
     * 「不存在」和「不是您的」回同一句话，不留一处可比较的差异给越权调用去探测。
     */
    @Test void someoneElsesAppointmentIdNeverOpensTheMap() {
        seedAppointment("rec-other", "user-002", FIRST_HOSPITAL, CARDIOLOGY, UPCOMING, DemoSeed.MORNING, "CONFIRMED");
        String id = service.start().conversationId();
        plan("travel.routePlan", """
                {"mode":"APPOINTMENT","appointmentId":"rec-other"}
                """);
        AgentTurnResponse someoneElses = service.chat(id, "看看这条预约怎么走");

        resetGateway();
        plan("travel.routePlan", """
                {"mode":"APPOINTMENT","appointmentId":"rec-does-not-exist"}
                """);
        AgentTurnResponse invented = service.chat(id, "再看看这条");

        assertThat(gateway.lastToolPhasePrompt).as("查不到和不是自己的，回的是同一句话")
                .contains("没有找到这条复诊预约");
        assertThat(someoneElses.reply()).as("越权不给路线").isEqualTo(invented.reply());
        assertThat(someoneElses.uiDirective()).as("模型给的一个编号不能把页面带走").isNull();
        assertThat(invented.uiDirective()).isNull();
    }

    // ---------------------------------------------------------------- 当前明确排除

    /**
     * 老人这一轮明确排除的医院，Java 在生成推荐文字和快捷按钮 <b>之前</b> 就把它去掉。
     *
     * <p>排除的目标由模型结构化给出（{@code facts.excludedHospitals}），Java 去真实目录里比对——
     * Java 这边没有、也不新增任何中文关键词或同义词表：老人这句话里根本没提医院名。
     */
    @Test void anExcludedHospitalIsFilteredFromTheTextAndTheButtons() {
        String id = service.start().conversationId();
        planRecommendationWithEvidence("hospital.list", "{}", """
                {"department":"%s","excludedHospitals":["%s"]}
                """.formatted(CARDIOLOGY, FIRST_HOSPITAL_INPUT),
                "按您说的心内科，我建议去市人民医院，它的心内科覆盖高血压和冠心病。",
                rec("h002", "心内科覆盖高血压和冠心病", "hospital.list"));

        AgentTurnResponse result = service.chat(id, "推荐一下心内科的医院，有一家我这次不去。");

        assertThat(gateway.lastToolPhasePrompt).as("模型自己点的目录工具真的跑过了")
                .contains("\"name\":\"hospital.list\"");
        assertThat(result.quickReplies()).as("按钮里只剩没被排除的那一家")
                .hasSize(1).allSatisfy(reply -> assertThat(reply.value()).isEqualTo("h002"));
        assertThat(result.reply()).as("推荐文字里也不能出现被排除的医院")
                .contains(PEOPLE_HOSPITAL_INPUT).doesNotContain(FIRST_HOSPITAL_INPUT);
    }

    /** 排除多家之后一家都不剩：如实说没有别家了，请他换条件，绝不把排除的悄悄放回来。 */
    @Test void excludingEveryCandidateAsksForNewConditionsInsteadOfRestoringThem() {
        String id = service.start().conversationId();
        planRecommendation("""
                {"department":"%s","excludedHospitals":["%s","%s"]}
                """.formatted(CARDIOLOGY, FIRST_HOSPITAL_INPUT, PEOPLE_HOSPITAL_INPUT));

        AgentTurnResponse result = service.chat(id, "推荐一下心内科的医院，这两家我都不想去。");

        assertThat(result.reply()).as("如实说明没有别家了，并给出可恢复的下一步")
                .contains("没有别家了").contains("换一个科室");
        assertThat(result.reply()).as("不能悄悄恢复成一句正常的推荐")
                .doesNotContain("我找到了以下选择")
                .doesNotContain(FIRST_HOSPITAL_INPUT).doesNotContain(PEOPLE_HOSPITAL_INPUT);
        assertThat(result.quickReplies()).as("被排除的医院不能从按钮里回来")
                .noneMatch(reply -> "SET_HOSPITAL".equals(reply.action()));
    }

    /** 排除的目标对不上目录：说清楚没认出来，这一轮不给推荐，不拿别的医院替它。 */
    @Test void anExclusionMatchingNoHospitalIsSaidOutLoudInsteadOfGuessed() {
        String id = service.start().conversationId();
        planRecommendation("""
                {"department":"%s","excludedHospitals":["%s"]}
                """.formatted(CARDIOLOGY, UNKNOWN_HOSPITAL));

        AgentTurnResponse result = service.chat(id, "推荐一下心内科的医院，有一家我不想考虑。");

        assertThat(result.reply()).as("指名说没找到这一家，并请老人说全名")
                .contains("没找到这一家").contains("请说完整的医院名称");
        assertThat(result.reply()).as("认不准就不给推荐").doesNotContain("我找到了以下选择");
        assertThat(result.quickReplies()).as("认不准时一个医院按钮都不给")
                .noneMatch(reply -> "SET_HOSPITAL".equals(reply.action()));
        assertThat(liveState(id).hospital).as("照旧不写草稿").isNull();
    }

    /** 唯一近似（老人说「市一」）：仍然排除，但必须把理解出来的结果说出来，他才有机会纠正。 */
    @Test void anApproximateExclusionIsSaidOutLoudBeforeItIsApplied() {
        String id = service.start().conversationId();
        String answering = "心内科剩下的这家市人民医院可以看看，它的心内科覆盖高血压和冠心病。";
        planRecommendationWithEvidence("hospital.list", "{}", """
                {"department":"%s","excludedHospitals":["市一"]}
                """.formatted(CARDIOLOGY),
                answering, rec("h002", "心内科覆盖高血压和冠心病", "hospital.list"));

        AgentTurnResponse result = service.chat(id, "推荐一下心内科的医院，市一我这次不去。");

        assertThat(result.reply()).as("把「市一」理解成了哪一家，要说出来")
                .contains("我理解成").contains(FIRST_HOSPITAL_INPUT).contains("已经不算在候选里");
        assertThat(result.reply()).as("Java 的说明接在模型那句话前面，一个字都不改它")
                .endsWith(answering);
        assertThat(result.quickReplies()).as("近似理解之后照样过滤掉它")
                .allSatisfy(reply -> assertThat(reply.value()).isEqualTo("h002"));
    }

    /** 排除只管这一轮：不是偏好、不落草稿，老人下一轮不提了，两家医院照常都在。 */
    @Test void anExclusionIsNotRememberedAsALongTermPreference() {
        String id = service.start().conversationId();
        planRecommendationWithEvidence("hospital.list", "{}", """
                {"department":"%s","excludedHospitals":["%s"]}
                """.formatted(CARDIOLOGY, FIRST_HOSPITAL_INPUT),
                "这次先看市人民医院，它的心内科可以复诊。",
                rec("h002", "心内科可以复诊", "hospital.list"));
        AgentTurnResponse excluded = service.chat(id, "推荐一下心内科的医院，有一家我这次不去。");
        assertThat(excluded.quickReplies()).hasSize(1);
        assertThat(liveState(id).hospital).as("排除不写进草稿").isNull();

        resetGateway();
        planRecommendationWithEvidence("hospital.list", "{}", """
                {"department":"%s"}
                """.formatted(CARDIOLOGY),
                "两家都可以看看：市第一医院和市人民医院都有心内科。",
                rec("h001", "心内科可以复诊", "hospital.list"),
                rec("h002", "心内科可以复诊", "hospital.list"));
        AgentTurnResponse again = service.chat(id, "那你重新推荐一下心内科的医院吧。");

        assertThat(again.reply()).as("下一轮不排除，两家都该回来")
                .contains(FIRST_HOSPITAL_INPUT).contains(PEOPLE_HOSPITAL_INPUT);
        assertThat(again.quickReplies()).hasSize(2);
    }

    /**
     * 先查历史、再给推荐：排除目标要活着穿过工具循环。
     *
     * <p>这是「当前明确排除」最容易漏掉的一条路。老人说「推荐心内科，别给我市一」之后，模型通常
     * 先点 {@code appointment.history}，拿到结果之后<b>下一轮才说推荐</b>——而那一轮的 facts 里
     * 它未必再把排除项写一遍。排除如果只看收口那一轮，就会在续跑轮里失效，被排除的医院从推荐里回来。
     *
     * <p>这条用例真的把历史工具跑了一遍（{@code traceCount == 1}），不是靠「没查历史所以没回来」
     * 混过去的：历史里最常去的那家正好就是被排除的那家，照样不能借历史回到候选。
     */
    @Test void anExcludedHospitalStaysExcludedAfterTheHistoryToolActuallyRuns() {
        seedAppointment("rec-old", "user-001", FIRST_HOSPITAL, CARDIOLOGY, PAST, DemoSeed.MORNING, "CONFIRMED");
        String id = service.start().conversationId();
        planWithFacts("appointment.history", "{}", """
                {"department":"%s","excludedHospitals":["%s"]}
                """.formatted(CARDIOLOGY, FIRST_HOSPITAL_INPUT));
        // 续跑这一轮模型一个字都没提排除项——它只是看到历史之后开始给推荐。
        gateway.continuations.add(recommendationAnswer(
                "这次看市人民医院吧，它的心内科可以复诊。",
                rec("h002", "心内科可以复诊", "appointment.history")));

        AgentTurnResponse result = service.chat(id, "根据我以前的情况推荐几个心内科的选择，有一家我这次不去。");

        assertThat(traceCount(id, "appointment.history")).as("历史工具真的跑了一遍").isEqualTo(1);
        assertThat(result.quickReplies()).as("被排除的医院不能借历史回到候选")
                .hasSize(1).allSatisfy(reply -> assertThat(reply.value()).isEqualTo("h002"));
        assertThat(result.reply()).as("连推荐文字里也不能有它")
                .doesNotContain(FIRST_HOSPITAL_INPUT);
        assertThat(liveState(id).hospital).as("这条只读路径照旧不写草稿").isNull();
        assertThat(liveState(id).hospitalId).isNull();

        // 排除只在这一次用户消息里有效：下一条消息不再提，两家医院就该回来。
        resetGateway();
        planRecommendationWithEvidence("hospital.list", "{}", """
                {"department":"%s"}
                """.formatted(CARDIOLOGY),
                "市第一医院和市人民医院都有心内科，可以都看看。",
                rec("h001", "心内科可以复诊", "hospital.list"),
                rec("h002", "心内科可以复诊", "hospital.list"));
        AgentTurnResponse again = service.chat(id, "那你重新推荐一下心内科的医院吧。");

        assertThat(again.quickReplies()).as("下一条消息不再排除，两家都能重新成为候选").hasSize(2);
        assertThat(again.reply()).contains(PEOPLE_HOSPITAL_INPUT);
    }

    /**
     * 多工具之后照样过滤：历史 + 画像两条都查完，排除目标还在。
     *
     * <p>约束是跟着整个循环走的，不是只跟着第一轮或最后一轮——中间多绕几圈也不该把它绕丢。
     */
    @Test void theExclusionSurvivesHistoryAndProfileBeforeTheRecommendation() {
        seedAppointment("rec-old", "user-001", FIRST_HOSPITAL, CARDIOLOGY, PAST, DemoSeed.MORNING, "CONFIRMED");
        String id = service.start().conversationId();
        planWithFacts("appointment.history", "{}", """
                {"department":"%s","excludedHospitals":["%s"]}
                """.formatted(CARDIOLOGY, FIRST_HOSPITAL_INPUT));
        gateway.continuations.add(toolOnly("profile.memorySummary", "{}"));
        // 最后这一轮同样没有再写 excludedHospitals。
        gateway.continuations.add(recommendationAnswer(
                "结合您的预约情况和偏好，这次看市人民医院吧。",
                rec("h002", "心内科可以复诊", "appointment.history", "profile.memorySummary")));

        AgentTurnResponse result = service.chat(id, "综合我以前的情况和您记着的偏好，推荐几个心内科的选择，市一这次不去。");

        assertThat(traceCount(id, "appointment.history")).as("历史查过了").isEqualTo(1);
        assertThat(traceCount(id, "profile.memorySummary")).as("画像也查过了").isEqualTo(1);
        assertThat(result.quickReplies()).as("两条工具都查完，被排除的那家仍然不在候选里")
                .hasSize(1).allSatisfy(reply -> assertThat(reply.value()).isEqualTo("h002"));
        assertThat(result.reply()).doesNotContain(FIRST_HOSPITAL_INPUT);
    }

    /**
     * 模型推荐的正是老人刚说不要的那家：那句话不给老人看，Java 退回一次让它改。
     *
     * <p>校验是靠<b>结构化清单</b>做的，不是去读模型中文字里点了哪几家医院——中文自由措辞
     * Java 解析不了，「这句话说的是谁」只能由模型自己填成 id 才验得动。
     */
    @Test void aRecommendationNamingAnExcludedOrUnknownHospitalIsNeverShown() {
        String id = service.start().conversationId();
        planWithFacts("hospital.list", "{}", """
                {"department":"%s","excludedHospitals":["%s"]}
                """.formatted(CARDIOLOGY, FIRST_HOSPITAL_INPUT));
        // 第一次：推荐的正是老人刚说不要的那家，answering 里也写着它。
        gateway.continuations.add(recommendationAnswer(
                "我推荐市第一医院，您以前就去这家。",
                rec("h001", "以前预约过", "hospital.list")));
        // 修正之后：换成真实候选里剩下的那家。
        gateway.continuations.add(recommendationAnswer(
                "那就看看市人民医院，它的心内科可以复诊。",
                rec("h002", "心内科可以复诊", "hospital.list")));

        AgentTurnResponse result = service.chat(id, "推荐几个心内科的选择，市一这次不去。");

        assertThat(result.reply()).as("被排除那家的那句话一个字都不给老人看")
                .doesNotContain("我推荐市第一医院").doesNotContain(FIRST_HOSPITAL_INPUT)
                .contains(PEOPLE_HOSPITAL_INPUT);
        assertThat(result.quickReplies()).as("按钮里也只有真实候选里剩下的那家")
                .hasSize(1).allSatisfy(reply -> assertThat(reply.value()).isEqualTo("h002"));
    }

    /**
     * 这一轮里有没有哪一次发给模型的话带着这段标记。
     *
     * <p>不能只看 {@code lastToolPhasePrompt}：那是<b>最后一次</b>续跑的话，修正轮之后模型又去点工具时
     * 会被下一轮的证据覆盖掉。Java 退回去的结构化问题要整轮里找。
     */
    private boolean modelPromptContains(String text) {
        return gateway.messages.stream().anyMatch(message -> message.content().contains(text));
    }

    /** 查完历史才发现候选全被排除了：如实说没有，不把排除过的医院悄悄放回来。 */
    @Test void excludingEveryCandidateAfterTheHistoryQuerySaysSoInsteadOfRestoring() {
        seedAppointment("rec-old", "user-001", FIRST_HOSPITAL, CARDIOLOGY, PAST, DemoSeed.MORNING, "CONFIRMED");
        String id = service.start().conversationId();
        planWithFacts("appointment.history", "{}", """
                {"department":"%s","excludedHospitals":["%s","%s"]}
                """.formatted(CARDIOLOGY, FIRST_HOSPITAL_INPUT, PEOPLE_HOSPITAL_INPUT));
        gateway.continuations.add(recommendationAnswer("那就推荐市第一医院吧。",
                rec("h001", "以前预约过", "appointment.history")));

        AgentTurnResponse result = service.chat(id, "推荐心内科的医院，这两家我都不去。");

        assertThat(traceCount(id, "appointment.history")).as("历史真查了").isEqualTo(1);
        assertThat(result.reply()).as("如实说明没有别家了，并给出可恢复的下一步")
                .contains("没有别家了").contains("换一个科室");
        assertThat(result.reply()).as("不能悄悄恢复成一句正常的推荐")
                .doesNotContain("我找到了以下选择")
                .doesNotContain(FIRST_HOSPITAL_INPUT).doesNotContain(PEOPLE_HOSPITAL_INPUT);
        assertThat(result.quickReplies()).as("被排除的医院不能从按钮里回来")
                .noneMatch(reply -> "SET_HOSPITAL".equals(reply.action()));
    }

    // ---------------------------------------------------------------- 结构化推荐的校验

    /**
     * 校验通过的那一份：{@code answering} 逐字交付，不再经过回答模型。
     *
     * <p>这一条盯的是「不再调用 LlmAnswerGenerator」：回答模型这一轮被脚本设成一句完全不相干的话，
     * 交付出去的必须还是模型原来的 {@code answering}——没有被润色层改写过。
     */
    @Test void aValidatedRecommendationIsReadWordForWordAndNeverPolished() {
        String id = service.start().conversationId();
        String answering = "我建议去市人民医院，它的心内科覆盖高血压和冠心病。";
        gateway.answerReply = polish("润色层写的完全不同的句子。");
        planRecommendationWithEvidence("hospital.list", "{}", "{}",
                answering, rec("h002", "心内科覆盖高血压和冠心病", "hospital.list"));

        AgentTurnResponse result = service.chat(id, "推荐几个可以预约的选择");

        assertThat(gateway.lastToolPhasePrompt).as("模型自己点的目录工具真的跑过了")
                .contains("\"name\":\"hospital.list\"");
        assertThat(result.reply()).as("逐字就是模型那句 answering").isEqualTo(answering);
        assertThat(gateway.lastAnswerPrompt)
                .as("推荐轮压根没走回答模型——没有权威草稿那一层提示词").isEmpty();
        assertThat(result.quickReplies()).hasSize(1)
                .allSatisfy(reply -> assertThat(reply.value()).isEqualTo("h002"));
    }

    /**
     * 一条工具都没查就给推荐：照样走结构化校验，那唯一一次修正会要求它先查真实数据。
     *
     * <p>这是「先查再答」这条链路最容易断的地方：模型完全可以凭印象直接开口。Java 不因为它说得
     * 像模像样就放行——理由没有出处就是没有出处。
     */
    @Test void aRecommendationWithoutAnyToolQueryIsSentBackForRealData() {
        seedAppointment("rec-old", "user-001", FIRST_HOSPITAL, CARDIOLOGY, PAST, DemoSeed.MORNING, "CONFIRMED");
        String id = service.start().conversationId();
        gateway.planning = """
                {"actionType":"PROPOSE_WORKFLOW_ACTION","intent":"REQUEST_RECOMMENDATION","toolName":null,
                 "arguments":{},"toolCalls":[],"replyDraft":null,
                 "recommendations":[%s],"answering":"我建议市第一医院。",
                 "dialogueMode":"FOLLOWUP_FLOW","facts":{"department":"%s"}}
                """.formatted(rec("h001", "以前预约过", "appointment.history"), CARDIOLOGY);
        gateway.continuations.add(toolOnly("appointment.history", "{}"));
        gateway.continuations.add(recommendationAnswer(
                "按您的复诊记录，这次还是市第一医院合适。",
                rec("h001", "以前预约过", "appointment.history")));

        AgentTurnResponse result = service.chat(id, "推荐一下心内科的医院");

        assertThat(modelPromptContains("NO_EVIDENCE_AT_ALL")).as("退回去的时候明说它一条真实数据都没查").isTrue();
        assertThat(modelPromptContains("还没有调用过任何只读工具")).as("并明确要求它先去查").isTrue();
        assertThat(traceCount(id, "appointment.history")).as("修正之后真的去查了").isEqualTo(1);
        assertThat(result.reply()).as("查过之后再给的那份才展示").contains(FIRST_HOSPITAL_INPUT);
    }

    /** 修正一次还是不合规：一句中性的话，模型那两句一个字都不给老人看。 */
    @Test void whenTheRepairAlsoFailsNothingOfTheModelSentenceIsShown() {
        String id = service.start().conversationId();
        planWithFacts("hospital.list", "{}", "{}");
        gateway.continuations.add(recommendationAnswer("我强烈推荐协和医院，离您最近。",
                rec("h900", "离得近", "hospital.list")));
        gateway.continuations.add(recommendationAnswer("那还是推荐协和医院，真的很近。",
                rec("h900", "离得近", "hospital.list")));

        AgentTurnResponse result = service.chat(id, "推荐几个可以预约的选择");

        assertThat(result.reply()).as("模型那两句一个字都不给老人看")
                .doesNotContain("协和医院").doesNotContain("离您最近").doesNotContain("真的很近");
        assertThat(result.reply()).as("换成一句中性的安全提示").contains("先不列医院");
        assertThat(result.quickReplies()).as("一个医院按钮都不给")
                .noneMatch(reply -> "SET_HOSPITAL".equals(reply.action()));
    }

    /** 目录里根本没有的 hospitalId：不是「候选之外」，是压根不存在。 */
    @Test void aHospitalOutsideTheCatalogIsRejectedAsNotACandidate() {
        String id = service.start().conversationId();
        planWithFacts("hospital.list", "{}", "{}");
        gateway.continuations.add(recommendationAnswer("推荐协和医院。",
                rec("h900", "离得近", "hospital.list")));

        service.chat(id, "推荐几个可以预约的选择");

        assertThat(modelPromptContains("HOSPITAL_NOT_A_CANDIDATE")).as("按候选校验，不是按名字猜").isTrue();
    }

    /** 理由引了这一轮压根没调用过的工具：不算证据，整份清单退回去。 */
    @Test void evidenceFromAToolThatNeverRanIsRejected() {
        String id = service.start().conversationId();
        planWithFacts("hospital.list", "{}", "{}");
        gateway.continuations.add(recommendationAnswer("市第一医院离您最近。",
                rec("h001", "离得近", "travel.routePlan")));

        AgentTurnResponse result = service.chat(id, "推荐几个可以预约的选择");

        assertThat(result.reply()).as("引了没跑过的工具，这句话不给老人看")
                .doesNotContain("市第一医院离您最近");
        assertThat(modelPromptContains("EVIDENCE_NOT_GATHERED")).as("退回去的时候说清是哪条证据没走通").isTrue();
    }

    /** 工具跑过了，但一条记录都没有：空结果不是证据。 */
    @Test void evidenceFromAToolThatRanButFoundNothingIsRejected() {
        String id = service.start().conversationId();
        planWithFacts("appointment.history", "{}", "{}");
        gateway.continuations.add(recommendationAnswer("按您的历史，推荐市第一医院。",
                rec("h001", "以前预约过", "appointment.history")));

        AgentTurnResponse result = service.chat(id, "推荐几个可以预约的选择");

        assertThat(traceCount(id, "appointment.history")).as("工具确实跑过").isEqualTo(1);
        assertThat(result.reply()).as("查了但没查到，这句话同样不给老人看")
                .doesNotContain("按您的历史");
        assertThat(modelPromptContains("EVIDENCE_EMPTY")).as("空结果被点出来了").isTrue();
    }

    /** 医院级证据必须就是关于这一家的：查的是市人民医院，不能拿来当市第一医院的理由。 */
    @Test void evidenceAboutAnotherHospitalIsRejected() {
        jdbc.update("""
                INSERT INTO appointment_slots(id,hospital_id,hospital_name,department,appointment_date,appointment_time,available)
                VALUES (?,?,?,?,?,?,TRUE)
                """, "rec-other-slot", "h002", PEOPLE_HOSPITAL, CARDIOLOGY,
                Date.valueOf(UPCOMING), Time.valueOf(DemoSeed.MORNING));
        String id = service.start().conversationId();
        planWithFacts("appointment.querySlots", """
                {"hospital":"%s","department":"%s","date":"%s"}
                """.formatted(PEOPLE_HOSPITAL_INPUT, CARDIOLOGY, UPCOMING), "{}");
        gateway.continuations.add(recommendationAnswer("市第一医院还有号。",
                rec("h001", "还有号", "appointment.querySlots")));

        AgentTurnResponse result = service.chat(id, "推荐几个可以预约的选择");

        assertThat(traceCount(id, "appointment.querySlots")).as("号源真的查了").isEqualTo(1);
        assertThat(result.reply()).as("张冠李戴的理由不给老人看").doesNotContain("市第一医院还有号");
        assertThat(modelPromptContains("EVIDENCE_NOT_ABOUT_HOSPITAL")).as("张冠李戴被点出来了").isTrue();
    }

    /** 清单是干净的，但那句话里又提了被排除的那家：整句不给老人看。 */
    @Test void answeringMayNotNameAnExcludedHospital() {
        String id = service.start().conversationId();
        planWithFacts("hospital.list", "{}", """
                {"department":"%s","excludedHospitals":["%s"]}
                """.formatted(CARDIOLOGY, FIRST_HOSPITAL_INPUT));
        gateway.continuations.add(recommendationAnswer(
                "市第一医院也不错，不过这次看看市人民医院吧。",
                rec("h002", "心内科可以复诊", "hospital.list")));
        gateway.continuations.add(recommendationAnswer(
                "那就看市人民医院，它的心内科可以复诊。",
                rec("h002", "心内科可以复诊", "hospital.list")));

        AgentTurnResponse result = service.chat(id, "推荐几个心内科的选择，市一这次不去。");

        assertThat(result.reply()).as("提了被排除的那家，整句不给老人看")
                .doesNotContain(FIRST_HOSPITAL_INPUT);
        assertThat(result.reply()).contains(PEOPLE_HOSPITAL_INPUT);
    }

    /**
     * 那句话里提了目录里真实存在、但不在本次已校验名单里的医院。
     *
     * <p>包含检查用的是<b>真实目录名</b>（去掉「（模拟）」再比），不是新增一份中文词表：
     * 老人看到的每句话里出现的医院名，都必须来自这一轮验过的那几家。
     */
    @Test void answeringMayNotNameARealHospitalThatWasNotRecommended() {
        String id = service.start().conversationId();
        planWithFacts("hospital.list", "{}", "{}");
        gateway.continuations.add(recommendationAnswer(
                "市第一医院也不错，不过这次看市人民医院吧。",
                rec("h002", "心内科可以复诊", "hospital.list")));
        gateway.continuations.add(recommendationAnswer(
                "这次看市人民医院，它的心内科可以复诊。",
                rec("h002", "心内科可以复诊", "hospital.list")));

        AgentTurnResponse result = service.chat(id, "推荐几个可以预约的选择");

        assertThat(result.reply()).as("夹带没验过的真实医院名，整句不给老人看")
                .doesNotContain(FIRST_HOSPITAL_INPUT);
        assertThat(result.reply()).contains(PEOPLE_HOSPITAL_INPUT);
    }

    /** 一次给超过 3 条：退回去让模型自己删，Java 不替它砍前三条。 */
    @Test void moreThanThreeRecommendationsIsSentBackInsteadOfTrimmed() {
        String id = service.start().conversationId();
        planWithFacts("hospital.list", "{}", "{}");
        gateway.continuations.add(recommendationAnswer("四家都看看吧。",
                rec("h001", "心内科可以复诊", "hospital.list"),
                rec("h002", "心内科可以复诊", "hospital.list"),
                rec("h001", "心内科可以复诊", "hospital.list"),
                rec("h002", "心内科可以复诊", "hospital.list")));

        AgentTurnResponse result = service.chat(id, "推荐几个可以预约的选择");

        assertThat(result.reply()).as("超了就不展示，也不悄悄砍成三条").doesNotContain("四家都看看吧");
        assertThat(modelPromptContains("TOO_MANY")).as("条数超了被点出来了").isTrue();
    }

    /** 按钮顺序跟着模型给的清单走；这一轮别的什么都不改。 */
    @Test void theButtonsFollowTheModelsOrderAndChangeNothingElse() {
        String id = service.start().conversationId();
        String hospitalBefore = liveState(id).hospital;
        String stageBefore = liveState(id).stage.name();
        planRecommendationWithEvidence("hospital.list", "{}", """
                {"department":"%s"}
                """.formatted(CARDIOLOGY),
                "这两家都有心内科，可以都看看。",
                // 故意把 h002 排前面：Java 不按目录顺序重排。
                rec("h002", "心内科可以复诊", "hospital.list"),
                rec("h001", "心内科可以复诊", "hospital.list"));

        AgentTurnResponse result = service.chat(id, "推荐一下心内科的医院");

        assertThat(result.quickReplies().stream().map(reply -> reply.value()).toList())
                .as("按钮顺序跟着模型给的清单走").containsExactly("h002", "h001");
        assertThat(result.confirmation()).as("推荐不建卡").isNull();
        assertThat(result.uiDirective()).as("推荐不跳页面").isNull();
        assertThat(liveState(id).hospital).as("不写草稿").isEqualTo(hospitalBefore);
        assertThat(liveState(id).stage.name()).as("不推进阶段").isEqualTo(stageBefore);
    }

    // ---------------------------------------------------------------- 脚手架

    /**
     * 模型这一轮以 REQUEST_RECOMMENDATION 给出推荐依据：科室，以及老人明确排除的医院。
     *
     * <p>只给依据、不给结构化推荐：验证的是 Java 拿这些依据算候选之后自己怎么收场
     * （排除认不准、排完一家不剩），那条路在读到模型输出之前就终结了。
     */
    private void planRecommendation(String factsJson) {
        gateway.planning = """
                {"actionType":"PROPOSE_WORKFLOW_ACTION","intent":"REQUEST_RECOMMENDATION","toolName":null,
                 "arguments":{},"toolCalls":[],"replyDraft":"我来帮您看看有哪些选择。",
                 "dialogueMode":"FOLLOWUP_FLOW","facts":%s}
                """.formatted(factsJson);
    }

    /** 医院、科室、日期、已选号源、交通方式都齐了、还没走到确认卡的草稿。 */
    private String loadedDraft() {
        String id = service.start().conversationId();
        action(id, "SET_HOSPITAL", "h001");
        action(id, "SET_DEPARTMENT", DemoSeed.CARDIOLOGY);
        action(id, "SET_DATE", DemoSeed.checkupDay().toString());
        action(id, "SELECT_SLOT", DemoSeed.morningSlot());
        action(id, "SET_TRANSPORT", "打车");
        resetGateway();
        return id;
    }

    private void seedAppointment(String id, String userId, String hospital, String department,
                                 LocalDate day, LocalTime time, String status) {
        jdbc.update("""
                INSERT INTO appointment_slots(id,hospital_id,hospital_name,department,appointment_date,appointment_time,available)
                VALUES (?,?,?,?,?,?,TRUE)
                """, "rec-slot-" + id, "h001", hospital, department, Date.valueOf(day), Time.valueOf(time));
        jdbc.update("INSERT INTO appointments(id,slot_id,user_id,status,created_at) VALUES (?,?,?,?,?)",
                id, "rec-slot-" + id, userId, status, Timestamp.valueOf(LocalDateTime.now()));
    }

    /** 模型这一轮点哪个工具、带哪几个参数。 */
    private void plan(String toolName, String argumentsJson) {
        planWithFacts(toolName, argumentsJson, "{}");
    }

    /**
     * 带 facts 的那一种：老人这一轮说的条件（科室、明确排除的医院）就在这里。
     *
     * <p>工具循环的用例必须用这一种——排除了谁首轮就要说清楚，光靠续跑轮是验不了「约束穿过
     * 循环」这件事的。
     */
    private void planWithFacts(String toolName, String argumentsJson, String factsJson) {
        gateway.planning = """
                {"actionType":"CALL_READ_TOOL","intent":"REQUEST_RECOMMENDATION","toolName":"%s",
                 "arguments":%s,"replyDraft":null,"dialogueMode":"FOLLOWUP_FLOW","facts":%s}
                """.formatted(toolName, argumentsJson, factsJson);
    }

    /**
     * 工具结果回到模型之后，它不再点工具，直接给出<b>结构化</b>推荐和最终话语。
     *
     * <p>facts 故意留空：真实的续跑轮常常就是这样——模型看到历史就开口推荐，不会再复述一遍
     * 老人说过什么。排除目标能不能穿过这一轮，正是这些用例要验的。
     */
    private String recommendationAnswer(String answering, String... recommendations) {
        return """
                {"actionType":"PROPOSE_WORKFLOW_ACTION","intent":"REQUEST_RECOMMENDATION","toolName":null,
                 "arguments":{},"toolCalls":[],"replyDraft":null,"recommendations":[%s],
                 "answering":"%s","dialogueMode":"FOLLOWUP_FLOW","facts":{}}
                """.formatted(String.join(",", recommendations), answering);
    }

    /** 一条结构化推荐：{@code hospitalId} + 一句理由 + 这一轮真正查过的工具名。 */
    private String rec(String hospitalId, String reason, String... evidenceRefs) {
        String refs = java.util.Arrays.stream(evidenceRefs).map(ref -> "\"" + ref + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"hospitalId\":\"%s\",\"reason\":\"%s\",\"evidenceRefs\":[%s]}"
                .formatted(hospitalId, reason, refs);
    }

    /**
     * 走一次完整的推荐轮：首轮先查一个真实只读工具，结果回来之后给结构化清单和最终话语。
     *
     * <p>推荐轮里模型必须真的查过东西——一条工具都没查就给推荐，Java 会把它退回来要求先查。
     * 所以除了专门验「没查就推荐」的那条用例，其它推荐用例都得先把工具跑起来。
     */
    private void planRecommendationWithEvidence(String toolName, String argumentsJson, String factsJson,
                                                String answering, String... recommendations) {
        planWithFacts(toolName, argumentsJson, factsJson);
        gateway.continuations.add(recommendationAnswer(answering, recommendations));
    }

    /** 回答阶段的一句润色稿：只有这一层说了话，{@code result.reply()} 才是「老人看到的那句」。 */
    private String polish(String text) {
        return """
                {"reply":"%s"}
                """.formatted(text);
    }

    /** 工具结果回到模型之后，它接着再点一个工具。 */
    private String toolOnly(String toolName, String argumentsJson) {
        return """
                {"actionType":"CALL_READ_TOOL","intent":"REQUEST_RECOMMENDATION","toolName":"%s",
                 "arguments":%s,"replyDraft":null,"dialogueMode":"FOLLOWUP_FLOW","facts":{}}
                """.formatted(toolName, argumentsJson);
    }

    /** 清掉上一轮留下的模型脚本，免得它被下一轮当成结果。 */
    private void resetGateway() {
        gateway.planning = "";
        gateway.continuations.clear();
        gateway.lastToolPhasePrompt = "";
    }

    private String draft(String hospital, String departmentId, LocalDate day) {
        String id = service.start().conversationId();
        action(id, "SET_HOSPITAL", hospital);
        action(id, "SET_DEPARTMENT", departmentId);
        action(id, "SET_DATE", day.toString());
        resetGateway();
        return id;
    }

    /**
     * 走完整办理漏斗，停在确认卡上（还没确认）。
     *
     * <p>历史那一条要落得离即将办的这次足够远：否则会被重复预约或时间冲突拦下，根本走不到确认卡。
     */
    private AgentTurnResponse useExistingCard() {
        seedAppointment("rec-old", "user-001", FIRST_HOSPITAL, CARDIOLOGY, PAST, DemoSeed.MORNING, "CONFIRMED");
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
        resetGateway();
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

    private String traceResponse(String conversationId, String toolName) {
        List<String> rows = jdbc.queryForList("""
                SELECT response_json FROM tool_call_logs
                WHERE conversation_id=? AND tool_name=? ORDER BY id
                """, String.class, conversationId, toolName);
        return rows.isEmpty() ? null : rows.get(rows.size() - 1);
    }

    private String traceRequest(String conversationId, String toolName) {
        List<String> rows = jdbc.queryForList("""
                SELECT request_json FROM tool_call_logs
                WHERE conversation_id=? AND tool_name=? ORDER BY id
                """, String.class, conversationId, toolName);
        return rows.isEmpty() ? null : rows.get(rows.size() - 1);
    }

    private int traceCount(String conversationId, String toolName) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tool_call_logs WHERE conversation_id=? AND tool_name=?
                """, Integer.class, conversationId, toolName);
        return count == null ? 0 : count;
    }
}
