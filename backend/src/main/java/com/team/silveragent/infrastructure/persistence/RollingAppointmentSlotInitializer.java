package com.team.silveragent.infrastructure.persistence;

import com.team.silveragent.application.BusinessClock;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 演示号源的唯一生成处。
 *
 * <p>排班形状（每个科室一致，只有「哪 3 天」按科室错开）：
 *
 * <ul>
 *   <li>每个科室每周固定放 3 个工作日的号（{@link #openWeekdays}），周末一律不放——
 *       「指定日期暂无号源」是刻意留的演示场景；</li>
 *   <li>放号日一天 4 格：上午 09:00 / 10:30，下午 14:00 / 15:30。**每格 1 位医生**；</li>
 *   <li>号别按格固定：每半天的**第一格**（09:00 / 14:00）是**专家号**，
 *       每半天的**第二格**（10:30 / 15:30）是**普通号** —— 于是上午、下午都各有一格专家号和
 *       一格普通号；</li>
 *   <li>每科室 4 位医生正好一人一格坐满：01 主任医师 / 04 副主任医师（都只出专家号）分两个专家格，
 *       02 / 03 主治医师（只出普通号）分两个普通格；第 1、3 个放号日与第 2 个放号日**上下午对调**，
 *       四位医生工作量完全相等（各 3 格、每天恰好 1 格）。</li>
 * </ul>
 *
 * <p>名额（{@code capacity}）按号别取：专家号 {@link #EXPERT_CAPACITY} 个、普通号
 * {@link #NORMAL_CAPACITY} 个。一条号源不再是「一个号」，而是「一位医生在一个时刻里的一班」，
 * 能接几位由 capacity 决定；{@code available} 降级为**派生位**（{@code booked &lt; capacity}）。
 *
 * <p>排班是**算出来的，不是抽出来的**：下面几个 {@code public static} 方法都是纯函数，
 * 同一个「科室 + 日期」在任何一次启动、任何一台机器上都得到同一份排班。演示要能提前彩排、
 * 回归用例要能按号源 id 断言，所以这里不能用 {@code Math.random()}。
 */
@Component
public class RollingAppointmentSlotInitializer implements ApplicationRunner {

    /** 一天 4 格，按时间顺序——{@code seed()} 就照这个顺序逐格排。 */
    private static final List<LocalTime> ALL_TIMES = List.of(
            LocalTime.of(9, 0), LocalTime.of(10, 30), LocalTime.of(14, 0), LocalTime.of(15, 30));

    /**
     * 放专家号的格：**每半天的第一格**。上午 09:00、下午 14:00。
     *
     * <p>为什么取每半天的第一格而不是别的：10:30 是 {@code DemoSeed.conflictingSlot()} 用的
     * 冲突格，号别无关，但保住它「和体检撞车」的语义；09:00 是 {@code DemoSeed.MORNING} 的锚点。
     */
    private static final List<LocalTime> EXPERT_TIMES = List.of(LocalTime.of(9, 0), LocalTime.of(14, 0));
    /** 放普通号的格：每半天的第二格。上午 10:30、下午 15:30。 */
    private static final List<LocalTime> NORMAL_TIMES = List.of(LocalTime.of(10, 30), LocalTime.of(15, 30));

    /** 上午 / 下午的分界。回归用例按它把号源分时段，别在别处再写一个 12:00。 */
    public static final LocalTime NOON = LocalTime.of(12, 0);

    private static final DateTimeFormatter ID_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    /** 号别：普通号 / 专家号。号别是**手工写在医生上**的，不按职称推导（职称只用于展示）。 */
    public static final String SLOT_TYPE_NORMAL = "NORMAL";
    public static final String SLOT_TYPE_EXPERT = "EXPERT";

    /**
     * 挂号费（分）。专家号贵一档，演示要讲得出价格差。这两个数就是号源的**预估自付**——
     * 演示里不再单独算医保报销比例，所以口播念的就是它。
     *
     * <p>改这两个常数就够了，回归用例只做**相对**断言（最便宜的专家号 &gt; 最贵的普通号；
     * 只有两档且都不小于 1000 分），不会跟着写死。
     */
    public static final int NORMAL_FEE_CENTS = 2500;
    public static final int EXPERT_FEE_CENTS = 4000;

    /**
     * 一条号源能接几位——即这一班放几个名额。
     *
     * <p>按号别取，与 {@code fee_cents} 一样只做「号别 → 数值」一步映射。**不挂在 {@code doctors}
     * 上**：名额是「这一班放几个」的属性，属于号源这一层；挂在医生上会变成「主任永远 3 个名额」
     * 这种写死的耦合，也重犯「挂号费不能挂医生」那条错误（DEC-024 决定三同源）。
     */
    public static final int EXPERT_CAPACITY = 3;
    public static final int NORMAL_CAPACITY = 4;

    /**
     * 一个科室配几位医生。一天 4 格、每格 1 人，所以正好 4 位：
     * 01 主任医师(专家号)、02 主治医师(普通号)、03 主治医师(普通号)、04 副主任医师(专家号)。
     * 主任 / 副主任只出专家号、主治只出普通号，于是 2 个专家格由 01 / 04 分、2 个普通格由 02 / 03 分，
     * 四位医生工作量完全相等。
     * data.sql 必须照这个数种，缺一位这里会直接报错，不会静默少排一班。
     */
    public static final int MAX_DOCTORS_PER_DEPARTMENT = 4;

    private final JdbcTemplate jdbc;

    public RollingAppointmentSlotInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 号源 id 的拼法只此一处：场景重置、回滚重放和回归用例都按这个规则找号源。
     *
     * <p>带上医生 id 而不是科室 id——同一科室同一天同一时段，不同医生各有一条号源，
     * 不带医生就会撞主键。医生 id 里本来就含科室 id（{@code doc-d001-01}），
     * 所以从号源 id 依然能看出科室。
     */
    public static String slotId(String doctorId, LocalDate date, LocalTime time) {
        return "r-" + doctorId + "-" + date.format(ID_DATE) + "-"
                + String.format("%02d%02d", time.getHour(), time.getMinute());
    }

    /** 医生 id：科室 id 打头，看号源 id 就能反推科室。与 data.sql 的种子写法一致。 */
    public static String doctorId(String departmentId, int sequence) {
        return "doc-" + departmentId + "-" + String.format("%02d", sequence);
    }

    /**
     * 每个科室每周固定放号的那 3 个工作日（ISO：1=周一 … 5=周五）。
     *
     * <p>各科室**错开**，避免全医院同一天扎堆放号；但每个科室固定、不随周次变化——这样
     * 演示与回归的日期坐标（体检日=下周三、改期日=下周五）永远成立，不会因为某周的排班
     * 漂到别的星期而突然变红。
     *
     * <p>12 个科室装不下「全院两两不同」：{@code {周一..周五}} 里选 3 天只有
     * C(5,3) = **10** 种组合，所以唯一性的口径是「**同一家医院内两两不同**」（每院 6 科，
     * 10 选 6 绰绰有余），跨医院允许撞同一天。回归用例按这个口径断言。
     *
     * <p>两个**不能随手改**的点：
     *
     * <ul>
     *   <li>d001（市第一医院·心内科）**必须含周三**：冲突场景把「下周三体检 + 心内科 10:30
     *       号源」钉在那天（见 {@code DemoSeedDataTests}），挪掉周三整个冲突演示就废了。</li>
     *   <li>d001 **必须含周五**：「下周六无号 → 往后三天的附近日期」这条降级路径要取得到号
     *       （见 {@code MockAppointmentAlternativesTests}），只有周一/周三填不满这个三天窗口。</li>
     * </ul>
     */
    public static Set<Integer> openWeekdays(String departmentId) {
        return switch (departmentId) {
            // —— 市第一医院 h001 ——
            case "d001" -> Set.of(1, 3, 5);  // 心内科：周一 / 周三 / 周五（锁死：含周三 + 周五）
            case "d002" -> Set.of(2, 4, 5);  // 神经内科：周二 / 周四 / 周五
            case "d005" -> Set.of(2, 3, 4);  // 内分泌科：周二 / 周三 / 周四
            case "d007" -> Set.of(1, 2, 4);  // 骨科：周一 / 周二 / 周四
            case "d008" -> Set.of(1, 3, 4);  // 呼吸内科：周一 / 周三 / 周四
            case "d009" -> Set.of(1, 4, 5);  // 消化内科：周一 / 周四 / 周五
            // —— 市人民医院 h002 ——
            case "d003" -> Set.of(1, 2, 5);  // 内分泌科：周一 / 周二 / 周五
            case "d004" -> Set.of(1, 2, 4);  // 骨科：周一 / 周二 / 周四
            case "d006" -> Set.of(3, 4, 5);  // 心内科：周三 / 周四 / 周五
            case "d010" -> Set.of(1, 2, 3);  // 神经内科：周一 / 周二 / 周三
            case "d011" -> Set.of(2, 3, 5);  // 呼吸内科：周二 / 周三 / 周五
            case "d012" -> Set.of(2, 4, 5);  // 消化内科：周二 / 周四 / 周五
            default -> Set.of(1, 3, 5);
        };
    }

    /** 这个科室在这天放不放号。周末一律不放。 */
    public static boolean isOpenDay(String departmentId, LocalDate date) {
        int weekday = date.getDayOfWeek().getValue();
        return weekday <= 5 && openWeekdays(departmentId).contains(weekday);
    }

    /**
     * 专家格（09:00 / 14:00）坐哪一位：**两位主任对调**。
     *
     * <p>第 2 个放号日（{@code role == 1}）上下午对调，第 1、3 个放号日「上午 01 / 下午 04」。
     * 于是 01 与 04 号各出 3 格、每天恰好 1 格，谁也压不过谁。
     */
    public static int expertAt(int role, LocalTime time) {
        boolean morning = time.isBefore(NOON);
        boolean swapped = role == 1;
        if (morning) {
            return swapped ? 4 : 1;
        }
        return swapped ? 1 : 4;
    }

    /** 普通格（10:30 / 15:30）坐哪一位：**两位主治对调**，口径与 {@link #expertAt} 一致。 */
    public static int normalAt(int role, LocalTime time) {
        boolean morning = time.isBefore(NOON);
        boolean swapped = role == 1;
        if (morning) {
            return swapped ? 3 : 2;
        }
        return swapped ? 2 : 3;
    }

    /**
     * 这一天、这一格坐哪一位医生（序号）。不属于 4 个固定格时返回 0。
     *
     * <p>号别按格固定——每半天的第一格是专家格、第二格是普通格 —— 所以直接按时刻派给
     * {@link #expertAt} 或 {@link #normalAt}。
     */
    public static int doctorAt(int role, LocalTime time) {
        if (EXPERT_TIMES.contains(time)) {
            return expertAt(role, time);
        }
        if (NORMAL_TIMES.contains(time)) {
            return normalAt(role, time);
        }
        return 0;
    }

    /**
     * 某天某个时段出诊的医生序号（0 位或 1 位）。
     *
     * <p>给 {@code DemoSeed} 反推号源 id 用：这天不放号、或者这个时刻不在四个固定时刻里，
     * 都返回空列表——调用方据此报错，而不是悄悄编一条 id。
     *
     * <p>返回 {@code List} 而不是裸的 int，是为了**从「按半天返回两位」平滑过来**：
     * 调用方原来就写着 {@code .get(0)}，语义没变，只是列表长度从 2 变成了 1。
     */
    public static List<Integer> doctorsAt(String departmentId, LocalDate date, LocalTime time) {
        int role = roleIndex(departmentId, date);
        if (role < 0) {
            return List.of();
        }
        int sequence = doctorAt(role, time);
        return sequence == 0 ? List.of() : List.of(sequence);
    }

    /**
     * 这天是本科室本周的第几个放号日（0 / 1 / 2）。
     *
     * <p>上午谁坐诊、下午排几位都按这个序号轮换，于是同一个科室一周里的三天各不相同，
     * 整体又完全确定。不放号的日子返回 -1。
     */
    private static int roleIndex(String departmentId, LocalDate date) {
        int weekday = date.getDayOfWeek().getValue();
        int index = 0;
        for (int open : openWeekdays(departmentId).stream().sorted().toList()) {
            if (open == weekday) {
                return index;
            }
            index++;
        }
        return -1;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /** 幂等：已经对得上的号源不会重复插入，场景重置可以直接再调一次。 */
    public void seed() {
        removeStaleGeneratedSlots();

        List<DepartmentSeed> departments = jdbc.query("""
                SELECT d.id, d.hospital_id, h.name, d.name
                FROM departments d
                JOIN hospitals h ON h.id = d.hospital_id
                WHERE d.enabled = TRUE AND h.enabled = TRUE
                """, (rs, rowNum) -> new DepartmentSeed(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));

        // 「今天」取业务时区（BusinessClock），不是 JVM 默认时区：播种窗口的起点必须和
        // 查号链路的「今天」同一天，否则容器没配时区时会出现「今天还没播种」这种自相矛盾的状态。
        LocalDate start = BusinessClock.demoToday();
        LocalDate end = start.plusMonths(1);
        for (DepartmentSeed department : departments) {
            Map<Integer, DoctorSeed> doctors = doctorsOf(department.id());
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                int role = roleIndex(department.id(), date);
                if (role < 0) {
                    continue;
                }
                // 一天 4 格，一格一位医生——每格放什么号别由时刻决定（见 doctorAt）。
                for (LocalTime time : ALL_TIMES) {
                    insertIfMissing(department, doctorOf(doctors, department, doctorAt(role, time)), date, time);
                }
            }
        }

        reconcileCapacities();
    }

    /**
     * 把存量行上的名额与派生位拉回不变式。
     *
     * <p>为什么需要这一步：{@link #insertIfMissing} 是按 id 判重的，而号源 id 的拼法
     * （{@code r-<医生id>-<日期>-<时刻>}）**没有变**，所以上一版排班生成、又**有预约挂着**
     * 因而被 {@link #removeStaleGeneratedSlots} 留下的那几行，会停在建表时的默认值
     * {@code capacity=1}——它们长得和新号源一样，但只放得出一个名额。
     *
     * <p>四条 UPDATE 都是幂等的，代价可忽略。顺序有讲究：先按实际预约数回填 {@code booked}，
     * 再按号别修正 {@code capacity}，最后才推导 {@code available}——否则会出现
     * 「刚把 capacity 拉大、booked 还没数对」的中间态被写进 available。
     */
    private void reconcileCapacities() {
        jdbc.update("""
                UPDATE appointment_slots s
                   SET booked = (SELECT COUNT(*) FROM appointments a
                                  WHERE a.slot_id = s.id AND a.status='CONFIRMED')
                 WHERE s.id LIKE 'r-%'
                """);
        jdbc.update("UPDATE appointment_slots SET capacity=? WHERE slot_type=? AND capacity<>?",
                EXPERT_CAPACITY, SLOT_TYPE_EXPERT, EXPERT_CAPACITY);
        jdbc.update("UPDATE appointment_slots SET capacity=? WHERE slot_type=? AND capacity<>?",
                NORMAL_CAPACITY, SLOT_TYPE_NORMAL, NORMAL_CAPACITY);
        // 派生位重算：available = (booked < capacity)。两个方向都扫，把漂移拉回来。
        jdbc.update("UPDATE appointment_slots SET available=TRUE  WHERE booked <  capacity AND available=FALSE");
        jdbc.update("UPDATE appointment_slots SET available=FALSE WHERE booked >= capacity AND available=TRUE");
    }

    private List<DoctorSeed> doctorRowsOf(String departmentId) {
        return jdbc.query("""
                SELECT id, name, title, slot_type
                FROM doctors
                WHERE department_id = ? AND enabled = TRUE
                ORDER BY id
                """, (rs, rowNum) -> new DoctorSeed(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), departmentId);
    }

    /** 按序号索引，排班只认序号，不认 id 的写法。 */
    private Map<Integer, DoctorSeed> doctorsOf(String departmentId) {
        return doctorRowsOf(departmentId).stream()
                .collect(Collectors.toMap(seed -> sequenceOf(seed.id()), seed -> seed));
    }

    /**
     * 排班要的那位医生；科室缺人时直接报错。
     *
     * <p>不静默跳过：少一位医生意味着某个上午少了「1 专家 + 1 普通」里的半边，
     * 或者下午空了一班，而这在界面上看不出来是缺数据还是今天没号。
     */
    private static DoctorSeed doctorOf(Map<Integer, DoctorSeed> doctors,
                                       DepartmentSeed department, int sequence) {
        DoctorSeed doctor = doctors.get(sequence);
        if (doctor == null) {
            throw new IllegalStateException("科室 " + department.id() + " 缺第 " + sequence
                    + " 位医生，排班排不出来——请检查 data.sql 里这个科室的医生种子");
        }
        return doctor;
    }

    private static int sequenceOf(String doctorId) {
        return Integer.parseInt(doctorId.substring(doctorId.lastIndexOf('-') + 1));
    }

    /**
     * 把上一版排班规则生成、现在**已经对不上**的号源整批清掉重建。
     *
     * <p>排班已经换过两次口径（先「每位医生错开出诊日」改成「每科室每周固定 3 天 + 上午/下午
     * 半天制」，再把 03 号从副主任医师改为主治医师、专家号号源改成只在上午出诊），旧号源
     * 既可能落在不再放号的日期上，也可能和新排班撞在同一时刻，留着只会两套并存。
     * 顺带把已经过期的日期也收干净，库不会随运行天数一直往上漂。
     *
     * <p>**唯一不动的是有预约挂着的那几条**。预约列表是 {@code JOIN appointment_slots} 出来的，
     * 号源一删，那条预约在页面上会直接消失、连取消都点不到。它们就此留下，没有医生信息，
     * 前端如实显示「医生信息未记录」。
     */
    private void removeStaleGeneratedSlots() {
        jdbc.update("""
                DELETE FROM appointment_slots
                WHERE id LIKE 'r-%'
                  AND NOT EXISTS (SELECT 1 FROM appointments a WHERE a.slot_id = appointment_slots.id)
                """);
    }

    private void insertIfMissing(DepartmentSeed department, DoctorSeed doctor,
                                 LocalDate date, LocalTime time) {
        String id = slotId(doctor.id(), date, time);
        // 判重按 id：id 里已经含医生 + 日期 + 时刻，这就是这条号源的身份。
        // 用「医院+科室+日期+时刻」判重的话，同一时段的第二位医生会被当成已存在而整批漏掉。
        jdbc.update("""
                INSERT INTO appointment_slots
                    (id, hospital_id, hospital_name, department, appointment_date, appointment_time,
                     available, doctor_id, slot_type, department_id, fee_cents, capacity, booked)
                SELECT ?, ?, ?, ?, ?, ?, TRUE, ?, ?, ?, ?, ?, 0
                WHERE NOT EXISTS (SELECT 1 FROM appointment_slots WHERE id = ?)
                """,
                id, department.hospitalId(), department.hospitalName(), department.name(), date, time,
                doctor.id(), doctor.slotType(), department.id(), feeCentsFor(doctor.slotType()),
                capacityFor(doctor.slotType()), id);
    }

    /** 挂号费按号别取。号别本身是手工指定的，这里只做「号别 → 价格」这一步映射。 */
    private static int feeCentsFor(String slotType) {
        return SLOT_TYPE_EXPERT.equals(slotType) ? EXPERT_FEE_CENTS : NORMAL_FEE_CENTS;
    }

    /** 名额按号别取，与 {@link #feeCentsFor} 同源：专家号 3 个、普通号 4 个。 */
    private static int capacityFor(String slotType) {
        return SLOT_TYPE_EXPERT.equals(slotType) ? EXPERT_CAPACITY : NORMAL_CAPACITY;
    }

    private record DepartmentSeed(String id, String hospitalId, String hospitalName, String name) {
    }

    private record DoctorSeed(String id, String name, String title, String slotType) {
    }
}
