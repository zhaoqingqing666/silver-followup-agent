package com.team.silveragent;

import com.team.silveragent.api.AppointmentController;
import com.team.silveragent.application.AppointmentRecordStore;
import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.infrastructure.persistence.RollingAppointmentSlotInitializer;
import com.team.silveragent.support.DemoSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Time;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 号源加上「医生」这一维之后的回归。
 *
 * <p>这次改动最容易出问题的地方不是「能不能查到医生」，而是三件更隐蔽的事：
 *
 * <p>(1) **号源 id 的拼法变了**（{@code r-<医生id>-<日期>-<时刻>}）。回归用例、场景重置、
 * 回滚重放都按这个拼法找号源，拼错了会一路静默：查不到就当成「当天没号」。
 *
 * <p>(2) **排班的形状**：每个科室每周固定放 3 天号，一天 4 格、**每格 1 位医生** ——
 * 09:00 / 14:00 是专家格，10:30 / 15:30 是普通格，于是上午下午都各有一格专家号和一格普通号。
 * 两位主任（01 / 04 号）在放号日之间上下午对调，两位主治（02 / 03 号）也一样，四位工作量相等。
 * 「同一时刻只有一条号源」在这个排法下是**成立**的（每格恰好 1 位医生），
 * 但排班必须**确定性**——重启、重跑 seed() 得到同一份，否则演示按不住、回归也没法断言。
 * 这几条在这里钉住，改排班的人会先看到失败，而不是等演示时才发现。
 *
 * <p>(3) **旧号源要清干净，但有预约挂着的那几条不能动**。清过头会把预约变成孤儿
 * （预约列表是 JOIN 号源出来的，页面会直接看不到这条预约，连取消都取消不了）。
 *
 * <p>另外钉一条展示口径：预约上的医生信息是**快照**，落库时就要写进去——号源会被滚动重建，
 * 事后再去 join 号源是查不回「当时约的是谁」的。
 *
 * <p>跑在模型不可用模式（默认 {@code agent.model.enabled=false}）下，走规则链路约一条真实预约。
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:silver-agent-doctor-slot;DB_CLOSE_DELAY=-1"})
class DoctorSlotModelTests {

    private static final String ELDER = "user-001";
    private static final String DAY = DemoSeed.day(DemoSeed.checkupDay());
    /** 用例自己插的「旧号源」都带这个前缀，免得残留下来干扰同类的其它用例。 */
    private static final String LEGACY_PREFIX = "r-legacy-";

    @Autowired FollowupAgentService service;
    @Autowired AppointmentController controller;
    @Autowired RollingAppointmentSlotInitializer slotInitializer;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        for (String table : List.of("appointments", "reminders", "family_notifications")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("DELETE FROM appointment_slots WHERE id LIKE ?", LEGACY_PREFIX + "%");
        // booked 必须一并归零：available 现在是派生位（booked < capacity），
        // 只把它拨回 TRUE、booked 还留着，用例就活在一个破掉不变式的状态里。
        jdbc.update("UPDATE appointment_slots SET booked=0, available=TRUE");
    }

    /** 每条生成出来的号源都得说清「谁出诊、什么号别、挂号费多少」。 */
    @Test
    void everyGeneratedSlotCarriesADoctorASlotTypeAndAFee() {
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM appointment_slots WHERE id LIKE 'r-%'", Integer.class);
        assertThat(total).as("都在的号源总数为 0 的话，下面那条断言是空转").isPositive();

        List<String> incomplete = jdbc.query("""
                SELECT id FROM appointment_slots
                WHERE id LIKE 'r-%'
                  AND (doctor_id IS NULL OR slot_type IS NULL OR department_id IS NULL OR fee_cents IS NULL)
                """, (rs, row) -> rs.getString(1));

        assertThat(incomplete)
                .as("带医生维度之后生成的号源不能缺医生/号别/科室外键/挂号费")
                .isEmpty();
    }

