package com.team.silveragent;

import com.team.silveragent.application.MemoParser;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 备忘钟点识别：中文数字(七点/三点半/七点二十五/十二点)与阿拉伯数字都解析到正确时刻，
 * 特别是“早上七点”不能因中文数字没识别而回落到时段默认值“早上8点”。
 */
class MemoParserClockTests {

    private static final ZoneId DEMO_ZONE = ZoneId.of("Asia/Shanghai");

    private static LocalDate tomorrow() {
        return LocalDate.now(DEMO_ZONE).plusDays(1);
    }

    @Test
    void chineseHourSevenParsesToSevenNotMorningFallback() {
        MemoParser.MemoIntent memo = MemoParser.detect("明天早上七点需要吃药");

        assertThat(memo).isNotNull();
        assertThat(memo.remindAt()).isEqualTo(tomorrow().atTime(7, 0));
    }

    @Test
    void chineseHourWithHalfPastParsesToTime() {
        MemoParser.MemoIntent memo = MemoParser.detect("下午三点半要去复查");

        assertThat(memo).isNotNull();
        assertThat(memo.remindAt().toLocalTime()).isEqualTo(java.time.LocalTime.of(15, 30));
    }

    @Test
    void eveningSevenOclockIsNineteenHundred() {
        MemoParser.MemoIntent memo = MemoParser.detect("晚上七点吃药");

        assertThat(memo).isNotNull();
        assertThat(memo.remindAt().toLocalTime()).isEqualTo(java.time.LocalTime.of(19, 0));
    }

    @Test
    void chineseTwoDigitMinutesParsed() {
        MemoParser.MemoIntent memo = MemoParser.detect("明天早上七点二十五吃药");

        assertThat(memo).isNotNull();
        assertThat(memo.remindAt()).isEqualTo(tomorrow().atTime(7, 25));
    }

    @Test
    void twelveThirtyParsed() {
        MemoParser.MemoIntent memo = MemoParser.detect("明天十二点半吃药");

        assertThat(memo).isNotNull();
        assertThat(memo.remindAt()).isEqualTo(tomorrow().atTime(12, 30));
    }

    @Test
    void arabicClockStillParses() {
        MemoParser.MemoIntent memo = MemoParser.detect("明天下午3点去抽血");

        assertThat(memo).isNotNull();
        assertThat(memo.remindAt()).isEqualTo(tomorrow().atTime(15, 0));
    }

    @Test
    void chineseNumeralMinutesLaterIsTimed() {
        // 回归：说“一分钟之后需要吃药”必须算作提到时间（原来是只认阿拉伯数字的“1分钟后”，识别不到、整条不记）
        MemoParser.MemoIntent memo = MemoParser.detect("我一分钟之后需要吃药");

        assertThat(memo).isNotNull();
        assertThat(memo.explicit()).isFalse();
        assertThat(memo.remindAt()).isNotNull()
                .isAfter(java.time.LocalDateTime.now(DEMO_ZONE).plusSeconds(30))
                .isBefore(java.time.LocalDateTime.now(DEMO_ZONE).plusMinutes(2));
    }

    @Test
    void chineseTenMinutesLaterIsTimed() {
        MemoParser.MemoIntent memo = MemoParser.detect("十分钟以后要吃药");

        assertThat(memo).isNotNull();
        assertThat(memo.remindAt()).isNotNull()
                .isAfter(java.time.LocalDateTime.now(DEMO_ZONE).plusMinutes(9))
                .isBefore(java.time.LocalDateTime.now(DEMO_ZONE).plusMinutes(11));
    }

    @Test
    void arabicMinutesAfterStillParses() {
        MemoParser.MemoIntent memo = MemoParser.detect("2分钟后提醒我量血压");

        assertThat(memo).isNotNull();
        assertThat(memo.remindAt()).isNotNull()
                .isAfter(java.time.LocalDateTime.now(DEMO_ZONE).plusSeconds(60))
                .isBefore(java.time.LocalDateTime.now(DEMO_ZONE).plusMinutes(3));
    }

