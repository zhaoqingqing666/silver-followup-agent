package com.team.silveragent.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 全应用唯一的一份业务时钟：业务意义上的「今天 / 现在」只在这里定义一次。
 *
 * <p>为什么不能直接写 {@code LocalDate.now()}：
 * <ul>
 *   <li>容器镜像默认没配时区，JVM 默认走 GMT（比北京慢 8 小时）。设了 {@code TZ=Asia/Shanghai}
 *       只是「环境里恰好对了」，代码本身并没有说清业务上「今天」是哪一天——换台机器、
 *       忘了重建容器，口径就又漂了。</li>
 *   <li>更隐蔽的一条：H2 的 {@code CURRENT_DATE / CURRENT_TIME} **不吃**
 *       {@code TimeZone.setDefault}。2.3.232 实测：改默认时区后 Java 的「现在」变了、H2 的没变
 *       （它在这个 JVM 里第一次连库时就把时区定死了）。所以查号 SQL 里用 {@code CURRENT_TIME}
 *       过滤「当天已过的时段」，等于把业务口径交给了容器环境。</li>
 * </ul>
 *
 * <p>因此号源链路一律在 Java 侧按本钟过滤，SQL 不再碰数据库时钟；备忘解析
 * （{@link com.team.silveragent.application.memo.MemoParser}）也用同一份时区常量。
 * 时区可用 {@code demo.zone}（环境变量 {@code DEMO_ZONE}）覆盖，方便换演示城市。
 */
@Component
public class BusinessClock {
    /** 演示业务时区。全仓只有这一处写时区字面量，别在别处再写一份。 */
    public static final ZoneId DEMO_ZONE = ZoneId.of("Asia/Shanghai");

    private final ZoneId zone;

    public BusinessClock(@Value("${demo.zone:Asia/Shanghai}") String zone) {
        this.zone = ZoneId.of(zone);
    }

    /** 业务意义上的「今天」。 */
    public LocalDate today() {
        return LocalDate.now(zone);
    }

    /** 业务意义上的「现在」，只取钟点。 */
    public LocalTime now() {
        return LocalTime.now(zone);
    }

    /** 业务意义上的「现在」。 */
    public LocalDateTime nowDateTime() {
        return LocalDateTime.now(zone);
    }

    /**
     * 这个时点是不是已经过去了——号源能不能约只认这一条判据。
     *
     * <p>与旧 SQL 的 {@code appointment_time > CURRENT_TIME} 同口径：**到点即算过**，
     * 不用「刚好等于现在」去为难老人。
     */
    public boolean isPast(LocalDateTime moment) {
        return !moment.isAfter(nowDateTime());
    }

    /**
     * 静态上下文（{@code ApplicationRunner} 种子、纯工具类、演示数据）用的「今天」。
     *
     * <p>这些地方按约定不能注入 Bean（比如 {@code DemoSeed} 在 Spring 起来之前就要算号源 id），
     * 但时区口径必须和 Bean 版本一致——所以两边共用 {@link #DEMO_ZONE}。
     */
    public static LocalDate demoToday() {
        return LocalDate.now(DEMO_ZONE);
    }

    /** 静态上下文用的「现在」。 */
    public static LocalDateTime demoNow() {
        return LocalDateTime.now(DEMO_ZONE);
    }
}
