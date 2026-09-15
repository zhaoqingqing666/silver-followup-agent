package com.team.silveragent.infrastructure.mock;

import com.team.silveragent.application.BusinessClock;
import com.team.silveragent.domain.model.ToolModels.Slot;
import com.team.silveragent.domain.tool.AppointmentTool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class MockAppointmentTool implements AppointmentTool {

    /**
     * 三个查号查询共用的取数与排序。
     *
     * <p>号源的医生信息靠 {@code LEFT JOIN}——旧号源没有 {@code doctor_id}，用内连接会让它们
     * 整条消失，比「查出来没有医生」糟得多。排序里带上 {@code doctor_id}：同一时刻一般只有
     * 一位医生在诊，但真撞上时也要有个稳定次序，否则同一批数据的候选顺序每次都不一样。
     *
     * <p><b>这里没有 {@code CURRENT_DATE / CURRENT_TIME}</b>：「当天已过的时段」由
     * {@link BusinessClock} 在 Java 侧滤掉。放在 SQL 里等于把业务口径挂在数据库时钟上，
     * 而 H2 的时钟既跟容器环境走、又不吃 {@code TimeZone.setDefault}（详见 {@link BusinessClock}）。
     */
    private static final String SLOT_SELECT = """
            SELECT s.id,s.hospital_id,s.hospital_name,s.department,s.appointment_date,s.appointment_time,
                   s.doctor_id,d.name,d.title,s.slot_type,s.fee_cents
            FROM appointment_slots s
            LEFT JOIN doctors d ON d.id=s.doctor_id
            """;

    private final JdbcTemplate jdbc;
    private final ToolTraceStore traces;
    private final BusinessClock clock;

    public MockAppointmentTool(JdbcTemplate jdbc, ToolTraceStore traces, BusinessClock clock) {
        this.jdbc = jdbc;
        this.traces = traces;
        this.clock = clock;
    }

    @Override
    public List<Slot> queryAvailableSlots(String conversationId, String hospitalId, String department, LocalDate date) {
        Map<String, Object> input = Map.of("hospitalId", hospitalId, "department", department, "date", date);
        List<Slot> result = bookable(daySlots(hospitalId, department, date));
        traces.record(conversationId, "appointment.querySlots", input, result, true);
        return result;
    }

    @Override
    public List<Slot> queryDaySlots(String conversationId, String hospitalId, String department, LocalDate date) {
        Map<String, Object> input = Map.of("hospitalId", hospitalId, "department", department, "date", date);
        List<Slot> result = daySlots(hospitalId, department, date);
        traces.record(conversationId, "appointment.queryDaySlots", input, result, true);
        return result;
    }

    @Override
    public List<Slot> queryUpcomingSlots(String conversationId, String hospitalId, String department,
                                         LocalDate from, LocalDate to) {
        Map<String, Object> input = Map.of("hospitalId", hospitalId, "department", department,
                "from", from, "to", to);
        List<Slot> result = bookable(jdbc.query(SLOT_SELECT + """
                WHERE s.hospital_id=? AND s.department=?
                  AND s.appointment_date BETWEEN ? AND ? AND s.available=TRUE
                ORDER BY s.appointment_date,s.appointment_time,s.doctor_id
                """, slotMapper(), hospitalId, department, Date.valueOf(from), Date.valueOf(to)));
        traces.record(conversationId, "appointment.queryUpcomingSlots", input, result, true);
        return result;
    }

    @Override
    public List<Slot> queryAlternatives(String conversationId, String hospitalId, String department, LocalDate date) {
        // 只往后看：往前找会捞出已经过去的时段，把当天其它时段也算进“附近日期”还会
        // 跟上一句“这一天暂无号源”自相矛盾。当天之内换时段由 SELECT_PERIOD 那条路负责。
        Map<String, Object> input = Map.of("hospitalId", hospitalId, "department", department,
                "from", date.plusDays(1), "to", date.plusDays(3));
        List<Slot> result = bookable(jdbc.query(SLOT_SELECT + """
                WHERE s.hospital_id=? AND s.department=?
                  AND s.appointment_date BETWEEN ? AND ? AND s.available=TRUE
                ORDER BY s.appointment_date,s.appointment_time,s.doctor_id
                """, slotMapper(), hospitalId, department,
                Date.valueOf(date.plusDays(1)), Date.valueOf(date.plusDays(3))));
        traces.record(conversationId, "appointment.queryAlternatives", input, result, true);
        return result;
    }

    /** 这一天仍可预约的号源，**不看时刻**（含当天已经过去的时段）。取数与排序见 {@link #SLOT_SELECT}。 */
    private List<Slot> daySlots(String hospitalId, String department, LocalDate date) {
        return jdbc.query(SLOT_SELECT + """
                WHERE s.hospital_id=? AND s.department=?
                  AND s.appointment_date=? AND s.available=TRUE
                ORDER BY s.appointment_time,s.doctor_id
                """, slotMapper(), hospitalId, department, Date.valueOf(date));
    }

    /**
     * 滤掉已经开始的时段——判据是业务时钟，不是数据库时钟。
     *
     * <p>「到了就算过」：正好等于现在的那一格也不再挂出来，与旧的 {@code > CURRENT_TIME} 同口径。
     */
    private List<Slot> bookable(List<Slot> slots) {
        return slots.stream()
                .filter(slot -> !clock.isPast(LocalDateTime.of(slot.date(), slot.time())))
                .toList();
    }

    /**
     * 建立一条预约，返回这条预约的 id。
     *
     * <p>**幂等只对「同一次提交」成立**：会话 + 就诊人 + **具体号源**三者都相同，才算这次提交重复，
     * 直接返回上一条、不再建。老实现只比 {@code conversation_id + user_id + status='CONFIRMED'}，
     * 等于把「这段会话里已经约过一次」当成了「这次提交是重复的」——同一段会话里先约 09:00 再约
     * 10:30 时（老人端「新建办理」会把 {@code appointmentId} 清空，所以第二次确实走得到这里），
     * 前置的 {@code checkDuplicate} 已经按「患者 + 日期 + 时段」放行，这里却把 09:00 那条原样返回：
     * 页面显示 10:30 办成了，库里还是 09:00 那一条。
     *
     * <p>为什么键里要带上号源：**一位就诊人 + 同一个号源**只可能是同一次提交——同一个人不会在
     * 同一班号里给自己连约两次（真要同一天看两次，那是另一条号源）。所以这个键不会误伤
     * 「同一天换个时段再约一次」。号源 id 里本来就含医生 + 日期 + 时刻
     * （见 {@code RollingAppointmentSlotInitializer.slotId}），时间维度因此天然包含在内。
     *
     * <p>号源改成「一班多个名额」（{@code capacity} / {@code booked}）之后这个键依然够用：
     * 名额解决的是「一班能接几位**不同**的就诊人」，幂等解决的是「同一个人别把同一次提交落两遍」，
     * 两件事互不干扰。
     *
     * <p>真正的冲突（患者 + 日期 + 时段）不在这里判：那是
     * {@code FollowupAgentService.checkDuplicate} 的职责，本方法没有把它搬过来，也没有削弱它。
     *
     * <p>顺便记一笔：全链路没有 {@code requestId} / {@code toolCallId} 这类能标识「同一次提交」的
     * 字段（{@code CareBookingService} 里那个 {@code bookingId} 是每次调用现生成的追踪号，标识不了
     * 重复请求）。当前的键已经够用，不为此改库表。
     */
    @Override
    @Transactional
    public String submit(String conversationId, String slotId, String userId) {
        List<String> sameSubmission = jdbc.query("""
                SELECT id FROM appointments
                WHERE conversation_id=? AND user_id=? AND slot_id=? AND status='CONFIRMED'
                ORDER BY created_at
                """, (rs, row) -> rs.getString(1), conversationId, userId, slotId);
        if (!sameSubmission.isEmpty()) return sameSubmission.get(0);
        // 还有名额就 +1；正好填满最后一位时把 available 落下，让查询侧照旧看不见它。
        // 条件里那句 booked < capacity 是**乐观锁**：两个并发提交抢最后一个名额时，
        // 只有一方的 UPDATE 能命中（changed == 1），另一方拿到 0 并吃到下面的异常。
        int changed = jdbc.update("""
                UPDATE appointment_slots
                   SET booked = booked + 1,
                       available = CASE WHEN booked + 1 < capacity THEN TRUE ELSE FALSE END
                 WHERE id=? AND booked < capacity
                """, slotId);
        if (changed != 1) throw new IllegalStateException("该号源刚刚已不可用，请重新选择");
        String id = "AP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        DoctorSnapshot doctor = doctorSnapshotOf(slotId);
        jdbc.update("""
                INSERT INTO appointments
                    (id,slot_id,user_id,status,created_at,conversation_id,
                     doctor_name,doctor_title,slot_type,fee_cents)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,
                id, slotId, userId, "CONFIRMED", Timestamp.valueOf(clock.nowDateTime()), conversationId,
                doctor.name(), doctor.title(), doctor.slotType(), doctor.feeCents());
        traces.record(conversationId, "appointment.submit", Map.of("slotId", slotId, "userId", userId),
                Map.of("appointmentId", id, "status", "CONFIRMED"), true);
        return id;
    }

    @Override
    @Transactional
    public String cancel(String conversationId, String appointmentId, String userId) {
        String slotId = jdbc.queryForObject(
                "SELECT slot_id FROM appointments WHERE id=? AND user_id=? AND status='CONFIRMED'",
                String.class, appointmentId, userId);
        int changed = jdbc.update(
                "UPDATE appointments SET status='CANCELLED' WHERE id=? AND user_id=? AND status='CONFIRMED'",
                appointmentId, userId);
        if (changed != 1) throw new IllegalStateException("没有找到可取消的预约");
        // 空出一个名额就必然重新可约；clamp 住 0，避免异常路径把 booked 减成负数。
        jdbc.update("""
                UPDATE appointment_slots
                   SET booked = CASE WHEN booked > 0 THEN booked - 1 ELSE 0 END,
                       available = TRUE
                 WHERE id=?
                """, slotId);
        jdbc.update("UPDATE reminders SET status='CANCELLED' WHERE appointment_id=?", appointmentId);
        jdbc.update("UPDATE appointments SET reminder_status='关联提醒已取消' WHERE id=?", appointmentId);
        traces.record(conversationId, "appointment.cancel",
                Map.of("appointmentId", appointmentId, "userId", userId),
                Map.of("status", "CANCELLED", "slotReleased", true), true);
        return "CANCELLED";
    }

    /** 批量取消先校验整批归属与状态，再在一个事务里逐条执行；任一条失效则整批不动。 */
    @Override
    @Transactional
    public List<String> cancelAll(String conversationId, List<String> appointmentIds, String userId) {
        List<String> ids = appointmentIds == null ? List.of() : appointmentIds.stream().distinct().toList();
        if (ids.isEmpty()) throw new IllegalArgumentException("没有选择要取消的预约");
        for (String id : ids) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM appointments WHERE id=? AND user_id=? AND status='CONFIRMED'",
                    Integer.class, id, userId);
            if (count == null || count != 1) throw new IllegalStateException("有预约已经变更，整批没有取消");
        }
        for (String id : ids) cancel(conversationId, id, userId);
        traces.record(conversationId, "appointment.cancelBatch",
                Map.of("appointmentIds", ids, "userId", userId),
                Map.of("status", "CANCELLED", "count", ids.size()), true);
        return ids;
    }

    @Override
    @Transactional
    public String reschedule(String conversationId, String appointmentId, String slotId, String userId) {
        String oldSlot = jdbc.queryForObject("SELECT slot_id FROM appointments WHERE id=? AND user_id=? AND status='CONFIRMED'", String.class, appointmentId, userId);
        if (!slotId.equals(oldSlot)) {
            int changed = jdbc.update("""
                    UPDATE appointment_slots
                       SET booked = booked + 1,
                           available = CASE WHEN booked + 1 < capacity THEN TRUE ELSE FALSE END
                     WHERE id=? AND booked < capacity
                    """, slotId);
            if (changed != 1) throw new IllegalStateException("新号源不可用，原预约保留");
            jdbc.update("""
                    UPDATE appointment_slots
                       SET booked = CASE WHEN booked > 0 THEN booked - 1 ELSE 0 END,
                           available = TRUE
                     WHERE id=?
                    """, oldSlot);
        }
        // 换号源就得换医生快照：新号源可能是另一位医生、另一种号别、另一个挂号费。
        // 快照不跟着改，页面上就会写着「张建国 主任医师」，实际约的却是李秀英。
        DoctorSnapshot doctor = doctorSnapshotOf(slotId);
        jdbc.update("""
                UPDATE appointments
                SET slot_id=?,conversation_id=?,doctor_name=?,doctor_title=?,slot_type=?,fee_cents=?
                WHERE id=?
                """, slotId, conversationId, doctor.name(), doctor.title(), doctor.slotType(),
                doctor.feeCents(), appointmentId);
        jdbc.update("UPDATE reminders SET status='CANCELLED' WHERE appointment_id=?", appointmentId);
        traces.record(conversationId, "appointment.reschedule", Map.of("appointmentId", appointmentId, "slotId", slotId), Map.of("status", "CONFIRMED"), true);
        return appointmentId;
    }

    /**
     * 取这条号源的医生快照，写进预约行。
     *
     * <p>为什么要拷贝一份而不是每次去 join 号源查：号源是滚动重建的（周末号源会删、
     * 过去的日期会过期），预约记录得自己说得清「当时约的是谁」。
     *
     * <p>取不到医生（旧号源没有 {@code doctor_id}）就整组存空：前端如实显示「医生信息未记录」，
     * 不替它编一位医生——那比空着更糟，老人会拿着一个假名字去医院。
     */
    private DoctorSnapshot doctorSnapshotOf(String slotId) {
        List<DoctorSnapshot> rows = jdbc.query("""
                SELECT d.name,d.title,s.slot_type,s.fee_cents
                FROM appointment_slots s
                LEFT JOIN doctors d ON d.id=s.doctor_id
                WHERE s.id=?
                """, (rs, row) -> new DoctorSnapshot(
                rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getObject(4) == null ? null : rs.getInt(4)), slotId);
        return rows.isEmpty() ? new DoctorSnapshot(null, null, null, null) : rows.get(0);
    }

    private org.springframework.jdbc.core.RowMapper<Slot> slotMapper() {
        return (rs, row) -> new Slot(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getDate(5).toLocalDate(), rs.getTime(6).toLocalTime(),
                rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getObject(11) == null ? null : rs.getInt(11));
    }

    /** 预约行上的医生快照；旧号源没有医生时四个值全空。 */
    private record DoctorSnapshot(String name, String title, String slotType, Integer feeCents) {
    }

}
