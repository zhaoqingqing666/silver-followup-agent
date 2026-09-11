package com.team.silveragent;

import com.team.silveragent.application.DemoScenarioService;
import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.agent.RuleFactExtractor;
import com.team.silveragent.agent.AgentContext;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.model.DemoScenarioResponse;
import com.team.silveragent.infrastructure.mock.MockScheduleTool;
import com.team.silveragent.infrastructure.persistence.RollingAppointmentSlotInitializer;
import com.team.silveragent.infrastructure.persistence.RollingUserScheduleInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 测试不写死具体日期：号源和用户已有日程由 RollingAppointmentSlotInitializer /
 * RollingUserScheduleInitializer 按“今天”生成，这里用同样的规则推算日期与号源编号，
 * 否则用例会随着演示日期一起过期。
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:silver-agent-test;DB_CLOSE_DELAY=-1", "agent.llm.enabled=false"})
class SilverAgentApplicationTests {
    private static final DateTimeFormatter ID_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("yyyy年M月d日");

    @Autowired FollowupAgentService service;
    @Autowired DemoScenarioService scenarios;
    @Autowired JdbcTemplate jdbc;
    @Autowired RuleFactExtractor extractor;
    @Autowired RollingAppointmentSlotInitializer slots;
    @Autowired RollingUserScheduleInitializer schedules;
    @MockitoSpyBean MockScheduleTool schedule;

    @BeforeEach void resetData() {
        for (String table : List.of("appointments", "reminders", "family_notifications")) jdbc.update("DELETE FROM " + table);
        jdbc.update("UPDATE appointment_slots SET available=TRUE");
        // 号源本应由 RollingAppointmentSlotInitializer 在上下文启动时生成；这里兜底，
        // 让“启动器没跑”表现为一条清晰的失败原因，而不是所有用例一起报号源为空。
        if (jdbc.queryForObject("SELECT COUNT(*) FROM appointment_slots WHERE id LIKE 'r-%'", Integer.class) == 0) {
            slots.seed();
        }
        // 日程种子只在启动时写过一次，而 conflictDate() 每个用例都按“今天”重算。
        // 跨零点运行时两者会错开，这里重新落一次，保证冲突用例始终撞得上。
        schedules.seed();
        reset(schedule);
    }