    /**
     * 号源 id 里必须含医生 id——同一科室同一天同一时段，不同医生是**两条**号源，不带医生就撞主键。
     */
    @Test
    void slotIdsCarryTheDoctorSoTheSameMomentCanBeToldApart() {
        String slotId = DemoSeed.morningSlot();
        String doctorId = jdbc.queryForObject(
                "SELECT doctor_id FROM appointment_slots WHERE id=?", String.class, slotId);

        assertThat(doctorId).as("演示要用的号源必须落在医生名下").isNotBlank();
        assertThat(slotId)
                .as("号源 id 的拼法是 r-<医生id>-<日期>-<时刻>")
                .startsWith("r-" + doctorId + "-");
        assertThat(slotId)
                .as("id 里的医生必须就是这条号源实际挂的那位，而不是别人")
                .isEqualTo(RollingAppointmentSlotInitializer.slotId(doctorId,
                        DemoSeed.checkupDay(), DemoSeed.MORNING));
    }

    /**
     * 每个放号日的**上午**都必须恰好「1 位专家 + 1 位普通」。
     *
     * <p>注意口径变了：现在是「每格 1 位医生」，09:00 是专家格、10:30 是普通格，
     * 所以**单看某一格**只有 1 条号源。组成要按**整个半天合起来**数才对：
     * 少一位专家，老人就选不到专家号；多一位，上午的候选又多一条。
     */
    @Test
    void everyMorningHasExactlyOneExpertAndOneNormal() {
        // 必须按 department_id 分组，不能按 department（科室**名字**）：「心内科」「内分泌科」
        // 在两家医院各有一份同名科室，按名字分组会把两家并成一组，上午就有 2 专家 2 普通。
        List<String> wrong = jdbc.query("""
                SELECT s.department_id, s.department, s.appointment_date,
                       SUM(CASE WHEN s.slot_type='EXPERT' THEN 1 ELSE 0 END) AS experts,
                       SUM(CASE WHEN s.slot_type='NORMAL' THEN 1 ELSE 0 END) AS normals
                FROM appointment_slots s
                WHERE s.id LIKE 'r-%' AND s.doctor_id IS NOT NULL AND s.appointment_time < ?
                GROUP BY s.department_id, s.department, s.appointment_date
                HAVING SUM(CASE WHEN s.slot_type='EXPERT' THEN 1 ELSE 0 END) <> 1
                    OR SUM(CASE WHEN s.slot_type='NORMAL' THEN 1 ELSE 0 END) <> 1
                """, (rs, row) -> rs.getString(1) + "·" + rs.getString(2) + " " + rs.getDate(3)
                + "（专家 " + rs.getInt(4) + " / 普通 " + rs.getInt(5) + "）",
                Time.valueOf(RollingAppointmentSlotInitializer.NOON));

        assertThat(wrong)
                .as("每个放号日的上午总共都该是 1 位专家 + 1 位普通（09:00 专家格 + 10:30 普通格）")
                .isEmpty();
    }

    /**
     * 下午同一时刻恰好 1 条号源，永远不会更多。
     *
     * <p>新排法里一天 4 格、每格 1 位医生，下午两格（14:00 专家格 + 15:30 普通格）各一条。
     * 所以这条断言现在比过去更稳——「下午同一时刻超过 2 条」这种事在新排法下不可能出现，
     * 它守的是「有人把某格排了两位医生」这种改坏排班的情形。
     */
    @Test
    void theAfternoonNeverHasMoreThanTwoSlotsAtTheSameMoment() {
        // 同样按 department_id 分组：同名科室在两院各一份，按名字分组会把下午的号源并成双份。
        List<String> tooMany = jdbc.query("""
                SELECT s.department_id, s.department, s.appointment_date, s.appointment_time, COUNT(*) AS n
                FROM appointment_slots s
                WHERE s.id LIKE 'r-%' AND s.doctor_id IS NOT NULL AND s.appointment_time >= ?
                GROUP BY s.department_id, s.department, s.appointment_date, s.appointment_time
                HAVING COUNT(*) > 2
                """, (rs, row) -> rs.getString(1) + "·" + rs.getString(2) + " " + rs.getDate(3) + " "
                + rs.getTime(4) + " 共 " + rs.getInt(5) + " 条",
                Time.valueOf(RollingAppointmentSlotInitializer.NOON));

        assertThat(tooMany).as("下午同一时刻不该超过 2 条号源").isEmpty();
    }

