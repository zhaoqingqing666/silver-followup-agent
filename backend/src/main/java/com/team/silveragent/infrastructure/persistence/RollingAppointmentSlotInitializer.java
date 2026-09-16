package com.team.silveragent.infrastructure.persistence;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Keeps the demo catalogue useful: every department has slots from today to one month later. */
@Component
public class RollingAppointmentSlotInitializer implements ApplicationRunner {

    private static final List<LocalTime> TIMES = List.of(
            LocalTime.of(9, 0), LocalTime.of(10, 30),
            LocalTime.of(14, 0), LocalTime.of(15, 30));
    private static final DateTimeFormatter ID_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final JdbcTemplate jdbc;

    public RollingAppointmentSlotInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 号源 id 的拼法只此一处：回滚重放、场景重置和回归用例都按这个规则找号源。 */
    public static String slotId(String departmentId, LocalDate date, LocalTime time) {
        return "r-" + departmentId + "-" + date.format(ID_DATE) + "-"
                + String.format("%02d%02d", time.getHour(), time.getMinute());
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /** 幂等：已经在的号源不会重复插入，场景重置可以直接再调一次。 */
    public void seed() {
        // Weekend gaps are deliberate so the Demo can still demonstrate the
        // required "requested date has no slots" recovery path.
        List<String> generatedWeekendSlots = jdbc.query("""
                SELECT s.id, s.appointment_date
                FROM appointment_slots s
                WHERE s.id LIKE 'r-%'
                  AND NOT EXISTS (SELECT 1 FROM appointments a WHERE a.slot_id = s.id)
                """, (rs, rowNum) -> rs.getDate(2).toLocalDate().getDayOfWeek().getValue() >= 6
                ? rs.getString(1) : null).stream().filter(java.util.Objects::nonNull).toList();
        generatedWeekendSlots.forEach(id -> jdbc.update("DELETE FROM appointment_slots WHERE id = ?", id));
        // 医院名和科室名不在这张表里存：号源只记 hospital / department 两个编号，
        // 名称由查询 JOIN 取回。所以这里只需要科室编号和它所属的医院编号。
        List<DepartmentSeed> departments = jdbc.query("""
                SELECT d.id, d.hospital_id
                FROM departments d
                JOIN hospitals h ON h.id = d.hospital_id
                WHERE d.enabled = TRUE AND h.enabled = TRUE
                """, (rs, rowNum) -> new DepartmentSeed(
                rs.getString(1), rs.getString(2)));

        LocalDate start = LocalDate.now();
        LocalDate end = start.plusMonths(1);
        for (DepartmentSeed department : departments) {
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                if (date.getDayOfWeek().getValue() >= 6) {
                    continue;
                }
                for (LocalTime time : TIMES) {
                    insertIfMissing(department, date, time);
                }
            }
        }
    }

    private void insertIfMissing(DepartmentSeed department, LocalDate date, LocalTime time) {
        String id = slotId(department.id(), date, time);
        jdbc.update("""
                INSERT INTO appointment_slots
                    (id, hospital, department, appointment_date, appointment_time, available)
                SELECT ?, ?, ?, ?, ?, TRUE
                WHERE NOT EXISTS (
                    SELECT 1 FROM appointment_slots
                    WHERE hospital = ? AND department = ? AND appointment_date = ? AND appointment_time = ?
                )
                """,
                id, department.hospitalId(), department.id(), date, time,
                department.hospitalId(), department.id(), date, time);
    }

    private record DepartmentSeed(String id, String hospitalId) {
    }
}