    @Test
    void chineseHourWithoutPeriodOrDayWordIsNotDropped() {
        // 回归：老人只说“八点吃药”（没有“早上/明天”这类词）原来整条被丢弃
        MemoParser.MemoIntent memo = MemoParser.detect("八点吃药");

        assertThat(memo).isNotNull();
        assertThat(memo.remindAt().toLocalTime()).isEqualTo(java.time.LocalTime.of(8, 0));
        assertThat(memo.remindAt()).isAfter(java.time.LocalDateTime.now(DEMO_ZONE));
    }

    @Test
    void chineseQuarterAndLeadingZeroMinutesParsed() {
        assertThat(MemoParser.detect("九点一刻吃药").remindAt().toLocalTime())
                .isEqualTo(java.time.LocalTime.of(9, 15));
        assertThat(MemoParser.detect("八点零五分吃药").remindAt().toLocalTime())
                .isEqualTo(java.time.LocalTime.of(8, 5));
        assertThat(MemoParser.detect("九点三刻吃药").remindAt().toLocalTime())
                .isEqualTo(java.time.LocalTime.of(9, 45));
    }

    @Test
    void halfAnHourAndHoursLaterAreTimed() {
        // 回归：“半小时后提醒我吃药”原来解析不出时间，被静默记成“长期备忘”（老人以为设了提醒，其实不会响）
        java.time.LocalDateTime half = MemoParser.detect("半小时后提醒我吃药").remindAt();
        assertThat(half).isNotNull()
                .isAfter(java.time.LocalDateTime.now(DEMO_ZONE).plusMinutes(29))
                .isBefore(java.time.LocalDateTime.now(DEMO_ZONE).plusMinutes(31));

        java.time.LocalDateTime twoHours = MemoParser.detect("两个小时后提醒我量血压").remindAt();
        assertThat(twoHours).isNotNull()
                .isAfter(java.time.LocalDateTime.now(DEMO_ZONE).plusMinutes(119))
                .isBefore(java.time.LocalDateTime.now(DEMO_ZONE).plusMinutes(121));
    }

    @Test
    void vagueOrUnparsableTimeAsksInsteadOfGuessing() {
        assertThat(MemoParser.detect("2分钟提醒我量血压").needsTime()).isTrue();
        assertThat(MemoParser.detect("一会儿提醒我吃药").needsTime()).isTrue();
        // “一个半小时”=90 分钟，算不准就不要猜
        assertThat(MemoParser.detect("一个半小时后吃药").needsTime()).isTrue();
    }

    @Test
    void absoluteDateIsUsedInsteadOfTomorrow() {
        // 回归：“9月15号上午九点去抽血”原来被算成明天（时间错了比不记更糟）
        LocalDate target = LocalDate.now(DEMO_ZONE).plusDays(20);
        String phrase = target.getMonthValue() + "月" + target.getDayOfMonth() + "号上午九点去抽血";

        assertThat(MemoParser.detect(phrase).remindAt()).isEqualTo(target.atTime(9, 0));
    }

    @Test
    void dayOnlyDateResolvesToUpcomingThatDay() {
        LocalDate target = LocalDate.now(DEMO_ZONE).plusDays(5);

        assertThat(MemoParser.detect(target.getDayOfMonth() + "号上午九点复查").remindAt())
                .isEqualTo(target.atTime(9, 0));
    }

    @Test
    void yiDianMeansSlightlyNotOneOclock() {
        // 回归：“差一点忘记吃药了”不能因为“一点”被当成 1 点的提醒
        assertThat(MemoParser.detect("差一点忘记吃药了")).isNull();
        assertThat(MemoParser.detect("一点点药就够了")).isNull();
    }

    @Test
    void dailyRepeatWordSetsRepeatRule() {
        MemoParser.MemoIntent memo = MemoParser.detect("每天八点吃药");

        assertThat(memo.repeatRule()).isEqualTo("DAILY");
        assertThat(memo.remindAt().toLocalTime()).isEqualTo(java.time.LocalTime.of(8, 0));
    }

    @Test
    void eveningDailyIsNormalizedToEveningEight() {
        // “每晚”要按“每天晚上”理解，否则时段折算不上、会被记成早上 8 点
        MemoParser.MemoIntent memo = MemoParser.detect("每晚八点吃药");

        assertThat(memo.repeatRule()).isEqualTo("DAILY");
        assertThat(memo.remindAt().toLocalTime()).isEqualTo(java.time.LocalTime.of(20, 0));
    }