    /** 同一位医生在同一天同一时刻只能有一条号源——id 里带了医生，撞了就说明 id 拼错了。 */
    @Test
    void noDoctorHasTwoSlotsAtTheSameMoment() {
        List<String> duplicated = jdbc.query("""
                SELECT s.doctor_id, s.appointment_date, s.appointment_time
                FROM appointment_slots s
                WHERE s.id LIKE 'r-%' AND s.doctor_id IS NOT NULL
                GROUP BY s.doctor_id, s.appointment_date, s.appointment_time
                HAVING COUNT(*) > 1
                """, (rs, row) -> rs.getString(1) + " " + rs.getDate(2) + " " + rs.getTime(3));

        assertThat(duplicated).isEmpty();
    }

    /** 号别真的在价格上有区别——演示要讲得出「专家号贵一档」。 */
    @Test
    void expertSlotsCostMoreThanNormalOnes() {
        Integer cheapestExpert = jdbc.queryForObject("""
                SELECT MIN(fee_cents) FROM appointment_slots WHERE id LIKE 'r-%' AND slot_type='EXPERT'
                """, Integer.class);
        Integer priciestNormal = jdbc.queryForObject("""
                SELECT MAX(fee_cents) FROM appointment_slots WHERE id LIKE 'r-%' AND slot_type='NORMAL'
                """, Integer.class);

        assertThat(cheapestExpert).as("库里应当同时有专家号").isNotNull();
        assertThat(priciestNormal).as("库里应当同时有普通号").isNotNull();
        assertThat(cheapestExpert)
                .as("最便宜的专家号也要比最贵的普通号贵，否则号别对挂号费没有意义")
                .isGreaterThan(priciestNormal);
    }

    /** 挂号费以**分**存储：普通号 2500 分就是 25 元，不该出现「25 元存成 25 分」这种错。 */
    @Test
    void feesAreStoredInCentsNotYuan() {
        List<Integer> fees = jdbc.query(
                "SELECT DISTINCT fee_cents FROM appointment_slots WHERE id LIKE 'r-%' ORDER BY fee_cents",
                (rs, row) -> rs.getInt(1));

        assertThat(fees).as("目前只有两档挂号费").hasSize(2);
        assertThat(fees).as("以分为单位时不会出现个位数元的价格").allSatisfy(
                fee -> assertThat(fee).isGreaterThanOrEqualTo(1000));
    }

