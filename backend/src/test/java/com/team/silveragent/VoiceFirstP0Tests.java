package com.team.silveragent;

import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.model.AgentTurnResponse.QuickReply;
import com.team.silveragent.domain.model.AgentTurnResponse.ResultCard;
import com.team.silveragent.domain.model.AgentTurnResponse.UiDirectiveType;
import com.team.silveragent.support.DemoSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 语音优先 P0：完整播报、自然语言开页、取消候选确定性解析、确认红线与紧急优先。 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:silver-agent-test;DB_CLOSE_DELAY=-1", "agent.model.enabled=false"})
class VoiceFirstP0Tests {
    /** 演示种子「下周三」的上午 09:00 与下午 15:30 两格，都不与「社区体检」撞车。 */
    private static final String DAY = DemoSeed.day(DemoSeed.checkupDay());
    private static final String MORNING_SLOT = DemoSeed.morningSlot();
    private static final String AFTERNOON_SLOT = DemoSeed.secondSlot();
    private static final String MORNING = DemoSeed.clock(DemoSeed.MORNING);
    private static final String AFTERNOON = DemoSeed.clock(DemoSeed.AFTERNOON);

    @Autowired FollowupAgentService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void resetData() {
        for (String table : List.of("appointments", "reminders", "family_notifications")) jdbc.update("DELETE FROM " + table);
        // 周六本来就没有号源（滚动初始化刻意留出的空档），不用再手动关掉某一天。
        jdbc.update("UPDATE appointment_slots SET available=TRUE");
    }

    private AgentTurnResponse action(String id, String action, String value) {
        return service.act(id, action, value, action);
    }

    private AgentTurnResponse confirm(AgentTurnResponse turn) {
        return service.confirm(turn.conversationId(), true, turn.confirmation().confirmationId());
    }

    private AgentTurnResponse book(String slotId) {
        return confirm(prepare(service.start().conversationId(), slotId));
    }

    private AgentTurnResponse prepare(String id, String slotId) {
        action(id, "SET_HOSPITAL", "h001");
        action(id, "SET_DEPARTMENT", "d001");
        action(id, "SET_DATE", DAY);
        action(id, "SELECT_SLOT", slotId);
        action(id, "SET_ALTERNATIVE", "true");
        action(id, "SET_COMPANION", "true");
        action(id, "SET_TRAVEL", "true");
        action(id, "SET_TRANSPORT", "打车");
        action(id, "SET_NOTIFY", "true");
        action(id, "SET_CONTACT", "family-001");
        AgentTurnResponse turn = action(id, "START_PLAN", "");
        assertThat(turn.stage()).as(turn.reply()).isEqualTo("AWAITING_CONFIRMATION");
        return turn;
    }

    private AgentTurnResponse book(String conversationId, String slotId) {
        return confirm(prepare(conversationId, slotId));
    }