    @Test
    void eveningAlreadySpelledOutIsNotDoubleNormalized() {
        // 回归：“每天晚上八点”不能被补成“每天晚上上八点”，否则时段词失效、晚上八点被存成早上八点
        assertThat(MemoParser.detect("每天晚上八点吃药").remindAt().toLocalTime())
                .isEqualTo(java.time.LocalTime.of(20, 0));
        assertThat(MemoParser.detect("每天早上七点吃药").remindAt().toLocalTime())
                .isEqualTo(java.time.LocalTime.of(7, 0));
    }

    @Test
    void weeklyRepeatAnchorsOnThatWeekday() {
        MemoParser.MemoIntent memo = MemoParser.detect("每周三下午三点量血压");

        assertThat(memo.repeatRule()).isEqualTo("WEEKLY");
        assertThat(memo.remindAt().getDayOfWeek()).isEqualTo(java.time.DayOfWeek.WEDNESDAY);
        assertThat(memo.remindAt().toLocalTime()).isEqualTo(java.time.LocalTime.of(15, 0));
        assertThat(memo.remindAt()).isAfter(java.time.LocalDateTime.now(DEMO_ZONE));
    }

    @Test
    void monthlyRepeatAnchorsOnThatDay() {
        MemoParser.MemoIntent memo = MemoParser.detect("每月15号上午九点复查");

        assertThat(memo.repeatRule()).isEqualTo("MONTHLY");
        assertThat(memo.remindAt().getDayOfMonth()).isEqualTo(15);
        assertThat(memo.remindAt().toLocalTime()).isEqualTo(java.time.LocalTime.of(9, 0));
    }

    @Test
    void everyMonthAndEveryDayWordFormsAreRecognized() {
        assertThat(MemoParser.detect("每个月15号下午三点复查").repeatRule()).isEqualTo("MONTHLY");
        assertThat(MemoParser.detect("每个星期三下午三点量血压").repeatRule()).isEqualTo("WEEKLY");
        assertThat(MemoParser.detect("每日八点吃药").repeatRule()).isEqualTo("DAILY");
    }

    @Test
    void oneOffReminderHasNoRepeatRule() {
        // 回归护栏：没有重复词的一条性提醒不能被当成每天提醒
        assertThat(MemoParser.detect("明天早上八点去抽血").repeatRule()).isNull();
    }

    @Test
    void pastWeekdayWithGeIsStillSeenAsPast() {
        // 回归：“这个星期三”（带“个”）原来认不出“这”，过了这天也不会追问、会直接算到下周
        java.time.LocalDate today = LocalDate.now(DEMO_ZONE);
        int weekday = today.getDayOfWeek().getValue();
        // 取一个本周已经过去的那天（周一说话就取上周日的意思，用“下”的写法跳过）
        if (weekday == 1) {
            assertThat(MemoParser.detect("这个星期三提醒我量血压").needsDay()).isFalse();
        } else {
            String past = "这个星期" + "一二三四五六日".charAt(weekday - 2);
            assertThat(MemoParser.detect(past + "提醒我量血压").needsDay()).isTrue();
        }
        assertThat(MemoParser.detect("下个星期三提醒我量血压").needsDay()).isFalse();
    }

    @Test
    void conditionalTimeHintIsEchoedBack() {
        // “饭后半小时”追问钟点时，回显的应该是这句话本身，不是含糊的“当天”
        assertThat(MemoParser.timeHintOf("饭后半小时提醒我吃药")).isEqualTo("饭后半小时");
    }

    @Test
    void timeAnswerMergeUsesChineseHour() {
        java.time.LocalDateTime at = MemoParser.resolveRemindAt("明早提醒我量血压", "七点");

        assertThat(at).isEqualTo(tomorrow().atTime(7, 0));
    }

