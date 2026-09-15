package com.team.silveragent;

import com.team.silveragent.application.BusinessClock;
import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.application.travel.TravelGuideService;
import com.team.silveragent.agent.RuleFactExtractor;
import com.team.silveragent.agent.AgentContext;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.infrastructure.mock.MockScheduleTool;
import com.team.silveragent.infrastructure.persistence.RollingAppointmentSlotInitializer;
import com.team.silveragent.support.DemoSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:silver-agent-test;DB_CLOSE_DELAY=-1", "agent.model.enabled=false"})
class SilverAgentApplicationTests {
    /** 演示种子：体检那天（下周三，冲突与普通办理都在这一天）、当天没有号的周末、以及改期用的次日。 */
    private static final String DAY = DemoSeed.day(DemoSeed.checkupDay());
    private static final String CHINESE_DAY = DemoSeed.chineseDay(DemoSeed.checkupDay());
    private static final String SLOT = DemoSeed.morningSlot();
    private static final String CLASH_SLOT = DemoSeed.conflictingSlot();
    private static final String EMPTY_DAY = DemoSeed.day(DemoSeed.emptyDay());
    private static final String LATER_DAY = DemoSeed.day(DemoSeed.laterDay());

    @Autowired FollowupAgentService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired RuleFactExtractor extractor;
    @Autowired TravelGuideService travelGuides;
    @MockitoSpyBean MockScheduleTool schedule;
    /** 业务时钟可以拨：把「现在」放到当天几点，就能确定性地演「今天已经过去」这类场景。 */
    @MockitoSpyBean BusinessClock clock;

    /** 手工为「今天」摆的号源都带这个前缀，{@link #resetData()} 负责清干净。 */
    private static final String TODAY_SLOT_PREFIX = "t-today-";

    @BeforeEach void resetData() {
        for (String table : List.of("appointments", "reminders", "family_notifications")) jdbc.update("DELETE FROM " + table);
        // 周六本来就没有号源（滚动初始化刻意留出的空档），不用再手动关掉某一天。
        // booked 一并归零：available 现在是派生位（booked < capacity）。
        jdbc.update("UPDATE appointment_slots SET booked=0, available=TRUE");
        jdbc.update("DELETE FROM appointment_slots WHERE id LIKE ?", TODAY_SLOT_PREFIX + "%");
        reset(schedule);
        reset(clock);
    }

    AgentTurnResponse action(String id, String action, String value) { return service.act(id, action, value, action); }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    AgentTurnResponse prepare(boolean travel, boolean notify) {
        String id = service.start().conversationId();
        action(id, "SET_HOSPITAL", "h001");
        action(id, "SET_DEPARTMENT", "d001");
        action(id, "SET_DATE", DAY);
        action(id, "SELECT_SLOT", SLOT);
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
        assertThat(turn.confirmation().operations().toString())
                .contains("市第一医院", "心内科", CHINESE_DAY, "需要陪同");
        assertThat(approve(turn).stage()).isEqualTo("COMPLETED");
        approve(turn);
        assertThat(count("appointments")).isEqualTo(1);
        assertThat(count("reminders")).isEqualTo(2);
        assertThat(count("family_notifications")).isEqualTo(1);
    }

    @Test void duplicateAppointmentIsExplainedBeforeAnotherConfirmationCanBeCreated() {
        approve(prepare(false, false));
        // 模拟号源系统仍返回同一个时段，用来验证重复预约检查不是只依赖 available 标志。
        jdbc.update("UPDATE appointment_slots SET booked=0, available=TRUE WHERE id=?", SLOT);

        String id = service.start().conversationId();
        action(id, "SET_HOSPITAL", "h001");
        action(id, "SET_DEPARTMENT", "d001");
        action(id, "SET_DATE", DAY);
        action(id, "SELECT_SLOT", SLOT);
        action(id, "SET_ALTERNATIVE", "true");
        action(id, "SET_COMPANION", "false");
        action(id, "SET_TRAVEL", "false");
        action(id, "SET_TRANSPORT", "打车");
        AgentTurnResponse duplicate = action(id, "SET_NOTIFY", "false");

        assertThat(duplicate.confirmation()).isNull();
        // 冲突口径自 2026-09-14 起是「同一就诊人 + 同一天 + 同一时刻」，不再比医院和科室。
        // 文案自 2026-09-15 起统一成「〈今天 / 日期〉这个时段您已经预约过了」——与日程冲突那句
        // 「这个时间与您的…冲突」分得开：一个是「您自己已经约了」，一个是「您那天有别的事」。
        assertThat(duplicate.reply()).contains("这个时段您已经预约过了", "没有重复提交", "重新选择时间");
        assertThat(count("appointments")).isEqualTo(1);
    }