    /**
     * 预约落库时要把医生写成**快照**，列表接口也要把它带出来。
     *
     * <p>不写成快照、事后再去 join 号源的话，号源被滚动重建之后就再也说不出「当时约的是谁」了。
     */
    @Test
    void bookingWritesTheDoctorSnapshotAndTheListEndpointExposesIt() {
        String slotId = DemoSeed.morningSlot();
        String appointmentId = book(slotId);

        String doctorId = jdbc.queryForObject(
                "SELECT doctor_id FROM appointment_slots WHERE id=?", String.class, slotId);
        List<String> expected = jdbc.query("SELECT name,title FROM doctors WHERE id=?",
                (rs, row) -> List.of(rs.getString(1), rs.getString(2)), doctorId).get(0);

        assertThat(jdbc.queryForObject("SELECT doctor_name FROM appointments WHERE id=?",
                String.class, appointmentId))
                .as("预约行要自己记下当时约的是哪位医生").isEqualTo(expected.get(0));
        assertThat(jdbc.queryForObject("SELECT doctor_title FROM appointments WHERE id=?",
                String.class, appointmentId)).isEqualTo(expected.get(1));

        // 号别与挂号费直接来自这条号源，不做二次推导。
        assertThat(jdbc.queryForObject("SELECT slot_type FROM appointments WHERE id=?",
                String.class, appointmentId))
                .isEqualTo(jdbc.queryForObject("SELECT slot_type FROM appointment_slots WHERE id=?",
                        String.class, slotId));
        assertThat(jdbc.queryForObject("SELECT fee_cents FROM appointments WHERE id=?",
                Integer.class, appointmentId))
                .isEqualTo(jdbc.queryForObject("SELECT fee_cents FROM appointment_slots WHERE id=?",
                        Integer.class, slotId));

        // 接口返回体是新前端唯一的数据来源，快照得真的出现在里面。
        AppointmentRecordStore.AppointmentView view = controller.list(ELDER).stream()
                .filter(row -> row.appointmentId().equals(appointmentId))
                .findFirst().orElseThrow();
        assertThat(view.doctorName()).isEqualTo(expected.get(0));
        assertThat(view.doctorTitle()).isEqualTo(expected.get(1));
        assertThat(view.slotType()).isNotBlank();
        assertThat(view.feeCents()).isPositive();
    }

    /**
     * 旧号源（没有医生维度的那批）要被清掉，不再和带医生的新号源并存。
     */
    @Test
    void legacySlotsWithoutADoctorAreCleared() {
        jdbc.update("""
                INSERT INTO appointment_slots
                    (id,hospital_id,hospital_name,department,appointment_date,appointment_time,available)
                VALUES (?,?,?,?,?,?,TRUE)
                """, LEGACY_PREFIX + "orphan", "h001", "市第一医院（模拟）", "心内科",
                DemoSeed.checkupDay(), DemoSeed.MORNING);

        assertThat(slotExists(LEGACY_PREFIX + "orphan")).as("前提：这条旧号源确实插进去了").isTrue();

        slotInitializer.seed();

        assertThat(slotExists(LEGACY_PREFIX + "orphan"))
                .as("没有预约在用的旧号源该整批清掉，重建出带医生的新号源")
                .isFalse();
    }

    /**
     * 有预约挂着的旧号源**不能**清——清了那条预约就成了孤儿。
     *
     * <p>预约列表是 {@code JOIN appointment_slots} 出来的，号源一没，这条预约会在页面上
     * 直接消失，老人连取消都点不到。所以这里钉的是那条例外真的存在。
     */
    @Test
    void legacySlotsStillUsedByAnAppointmentSurviveTheRebuild() {
        String legacySlotId = LEGACY_PREFIX + "in-use";
        jdbc.update("""
                INSERT INTO appointment_slots
                    (id,hospital_id,hospital_name,department,appointment_date,appointment_time,available)
                VALUES (?,?,?,?,?,?,FALSE)
                """, legacySlotId, "h001", "市第一医院（模拟）", "心内科",
                DemoSeed.checkupDay(), DemoSeed.MORNING);
        jdbc.update("""
                INSERT INTO appointments(id,slot_id,user_id,status,created_at)
                VALUES (?,?,?,?,CURRENT_TIMESTAMP)
                """, "AP-LEGACY-1", legacySlotId, ELDER, "CONFIRMED");

        slotInitializer.seed();

        assertThat(slotExists(legacySlotId))
                .as("有预约在用的号源必须留下，否则这条预约会变成读不出来的孤儿")
                .isTrue();
        assertThat(controller.list(ELDER))
                .extracting(AppointmentRecordStore.AppointmentView::appointmentId)
                .as("这条预约仍然要在列表里看得到")
                .contains("AP-LEGACY-1");
    }

