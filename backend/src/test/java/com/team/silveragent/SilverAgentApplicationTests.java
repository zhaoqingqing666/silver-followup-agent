package com.team.silveragent;

import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.application.TravelGuideService;
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

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:silver-agent-test;DB_CLOSE_DELAY=-1", "agent.model.enabled=false"})
class SilverAgentApplicationTests {
    @Autowired FollowupAgentService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired RuleFactExtractor extractor;
    @Autowired TravelGuideService travelGuides;
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

    @Test void duplicateAppointmentIsExplainedBeforeAnotherConfirmationCanBeCreated() {
        approve(prepare(false, false));
        // 模拟号源系统仍返回同一个时段，用来验证重复预约检查不是只依赖 available 标志。
        jdbc.update("UPDATE appointment_slots SET available=TRUE WHERE id='slot-0918-0900'");

        String id = service.start().conversationId();
        action(id, "SET_HOSPITAL", "h001");
        action(id, "SET_DEPARTMENT", "d001");
        action(id, "SET_DATE", "2026-09-18");
        action(id, "SELECT_SLOT", "slot-0918-0900");
        action(id, "SET_ALTERNATIVE", "true");
        action(id, "SET_COMPANION", "false");
        action(id, "SET_TRAVEL", "false");
        action(id, "SET_TRANSPORT", "打车");
        AgentTurnResponse duplicate = action(id, "SET_NOTIFY", "false");

        assertThat(duplicate.confirmation()).isNull();
        assertThat(duplicate.reply()).contains("已经有一条相同", "没有重复提交", "重新选择时间");
        assertThat(count("appointments")).isEqualTo(1);
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
        AgentTurnResponse restarted = action(turn.conversationId(), "CONTINUE", "");
        assertThat(restarted.stage()).isEqualTo("ASK_HOSPITAL");
        assertThat(restarted.task().active()).isTrue();
        assertThat(count("appointments")).isZero();
    }

    @Test void newConversationStartsAsGeneralChatAndCreatesTaskOnlyOnExplicitGoal() {
        AgentTurnResponse start = service.start();
        assertThat(start.task().status()).isEqualTo("NONE");
        assertThat(start.task().active()).isFalse();
        assertThat(start.plan()).isNull();
        assertThat(start.reply()).doesNotContain("请问想去哪家医院");

        AgentTurnResponse booking = service.chat(start.conversationId(), "我要预约复诊");
        assertThat(booking.stage()).isEqualTo("ASK_HOSPITAL");
        assertThat(booking.task().status()).isEqualTo("ACTIVE");
        assertThat(booking.reply()).contains("医院");
    }

    @Test void naturalStartAndResumePhrasesFollowTheAuthoritativeTaskState() {
        // 没有可恢复任务时，“继续”不能凭空创建预约，而应先澄清。
        AgentTurnResponse noTask = service.chat(service.start().conversationId(), "继续");
        assertThat(noTask.task().active()).isFalse();
        assertThat(noTask.reply()).contains("不确定", "开始复诊办理");

        // 已有任务时，不同自然表达都应恢复当前节点，而不是落入“我在听”的通用兜底。
        for (String phrase : List.of("我要办理", "开始办理", "继续", "接着来", "往下办吧", "继续弄吧", "回到刚才")) {
            AgentTurnResponse start = service.start();
            String id = start.conversationId();
            action(id, "CONTINUE", "");

            AgentTurnResponse resumed = service.chat(id, phrase);
            assertThat(resumed.stage()).as(phrase).isEqualTo("ASK_HOSPITAL");
            assertThat(resumed.task().status()).as(phrase).isEqualTo("ACTIVE");
            assertThat(resumed.reply()).as(phrase).contains("医院").doesNotContain("我在听");
        }
    }

    @Test void unrelatedConversationPausesTaskWithoutLosingItsStage() {
        AgentTurnResponse start = service.start();
        action(start.conversationId(), "CONTINUE", "");

        AgentTurnResponse chat = service.chat(start.conversationId(), "我还有件别的事情想说");
        assertThat(chat.stage()).isEqualTo("ASK_HOSPITAL");
        assertThat(chat.task().status()).isEqualTo("PAUSED");
        assertThat(chat.reply()).doesNotContain("请告诉我就诊医院");

        AgentTurnResponse resumed = action(start.conversationId(), "RETURN_TO_FLOW", "");
        assertThat(resumed.stage()).isEqualTo("ASK_HOSPITAL");
        assertThat(resumed.task().status()).isEqualTo("ACTIVE");
    }

