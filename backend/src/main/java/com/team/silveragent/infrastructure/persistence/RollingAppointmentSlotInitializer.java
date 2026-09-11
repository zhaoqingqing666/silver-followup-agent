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

    @Override
    public void run(ApplicationArguments args) {
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
        List<DepartmentSeed> departments = jdbc.query("""
                SELECT d.id, d.hospital_id, h.name, d.name
                FROM departments d
                JOIN hospitals h ON h.id = d.hospital_id
                WHERE d.enabled = TRUE AND h.enabled = TRUE
                """, (rs, rowNum) -> new DepartmentSeed(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));

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
        String id = "r-" + department.id() + "-" + date.format(ID_DATE)
                + "-" + String.format("%02d%02d", time.getHour(), time.getMinute());
        jdbc.update("""
                INSERT INTO appointment_slots
                    (id, hospital_id, hospital_name, department, appointment_date, appointment_time, available)
                SELECT ?, ?, ?, ?, ?, ?, TRUE
                WHERE NOT EXISTS (
                    SELECT 1 FROM appointment_slots
                    WHERE hospital_id = ? AND department = ? AND appointment_date = ? AND appointment_time = ?
                )
                """,
                id, department.hospitalId(), department.hospitalName(), department.name(), date, time,
                department.hospitalId(), department.name(), date, time);
    }

    private record DepartmentSeed(String id, String hospitalId, String hospitalName, String name) {
    }
}
