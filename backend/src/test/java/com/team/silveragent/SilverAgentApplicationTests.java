package com.team.silveragent;

import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.agent.RuleFactExtractor;
import com.team.silveragent.agent.AgentContext;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.infrastructure.mock.MockScheduleTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:silver-agent-test;DB_CLOSE_DELAY=-1", "agent.llm.enabled=false"})
class SilverAgentApplicationTests {
    @Autowired FollowupAgentService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired RuleFactExtractor extractor;
    @MockitoSpyBean MockScheduleTool schedule;

    @BeforeEach void resetData() {
        for (String table : List.of("appointments", "reminders", "family_notifications")) jdbc.update("DELETE FROM " + table);
        jdbc.update("UPDATE appointment_slots SET available=TRUE");
        jdbc.update("UPDATE appointment_slots SET available=FALSE WHERE appointment_date='2026-09-19'");
        reset(schedule);
    }

    AgentTurnResponse action(String id, String action, String value) { return service.act(id, action, value, action); }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    AgentTurnResponse prepare(boolean travel, boolean notify) {
        String id = service.start().conversationId();
        action(id, "SET_HOSPITAL", "h001");
        action(id, "SET_DEPARTMENT", "d001");
        action(id, "SET_DATE", "2026-09-18");
        action(id, "SELECT_SLOT", "slot-0918-0900");
        action(id, "SET_ALTERNATIVE", "true");
        action(id, "SET_COMPANION", "true");
        action(id, "SET_TRAVEL", Boolean.toString(travel));
        action(id, "SET_TRANSPORT", "打车");
        action(id, "SET_NOTIFY", Boolean.toString(notify));
        if (notify) action(id, "SET_CONTACT", "family-001");
        AgentTurnResponse turn = action(id, "START_PLAN", "");
        assertThat(turn.stage()).as(turn.reply()).isEqualTo("AWAITING_CONFIRMATION");
        return turn;
    }
    AgentTurnResponse approve(AgentTurnResponse turn) {
        return service.confirm(turn.conversationId(), true, turn.confirmation().confirmationId());
    }

    @Test void normalFlowRequiresConfirmationAndDoesNotRepeatWrites() {
        AgentTurnResponse turn = prepare(true, true);
        assertThat(count("appointments")).isZero();
        assertThat(count("reminders")).isZero();
        assertThat(count("family_notifications")).isZero();
        assertThat(turn.confirmation().operations().toString()).contains("市第一医院", "心内科", "2026", "需要陪同");
        assertThat(approve(turn).stage()).isEqualTo("COMPLETED");
        approve(turn);
        assertThat(count("appointments")).isEqualTo(1);
        assertThat(count("reminders")).isEqualTo(2);
        assertThat(count("family_notifications")).isEqualTo(1);
    }

    @Test void emergencyInvalidatesPendingConfirmationAndContinue() {
        AgentTurnResponse turn = prepare(true, true);
        assertThat(service.chat(turn.conversationId(), "我胸痛，喘不上气").stage()).isEqualTo("EMERGENCY_PAUSED");
        approve(turn);
        action(turn.conversationId(), "CONTINUE", "");
        action(turn.conversationId(), "START_PLAN", "");
        assertThat(count("appointments")).isZero();
        assertThat(service.resume(turn.conversationId()).stage()).isEqualTo("EMERGENCY_PAUSED");
    }

    @Test void cancellationStopsOldActions() {
        AgentTurnResponse turn = prepare(false, false);
        action(turn.conversationId(), "CANCEL_TASK", "");
        approve(turn);
        assertThat(action(turn.conversationId(), "CONTINUE", "").stage()).isEqualTo("CANCELLED");
        assertThat(count("appointments")).isZero();
    }

    @Test void changesInvalidateConfirmationAndPreserveLatestPlan() {
        AgentTurnResponse turn = prepare(true, true);
        action(turn.conversationId(), "SET_NOTIFY", "false");
        approve(turn);
        assertThat(count("appointments")).isZero();
        AgentTurnResponse revised = action(turn.conversationId(), "START_PLAN", "");
        assertThat(revised.confirmation().operations()).contains("不通知家属");
        assertThat(approve(revised).stage()).isEqualTo("COMPLETED");
        assertThat(count("family_notifications")).isZero();
    }

