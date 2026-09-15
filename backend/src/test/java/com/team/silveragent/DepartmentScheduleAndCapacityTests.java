package com.team.silveragent;

import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.model.AgentTurnResponse.QuickReply;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「每科室 4 位医生 + 一天 4 格」这套新排班，以及号源**名额**（capacity / booked）的正向回归。
 *
 * <p>这一组用例是**新口径的关卡**：老口径那几条断言（「上午同一时刻两条」「下午没有专家号」
 * 「一条号源就是一个号」）被需求改掉了，如果只把它们删掉或改掉、不补新的，就等于拆了关卡没装新的。
 * 这里钉住的是新口径本身——
 *
 * <ul>
 *   <li>每个放号日恰好 4 条号源、由 4 位**不同**的医生各坐一格（于是每人每天 1 格、3 天都在）；</li>
 *   <li>号别按格固定：09:00 / 14:00 专家号，10:30 / 15:30 普通号；</li>
 *   <li>两位主任（01 / 04 号）在放号日之间上下午对调，不是一位主任坐满；</li>
 *   <li>名额按号别落库（专家 3 / 普通 4），且全表满足 {@code available = (booked < capacity)}；</li>
 *   <li>名额的扣减与释放行为：约一个还有名额、约满才不可约、取消放回一个；</li>
 *   <li>两院科室名集合相同且各 6 个。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-schedule-capacity;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=false"})
class DepartmentScheduleAndCapacityTests {

    private static final LocalTime MORNING_EXPERT = LocalTime.of(9, 0);
    private static final LocalTime MORNING_NORMAL = LocalTime.of(10, 30);
    private static final LocalTime AFTERNOON_EXPERT = LocalTime.of(14, 0);
    private static final LocalTime AFTERNOON_NORMAL = LocalTime.of(15, 30);

    @Autowired FollowupAgentService service;
    @Autowired AppointmentTool appointments;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        for (String table : List.of("appointments", "reminders", "family_notifications")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE appointment_slots SET booked=0, available=TRUE");
    }

    /**
     * 每个放号日恰好 4 条号源，且来自 **4 位不同的医生**。
     *
     * <p>「4 位不同」这一条就够了：一天 4 格、每格 1 人，所以它同时说明了
     * 「每位医生每天恰好 1 格」与「科室里的 4 位医生每天全都在岗」——
     * 也就是「每位医生在 3 个放号日里都出诊」这条需求。
     */
    @Test
    void everyOpenDayHasFourSlotsFromFourDifferentDoctors() {
        List<String> wrong = jdbc.query("""
                SELECT s.department_id, s.department, s.appointment_date,
                       COUNT(*) AS slots, COUNT(DISTINCT s.doctor_id) AS doctors
                FROM appointment_slots s
                WHERE s.id LIKE 'r-%' AND s.doctor_id IS NOT NULL
                GROUP BY s.department_id, s.department, s.appointment_date
                HAVING COUNT(*) <> 4 OR COUNT(DISTINCT s.doctor_id) <> 4
                """, (rs, row) -> rs.getString(1) + "·" + rs.getString(2) + " " + rs.getDate(3)
                + "（号源 " + rs.getInt(4) + " 条 / 医生 " + rs.getInt(5) + " 位）");

        assertThat(wrong)
                .as("每个放号日都该是 4 格 4 人——少人格子就空着，多人说明有人一天坐两格")
                .isEmpty();
    }

    /** 号别严格按格：每半天的第一格（09:00 / 14:00）专家号，第二格（10:30 / 15:30）普通号。 */
    @Test
    void slotTypesFollowTheFixedGrid() {
        List<Object[]> rows = jdbc.query("""
                SELECT id, appointment_time, slot_type FROM appointment_slots
                WHERE id LIKE 'r-%' AND doctor_id IS NOT NULL
                """, (rs, row) -> new Object[]{rs.getString(1), rs.getTime(2).toLocalTime(), rs.getString(3)});
        assertThat(rows).as("库里应当有生成的号源，否则这条用例是空转").isNotEmpty();

        assertThat(rows).allSatisfy(row -> {
            LocalTime time = (LocalTime) row[1];
            boolean expertGrid = time.equals(MORNING_EXPERT) || time.equals(AFTERNOON_EXPERT);
            assertThat((String) row[2])
                    .as("号源 %s（%s）的号别", row[0], time)
                    .isEqualTo(expertGrid ? RollingAppointmentSlotInitializer.SLOT_TYPE_EXPERT
                            : RollingAppointmentSlotInitializer.SLOT_TYPE_NORMAL);
        });
    }

