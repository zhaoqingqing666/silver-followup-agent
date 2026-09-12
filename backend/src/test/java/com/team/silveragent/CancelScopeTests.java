package com.team.silveragent;

import com.team.silveragent.agent.ExtractedFacts;
import com.team.silveragent.agent.planning.ConversationPlanner;
import com.team.silveragent.agent.planning.PlannerActionType;
import com.team.silveragent.agent.planning.PlannerDecision;
import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.support.DemoSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 取消范围走结构化通道时的边界：范围由模型给，能不能执行由 Java 判。
 *
 * <p>范围不再是 Java 从中文里认出来的，而是模型显式给出的 {@code scope/date/direction}。所以这里钉的是
 * 三条端到端的性质：(1) 模型抽的 {@code facts.date()} 不参与筛选——“都取消”不会被悄悄缩成某一天；
 * (2) 用户改口换范围时旧凭据当场作废、必须二次确认；(3) 模型把一句没圈定范围的话硬判成 {@code scope=ALL}
 * 时，过宽的范围**逐条列在卡上**（老人看得见要取消的是哪几条），且确认之前库里一条都不动。
 *
 * <p>关键词那条路只在模型不可用时才跑到，由 {@code VoiceFirstP0Tests}（{@code agent.model.enabled=false}）覆盖。
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:silver-agent-cancel-scope;DB_CLOSE_DELAY=-1"})
class CancelScopeTests {
    private static final String DAY = DemoSeed.day(DemoSeed.checkupDay());
    private static final String MORNING_SLOT = DemoSeed.morningSlot();
    private static final String AFTERNOON_SLOT = DemoSeed.secondSlot();
    /** 库里绝不会有预约的一天，用来证明“模型给的日子”没有被拿来当筛选条件。 */
    private static final LocalDate UNRELATED_DAY = LocalDate.now().plusMonths(3);

    @Autowired FollowupAgentService service;
    @Autowired JdbcTemplate jdbc;

    /**
     * 模型可用时，AgentRuntime 才会走模型提出的动作；这里只需要它稳定地给出 CANCEL_APPOINTMENT，
     * 具体日期由每个用例自己决定，用来模拟模型擅自补全的那个 date。
     */
    @MockitoBean ConversationPlanner planner;