    /**
     * 排班是**确定性**的：每个科室每周恰好 3 个放号日、各科室错开、周末永远不放号。
     *
     * <p>回归用例（{@code DemoSeed}）与生成器用的是同一份规则，规则被改乱时这里先红，
     * 而不是等某个演示日期落空。
     */
    @Test
    void everyDepartmentOpensExactlyThreeFixedWeekdays() {
        // ⚠️ 唯一性的口径是「**同一家医院内**两两不同」，不是「全院两两不同」。
        // {周一..周五} 里选 3 天只有 C(5,3)=10 种组合，而全院有 12 个科室——数学上就装不下。
        // 每院 6 科、从 10 种里挑 6 种，绰绰有余；跨医院允许撞同一天。
        List<String[]> departments = jdbc.query("""
                SELECT d.id, d.hospital_id FROM departments d
                JOIN hospitals h ON h.id = d.hospital_id
                WHERE d.enabled = TRUE AND h.enabled = TRUE
                ORDER BY d.hospital_id, d.id
                """, (rs, row) -> new String[]{rs.getString(1), rs.getString(2)});
        assertThat(departments).isNotEmpty();

        Map<String, List<String>> patternsByHospital = new LinkedHashMap<>();
        for (String[] department : departments) {
            Set<Integer> weekdays = RollingAppointmentSlotInitializer.openWeekdays(department[0]);
            assertThat(weekdays)
                    .as("科室 %s 每周的放号日", department[0])
                    .hasSize(3)
                    .allSatisfy(day -> assertThat(day).isBetween(1, 5));
            patternsByHospital.computeIfAbsent(department[1], key -> new ArrayList<>())
                    .add(weekdays.stream().sorted().toList().toString());
        }
        patternsByHospital.forEach((hospitalId, patterns) -> assertThat(patterns)
                .as("同一家医院（%s）内各科室的放号日不该完全一样，否则同院一天扎堆放号", hospitalId)
                .doesNotHaveDuplicates());

        // 周末判断放在 Java 里算，不用 SQL 的 DAY_OF_WEEK——那个函数各库的起点不一样
        // （H2 这里是 ISO：1=周一…7=周日，写成 IN (1,7) 会把周一整批算成周末）。
        // 与生成器、DemoSeed 共用 LocalDate.getDayOfWeek() 这一套口径才不会有方言差。
        List<Integer> weekendDays = jdbc.query("""
                SELECT DISTINCT appointment_date FROM appointment_slots
                WHERE id LIKE 'r-%' AND doctor_id IS NOT NULL
                """, (rs, row) -> rs.getDate(1).toLocalDate().getDayOfWeek().getValue())
                .stream().filter(day -> day >= 6).toList();
        assertThat(weekendDays).as("周末一律不生成号源（ISO：6=周六 / 7=周日）").isEmpty();
    }

    /**
     * 重跑 seed() 之后号源集合**一点不变**：id、医生、号别、费用全都不漂。
     *
     * <p>演示要能提前彩排、回归要按 id 断言，靠的就是「算出来、不随机」。
     * 要是有谁在排班里塞进 {@code Math.random()}，这里立刻红。
     */
    @Test
    void rebuildingTheSlotsChangesNothing() {
        List<String> before = slotFingerprints();

        slotInitializer.seed();

        assertThat(slotFingerprints())
                .as("排班必须确定性：重跑一次不该多一条、少一条或换一位医生")
                .isEqualTo(before);
    }

