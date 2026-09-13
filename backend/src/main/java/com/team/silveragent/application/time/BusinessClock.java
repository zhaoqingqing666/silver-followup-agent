package com.team.silveragent.application.time;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * 业务时间：整个后端「现在几点、今天几号」的唯一来源。
 *
 * <p><b>为什么需要它。</b>预约、取消、提醒、照护代约、号源初始化这些判断都在问「今天/现在」，
 * 而 {@code LocalDate.now()} / {@code LocalDateTime.now()} 取的是 JVM 默认时区。开发容器是 UTC，
 * 业务却在中国：北京时间 00:00–08:00 这八个小时里，服务器眼里的「今天」还停在昨天。同一份
 * 数据，Java 用 UTC 判断、SQL 用 {@code CURRENT_DATE} 判断、备忘解析又写死 Asia/Shanghai，
 * 三种口径混在一起就会出现「今天已经过去的时段还能约」「确认卡上的日期和老人以为的不是同一天」
 * 这类问题。业务判断一律走这个时钟，时区由 {@code business.time.zone} 配置（默认 Asia/Shanghai）。
 *
 * <p><b>它只管业务钟面。</b>预约日期、号源时段、"今天/明天"、提醒到点时间属于业务钟面，按
 * {@link #zone()} 解释。各类 {@code created_at} 审计时间戳不属于——它们一直是按 JVM 默认时区
 * 写进去的（历史数据就是这么写的），本阶段刻意不动，否则已有的行会凭空老八小时。两边的值
 * 不能直接比大小：真要比较先用 {@link #toAuditClock(LocalDateTime)} 把业务钟面换算成审计钟面
 * （见 {@code HealthReportService#weeklySentThisWeek}）。
 *
 * <p><b>可测试。</b>构造器直接收一个 {@link Clock}，测试传 {@code Clock.fixed(...)} 就能把
 * 「明天」「跨月」「跨年」「上海凌晨」「今天已过的时段」固定下来，不再依赖跑测试的时刻。
 */
@Component
public class BusinessClock {

    /** 业务时区默认值。想改只改这一处，其他地方都从这里取。 */
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    private final Clock clock;

    /**
     * @param zoneId 业务时区，见 {@code business.time.zone}。写错会直接启动失败——
     *               把时间算到错时区上的后果比启动报错严重得多，宁可当场发现。
     */
    @Autowired
    public BusinessClock(@Value("${business.time.zone:Asia/Shanghai}") String zoneId) {
        this(Clock.system(ZoneId.of(zoneId)));
    }

    /** 测试用：直接注入固定时钟。时区取自 clock 自己，所以两边不会打架。 */
    public BusinessClock(Clock clock) {
        this.clock = clock;
    }

    /** 少数没有 Spring 上下文的降级路径（如单元测试里直接 new 出来的适配器）用系统时钟。 */
    public static BusinessClock systemDefault() {
        return new BusinessClock(Clock.system(DEFAULT_ZONE));
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public LocalTime time() {
        return LocalTime.now(clock);
    }

    public ZoneId zone() {
        return clock.getZone();
    }

    /** 如 {@code Asia/Shanghai}，写进提示词和日志用。 */
    public String zoneId() {
        return clock.getZone().getId();
    }

    /** 时区的中文名，如「中国标准时间」。不写死文案，换时区自动跟着变。 */
    public String zoneLabel() {
        return clock.getZone().getDisplayName(TextStyle.FULL, Locale.CHINA);
    }

    /** 星期几的中文名，如「星期日」。 */
    public String weekdayLabel(LocalDate date) {
        return date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA);
    }

    /**
     * 业务钟面 → 审计钟面：把业务时区里的一个钟点换算成 {@code created_at} 那类列写法。
     *
     * <p>只在「业务时间要跟审计时间戳比大小」时用。审计列按 JVM 默认时区写入，
     * 直接拿业务钟面去比，在 UTC 容器里会差八小时。
     */
    public LocalDateTime toAuditClock(LocalDateTime businessMoment) {
        return LocalDateTime.ofInstant(businessMoment.atZone(clock.getZone()).toInstant(), ZoneId.systemDefault());
    }
}