    @Test void departureAdviceDoesNotRequireDepartureReminder() {
        AgentTurnResponse turn = prepare(false, false);
        AgentTurnResponse done = approve(turn);
        assertThat(done.result().departureTime()).matches("[0-9]{2}:[0-9]{2}");
        assertThat(count("reminders")).isEqualTo(1);
    }

    @Test void failedReminderCanBeRetriedWithoutAnotherBooking() {
        AgentTurnResponse turn = prepare(true, true);
        doThrow(new IllegalStateException("模拟提醒服务故障")).doCallRealMethod()
                .when(schedule).createReminder(anyString(), anyString(), eq("复诊材料准备提醒"), any());
        AgentTurnResponse partial = approve(turn);
        assertThat(partial.stage()).isEqualTo("PARTIAL");
        assertThat(count("appointments")).isEqualTo(1);
        assertThat(partial.result().reminderStatus()).contains("未完成");
        AgentTurnResponse retry = action(turn.conversationId(), "RETRY_EXECUTION", "");
        assertThat(approve(retry).stage()).isEqualTo("COMPLETED");
        assertThat(count("appointments")).isEqualTo(1);
        assertThat(count("reminders")).isEqualTo(2);
    }

    @Test void noSlotRespectsRefusalOfOtherDates() {
        String id = service.start().conversationId();
        action(id, "SET_HOSPITAL", "h001"); action(id, "SET_DEPARTMENT", "d001");
        action(id, "SET_ALTERNATIVE", "false");
        AgentTurnResponse turn = action(id, "SET_DATE", "2026-09-19");
        assertThat(turn.stage()).isEqualTo("NO_SLOT");
        assertThat(turn.quickReplies()).noneMatch(q -> q.action().equals("SELECT_SLOT"));
        assertThat(turn.toolTraces()).noneMatch(t -> t.toolName().equals("appointment.queryAlternatives"));
    }

