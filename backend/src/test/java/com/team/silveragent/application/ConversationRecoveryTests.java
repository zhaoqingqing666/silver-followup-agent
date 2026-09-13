package com.team.silveragent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.silveragent.agent.ExtractedFacts;
import com.team.silveragent.agent.planning.ConversationPlanner;
import com.team.silveragent.agent.planning.PlannerActionType;
import com.team.silveragent.agent.planning.PlannerDecision;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.support.DemoSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 会话恢复时那张确认卡还剩下多少授权——走<b>真实的库</b>（存进 state_json，再读回来）。
 *
 * <p>这里钉两条互相咬合的性质：
 * <ol>
 *   <li><b>完整恢复。</b>一张批量取消卡在签发时锁定了两条预约，那么从库里读回来之后它锁的还是
 *       那两条，确认下来取消的就是那两条。以前这批目标不进快照，重启之后卡片上写着两条、
 *       实际只会取消一条——而老人是照着卡片点的头，他根本看不出少了哪一条。</li>
 *   <li><b>旧快照安全失效。</b>更早版本写下的快照里没有"授权了什么"，还原不出完整目标集合。
 *       这种凭据整份作废、请他重新确认，<b>绝不按其中一条凑合执行</b>。</li>
 * </ol>
 *
 * <p>第 2 条是刻意做得这么绝的：能还原的范围也许只差一条，但"少取消一条"和"什么都没发生"
 * 在老人那边看起来完全一样，而前者的后果（一个他以为已经取消、实际还留着的号）要重得多。
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:silver-agent-recovery;DB_CLOSE_DELAY=-1"})
class ConversationRecoveryTests {
    private static final String DAY = DemoSeed.day(DemoSeed.checkupDay());

    @Autowired FollowupAgentService service;
    @Autowired ConversationStore conversations;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    /** 模型可用时才会走模型提出的动作；这里只需要它稳定地给出"我要取消预约"。 */
    @MockitoBean ConversationPlanner planner;

    @BeforeEach
    void resetData() {
        for (String table : List.of("appointments", "reminders", "family_notifications")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE appointment_slots SET available=TRUE");
        // 会话不能跨用例串：内存里那份缓存清掉，让下一轮从库里读。
        sessions().clear();
    }

    /**
     * 签发 → 落库 → 重启（内存缓存清空、从 state_json 读回来）→ 确认，取消的仍是原来那两条。
     */
    @Test
    void aBatchCardCancelsTheSameTwoAppointmentsAfterASessionReload() {
        askForCancellationCard();
        seedAppointment("recover-a", LocalDate.of(2026, 9, 10), "09:00");
        seedAppointment("recover-b", LocalDate.of(2026, 9, 14), "14:00");

        String conversationId = service.start().conversationId();
        AgentTurnResponse card = service.chat(conversationId, "我想取消预约");
        assertThat(card.stage()).as(card.reply()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(card.confirmation().title()).contains("2条");
        String confirmationId = card.confirmation().confirmationId();

        // 先确认授权范围真的进了库：从 state_json 读回来那一份必须一字不差。
        ConversationState fromDatabase = conversations.find(conversationId).orElseThrow();
        assertThat(fromDatabase.confirmationId).isEqualTo(confirmationId);
        assertThat(fromDatabase.confirmationKind)
                .isEqualTo(ConfirmationService.PendingOperation.Kind.CANCEL_APPOINTMENTS.name());
        assertThat(fromDatabase.confirmationTargetIds).containsExactly("recover-a", "recover-b");

        // 重启：服务内存里那份没了，这一轮只能从库里读。
        sessions().clear();
        AgentTurnResponse done = service.confirm(conversationId, true, confirmationId);

        assertThat(done.reply()).as(done.reply()).contains("2条已确认预约已经取消");
        assertThat(confirmedAppointments()).isZero();
    }

    /**
     * 旧快照（没有类型、没有目标集合）里的那张卡：不执行、作废、请他重新确认。
     *
     * <p>快照是拿一张真卡改出来的——把这次才进快照的两个字段删掉，等价于上一个版本写下的那行。
     * 库里两条预约在卡片上，而旧快照只留得下 {@code pendingAppointmentId} 一条；要是谁把回填
     * 写成"按剩下的那条执行"，这里就会取消一条、留下一条，而且看不出来。
     */
    @Test
    void anOldSnapshotCannotExecuteEvenPartOfTheCardAndAsksForReconfirmation() {
        askForCancellationCard();
        seedAppointment("legacy-a", LocalDate.of(2026, 9, 10), "09:00");
        seedAppointment("legacy-b", LocalDate.of(2026, 9, 14), "14:00");

        String conversationId = service.start().conversationId();
        AgentTurnResponse card = service.chat(conversationId, "我想取消预约");
        String confirmationId = card.confirmation().confirmationId();
        assertThat(card.confirmation().title()).contains("2条");

        stripTheAuthorizedScope(conversationId);

        sessions().clear();
        AgentTurnResponse refused = service.confirm(conversationId, true, confirmationId);

        assertThat(refused.reply()).as(refused.reply()).contains("失效");
        assertThat(confirmedAppointments()).isEqualTo(2);
        // 也不许它停在等待确认上：那份凭据已经作废了，屏幕上那个按钮不会再亮。
        assertThat(refused.stage()).isNotEqualTo("AWAITING_CONFIRMATION");
    }

    // —— 工具方法 ——

    /** 把库里的快照改成"上一个版本写下的样子"：删掉这次才进快照的授权范围。 */
    private void stripTheAuthorizedScope(String conversationId) {
        String stateJson = jdbc.queryForObject(
                "SELECT state_json FROM conversation_sessions WHERE id=?", String.class, conversationId);
        try {
            ObjectNode state = (ObjectNode) json.readTree(stateJson);
            state.remove("confirmationKind");
            state.remove("confirmationTargetIds");
            jdbc.update("UPDATE conversation_sessions SET state_json=? WHERE id=?",
                    json.writeValueAsString(state), conversationId);
        } catch (Exception error) {
            throw new IllegalStateException("构造旧快照失败", error);
        }
        // 造完之后先自查一遍：库里那一行确实读不出授权范围了，否则下面的断言是假的。
        ConversationState legacy = conversations.find(conversationId).orElseThrow();
        assertThat(legacy.confirmationId).isNotBlank();
        assertThat(legacy.confirmationKind).isNull();
        assertThat(legacy.confirmationTargetIds).isNull();
    }

    private void askForCancellationCard() {
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(new PlannerDecision(
                PlannerActionType.CALL_CONFIRMATION_TOOL, "CANCEL_APPOINTMENT",
                "interaction.requestConfirmation", Map.of("scope", "ALL"),
                "好的，我再和您确认一下。", "FOLLOWUP_FLOW",
                facts("CANCEL_APPOINTMENT"), "MODEL_PLANNER"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, ConversationState> sessions() {
        return (Map<String, ConversationState>) ReflectionTestUtils.getField(service, "sessions");
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
                VALUES (?,?,'user-001','CONFIRMED',CURRENT_TIMESTAMP,'recovery-seed')
                """, id, slotId);
    }

    private int confirmedAppointments() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE status='CONFIRMED'", Integer.class);
    }

    private ExtractedFacts facts(String intent) {
        return new ExtractedFacts(intent, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }
}