    /**
     * 今天已经过去的时段：必须说「已经过了」，不能说成「没有号」，更不能说成「约满」。
     *
     * <p>钉的是演示里真实出过的一幕——老人问 9月15日（就是当天），助手答「只有下午有空位」，
     * 追问「啊上午没有吗」，答的是「上午已经约满了」。可那一格根本没被别人占，只是当天上午的
     * 时间已经走掉了：当时容器时钟走 UTC，应用以为才下午两点多，上午整片被判成「已过去」。
     * 而权威草稿里只写了「共查到 N 个可预约时段、下午最早…」，理由空着，就被补成了「约满」——
     * 「约满」两个字在全仓代码里一个都没有。
     *
     * <p>「今天」由 {@link BusinessClock} 定，所以这里直接把钟拨到 15:00：当天上午两格必然过去、
     * 下午还有一格。当天 09:00 与 15:30 各手工摆一条，是因为演示的「当天」多半不是心内科的放号日，
     * 不手工插就造不出这个场景。
     */
    @Test void aMorningThatAlreadyPassedIsExplainedAsElapsedNotAsFullyBooked() {
        LocalDate today = clock.today();
        slotTodayAt(LocalTime.of(9, 0));
        slotTodayAt(LocalTime.of(15, 30));
        doReturn(LocalTime.of(15, 0)).when(clock).now();
        doReturn(LocalDateTime.of(today, LocalTime.of(15, 0))).when(clock).nowDateTime();

        String id = service.start().conversationId();
        action(id, "SET_HOSPITAL", "h001");
        action(id, "SET_DEPARTMENT", DemoSeed.CARDIOLOGY);
        AgentTurnResponse listed = action(id, "SET_DATE", today.toString());

        assertThat(listed.reply())
                .as("当天上午没有可约时段时，理由要当场说出来，问句也不能还问「上午还是下午」")
                .contains("今天上午", "已经过了", "您看下午可以吗")
                .doesNotContain("约满", "您想上午去还是下午去");
        assertThat(action(id, "SET_PERIOD", "MORNING").reply())
                .as("老人追问「上午没有吗」时给的是同一个理由，不能退回「暂时没有号」")
                .contains("已经过了", "还有号")
                .doesNotContain("暂时没有号", "约满");
    }

    /** 当天一个可约时段都不剩、原因就是「今天已经过了」：说是过期，不能含糊成「暂无号源」。 */
    @Test void aWholeDayThatAlreadyPassedIsExplainedWithTheElapsedTimes() {
        LocalDate today = clock.today();
        slotTodayAt(LocalTime.of(9, 0));
        doReturn(LocalTime.of(20, 0)).when(clock).now();
        doReturn(LocalDateTime.of(today, LocalTime.of(20, 0))).when(clock).nowDateTime();

        String id = service.start().conversationId();
        action(id, "SET_HOSPITAL", "h001");
        action(id, "SET_DEPARTMENT", DemoSeed.CARDIOLOGY);
        AgentTurnResponse turn = action(id, "SET_DATE", today.toString());

        assertThat(turn.reply())
                .as("说清「今天是几号、哪些时段过了」，而不是一句含糊的「暂无号源」")
                .contains("今天", "的号都已经过了", "上午9点")
                .doesNotContain("约满", "暂无号源");
    }

