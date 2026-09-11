package com.team.silveragent.infrastructure.persistence;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;

/**
 * 用户已有日程的滚动种子。
 *
 * <p>场景三（时间冲突）依赖一条与号源重叠的日程。如果把它写死在 data.sql 里，
 * 演示寿命就截止到那个日期；这里按“今天”计算，保证任何时候启动都能复现冲突。
 * 时间取 10:00-11:00，与号源档位 10:30 必然重叠（见 {@link RollingAppointmentSlotInitializer}）。
 */
@Component
public class RollingUserScheduleInitializer implements ApplicationRunner {

    /** 冲突日程的固定主键，便于测试与场景重置定位。 */
    public static final String CONFLICT_SCHEDULE_ID = "schedule-001";
    private static final String OTHER_SCHEDULE_ID = "schedule-002";
    private static final String DEMO_USER_ID = "user-001";

    private static final LocalTime CHECKUP_START = LocalTime.of(10, 0);
    private static final LocalTime CHECKUP_END = LocalTime.of(11, 0);
    private static final LocalTime FAMILY_MEAL_START = LocalTime.of(12, 0);
    private static final LocalTime FAMILY_MEAL_END = LocalTime.of(13, 30);

    private final JdbcTemplate jdbc;

    public RollingUserScheduleInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) { seed(); }

    /** 可重复调用；场景重置时也走这里，保证日程与重新生成的号源对齐。 */
    public void seed() {
        LocalDate checkup = nextCommunityCheckupDate();
        upsert(CONFLICT_SCHEDULE_ID, "社区体检", checkup.atTime(CHECKUP_START), checkup.atTime(CHECKUP_END));
        // 一条不与任何工作日号源重叠的日程，用来验证冲突检查不会误报。
        LocalDate weekend = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.SATURDAY));
        upsert(OTHER_SCHEDULE_ID, "和家人吃饭", weekend.atTime(FAMILY_MEAL_START), weekend.atTime(FAMILY_MEAL_END));
    }

    /** 下周三：当天一定有工作日号源档位，10:30 那一档与社区体检时间重叠。 */
    public LocalDate nextCommunityCheckupDate() {
        return LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.WEDNESDAY));
    }

    private void upsert(String id, String title, java.time.LocalDateTime start, java.time.LocalDateTime end) {
        jdbc.update("""
                MERGE INTO user_schedules(id,user_id,title,start_at,end_at) KEY(id) VALUES (?,?,?,?,?)
                """, id, DEMO_USER_ID, title, Timestamp.valueOf(start), Timestamp.valueOf(end));
    }
}
