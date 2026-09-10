package com.team.silveragent.infrastructure;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 号源保底生成器：data.sql 种子里有一部分写死的演示日期（如 9/17-9/21）。
 * 为避免演示当天已超过这些日期导致“无号可约”，每次启动检查核心科室未来可约日期数量；
 * 当某个科室未来可约的日期不足 {@value #MIN_FUTURE_DAYS} 天时，按“今天往后”补足未来几天的号。
 * 只做补录、不清空、不覆盖已有号；老人端与代约流程读取的仍是同一张 appointment_slots 表，无需改动。
 */
@Component
public class SlotAvailabilityInitializer implements ApplicationRunner {
    private static final int MIN_FUTURE_DAYS = 2;
    private static final int WINDOW_DAYS = 6;
    private static final DateTimeFormatter MMdd = DateTimeFormatter.ofPattern("MMdd");

    private record Clinic(String hospitalId, String hospitalName, String departmentId,
                          String department, List<String> times) { }

    /** 与 data.sql 中的演示科室保持一致：市第一医院·心内科、市人民医院·内分泌科。 */
    private static final List<Clinic> CLINICS = List.of(
            new Clinic("h001", "市第一医院（模拟）", "d001", "心内科",
                    List.of("09:00", "10:20", "14:30", "16:00")),
            new Clinic("h002", "市人民医院（模拟）", "d003", "内分泌科",
                    List.of("09:00", "10:30", "14:00", "15:00")));

    private final JdbcTemplate jdbc;

    public SlotAvailabilityInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Clinic clinic : CLINICS) {
            Integer futureDays = jdbc.queryForObject("""
                    SELECT COUNT(DISTINCT appointment_date) FROM appointment_slots
                    WHERE hospital_id=? AND department=? AND appointment_date>=CURRENT_DATE AND available=TRUE
                    """, Integer.class, clinic.hospitalId(), clinic.department());
            if (futureDays != null && futureDays >= MIN_FUTURE_DAYS) continue;
            for (int offset = 1; offset <= WINDOW_DAYS; offset++) {
                LocalDate day = LocalDate.now().plusDays(offset);
                for (String time : clinic.times()) {
                    ensureSlot(clinic, day, time);
                }
            }
        }
    }

    private void ensureSlot(Clinic clinic, LocalDate day, String time) {
        LocalTime parsed = LocalTime.parse(time);
        Integer exists = jdbc.queryForObject("""
                SELECT COUNT(*) FROM appointment_slots
                WHERE hospital_id=? AND department=? AND appointment_date=? AND appointment_time=?
                """, Integer.class, clinic.hospitalId(), clinic.department(), day, parsed);
        if (exists != null && exists > 0) return;
        String id = "slot-" + clinic.hospitalId() + "-" + clinic.departmentId() + "-"
                + day.format(MMdd) + "-" + time.replace(":", "");
        jdbc.update("""
                INSERT INTO appointment_slots(id,hospital_id,hospital_name,department,appointment_date,appointment_time,available)
                VALUES (?,?,?,?,?,?,TRUE)
                """, id, clinic.hospitalId(), clinic.hospitalName(), clinic.department(), day, parsed);
    }
}