    /**
     * 两位主任在放号日之间**上下午对调**：09:00 这一格在 3 个放号日里由两位主任轮流坐。
     *
     * <p>这条专门防「排班被改回一位主任坐满 3 天上午」——那正是加第二位主任要消掉的现象。
     */
    @Test
    void theTwoChiefsTakeTurnsOnTheExpertGrid() {
        for (String departmentId : enabledDepartmentIds()) {
            List<String> expertDoctors = jdbc.query("""
                    SELECT DISTINCT doctor_id FROM appointment_slots
                    WHERE id LIKE 'r-%' AND slot_type='EXPERT' AND department_id=?
                    ORDER BY doctor_id
                    """, (rs, row) -> rs.getString(1), departmentId);

            assertThat(expertDoctors).as("科室 %s 出专家号的医生", departmentId).hasSize(2);
            assertThat(expertDoctors).as("两位都是 01 / 04 号（主任 + 副主任）")
                    .allSatisfy(id -> assertThat(id).matches(".*-(01|04)$"));

            Integer morningExpertDoctors = jdbc.queryForObject("""
                    SELECT COUNT(DISTINCT doctor_id) FROM appointment_slots
                    WHERE id LIKE 'r-%' AND slot_type='EXPERT' AND department_id=? AND appointment_time=?
                    """, Integer.class, departmentId, Time.valueOf(MORNING_EXPERT));
            assertThat(morningExpertDoctors)
                    .as("科室 %s 的 09:00 专家格要由两位主任轮流坐，才算真的对调", departmentId)
                    .isEqualTo(2);
        }
    }

    /** 名额按号别落库：专家号 3 / 普通号 4，且全表只有这两档。 */
    @Test
    void capacityIsStoredPerSlotType() {
        List<String> wrong = jdbc.query("""
                SELECT id FROM appointment_slots
                WHERE id LIKE 'r-%' AND doctor_id IS NOT NULL
                  AND ((slot_type='EXPERT' AND capacity <> ?) OR (slot_type='NORMAL' AND capacity <> ?))
                """, (rs, row) -> rs.getString(1),
                RollingAppointmentSlotInitializer.EXPERT_CAPACITY,
                RollingAppointmentSlotInitializer.NORMAL_CAPACITY);

        assertThat(wrong).as("专家号 3 个名额、普通号 4 个名额，别的值都是错的").isEmpty();

        List<Integer> tiers = jdbc.query("""
                SELECT DISTINCT capacity FROM appointment_slots
                WHERE id LIKE 'r-%' AND doctor_id IS NOT NULL ORDER BY capacity
                """, (rs, row) -> rs.getInt(1));
        assertThat(tiers).as("名额只有两档").containsExactly(3, 4);
    }

    /**
     * 名额的扣减与释放。
     *
     * <p>这是新口径的核心行为：约一个**还剩名额、仍然可约**；约满 {@code capacity} 个之后
     * 才从查询侧消失；第 {@code capacity + 1} 个被拒；取消一个又把名额放回来。
     */
    @Test
    void bookingConsumesOneSeatUntilTheSlotIsFull() {
        String slot = DemoSeed.morningSlot();
        int capacity = capacityOf(slot);
        assertThat(capacity).as("这条演示号源是专家格").isEqualTo(RollingAppointmentSlotInitializer.EXPERT_CAPACITY);

        appointments.submit("t-capacity", slot, "user-001");
        assertThat(bookedOf(slot)).as("约一个只占一个名额").isEqualTo(1);
        assertThat(availableOf(slot)).as("还剩名额，查询侧照旧看得见它").isTrue();

        // 把剩下的名额约满（用不同的就诊人 / 会话，走的都是真实提交路径）。
        for (int i = 2; i <= capacity; i++) {
            appointments.submit("t-capacity-" + i, slot, "user-00" + i);
        }
        assertThat(bookedOf(slot)).isEqualTo(capacity);
        assertThat(availableOf(slot)).as("约满之后就从查询侧消失了").isFalse();

        assertThatThrownBy(() -> appointments.submit("t-capacity-full", slot, "user-009"))
                .as("满员之后再提交要被拒绝")
                .isInstanceOf(IllegalStateException.class);

        appointments.cancel("t-capacity", appointmentOf(slot, "user-001"), "user-001");
        assertThat(bookedOf(slot)).as("取消要当场放回一个名额").isEqualTo(capacity - 1);
        assertThat(availableOf(slot)).as("有位置空出来就重新可约").isTrue();
    }

