package com.team.silveragent;

import com.team.silveragent.application.memo.MemoParser;

import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.application.memo.MemoStore;
import com.team.silveragent.domain.model.AgentTurnResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 老人端健康备忘：显式托付直接记、隐式先确认，均走 memo.create 工具并落 memos 表；
 * 预约/医疗咨询语句不会被误当备忘。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-memo;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=false"})
class MemoFlowTests {

    /** 备忘按中国时区起算(与 MemoParser.DEMO_ZONE 一致)，断言要用同一时区才不受测试运行时刻影响。 */
    private static final ZoneId DEMO_ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired FollowupAgentService service;
    @Autowired MemoStore memos;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM memos");
    }

    private int memoCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM memos WHERE status='ACTIVE'", Integer.class);
    }

    private MemoStore.MemoView onlyMemo() {
        List<MemoStore.MemoView> rows = memos.activeFor("user-001");
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private AgentTurnResponse start() {
        return service.start("user-001");
    }

    @Test
    void explicitMemoIsRecordedImmediatelyWithRemindAt() {
        AgentTurnResponse start = start();
        AgentTurnResponse reply = service.chat(start.conversationId(),
                "帮我记着，明天早上8点要空腹抽血，记得提醒我");

        assertThat(reply.reply()).contains("已记下", "空腹抽血");
        // 时间靠“提醒”那一行回读（绝对日期），正文里不再重复一遍
        assertThat(reply.reply()).contains("08:00");
        assertThat(reply.stage()).isEqualTo("ASK_HOSPITAL");
        assertThat(reply.toolTraces()).anyMatch(trace ->
                "memo.create".equals(trace.toolName()) && trace.success());

        MemoStore.MemoView memo = onlyMemo();
        // 正文只留“事项”，时间交给提醒那一行，首页才不会同一件事说两遍
        assertThat(memo.text()).isEqualTo("要空腹抽血");
        assertThat(memo.remindAt()).isEqualTo(LocalDate.now(DEMO_ZONE).plusDays(1).atTime(8, 0));
    }

    @Test
    void explicitStandingMemoHasNoRemindAt() {
        AgentTurnResponse start = start();
        AgentTurnResponse reply = service.chat(start.conversationId(), "记一下，我青霉素过敏");

        assertThat(reply.reply()).contains("已记下", "青霉素过敏", "长期备忘");
        assertThat(onlyMemo().text()).isEqualTo("我青霉素过敏");
        assertThat(onlyMemo().remindAt()).isNull();
    }

    @Test
    void relativeMinuteReminderIsTimed() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "2分钟后提醒我量血压");

        LocalDateTime remindAt = onlyMemo().remindAt();
        assertThat(remindAt).isNotNull()
                .isAfter(LocalDateTime.now(DEMO_ZONE).plusSeconds(60))
                .isBefore(LocalDateTime.now(DEMO_ZONE).plusMinutes(3));
    }

    @Test
    void implicitHealthMemoAsksConfirmationBeforeWriting() {
        AgentTurnResponse start = start();
        AgentTurnResponse ask = service.chat(start.conversationId(), "明天早上8点要去抽血，最好空腹");

        assertThat(ask.stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(ask.confirmation()).isNotNull();
        assertThat(ask.confirmation().operations().toString()).contains("备忘内容", "提醒");
        assertThat(memoCount()).isZero();

        AgentTurnResponse done = service.confirm(ask.conversationId(), true, ask.confirmation().confirmationId());
        assertThat(done.reply()).contains("已记下", "抽血");
        assertThat(done.stage()).isEqualTo("ASK_HOSPITAL");
        MemoStore.MemoView memo = onlyMemo();
        assertThat(memo.text()).contains("要去抽血", "空腹").doesNotContain("8点");
        assertThat(memo.remindAt()).isEqualTo(LocalDate.now(DEMO_ZONE).plusDays(1).atTime(8, 0));
    }

    @Test
    void decliningImplicitMemoWritesNothing() {
        AgentTurnResponse start = start();
        AgentTurnResponse ask = service.chat(start.conversationId(), "明天下午3点复查一下血压");

        AgentTurnResponse declined = service.confirm(ask.conversationId(), false, ask.confirmation().confirmationId());
        assertThat(declined.reply()).contains("没有记下");
        assertThat(declined.stage()).isEqualTo("ASK_HOSPITAL");
        assertThat(memoCount()).isZero();
    }

    @Test
    void chineseNumeralClockStoredAtSpokenTimeNotPeriodFallback() {
        // 回归：说“明天早上七点”必须记成 07:00，不能因中文数字没识别而回落成“早上8点”
        AgentTurnResponse start = start();
        AgentTurnResponse ask = service.chat(start.conversationId(), "明天早上七点需要吃药");

        assertThat(ask.stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(ask.confirmation().operations().toString()).contains("07:00").doesNotContain("08:00");

        AgentTurnResponse done = service.confirm(ask.conversationId(), true, ask.confirmation().confirmationId());
        assertThat(done.reply()).contains("已记下");
        MemoStore.MemoView memo = onlyMemo();
        assertThat(memo.text()).contains("需要吃药").doesNotContain("七点");
        assertThat(memo.remindAt()).isEqualTo(LocalDate.now(DEMO_ZONE).plusDays(1).atTime(7, 0));
    }

    @Test
    void chineseNumeralMinutesLaterIsRecordedAfterConfirmation() {
        // 回归：老人说“我一分钟之后需要吃药”原来整条被漏掉（只认阿拉伯数字的“1分钟后”）
        AgentTurnResponse start = start();
        AgentTurnResponse ask = service.chat(start.conversationId(), "我一分钟之后需要吃药");

        assertThat(ask.stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(ask.confirmation()).isNotNull();
        assertThat(memoCount()).isZero();

        AgentTurnResponse done = service.confirm(ask.conversationId(), true, ask.confirmation().confirmationId());
        assertThat(done.reply()).contains("已记下");

        LocalDateTime remindAt = onlyMemo().remindAt();
        assertThat(remindAt).isNotNull()
                .isAfter(LocalDateTime.now(DEMO_ZONE).plusSeconds(30))
                .isBefore(LocalDateTime.now(DEMO_ZONE).plusMinutes(2));
    }

    @Test
    void halfAnHourLaterIsRecordedAsReminderNotStandingMemo() {
        // 回归：“半小时后提醒我吃药”原来静默存成长期备忘（永不提醒），现在应带 30 分钟后的提醒时间
        AgentTurnResponse start = start();
        AgentTurnResponse reply = service.chat(start.conversationId(), "半小时后提醒我吃药");

        assertThat(reply.reply()).contains("已记下");
        LocalDateTime remindAt = onlyMemo().remindAt();
        assertThat(remindAt).isNotNull()
                .isAfter(LocalDateTime.now(DEMO_ZONE).plusMinutes(29))
                .isBefore(LocalDateTime.now(DEMO_ZONE).plusMinutes(31));
    }

    @Test
    void updateMemoChangesTextAndRemindAt() {
        LocalDateTime at = LocalDate.now(DEMO_ZONE).plusDays(1).atTime(7, 0);
        MemoStore.MemoView memo = memos.create("user-001", "明早七点吃药", at);

        boolean ok = memos.update("user-001", memo.id(), "改到早上八点吃药", at.plusHours(1));
        assertThat(ok).isTrue();

        MemoStore.MemoView changed = onlyMemo();
        assertThat(changed.text()).isEqualTo("改到早上八点吃药");
        assertThat(changed.remindAt()).isEqualTo(at.plusHours(1));
    }

    @Test
    void updateMemoCanTurnStandingToReminderAndBack() {
        MemoStore.MemoView standing = memos.create("user-001", "青霉素过敏", null);

        LocalDateTime at = LocalDate.now(DEMO_ZONE).plusDays(1).atTime(9, 0);
        assertThat(memos.update("user-001", standing.id(), "青霉素过敏，记得复查前告诉医生", at)).isTrue();
        MemoStore.MemoView timed = onlyMemo();
        assertThat(timed.remindAt()).isEqualTo(at);

        assertThat(memos.update("user-001", timed.id(), "青霉素过敏", null)).isTrue();
        MemoStore.MemoView back = onlyMemo();
        assertThat(back.remindAt()).isNull();
        assertThat(back.text()).isEqualTo("青霉素过敏");
    }

    @Test
    void updateMemoRejectsOtherUsersAndOverlongText() {
        MemoStore.MemoView memo = memos.create("user-001", "量血压", LocalDate.now(DEMO_ZONE).plusDays(1).atTime(8, 0));

        assertThat(memos.update("user-002", memo.id(), "别人的备忘", null)).isFalse();
        assertThat(memos.update("user-001", "memo-none", "不存在", null)).isFalse();
        assertThat(memos.update("user-001", memo.id(), "  ", null)).isFalse();

        String longText = "记一下，" + "药".repeat(400);
        assertThat(memos.update("user-001", memo.id(), longText, null)).isTrue();
        MemoStore.MemoView changed = onlyMemo();
        assertThat(changed.text().length()).isEqualTo(300);
    }

    @Test
    void bookingPhraseIsNotMemoized() {
        AgentTurnResponse start = start();
        AgentTurnResponse reply = service.chat(start.conversationId(), "帮我把周三下午2点的心内科号预约上");

        assertThat(memoCount()).isZero();
        assertThat(reply.confirmation()).isNull();
        assertThat(reply.reply()).doesNotContain("已记下");
    }

    @Test
    void medicalAdviceQuestionIsNotMemoized() {
        AgentTurnResponse start = start();
        AgentTurnResponse reply = service.chat(start.conversationId(), "这个药量是不是该减半？");

        assertThat(memoCount()).isZero();
        assertThat(reply.reply()).contains("调整用药");
    }

    @Test
    void activeMemosOrderSoonestFirstAndStandingLast() {
        // 首页列表顺序：带提醒时间的按提醒时间升序在前，长期备忘（无时间）排最后
        MemoStore.MemoView morning = memos.create("user-001", "早上测血糖", LocalDate.now(DEMO_ZONE).plusDays(1).atTime(8, 0));
        MemoStore.MemoView afternoon = memos.create("user-001", "下午量血压", LocalDate.now(DEMO_ZONE).plusDays(1).atTime(15, 0));
        MemoStore.MemoView standing = memos.create("user-001", "青霉素过敏", null);

        List<MemoStore.MemoView> rows = memos.activeFor("user-001");
        assertThat(rows).extracting(MemoStore.MemoView::id)
                .containsExactly(morning.id(), afternoon.id(), standing.id());
    }

    @Test
    void overlongMemoTextIsTruncatedToColumnWidth() {
        AgentTurnResponse start = start();
        String longText = "记一下，" + "药".repeat(400);
        AgentTurnResponse reply = service.chat(start.conversationId(), longText);

        assertThat(reply.reply()).contains("已记下");
        assertThat(onlyMemo().text().length()).isEqualTo(300);
    }

    @Test
    void ambiguousDayWithoutClockAsksTimeBeforeRecording() {
        AgentTurnResponse start = start();
        AgentTurnResponse ask = service.chat(start.conversationId(), "明早提醒我量血压");

        assertThat(ask.reply()).contains("几点").contains("明早");
        assertThat(ask.stage()).isEqualTo("MEMO_TIME");
        assertThat(memoCount()).isZero();

        AgentTurnResponse done = service.chat(start.conversationId(), "8点");
        assertThat(done.reply()).contains("已记下");
        MemoStore.MemoView memo = onlyMemo();
        assertThat(memo.text()).isEqualTo("量血压");
        assertThat(memo.remindAt()).isEqualTo(LocalDate.now(DEMO_ZONE).plusDays(1).atTime(8, 0));
    }

    @Test
    void ambiguousTimeAnsweringNoReminderKeepsStandingMemo() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "明早提醒我量血压");
        AgentTurnResponse done = service.chat(start.conversationId(), "不用提醒，只记下");

        assertThat(done.reply()).contains("已记下").contains("长期备忘");
        assertThat(onlyMemo().remindAt()).isNull();
    }

    @Test
    void unclearClockAnswerAsksAgainWithoutDroppingDraft() {
        AgentTurnResponse start = start();
        AgentTurnResponse ask = service.chat(start.conversationId(), "明早提醒我量血压");
        AgentTurnResponse retry = service.chat(start.conversationId(), "嗯嗯");

        assertThat(retry.reply()).contains("没听清");
        assertThat(memoCount()).isZero();

        AgentTurnResponse done = service.chat(start.conversationId(), "上午8点");
        assertThat(done.reply()).contains("已记下");
        assertThat(onlyMemo().remindAt()).isEqualTo(LocalDate.now(DEMO_ZONE).plusDays(1).atTime(8, 0));
    }

    /**
     * 补答的那句里带了两个钟点：不落库、<b>草稿留着</b>、并且把“另一件事我还没记”说出来。
     *
     * <p>{@code resolveRemindAt} 只取一个钟点：答“早上八点吃药，下午三点量血压”只会落成 08:00 一条，
     * “下周三下午三点复查，下周五早上八点吃药”会落成两天共用 15:00。所以这一步不能猜。
     *
     * <p>和 {@code aSentenceWithTwoMomentsIsAskedBackInsteadOfBeingRecorded} 的关键差别是草稿：
     * 追问到这一步内容已经问清楚了（“量血压”），作废的只是“钟点”这个答案，清掉草稿等于让他为了
     * 一个钟点把内容重讲一遍。所以这里钉三件——没落库、草稿还在（补一个钟点就能落）、
     * 落的是那句“量血压”而不是答句里的“吃药”。
     */
    @Test
    void anAnswerWithTwoClocksIsAskedBackAndKeepsTheDraft() {
        AgentTurnResponse start = start();
        AgentTurnResponse ask = service.chat(start.conversationId(), "明早提醒我量血压");
        assertThat(ask.stage()).as(ask.reply()).isEqualTo("MEMO_TIME");

        AgentTurnResponse again = service.chat(start.conversationId(), "早上八点吃药，下午三点量血压");

        assertThat(again.stage()).as(again.reply()).isEqualTo("MEMO_TIME");
        assertThat(again.reply()).as(again.reply())
                .contains("一次只能记一个到点时间").contains("再跟我说一遍");
        assertThat(memoCount()).isZero();

        // 草稿还在：只补一个钟点就能落库
        AgentTurnResponse done = service.chat(start.conversationId(), "早上八点");
        assertThat(done.reply()).as(done.reply()).contains("已记下");
        MemoStore.MemoView memo = onlyMemo();
        assertThat(memo.text()).isEqualTo("量血压");
        assertThat(memo.remindAt()).isEqualTo(LocalDate.now(DEMO_ZONE).plusDays(1).atTime(8, 0));
    }

    /**
     * 但“同一件事的两天”不能被误伤：答“下周三，下周五”里没有第二个钟点，仍然该拆成两条。
     *
     * <p>这条是上一条的对照——判据只看钟点、不看日子，否则补答多天这条正常路会被挡在门外。
     * 断言写成“两条相隔两天、都在 8:00”，不写死具体日期：“下周三”的算法在测试运行日是周几时不一样，
     * 写死日期会让测试在某个特定星期几才过（这个坑以前踩过）。
     */
    @Test
    void anAnswerNamingTwoDaysIsStillRecordedAsTwoMemos() {
        AgentTurnResponse start = start();
        // 用显式托付，走直写那条路；隐式那条会先给确认卡，这里要验的是“有没有被拦”，不是卡本身
        service.chat(start.conversationId(), "提醒我这周要吃药");
        AgentTurnResponse days = service.chat(start.conversationId(), "下周三，下周五");
        assertThat(days.reply()).as(days.reply()).contains("还差具体几点");
        assertThat(memoCount()).isZero();

        AgentTurnResponse done = service.chat(start.conversationId(), "早上八点");

        assertThat(done.reply()).as(done.reply()).contains("已记下 2 条");
        assertThat(activeMemos()).hasSize(2);
        List<LocalDateTime> ats = activeMemos().stream()
                .map(MemoStore.MemoView::remindAt).sorted().toList();
        assertThat(ats).as("周三和周五各一条，都是 8:00")
                .allSatisfy(at -> assertThat(at.toLocalTime()).isEqualTo(LocalTime.of(8, 0)));
        assertThat(ats.get(1)).isEqualTo(ats.get(0).plusDays(2));
        assertThat(activeMemos()).allSatisfy(memo -> assertThat(memo.text()).contains("吃药"));
    }

    @Test
    void dailyRepeatIsSavedWithRepeatRule() {
        AgentTurnResponse start = start();
        AgentTurnResponse reply = service.chat(start.conversationId(), "每天八点提醒我吃药");

        // 回读要带周期锚点（“每天 08:00”），不能只说“每天”——老人听不出到底几点
        assertThat(reply.reply()).contains("已记下", "每天 08:00");
        MemoStore.MemoView memo = onlyMemo();
        assertThat(memo.repeatRule()).isEqualTo("DAILY");
        assertThat(memo.remindAt().toLocalTime()).isEqualTo(java.time.LocalTime.of(8, 0));
    }

    @Test
    void weeklyRepeatHearsBackTheWeekdayItWillFireOn() {
        AgentTurnResponse start = start();
        AgentTurnResponse ask = service.chat(start.conversationId(), "每周三下午三点量血压");

        // 隐式备忘先确认；确认卡上要写清楚“每周三 15:00”，不能只说“每周”
        assertThat(ask.stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(ask.confirmation().operations().toString()).contains("每周三", "15:00");

        AgentTurnResponse done = service.confirm(ask.conversationId(), true, ask.confirmation().confirmationId());
        assertThat(done.reply()).contains("已记下", "每周三 15:00");
        MemoStore.MemoView memo = onlyMemo();
        assertThat(memo.repeatRule()).isEqualTo("WEEKLY");
        assertThat(memo.remindAt().getDayOfWeek()).isEqualTo(java.time.DayOfWeek.WEDNESDAY);
    }

    @Test
    void monthlyRepeatIsSavedWithRepeatRule() {
        AgentTurnResponse start = start();
        AgentTurnResponse reply = service.chat(start.conversationId(), "每月15号上午九点提醒我复查");

        assertThat(reply.reply()).contains("已记下", "每月");
        MemoStore.MemoView memo = onlyMemo();
        assertThat(memo.repeatRule()).isEqualTo("MONTHLY");
        assertThat(memo.remindAt().getDayOfMonth()).isEqualTo(15);
    }

    @Test
    void dailyWithoutClockAsksTimeAndKeepsRepeatRule() {
        // 重复规则要跟着追问流程走：答完“几点”以后仍是每天提醒，不能掉成一次性
        AgentTurnResponse start = start();
        AgentTurnResponse ask = service.chat(start.conversationId(), "每天提醒我量血压");

        assertThat(ask.stage()).isEqualTo("MEMO_TIME");
        assertThat(ask.reply()).contains("几点", "每天");
        assertThat(memoCount()).isZero();

        AgentTurnResponse done = service.chat(start.conversationId(), "早上八点");
        assertThat(done.reply()).contains("已记下", "每天");
        MemoStore.MemoView memo = onlyMemo();
        assertThat(memo.repeatRule()).isEqualTo("DAILY");
        assertThat(memo.remindAt().toLocalTime()).isEqualTo(java.time.LocalTime.of(8, 0));
    }

    @Test
    void updateMemoCanSetAndClearRepeatRule() {
        MemoStore.MemoView memo = memos.create("user-001", "八点吃药",
                LocalDate.now(DEMO_ZONE).plusDays(1).atTime(8, 0));

        assertThat(memos.update("user-001", memo.id(), "八点吃药",
                LocalDate.now(DEMO_ZONE).plusDays(1).atTime(8, 0), "daily")).isTrue();
        assertThat(onlyMemo().repeatRule()).isEqualTo("DAILY");

        assertThat(memos.update("user-001", memo.id(), "八点吃药",
                LocalDate.now(DEMO_ZONE).plusDays(1).atTime(8, 0), null)).isTrue();
        assertThat(onlyMemo().repeatRule()).isNull();

        // 认不出的重复词按“仅一次”处理，不能存进库
        assertThat(memos.update("user-001", memo.id(), "八点吃药",
                LocalDate.now(DEMO_ZONE).plusDays(1).atTime(8, 0), "EVERY_HOUR")).isTrue();
        assertThat(onlyMemo().repeatRule()).isNull();
    }

    @Test
    void pastWeekdayIsAskedAgainAndThenOnlyTheClock() {
        // “这个星期三”说的那天已经过去：先问哪一天，老人答“下周三”后只差钟点，
        // 不能把已经听懂的日子丢掉再问一遍“没听清是哪一天”
        LocalDate today = LocalDate.now(DEMO_ZONE);
        int weekday = today.getDayOfWeek().getValue();
        if (weekday == 1) return; // 周一没有“本周已过去”的那天，这条用例不适用
        String past = "这个星期" + "一二三四五六日".charAt(weekday - 2);

        AgentTurnResponse start = start();
        AgentTurnResponse ask = service.chat(start.conversationId(), past + "提醒我量血压");
        assertThat(ask.reply()).contains("已经过去了");
        assertThat(ask.stage()).isEqualTo("MEMO_TIME");
        assertThat(memoCount()).isZero();

        AgentTurnResponse clock = service.chat(start.conversationId(), "下周三");
        assertThat(clock.reply()).contains("还差具体几点").doesNotContain("没听清");

        AgentTurnResponse done = service.chat(start.conversationId(), "下午三点");
        assertThat(done.reply()).contains("已记下");
        MemoStore.MemoView memo = onlyMemo();
        assertThat(memo.remindAt().getDayOfWeek()).isEqualTo(java.time.DayOfWeek.WEDNESDAY);
        assertThat(memo.remindAt().toLocalTime()).isEqualTo(java.time.LocalTime.of(15, 0));
        assertThat(memo.remindAt()).isAfter(LocalDateTime.now(DEMO_ZONE));
    }

    @Test
    void conditionalTimeIsAskedBackInsteadOfBeingDropped() {
        // “饭后半小时”没说准钟点：追问时要回显这句话本身，答完钟点再落库
        AgentTurnResponse start = start();
        AgentTurnResponse ask = service.chat(start.conversationId(), "饭后半小时提醒我吃药");

        assertThat(ask.stage()).isEqualTo("MEMO_TIME");
        assertThat(ask.reply()).contains("饭后半小时");
        assertThat(memoCount()).isZero();

        AgentTurnResponse done = service.chat(start.conversationId(), "中午十二点");
        assertThat(done.reply()).contains("已记下");
        assertThat(onlyMemo().remindAt().toLocalTime()).isEqualTo(java.time.LocalTime.of(12, 0));
    }

    @Test
    void ambiguousImplicitMemoAsksClockThenConfirmsBeforeWriting() {
        AgentTurnResponse start = start();
        AgentTurnResponse ask = service.chat(start.conversationId(), "明早要去抽血，记得空腹");

        assertThat(ask.stage()).isEqualTo("MEMO_TIME");
        assertThat(memoCount()).isZero();

        AgentTurnResponse confirmCard = service.chat(start.conversationId(), "8点");
        assertThat(confirmCard.stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(confirmCard.confirmation()).isNotNull();
        assertThat(memoCount()).isZero();

        AgentTurnResponse done = service.confirm(confirmCard.conversationId(), true, confirmCard.confirmation().confirmationId());
        assertThat(done.reply()).contains("已记下");
        MemoStore.MemoView memo = onlyMemo();
        assertThat(memo.remindAt()).isEqualTo(LocalDate.now(DEMO_ZONE).plusDays(1).atTime(8, 0));
    }

    /** 从今天起算的下一个周一（今天就是周一时算 7 天后），与 MemoParser 的“下周”口径一致。 */
    private static LocalDate nextMonday() {
        int toMonday = (8 - LocalDate.now(DEMO_ZONE).getDayOfWeek().getValue()) % 7;
        return LocalDate.now(DEMO_ZONE).plusDays(toMonday == 0 ? 7 : toMonday);
    }

    private List<MemoStore.MemoView> activeMemos() {
        return memos.activeFor("user-001");
    }

    /**
     * 一句话里两个时间点：<b>反问，不落库</b>。
     *
     * <p>解析器一条备忘只认一套“钟点 + 日子”，硬记的结果是两件事共用一个钟点——老人到点听到的是
     * 张冠李戴的提醒，而且听不出错在哪。不替他挑“头一件”（挑错了他还听不出来），请他重说。
     *
     * <p>“不留草稿”和“不落库”一样重要：留个半截草稿，下一句就会被接着当成补充答案，
     * 于是“周三下午三点”摇身一变成了明天那件事的时间。所以这条还钉了下一句从零解析。
     */
    @Test
    void aSentenceWithTwoMomentsIsAskedBackInsteadOfBeingRecorded() {
        AgentTurnResponse start = start();
        AgentTurnResponse ask = service.chat(start.conversationId(),
                "提醒我周一早上八点吃药，周三下午三点复查");

        assertThat(ask.stage()).as(ask.reply()).isNotEqualTo("AWAITING_CONFIRMATION");
        assertThat(ask.reply()).contains("一件一件说");
        assertThat(ask.confirmation()).as("话都没听懂，不能摆一张确认卡").isNull();
        assertThat(memoCount()).isZero();

        // 下一句正常说，从零解析：只落一条，时间是明天早上八点，不是那句里的周三下午三点
        AgentTurnResponse next = service.chat(start.conversationId(), "提醒我明天早上八点吃药");
        assertThat(next.reply()).as(next.reply()).contains("已记下");
        assertThat(onlyMemo().remindAt()).isEqualTo(LocalDate.now(DEMO_ZONE).plusDays(1).atTime(8, 0));
        assertThat(onlyMemo().text()).isEqualTo("吃药");
    }

    @Test
    void severalDaysInOneSentenceBecomeOneMemoPerDay() {
        // “下周周一周二周三”原来只存周一，周二周三被静默丢掉；每条的正文也不该给周三那条写着周一
        AgentTurnResponse start = start();
        AgentTurnResponse reply = service.chat(start.conversationId(), "提醒我下周周一周二周三早上八点吃药");

        assertThat(reply.reply()).contains("已记下 3 条");
        assertThat(activeMemos()).extracting(MemoStore.MemoView::remindAt).containsExactlyInAnyOrder(
                nextMonday().atTime(8, 0), nextMonday().plusDays(1).atTime(8, 0), nextMonday().plusDays(2).atTime(8, 0));
        // 每条正文只说自己那天，不留“三五”这种残渣
        assertThat(activeMemos()).allSatisfy(memo -> assertThat(memo.text()).isEqualTo("吃药"));
    }

    @Test
    void pastWeekdayGroupIsAskedInsteadOfBeingRecordedInTheFuture() {
        // 周五说“这周周一周二周三”，这三天全过去了：不能静默算到下周，也不能只挑一天来问
        if (LocalDate.now(DEMO_ZONE).getDayOfWeek().getValue() == 1) return; // 周一没有“本周已过去”的说法

        AgentTurnResponse start = start();
        AgentTurnResponse ask = service.chat(start.conversationId(), "提醒我这周周一周二周三早上八点吃药");

        assertThat(ask.stage()).isEqualTo("MEMO_TIME");
        assertThat(ask.reply()).contains("已经过去了");
        assertThat(memoCount()).isZero();

        // 快捷回复得把整串带到下周：只给“下周一”的话老人一点，周二周三就没了
        AgentTurnResponse done = service.chat(start.conversationId(), "下周周一周二周三");
        assertThat(done.reply()).contains("已记下 3 条");
        assertThat(activeMemos()).extracting(MemoStore.MemoView::remindAt).containsExactlyInAnyOrder(
                nextMonday().atTime(8, 0), nextMonday().plusDays(1).atTime(8, 0), nextMonday().plusDays(2).atTime(8, 0));
    }

    @Test
    void weekScopeAloneAsksTheDayBeforeTheClock() {
        // 回归：老人说“我这周要吃药”只给了范围，原来直接问几点，答完按“今天已过点就顺延明天”
        // 悄悄记成周六。现在先问哪一天，问到了才轮到钟点
        AgentTurnResponse start = start();
        AgentTurnResponse askDay = service.chat(start.conversationId(), "我这周要吃药");

        assertThat(askDay.stage()).isEqualTo("MEMO_TIME");
        assertThat(askDay.reply()).contains("还没说具体哪一天").doesNotContain("几点");
        assertThat(memoCount()).isZero();

        AgentTurnResponse askClock = service.chat(start.conversationId(), "这周日");
        assertThat(askClock.reply()).contains("还差具体几点");
        assertThat(memoCount()).isZero();

        AgentTurnResponse card = service.chat(start.conversationId(), "早上八点");
        assertThat(card.stage()).isEqualTo("AWAITING_CONFIRMATION");
        AgentTurnResponse done = service.confirm(card.conversationId(), true, card.confirmation().confirmationId());

        assertThat(done.reply()).contains("已记下");
        LocalDate today = LocalDate.now(DEMO_ZONE);
        LocalDate sunday = today.plusDays((7 - today.getDayOfWeek().getValue() + 7) % 7);
        assertThat(onlyMemo().remindAt()).isEqualTo(sunday.atTime(8, 0));
    }

    @Test
    void bareWeeklyAsksWhichWeekdayThenSavesRepeatRule() {
        // 回归：只说“每周”没说周几，原来会存成一条永远不到点的备忘；现在要先追问
        AgentTurnResponse start = start();
        AgentTurnResponse ask = service.chat(start.conversationId(), "每周提醒我量血压");

        assertThat(ask.reply()).contains("每周几");
        assertThat(ask.stage()).isEqualTo("MEMO_TIME");
        assertThat(memoCount()).isZero();

        AgentTurnResponse clock = service.chat(start.conversationId(), "每周三");
        assertThat(clock.reply()).contains("每周三", "还差具体几点");
        assertThat(memoCount()).isZero();

        AgentTurnResponse done = service.chat(start.conversationId(), "下午三点");
        assertThat(done.reply()).contains("已记下", "每周三 15:00");
        MemoStore.MemoView memo = onlyMemo();
        assertThat(memo.repeatRule()).isEqualTo("WEEKLY");
        assertThat(memo.remindAt().getDayOfWeek()).isEqualTo(java.time.DayOfWeek.WEDNESDAY);
        assertThat(memo.remindAt().toLocalTime()).isEqualTo(java.time.LocalTime.of(15, 0));
        assertThat(memo.remindAt()).isAfter(LocalDateTime.now(DEMO_ZONE));
    }

    @Test
    void bareMonthlyAsksWhichDayOfMonth() {
        AgentTurnResponse start = start();
        AgentTurnResponse ask = service.chat(start.conversationId(), "每月提醒我量血压");

        assertThat(ask.reply()).contains("每月几号");
        assertThat(memoCount()).isZero();

        // 一句话里连几号和钟点一起答上来：直接落库，不用再问一轮
        AgentTurnResponse done = service.chat(start.conversationId(), "每月20号上午九点");
        assertThat(done.reply()).contains("已记下", "每月20号 09:00");
        MemoStore.MemoView memo = onlyMemo();
        assertThat(memo.repeatRule()).isEqualTo("MONTHLY");
        assertThat(memo.remindAt().getDayOfMonth()).isEqualTo(20);
        assertThat(memo.remindAt().toLocalTime()).isEqualTo(java.time.LocalTime.of(9, 0));
    }

    @Test
    void bareRepeatAnsweringNoReminderStoresAStandingMemo() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "每周提醒我量血压");
        AgentTurnResponse done = service.chat(start.conversationId(), "不用提醒，只记下");

        assertThat(done.reply()).contains("已记下", "长期备忘");
        MemoStore.MemoView memo = onlyMemo();
        assertThat(memo.remindAt()).isNull();
        // 改主意不提醒了，重复规则一并作废，不能留下一条每周空响的备忘
        assertThat(memo.repeatRule()).isNull();
    }

    @Test
    void bareRepeatUnclearAnswerAsksAgainWithoutDroppingTheDraft() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "每月提醒我量血压");
        AgentTurnResponse retry = service.chat(start.conversationId(), "嗯嗯");

        assertThat(retry.reply()).contains("没听清");
        assertThat(memoCount()).isZero();

        AgentTurnResponse done = service.chat(start.conversationId(), "每月1号早上八点");
        assertThat(done.reply()).contains("已记下");
        assertThat(onlyMemo().repeatRule()).isEqualTo("MONTHLY");
    }

    @Test
    void listMemosNumbersThemAndOffersEditAndDeleteReplies() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "明天早上八点提醒我吃药");
        AgentTurnResponse reply = service.chat(start.conversationId(), "我都有哪些备忘");

        assertThat(reply.reply()).contains("1．", "吃药", "改第几条");
        assertThat(reply.quickReplies()).extracting(AgentTurnResponse.QuickReply::label)
                .contains("改第1条", "删第1条");
        // 摆清单不该顺手写库
        assertThat(memoCount()).isEqualTo(1);
    }

    @Test
    void listMemosWithNothingRecordedTeachesHowToAddOne() {
        AgentTurnResponse start = start();
        AgentTurnResponse reply = service.chat(start.conversationId(), "我都有哪些备忘");

        assertThat(reply.reply()).contains("还没有健康备忘", "明早八点提醒我吃药");
    }

    @Test
    void editMemoByOrdinalAsksThenUpdatesTheTime() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "明天早上八点提醒我吃药");

        AgentTurnResponse ask = service.chat(start.conversationId(), "改第1条");
        assertThat(ask.reply()).contains("改到什么时候").contains("吃药");

        AgentTurnResponse done = service.chat(start.conversationId(), "明天早上九点");
        assertThat(done.reply()).contains("已把", "提醒改成");
        MemoStore.MemoView memo = onlyMemo();
        assertThat(memo.remindAt()).isEqualTo(LocalDate.now(DEMO_ZONE).plusDays(1).atTime(9, 0));
        // 改过时间的备忘正文不再带时间说法（首页“提醒”那一行已经写清楚了），避免两处打架
        assertThat(memo.text()).isEqualTo("吃药");
    }

    @Test
    void editMemoInOneSentenceWithNewTimeAppliesRightAway() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "明天早上八点提醒我吃药");

        AgentTurnResponse done = service.chat(start.conversationId(), "把第1条改到每天下午三点");
        assertThat(done.reply()).contains("已把", "每天 15:00");
        MemoStore.MemoView memo = onlyMemo();
        assertThat(memo.repeatRule()).isEqualTo("DAILY");
        assertThat(memo.remindAt().toLocalTime()).isEqualTo(java.time.LocalTime.of(15, 0));
    }

    /**
     * 改期那句里说了两个钟点：这条备忘只装得下一个到点时间，硬取第一个就是「看着说改到下午三点、
     * 实际按早上八点响」，而且老人听不出错在哪。先问清楚，且挂起的那条不能丢——他挑一个就接着改。
     */
    @Test
    void editWithTwoClocksAsksWhichOneInsteadOfTakingTheFirstSilently() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "明天早上八点提醒我吃药");
        service.chat(start.conversationId(), "改第1条");

        AgentTurnResponse ask = service.chat(start.conversationId(), "改成后天早上八点，还有周三下午三点");
        assertThat(ask.reply()).contains("两个时间", "哪个时间");
        // 没改：还是原来那个时间，也没有多出一条备忘
        assertThat(memoCount()).isEqualTo(1);
        assertThat(onlyMemo().remindAt()).isEqualTo(LocalDate.now(DEMO_ZONE).plusDays(1).atTime(8, 0));

        // 挑了一个就接着改——挂起的备忘没丢，不用重新说“改第1条”
        AgentTurnResponse done = service.chat(start.conversationId(), "周三下午三点");
        assertThat(done.reply()).contains("已把", "提醒改成");
        assertThat(onlyMemo().remindAt().toLocalTime()).isEqualTo(LocalTime.of(15, 0));
        assertThat(onlyMemo().remindAt().getDayOfWeek().getValue()).isEqualTo(3);
    }

    /**
     * 同一件事的两天：改期只动这一条备忘，装不下两天，取第一个就是把周五静默丢掉——
     * 和两个钟点同一个出口，先请他挑一个（这条只走了日子那半个判据：钟点只有一个）。
     */
    @Test
    void editWithTwoDaysAsksWhichOneInsteadOfTakingTheFirstSilently() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "明天早上八点提醒我吃药");
        service.chat(start.conversationId(), "改第1条");

        AgentTurnResponse ask = service.chat(start.conversationId(), "改成周三和周五早上八点");
        assertThat(ask.reply()).contains("两个时间", "哪个时间");
        assertThat(memoCount()).isEqualTo(1);
        assertThat(onlyMemo().remindAt()).isEqualTo(LocalDate.now(DEMO_ZONE).plusDays(1).atTime(8, 0));

        // 挑了一天就接着改
        AgentTurnResponse done = service.chat(start.conversationId(), "周三早上八点");
        assertThat(done.reply()).contains("已把", "提醒改成");
        assertThat(onlyMemo().remindAt().getDayOfWeek().getValue()).isEqualTo(3);
        assertThat(onlyMemo().remindAt().toLocalTime()).isEqualTo(LocalTime.of(8, 0));
    }

    @Test
    void editMemoCanTurnOffTheReminderAndKeepTheMemo() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "明天早上八点提醒我吃药");
        service.chat(start.conversationId(), "改第1条");
        AgentTurnResponse done = service.chat(start.conversationId(), "不用提醒了");

        assertThat(done.reply()).contains("不再提醒");
        assertThat(onlyMemo().remindAt()).isNull();
    }

    @Test
    void deleteMemoAsksFirstAndRefusalKeepsIt() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "明天早上八点提醒我吃药");

        AgentTurnResponse ask = service.chat(start.conversationId(), "删第1条");
        assertThat(ask.reply()).contains("要删掉").contains("吃药");
        assertThat(memoCount()).isEqualTo(1);

        // “先不删”里也有个“删”字，不能被当成同意
        AgentTurnResponse kept = service.chat(start.conversationId(), "先不删");
        assertThat(kept.reply()).contains("保留");
        assertThat(memoCount()).isEqualTo(1);
    }

    @Test
    void deleteMemoConfirmedRemovesTheRow() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "明天早上八点提醒我吃药");
        service.chat(start.conversationId(), "删第1条");
        AgentTurnResponse done = service.chat(start.conversationId(), "确认删掉");

        assertThat(done.reply()).contains("已删掉");
        assertThat(memoCount()).isZero();
    }

    @Test
    void followUpQuestionsOfferNoPresetTimeOptionsButKeepTheNoReminderEscape() {
        // 周几/几号/几点都不给“早上8点/下午3点”这种预设：选项再多也盖不全，
        // 还等于在老人没说时间的时候替他挑一个。唯一保留的是“不用提醒，只记下”——
        // 它不是猜老人要什么时间，而是给不想设提醒的老人一条退路
        AgentTurnResponse start = start();
        assertThat(service.chat(start.conversationId(), "每周提醒我量血压").quickReplies())
                .extracting(AgentTurnResponse.QuickReply::label).containsExactly("不用提醒，只记下");
        assertThat(service.chat(start.conversationId(), "每周三").quickReplies())
                .extracting(AgentTurnResponse.QuickReply::label).containsExactly("不用提醒，只记下");

        assertThat(service.chat(start.conversationId(), "下午三点").reply()).contains("已记下");
        // 改提醒时间那一步同理：要么给个新时间，要么点“不用提醒了”
        assertThat(service.chat(start.conversationId(), "改第1条").quickReplies())
                .extracting(AgentTurnResponse.QuickReply::label).containsExactly("不用提醒了");
    }

    @Test
    void editingTheTimeAlsoDropsTheOldScheduleFromTheText() {
        // 回归（用户实测）：正文写着“每月”，改成明天九点后首页成了“事项：每月提醒我量血压 /
        // 提醒：9月11日 09:00”，自己跟自己打架。改时间要连正文里的时间说法一起摘掉。
        // 新建的备忘正文已经不带时间说法，这里直接插一条旧数据来覆盖这条老路径
        AgentTurnResponse start = start();
        memos.create("user-001", "每月提醒我量血压", LocalDateTime.now(DEMO_ZONE).plusDays(20), "MONTHLY");
        assertThat(onlyMemo().text()).isEqualTo("每月提醒我量血压");

        service.chat(start.conversationId(), "改第1条");
        AgentTurnResponse done = service.chat(start.conversationId(), "明天早上九点");

        assertThat(done.reply()).contains("量血压", "去掉");
        MemoStore.MemoView memo = onlyMemo();
        assertThat(memo.text()).isEqualTo("量血压");
        assertThat(memo.repeatRule()).isNull();
        assertThat(memo.remindAt()).isEqualTo(LocalDate.now(DEMO_ZONE).plusDays(1).atTime(9, 0));
    }

    @Test
    void editingToAWeeklyRepeatDropsTheOldDailyPhraseToo() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "每天早上八点提醒我量血压");
        assertThat(onlyMemo().repeatRule()).isEqualTo("DAILY");

        AgentTurnResponse done = service.chat(start.conversationId(), "把第1条改到每周五下午三点");

        assertThat(done.reply()).contains("量血压", "每周五 15:00");
        MemoStore.MemoView memo = onlyMemo();
        assertThat(memo.text()).isEqualTo("量血压");
        assertThat(memo.repeatRule()).isEqualTo("WEEKLY");
        assertThat(memo.remindAt().getDayOfWeek()).isEqualTo(java.time.DayOfWeek.FRIDAY);
        assertThat(memo.remindAt().toLocalTime()).isEqualTo(java.time.LocalTime.of(15, 0));
    }

    @Test
    void editingToAStandingMemoAlsoDropsTheOldSchedule() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "每天八点提醒我吃药");
        service.chat(start.conversationId(), "改第1条");
        service.chat(start.conversationId(), "不用提醒了");

        MemoStore.MemoView memo = onlyMemo();
        assertThat(memo.text()).isEqualTo("吃药");
        assertThat(memo.remindAt()).isNull();
        assertThat(memo.repeatRule()).isNull();
    }

    @Test
    void editingWithAnUnknownOrdinalShowsTheListInsteadOfGuessing() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "明天早上八点提醒我吃药");
        AgentTurnResponse reply = service.chat(start.conversationId(), "改第5条");

        assertThat(reply.reply()).contains("没有找到", "1．");
        assertThat(memoCount()).isEqualTo(1);
    }

    @Test
    void creatingAMemoStripsTheScheduleFromTheText() {
        // 新建时就把时间说法摘掉，正文和“提醒”那一行才不会各说各的
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "明天早上八点提醒我吃药");

        MemoStore.MemoView memo = onlyMemo();
        assertThat(memo.text()).isEqualTo("吃药");
        assertThat(memo.remindAt()).isEqualTo(LocalDate.now(DEMO_ZONE).plusDays(1).atTime(8, 0));
    }

    @Test
    void standingMemoKeepsTheDateBecauseTheDateIsTheThing() {
        // 长期备忘没有“提醒”那一行，正文里的日期就是内容本身：老人说“不用提醒”指的是
        // 别弹提醒，不是把“9月20号”这件事删掉
        AgentTurnResponse start = start();
        AgentTurnResponse ask = service.chat(start.conversationId(), "记一下，9月20号家人来接我");
        assertThat(ask.stage()).isEqualTo("MEMO_TIME");

        AgentTurnResponse done = service.chat(start.conversationId(), "不用提醒，只记下");
        assertThat(done.reply()).contains("长期备忘");
        MemoStore.MemoView memo = onlyMemo();
        assertThat(memo.remindAt()).isNull();
        assertThat(memo.text()).contains("9月20号");
    }

    @Test
    void clockFollowUpOffersTheNoReminderButton() {
        // 光在话里说“不需要提醒就说…”，老人得自己敲这七个字；这按钮是长期备忘唯一的入口
        AgentTurnResponse start = start();
        AgentTurnResponse ask = service.chat(start.conversationId(), "明早提醒我量血压");
        assertThat(ask.stage()).isEqualTo("MEMO_TIME");
        assertThat(ask.quickReplies()).extracting(AgentTurnResponse.QuickReply::label)
                .contains("不用提醒，只记下");

        AgentTurnResponse done = service.act(ask.conversationId(), "MEMO_TIME", "不用提醒，只记下", "不用提醒，只记下");
        assertThat(done.reply()).contains("已记下").contains("长期备忘");
        assertThat(onlyMemo().remindAt()).isNull();
    }

    /**
     * 办理停掉之后，备忘照样要能记。
     *
     * <p>回归的是实测出来的那个 bug：老人取消办理后，说多少遍“提醒我后天七点测血糖”都记不上，
     * 只有重新办理才恢复，而界面还写着“当前可以自由交流”。模型模式下这条备忘路由排在
     * stopped 检查后面，被整个拦掉；回退模式里它本来就在前面，所以两种模式行为不一致——
     * 而当时的测试全用回退模式，测不出这个差别。这个用例守的是两种模式共同的行为契约。
     */
    @Test
    void cancelledBookingStillRecordsAMemo() {
        AgentTurnResponse start = start();
        AgentTurnResponse cancelled = service.act(start.conversationId(), "CANCEL_TASK", "", "取消本次办理");
        assertThat(cancelled.stage()).isEqualTo("CANCELLED");

        AgentTurnResponse done = service.chat(start.conversationId(), "提醒我后天早上七点测血糖");

        assertThat(done.reply()).contains("已记下", "测血糖");
        assertThat(onlyMemo().remindAt())
                .isEqualTo(LocalDate.now(DEMO_ZONE).plusDays(2).atTime(7, 0));
    }

    /**
     * 记完备忘不能把“已经停掉的办理”重新推回去。
     *
     * <p>原来接力落到 askHospital，回的是“我们继续办理复诊，请问您想去哪家医院”，还会把
     * stage 从 CANCELLED 改成 ASK_HOSPITAL——老人刚取消完，记一条提醒就被塞回了预约流程。
     * 要重新办，得由老人自己点“新建办理”。
     */
    @Test
    void recordingAMemoAfterCancelDoesNotRestartTheBooking() {
        AgentTurnResponse start = start();
        service.act(start.conversationId(), "CANCEL_TASK", "", "取消本次办理");

        AgentTurnResponse done = service.chat(start.conversationId(), "提醒我后天早上七点测血糖");

        assertThat(done.stage()).isEqualTo("CANCELLED");
        assertThat(done.reply()).doesNotContain("想去哪家医院", "继续办理复诊");
        assertThat(done.quickReplies()).extracting(AgentTurnResponse.QuickReply::label).contains("新建办理");
    }
}