    @Test
    void bareWeeklyAndMonthlyAskForTheAnchorDay() {
        // 回归：只说“每周”没说周几时，原来会静默存成永远不到点的重复提醒；
        // 现在要留下 repeatDayGap 让助手追问，且当天先不算出 remindAt
        MemoParser.MemoIntent weekly = MemoParser.detect("每周提醒我量血压");
        assertThat(weekly.repeatRule()).isEqualTo("WEEKLY");
        assertThat(weekly.repeatDayGap()).isEqualTo("WEEKLY");
        assertThat(weekly.remindAt()).isNull();

        MemoParser.MemoIntent monthly = MemoParser.detect("每月提醒我去医院开药");
        assertThat(monthly.repeatRule()).isEqualTo("MONTHLY");
        assertThat(monthly.repeatDayGap()).isEqualTo("MONTHLY");
        assertThat(monthly.remindAt()).isNull();
    }

    @Test
    void weeklyAndMonthlyWithAnchorDayHaveNoGap() {
        assertThat(MemoParser.detect("每周三下午三点量血压").repeatDayGap()).isNull();
        assertThat(MemoParser.detect("每个星期三下午三点量血压").repeatDayGap()).isNull();
        assertThat(MemoParser.detect("每月15号上午九点复查").repeatDayGap()).isNull();
        assertThat(MemoParser.detect("每个月15号下午三点复查").repeatDayGap()).isNull();
    }

    @Test
    void anchorDayAnswerResolvesToUpcomingWeekdayOrDayOfMonth() {
        LocalDate today = LocalDate.now(DEMO_ZONE);

        LocalDate wednesday = MemoParser.resolveRepeatAnchor("WEEKLY", "每周三");
        assertThat(wednesday.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.WEDNESDAY);
        assertThat(wednesday).isAfterOrEqualTo(today);

        LocalDate fifteenth = MemoParser.resolveRepeatAnchor("MONTHLY", "每月15号");
        assertThat(fifteenth.getDayOfMonth()).isEqualTo(15);
        assertThat(fifteenth).isAfterOrEqualTo(today);

        // 听不出哪一天时要返回 null，让助手再问一遍，不能拿今天顶上
        assertThat(MemoParser.resolveRepeatAnchor("WEEKLY", "嗯嗯")).isNull();
        assertThat(MemoParser.resolveRepeatAnchor("MONTHLY", "每周三")).isNull();
    }

    @Test
    void stripScheduleLeavesOnlyTheThingItself() {
        // 改提醒时间时用：正文里的“每月/每天早上八点”要摘掉，时间只由“提醒”那一行负责
        assertThat(MemoParser.stripSchedule("每月提醒我量血压")).isEqualTo("量血压");
        assertThat(MemoParser.stripSchedule("每天早上八点量血压")).isEqualTo("量血压");
        assertThat(MemoParser.stripSchedule("明天早上八点提醒我吃药")).isEqualTo("吃药");
        assertThat(MemoParser.stripSchedule("每天八点吃药")).isEqualTo("吃药");
        assertThat(MemoParser.stripSchedule("9月15号上午九点去抽血")).isEqualTo("去抽血");
        assertThat(MemoParser.stripSchedule("每周三下午三点量血压")).isEqualTo("量血压");
    }

    @Test
    void stripScheduleKeepsConditionsAndRealContent() {
        // “饭后/空腹/睡前”是吃药的讲究，不是时间，摘掉就把事项说没了
        assertThat(MemoParser.stripSchedule("饭后半小时提醒我吃药")).isEqualTo("饭后半小时提醒我吃药");
        assertThat(MemoParser.stripSchedule("睡前提醒我吃药")).isEqualTo("睡前提醒我吃药");
        assertThat(MemoParser.stripSchedule("明天早上八点去抽血，记得空腹")).isEqualTo("去抽血，记得空腹");
    }

    @Test
    void stripScheduleNeverBlanksTheMemo() {
        assertThat(MemoParser.stripSchedule("明天")).isEqualTo("明天");
        assertThat(MemoParser.stripSchedule("买2号电池")).isNotBlank();
    }

    @Test
    void repeatRuleInReadsTheRuleFromAnEditSentence() {
        // “把这条提醒改成每周三下午三点”：改期句里也要能认出重复规则
        assertThat(MemoParser.repeatRuleIn("改成每周三下午三点")).isEqualTo("WEEKLY");
        assertThat(MemoParser.repeatRuleIn("每个月1号")).isEqualTo("MONTHLY");
        assertThat(MemoParser.repeatRuleIn("明天早上八点")).isNull();
    }
}
