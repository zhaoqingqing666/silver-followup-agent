package com.team.silveragent;

import com.team.silveragent.api.AppointmentController;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 老人端页内确认后直连的那个取消端点（{@code POST .../appointments/{id}/cancel}）。
 *
 * <p>新增这个端点时冒过一个真实风险：它绕开了确认卡，很容易顺手写成「页面自己删一条」。
 * 所以这里钉的不是「接口能通」，而是三条**性质**：
 *
 * <p>(1) 它和助手那条取消走**同一个业务方法**，三个动作仍然是一套——释放号源、把关联提醒置
 * {@code CANCELLED}、预约置 {@code CANCELLED}。任何一条漏掉，三个入口的取消就会各说各话。
 *
 * <p>(2) 记录**不物理删除**：{@code materials} / {@code reminders} 挂在 {@code appointment_id} 上，
 * 所以列表接口照样读得到它，只是状态变了。
 *
 * <p>(3) 归属由工具里那句带 {@code user_id} 的查询把关，路径里换个别人的 id 拿不到数据；
 * 而且接口**不回 404** —— 那等于给人一个「这个 id 存在吗」的探测口子。
 *
 * <p>跑在模型不可用模式（{@code agent.model.enabled=false}）下，走规则链路约一条真实预约。
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:silver-agent-elder-cancel;DB_CLOSE_DELAY=-1"})
class ElderCancelEndpointTests {
    private static final String ELDER = "user-001";
    private static final String DAY = DemoSeed.day(DemoSeed.checkupDay());
    /** 库里存在的另一位用户，用来验证「不是你的预约就拿不到」。 */
    private static final String SOMEONE_ELSE = "user-999";

    @Autowired FollowupAgentService service;
    @Autowired AppointmentController controller;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        for (String table : List.of("appointments", "reminders", "family_notifications")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE appointment_slots SET booked=0, available=TRUE");
    }

    /** 一次取消要把三件事一起做完；做完之后记录还在，只是状态变了。 */
    @Test
    void cancellingReleasesTheSlotStopsTheReminderAndKeepsTheRow() {
        String appointmentId = book(DemoSeed.morningSlot());
        String slotId = slotOf(appointmentId);

        assertThat(slotBooked(slotId)).as("约上之后这一班要记上一位").isEqualTo(1);
        // 复诊提醒和出发提醒是两条，所以不写死条数——只钉「取消前它们是活的」，否则下面的断言是空转。
        int reminders = remindersOf(appointmentId);
        assertThat(reminders).as("这条预约应当带着提醒").isPositive();
        assertThat(openRemindersOf(appointmentId)).isEqualTo(reminders);

        controller.cancel(ELDER, appointmentId);

        assertThat(statusOf(appointmentId)).isEqualTo("CANCELLED");
        assertThat(slotBooked(slotId)).as("取消要当场把这个名额放回去").isZero();
        assertThat(openRemindersOf(appointmentId))
                .as("关联提醒要一起停掉，否则取消了还在响").isZero();
        assertThat(remindersOf(appointmentId))
                .as("停掉是改状态，不是删行").isEqualTo(reminders);
        assertThat(jdbc.queryForObject("SELECT reminder_status FROM appointments WHERE id=?",
                String.class, appointmentId)).isEqualTo("关联提醒已取消");

        // 软删：列表接口照样读得到这条记录，只是状态是 CANCELLED。
        assertThat(controller.list(ELDER))
                .filteredOn(row -> row.appointmentId().equals(appointmentId))
                .singleElement()
                .satisfies(row -> assertThat(row.status()).isEqualTo("CANCELLED"));
    }

