package com.team.silveragent;

import com.team.silveragent.application.memo.MemoParser;
import com.team.silveragent.application.memo.MemoStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「已完成」在重复提醒上的行为。
 *
 * <p>这些测试钉的是一件会害到人的事：老人给“每天八点吃药”按一下「已完成」，
 * 他说的是“这次吃完了”。早先的实现直接 {@code status='DONE'}，整条就再也不会提醒了——
 * 他既不会知道，也不会发现。
 *
 * <p>{@link MemoStore#nextOccurrence} 那几条是纯日历题：锚点怎么顺延才既落在将来、
 * 又不把“每月31号”悄悄改成别的号。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-memo;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=false"})
class MemoRepeatCompleteTests {

    /** 备忘按中国时区起算（与 MemoParser.DEMO_ZONE 一致），断言要用同一时区才不受运行时刻影响。 */
    private static final ZoneId DEMO_ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired MemoStore memos;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM memos");
    }

    // ---------- 锚点顺延（纯日历） ----------

    @Test
    void dailyAnchorInThePastLandsOnTheNextFutureOccurrence() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 22, 9, 0);
        LocalDateTime anchor = LocalDateTime.of(2026, 9, 19, 8, 0);

        // 9月19、20、21 的八点都过去了，22 号的八点也过去了（现在九点），所以是 23 号
        assertThat(MemoStore.nextOccurrence(anchor, "DAILY", now))
                .isEqualTo(LocalDateTime.of(2026, 9, 23, 8, 0));
    }

    @Test
    void dailyKeepsTheClockTimeEvenWhenTodayHasNotPassedYet() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 22, 7, 0);
        LocalDateTime anchor = LocalDateTime.of(2026, 9, 1, 8, 0);

        // 今天的八点还没到，就是今天——不该推到明天
        assertThat(MemoStore.nextOccurrence(anchor, "DAILY", now))
                .isEqualTo(LocalDateTime.of(2026, 9, 22, 8, 0));
    }

    @Test
    void anOccurrenceExactlyAtNowCountsAsPassed() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 22, 8, 0);

        assertThat(MemoStore.nextOccurrence(LocalDateTime.of(2026, 9, 1, 8, 0), "DAILY", now))
                .isEqualTo(LocalDateTime.of(2026, 9, 23, 8, 0));
    }

    @Test
    void weeklyKeepsTheSameWeekday() {
        // 锚点 2026-09-08 是周二，现在是 09-23（周三）：09-15、09-22 都过去了，下一个是 09-29
        LocalDateTime now = LocalDateTime.of(2026, 9, 23, 9, 0);
        LocalDateTime anchor = LocalDateTime.of(2026, 9, 8, 15, 0);

        LocalDateTime next = MemoStore.nextOccurrence(anchor, "WEEKLY", now);
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 9, 29, 15, 0));
        assertThat(next.getDayOfWeek()).isEqualTo(anchor.getDayOfWeek());
    }

    @Test
    void monthlySkipsTheMonthsThatHaveNoSuchDayInsteadOfDriftingToThe28th() {
        //「每月31号」：remind_at 同时是锚点，落到 2 月 28 号就再也回不去 31 号了
        LocalDateTime now = LocalDateTime.of(2026, 2, 10, 9, 0);
        LocalDateTime anchor = LocalDateTime.of(2026, 1, 31, 8, 0);

        assertThat(MemoStore.nextOccurrence(anchor, "MONTHLY", now))
                .isEqualTo(LocalDateTime.of(2026, 3, 31, 8, 0));
    }

    @Test
    void monthlyStaysInTheSameMonthWhenThatDayIsStillAhead() {
        LocalDateTime now = LocalDateTime.of(2026, 3, 1, 9, 0);
        LocalDateTime anchor = LocalDateTime.of(2026, 3, 31, 8, 0);

        assertThat(MemoStore.nextOccurrence(anchor, "MONTHLY", now))
                .isEqualTo(LocalDateTime.of(2026, 3, 31, 8, 0));
    }

    // ---------- 「已完成」到底做了什么 ----------

    @Test
    void aOneOffMemoIsMarkedDone() {
        MemoStore.MemoView created = memos.create("user-001", "明天去复查",
                MemoParser.nowInDemoZone().plusDays(1));

        MemoStore.MemoView done = memos.complete("user-001", created.id());

        assertThat(done).isNotNull();
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(memos.activeFor("user-001")).isEmpty();
    }

    @Test
    void aRepeatingMemoIsNotFinishedButPushedToTheNextOccurrence() {
        LocalDateTime now = MemoParser.nowInDemoZone();
        MemoStore.MemoView created = memos.create("user-001", "每天八点吃药", threeDaysAgoAt8(), "DAILY");

        MemoStore.MemoView done = memos.complete("user-001", created.id());

        assertThat(done).isNotNull();
        assertThat(done.status()).isEqualTo("ACTIVE");
        assertThat(done.remindAt()).isAfter(now);
        assertThat(done.remindAt().toLocalTime()).isEqualTo(LocalTime.of(8, 0));
        // 还在进行中的列表里，提醒没断
        assertThat(memos.activeFor("user-001")).hasSize(1);
    }

    /** 三天前的早上八点整。刻意不带秒：解析器给出来的钟点都是整分，锚点不该有零头。 */
    private static LocalDateTime threeDaysAgoAt8() {
        return MemoParser.nowInDemoZone().toLocalDate().minusDays(3).atTime(8, 0);
    }

    @Test
    void completingTwiceDoesNotPushTheNextOccurrenceAway() {
        MemoStore.MemoView created = memos.create("user-001", "每天八点吃药", threeDaysAgoAt8(), "DAILY");

        LocalDateTime first = memos.complete("user-001", created.id()).remindAt();
        LocalDateTime second = memos.complete("user-001", created.id()).remindAt();

        // 界面上卡片文案（“每天 08:00”）顺延前后一模一样，老人可能以为没点着而再点一次；
        // 第二次不能把明天那次也推掉，否则等于漏吃一天
        assertThat(second).isEqualTo(first);
    }

    @Test
    void completingSomethingThatIsNotThereReturnsNull() {
        assertThat(memos.complete("user-001", "memo-does-not-exist")).isNull();

        MemoStore.MemoView created = memos.create("user-001", "明天去复查",
                MemoParser.nowInDemoZone().plusDays(1));
        memos.complete("user-001", created.id());
        // 已经处理过的一次性备忘，再点一次也是 null（接口会回“不存在或已经处理过了”）
        assertThat(memos.complete("user-001", created.id())).isNull();
    }

    @Test
    void onePersonCannotCompleteAnotherPersonsMemo() {
        MemoStore.MemoView created = memos.create("user-001", "每天八点吃药",
                MemoParser.nowInDemoZone().minusDays(3), "DAILY");

        assertThat(memos.complete("user-002", created.id())).isNull();
        assertThat(memos.activeFor("user-001")).hasSize(1);
    }
}
