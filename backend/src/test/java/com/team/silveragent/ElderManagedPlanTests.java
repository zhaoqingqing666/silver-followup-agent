package com.team.silveragent;

import com.team.silveragent.application.AppointmentRecordStore;
import com.team.silveragent.application.care.CareBookingService;
import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.support.DemoSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 老人端感知“家属/志愿者代约计划”：开场主动告知 + 查看/取消/改期，改动自动通知安排者。
 * 复用真实代约（CareBookingService）先造一份 arranged_by 预约，再走老人对话漏斗管理。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-elder-plan;DB_CLOSE_DELAY=-1",
        "agent.llm.enabled=false"})
class ElderManagedPlanTests {

    /** 代约那天 = 演示的「下周三」；改期目标 = 次日，两天都有滚动号源。 */
    private static final String DAY = DemoSeed.day(DemoSeed.checkupDay());
    private static final String CHINESE_DAY = DemoSeed.chineseDay(DemoSeed.checkupDay());
    private static final String SLOT = DemoSeed.morningSlot();
    private static final String LATER_DAY = DemoSeed.day(DemoSeed.laterDay());
    private static final String LATER_SLOT = DemoSeed.laterDaySlot();

    @Autowired FollowupAgentService service;
    @Autowired CareBookingService booking;
    @Autowired AppointmentRecordStore records;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        for (String table : List.of("memos", "care_notifications", "family_notifications", "reminders", "appointments")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE appointment_slots SET available=TRUE");
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    /** 女儿小丽替 user-001 王阿姨代约一份 h001 心内科下周三的复诊。 */
    private String bookArranged() {
        AppointmentRecordStore.AppointmentView view = booking.book("user-f001", "user-001",
                new CareBookingService.BookingRequest(
                        "h001", "d001", DAY, SLOT, false, "打车"));
        assertThat(view.arrangedLabel()).isEqualTo("女儿 小丽");
        assertThat(view.arrangedBy()).isEqualTo("user-f001");
        return view.appointmentId();
    }

    @Test
    void startAnnouncesArrangedPlanAndCancelNotifiesArranger() {
        bookArranged();
        AgentTurnResponse start = service.start("user-001");
        assertThat(start.reply()).contains("女儿 小丽", "约好的复诊", "心内科");

        AgentTurnResponse overview = service.act(start.conversationId(), "VIEW_MANAGED", "", "查看这次安排");
        assertThat(overview.reply()).contains("医院科室", CHINESE_DAY, "就诊材料");

        // 先拒绝一次：预约仍在，仍只有代约时的 book 回执
        AgentTurnResponse declineTurn = service.act(start.conversationId(), "CANCEL_MANAGED", "", "取消这次预约");
        assertThat(declineTurn.stage()).isEqualTo("AWAITING_CONFIRMATION");
        service.confirm(declineTurn.conversationId(), false, declineTurn.confirmation().confirmationId());
        assertThat(jdbc.queryForObject("SELECT status FROM appointments", String.class)).isEqualTo("CONFIRMED");
        assertThat(count("care_notifications")).isEqualTo(1);

        // 再次取消并确认：预约取消、号源释放、通知安排者
        AgentTurnResponse confirmTurn = service.act(start.conversationId(), "CANCEL_MANAGED", "", "取消这次预约");
        AgentTurnResponse done = service.confirm(confirmTurn.conversationId(), true, confirmTurn.confirmation().confirmationId());
        assertThat(done.reply()).contains("已取消", "并已通知女儿 小丽");
        assertThat(jdbc.queryForObject("SELECT status FROM appointments", String.class)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reminders WHERE status='CREATED'", Integer.class)).isZero();

        List<String> notices = jdbc.query(
                "SELECT kind FROM care_notifications WHERE caregiver_id='user-f001' AND elder_user_id='user-001'",
                (rs, row) -> rs.getString(1));
        assertThat(notices).containsExactly("book", "cancel");
    }

    @Test
    void travelHelpAndEmergencyAutoNotifyArranger() {
        bookArranged();
        AgentTurnResponse start = service.start("user-001");

        service.act(start.conversationId(), "REQUEST_HELP", "", "途中需要帮助");
        List<String> helpKinds = jdbc.query(
                "SELECT kind FROM care_notifications WHERE caregiver_id='user-f001'",
                (rs, row) -> rs.getString(1));
        assertThat(helpKinds).containsExactly("book", "help");

        service.chat(start.conversationId(), "我胸痛，喘不上气");
        List<String> emergencyKinds = jdbc.query(
                "SELECT kind FROM care_notifications WHERE caregiver_id='user-f001'",
                (rs, row) -> rs.getString(1));
        assertThat(emergencyKinds).containsExactly("book", "help", "emergency");
    }

    @Test
    void rescheduleArrangedPlanNotifiesArrangerOnce() {
        bookArranged();
        AgentTurnResponse start = service.start("user-001");

        AgentTurnResponse reschedule = service.act(start.conversationId(), "RESCHEDULE_MANAGED", "", "临时改期");
        assertThat(reschedule.reply()).contains("原来的安排", "改到哪一天");
        assertThat(reschedule.stage()).isEqualTo("ASK_DATE");

        // 顺着漏斗改到第二天下午，确认后原预约同一条记录换 slot
        service.act(reschedule.conversationId(), "SET_DATE", LATER_DAY, "选择日期");
        service.act(reschedule.conversationId(), "SELECT_SLOT", LATER_SLOT, "选择时间");
        service.act(reschedule.conversationId(), "SET_ALTERNATIVE", "true", "可以换日期");
        service.act(reschedule.conversationId(), "SET_COMPANION", "false", "不需要陪同");
        service.act(reschedule.conversationId(), "SET_TRAVEL", "false", "不需要出行提醒");
        service.act(reschedule.conversationId(), "SET_TRANSPORT", "打车", "打车");
        service.act(reschedule.conversationId(), "SET_NOTIFY", "false", "不用通知家属");
        AgentTurnResponse plan = service.act(reschedule.conversationId(), "START_PLAN", "", "开始办理");
        assertThat(plan.stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(plan.confirmation().operations().toString()).contains("变更原预约");

        AgentTurnResponse done = service.confirm(plan.conversationId(), true, plan.confirmation().confirmationId());
        assertThat(done.stage()).isEqualTo("COMPLETED");

        assertThat(jdbc.queryForObject("SELECT appointment_date FROM appointment_slots WHERE id=?", String.class, LATER_SLOT))
                .isEqualTo(LATER_DAY);
        assertThat(jdbc.queryForObject("SELECT slot_id FROM appointments", String.class)).isEqualTo(LATER_SLOT);
        assertThat(jdbc.queryForObject("SELECT arranged_by FROM appointments", String.class)).isEqualTo("user-f001");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reminders WHERE status='CREATED'", Integer.class)).isEqualTo(1);

        List<String> kinds = jdbc.query(
                "SELECT kind FROM care_notifications WHERE caregiver_id='user-f001' AND elder_user_id='user-001'",
                (rs, row) -> rs.getString(1));
        assertThat(kinds).containsExactly("book", "reschedule");
    }

    private boolean hasAction(AgentTurnResponse turn, String action) {
        return turn.quickReplies().stream().anyMatch(q -> action.equals(q.action()));
    }

    private int activeMemoCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM memos WHERE status='ACTIVE'", Integer.class);
    }

    /** 代约开场下记完显式备忘：交回“查看/改期”入口，而不是反问去哪家医院。 */
    @Test
    void memoAtManagedOpeningReturnsToManagedEntryInsteadOfAskingHospital() {
        bookArranged();
        AgentTurnResponse start = service.start("user-001");
        assertThat(start.reply()).contains("约好的复诊");

        AgentTurnResponse reply = service.chat(start.conversationId(), "帮我记着，明天早上8点要空腹抽血");

        assertThat(reply.reply()).contains("已记下", "空腹抽血");
        assertThat(reply.reply()).doesNotContain("想去哪家医院");
        assertThat(reply.reply()).contains("仍在");
        assertThat(hasAction(reply, "VIEW_MANAGED")).isTrue();
        assertThat(hasAction(reply, "MANAGE_MANAGED")).isTrue();
        assertThat(hasAction(reply, "CONTINUE")).isTrue();
        assertThat(activeMemoCount()).isEqualTo(1);

        // 备忘后仍可正常进入“查看这次安排”看代约详情
        AgentTurnResponse overview = service.act(start.conversationId(), "VIEW_MANAGED", "", "查看这次安排");
        assertThat(overview.reply()).contains("医院科室", CHINESE_DAY);
    }

    /** 代约开场下隐式备忘走确认卡，确认后同样交回代约入口。 */
    @Test
    void implicitMemoAtManagedOpeningConfirmsThenReturnsToManagedEntry() {
        bookArranged();
        AgentTurnResponse start = service.start("user-001");

        AgentTurnResponse ask = service.chat(start.conversationId(), "明天早上8点要去抽血，最好空腹");
        assertThat(ask.stage()).isEqualTo("AWAITING_CONFIRMATION");

        AgentTurnResponse done = service.confirm(ask.conversationId(), true, ask.confirmation().confirmationId());
        assertThat(done.reply()).contains("已记下");
        assertThat(done.reply()).doesNotContain("想去哪家医院");
        assertThat(hasAction(done, "VIEW_MANAGED")).isTrue();
        assertThat(activeMemoCount()).isEqualTo(1);
    }

    /** 老人已转入“本人新预约”（点了“我另外想预约复诊”）后记备忘：仍留在本人漏斗问医院，不回代约入口。 */
    @Test
    void memoAfterPivotingToOwnBookingStillAsksHospital() {
        bookArranged();
        AgentTurnResponse start = service.start("user-001");

        AgentTurnResponse funnel = service.act(start.conversationId(), "CONTINUE", "", "我另外想预约复诊");
        assertThat(funnel.stage()).isEqualTo("ASK_HOSPITAL");
        assertThat(funnel.reply()).contains("医院");

        AgentTurnResponse reply = service.chat(start.conversationId(), "记一下，我青霉素过敏");
        assertThat(reply.reply()).contains("已记下", "想去哪家医院");
        assertThat(hasAction(reply, "VIEW_MANAGED")).isFalse();
        assertThat(activeMemoCount()).isEqualTo(1);
    }
}