    /**
     * 每个启用科室恰好 4 位医生，且**恰好 2 位专家号医生**。
     *
     * <p>排班按序号取人：一天 4 格、每格 1 人，所以必须 4 位；其中 01 主任医师与 04 副主任医师
     * 只出专家号、02 / 03 主治医师只出普通号，于是「2 个专家格」正好分给两位专家。少一位生成器
     * 会直接报错（{@code doctorOf}），这里让失败提前到用例里，而不是等某个科室启动时才抛。
     */
    @Test
    void everyEnabledDepartmentHasFourDoctorsIncludingTwoExperts() {
        List<String> departments = jdbc.query("""
                SELECT d.id FROM departments d
                JOIN hospitals h ON h.id = d.hospital_id
                WHERE d.enabled = TRUE AND h.enabled = TRUE
                ORDER BY d.id
                """, (rs, row) -> rs.getString(1));
        assertThat(departments).isNotEmpty();

        for (String departmentId : departments) {
            Integer doctors = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM doctors WHERE department_id=? AND enabled=TRUE",
                    Integer.class, departmentId);
            assertThat(doctors).as("科室 %s 的出诊医生数", departmentId)
                    .isEqualTo(RollingAppointmentSlotInitializer.MAX_DOCTORS_PER_DEPARTMENT);

            Integer experts = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM doctors
                    WHERE department_id=? AND enabled=TRUE AND slot_type='EXPERT'
                    """, Integer.class, departmentId);
            assertThat(experts).as("科室 %s 的专家号医生数（两个专家格要两位专家分）", departmentId)
                    .isEqualTo(2);
        }
    }

    /**
     * 「普通号**名额**总量 &gt; 专家号**名额**总量」——这条是号源供给侧的硬约束。
     *
     * <p>⚠️ 口径从「条数」改成了「名额」：新排法里每科室都是 2 条专家号 + 2 条普通号，
     * 按**条数**是 2 : 2 相等，这条约束就不再成立。而名额才是「真正能约上的人数」：
     * 专家号 3 个名额 / 条、普通号 4 个名额 / 条，于是 8 : 6 —— 语义也更准。
     *
     * <p>演示要讲得出「专家号经常约满、普通号相对好挂」，靠的就是这个配比。哪天有人把 03 号
     * 偷偷改成专家号、或者把专家格与普通格的名额对调，这里立刻红。
     */
    @Test
    void everyDepartmentHasMoreNormalCapacityThanExpertCapacity() {
        List<String> departments = jdbc.query("""
                SELECT d.id FROM departments d
                JOIN hospitals h ON h.id = d.hospital_id
                WHERE d.enabled = TRUE AND h.enabled = TRUE
                ORDER BY d.id
                """, (rs, row) -> rs.getString(1));
        assertThat(departments).isNotEmpty();

        for (String departmentId : departments) {
            Integer expertCapacity = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(capacity),0) FROM appointment_slots
                    WHERE id LIKE 'r-%' AND doctor_id IS NOT NULL
                      AND department_id = ? AND slot_type = 'EXPERT'
                    """, Integer.class, departmentId);
            Integer normalCapacity = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(capacity),0) FROM appointment_slots
                    WHERE id LIKE 'r-%' AND doctor_id IS NOT NULL
                      AND department_id = ? AND slot_type = 'NORMAL'
                    """, Integer.class, departmentId);

            assertThat(normalCapacity)
                    .as("科室 %s 的普通号名额总量必须大于专家号名额总量（专家稀缺、普通好挂）",
                            departmentId)
                    .isGreaterThan(expertCapacity);
        }
    }

    /** 号源总量要留在几百条这个量级——每科室每周 3 天、半天制，不等于把列表灌爆。 */
    @Test
    void theGeneratedSlotCountStaysInTheHundreds() {
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM appointment_slots WHERE id LIKE 'r-%'", Integer.class);

        assertThat(total)
                .as("每科室每周只放 3 天号，总量应保持在几百条，方便调试")
                .isBetween(200, 1200);
    }

    /** 号源集合的指纹：id + 医生 + 号别 + 费用。用来钉「重跑不漂」。 */
    private List<String> slotFingerprints() {
        return jdbc.query("""
                SELECT id, doctor_id, slot_type, fee_cents FROM appointment_slots
                WHERE id LIKE 'r-%' AND doctor_id IS NOT NULL ORDER BY id
                """, (rs, row) -> rs.getString(1) + "|" + rs.getString(2) + "|"
                + rs.getString(3) + "|" + rs.getInt(4));
    }

    private boolean slotExists(String slotId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM appointment_slots WHERE id=?", Integer.class, slotId);
        return count != null && count > 0;
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
