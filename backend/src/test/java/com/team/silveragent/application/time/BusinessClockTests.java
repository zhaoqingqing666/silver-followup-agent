package com.team.silveragent.application.time;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务时钟本身的语义：同一个瞬间，换个时区就该是另一天。
 *
 * <p>「上海凌晨」这条是最容易出错的窗口——开发容器的 JVM 默认时区是 UTC，北京时间 00:00–08:00
 * 这八个小时里，服务器眼里的「今天」还停在昨天。这里用固定瞬间把这段时间钉死，不依赖跑测试的时刻。
 */
class BusinessClockTests {

    /** 2026-09-15 16:30 UTC = 2026-09-16 00:30 上海：两边不是同一天。 */
    private static final Instant SHANGHAI_JUST_AFTER_MIDNIGHT = Instant.parse("2026-09-15T16:30:00Z");

    @Test
    void businessDayIsShanghaiDayNotTheServersDay() {
        BusinessClock shanghai = new BusinessClock(Clock.fixed(SHANGHAI_JUST_AFTER_MIDNIGHT, BusinessClock.DEFAULT_ZONE));
        BusinessClock utc = new BusinessClock(Clock.fixed(SHANGHAI_JUST_AFTER_MIDNIGHT, ZoneOffset.UTC));

        assertThat(shanghai.today()).isEqualTo(LocalDate.of(2026, 9, 16));
        assertThat(utc.today()).as("同一个瞬间，UTC 那边还是 15 号").isEqualTo(LocalDate.of(2026, 9, 15));
    }

    @Test
    void clockZoneDecidesTheDay() {
        // 时区取自 clock 自己，不走系统默认时区；构造时不传 zone 也不会串味。
        BusinessClock clock = new BusinessClock(Clock.fixed(SHANGHAI_JUST_AFTER_MIDNIGHT, BusinessClock.DEFAULT_ZONE));

        assertThat(clock.zoneId()).isEqualTo("Asia/Shanghai");
        assertThat(clock.now()).isEqualTo(LocalDateTime.of(2026, 9, 16, 0, 30));
        assertThat(clock.time()).isEqualTo(java.time.LocalTime.of(0, 30));
        assertThat(clock.weekdayLabel(clock.today())).isEqualTo("星期三");
    }

    @Test
    void zoneLabelIsReadFromTheZoneNotHardCoded() {
        BusinessClock shanghai = new BusinessClock(Clock.fixed(SHANGHAI_JUST_AFTER_MIDNIGHT, BusinessClock.DEFAULT_ZONE));
        BusinessClock tokyo = new BusinessClock(Clock.fixed(SHANGHAI_JUST_AFTER_MIDNIGHT, ZoneId.of("Asia/Tokyo")));

        assertThat(shanghai.zoneLabel()).isEqualTo("中国时间");
        assertThat(tokyo.today()).as("东京已经又过了一天").isEqualTo(LocalDate.of(2026, 9, 16));
        assertThat(tokyo.zoneLabel()).isNotEqualTo(shanghai.zoneLabel());
    }

    /**
     * 业务钟面换算成审计钟面：两者表示的是同一个瞬间，只是写法不同。
     *
     * <p>断言按瞬间比，不按字面比：这样容器时区换成什么都不会误报。
     */
    @Test
    void auditClockConversionKeepsTheSameInstant() {
        BusinessClock clock = new BusinessClock(Clock.fixed(SHANGHAI_JUST_AFTER_MIDNIGHT, BusinessClock.DEFAULT_ZONE));
        LocalDateTime businessMoment = LocalDateTime.of(2026, 9, 16, 0, 30);

        LocalDateTime audit = clock.toAuditClock(businessMoment);

        assertThat(audit.atZone(ZoneId.systemDefault()).toInstant())
                .isEqualTo(businessMoment.atZone(BusinessClock.DEFAULT_ZONE).toInstant());
    }

    @Test
    void systemDefaultUsesTheBusinessZone() {
        BusinessClock clock = BusinessClock.systemDefault();

        assertThat(clock.zoneId()).isEqualTo("Asia/Shanghai");
        assertThat(clock.today()).isEqualTo(LocalDate.now(BusinessClock.DEFAULT_ZONE));
    }
}
