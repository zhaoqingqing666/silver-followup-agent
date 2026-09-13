package com.team.silveragent;

import com.team.silveragent.agent.ExtractedFacts;
import com.team.silveragent.agent.planning.ConversationPlanner;
import com.team.silveragent.agent.planning.PlannerActionType;
import com.team.silveragent.agent.planning.PlannerDecision;
import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.model.AgentTurnResponse.QuickReply;
import com.team.silveragent.domain.model.ConversationHistoryResponse;
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
 * 通用澄清能力接进预约取消链路之后的端到端性质。
 *
 * <p>三条线必须一直分得开，这是本阶段的重点：
 * <ul>
 *   <li><b>澄清</b>：模型问一句、Java 摆出真实候选。不建卡、不发 {@code confirmationId}。</li>
 *   <li><b>确认</b>：Java 按数据库快照建卡发凭据，等老人明确确认。</li>
 *   <li><b>执行</b>：只认有效凭据。</li>
 * </ul>
 * 所以「模型提了个问题」这件事本身绝不能被当成一次执行授权：这里从头到尾断言库里一条都没动。
 *
 * <p>另一半是参数契约：模型给的 scope 说不通时（取值不在枚举里、按日期范围却没给方向）这次调用
 * 不执行，Java 在范围不明和「压根没有可取消的预约」之间给出不同的说法。
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:silver-agent-clarify;DB_CLOSE_DELAY=-1"})
class ClarificationFlowTests {
    @Autowired FollowupAgentService service;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ConversationPlanner planner;

