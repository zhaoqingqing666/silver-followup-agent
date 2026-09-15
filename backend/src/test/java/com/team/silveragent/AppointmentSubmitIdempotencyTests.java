package com.team.silveragent;

import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.tool.AppointmentTool;
import com.team.silveragent.infrastructure.persistence.RollingAppointmentSlotInitializer;
import com.team.silveragent.support.DemoSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Time;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code MockAppointmentTool.submit} 的幂等边界。
 *
 * <p>老实现开头只按 {@code conversation_id + user_id + status='CONFIRMED'} 找已有预约，找到就直接
 * 把它返回——等于把「这段会话里已经约过一次」当成了「这一次提交是重复的」。同一段会话里再办一次
 * （老人端那个按钮叫「新建办理」，{@code restartInCurrentConversation} 会把 {@code appointmentId}
 * 清空，所以第二次确实会走到 {@code submit}）时，前置的 {@code checkDuplicate}
 * （患者 + 日期 + 时段）明明放行了新的时段，这里却把上一次那条预约原样返回：
 * **页面上显示新时段办成了，库里还是旧那一条**。
 *
 * <p>修好之后要同时守住两侧：
 * <ul>
 *   <li>换了号源 → 必须真的新建一条，返回的必须是新 id
 *       （{@link #anotherSlotInTheSameConversationCreatesASecondAppointment} 与
 *       {@link #aSecondBookingInTheSameConversationLandsOnItsOwnSlot}）；</li>
 *   <li>同一次提交重复落到 {@code submit} → 不能重复建
 *       （{@link #resubmittingTheSameSlotIsIdempotent}）。</li>
 * </ul>
 *
 * <p>真正的冲突（患者 + 日期 + 时段）不归 {@code submit} 管，它在 {@code FollowupAgentService.checkDuplicate}
 * 里，本次没有动。最后两条用例守着它没被这次改动带弱——尤其「同一时刻的**另一位医生**」那条：
 * 幂等键多带了号源这一维之后，最容易犯的错就是有人以为「换了号源就不算重复」。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-submit-idempotency;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=false"})
class AppointmentSubmitIdempotencyTests {

    private static final String ELDER = "user-001";
    private static final String DAY = DemoSeed.checkupDay().toString();
    /** 下周三上午 09:00，那天排班里的第一位医生（专家号）。 */
    private static final String MORNING_SLOT = DemoSeed.morningSlot();
    /** 同一天 15:30——与 09:00 是**不同时段**，按业务规则本来就该允许再约一次。 */
    private static final String AFTERNOON_SLOT = DemoSeed.secondSlot();
    /** 用例自己手工插的号源都带这个前缀，免得残留下来干扰同类的其它用例。 */
    private static final String MANUAL_SLOT_PREFIX = "r-test-manual-";

    @Autowired AppointmentTool appointments;
    @Autowired FollowupAgentService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        for (String table : List.of("appointments", "reminders", "family_notifications")) {
            jdbc.update("DELETE FROM " + table);
        }
        // 用例手工插的那条「同一时刻另一位医生」的号源要清掉，别留到下一条用例里。
        jdbc.update("DELETE FROM appointment_slots WHERE id LIKE ?", MANUAL_SLOT_PREFIX + "%");
        jdbc.update("UPDATE appointment_slots SET booked=0, available=TRUE");
    }

    /**
     * Case 1（工具层）：同一段会话换了时段，必须真的新建一条，返回的也得是新 id。
     *
     * <p>修之前这里拿到的是第一次那条预约的 id，第二次的时段根本没落库。
     */
    @Test
    void anotherSlotInTheSameConversationCreatesASecondAppointment() {
        String first = appointments.submit("t-second-booking", MORNING_SLOT, ELDER);
        String second = appointments.submit("t-second-booking", AFTERNOON_SLOT, ELDER);

        assertThat(second).as("换了时段就是另一份预约，不能把上一次那条的 id 返回来")
                .isNotEqualTo(first);
        assertThat(slotOf(first)).as("第一条仍然是 09:00").isEqualTo(MORNING_SLOT);
        assertThat(slotOf(second)).as("第二次提交的时段必须真的落库").isEqualTo(AFTERNOON_SLOT);
        assertThat(confirmedSlots()).as("两条预约各占一个时段").hasSize(2);
    }

    /** Case 2（工具层）：同一次提交重复落到 submit，不能重复建，两次拿回同一个 id。 */
    @Test
    void resubmittingTheSameSlotIsIdempotent() {
        String first = appointments.submit("t-repeat", MORNING_SLOT, ELDER);
        String again = appointments.submit("t-repeat", MORNING_SLOT, ELDER);

        assertThat(again).as("同一次提交重复落下来只能有一条预约，返回同一个 id").isEqualTo(first);
        assertThat(confirmedSlots()).as("第二条预约不能被重复创建").hasSize(1);
    }

    /**
     * 幂等键里必须带就诊人：同一段会话里，另一位就诊人拿同一个号源，**不能被第一位那条预约顶掉**。
     *
     * <p>号源改成「一班多个名额」之后，同一班本来就容得下两位就诊人——所以这里期望的是
     * 各得一条预约（而不是以前那种「第二个被号源占满拒掉」）。要钉的是**别把别人那条返回回来**。
     */
    @Test
    void theIdempotencyKeyStillCarriesThePatient() {
        String first = appointments.submit("t-shared-conversation", MORNING_SLOT, ELDER);
        String other = appointments.submit("t-shared-conversation", MORNING_SLOT, "user-002");

        assertThat(other).as("幂等键里带了就诊人：另一位就诊人不能被第一位那条预约顶掉")
                .isNotEqualTo(first);
        assertThat(patientOf(first)).isEqualTo(ELDER);
        assertThat(patientOf(other)).isEqualTo("user-002");
        assertThat(confirmedSlots()).as("这一班还有名额，两位就诊人各留一条预约").hasSize(2);
    }

    /**
     * Case 1（端到端）：老人办完一次之后，在同一段会话里再点「新建办理」办第二个时段。
     *
     * <p>这条走的是真实按钮序列，复现的就是界面上看到的那一幕：第二次也提示办好了，
     * 但库里还是第一次那条。
     */
    @Test
    void aSecondBookingInTheSameConversationLandsOnItsOwnSlot() {
        String conversationId = service.start().conversationId();
        confirm(plan(conversationId, MORNING_SLOT));

        service.act(conversationId, "NEW_BOOKING", "", "新建办理");
        AgentTurnResponse second = confirm(plan(conversationId, AFTERNOON_SLOT));

        assertThat(second.stage()).as(second.reply()).isEqualTo("COMPLETED");
        assertThat(confirmedSlots()).as("两条预约各占一个时段，第二次不能被旧预约顶掉")
                .containsExactlyInAnyOrder(MORNING_SLOT, AFTERNOON_SLOT);
    }

    /**
     * Case 3：真正的冲突仍然按「患者 + 日期 + 时段」拦下来——**换成同一时刻另一位医生的号源也算冲突**。
     *
     * <p>这条规则在 {@code checkDuplicate} 里，本次没有动它；专门盯「同一时刻的另一条号源」，
     * 是因为修好之后 {@code submit} 的幂等键多带了号源这一维。
     *
     * <p>重复是在最后一步（{@code SET_NOTIFY}）被报出来的，与
     * {@code SilverAgentApplicationTests.duplicateAppointmentIsExplainedBeforeAnotherConfirmationCanBeCreated}
     * 同一条路；所以这里不往下走到 START_PLAN。
     */
    @Test
    void theSameDayAndTimeIsStillRejectedForAnotherDoctorsSlot() {
        confirm(plan(service.start().conversationId(), MORNING_SLOT));

        String otherDoctorMorning = otherDoctorsMorningSlot();
        assertThat(otherDoctorMorning).as("前提：09:00 确实排了第二位医生，否则这条用例是空转")
                .isNotEqualTo(MORNING_SLOT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE slot_id=?",
                Integer.class, otherDoctorMorning)).as("前提：这条号源本身还没有人约").isZero();

        AgentTurnResponse duplicate = draft(service.start().conversationId(), otherDoctorMorning);

        assertThat(duplicate.confirmation()).as("同一天同一时段不能再约一次，不能给出确认卡").isNull();
        assertThat(duplicate.reply()).contains("这个时段您已经预约过了", "没有重复提交");
        assertThat(confirmedSlots()).as("重复的那次一条都不该落下").containsExactly(MORNING_SLOT);
    }

    /**
     * 同一时刻**换一个科室**也必须被拦下来——老人只有一个身子，不可能同时坐在两个科室里。
     *
     * <p>这条是补上原来的缺口：检查条件里原来带着医院和科室，只拦得住「同院同科同时间」，
     * 换个科室（甚至换家医院）同一时刻照样能约上；演示里同一位老人名下那两条 9月15日 10:30
     * （骨科沈国安 / 呼吸内科潘晓丽）就是这么进来的。
     *
     * <p>号源必须手工插：每个科室每周只放 3 天号、同院内两两不同，自然排班里同一天同一时刻
     * 本来就只有一个科室开门（排班规则使然，不是缺陷）。
     */
    @Test
    void theSameMomentIsRejectedEvenInAnotherDepartment() {
        confirm(plan(service.start().conversationId(), MORNING_SLOT));

        String otherDepartmentMorning = anotherDepartmentMorningSlot();
        assertThat(otherDepartmentMorning).as("前提：这条手工号源确实和上午那格不是同一条").isNotEqualTo(MORNING_SLOT);

        AgentTurnResponse duplicate = draft(service.start().conversationId(), "d007", otherDepartmentMorning);

        assertThat(duplicate.confirmation()).as("换个科室也是同一时刻，不能给出确认卡").isNull();
        assertThat(duplicate.reply()).contains("没有重复提交");
        assertThat(confirmedSlots()).as("重复的那次一条都不该落下").containsExactly(MORNING_SLOT);
    }

    /** 前提自检：这几条用例都要求两条号源分处上下午，别写成同一格。 */
    @Test
    void theTwoSlotsUsedHereReallyAreDifferentPeriods() {
        assertThat(timeOf(MORNING_SLOT)).isBefore(RollingAppointmentSlotInitializer.NOON);
        assertThat(timeOf(AFTERNOON_SLOT)).isAfter(RollingAppointmentSlotInitializer.NOON);
    }

    /**
     * 走一遍老人端的填草稿按钮序列，返回最后一步的回合。
     *
     * <p>顺序照抄 {@code SilverAgentApplicationTests.prepare()}：先定医院 / 科室 / 日期，
     * 再选号源，最后问到「要不要通知家属」——那一步正是重复预约会被报出来的地方。
     * 第二次办理时 {@code NEW_BOOKING} 已经清空了草稿，同一套按钮可以再来一遍。
     */
    private AgentTurnResponse draft(String conversationId, String slotId) {
        return draft(conversationId, "d001", slotId);
    }

    /** 同上，但指定科室：跨科室的「同一时刻」冲突要靠它复现（见 {@link #theSameMomentIsRejectedEvenInAnotherDepartment}）。 */
    private AgentTurnResponse draft(String conversationId, String departmentId, String slotId) {
        service.act(conversationId, "SET_HOSPITAL", "h001", "选医院");
        service.act(conversationId, "SET_DEPARTMENT", departmentId, "选科室");
        service.act(conversationId, "SET_DATE", DAY, "选日期");
        service.act(conversationId, "SELECT_SLOT", slotId, "选号源");
        service.act(conversationId, "SET_ALTERNATIVE", "true", "只要这一天");
        service.act(conversationId, "SET_COMPANION", "false", "不需要陪同");
        service.act(conversationId, "SET_TRAVEL", "false", "不需要出发建议");
        service.act(conversationId, "SET_TRANSPORT", "打车", "打车");
        return service.act(conversationId, "SET_NOTIFY", "false", "不通知家属");
    }

    /** 填完草稿再「检查计划」，返回待确认的卡片。 */
    private AgentTurnResponse plan(String conversationId, String slotId) {
        draft(conversationId, slotId);
        AgentTurnResponse turn = service.act(conversationId, "START_PLAN", "", "检查计划");
        assertThat(turn.stage()).as(turn.reply()).isEqualTo("AWAITING_CONFIRMATION");
        return turn;
    }

    /** 确认这张卡，返回确认后的回合。 */
    private AgentTurnResponse confirm(AgentTurnResponse card) {
        return service.confirm(card.conversationId(), true, card.confirmation().confirmationId());
    }

    /**
     * 在下周三 09:00 手工再摆一条**另一位医生**的号源，用来验证「冲突判的是时段，不是号源」。
     *
     * <p>为什么要手工插：新排法一天 4 格、**每格 1 位医生**，生成器不会在同一时刻排第二位医生，
     * 所以自然排班里已经造不出「同一时刻的另一条号源」。而这条用例要钉的恰恰是
     * 「同一时刻换一条号源（另一位医生）也照样算重复」——冲突键是
     * 「患者 + 日期 + 时刻」（见 {@code FollowupAgentService.checkDuplicate}），
     * 与号源、与医生都无关，**也不分医院科室**（同一个人同一时刻只可能在一个地方）。
     *
     * <p>id 带 {@code r-test-manual-} 前缀，{@code @BeforeEach} 会清干净。
     */
    private String otherDoctorsMorningSlot() {
        String id = MANUAL_SLOT_PREFIX + DemoSeed.checkupDay() + "-0900";
        jdbc.update("""
                INSERT INTO appointment_slots
                    (id,hospital_id,hospital_name,department,appointment_date,appointment_time,
                     available,doctor_id,slot_type,department_id,fee_cents,capacity,booked)
                SELECT ?,?,?,?,?,?,TRUE,?,?,?,?,?,0
                WHERE NOT EXISTS (SELECT 1 FROM appointment_slots WHERE id=?)
                """, id, "h001", "市第一医院（模拟）", "心内科", DemoSeed.checkupDay(), DemoSeed.MORNING,
                "doc-d001-01", RollingAppointmentSlotInitializer.SLOT_TYPE_EXPERT, DemoSeed.CARDIOLOGY,
                RollingAppointmentSlotInitializer.EXPERT_FEE_CENTS,
                RollingAppointmentSlotInitializer.EXPERT_CAPACITY, id);
        return id;
    }

    /**
     * 在下周三 09:00 手工再摆一条**另一个科室**的号源，用来验证「同一时刻的冲突不分医院科室」。
     *
     * <p>为什么也要手工插：每个科室每周只放 3 天号、同院内两两不同，自然排班里同一天同一时刻
     * 本来就只有一个科室开门。id 同样带 {@code r-test-manual-} 前缀，{@code @BeforeEach} 会清干净。
     */
    private String anotherDepartmentMorningSlot() {
        String id = MANUAL_SLOT_PREFIX + DemoSeed.checkupDay() + "-0900-ortho";
        jdbc.update("""
                INSERT INTO appointment_slots
                    (id,hospital_id,hospital_name,department,appointment_date,appointment_time,
                     available,doctor_id,slot_type,department_id,fee_cents,capacity,booked)
                SELECT ?,?,?,?,?,?,TRUE,?,?,?,?,?,0
                WHERE NOT EXISTS (SELECT 1 FROM appointment_slots WHERE id=?)
                """, id, "h001", "市第一医院（模拟）", "骨科", DemoSeed.checkupDay(), DemoSeed.MORNING,
                "doc-d007-02", RollingAppointmentSlotInitializer.SLOT_TYPE_NORMAL, "d007",
                RollingAppointmentSlotInitializer.NORMAL_FEE_CENTS,
                RollingAppointmentSlotInitializer.NORMAL_CAPACITY, id);
        return id;
    }

    private String slotOf(String appointmentId) {
        return jdbc.queryForObject("SELECT slot_id FROM appointments WHERE id=?",
                String.class, appointmentId);
    }

    private String patientOf(String appointmentId) {
        return jdbc.queryForObject("SELECT user_id FROM appointments WHERE id=?",
                String.class, appointmentId);
    }

    private List<String> confirmedSlots() {
        return jdbc.queryForList(
                "SELECT slot_id FROM appointments WHERE status='CONFIRMED' ORDER BY created_at",
                String.class);
    }

    private LocalTime timeOf(String slotId) {
        return jdbc.queryForObject("SELECT appointment_time FROM appointment_slots WHERE id=?",
                Time.class, slotId).toLocalTime();
    }
}
