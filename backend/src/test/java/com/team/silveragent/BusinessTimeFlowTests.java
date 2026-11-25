package com.team.silveragent;

import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.application.time.BusinessClock;
import com.team.silveragent.application.demo.DemoScenarioService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.model.DemoScenarioResponse;
import com.team.silveragent.infrastructure.persistence.RollingAppointmentSlotInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务时间线：把时钟钉死在一个已知瞬间，验证「今天是哪天」「今天的哪个时段还能约」
 * 「确认卡的绝对日期不会因为跨午夜自己变掉」。
 *
 * <p><b>为什么必须钉死时钟</b>：这些判断全是相对「现在」的，跟着真实时间跑的话，
 * 用例一过午夜就变红、上海凌晨那八小时在 UTC 容器里更是随机红绿。这里用一个可拨动的
 * {@link MovableClock} 覆盖掉真实的 BusinessClock，时间由用例自己决定。
 *
 * <p>时区固定为 Asia/Shanghai。基准瞬间取 2026-09-15T16:30Z —— 换算到北京时间是
 * <b>9 月 16 日凌晨 00:30</b>，而服务器（UTC）眼里的今天还停在 9 月 15 日。
 * 正好卡在最容易出错的窗口上：业务时区的 16 号凌晨，服务器时区还是 15 号。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-business-time;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=false"})
class BusinessTimeFlowTests {

