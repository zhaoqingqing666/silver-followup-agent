package com.team.silveragent.agent;

import com.team.silveragent.application.care.CareCatalogRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 相对日期的口径：全部以传进来的「业务时区的今天」为基准，跨月跨年自己算。
 *
 * <p>这里的今天一律由测试固定，不读真实时钟——否则这些用例到明年就会自己变红。
 */
class RuleFactExtractorDateTests {

    private final RuleFactExtractor extractor = new RuleFactExtractor(mock(CareCatalogRepository.class));

    @Test
    void tomorrowAndTheDayAfterAreCountedFromToday() {
        LocalDate today = LocalDate.of(2026, 9, 13);

        assertThat(date("明天", today)).isEqualTo(LocalDate.of(2026, 9, 14));
        assertThat(date("后天", today)).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(date("今天", today)).isEqualTo(today);
    }

    /** 8 月 31 号说「明天」，是 9 月 1 号，不能只加天数却忘了月份。 */
    @Test
    void tomorrowCrossesTheMonthBoundary() {
        assertThat(date("明天", LocalDate.of(2026, 8, 31))).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    /** 12 月 31 号说「明天」，是次年元旦。 */
    @Test
    void tomorrowCrossesTheYearBoundary() {
        assertThat(date("明天", LocalDate.of(2026, 12, 31))).isEqualTo(LocalDate.of(2027, 1, 1));
    }

    /** 周四说的「下周三」严格是下周，也就是次年 1 月 6 号，不能当成 4 天后的本周三。 */
    @Test
    void nextWeekWednesdayCrossesTheYearBoundary() {
        assertThat(date("下周三", LocalDate.of(2026, 12, 31))).isEqualTo(LocalDate.of(2027, 1, 6));
    }

    /** 说「周三」而本周三还没到，就按本周算；已经过了才顺延到下周。 */
    @Test
    void plainWeekdayMeansThisWeekUnlessItHasPassed() {
        assertThat(date("周三", LocalDate.of(2026, 9, 14)))   // 周一
                .isEqualTo(LocalDate.of(2026, 9, 16));
        assertThat(date("周三", LocalDate.of(2026, 9, 18)))   // 周五
                .isEqualTo(LocalDate.of(2026, 9, 23));
    }

    /**
     * 只写了月日时按今年理解，<b>不顺延到明年</b>。
     *
     * <p>这是本次改动的核心回归：以前这里会把明显过去的日期悄悄推到明年，把一次询问变成八个月的错约。
     * 日期到底是不是明年，只有老人自己知道，系统要做的是复述后请他确认。
     */
    @Test
    void aShortDateThatAlreadyPassedStaysInThisYear() {
        LocalDate today = LocalDate.of(2026, 9, 13);

        assertThat(date("3月5日", today)).isEqualTo(LocalDate.of(2026, 3, 5));
        assertThat(date("9月12日", today)).isEqualTo(LocalDate.of(2026, 9, 12));
        assertThat(date("3/5", today)).isEqualTo(LocalDate.of(2026, 3, 5));
    }

    /** 写全了年份就按写的来，不再套用「今天这一年」。 */
    @Test
    void aFullIsoDateIsTakenLiterally() {
        assertThat(date("2027-03-05", LocalDate.of(2026, 9, 13))).isEqualTo(LocalDate.of(2027, 3, 5));
    }

    /** 当天、次日的边界：今天说「今天」不该被算成明天，「明天」也不会因为跨到 0 点而变。 */
    @Test
    void monthEndAndMonthStartAreConsistent() {
        assertThat(date("今天", LocalDate.of(2026, 9, 30))).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(date("明天", LocalDate.of(2026, 9, 30))).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(date("后天", LocalDate.of(2026, 9, 30))).isEqualTo(LocalDate.of(2026, 10, 2));
    }

    private LocalDate date(String message, LocalDate today) {
        return extractor.extract(message, context(today)).date();
    }

    private AgentContext context(LocalDate today) {
        return new AgentContext("ASK_DATE", "对话模式=FOLLOWUP_FLOW", today, List.of());
    }
}