    @Test void cancelledTaskStillAllowsSupportiveConversation() {
        String id = service.start().conversationId();
        action(id, "CANCEL_TASK", "");

        AgentTurnResponse tired = service.chat(id, "我感觉好累");
        assertThat(tired.stage()).isEqualTo("CANCELLED");
        assertThat(tired.reply()).doesNotContain("原来的办理进度已经保留")
                .contains("累", "不会提交预约");
        assertThat(tired.quickReplies()).extracting(AgentTurnResponse.QuickReply::action)
                .contains("NEW_BOOKING", "OPEN_TASKS");

        AgentTurnResponse chat = service.chat(id, "我可以和你聊天吗");
        assertThat(chat.stage()).isEqualTo("CANCELLED");
        assertThat(chat.reply()).contains("可以");
        assertThat(count("appointments")).isZero();
    }

    @Test void supportiveConversationKeepsTheInterruptedBusinessStep() {
        AgentTurnResponse start = service.start();
        AgentTurnResponse support = service.chat(start.conversationId(), "我有点害怕一个人去医院");
        assertThat(support.stage()).isEqualTo("ASK_HOSPITAL");
        assertThat(support.reply()).contains("担心");

        AgentTurnResponse resumed = action(start.conversationId(), "RETURN_TO_FLOW", "");
        assertThat(resumed.stage()).isEqualTo("ASK_HOSPITAL");
        assertThat(resumed.reply()).contains("医院");
    }

    @Test void ambiguousDiscomfortIsClarifiedWithoutChangingTheWorkflow() {
        AgentTurnResponse start = service.start();
        AgentTurnResponse clarified = service.chat(start.conversationId(), "我感觉现在心里不舒服");
        assertThat(clarified.stage()).isEqualTo("ASK_HOSPITAL");
        assertThat(clarified.reply()).contains("身体不舒服", "心情难受", "120");
        assertThat(count("appointments")).isZero();
    }

    @Test void colloquialChestPainPausesTheWorkflowBeforeNormalRouting() {
        for (String message : List.of("我心口疼怎么办", "我感觉胸口疼", "胸闷得厉害")) {
            AgentTurnResponse start = service.start();
            AgentTurnResponse result = service.chat(start.conversationId(), message);
            assertThat(result.stage()).isEqualTo("EMERGENCY_PAUSED");
            assertThat(result.reply()).contains("120", "暂停").doesNotContain("请告诉我就诊医院");
        }
        assertThat(count("appointments")).isZero();
    }

    @Test void processQuestionUsesTheCareGuideWithoutLosingCurrentStep() {
        AgentTurnResponse start = service.start();
        AgentTurnResponse result = service.chat(start.conversationId(), "办理这个是个什么流程啊");

        assertThat(result.stage()).isEqualTo("ASK_HOSPITAL");
        assertThat(result.reply()).contains("流程", "明确确认").doesNotContain("请告诉我就诊医院");
        assertThat(result.toolTraces()).anyMatch(trace -> trace.toolName().equals("careGuide.search") && trace.success());
    }

    @Test void combinedProcessAndMaterialsQuestionUsesBothReadTools() {
        AgentTurnResponse start = service.start();
        AgentTurnResponse result = service.chat(start.conversationId(), "复诊的流程是什么啊，还有要带哪些材料啊");

        assertThat(result.reply()).contains("身份证", "流程", "医院");
        assertThat(result.toolTraces()).extracting(AgentTurnResponse.ToolTrace::toolName)
                .contains("careGuide.search", "material.generateChecklist");
    }

