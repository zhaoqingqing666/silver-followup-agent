package com.team.silveragent;

import com.team.silveragent.application.memo.MemoParser;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

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

    /** 没有业务时钟的调用方（这个测试类）用的锚点，与 MemoParser 的兜底时区同一口径。 */
    private static LocalDate today() {
        return LocalDate.now(DEMO_ZONE);
    }

    private static java.time.LocalDateTime now() {
        return java.time.LocalDateTime.now(DEMO_ZONE);
    }

    @Test
    void chineseHourSevenParsesToSevenNotMorningFallback() {
        MemoParser.MemoIntent memo = MemoParser.detect("明天早上七点需要吃药");

        assertThat(memo).isNotNull();
        assertThat(memo.remindAts().get(0)).isEqualTo(tomorrow().atTime(7, 0));
    }

    @Test
    void chineseHourWithHalfPastParsesToTime() {
        MemoParser.MemoIntent memo = MemoParser.detect("下午三点半要去复查");

        assertThat(memo).isNotNull();
        assertThat(memo.remindAts().get(0).toLocalTime()).isEqualTo(java.time.LocalTime.of(15, 30));
    }

    @Test
    void eveningSevenOclockIsNineteenHundred() {
        MemoParser.MemoIntent memo = MemoParser.detect("晚上七点吃药");

        assertThat(memo).isNotNull();
        assertThat(memo.remindAts().get(0).toLocalTime()).isEqualTo(java.time.LocalTime.of(19, 0));
    }

    @Test
    void chineseTwoDigitMinutesParsed() {
        MemoParser.MemoIntent memo = MemoParser.detect("明天早上七点二十五吃药");

        assertThat(memo).isNotNull();
        assertThat(memo.remindAts().get(0)).isEqualTo(tomorrow().atTime(7, 25));
    }

    @Test
    void twelveThirtyParsed() {
        MemoParser.MemoIntent memo = MemoParser.detect("明天十二点半吃药");

        assertThat(memo).isNotNull();
        assertThat(memo.remindAts().get(0)).isEqualTo(tomorrow().atTime(12, 30));
    }

    @Test
    void arabicClockStillParses() {
        MemoParser.MemoIntent memo = MemoParser.detect("明天下午3点去抽血");

        assertThat(memo).isNotNull();
        assertThat(memo.remindAts().get(0)).isEqualTo(tomorrow().atTime(15, 0));
    }

    @Test
    void chineseNumeralMinutesLaterIsTimed() {
        // 回归：说“一分钟之后需要吃药”必须算作提到时间（原来是只认阿拉伯数字的“1分钟后”，识别不到、整条不记）
        MemoParser.MemoIntent memo = MemoParser.detect("我一分钟之后需要吃药");

        assertThat(memo).isNotNull();
        assertThat(memo.explicit()).isFalse();
        assertThat(memo.remindAts().get(0)).isNotNull()
                .isAfter(java.time.LocalDateTime.now(DEMO_ZONE).plusSeconds(30))
                .isBefore(java.time.LocalDateTime.now(DEMO_ZONE).plusMinutes(2));
    }

    @Test
    void chineseTenMinutesLaterIsTimed() {
        MemoParser.MemoIntent memo = MemoParser.detect("十分钟以后要吃药");

        assertThat(memo).isNotNull();
        assertThat(memo.remindAts().get(0)).isNotNull()
                .isAfter(java.time.LocalDateTime.now(DEMO_ZONE).plusMinutes(9))
                .isBefore(java.time.LocalDateTime.now(DEMO_ZONE).plusMinutes(11));
    }

    @Test
    void arabicMinutesAfterStillParses() {
        MemoParser.MemoIntent memo = MemoParser.detect("2分钟后提醒我量血压");

        assertThat(memo).isNotNull();
        assertThat(memo.remindAts().get(0)).isNotNull()
                .isAfter(java.time.LocalDateTime.now(DEMO_ZONE).plusSeconds(60))
                .isBefore(java.time.LocalDateTime.now(DEMO_ZONE).plusMinutes(3));
    }

    @Test
    void chineseHourWithoutPeriodOrDayWordIsNotDropped() {
        // 回归：老人只说“八点吃药”（没有“早上/明天”这类词）原来整条被丢弃
        MemoParser.MemoIntent memo = MemoParser.detect("八点吃药");

        assertThat(memo).isNotNull();
        assertThat(memo.remindAts().get(0).toLocalTime()).isEqualTo(java.time.LocalTime.of(8, 0));
        assertThat(memo.remindAts().get(0)).isAfter(java.time.LocalDateTime.now(DEMO_ZONE));
    }

    @Test
    void chineseQuarterAndLeadingZeroMinutesParsed() {
        assertThat(MemoParser.detect("九点一刻吃药").remindAts().get(0).toLocalTime())
                .isEqualTo(java.time.LocalTime.of(9, 15));
        assertThat(MemoParser.detect("八点零五分吃药").remindAts().get(0).toLocalTime())
                .isEqualTo(java.time.LocalTime.of(8, 5));
        assertThat(MemoParser.detect("九点三刻吃药").remindAts().get(0).toLocalTime())
                .isEqualTo(java.time.LocalTime.of(9, 45));
    }

    /**
     * 两字中文钟点（十三…二十四）不能读成最后一个字。
     *
     * <p>回归：原来“几”这一支只写到 {@code 十[一二]?}，正则于是在“二十三点”里退到“三点”上匹配——
     * 老人说晚上 11 点，记下的是凌晨 3 点，他到点听不到、别的时候被吵醒。
     */
    @Test
    void twoCharacterChineseHoursAreNotReadAsTheirLastDigit() {
        java.time.LocalDateTime now = java.time.LocalDateTime.of(2026, 9, 14, 10, 0);

        assertThat(MemoParser.detect("提醒我明天二十三点吃药", now).remindAts())
                .containsExactly(java.time.LocalDateTime.of(2026, 9, 15, 23, 0));
        assertThat(MemoParser.detect("提醒我明天二十二点半吃药", now).remindAts())
                .containsExactly(java.time.LocalDateTime.of(2026, 9, 15, 22, 30));
        assertThat(MemoParser.detect("提醒我明天十三点吃药", now).remindAts())
                .containsExactly(java.time.LocalDateTime.of(2026, 9, 15, 13, 0));
        assertThat(MemoParser.detect("提醒我明天十八点吃药", now).remindAts())
                .containsExactly(java.time.LocalDateTime.of(2026, 9, 15, 18, 0));
        // 一位数/“两”这些老写法不能被顺手改坏
        assertThat(MemoParser.detect("提醒我明天三点吃药", now).remindAts())
                .containsExactly(java.time.LocalDateTime.of(2026, 9, 15, 3, 0));
        assertThat(MemoParser.detect("提醒我明天两点吃药", now).remindAts())
                .containsExactly(java.time.LocalDateTime.of(2026, 9, 15, 2, 0));
    }

    /**
     * 二十四点是当天零点；说不通的钟点（二十五点）不猜，交给追问。
     *
     * <p>原来一律往 23 点夹：二十四点被记成 23 点、二十五点也被记成 23 点，都是不声不响差一截。
     */
    @Test
    void twentyFourHoursIsMidnightAndAnImpossibleHourAsksAgain() {
        java.time.LocalDateTime now = java.time.LocalDateTime.of(2026, 9, 14, 10, 0);

        assertThat(MemoParser.detect("提醒我明天二十四点吃药", now).remindAts())
                .containsExactly(java.time.LocalDateTime.of(2026, 9, 15, 0, 0));
        MemoParser.MemoIntent impossible = MemoParser.detect("提醒我明天二十五点吃药", now);
        assertThat(impossible.remindAts()).isEmpty();
        assertThat(impossible.needsTime()).isTrue();
    }

    /**
     * 深夜那一头也得说得出“零点”：老人说“凌晨零点吃药”，原来一个字都认不出，只会问“几点”。
     *
     * <p>零点与二十四点指的是同一个时刻，判据要一致（见 {@code periodHour}）；差别在<b>分钟要留下</b>——
     * “零点三十分”是 00:30，不能像二十四点那样整个折成 00:00。时段词在这条路上不起作用：
     * “凌晨零点”“晚上零点”都是 00:00，没有“下午零点”这种东西。
     */
    @Test
    void midnightIsParsedAsMidnight() {
        java.time.LocalDateTime now = java.time.LocalDateTime.of(2026, 9, 14, 10, 0);

        assertThat(MemoParser.detect("提醒我明天零点吃药", now).remindAts())
                .containsExactly(java.time.LocalDateTime.of(2026, 9, 15, 0, 0));
        assertThat(MemoParser.detect("提醒我明天凌晨零点吃药", now).remindAts())
                .containsExactly(java.time.LocalDateTime.of(2026, 9, 15, 0, 0));
        assertThat(MemoParser.detect("提醒我明天晚上零点吃药", now).remindAts())
                .containsExactly(java.time.LocalDateTime.of(2026, 9, 15, 0, 0));
        assertThat(MemoParser.detect("提醒我明天零点三十分吃药", now).remindAts())
                .containsExactly(java.time.LocalDateTime.of(2026, 9, 15, 0, 30));
        // 十二小时制的“十二点”照旧：中午十二点是 12:00，不是零点
        assertThat(MemoParser.detect("提醒我明天中午十二点吃药", now).remindAts())
                .containsExactly(java.time.LocalDateTime.of(2026, 9, 15, 12, 0));
    }

    @Test
    void halfAnHourAndHoursLaterAreTimed() {
        // 回归：“半小时后提醒我吃药”原来解析不出时间，被静默记成“长期备忘”（老人以为设了提醒，其实不会响）
        java.time.LocalDateTime half = MemoParser.detect("半小时后提醒我吃药").remindAts().get(0);
        assertThat(half).isNotNull()
                .isAfter(java.time.LocalDateTime.now(DEMO_ZONE).plusMinutes(29))
                .isBefore(java.time.LocalDateTime.now(DEMO_ZONE).plusMinutes(31));

        java.time.LocalDateTime twoHours = MemoParser.detect("两个小时后提醒我量血压").remindAts().get(0);
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

        assertThat(MemoParser.detect(phrase).remindAts().get(0)).isEqualTo(target.atTime(9, 0));
    }

    @Test
    void dayOnlyDateResolvesToUpcomingThatDay() {
        LocalDate target = LocalDate.now(DEMO_ZONE).plusDays(5);

        assertThat(MemoParser.detect(target.getDayOfMonth() + "号上午九点复查").remindAts().get(0))
                .isEqualTo(target.atTime(9, 0));
    }

    /**
     * 中文月日要跟阿拉伯数字一样认得出。
     *
     * <p>回归：“九月十五号”整句认不出时间——既没算出日子也没问钟点，老人还被他听不懂的话问一句
     * “还差具体几点”，而他那句里明明说了哪天。只认 {@code [0-9]} 的那几张表都得一起放宽，
     * 只宽一张就会出现“听懂了日期、却被回问哪天”。
     */
    @Test
    void chineseMonthAndDayAreRecognized() {
        LocalDate anchor = LocalDate.of(2026, 9, 14);

        assertThat(MemoParser.resolveDays("九月十五号吃药", anchor)).containsExactly(LocalDate.of(2026, 9, 15));
        assertThat(MemoParser.resolveDays("十月一号吃药", anchor)).containsExactly(LocalDate.of(2026, 10, 1));
        assertThat(MemoParser.resolveDays("十二月三十一号吃药", anchor)).containsExactly(LocalDate.of(2026, 12, 31));
        // 只给号数：今天以后就是本月那天，已经过去就顺延下个月
        assertThat(MemoParser.resolveDays("二十三号吃药", anchor)).containsExactly(LocalDate.of(2026, 9, 23));
        assertThat(MemoParser.resolveDays("五号吃药", anchor)).containsExactly(LocalDate.of(2026, 10, 5));
        assertThat(MemoParser.resolveDays("下个月五号吃药", anchor)).containsExactly(LocalDate.of(2026, 10, 5));
        // 换个说法必须听到同一天：口径不同就等于“换句话就听不懂”
        assertThat(MemoParser.resolveDays("九月十五号吃药", anchor))
                .isEqualTo(MemoParser.resolveDays("9月15号吃药", anchor));
    }

    @Test
    void chineseMonthAndDaySetsTheReminderDateInsteadOfTomorrow() {
        java.time.LocalDateTime now = java.time.LocalDateTime.of(2026, 9, 14, 10, 0);

        assertThat(MemoParser.detect("提醒我九月二十号上午八点吃药", now).remindAts())
                .containsExactly(java.time.LocalDateTime.of(2026, 9, 20, 8, 0));
        // 只说了日子没说钟点：先问几点，不默默记成“长期备忘”（那是一条永远不响的提醒）
        MemoParser.MemoIntent memo = MemoParser.detect("提醒我九月二十号吃药", now);
        assertThat(memo.remindAts()).isEmpty();
        assertThat(memo.needsTime()).isTrue();
    }

    /**
     * 「九月十五」省掉“号/日”也算日期：月份写明了，就不存在“这是十五号还是十五分钟”的歧义。
     *
     * <p>为什么值得认：老人说「九月十五」是完整的说法，答一句“您说的是哪天”等于没听懂他说的话。
     * 而光说「十五」仍然要带号/日——那串数字没有月份兜底，本来就说不清是几号。
     *
     * <p>所以判据不是“数字后面有没有号”，而是<b>月份有没有写明</b>。省掉号的那一支还得自证不是别的意思：
     * “十月一天都没歇”里的“十月一”不是十月一号（见 {@code DAY_SUFFIX_OR_NONE}）。
     */
    @Test
    void aMonthWithANumberIsADateEvenWithoutTheDaySuffix() {
        LocalDate anchor = LocalDate.of(2026, 9, 14);
        java.time.LocalDateTime now = java.time.LocalDateTime.of(2026, 9, 14, 10, 0);

        assertThat(MemoParser.resolveDays("九月十五吃药", anchor)).containsExactly(LocalDate.of(2026, 9, 15));
        assertThat(MemoParser.resolveDays("十月一吃药", anchor)).containsExactly(LocalDate.of(2026, 10, 1));
        assertThat(MemoParser.resolveDays("十二月三十一吃药", anchor)).containsExactly(LocalDate.of(2026, 12, 31));
        assertThat(MemoParser.resolveDays("9月15吃药", anchor)).containsExactly(LocalDate.of(2026, 9, 15));
        // 省与不省必须落在同一天：换个写法就听不懂，等于逼着老人改口
        assertThat(MemoParser.resolveDays("九月十五吃药", anchor))
                .isEqualTo(MemoParser.resolveDays("九月十五号吃药", anchor));
        // 日子 + 钟点整句也要算得出时刻，而不是只认得日期、又问一遍几点
        assertThat(MemoParser.detect("提醒我九月十五早上八点吃药", now).remindAts())
                .containsExactly(java.time.LocalDateTime.of(2026, 9, 15, 8, 0));
        // 光说“十五”没有月份兜底：还是得问清哪天
        assertThat(MemoParser.resolveDays("十五吃药", anchor)).isEmpty();
        // 频次与“没歇着”的话不能被当成日期
        assertThat(MemoParser.resolveDays("十月一天都没歇", anchor)).isEmpty();
        assertThat(MemoParser.resolveDays("一日三次吃药", anchor)).isEmpty();
    }

    /** 服药频次不是日期：“一日三次”“1日3次”说的是一天吃几次，记成 1 号的提醒是另一回事。 */
    @Test
    void aFrequencyPhraseIsNotADate() {
        LocalDate anchor = LocalDate.of(2026, 9, 14);

        assertThat(MemoParser.resolveDays("一日三次吃药", anchor)).isEmpty();
        assertThat(MemoParser.resolveDays("1日3次吃药", anchor)).isEmpty();
        assertThat(MemoParser.resolveDays("一日三餐要清淡", anchor)).isEmpty();
    }

    @Test
    void yiDianMeansSlightlyNotOneOclock() {
        // 回归：“差一点忘记吃药了”不能因为“一点”被当成 1 点的提醒
        assertThat(MemoParser.detect("差一点忘记吃药了")).isNull();
        assertThat(MemoParser.detect("一点点药就够了")).isNull();
    }

    /**
     * 回归：拦「有一点高」「差一点迟到」的那道守卫只管「一」这一支。
     *
     * <p>它原来加在整组数字前面，于是「还<b>有九点</b>量血压」里的九点也一起被吃掉。后果不在这一句上，
     * 而在下游：{@code severalMomentsInOneSentence} 数出来只剩一个钟点，两件事被当成一件事写下来，
     * 第二个到点永远不响——这正是用户报的「说了两个时间只记了一个」。
     */
    @Test
    void aRealClockRightAfterYouIsStillAClock() {
        assertThat(MemoParser.detect("提醒我明天八点吃药，还有九点量血压").remindAts().get(0).toLocalTime())
                .isEqualTo(java.time.LocalTime.of(8, 0));
        assertThat(MemoParser.severalClocksInOneSentence("提醒我明天八点吃药，还有九点量血压")).isTrue();
    }

    @Test
    void dailyRepeatWordSetsRepeatRule() {
        MemoParser.MemoIntent memo = MemoParser.detect("每天八点吃药");

        assertThat(memo.repeatRule()).isEqualTo("DAILY");
        assertThat(memo.remindAts().get(0).toLocalTime()).isEqualTo(java.time.LocalTime.of(8, 0));
    }

    @Test
    void eveningDailyIsNormalizedToEveningEight() {
        // “每晚”要按“每天晚上”理解，否则时段折算不上、会被记成早上 8 点
        MemoParser.MemoIntent memo = MemoParser.detect("每晚八点吃药");

        assertThat(memo.repeatRule()).isEqualTo("DAILY");
        assertThat(memo.remindAts().get(0).toLocalTime()).isEqualTo(java.time.LocalTime.of(20, 0));
    }

    @Test
    void eveningAlreadySpelledOutIsNotDoubleNormalized() {
        // 回归：“每天晚上八点”不能被补成“每天晚上上八点”，否则时段词失效、晚上八点被存成早上八点
        assertThat(MemoParser.detect("每天晚上八点吃药").remindAts().get(0).toLocalTime())
                .isEqualTo(java.time.LocalTime.of(20, 0));
        assertThat(MemoParser.detect("每天早上七点吃药").remindAts().get(0).toLocalTime())
                .isEqualTo(java.time.LocalTime.of(7, 0));
    }

    @Test
    void weeklyRepeatAnchorsOnThatWeekday() {
        MemoParser.MemoIntent memo = MemoParser.detect("每周三下午三点量血压");

        assertThat(memo.repeatRule()).isEqualTo("WEEKLY");
        assertThat(memo.remindAts().get(0).getDayOfWeek()).isEqualTo(java.time.DayOfWeek.WEDNESDAY);
        assertThat(memo.remindAts().get(0).toLocalTime()).isEqualTo(java.time.LocalTime.of(15, 0));
        assertThat(memo.remindAts().get(0)).isAfter(java.time.LocalDateTime.now(DEMO_ZONE));
    }

    @Test
    void monthlyRepeatAnchorsOnThatDay() {
        MemoParser.MemoIntent memo = MemoParser.detect("每月15号上午九点复查");

        assertThat(memo.repeatRule()).isEqualTo("MONTHLY");
        assertThat(memo.remindAts().get(0).getDayOfMonth()).isEqualTo(15);
        assertThat(memo.remindAts().get(0).toLocalTime()).isEqualTo(java.time.LocalTime.of(9, 0));
    }

    /** “每月五号”说的是五号：不能被当成“每月”没跟几号，反过来问老人每月几号。 */
    @Test
    void chineseMonthlyAnchorIsNotAskedAgainAsIfItWereMissing() {
        MemoParser.MemoIntent memo = MemoParser.detect("每月五号早上八点吃药");

        assertThat(memo.repeatRule()).isEqualTo("MONTHLY");
        assertThat(memo.repeatDayGap()).isNull();
        assertThat(memo.remindAts().get(0).getDayOfMonth()).isEqualTo(5);
        assertThat(memo.remindAts().get(0).toLocalTime()).isEqualTo(java.time.LocalTime.of(8, 0));
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

    /** 只说到日期、还差钟点时，回显的要是那句日期：回一句“当天”等于把听懂的日子又丢了。 */
    @Test
    void absoluteDateHintIsEchoedBack() {
        assertThat(MemoParser.timeHintOf("九月五号吃药")).isEqualTo("九月五号");
        assertThat(MemoParser.timeHintOf("15号吃药")).isEqualTo("15号");
    }

    /**
     * 传进去的业务时间锚点说了算，不是系统时钟。
     *
     * <p>生产路径（FollowupAgentService）每次都把 BusinessClock 的 now/today 传进来，所以配置改
     * {@code business.time.zone} 备忘推算跟着一起改。这里钉一个跟真实「今天」无关的锚点：如果哪天
     * 又退回读系统时钟，这几个断言立刻变红。
     */
    @Test
    void explicitAnchorDecidesTheDayInsteadOfTheSystemClock() {
        // 锚在 2026-09-14（周一）：这一周的周三 09-16 还没到。
        java.time.LocalDateTime monday = java.time.LocalDateTime.of(2026, 9, 14, 8, 0);
        assertThat(MemoParser.detect("这个星期三提醒我量血压", monday).needsDay()).isFalse();
        assertThat(MemoParser.resolveDays("明天", monday.toLocalDate()))
                .containsExactly(java.time.LocalDate.of(2026, 9, 15));
        assertThat(MemoParser.resolveRepeatAnchor("WEEKLY", "每周三", monday.toLocalDate()))
                .isEqualTo(java.time.LocalDate.of(2026, 9, 16));

        // 锚在 2026-09-17（周四）：这一周的周三已经过去了，要追问是哪一天。
        java.time.LocalDateTime thursday = java.time.LocalDateTime.of(2026, 9, 17, 8, 0);
        assertThat(MemoParser.detect("这个星期三提醒我量血压", thursday).needsDay()).isTrue();
        assertThat(MemoParser.pastWeekdays("这个星期三提醒我量血压", thursday.toLocalDate()))
                .containsExactly(java.time.LocalDate.of(2026, 9, 16));
        // 锚点当天 09:00 说“八点吃药”：已经过点，顺延到 09-18（不是系统时钟里的明天）。
        assertThat(MemoParser.resolveRemindAt("八点吃药", "八点", List.of(),
                java.time.LocalDateTime.of(2026, 9, 17, 9, 0)))
                .containsExactly(java.time.LocalDateTime.of(2026, 9, 18, 8, 0));
    }

    @Test
    void timeAnswerMergeUsesChineseHour() {
        java.time.LocalDateTime at = MemoParser.resolveRemindAt("明早提醒我量血压", "七点").get(0);

        assertThat(at).isEqualTo(tomorrow().atTime(7, 0));
    }

    @Test
    void bareWeeklyAndMonthlyAskForTheAnchorDay() {
        // 回归：只说“每周”没说周几时，原来会静默存成永远不到点的重复提醒；
        // 现在要留下 repeatDayGap 让助手追问，且当天先不算出 remindAt
        MemoParser.MemoIntent weekly = MemoParser.detect("每周提醒我量血压");
        assertThat(weekly.repeatRule()).isEqualTo("WEEKLY");
        assertThat(weekly.repeatDayGap()).isEqualTo("WEEKLY");
        assertThat(weekly.remindAts()).isEmpty();

        MemoParser.MemoIntent monthly = MemoParser.detect("每月提醒我去医院开药");
        assertThat(monthly.repeatRule()).isEqualTo("MONTHLY");
        assertThat(monthly.repeatDayGap()).isEqualTo("MONTHLY");
        assertThat(monthly.remindAts()).isEmpty();
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

    /** 从今天起算的下一个周一（今天就是周一时算 7 天后），与 dateFor 的“下周”口径一致。 */
    private static LocalDate nextMonday() {
        return nearestWeekday(1);
    }

    /**
     * 裸“周X”（前面没有这/本/下）落在最近的那一天；今天正好是这天时顺延一周（今天 08:00 已经过了）。
     *
     * <p>与 {@code MemoParser.dateFor} 对裸星期几的口径一致，用来把断言写成不依赖“今天星期几”的。
     */
    private static LocalDate nearestWeekday(int dayOfWeek) {
        LocalDate today = LocalDate.now(DEMO_ZONE);
        int delta = (dayOfWeek - today.getDayOfWeek().getValue() + 7) % 7;
        return today.plusDays(delta == 0 ? 7 : delta);
    }

    @Test
    void weekScopeWithoutAnyWeekdayAsksForTheDay() {
        // 回归：只说“这周/下周”没说哪天时，原来不问日期，直接按“今天过点就顺延明天”兜底，
        // 周五说“我这周要吃药”会被悄悄记成周六
        MemoParser.MemoIntent week = MemoParser.detect("我这周要吃药");
        assertThat(week.needsDay()).isTrue();
        assertThat(week.remindAts()).isEmpty();

        // 追问给的日子要一点就能解析出来，不能是裸“下周”——点了照样“没听清是哪一天”
        assertThat(MemoParser.resolveDays(MemoParser.weekDaySuggestion("我这周要吃药", today()))).hasSize(1);
        assertThat(MemoParser.resolveDays(MemoParser.weekDaySuggestion("我下周要吃药", today()))).hasSize(1);
    }

    @Test
    void severalWeekdaysInARowAllResolveToTheirOwnDay() {
        // 原来只 find() 一次，第二个以后的星期几被静默丢掉：“每周一三五”（隔天吃药）塌成每周一次
        MemoParser.MemoIntent weekdays = MemoParser.detect("每周一三五早上八点吃药");
        assertThat(weekdays.repeatRule()).isEqualTo("WEEKLY");
        // 每个星期几各自落在自己最近的那一天，再按时间先后排（所以周中说的“每周一”排在这一周的周三、周五之后）。
        // 这条断言原来写的是 nextMonday()+2/+4：那只在“今天正好是周日”时成立，其余日子都算错，这里改成跟
        // 星期几走、与今天星期几无关。
        assertThat(weekdays.remindAts()).containsExactlyElementsOf(
                java.util.stream.Stream.of(nearestWeekday(1), nearestWeekday(3), nearestWeekday(5))
                        .map(day -> day.atTime(8, 0)).sorted().toList());

        MemoParser.MemoIntent streak = MemoParser.detect("下周周一周二周三早上八点吃药");
        assertThat(streak.remindAts()).containsExactly(
                nextMonday().atTime(8, 0), nextMonday().plusDays(1).atTime(8, 0), nextMonday().plusDays(2).atTime(8, 0));

        // “和/、”连接的两个星期几也算一串（“下周一和周三”原来只存周一，正文还漏出“和吃药”）
        MemoParser.MemoIntent joined = MemoParser.detect("下周一和周三早上八点吃药");
        assertThat(joined.remindAts()).containsExactly(
                nextMonday().atTime(8, 0), nextMonday().plusDays(2).atTime(8, 0));
    }

    @Test
    void pastWeekdayGroupAsksInsteadOfBeingQuietlyMovedToNextWeek() {
        // “这周周一周二周三”在周五说是已经过去的三天：不能静默算到下周，也不能只挑一天来问
        int weekday = LocalDate.now(DEMO_ZONE).getDayOfWeek().getValue();
        if (weekday == 1) return; // 周一那天“这周X”都还没过去，这条用例不适用

        MemoParser.MemoIntent memo = MemoParser.detect("提醒我这周周一周二周三早上八点吃药");
        assertThat(memo.needsDay()).isTrue();
        assertThat(memo.remindAts()).isEmpty();

        // 回显的是老人说的过去那几天（本周的周一），不是“下一次”的下周一
        assertThat(MemoParser.pastWeekdays("这周周一周二周三")).allSatisfy(
                day -> assertThat(day).isBefore(LocalDate.now(DEMO_ZONE)));

        // 快捷回复要把整串挪到下周，只给“下周一”的话周二周三就没了。
        // 走的是老人点按钮那条路：钟点沿用原话里的八点，日期取答句里的整组
        String raw = "提醒我这周周一周二周三早上八点吃药";
        String suggest = MemoParser.weekDaySuggestion(raw, today());
        assertThat(suggest).isEqualTo("下周周一周二周三");
        assertThat(MemoParser.resolveRemindAt(raw, suggest, List.of(), now())).containsExactly(
                nextMonday().atTime(8, 0), nextMonday().plusDays(1).atTime(8, 0), nextMonday().plusDays(2).atTime(8, 0));
    }

    @Test
    void textForDayRewritesTheWeekdayRunIntoThatDaysOwnSentence() {
        // 拆条时每条正文只说自己那天，否则多天原句喂进 stripSchedule 会漏出“三五吃药”“和吃药”
        assertThat(MemoParser.textForDay("每周一三五早上八点吃药", nextMonday(), today()))
                .isEqualTo("每周一早上八点吃药");
        assertThat(MemoParser.stripSchedule(MemoParser.textForDay("每周一三五早上八点吃药", nextMonday(), today())))
                .isEqualTo("吃药");
        assertThat(MemoParser.stripSchedule(
                MemoParser.textForDay("下周一和周三早上八点吃药", nextMonday().plusDays(2), today())))
                .isEqualTo("吃药");
        // 句中本来没有星期串时原样返回
        assertThat(MemoParser.textForDay("明天早上八点吃药", nextMonday(), today()))
                .isEqualTo("明天早上八点吃药");
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

    /**
     * 一句里塞了两个时间点的句子要认出来。
     *
     * <p>一条备忘只带一个 remind_at，解析器也只认一套“钟点 + 日子”（见 {@code detect}），
     * 于是“周一八点吃药，周三下午三点复查”会被记成<b>两件事共用一个时间</b>，
     * “9月15号和9月20号”还会再丢掉一天。判出来的目的是<b>不猜、不落库、反问</b>，不是记下来。
     */
    @Test
    void sentencesWithTwoMomentsAreFlagged() {
        // 两个日子各带各的钟点
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我周一早上八点吃药，周三下午三点复查")).isTrue();
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我明天早上八点吃药，后天下午三点复查")).isTrue();
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我下周一早上八点吃药，下周三下午三点复查")).isTrue();
        // 两个绝对日期：MONTH_DAY 与更宽的 DAY_ONLY 会同时命中，只能算两处，不能算三处
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我9月15号和9月20号早上八点吃药")).isTrue();
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我15号和20号早上八点吃药")).isTrue();
        // 中文写法的两个日期一样要数出来，否则又变成“说两件事只办一件”
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我九月十五号和九月二十号早上八点吃药")).isTrue();
        // 省了号/日的中文日期一样要数出来（两个都是「九月X」，不是「九月十五」+「二十」）
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我九月十五和九月二十早上八点吃药")).isTrue();
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我十五号和二十号早上八点吃药")).isTrue();
        // 同一件事的两个钟点（“早八点和晚八点”）也是两个时间点
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我每天早八点和晚八点吃药")).isTrue();
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我每周一三五早上八点和晚上八点吃药")).isTrue();
        // 相对分钟
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我2分钟后吃药，10分钟后量血压")).isTrue();
        // 第二个钟点紧跟在“有”后面（“还有九点”）：拦“有一点高”的守卫不能把它一起吃掉
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我明天八点吃药，还有九点量血压")).isTrue();
        // 只说了时段、一个钟点都没说：解析器只取其中一个，另外两顿静默丢——追问回显“您说的是‘晚上’”，
        // 早上和中午那两顿就没了，老人还以为三顿都交代过了
        assertThat(MemoParser.severalMomentsInOneSentence("早上提醒我吃药，中午提醒我吃药，晚上提醒我吃药")).isTrue();
        assertThat(MemoParser.severalMomentsInOneSentence("早上提醒我吃药，晚上提醒我吃药")).isTrue();
        // 一个钟点 + 一个独立时段也是两处（贴在钟点上的时段词不算独立一处，这条里的“早上”是独立的）
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我晚上八点吃药，早上提醒我吃药")).isTrue();
    }

    /**
     * 正常说法一句一个时间点，必须保持沉默——这个判据是拦路用的，误报会把正常提醒也挡在门外。
     *
     * <p>三种最容易误伤的：一<b>串</b>星期词（“每周一三五”“下周一和周三”指的是同一次提醒）、
     * 一个<b>范围</b>（“三点到五点”是一个时间段，不是两个时间点）、以及“日子 + 钟点”这种
     * 只是把一件事说清楚的说法。
     */
    @Test
    void ordinarySingleMomentSentencesAreNotFlagged() {
        // 一串星期词 = 一件事的多天，不是多件事
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我每周一三五早上八点吃药")).isFalse();
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我下周一和周三早上八点吃药")).isFalse();
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我这周六周日早上八点测血压")).isFalse();
        // 钟点之间的连接符把两处并成一处
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我下午三点到五点去医院")).isFalse();
        // 一个日子 + 一个钟点
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我明天早上八点吃药")).isFalse();
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我9月15号早上八点吃药")).isFalse();
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我12月1号早上八点吃药")).isFalse();
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我每月5号早上八点吃药")).isFalse();
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我九月十五号早上八点吃药")).isFalse();
        // 省了号/日的日期也是一处（月份 + 号数不能被数成两处）
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我九月十五早上八点吃药")).isFalse();
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我12月1早上八点吃药")).isFalse();
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我每月五号早上八点吃药")).isFalse();
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我明天二十三点吃药")).isFalse();
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我10点吃药")).isFalse();
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我早上八点半吃药")).isFalse();
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我半个小时后吃药")).isFalse();
        // 没有钟点的重复说法
        assertThat(MemoParser.severalMomentsInOneSentence("每天提醒我量血压")).isFalse();
        // “这周”是范围词不是某一天：该由 needsDay 去补问，这里不要抢答
        assertThat(MemoParser.severalMomentsInOneSentence("我这周要吃药")).isFalse();
        // 项目里最常走的那句（显式托付），一字不改也要放行
        assertThat(MemoParser.severalMomentsInOneSentence("帮我记着，明天早上8点要空腹抽血，记得提醒我")).isFalse();
        // 一个时段词 + 一个钟点是同一处时间（“早上8点”“晚上八点”），不是两处
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我晚上八点吃药")).isFalse();
        // 只说了时段的单件事：这一句该问“几点”，不是“一件一件说”
        assertThat(MemoParser.severalMomentsInOneSentence("提醒我早上吃药")).isFalse();
    }

    /**
     * 家属端专用：话里对长辈的称呼要摘掉，那是他在叫谁、不是事项。
     *
     * <p>称呼残渣来自解析器认得“提醒我”这个指令、却不认“我妈”这个称呼：留着的话长辈看到的备忘是
     * “妈带身份证”。三种摘法各有各的坑——长的要先命中（“我妈妈”不能只剩个“妈”）、称呼后的“的”
     * 要跟着走（“我妈的药没了”→“药没了”，不是“的药没了”）、摘空了要原样返回。
     */
    @Test
    void caregiverAddressIsStrippedFromTheMemoText() {
        assertThat(MemoParser.stripElderAddress("妈量血压")).isEqualTo("量血压");
        assertThat(MemoParser.stripElderAddress("妈妈带身份证")).isEqualTo("带身份证");
        assertThat(MemoParser.stripElderAddress("我妈妈带身份证")).isEqualTo("带身份证");
        assertThat(MemoParser.stripElderAddress("奶奶量血压")).isEqualTo("量血压");
        assertThat(MemoParser.stripElderAddress("老伴量血压")).isEqualTo("量血压");
        assertThat(MemoParser.stripElderAddress("家里人量血压")).isEqualTo("量血压");
        // 称呼后面紧跟的“的”跟着走（那是“谁的”里的“的”）
        assertThat(MemoParser.stripElderAddress("妈的药没了")).isEqualTo("药没了");
        assertThat(MemoParser.stripElderAddress("我妈妈的体检报告放哪了")).isEqualTo("体检报告放哪了");
        // 识别不出备忘句式时调用方给的是原句：指令词也一起摘
        assertThat(MemoParser.stripElderAddress("提醒姥姥量血压")).isEqualTo("量血压");
        assertThat(MemoParser.stripElderAddress("提醒一下我妈带身份证")).isEqualTo("带身份证");
    }

    /** 摘称呼这件事只许在“话的开头”动一刀，别的地方一个字都不能少。 */
    @Test
    void addressStrippingLeavesOrdinaryTextAlone() {
        // 称呼在句中/句尾：那是内容
        assertThat(MemoParser.stripElderAddress("带妈去医院")).isEqualTo("带妈去医院");
        assertThat(MemoParser.stripElderAddress("给妈妈打电话")).isEqualTo("给妈妈打电话");
        // 老人自己的话：老人不会管自己叫“我妈”，本来也没有称呼可摘
        assertThat(MemoParser.stripElderAddress("量血压")).isEqualTo("量血压");
        assertThat(MemoParser.stripElderAddress("早饭前吃药")).isEqualTo("早饭前吃药");
        // 摘干净了也不能把正文摘空——空正文的备忘比带个称呼难查得多
        assertThat(MemoParser.stripElderAddress("妈")).isEqualTo("妈");
        assertThat(MemoParser.stripElderAddress("提醒")).isEqualTo("提醒");
        assertThat(MemoParser.stripElderAddress("提醒我妈")).isEqualTo("提醒我妈");
        assertThat(MemoParser.stripElderAddress("")).isEmpty();
        assertThat(MemoParser.stripElderAddress(null)).isEmpty();
    }

    /**
     * 补答那条路（老人回答“哪一天/几点”）用的判据：<b>只看钟点</b>。
     *
     * <p>为什么不顺手用上面那条：上面那条会把“下周三，下周五”也判成两处（逗号不算星期串的连接符），
     * 可那正是补答日子时该支持的说法——同一件事的两天，本来就该拆成两条。
     * 误伤正常的多天答复比漏拦更贵，所以这条路单独一个判据，最后三行就是两种判据分家的地方。
     */
    @Test
    void onlyTheClockCountMattersWhenTheAnswerIsJustATime() {
        // 答句里两个钟点：拦（第二个会被静默丢掉，两个日子还会共用第一个钟点）
        assertThat(MemoParser.severalClocksInOneSentence("早上八点吃药，下午三点量血压")).isTrue();
        assertThat(MemoParser.severalClocksInOneSentence("下周三下午三点复查，下周五早上八点吃药")).isTrue();
        assertThat(MemoParser.severalClocksInOneSentence("早上八点和晚上八点")).isTrue();
        assertThat(MemoParser.severalClocksInOneSentence("2分钟后吃药，10分钟后量血压")).isTrue();
        assertThat(MemoParser.severalClocksInOneSentence("下午三点，四点")).isTrue();
        // 一个钟点：放行
        assertThat(MemoParser.severalClocksInOneSentence("早上8点")).isFalse();
        assertThat(MemoParser.severalClocksInOneSentence("下午三点到五点")).isFalse();
        assertThat(MemoParser.severalClocksInOneSentence("不用提醒，只记下")).isFalse();
        // 只说日子、不说钟点：这是要接着问几点的正常答复
        assertThat(MemoParser.severalClocksInOneSentence("每周三")).isFalse();
        // 两步分家：整条判据拦，只看钟点这条不拦（“下周三，下周五”＝同一件事的两天）
        assertThat(MemoParser.severalMomentsInOneSentence("下周三，下周五")).isTrue();
        assertThat(MemoParser.severalClocksInOneSentence("下周三，下周五")).isFalse();
        assertThat(MemoParser.severalClocksInOneSentence("下周三，下周五，下周日晚")).isFalse();
    }
}