    @BeforeEach void resetData() {
        for (String table : List.of("appointments", "reminders", "family_notifications")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    /** 模型提议一次澄清：candidateTool 点名只读工具，question 是它自己写的追问。 */
    private PlannerDecision clarification(String question, String candidateTool) {
        Map<String, String> arguments = new java.util.LinkedHashMap<>();
        arguments.put("candidateTool", candidateTool);
        if (question != null) arguments.put("question", question);
        return new PlannerDecision(PlannerActionType.CALL_CONFIRMATION_TOOL, "CANCEL_APPOINTMENT",
                "interaction.askClarification", arguments, null, "FOLLOWUP_FLOW",
                facts("CANCEL_APPOINTMENT"), "MODEL_PLANNER");
    }

    private PlannerDecision cancellationCard(Map<String, String> arguments) {
        return new PlannerDecision(PlannerActionType.CALL_CONFIRMATION_TOOL, "CANCEL_APPOINTMENT",
                "interaction.requestConfirmation", arguments, "我再和您确认一下。", "FOLLOWUP_FLOW",
                facts("CANCEL_APPOINTMENT"), "MODEL_PLANNER");
    }

    private PlannerDecision readMyAppointments() {
        return new PlannerDecision(PlannerActionType.CALL_READ_TOOL, "QUERY_APPOINTMENTS",
                "appointment.queryMine", Map.of(), null, "FOLLOWUP_FLOW",
                facts("QUERY_APPOINTMENTS"), "MODEL_PLANNER");
    }

    /**
     * 模型把确认交互<b>误写成只读调用</b>，参数却是确认工具该有的那份。
     *
     * <p>实测里这个错非常常见（它把「要不要确认」当成了「查一下」）。写错标签不该改变任何结果：
     * 参数还得原样进取消链路，该走哪条路由、该出什么卡，和写对了完全一样。
     */
    private PlannerDecision miswrittenConfirmation(Map<String, String> arguments) {
        return new PlannerDecision(PlannerActionType.CALL_READ_TOOL, "CANCEL_APPOINTMENT",
                "interaction.requestConfirmation", arguments, "我再和您确认一下。", "FOLLOWUP_FLOW",
                facts("CANCEL_APPOINTMENT"), "MODEL_PLANNER");
    }

    /** 一句话拆成两个分支用的桩：按原句内容决定这一轮模型给什么。 */
    private void plannerAnswers(java.util.function.Function<String, PlannerDecision> byMessage) {
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenAnswer(call -> {
            String message = call.getArgument(0);
            return byMessage.apply(message == null ? "" : message);
        });
    }

    /**
     * 模型提出的澄清：话是模型写的，候选必须是数据库里的真行，并且摆成可以点选的按钮。
     * 关键在于这一轮不产生任何执行授权——stage 不进 AWAITING_CONFIRMATION，confirmation 为空。
     */
    @Test
    void modelQuestionIsAskedWithRealCandidatesAndNoExecutionAuthority() {
        seedAppointment("clarify-a", LocalDate.of(2026, 9, 10), "09:00");
        seedAppointment("clarify-b", LocalDate.of(2026, 9, 14), "14:00");
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(
                clarification("您想取消的是哪一条复诊预约？", "appointment.queryMine"));

        AgentTurnResponse turn = service.chat(service.start().conversationId(), "那些都不要了");

        assertThat(turn.reply()).as(turn.reply()).contains("您想取消的是哪一条复诊预约？");
        assertThat(turn.reply()).contains("2026年9月10日", "2026年9月14日");
        assertThat(turn.stage()).isNotEqualTo("AWAITING_CONFIRMATION");
        assertThat(turn.confirmation()).isNull();
        assertThat(turn.quickReplies()).filteredOn(reply -> "SELECT_APPOINTMENT_TO_CANCEL".equals(reply.action()))
                .hasSize(2)
                .anySatisfy(reply -> assertThat(reply.value()).isEqualTo("clarify-a"))
                .anySatisfy(reply -> assertThat(reply.value()).isEqualTo("clarify-b"));
        // 按钮上的话和口播的话必须一致：这份回复不交给模型润色。
        assertThat(turn.speechText()).isEqualTo(turn.reply());
        assertThat(confirmedAppointments()).isEqualTo(2);
    }

    /**
     * 澄清完再收到一句「确认」也不构成执行：凭据还是空的，那句确认只能被判成失效。
     * 这正是「澄清不是确认」在链路上的可见后果——模型可以问，但问不出一次取消。
     */
    @Test
    void aConfirmRightAfterAClarificationCancelsNothing() {
        seedAppointment("clarify-confirm", LocalDate.of(2026, 9, 10), "09:00");
        seedAppointment("clarify-confirm-2", LocalDate.of(2026, 9, 14), "14:00");
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenAnswer(call -> {
            String message = call.getArgument(0);
            if (message != null && message.contains("确认")) {
                return new PlannerDecision(PlannerActionType.CALL_CONFIRMATION_TOOL, "CONFIRM_ACTION",
                        "interaction.respondConfirmation", Map.of("decision", "CONFIRM"), null,
                        "FOLLOWUP_FLOW", facts("CONFIRM_ACTION"), "MODEL_PLANNER");
            }
            return clarification("您想取消的是哪一条？", "appointment.queryMine");
        });

        String conversationId = service.start().conversationId();
        AgentTurnResponse asked = service.chat(conversationId, "那些都不要了");
        assertThat(asked.confirmation()).isNull();

        AgentTurnResponse confirmed = service.chat(conversationId, "确认");
        assertThat(confirmed.reply()).as(confirmed.reply()).contains("失效");
        assertThat(confirmed.confirmation()).isNull();
        assertThat(confirmedAppointments()).isEqualTo(2);
    }

    /** 库里没有可取消的预约时，「查不到」和「说不清」要分开：不摆空问题，也不摆按钮。 */
    @Test
    void clarificationWithoutAnyCandidateSaysNothingWasFound() {
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(
                clarification("您想取消的是哪一条？", "appointment.queryMine"));

        AgentTurnResponse turn = service.chat(service.start().conversationId(), "那些都不要了");

        assertThat(turn.reply()).contains("没有查到可以取消的已确认预约");
        assertThat(turn.quickReplies()).noneSatisfy(reply ->
                assertThat(reply.action()).isEqualTo("SELECT_APPOINTMENT_TO_CANCEL"));
        assertThat(turn.confirmation()).isNull();
    }

    /**
     * 参数说不通的取消请求（按日期范围却没给方向）不执行，也不顺手当成「都取消」：
     * 换成 Java 固定的追问，把真实候选摆出来让老人直接选。
     */
    @Test
    void dateRangeWithoutDirectionBecomesAMissingInfoClarification() {
        seedAppointment("missing-direction-a", LocalDate.of(2026, 9, 10), "09:00");
        seedAppointment("missing-direction-b", LocalDate.of(2026, 9, 14), "14:00");
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(
                cancellationCard(Map.of("scope", "DATE_RANGE", "date", "2026-09-12")));

        AgentTurnResponse turn = service.chat(service.start().conversationId(), "12号之前的都取消");

        assertThat(turn.confirmation()).isNull();
        assertThat(turn.stage()).isNotEqualTo("AWAITING_CONFIRMATION");
        assertThat(turn.reply()).as(turn.reply()).contains("我还需要知道取消的范围");
        assertThat(turn.quickReplies()).filteredOn(reply -> "SELECT_APPOINTMENT_TO_CANCEL".equals(reply.action()))
                .hasSize(2);
        assertThat(confirmedAppointments()).isEqualTo(2);
    }

    /** 枚举之外的取值同样不执行。 */
    @Test
    void scopeOutsideTheEnumIsNotExecuted() {
        seedAppointment("bad-scope-a", LocalDate.of(2026, 9, 10), "09:00");
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(
                cancellationCard(Map.of("scope", "EVERYTHING")));

        AgentTurnResponse turn = service.chat(service.start().conversationId(), "把预约都处理掉");

        assertThat(turn.confirmation()).isNull();
        assertThat(turn.stage()).isNotEqualTo("AWAITING_CONFIRMATION");
        assertThat(turn.reply()).contains("我还需要知道取消的范围");
        assertThat(confirmedAppointments()).isEqualTo(1);
    }

    /**
     * 模型这一轮给的是<b>合法</b>工具调用时，原句里的「都取消」不得再把意图覆盖回去。
     *
     * <p>这正是本阶段要堵的那个洞：以前关键词兜底只看「动作类型像不像确认」，模型调一个只读工具
     * （这里 {@code appointment.queryMine}）时，一句“都取消”就会被 Java 接手、直接生成整组确认卡。
     * 现在合法调用本身就说明白了意图，兜底整段不跑——库里一条都不动，也不出卡。
     */
    @Test
    void aLegalToolCallIsNotOverriddenByBatchCancelKeywords() {
        seedAppointment("keyword-override-a", LocalDate.of(2026, 9, 10), "09:00");
        seedAppointment("keyword-override-b", LocalDate.of(2026, 9, 14), "14:00");
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenAnswer(call -> {
            String message = call.getArgument(0);
            // 第一句让取消支线立起来（澄清，不建卡）。
            if (message != null && message.contains("都不要了")) {
                return clarification("您想取消的是哪一条？", "appointment.queryMine");
            }
            // 第二句模型给的是合法只读查询，原句里却带着「都取消」。
            return readMyAppointments();
        });

        String conversationId = service.start().conversationId();
        assertThat(service.chat(conversationId, "那些都不要了").confirmation()).isNull();

        AgentTurnResponse turn = service.chat(conversationId, "都取消");

        assertThat(turn.confirmation()).isNull();
        assertThat(turn.stage()).isNotEqualTo("AWAITING_CONFIRMATION");
        assertThat(confirmedAppointments()).isEqualTo(2);
    }

    /**
     * 标签写错、参数合法：范围必须按模型给的结构化参数生效，不能被原句里的「都取消」改回去。
     *
     * <p>这条用例特意只写「都取消」——没有「之前」这类关键词可捞。老路子上参数会被丢掉、退回
     * Java 词表，于是「都取消」被读成整组，两张卡齐发，其中一张是老人没说过的那条预约。
     */
    @Test
    void aMiswrittenConfirmationWithALegalRangeStillCancelsOnlyThatRange() {
        seedAppointment("range-in", LocalDate.of(2026, 9, 10), "09:00");
        seedAppointment("range-out", LocalDate.of(2026, 9, 14), "14:00");
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(miswrittenConfirmation(
                Map.of("scope", "DATE_RANGE", "date", "2026-09-12", "direction", "BEFORE")));

        AgentTurnResponse turn = service.chat(service.start().conversationId(), "都取消");

        assertThat(turn.stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(turn.confirmation()).as(turn.reply()).isNotNull();
        assertThat(turn.confirmation().operations())
                .anySatisfy(operation -> assertThat(operation).contains("2026年9月10日"))
                .noneSatisfy(operation -> assertThat(operation).contains("2026年9月14日"));
        // 一张卡而不是整组：模型点的是范围，Java 就按范围出卡。
        assertThat(turn.confirmation().title()).doesNotContain("2条");
        assertThat(turn.reply()).contains("2026年9月10日").doesNotContain("2026年9月14日");
        assertThat(confirmedAppointments()).isEqualTo(2);
    }

    /** 参数不合法（按日期范围却没给方向）时，「都取消」也救不了它：不出卡、不写库，只追问范围。 */
    @Test
    void aMiswrittenConfirmationWithoutDirectionIsNotSavedByBatchKeywords() {
        seedAppointment("no-direction-a", LocalDate.of(2026, 9, 10), "09:00");
        seedAppointment("no-direction-b", LocalDate.of(2026, 9, 14), "14:00");
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(miswrittenConfirmation(
                Map.of("scope", "DATE_RANGE", "date", "2026-09-12")));

        AgentTurnResponse turn = service.chat(service.start().conversationId(), "都取消");

        assertThat(turn.confirmation()).isNull();
        assertThat(turn.stage()).isNotEqualTo("AWAITING_CONFIRMATION");
        assertThat(turn.reply()).contains("我还需要知道取消的范围");
        assertThat(turn.quickReplies()).filteredOn(reply -> "SELECT_APPOINTMENT_TO_CANCEL".equals(reply.action()))
                .hasSize(2);
        assertThat(confirmedAppointments()).isEqualTo(2);
    }

    /** 取值不在枚举里同样不执行，同样不被「都取消」兜成整组卡。 */
    @Test
    void aMiswrittenConfirmationWithAnUnknownScopeIsNotSavedByBatchKeywords() {
        seedAppointment("bad-enum-a", LocalDate.of(2026, 9, 10), "09:00");
        seedAppointment("bad-enum-b", LocalDate.of(2026, 9, 14), "14:00");
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(
                miswrittenConfirmation(Map.of("scope", "EVERYTHING")));

        AgentTurnResponse turn = service.chat(service.start().conversationId(), "都取消");

        assertThat(turn.confirmation()).isNull();
        assertThat(turn.stage()).isNotEqualTo("AWAITING_CONFIRMATION");
        assertThat(turn.reply()).contains("我还需要知道取消的范围");
        assertThat(confirmedAppointments()).isEqualTo(2);
    }

    /**
     * 范围被修订、改到一条都不剩时，旧卡必须同时作废并<b>退出等待确认</b>。
     *
     * <p>只清凭据不退状态是更坏的一种：屏幕还停在「等待确认」，按钮却永远不会亮，
     * 老人按哪儿都不对，也说不清自己卡在哪一步。而且那张卡的旧凭据必须真的失效——
     * 否则他还能拿着一张描述早已被改掉的范围的卡去执行。
     */
    @Test
    void revisingTheRangeAwayRetiresTheOldCardAndLeavesAwaitingConfirmation() {
        seedAppointment("revision-a", LocalDate.of(2026, 9, 10), "09:00");
        seedAppointment("revision-b", LocalDate.of(2026, 9, 14), "14:00");
        plannerAnswers(message -> message.contains("5号")
                ? cancellationCard(Map.of("scope", "DATE_RANGE", "date", "2026-09-05", "direction", "BEFORE"))
                : cancellationCard(Map.of("scope", "DATE_RANGE", "date", "2026-09-20", "direction", "BEFORE")));

        String conversationId = service.start().conversationId();
        AgentTurnResponse card = service.chat(conversationId, "20号之前的都取消");
        assertThat(card.stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(card.confirmation()).isNotNull();
        String retiredId = card.confirmation().confirmationId();

        AgentTurnResponse revised = service.chat(conversationId, "还是只取消5号之前的");

        assertThat(revised.reply()).contains("没有找到符合条件的已确认预约");
        assertThat(revised.confirmation()).isNull();
        assertThat(revised.stage()).isNotEqualTo("AWAITING_CONFIRMATION");
        // 旧凭据跟着作废：拿它再确认一次只能得到「失效」，什么都不会发生。
        AgentTurnResponse late = service.confirm(conversationId, true, retiredId);
        assertThat(late.reply()).contains("失效");
        assertThat(confirmedAppointments()).isEqualTo(2);
    }

    /**
     * 卡还在等确认时，老人插一句不相干的追问，那张卡不能因此消失——他正要按的「确认办理」
     * 不能因为多问了一句话就没了。重新下发的必须是<b>同一张卡、同一个凭据</b>：内容只有一处来源，
     * 他屏幕上看到的和真正会执行的那份永远是同一个。
     */
    @Test
    void aSideQuestionKeepsTheSamePendingCardAndResumeStillShowsIt() {
        seedAppointment("side-a", LocalDate.of(2026, 9, 10), "09:00");
        seedAppointment("side-b", LocalDate.of(2026, 9, 14), "14:00");
        plannerAnswers(message -> message.contains("材料")
                ? new PlannerDecision(PlannerActionType.ANSWER, "QUERY_CARE_GUIDE", null, Map.of(),
                        "复诊记得带上身份证、医保卡和既往检查报告。", "FOLLOWUP_FLOW",
                        facts("QUERY_CARE_GUIDE"), "MODEL_PLANNER")
                : cancellationCard(Map.of("scope", "DATE_RANGE", "date", "2026-09-20", "direction", "BEFORE")));

        String conversationId = service.start().conversationId();
        AgentTurnResponse card = service.chat(conversationId, "20号之前的都取消");
        assertThat(card.confirmation()).isNotNull();
        String confirmationId = card.confirmation().confirmationId();

        AgentTurnResponse asked = service.chat(conversationId, "复诊要准备什么材料？");

        assertThat(asked.reply()).contains("医保卡");
        assertThat(asked.stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(asked.confirmation()).as("追问不该把待确认的卡吞掉").isNotNull();
        assertThat(asked.confirmation().confirmationId()).isEqualTo(confirmationId);
        assertThat(asked.confirmation().operations()).isEqualTo(card.confirmation().operations());

        // 会话恢复（刷新页面 / 跨请求重建）看到的必须是同一份：还是这张卡、这个凭据。
        ConversationHistoryResponse resumed = service.resume(conversationId);
        assertThat(resumed.current().stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(resumed.current().confirmation()).isNotNull();
        assertThat(resumed.current().confirmation().confirmationId()).isEqualTo(confirmationId);
    }

    /**
     * 候选多于一页时，<b>每一条真实的候选都得点得到</b>。
     *
     * <p>一页只摆四条是为了看得清、点得准；但排在第五、第六位的那条如果谁都点不到，就等于
     * 「我上个月那条」永远取消不了。所以超额部分要能翻过去，而且翻页按钮自己带着页码，
     * 不往会话里塞任何新状态。
     */
    @Test
    void everyCancellationCandidateIsReachableAcrossPages() {
        for (int day = 11; day <= 16; day++) {
            seedAppointment("page-" + day, LocalDate.of(2026, 9, day), "09:00");
        }
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(
                clarification("您想取消的是哪一条？", "appointment.queryMine"));

        String conversationId = service.start().conversationId();
        AgentTurnResponse first = service.chat(conversationId, "那些都不要了");

        assertThat(first.reply()).contains("第1页（共2页）");
        assertThat(candidateIds(first)).hasSize(4);
        QuickReply more = first.quickReplies().stream()
                .filter(reply -> "MORE_CANCEL_CANDIDATES".equals(reply.action()))
                .findFirst().orElseThrow(() -> new AssertionError("首页没有翻页入口：" + first.quickReplies()));
        assertThat(more.value()).isEqualTo("1");

        AgentTurnResponse second = service.act(conversationId, "MORE_CANCEL_CANDIDATES", more.value(), more.label());

        assertThat(candidateIds(second)).hasSize(2);
        assertThat(candidateIds(second)).doesNotContainAnyElementsOf(candidateIds(first));
        assertThat(concat(candidateIds(first), candidateIds(second)))
                .containsExactlyInAnyOrderElementsOf(List.of("page-11", "page-12", "page-13",
                        "page-14", "page-15", "page-16"));
        // 翻过去的那条照样点得动：点选之后仍然只是进确认卡，不是直接取消。
        String lastId = candidateIds(second).stream().sorted().reduce((left, right) -> right).orElseThrow();
        AgentTurnResponse chosen = service.act(conversationId, "SELECT_APPOINTMENT_TO_CANCEL", lastId, null);
        assertThat(chosen.stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(chosen.confirmation()).as(chosen.reply()).isNotNull();
        assertThat(chosen.confirmation().operations())
                .anySatisfy(operation -> assertThat(operation).contains("2026年9月16日"));
        assertThat(confirmedAppointments()).isEqualTo(6);
    }

    private List<String> candidateIds(AgentTurnResponse turn) {
        return turn.quickReplies().stream()
                .filter(reply -> "SELECT_APPOINTMENT_TO_CANCEL".equals(reply.action()))
                .map(QuickReply::value).toList();
    }

    private List<String> concat(List<String> left, List<String> right) {
        List<String> all = new java.util.ArrayList<>(left);
        all.addAll(right);
        return all;
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
                VALUES (?,?,'user-001','CONFIRMED',CURRENT_TIMESTAMP,'clarify-seed')
                """, id, slotId);
    }

    private ExtractedFacts facts(String intent) {
        return new ExtractedFacts(intent, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private int confirmedAppointments() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE status='CONFIRMED'", Integer.class);
    }
}