    @BeforeEach void resetData() {
        for (String table : List.of("appointments", "reminders", "family_notifications")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE appointment_slots SET available=TRUE");
    }

    /**
     * 模型把“都取消”的 {@code facts.date} 抽成了某一天。范围参数是 {@code scope=ALL}，它不看 date，
     * 所以候选仍是全部——模型的日期事实不能把一次整体取消悄悄缩成一天。
     */
    @Test
    void modelExtractedDateCannotShrinkAnExplicitAllCancellation() {
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenAnswer(call ->
                cancellation(call.getArgument(0), null));

        book(MORNING_SLOT);
        AgentTurnResponse second = book(service.start().conversationId(), AFTERNOON_SLOT);
        String conversationId = second.conversationId();

        service.chat(conversationId, "我想取消预约");
        // 这一轮的 facts.date 是模型“脑补”的：它没在用户话里出现过。
        AgentTurnResponse card = service.chat(conversationId, "都取消");

        assertThat(card.stage()).as(card.reply()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(card.confirmation().title()).contains("2条");
        assertThat(card.confirmation().operations()).hasSize(3);
        assertThat(confirmedAppointments()).isEqualTo(2);
    }

    /**
     * 反过来：用户这一句真的提到了日期时，模型给的 {@code DATE_RANGE} 照常生效。
     * 这条守着上面那条不要矫枉过正，把日期筛选一并关掉。
     */
    @Test
    void modelDateIsStillHonouredWhenTheMessageReallyMentionsADate() {
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenAnswer(call ->
                cancellation(call.getArgument(0), LocalDate.parse(DAY)));

        book(MORNING_SLOT);
        AgentTurnResponse second = book(service.start().conversationId(), AFTERNOON_SLOT);
        String conversationId = second.conversationId();

        service.chat(conversationId, "我想取消预约");
        AgentTurnResponse card = service.chat(conversationId, "下周的都取消");

        assertThat(card.stage()).as(card.reply()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(card.confirmation().title()).contains("2条");
    }

    /** 假规划器：只负责“这句话是取消预约”和它擅自补全的日期，其余事实留空。 */
    private PlannerDecision cancellation(String message, LocalDate modelDate) {
        boolean mentionsDate = message != null
                && (message.contains("今天") || message.contains("明天") || message.contains("后天")
                || message.contains("下周") || message.contains("本周") || message.contains("这周")
                || message.matches(".*(周|星期)[一二三四五六日天].*")
                || message.matches(".*\\d{1,2}\\s*(月|[./-])\\s*\\d{1,2}.*"));
        ExtractedFacts facts = facts("CANCEL_APPOINTMENT", mentionsDate ? modelDate : UNRELATED_DAY);
        Map<String, String> arguments;
        if (message != null && message.contains("都取消")) {
            arguments = mentionsDate && modelDate != null
                    ? Map.of("scope", "DATE_RANGE", "date", modelDate.plusDays(1).toString(), "direction", "BEFORE")
                    : Map.of("scope", "ALL");
        } else {
            arguments = Map.of("scope", "AMBIGUOUS");
        }
        return new PlannerDecision(PlannerActionType.CALL_CONFIRMATION_TOOL, "CANCEL_APPOINTMENT",
                "interaction.requestConfirmation", arguments, "好的，我再和您确认一下。",
                "FOLLOWUP_FLOW", facts, "MODEL_PLANNER");
    }

    @Test
    void changingTheScopeInvalidatesTheOldCardAndRequiresASecondConfirmation() {
        seedAppointment("scope-before", LocalDate.of(2026, 9, 10), "09:00");
        seedAppointment("scope-after", LocalDate.of(2026, 9, 14), "14:00");
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenAnswer(call -> {
            String message = call.getArgument(0);
            if (message.contains("确认")) {
                return new PlannerDecision(PlannerActionType.CALL_CONFIRMATION_TOOL, "CONFIRM_ACTION",
                        "interaction.respondConfirmation", Map.of("decision", "CONFIRM"), null,
                        "FOLLOWUP_FLOW", facts("CONFIRM_ACTION", null), "MODEL_PLANNER");
            }
            Map<String, String> arguments = message.contains("12号之前")
                    ? Map.of("scope", "DATE_RANGE", "date", "2026-09-12", "direction", "BEFORE")
                    : Map.of("scope", "ALL");
            return new PlannerDecision(PlannerActionType.CALL_CONFIRMATION_TOOL, "CANCEL_APPOINTMENT",
                    "interaction.requestConfirmation", arguments, "我重新按您说的范围确认。",
                    "FOLLOWUP_FLOW", facts("CANCEL_APPOINTMENT", null), "MODEL_PLANNER");
        });

        String conversationId = service.start().conversationId();
        AgentTurnResponse allCard = service.chat(conversationId, "我想都取消了");
        assertThat(allCard.confirmation().title()).contains("2条");
        String oldConfirmationId = allCard.confirmation().confirmationId();

        AgentTurnResponse revised = service.chat(conversationId, "还是就取消一下12号之前的预约吧");
        assertThat(revised.stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(revised.confirmation().operations()).filteredOn(line -> line.startsWith("取消预约：")).hasSize(1);
        assertThat(revised.confirmation().operations().toString()).contains("2026年9月10日").doesNotContain("2026年9月14日");
        assertThat(revised.confirmation().confirmationId()).isNotEqualTo(oldConfirmationId);
        assertThat(confirmedAppointments()).isEqualTo(2);

        AgentTurnResponse stale = service.confirm(conversationId, true, oldConfirmationId);
        assertThat(stale.reply()).contains("失效");
        // 即使确认句里再次带了日期，也应由 respondConfirmation 消费当前卡，
        // 不能让旧的 Java 日期解析抢先生成第三张卡。
        service.chat(conversationId, "确认取消9月10日这条");
        assertThat(confirmedAppointments()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointments a JOIN appointment_slots s ON s.id=a.slot_id "
                + "WHERE a.status='CONFIRMED' AND s.appointment_date='2026-09-14'", Integer.class)).isEqualTo(1);
    }

    /**
     * 模型把一句**没有圈定范围**的话（“我想取消预约”）硬判成 {@code scope=ALL} 时会怎样。
     *
     * <p>这是结构化通道最薄弱的一环：Java 不再拿关键词复核“用户这句话配不配 ALL”，防线落在确认卡上。
     * 所以这条钉的不是“Java 会拦住”，而是两件必须成立的事——过宽的范围**逐条列在卡上**，老人看得见自己要
     * 取消的是哪几条；以及**确认之前库里一条都不动**。防线搬家了，这条测试就是把新防线写下来。
     */
    @Test
    void anOverBroadAllScopeIsStillVisibleOnTheCardAndCancelsNothing() {
        seedAppointment("over-broad-a", LocalDate.of(2026, 9, 10), "09:00");
        seedAppointment("over-broad-b", LocalDate.of(2026, 9, 14), "14:00");
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(new PlannerDecision(
                PlannerActionType.CALL_CONFIRMATION_TOOL, "CANCEL_APPOINTMENT",
                "interaction.requestConfirmation", Map.of("scope", "ALL"),
                "好的，我再和您确认一下。", "FOLLOWUP_FLOW",
                facts("CANCEL_APPOINTMENT", null), "MODEL_PLANNER"));

        AgentTurnResponse card = service.chat(service.start().conversationId(), "我想取消预约");

        assertThat(card.stage()).as(card.reply()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(card.confirmation().title()).contains("2条");
        assertThat(card.confirmation().operations()).filteredOn(line -> line.startsWith("取消预约："))
                .hasSize(2)
                .anySatisfy(line -> assertThat(line).contains("2026年9月10日"))
                .anySatisfy(line -> assertThat(line).contains("2026年9月14日"));
        assertThat(card.confirmation().confirmationId()).isNotBlank();
        assertThat(confirmedAppointments()).isEqualTo(2);
    }

    private void seedAppointment(String id, LocalDate date, String time) {
        String slotId = id + "-slot";
        jdbc.update("""
                INSERT INTO appointment_slots(id,hospital_id,hospital_name,department,
                    appointment_date,appointment_time,available)
                VALUES (?,'h001','市第一医院（模拟）','心内科',?,?,FALSE)
                """, slotId, date, time);
        jdbc.update("""
                INSERT INTO appointments(id,slot_id,user_id,status,created_at,conversation_id)
                VALUES (?,?,'user-001','CONFIRMED',CURRENT_TIMESTAMP,'scope-seed')
                """, id, slotId);
    }

    private ExtractedFacts facts(String intent, LocalDate date) {
        return new ExtractedFacts(intent, null, null, date, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private AgentTurnResponse action(String conversationId, String action, String value) {
        return service.act(conversationId, action, value, action);
    }

    private AgentTurnResponse book(String slotId) {
        return book(service.start().conversationId(), slotId);
    }

    private AgentTurnResponse book(String conversationId, String slotId) {
        action(conversationId, "SET_HOSPITAL", "h001");
        action(conversationId, "SET_DEPARTMENT", "d001");
        action(conversationId, "SET_DATE", DAY);
        action(conversationId, "SELECT_SLOT", slotId);
        action(conversationId, "SET_ALTERNATIVE", "true");
        action(conversationId, "SET_COMPANION", "true");
        action(conversationId, "SET_TRAVEL", "true");
        action(conversationId, "SET_TRANSPORT", "打车");
        action(conversationId, "SET_NOTIFY", "true");
        action(conversationId, "SET_CONTACT", "family-001");
        AgentTurnResponse turn = action(conversationId, "START_PLAN", "");
        assertThat(turn.stage()).as(turn.reply()).isEqualTo("AWAITING_CONFIRMATION");
        return service.confirm(conversationId, true, turn.confirmation().confirmationId());
    }

    private int confirmedAppointments() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE status='CONFIRMED'", Integer.class);
    }
}