    /**
     * 同一条再取消一次要给 400，且不产生任何副作用。
     *
     * <p>这是最容易出错的窗口：同一台设备开着两个页面各点一次，或者老人重复点。
     */
    @Test
    void cancellingTwiceIsRejectedAndChangesNothingElse() {
        String appointmentId = book(DemoSeed.morningSlot());
        controller.cancel(ELDER, appointmentId);

        assertThatThrownBy(() -> controller.cancel(ELDER, appointmentId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有找到");

        assertThat(statusOf(appointmentId)).isEqualTo("CANCELLED");
        assertThat(openRemindersOf(appointmentId)).isZero();
    }

    /**
     * 换个 userId 不该能取消别人的预约，而且**不能靠 HTTP 状态码把这个 id 存不存在说出去**。
     *
     * <p>所以这里断言的是「400 + 同一句含糊的话」，不是 404。
     */
    @Test
    void someoneElsesIdCannotCancelAndTheRecordStaysUntouched() {
        String appointmentId = book(DemoSeed.morningSlot());
        String slotId = slotOf(appointmentId);

        assertThatThrownBy(() -> controller.cancel(SOMEONE_ELSE, appointmentId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有找到这条可以取消的预约");

        assertThat(statusOf(appointmentId)).as("别人的失败尝试不能改到这条记录").isEqualTo("CONFIRMED");
        assertThat(slotBooked(slotId)).as("这一班的名额也不该被别人的失败尝试动到").isEqualTo(1);
        assertThat(openRemindersOf(appointmentId)).as("提醒也不该被别人的失败尝试停掉").isPositive();
    }

    private String slotOf(String appointmentId) {
        return jdbc.queryForObject("SELECT slot_id FROM appointments WHERE id=?", String.class, appointmentId);
    }

    private String statusOf(String appointmentId) {
        return jdbc.queryForObject("SELECT status FROM appointments WHERE id=?", String.class, appointmentId);
    }

    /**
     * 这一班已经占了几个名额。
     *
     * <p>名额口径下「约上了」不能再看 {@code available}——一班有 3~4 个名额，
     * 约掉一个之后 {@code available} 仍是 TRUE（还剩名额），要看的是 {@code booked}。
     */
    private Integer slotBooked(String slotId) {
        return jdbc.queryForObject(
                "SELECT booked FROM appointment_slots WHERE id=?", Integer.class, slotId);
    }

    /** 这条预约关联的提醒总数：复诊一条、出发一条，具体条数由业务决定，这里不写死。 */
    private int remindersOf(String appointmentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reminders WHERE appointment_id=?",
                Integer.class, appointmentId);
    }

    /**
     * 还活着的提醒数。和上面的总数分开看，是因为「停掉」在这个项目里解释成改状态、
     * 不是删行——只查 CANCELLED 的条数会放过「原来就没建提醒」这种情况。
     */
    private int openRemindersOf(String appointmentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reminders WHERE appointment_id=? AND status='CREATED'",
                Integer.class, appointmentId);
    }

    private String book(String slotId) {
        String conversationId = service.start().conversationId();
        action(conversationId, "SET_HOSPITAL", "h001");
        action(conversationId, "SET_DEPARTMENT", DemoSeed.CARDIOLOGY);
        action(conversationId, "SET_DATE", DAY);
        action(conversationId, "SELECT_SLOT", slotId);
        action(conversationId, "SET_ALTERNATIVE", "true");
        action(conversationId, "SET_COMPANION", "true");
        action(conversationId, "SET_TRAVEL", "true");
        action(conversationId, "SET_TRANSPORT", "打车");
        action(conversationId, "SET_NOTIFY", "true");
        action(conversationId, "SET_CONTACT", "family-001");
        AgentTurnResponse plan = action(conversationId, "START_PLAN", "");
        assertThat(plan.stage()).as(plan.reply()).isEqualTo("AWAITING_CONFIRMATION");
        service.confirm(conversationId, true, plan.confirmation().confirmationId());

        return jdbc.queryForObject("SELECT id FROM appointments WHERE user_id=? AND status='CONFIRMED'",
                String.class, ELDER);
    }

    private AgentTurnResponse action(String conversationId, String action, String value) {
        return service.act(conversationId, action, value, action);
    }
}