    /** 基准瞬间：北京时间 2026-09-16（周三）00:30，服务器 UTC 还是 09-15。 */
    private static final Instant START = Instant.parse("2026-09-15T16:30:00Z");

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 16);
    /** 服务器默认时区（UTC）此刻的那一天：用它证明「过去日期」是按业务时区判的。 */
    private static final LocalDate SERVER_TODAY = LocalDate.of(2026, 9, 15);
    private static final LocalDate TOMORROW = LocalDate.of(2026, 9, 17);

    private static final LocalTime MORNING = LocalTime.of(9, 0);
    private static final LocalTime AFTERNOON = LocalTime.of(15, 30);

    /** 北京时间 09-16 08:00 / 09:00 / 10:30 / 14:30 —— 同一个业务日的四个时刻，用来让时间真的走过去。 */
    private static final Instant TODAY_0800 = Instant.parse("2026-09-16T00:00:00Z");
    /** 正好 09:00 整：与上午那个号源同一瞬间，用来钉「等于此刻也算过期」。 */
    private static final Instant TODAY_0900 = Instant.parse("2026-09-16T01:00:00Z");
    private static final Instant TODAY_1030 = Instant.parse("2026-09-16T02:30:00Z");
    private static final Instant TODAY_1430 = Instant.parse("2026-09-16T06:30:00Z");
    /** 北京时间 09-16 23:59 与 09-17 00:01：跨午夜的前后一分钟。 */
    private static final Instant TODAY_2359 = Instant.parse("2026-09-16T15:59:00Z");
    private static final Instant TOMORROW_0001 = Instant.parse("2026-09-16T16:01:00Z");

    @Autowired FollowupAgentService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired BusinessClock clock;
    @Autowired MovableClock movableClock;
    @Autowired DemoScenarioService scenarios;

    @BeforeEach
    void reset() {
        movableClock.moveTo(START);
        for (String table : List.of("appointments", "reminders", "family_notifications")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE appointment_slots SET available=TRUE");
    }

    /**
     * 上海凌晨：业务时区已经是 16 号，服务器（UTC）那边还是 15 号。
     *
     * <p>「昨天」必须被拒——按服务器时区算的话 15 号正是「今天」，会被老老实实查一遍号。
     */
    @Test
    void businessDayRollsOverAtShanghaiMidnightNotTheServersMidnight() {
        assertThat(clock.today())
                .as("基准瞬间下业务时区已经是 16 号")
                .isEqualTo(TODAY);
        assertThat(LocalDate.ofInstant(START, ZoneOffset.UTC))
                .as("同一瞬间按服务器时区（UTC）算还停在 15 号——这就是「昨天」被当成「今天」的由来")
                .isEqualTo(SERVER_TODAY);

        String id = begin();
        AgentTurnResponse past = action(id, "SET_DATE", SERVER_TODAY.toString());
        assertThat(past.stage()).isEqualTo("ASK_DATE");
        assertThat(past.reply()).contains("这个日期已经过去");
        // 候选日期也应该从业务时区的今天开始铺，16 号必须出现
        assertThat(past.quickReplies()).anyMatch(q -> TODAY.toString().equals(q.value()));

        AgentTurnResponse today = action(id, "SET_DATE", TODAY.toString());
        assertThat(today.reply())
                .as("凌晨 00:30 四个时段都还没到，今天的号一个都不该少")
                .contains("共查到4个可预约时段", "上午最早09:00", "下午最早14:00");
    }

    /** 今天已经过去的时段不算号：14:30 再查今天，上午和 14:00 都不该出现。 */
    @Test
    void slotsThatAlreadyPassedTodayAreNotOffered() {
        movableClock.moveTo(TODAY_1430);
        String id = begin();

        AgentTurnResponse turn = action(id, "SET_DATE", TODAY.toString());
        assertThat(turn.reply())
                .contains("共查到1个可预约时段", "下午最早15:30")
                .as("上午的号早就过去了，不能再报给老人")
                .doesNotContain("上午最早");

        AgentTurnResponse slots = action(id, "SHOW_PERIOD_SLOTS", "AFTERNOON");
        List<String> offered = slots.quickReplies().stream()
                .filter(q -> "SELECT_SLOT".equals(q.action())).map(q -> q.value()).toList();
        assertThat(offered).containsExactly(slotId(TODAY, AFTERNOON));
        assertThat(offered).doesNotContain(slotId(TODAY, MORNING));
    }

    /**
     * 确认卡生成之后时间又走过去了：确认时必须被挡回重选，不能把已经过期的时段写进预约。
     *
     * <p>确认卡上没有任何时间闸门，老人停多久都行，所以这道门只能开在写入那一刻。
     */
    @Test
    void aCardWhoseSlotHasPassedCannotBeConfirmed() {
        movableClock.moveTo(TODAY_0800);
        String id = begin();
        AgentTurnResponse card = planCard(id, TODAY, MORNING);
        assertThat(card.stage()).as(card.reply()).isEqualTo("AWAITING_CONFIRMATION");

        // 08:00 建卡时 09:00 还没到，它是合法的；两个半小时后老人再按确认。
        movableClock.moveTo(TODAY_1030);
        AgentTurnResponse refused = service.confirm(id, true, card.confirmation().confirmationId());

        assertThat(refused.stage()).isEqualTo("ASK_DATE");
        assertThat(refused.reply()).contains("2026年9月16日 09:00", "已经过去", "重新选择复诊日期");
        assertThat(count("appointments")).as("过期时段绝不能落库").isZero();
        assertThat(count("reminders")).isZero();
    }

    /**
     * 跨午夜不偷改日期：23:59 建卡、00:01 确认，写的还是卡上那一天。
     *
     * <p>卡上存的是绝对日期时间，不是「明天」这个相对说法——这条用例就是钉住这一点。
     */
    @Test
    void confirmingJustAfterMidnightKeepsTheDateOnTheCard() {
        movableClock.moveTo(TODAY_2359);
        String id = begin();
        AgentTurnResponse card = planCard(id, TOMORROW, MORNING);
        assertThat(card.stage()).as(card.reply()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(card.confirmation().operations().toString()).contains("2026年9月17日", "09:00");

        // 过了午夜，业务时区的「今天」已经是 17 号了，卡上的 17 号 09:00 还没到，照约。
        movableClock.moveTo(TOMORROW_0001);
        AgentTurnResponse done = service.confirm(id, true, card.confirmation().confirmationId());

        assertThat(done.stage()).as(done.reply()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT slot_id FROM appointments", String.class))
                .isEqualTo(slotId(TOMORROW, MORNING));
        assertThat(jdbc.queryForObject(
                "SELECT appointment_date FROM appointment_slots WHERE id=?", String.class, slotId(TOMORROW, MORNING)))
                .isEqualTo(TOMORROW.toString());
    }

    /**
     * 「正好等于此刻」的号源也算过期。
     *
     * <p>号源查询的口径是 {@code appointment_time > 当前时间}，差一毫秒都查不出来。确认这道门要是
     * 用「严格早于」判过期，09:00 整点按确认时就能约到一条查询永远查不出来的时段——同一件事两套口径。
     */
    @Test
    void aSlotEqualToNowCountsAsPassed() {
        movableClock.moveTo(TODAY_0800);
        String id = begin();
        AgentTurnResponse card = planCard(id, TODAY, MORNING);
        assertThat(card.stage()).as(card.reply()).isEqualTo("AWAITING_CONFIRMATION");

        // 正好 09:00 整：不早也不晚，就是号源查询不再返回的那一瞬。
        movableClock.moveTo(TODAY_0900);
        AgentTurnResponse refused = service.confirm(id, true, card.confirmation().confirmationId());

        assertThat(refused.stage()).as(refused.reply()).isEqualTo("ASK_DATE");
        assertThat(count("appointments")).as("整点那一刻的时段照样不能落库").isZero();
    }

    /**
     * 取消已有预约不能被「草稿里那个时段已经过去」挡住。
     *
     * <p>办完预约之后会话仍然留着当初那份 {@code selectedSlot}，等号源时间过去再回头取消，草稿里的
     * 时段必然已经过期。过期检查只该约束「照草稿开新预约」——拿它拦取消，老人必须先重选一遍日期
     * 才能取消一条跟那个时段无关的预约。
     */
    @Test
    void cancellingACompletedBookingIsNotBlockedByItsPassedSlot() {
        movableClock.moveTo(TODAY_0800);
        String id = begin();
        AgentTurnResponse booked = book(id, TODAY, MORNING);
        assertThat(booked.stage()).as(booked.reply()).isEqualTo("COMPLETED");
        assertThat(status()).isEqualTo("CONFIRMED");

        // 时间走到号源之后：草稿里那个 09:00 成了过去时，取消照样得办。
        movableClock.moveTo(TODAY_1030);
        AgentTurnResponse card = action(id, "CANCEL_APPOINTMENT", "");
        assertThat(card.stage()).as(card.reply()).isEqualTo("AWAITING_CONFIRMATION");

        AgentTurnResponse done = service.confirm(id, true, card.confirmation().confirmationId());
        assertThat(done.stage())
                .as("过期检查不能把取消拦成 ASK_DATE: " + done.reply())
                .isEqualTo("CANCELLED");
        assertThat(status()).isEqualTo("CANCELLED");
    }

    /**
     * 演示场景步骤里报的日期跟着业务时钟走。
     *
     * <p>步骤文案写着「下周三（M月d日，助手日程里「社区体检」那天）」，这个日期必须跟
     * 播进 user_schedules 的那天是同一个。用钉死在 Asia/Shanghai 上的静态兜底取「今天」，
     * 改了 {@code business.time.zone} 之后文案会指向一个压根没有日程的日子，按步骤演示就演不下去。
     */
    @Test
    void demoScenarioStepsFollowTheBusinessClock() {
        DemoScenarioResponse reset = scenarios.reset("normal");
        // 业务时区的今天是 09-16（周三），「下周三」落在 09-23；按真实系统时钟算会是另一天。
        assertThat(reset.steps().toString())
                .as("场景步骤里的体检日期要按业务时钟的今天算")
                .contains("9月23日");
    }

    /**
     * 备忘的「今天」来自业务时钟，不是系统时钟。
     *
     * <p>备忘推算原先自己在 MemoParser 里按钉死的 Asia/Shanghai 取「现在」，生产路径改
     * {@code business.time.zone} 也不生效。现在 FollowupAgentService 把 BusinessClock.now()
     * 传进去：时钟拨到业务时区的 09-16，说「明天早上八点」就得存 09-17。
     */
    @Test
    void memoRemindAtIsComputedFromTheBusinessClock() {
        movableClock.moveTo(TODAY_0800);
        String id = service.start().conversationId();

        AgentTurnResponse reply = service.chat(id, "帮我记着，明天早上8点要空腹抽血，记得提醒我");
        assertThat(reply.reply()).contains("08:00");

        assertThat(jdbc.queryForObject("SELECT text FROM memos WHERE status='ACTIVE'", String.class))
                .as("备忘正文只留事项本身，时间交给提醒那一行")
                .isEqualTo("要空腹抽血");
        assertThat(jdbc.queryForObject("SELECT remind_at FROM memos WHERE status='ACTIVE'", LocalDateTime.class))
                .as("业务时区 09-16 的「明天早上8点」是 09-17 08:00，不是系统时钟的今天+1")
                .isEqualTo(LocalDateTime.of(TOMORROW, LocalTime.of(8, 0)));
    }

    /** 走完整办理漏斗，停在确认卡上（还没确认）。 */
    private AgentTurnResponse planCard(String id, LocalDate date, LocalTime time) {
        action(id, "SET_DATE", date.toString());
        action(id, "SELECT_SLOT", slotId(date, time));
        action(id, "SET_ALTERNATIVE", "true");
        action(id, "SET_COMPANION", "false");
        action(id, "SET_TRAVEL", "false");
        action(id, "SET_TRANSPORT", "打车");
        action(id, "SET_NOTIFY", "false");
        return action(id, "START_PLAN", "");
    }

    /** 走完整漏斗并确认，把一条预约真正写进库。 */
    private AgentTurnResponse book(String id, LocalDate date, LocalTime time) {
        AgentTurnResponse card = planCard(id, date, time);
        return service.confirm(id, true, card.confirmation().confirmationId());
    }

    /** 库里那条预约的状态；用例只造一条，所以不用再筛。 */
    private String status() {
        return jdbc.queryForObject("SELECT status FROM appointments", String.class);
    }

    /** 开一段会话并把医院、科室选好，剩下的用例从「选日期」开始。 */
    private String begin() {
        String id = service.start().conversationId();
        action(id, "SET_HOSPITAL", "h001");
        action(id, "SET_DEPARTMENT", "d001");
        return id;
    }

    private AgentTurnResponse action(String id, String action, String value) {
        return service.act(id, action, value, action);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    /** 号源 id 的拼法跟生产者共用同一处，避免用例里再抄一份规则。 */
    private static String slotId(LocalDate date, LocalTime time) {
        return RollingAppointmentSlotInitializer.slotId("d001", date, time);
    }

    /**
     * 可以往前拨的时钟。固定时钟不够用：跨午夜那条用例必须让时间真的走过去。
     */
    static final class MovableClock extends Clock {
        private volatile Instant instant;

        MovableClock(Instant instant) {
            this.instant = instant;
        }

        void moveTo(Instant next) {
            this.instant = next;
        }

        @Override public ZoneId getZone() { return BusinessClock.DEFAULT_ZONE; }

        @Override public Clock withZone(ZoneId zone) { return this; }

        @Override public Instant instant() { return instant; }
    }

    /**
     * 用可拨时钟替换真实的 BusinessClock。
     *
     * <p>号源是启动时按时钟铺的，所以替换必须在容器启动前生效——TestConfiguration 的 bean
     * 正好赶在 ApplicationRunner 之前装配好，铺号源那天就是 16 号。
     */
    @TestConfiguration
    static class MovableClockConfig {
        @Bean
        MovableClock movableClock() {
            return new MovableClock(START);
        }

        @Bean
        @Primary
        BusinessClock businessClock(MovableClock movableClock) {
            return new BusinessClock(movableClock);
        }
    }
}