    /** 手工在「今天」摆一条 09:00 的号源；id 带专用前缀，{@link #resetData()} 负责清干净。 */
    private void slotTodayAt(LocalTime time) {
        String id = TODAY_SLOT_PREFIX + time.toString().replace(":", "");
        jdbc.update("""
                INSERT INTO appointment_slots
                    (id,hospital_id,hospital_name,department,appointment_date,appointment_time,
                     available,doctor_id,slot_type,department_id,fee_cents,capacity,booked)
                SELECT ?,?,?,?,?,?,TRUE,?,?,?,?,?,0
                WHERE NOT EXISTS (SELECT 1 FROM appointment_slots WHERE id=?)
                """, id, "h001", "市第一医院（模拟）", "心内科", clock.today(), time,
                "doc-d001-01", RollingAppointmentSlotInitializer.SLOT_TYPE_EXPERT, DemoSeed.CARDIOLOGY,
                RollingAppointmentSlotInitializer.EXPERT_FEE_CENTS,
                RollingAppointmentSlotInitializer.EXPERT_CAPACITY, id);
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

    /**
     * 越界回答不能打断正在办的事：确认卡和它的 confirmationId 都得原样还在，
     * 老人问完一句药，接着按「确认办理」仍然办得成。
     *
     * <p>与 {@link #emergencyInvalidatesPendingConfirmationAndContinue} 正好是一对：
     * 紧急情况必须作废待确认操作，问病问药不该。
     */
    @Test void medicalBoundaryKeepsThePendingConfirmationAlive() {
        AgentTurnResponse turn = prepare(true, true);
        AgentTurnResponse boundary = service.chat(turn.conversationId(), "这个药量是不是该减半？");

        assertThat(boundary.stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(boundary.notice().type()).isEqualTo(AgentTurnResponse.Notice.MEDICAL_BOUNDARY);
        assertThat(boundary.confirmation()).isNotNull();
        assertThat(boundary.confirmation().confirmationId()).isEqualTo(turn.confirmation().confirmationId());
        assertThat(boundary.confirmation().operations()).containsExactlyElementsOf(turn.confirmation().operations());
        // 越界只是一句问答，不写库；紧接着确认，这次办理照常落地。
        assertThat(count("appointments")).isZero();
        assertThat(approve(boundary).stage()).isEqualTo("COMPLETED");
        assertThat(count("appointments")).isEqualTo(1);
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
        // 越界回复和普通回复一样是聊天气泡，前端靠这个提示块才把它显示成一块单独的提示卡。
        assertThat(result.notice()).isNotNull();
        assertThat(result.notice().type()).isEqualTo(AgentTurnResponse.Notice.MEDICAL_BOUNDARY);
        assertThat(result.notice().title()).isNotBlank();
        // 提示卡的正文不是回复的复制：回复照常进对话记录，卡上只补一句「为什么不一样」。
        assertThat(result.notice().message()).doesNotContain("不能诊断");
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
        AgentTurnResponse turn = action(id, "SET_DATE", EMPTY_DAY);
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
        AgentTurnResponse noSlot = action(noSlotId, "SET_DATE", EMPTY_DAY);
        assertThat(noSlot.stage()).isEqualTo("NO_SLOT");
        AgentTurnResponse nearby = service.chat(noSlotId, "那帮我看看附近几天");
        assertThat(nearby.stage()).isEqualTo("NO_SLOT");
        assertThat(nearby.reply()).contains("附近日期", "真实模拟号源");
        assertThat(nearby.quickReplies()).anyMatch(q -> q.action().equals("SELECT_SLOT"));

        String conflictId = service.start().conversationId();
        action(conflictId, "SET_HOSPITAL", "h001");
        action(conflictId, "SET_DEPARTMENT", "d001");
        action(conflictId, "SET_DATE", DAY);
        action(conflictId, "SELECT_SLOT", CLASH_SLOT);
        action(conflictId, "SET_ALTERNATIVE", "true");
        action(conflictId, "SET_COMPANION", "false");
        action(conflictId, "SET_TRAVEL", "false");
        action(conflictId, "SET_TRANSPORT", "家属开车");
        AgentTurnResponse conflict = action(conflictId, "SET_NOTIFY", "false");
        assertThat(conflict.stage()).isEqualTo("CONFLICT");
        // 前端每页只渲染 3 个候选：保留冲突是最后一道防线，不能被挤到第二页。
        assertThat(conflict.quickReplies()).hasSize(3);
        assertThat(conflict.quickReplies().get(2).action()).isEqualTo("KEEP_CONFLICT");
        AgentTurnResponse kept = service.chat(conflictId, "还是这个时间吧");
        assertThat(kept.stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(kept.confirmation()).isNotNull();
        // 用户选完「仍保留」之后，确认卡必须把冲突本身摆出来，不能只说“请核对本次实际执行内容”
        assertThat(kept.confirmation().operations())
                .anyMatch(line -> line.contains("已知冲突") && line.contains("社区体检") && line.contains("已选择保留"));
        assertThat(count("appointments")).isZero();
    }

    @Test void selectingAConflictingSlotIsBlockedBeforeCompanionOrTravelIsAsked() {
        String id = service.start().conversationId();
        action(id, "SET_HOSPITAL", "h001");
        action(id, "SET_DEPARTMENT", "d001");
        action(id, "SET_DATE", DAY);

        // 号源一锁定就得说清「这个时间约不上」：陪同、出行、交通、通知一个都还没问过，
        // 不能让老人把这一整轮答完才被告知，那些答案全白答。
        AgentTurnResponse blocked = action(id, "SELECT_SLOT", CLASH_SLOT);
        assertThat(blocked.stage()).isEqualTo("CONFLICT");
        assertThat(blocked.reply()).contains("社区体检");
        assertThat(blocked.quickReplies()).hasSize(3);
        assertThat(blocked.quickReplies().get(2).action()).isEqualTo("KEEP_CONFLICT");

        // 老人选择保留之后接着问没问完的那些，而不是回头再弹一次同一个冲突——
        // 那样他永远走不到确认卡。冲突本身留在卡上，作为最后一道提醒。
        AgentTurnResponse kept = action(id, "KEEP_CONFLICT", "");
        assertThat(kept.stage()).as(kept.reply()).isEqualTo("ASK_COMPANION");
        action(id, "SET_COMPANION", "false");
        action(id, "SET_TRAVEL", "false");
        action(id, "SET_TRANSPORT", "家属开车");
        AgentTurnResponse plan = action(id, "SET_NOTIFY", "false");

        assertThat(plan.stage()).as(plan.reply()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(plan.reply()).doesNotContain("社区体检");
        assertThat(plan.confirmation().operations())
                .anyMatch(line -> line.contains("已知冲突") && line.contains("社区体检") && line.contains("已选择保留"));
        assertThat(count("appointments")).isZero();
    }

    @Test void changingTimeClearsAnAcknowledgedConflictFromTheCard() {
        String id = service.start().conversationId();
        action(id, "SET_HOSPITAL", "h001");
        action(id, "SET_DEPARTMENT", "d001");
        action(id, "SET_DATE", DAY);
        action(id, "SELECT_SLOT", CLASH_SLOT);
        action(id, "SET_ALTERNATIVE", "true");
        action(id, "SET_COMPANION", "false");
        action(id, "SET_TRAVEL", "false");
        action(id, "SET_TRANSPORT", "家属开车");
        action(id, "SET_NOTIFY", "false");
        action(id, "KEEP_CONFLICT", "");

        // 改到不冲突的那一格再走一遍日程检查：冲突已经解决了，确认卡不能再挂着它
        action(id, "SELECT_SLOT", SLOT);
        AgentTurnResponse revised = action(id, "START_PLAN", "");

        assertThat(revised.stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(revised.confirmation().operations()).noneMatch(line -> line.contains("已知冲突"));
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
        action(id, "SET_DATE", DAY);
        action(id, "SELECT_SLOT", CLASH_SLOT);
        AgentTurnResponse conflict = action(id, "START_PLAN", "");
        assertThat(conflict.stage()).isEqualTo("CONFLICT");
        AgentTurnResponse revised = action(id, "KEEP_CONFLICT", "");
        assertThat(jdbc.queryForObject("SELECT slot_id FROM appointments", String.class)).isEqualTo(SLOT);
        assertThat(approve(revised).stage()).isEqualTo("COMPLETED");
        assertThat(count("appointments")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT slot_id FROM appointments", String.class)).isEqualTo(CLASH_SLOT);
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
        // 后六句是「服务越界」场景要演的句子：都不带“用药/诊断”这类现成动词，
        // 靠“症状/药物/报告名词 + 疑问语气”判出来。
        for (String message : List.of("是不是得了什么病", "推荐药", "药量加量", "检查结果", "治疗方案",
                "我血压有点高，要不要紧？", "这个药还能继续吃吗？", "阿司匹林一天吃几片？",
                "帮我看看这个化验单", "我是不是该住院？", "9月18日，我最近头晕是不是血压高了")) {
            AgentTurnResponse boundary = service.chat(id, message);
            assertThat(boundary.reply()).as(message).contains("不能诊断");
            assertThat(boundary.notice()).as(message).isNotNull();
            assertThat(boundary.notice().type()).as(message).isEqualTo(AgentTurnResponse.Notice.MEDICAL_BOUNDARY);
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
        action(id, "SET_DATE", DAY);
        action(id, "SELECT_SLOT", CLASH_SLOT);
        action(id, "START_PLAN", "");
        AgentTurnResponse revised = action(id, "KEEP_CONFLICT", "");
        // 把新号源整班约满（名额口径），模拟「改期那一刻号刚好被抢光」。
        jdbc.update("UPDATE appointment_slots SET booked=capacity, available=FALSE WHERE id=?", CLASH_SLOT);
        assertThat(approve(revised).stage()).isEqualTo("TOOL_ERROR");
        assertThat(jdbc.queryForObject("SELECT slot_id FROM appointments", String.class)).isEqualTo(SLOT);
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
        AgentTurnResponse changed = service.chat(turn.conversationId(), "改成" + LATER_DAY + "下午");
        assertThat(changed.plan().date()).isEqualTo(DemoSeed.chineseDay(DemoSeed.laterDay()));
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

}