    AgentTurnResponse action(String id, String action, String value) { return service.act(id, action, value, action); }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    /** 正常办理：走到等待确认，全程使用滚动种子生成的号源，不依赖写死日期。 */
    AgentTurnResponse prepare(boolean travel, boolean notify) {
        String id = service.start().conversationId();
        LocalDate date = nextWeekday();
        action(id, "SET_HOSPITAL", "h001");
        action(id, "SET_DEPARTMENT", "d001");
        action(id, "SET_DATE", date.toString());
        action(id, "SET_ALTERNATIVE", "true");
        action(id, "SELECT_SLOT", slotId("d001", date, 9, 0));
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

    /** 走到“请检查当前计划”，且所选 10:30 号源与滚动种子“社区体检”冲突。 */
    String readyWithConflict() {
        String id = service.start().conversationId();
        LocalDate date = conflictDate();
        action(id, "SET_HOSPITAL", "h001");
        action(id, "SET_DEPARTMENT", "d001");
        action(id, "SET_DATE", date.toString());
        action(id, "SET_ALTERNATIVE", "true");
        action(id, "SELECT_SLOT", slotId("d001", date, 10, 30));
        action(id, "SET_COMPANION", "false");
        action(id, "SET_TRAVEL", "false");
        action(id, "SET_TRANSPORT", "打车");
        action(id, "SET_NOTIFY", "false");
        assertThat(action(id, "CONTINUE", "").stage()).isEqualTo("READY_TO_PLAN");
        return id;
    }

    @Test void normalFlowRequiresConfirmationAndDoesNotRepeatWrites() {
        AgentTurnResponse turn = prepare(true, true);
        assertThat(count("appointments")).isZero();
        assertThat(count("reminders")).isZero();
        assertThat(count("family_notifications")).isZero();
        assertThat(turn.confirmation().operations().toString())
                .contains("市第一医院", "心内科", String.valueOf(LocalDate.now().getYear()), "需要陪同");
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
                .when(schedule).createReminder(anyString(), anyString(), eq(FollowupAgentService.MATERIAL_REMINDER_TITLE), any());
        AgentTurnResponse partial = approve(turn);
        assertThat(partial.stage()).isEqualTo("PARTIAL");
        assertThat(count("appointments")).isEqualTo(1);
        assertThat(partial.result().reminderStatus()).contains("未完成");
        AgentTurnResponse retry = action(turn.conversationId(), "RETRY_EXECUTION", "");
        assertThat(approve(retry).stage()).isEqualTo("COMPLETED");
        assertThat(count("appointments")).isEqualTo(1);
        assertThat(count("reminders")).isEqualTo(2);
    }

    @Test void alternativeDateIsAskedBeforeSlotsAreQueried() {
        String id = service.start().conversationId();
        action(id, "SET_HOSPITAL", "h001");
        action(id, "SET_DEPARTMENT", "d001");
        AgentTurnResponse asked = action(id, "SET_DATE", nextWeekday().toString());
        assertThat(asked.stage()).isEqualTo("ASK_ALTERNATIVE");
        assertThat(asked.reply()).contains("前后几天");
        assertThat(asked.toolTraces()).noneMatch(t -> t.toolName().equals("appointment.querySlots"));
    }

    @Test void noSlotRespectsRefusalOfOtherDates() {
        String id = service.start().conversationId();
        action(id, "SET_HOSPITAL", "h001"); action(id, "SET_DEPARTMENT", "d001");
        action(id, "SET_ALTERNATIVE", "false");
        AgentTurnResponse turn = action(id, "SET_DATE", nextWeekend().toString());
        assertThat(turn.stage()).isEqualTo("NO_SLOT");
        assertThat(turn.quickReplies()).noneMatch(q -> q.action().equals("SELECT_SLOT"));
        assertThat(turn.toolTraces()).noneMatch(t -> t.toolName().equals("appointment.queryAlternatives"));
    }

    @Test void alternativesNeverFallOnTheRequestedDate() {
        String id = service.start().conversationId();
        action(id, "SET_HOSPITAL", "h001");
        action(id, "SET_DEPARTMENT", "d001");
        action(id, "SET_ALTERNATIVE", "true");
        LocalDate weekend = nextWeekend();
        AgentTurnResponse turn = action(id, "SET_DATE", weekend.toString());
        assertThat(turn.stage()).isEqualTo("NO_SLOT");
        assertThat(turn.quickReplies()).anyMatch(q -> q.action().equals("SELECT_SLOT"));
        for (AgentTurnResponse.QuickReply reply : turn.quickReplies()) {
            if (!reply.action().equals("SELECT_SLOT")) continue;
            assertThat(jdbc.queryForObject("SELECT appointment_date FROM appointment_slots WHERE id=?",
                    java.sql.Date.class, reply.value()).toLocalDate()).isAfter(weekend);
        }
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
        LocalDate conflictDate = conflictDate();
        action(id, "EDIT_BOOKING", "");
        action(id, "SET_DATE", conflictDate.toString());
        action(id, "SELECT_SLOT", slotId("d001", conflictDate, 10, 30));
        AgentTurnResponse conflict = action(id, "START_PLAN", "");
        assertThat(conflict.stage()).isEqualTo("CONFLICT");
        assertThat(conflict.reply()).contains("社区体检");
        assertThat(conflict.quickReplies()).extracting(AgentTurnResponse.QuickReply::action)
                .containsExactly("SELECT_SLOT", "CHANGE_DATE", "KEEP_CONFLICT");
        AgentTurnResponse revised = action(id, "KEEP_CONFLICT", "");
        assertThat(jdbc.queryForObject("SELECT slot_id FROM appointments", String.class))
                .isEqualTo(slotId("d001", nextWeekday(), 9, 0));
        assertThat(revised.confirmation().operations().toString()).contains("已知冲突", "社区体检");
        assertThat(approve(revised).stage()).isEqualTo("COMPLETED");
        assertThat(count("appointments")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT slot_id FROM appointments", String.class))
                .isEqualTo(slotId("d001", conflictDate, 10, 30));
    }

    @Test void naturalLanguageStartReachesConflictCheck() {
        assertThat(service.chat(readyWithConflict(), "好的").stage()).isEqualTo("CONFLICT");
    }

    @Test void startExecutionIntentAlsoReachesConflictCheck() {
        assertThat(service.chat(readyWithConflict(), "开始办理吧").stage()).isEqualTo("CONFLICT");
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

    @Test void commonMedicalQuestionsAreRefusedInsteadOfIgnored() {
        String id = service.start().conversationId();
        for (String message : List.of("我血压有点高，要不要紧？", "这个药还能继续吃吗？",
                "阿司匹林一天吃几片？", "帮我看看这个化验单", "我是不是该住院？",
                "9月18日，我最近头晕是不是血压高了")) {
            AgentTurnResponse turn = service.chat(id, message);
            assertThat(turn.notice()).as(message).isNotNull();
            assertThat(turn.notice().type()).as(message).isEqualTo(AgentTurnResponse.Notice.MEDICAL_BOUNDARY);
            assertThat(turn.reply()).as(message).contains("不能诊断");
            assertThat(turn.stage()).as(message).isEqualTo("ASK_HOSPITAL");
        }
        assertThat(count("appointments")).isZero();
        assertThat(service.chat(id, "市第一医院").stage()).isEqualTo("ASK_DEPARTMENT");
    }

    @Test void partialProgressSurvivesSessionReload() {
        AgentTurnResponse turn = prepare(true, false);
        doThrow(new IllegalStateException("模拟出发提醒失败")).doCallRealMethod()
                .when(schedule).createReminder(anyString(), anyString(), eq(FollowupAgentService.DEPARTURE_REMINDER_TITLE), any());
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
        LocalDate conflictDate = conflictDate();
        String newSlot = slotId("d001", conflictDate, 10, 30);
        action(id, "EDIT_BOOKING", "");
        action(id, "SET_DATE", conflictDate.toString());
        action(id, "SELECT_SLOT", newSlot);
        action(id, "START_PLAN", "");
        AgentTurnResponse revised = action(id, "KEEP_CONFLICT", "");
        jdbc.update("UPDATE appointment_slots SET available=FALSE WHERE id=?", newSlot);
        assertThat(approve(revised).stage()).isEqualTo("TOOL_ERROR");
        assertThat(jdbc.queryForObject("SELECT slot_id FROM appointments", String.class))
                .isEqualTo(slotId("d001", nextWeekday(), 9, 0));
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
        LocalDate target = nextWeekday().plusWeeks(1);
        AgentTurnResponse changed = service.chat(turn.conversationId(), "改成" + target + "下午");
        assertThat(changed.plan().date()).isEqualTo(target.format(DATE_LABEL));
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

    @Test
    void demoScenarioResetReturnsReproducibleSession() {
        DemoScenarioResponse response = scenarios.reset("conflict");
        assertThat(response.scenarioId()).isEqualTo("conflict");
        assertThat(response.steps()).isNotEmpty();
        assertThat(response.availableScenarios()).contains("normal", "no-slot", "conflict", "boundary");
        assertThat(response.turn().conversationId()).isNotBlank();
        assertThat(response.turn().stage()).isEqualTo("ASK_HOSPITAL");
        assertThat(count("appointments")).isZero();
        assertThat(jdbc.queryForObject("SELECT start_at FROM user_schedules WHERE id=?",
                java.sql.Timestamp.class, RollingUserScheduleInitializer.CONFLICT_SCHEDULE_ID)
                .toLocalDateTime().toLocalDate()).isEqualTo(conflictDate());
        assertThatThrownBy(() -> scenarios.reset("unknown")).isInstanceOf(IllegalArgumentException.class);
    }

    /** 与 RollingAppointmentSlotInitializer 的档位一致：下一个可预约的工作日。 */
    private LocalDate nextWeekday() {
        LocalDate date = LocalDate.now().plusDays(1);
        while (date.getDayOfWeek().getValue() >= 6) date = date.plusDays(1);
        return date;
    }

    /** 滚动种子不会为周末生成号源，用来复现“指定日期无号源”。 */
    private LocalDate nextWeekend() {
        LocalDate date = LocalDate.now().plusDays(1);
        while (date.getDayOfWeek().getValue() < 6) date = date.plusDays(1);
        return date;
    }

    /** 与 RollingUserScheduleInitializer 的“社区体检”日程同一天。 */
    private LocalDate conflictDate() {
        return LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.WEDNESDAY));
    }

    /** 与 RollingAppointmentSlotInitializer 相同的号源编号规则：r-<科室ID>-<yyyyMMdd>-<HHmm>。 */
    private String slotId(String departmentId, LocalDate date, int hour, int minute) {
        return "r-" + departmentId + "-" + date.format(ID_DATE) + "-" + String.format("%02d%02d", hour, minute);
    }
}