    @Test void cancelBookingRequiresConfirmationAndDisablesReminders() {
        AgentTurnResponse done = approve(prepare(true, true));
        AgentTurnResponse cancel = action(done.conversationId(), "CANCEL_APPOINTMENT", "");
        assertThat(jdbc.queryForObject("SELECT status FROM appointments", String.class)).isEqualTo("CONFIRMED");
        approve(cancel);
        assertThat(jdbc.queryForObject("SELECT status FROM appointments", String.class)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reminders WHERE status='CREATED'", Integer.class)).isZero();
    }

    @Test void rescheduleKeepsOriginalUntilConfirmed() {
        AgentTurnResponse done = approve(prepare(false, false));
        String id = done.conversationId();
        action(id, "EDIT_BOOKING", "");
        action(id, "SET_DATE", "2026-09-18");
        action(id, "SELECT_SLOT", "slot-0918-1020");
        AgentTurnResponse conflict = action(id, "START_PLAN", "");
        assertThat(conflict.stage()).isEqualTo("CONFLICT");
        AgentTurnResponse revised = action(id, "KEEP_CONFLICT", "");
        assertThat(jdbc.queryForObject("SELECT slot_id FROM appointments", String.class)).isEqualTo("slot-0918-0900");
        assertThat(approve(revised).stage()).isEqualTo("COMPLETED");
        assertThat(count("appointments")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT slot_id FROM appointments", String.class)).isEqualTo("slot-0918-1020");
    }

    @Test void missingInformationCannotBeBypassed() {
        String id = service.start().conversationId();
        action(id, "KEEP_CONFLICT", ""); action(id, "START_PLAN", "");
        service.confirm(id, true, "fake");
        assertThat(count("appointments")).isZero();
    }

    @Test void understandsRelativeDatesAndAllMedicalBoundaries() {
        assertThat(extractor.extract("下周三", new AgentContext("ASK_DATE", "", LocalDate.of(2026,9,7), List.of())).date())
                .isEqualTo(LocalDate.of(2026,9,16));
        String id = service.start().conversationId();
        for (String message : List.of("是不是得了什么病", "推荐药", "药量加量", "检查结果", "治疗方案")) {
            assertThat(service.chat(id, message).reply()).contains("不能诊断");
        }
        assertThat(count("appointments")).isZero();
        assertThat(service.chat(id, "市第一医院").stage()).isEqualTo("ASK_DEPARTMENT");
    }

    @Test void partialProgressSurvivesSessionReload() {
        AgentTurnResponse turn = prepare(true, false);
        doThrow(new IllegalStateException("模拟出发提醒失败")).doCallRealMethod()
                .when(schedule).createReminder(anyString(), anyString(), eq("复诊出发提醒"), any());
        assertThat(approve(turn).stage()).isEqualTo("PARTIAL");
        ((java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(service, "sessions")).clear();
        assertThat(service.resume(turn.conversationId()).stage()).isEqualTo("PARTIAL");
        AgentTurnResponse retry = action(turn.conversationId(), "RETRY_EXECUTION", "");
        assertThat(approve(retry).stage()).isEqualTo("COMPLETED");
        assertThat(count("appointments")).isEqualTo(1);
        assertThat(count("reminders")).isEqualTo(2);
    }

    @Test void failedRescheduleKeepsOriginalBookingAndReminders() {
        AgentTurnResponse done = approve(prepare(true, false));
        String id = done.conversationId();
        action(id, "EDIT_BOOKING", "");
        action(id, "SET_DATE", "2026-09-18");
        action(id, "SELECT_SLOT", "slot-0918-1020");
        action(id, "START_PLAN", "");
        AgentTurnResponse revised = action(id, "KEEP_CONFLICT", "");
        jdbc.update("UPDATE appointment_slots SET available=FALSE WHERE id='slot-0918-1020'");
        assertThat(approve(revised).stage()).isEqualTo("TOOL_ERROR");
        assertThat(jdbc.queryForObject("SELECT slot_id FROM appointments", String.class)).isEqualTo("slot-0918-0900");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reminders WHERE status='CREATED'", Integer.class)).isEqualTo(2);
    }

    @Test void missingRouteHasFailureTraceAndCanRecover() {
        AgentTurnResponse turn = prepare(false, false);
        action(turn.conversationId(), "SET_TRANSPORT", "不存在的交通方式");
        AgentTurnResponse failed = action(turn.conversationId(), "START_PLAN", "");
        assertThat(failed.stage()).isEqualTo("TOOL_ERROR");
        assertThat(failed.toolTraces()).anyMatch(t -> t.toolName().equals("travel.plan") && !t.success());
        action(turn.conversationId(), "SET_TRANSPORT", "打车");
        assertThat(approve(action(turn.conversationId(), "START_PLAN", "")).stage()).isEqualTo("COMPLETED");
    }

    @Test void naturalLanguageDateChangeInvalidatesSelectedSlot() {
        AgentTurnResponse turn = prepare(false, false);
        AgentTurnResponse changed = service.chat(turn.conversationId(), "改成2026-09-20下午");
        assertThat(changed.plan().date()).contains("20");
        approve(turn);
        assertThat(count("appointments")).isZero();
    }

    @Test void concurrentConfirmationWritesOnce() throws Exception {
        AgentTurnResponse turn = prepare(true, true);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> approve(turn));
            var second = pool.submit(() -> approve(turn));
            first.get(); second.get();
            assertThat(count("appointments")).isEqualTo(1);
            assertThat(count("reminders")).isEqualTo(2);
            assertThat(count("family_notifications")).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void sideQueryCanReturnToInterruptedBookingFlow() {
        AgentTurnResponse turn = service.start();
        String id = turn.conversationId();
        service.act(id, "SET_HOSPITAL", "h002", "市人民医院");

        AgentTurnResponse queried = service.chat(id, "我的复诊时间是什么时候");
        assertThat(queried.quickReplies()).extracting(AgentTurnResponse.QuickReply::action)
                .contains("RESUME_INTERRUPTED");

        AgentTurnResponse resumed = service.act(id, "RESUME_INTERRUPTED", "", "继续刚才办理");
        assertThat(resumed.stage()).isEqualTo("ASK_DEPARTMENT");
        assertThat(resumed.reply()).contains("科室");
    }

    private LocalDate nextWeekday() {
        LocalDate date = LocalDate.now().plusDays(1);
        while (date.getDayOfWeek().getValue() >= 6) date = date.plusDays(1);
        return date;
    }

    private LocalDate nextWeekendWithoutSeed() {
        LocalDate date = LocalDate.now().plusDays(1);
        while (date.getDayOfWeek().getValue() < 6) date = date.plusDays(1);
        if (date.equals(LocalDate.of(2026, 9, 19))) date = date.plusDays(1);
        return date;
    }
}