    private int confirmedAppointments() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE status='CONFIRMED'", Integer.class);
    }

    // 1 & 2：完成路径的 reply 与 speechText 都完整覆盖 ResultCard 的权威事实，且不虚构路线类信息。
    @Test void completionNarratesEveryAuthoritativeFact() {
        AgentTurnResponse done = book(MORNING_SLOT);

        assertThat(done.stage()).isEqualTo("COMPLETED");
        ResultCard card = done.result();
        assertThat(card).isNotNull();
        String speech = done.speechText();
        assertThat(speech).isNotBlank();
        assertThat(done.reply()).isEqualTo(speech);

        for (String text : List.of(done.reply(), speech)) {
            assertThat(text).contains(card.date(), card.hospital(), card.department(), "上午9点", "请携带", "出发");
        }
        assertThat(speech).contains(card.materials().toArray(new String[0]));
        assertThat(speech).contains(card.reminderStatus(), card.familyStatus());
        assertThat(speech).contains("打开地图");
        // ResultCard 没有交通方式、耗时、距离、楼层和诊室，口播不得虚构。
        assertThat(speech).doesNotContain("分钟", "公里", "诊室", "预计", "楼");
    }

    @Test void naturalLanguageMapRequestOpensTravelPageWithTheEffectiveAppointment() {
        AgentTurnResponse done = book(MORNING_SLOT);
        AgentTurnResponse asked = service.chat(done.conversationId(), "我想看地图");

        assertThat(asked.uiDirective()).isNotNull();
        assertThat(asked.uiDirective().type()).isEqualTo(UiDirectiveType.OPEN_TRAVEL);
        assertThat(asked.uiDirective().appointmentId()).isEqualTo(done.result().appointmentId());
        assertThat(asked.quickReplies()).extracting(QuickReply::action).contains("OPEN_TRAVEL");
    }

    @Test void naturalLanguageInsideGuideRequestUsesTheInsideDirective() {
        AgentTurnResponse done = book(MORNING_SLOT);
        AgentTurnResponse asked = service.chat(done.conversationId(), "到医院后怎么走");

        assertThat(asked.uiDirective()).isNotNull();
        assertThat(asked.uiDirective().type()).isEqualTo(UiDirectiveType.SHOW_INSIDE_GUIDE);
        assertThat(asked.uiDirective().focus()).isEqualTo("inside");
        assertThat(asked.uiDirective().appointmentId()).isEqualTo(done.result().appointmentId());
    }

    @Test void pageRequestWithoutAnyAppointmentFallsBackToTextAndButtons() {
        String id = service.start().conversationId();
        AgentTurnResponse asked = service.chat(id, "我想看地图");

        assertThat(asked.uiDirective()).isNull();
        assertThat(asked.reply()).isNotBlank();
        assertThat(asked.quickReplies()).isNotEmpty();
    }

    // 完成播报最后问“需要我现在打开地图吗”，老人只回一个“要”也要能打开路线页；否定和寒暄不跳页。
    @Test void shortYesAfterCompletionOpensTheTravelPage() {
        AgentTurnResponse done = book(MORNING_SLOT);
        String id = done.conversationId();
        String appointmentId = done.result().appointmentId();

        AgentTurnResponse yes = service.chat(id, "要");
        assertThat(yes.uiDirective()).isNotNull();
        assertThat(yes.uiDirective().type()).isEqualTo(UiDirectiveType.OPEN_TRAVEL);
        assertThat(yes.uiDirective().appointmentId()).isEqualTo(appointmentId);

        for (String declined : List.of("先不用", "好的谢谢", "不要")) {
            assertThat(service.chat(id, declined).uiDirective()).as(declined).isNull();
        }
        assertThat(confirmedAppointments()).isEqualTo(1);
    }

    @Test void voiceCancellationPicksTheNearestCandidateWithoutExecutingIt() {
        book(MORNING_SLOT);
        AgentTurnResponse second = book(service.start().conversationId(), AFTERNOON_SLOT);
        String id = second.conversationId();

        AgentTurnResponse asked = service.chat(id, "我想取消预约");
        assertThat(asked.stage()).isNotEqualTo("AWAITING_CONFIRMATION");
        assertThat(asked.quickReplies()).extracting(QuickReply::action).contains("SELECT_APPOINTMENT_TO_CANCEL");

        AgentTurnResponse picked = service.chat(id, "最近的一次");
        assertThat(picked.stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(picked.confirmation()).isNotNull();
        assertThat(picked.confirmation().operations().toString())
                .contains(DemoSeed.chineseDay(DemoSeed.checkupDay()), MORNING, "市第一医院", "心内科")
                .doesNotContain(AFTERNOON);
        assertThat(confirmedAppointments()).isEqualTo(2);
    }

    @Test void voiceCancellationPicksTheEarliestAndTheAfternoonCandidate() {
        book(MORNING_SLOT);
        AgentTurnResponse second = book(service.start().conversationId(), AFTERNOON_SLOT);
        String id = second.conversationId();

        service.chat(id, "我想取消预约");
        AgentTurnResponse earliest = service.chat(id, "最早的一次");
        assertThat(earliest.confirmation().operations().toString()).contains(MORNING).doesNotContain(AFTERNOON);
        assertThat(confirmedAppointments()).isEqualTo(2);

        AgentTurnResponse afternoon = service.chat(id, "下午那个");
        assertThat(afternoon.confirmation().operations().toString()).contains(AFTERNOON).doesNotContain(MORNING);
        assertThat(confirmedAppointments()).isEqualTo(2);
    }

    // “市第一医院那条”里的“第一”不能被当成“取最早的一条”，两条都在这家医院时必须反问而不是猜。
    @Test void hospitalNameDoesNotTurnIntoAnEarliestSelection() {
        book(MORNING_SLOT);
        AgentTurnResponse second = book(service.start().conversationId(), AFTERNOON_SLOT);
        String id = second.conversationId();
        service.chat(id, "我想取消预约");

        AgentTurnResponse asked = service.chat(id, "市第一医院那条");
        assertThat(asked.stage()).isNotEqualTo("AWAITING_CONFIRMATION");
        assertThat(asked.reply()).contains("请选择");
        assertThat(confirmedAppointments()).isEqualTo(2);
    }

    @Test void vagueAgreementNeverExecutesBookingOrCancellation() {
        AgentTurnResponse turn = prepare(service.start().conversationId(), MORNING_SLOT);
        assertThat(service.chat(turn.conversationId(), "好的").stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(service.chat(turn.conversationId(), "继续").stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(confirmedAppointments()).isZero();

        AgentTurnResponse done = confirm(turn);
        AgentTurnResponse cancel = action(done.conversationId(), "CANCEL_APPOINTMENT", "");
        assertThat(cancel.stage()).isEqualTo("AWAITING_CONFIRMATION");
        service.chat(done.conversationId(), "好的");
        service.chat(done.conversationId(), "继续");
        assertThat(confirmedAppointments()).isEqualTo(1);
    }

    @Test void emergencyExpressionPreemptsPageRequestsAndBookingFlow() {
        AgentTurnResponse done = book(MORNING_SLOT);
        String id = service.start().conversationId();
        AgentTurnResponse emergency = service.chat(id, "我胸口疼，帮我打开地图");

        assertThat(emergency.stage()).isEqualTo("EMERGENCY_PAUSED");
        assertThat(emergency.reply()).contains("120");
        assertThat(emergency.uiDirective()).isNull();
        assertThat(emergency.quickReplies()).extracting(QuickReply::action).doesNotContain("OPEN_TRAVEL");

        // 紧急暂停期间即使明确要求看地图，也只给文字和按钮，不自动跳转页面，已有预约不受影响。
        AgentTurnResponse blocked = service.chat(id, "我想看地图");
        assertThat(blocked.stage()).isEqualTo("EMERGENCY_PAUSED");
        assertThat(blocked.uiDirective()).isNull();
        assertThat(confirmedAppointments()).isEqualTo(1);
        assertThat(done.result().appointmentId()).isNotBlank();
    }
}