    @Test void colloquialCancellationStillRequiresExplicitConfirmation() {
        AgentTurnResponse done = approve(prepare(false, false));
        AgentTurnResponse cancel = service.chat(done.conversationId(), "我想退掉之前的预约");

        assertThat(cancel.stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(cancel.confirmation()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT status FROM appointments", String.class)).isEqualTo("CONFIRMED");
    }

    @Test void stoppingCurrentDraftDoesNotBecomeExistingAppointmentCancellation() {
        AgentTurnResponse start = service.start();
        AgentTurnResponse stopped = service.chat(start.conversationId(), "我不要预约了");

        assertThat(stopped.stage()).isEqualTo("CANCELLED");
        assertThat(stopped.confirmation()).isNull();
        assertThat(stopped.reply()).contains("停止", "未提交");
    }

    @Test void nonUrgentPhysicalDiscomfortUsesMedicalBoundaryInsteadOfCollectingHistory() {
        AgentTurnResponse start = service.start();
        AgentTurnResponse result = service.chat(start.conversationId(), "我感觉自己的腿不舒服");

        assertThat(result.stage()).isEqualTo("ASK_HOSPITAL");
        assertThat(result.reply()).contains("不能诊断", "医生")
                .doesNotContain("年龄", "既往病史", "请告诉我就诊医院");
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

    @Test void confirmedAppointmentProvidesRouteAndClinicRoomGuide() {
        AgentTurnResponse done = approve(prepare(false, false));
        var guide = travelGuides.forAppointment("user-001", done.result().appointmentId());

        assertThat(guide.route().durationMinutes()).isPositive();
        assertThat(guide.route().steps()).isNotEmpty();
        assertThat(guide.route().polyline()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(guide.facility().building()).isEqualTo("门诊楼");
        assertThat(guide.facility().floor()).isEqualTo("三层");
        assertThat(guide.facility().room()).isEqualTo("308诊室");
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

    @Test void inaccurateHospitalAndDepartmentNamesAreExplainedAndConfirmed() {
        String missingId = service.start().conversationId();
        service.chat(missingId, "办理复诊");
        AgentTurnResponse missing = service.chat(missingId, "我想去同济医院");
        assertThat(missing.stage()).isEqualTo("ASK_HOSPITAL");
        assertThat(missing.reply()).contains("同济医院", "没有找到", "市第一医院", "市人民医院");
        assertThat(missing.toolTraces()).anyMatch(t -> t.toolName().equals("catalog.queryHospitals"));

        String aliasId = service.start().conversationId();
        service.chat(aliasId, "办理复诊");
        AgentTurnResponse hospitalCandidate = service.chat(aliasId, "那就市一吧");
        assertThat(hospitalCandidate.stage()).isEqualTo("ASK_HOSPITAL");
        assertThat(hospitalCandidate.reply()).contains("市一", "市第一医院", "确认");
        assertThat(service.chat(aliasId, "是的").stage()).isEqualTo("ASK_DEPARTMENT");

        AgentTurnResponse departmentCandidate = service.chat(aliasId, "医生说可能是神内吧");
        assertThat(departmentCandidate.stage()).isEqualTo("ASK_DEPARTMENT");
        assertThat(departmentCandidate.reply()).contains("神内", "神经内科", "确认");
        assertThat(service.chat(aliasId, "对").stage()).isEqualTo("ASK_DATE");
    }

    @Test void ambiguousDepartmentDoesNotGuessAndAsksForClarification() {
        String id = service.start().conversationId();
        service.chat(id, "办理复诊");
        service.chat(id, "市第一医院");
        AgentTurnResponse ambiguous = service.chat(id, "内科");
        assertThat(ambiguous.stage()).isEqualTo("ASK_DEPARTMENT");
        assertThat(ambiguous.reply()).contains("多个科室", "心内科", "神经内科");
        assertThat(ambiguous.toolTraces()).anyMatch(t -> t.toolName().equals("catalog.queryDepartments"));
    }

    @Test void noSlotAndConflictAcceptNaturalRecoveryInstructions() {
        String noSlotId = service.start().conversationId();
        action(noSlotId, "SET_HOSPITAL", "h001");
        action(noSlotId, "SET_DEPARTMENT", "d001");
        AgentTurnResponse noSlot = action(noSlotId, "SET_DATE", "2026-09-19");
        assertThat(noSlot.stage()).isEqualTo("NO_SLOT");
        AgentTurnResponse nearby = service.chat(noSlotId, "那帮我看看附近几天");
        assertThat(nearby.stage()).isEqualTo("NO_SLOT");
        assertThat(nearby.reply()).contains("附近日期", "真实模拟号源");
        assertThat(nearby.quickReplies()).anyMatch(q -> q.action().equals("SELECT_SLOT"));

        String conflictId = service.start().conversationId();
        action(conflictId, "SET_HOSPITAL", "h001");
        action(conflictId, "SET_DEPARTMENT", "d001");
        action(conflictId, "SET_DATE", "2026-09-18");
        action(conflictId, "SELECT_SLOT", "slot-0918-1020");
        action(conflictId, "SET_ALTERNATIVE", "true");
        action(conflictId, "SET_COMPANION", "false");
        action(conflictId, "SET_TRAVEL", "false");
        action(conflictId, "SET_TRANSPORT", "家属开车");
        AgentTurnResponse conflict = action(conflictId, "SET_NOTIFY", "false");
        assertThat(conflict.stage()).isEqualTo("CONFLICT");
        AgentTurnResponse kept = service.chat(conflictId, "还是这个时间吧");
        assertThat(kept.stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(kept.confirmation()).isNotNull();
        assertThat(count("appointments")).isZero();
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
