package com.team.silveragent.infrastructure.persistence;

import com.team.silveragent.application.BusinessClock;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;

/**
 * 演示用的“既有日程”跟着今天走。
 *
 * 写死某一天的话，那天一过，「时间冲突」就再也造不出来——冲突检查查的是**预约当天**
 * 有没有别的安排，一条落在过去的日程永远不会命中，界面上看起来就像这位老人从来没有别的事。
 *
 * 体检排在「下周三」10:00-11:00：RollingAppointmentSlotInitializer 给心内科（d001）排的
 * 放号日固定含周三，那天上午必有一条 10:30 的号源，两者必然重叠，所以演示话术
 * 「下周三去市第一医院心内科复诊」不管哪天演示都能撞上冲突。
 * 第二条排在「下周六」12:00-13:30（周六没有号源），用来验证冲突检查不会误报。
 */
@Component
public class RollingUserScheduleInitializer implements ApplicationRunner {

    /** 冲突场景里的那条既有日程；回归用例按 id 引用它。 */
    public static final String CHECKUP_ID = "schedule-001";
    /** 不参与冲突的反例日程。 */
    public static final String DINNER_ID = "schedule-002";
    private static final String ELDER_ID = "user-001";
    private static final String CHECKUP_TITLE = "社区体检";
    private static final String DINNER_TITLE = "和家人吃饭";

    private final JdbcTemplate jdbc;

    public RollingUserScheduleInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /** 幂等：重复调用只会把两条日程挪到最新的「下周三 / 下周六」。 */
    public void seed() {
        upsert(CHECKUP_ID, CHECKUP_TITLE, checkupDate(), LocalTime.of(10, 0), LocalTime.of(11, 0));
        upsert(DINNER_ID, DINNER_TITLE, dinnerDate(), LocalTime.of(12, 0), LocalTime.of(13, 30));
    }

    /**
     * 演示话术「下周三」指的那一天，与 RuleFactExtractor 的口语解析一致：本周三再往后推一周。
     * 今天就是周三时同样指七天后的那个周三——两处必须一起理解，否则演示话术会落到没有日程的日期上。
     *
     * <p>「今天」取业务时区（{@code BusinessClock}），不是 JVM 默认时区：
     * 这条日程是冲突演示的锚点，它和号源链路的「今天」必须是同一天。
     */
    public static LocalDate checkupDate() {
        return nextWeek(DayOfWeek.WEDNESDAY);
    }

    /** 「下周六」：不参与冲突场景，只用来验证周末的日程不会被误报成冲突。 */
    public static LocalDate dinnerDate() {
        return nextWeek(DayOfWeek.SATURDAY);
    }

    private static LocalDate nextWeek(DayOfWeek day) {
        return BusinessClock.demoToday()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .plusWeeks(1)
                .plusDays(day.getValue() - 1L);
    }

    private void upsert(String id, String title, LocalDate day, LocalTime start, LocalTime end) {
        jdbc.update("""
                MERGE INTO user_schedules(id,user_id,title,start_at,end_at) KEY(id) VALUES (?,?,?,?,?)
                """, id, ELDER_ID, title,
                Timestamp.valueOf(LocalDateTime.of(day, start)), Timestamp.valueOf(LocalDateTime.of(day, end)));
    }
}