    /** 全表不变式：{@code 0 <= booked <= capacity} 且 {@code available = (booked < capacity)}。 */
    @Test
    void everySlotKeepsTheSeatInvariant() {
        String slot = DemoSeed.morningSlot();
        appointments.submit("t-invariant", slot, "user-001");

        List<String> violations = jdbc.query("""
                SELECT id, booked, capacity, available FROM appointment_slots
                WHERE booked < 0 OR booked > capacity
                   OR available <> CASE WHEN booked < capacity THEN TRUE ELSE FALSE END
                """, (rs, row) -> rs.getString(1) + "（booked " + rs.getInt(2) + " / capacity "
                + rs.getInt(3) + " / available " + rs.getBoolean(4) + "）");

        assertThat(violations).as("派生位 available 与 booked / capacity 必须始终一致").isEmpty();
    }

    /**
     * R1 的**时段级**降级：下午的专家号被约满之后，老人仍要「下午的专家号」→
     * 明说下午没有专家号，并把上午的专家号摆出来问一句。
     *
     * <p>老口径下这个场景靠「下午压根不排专家号」天然成立；新排法下午 14:00 就是专家格，
     * 所以只能手工把它约满来演。这条就是替换掉旧守卫用例的那道关卡。
     */
    @Test
    void soldOutAfternoonExpertFallsBackToTheMorningExpert() {
        String afternoonExpert = DemoSeed.slot(DemoSeed.CARDIOLOGY, DemoSeed.checkupDay(), AFTERNOON_EXPERT);
        jdbc.update("UPDATE appointment_slots SET booked=capacity, available=FALSE WHERE id=?", afternoonExpert);

        String id = service.start().conversationId();
        act(id, "SET_HOSPITAL", "h001");
        act(id, "SET_DEPARTMENT", DemoSeed.CARDIOLOGY);
        act(id, "SET_DATE", DemoSeed.day(DemoSeed.checkupDay()));

        AgentTurnResponse turn = service.chat(id, "我要挂专家号，下午去");

        assertThat(turn.reply()).as("先明说这个时段没有专家号")
                .contains("您要的是专家号").contains("下午的号都是普通号");
        assertThat(turn.reply()).as("再把上午的专家号摆出来问一句，而不是悄悄换")
                .contains("专家号在上午");
        assertThat(turn.quickReplies()).extracting(QuickReply::action)
                .as("出路是「改选上午」，不是替老人改日期")
                .contains("SET_PERIOD")
                .doesNotContain("SET_DATE");
    }

    /** 两家医院各有 6 个科室，且**科室名字的集合完全相同**（心内/神内/内分泌/骨科/呼吸/消化）。 */
    @Test
    void bothHospitalsOfferTheSameSixDepartments() {
        for (String hospitalId : List.of("h001", "h002")) {
            Integer total = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM departments WHERE hospital_id=? AND enabled=TRUE
                    """, Integer.class, hospitalId);
            Integer distinctNames = jdbc.queryForObject("""
                    SELECT COUNT(DISTINCT name) FROM departments WHERE hospital_id=? AND enabled=TRUE
                    """, Integer.class, hospitalId);
            assertThat(total).as("%s 的启用科室数", hospitalId).isEqualTo(6);
            assertThat(distinctNames).as("%s 的科室名不应重复", hospitalId).isEqualTo(6);
        }

        assertThat(namesOf("h001")).as("两院的科室名集合应当一致").isEqualTo(namesOf("h002"));
    }

    // ------------------------------------------------------------------

    private List<String> enabledDepartmentIds() {
        return jdbc.query("""
                SELECT d.id FROM departments d
                JOIN hospitals h ON h.id = d.hospital_id
                WHERE d.enabled = TRUE AND h.enabled = TRUE ORDER BY d.id
                """, (rs, row) -> rs.getString(1));
    }

    private List<String> namesOf(String hospitalId) {
        return jdbc.query("SELECT name FROM departments WHERE hospital_id=? AND enabled=TRUE ORDER BY name",
                (rs, row) -> rs.getString(1), hospitalId);
    }

    private int capacityOf(String slotId) {
        Integer capacity = jdbc.queryForObject(
                "SELECT capacity FROM appointment_slots WHERE id=?", Integer.class, slotId);
        return capacity == null ? -1 : capacity;
    }

    private int bookedOf(String slotId) {
        Integer booked = jdbc.queryForObject(
                "SELECT booked FROM appointment_slots WHERE id=?", Integer.class, slotId);
        return booked == null ? -1 : booked;
    }

    private boolean availableOf(String slotId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT available FROM appointment_slots WHERE id=?", Boolean.class, slotId));
    }

    private String appointmentOf(String slotId, String userId) {
        return jdbc.queryForObject(
                "SELECT id FROM appointments WHERE slot_id=? AND user_id=? AND status='CONFIRMED'",
                String.class, slotId, userId);
    }

    private AgentTurnResponse act(String conversationId, String action, String value) {
        return service.act(conversationId, action, value, action);
    }
}
