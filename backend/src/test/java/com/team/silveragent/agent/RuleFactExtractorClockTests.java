package com.team.silveragent.agent;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则链路的钟点抽取：阿拉伯数字照旧，中文数字走 {@code MemoParser} 兜底。
 *
 * <p>由来（`docs/proposals/模型链路与规则链路对齐方案.md` 的 C）：老人说「下午三点半」，原来的
 * {@code TIME = (\d{1,2})[:：点时](\d{1,2})?} 只认阿拉伯数字，一个字也抽不出来，只会退化成
 * {@code timePreference=AFTERNOON}；于是下一轮又把「您想上午还是下午」问一遍。
 *
 * <p>口径不另写第二份：中文钟点直接调 {@code MemoParser.clockIn}，与备忘提醒共用一份实现
 * （项目的既有规矩：词表写两遍，迟早只剩一份是对的）。因此备忘那边认得的「一刻 / 三刻 /
 * 零五分」「晚上 / 中午」，这里也一并认得——这是复用带来的既定结果，不是新加的语义。
 */
class RuleFactExtractorClockTests {

    // 这里只调 spokenTime：它不碰目录。传 null 是刻意的——真要跑 extract() 时目录是必需的，
    // 而这一条测的是「话里有没有钟点」，与医院目录无关。
    private final RuleFactExtractor extractor = new RuleFactExtractor(null);

    @Test
    void chineseHourWithHalfPastIsUnderstood() {
        assertThat(extractor.spokenTime("下午三点半")).isEqualTo(LocalTime.of(15, 30));
        assertThat(extractor.spokenTime("上午九点")).isEqualTo(LocalTime.of(9, 0));
    }

    @Test
    void theAfternoonWordCountsEvenWhenItIsNotAdjacentToTheClock() {
        // 演示里的原话就是这样说的：「下午」和「三点半」中间隔着一个「的」。
        // 只看紧挨着钟点的那几个字，会把它算成凌晨 3:30，再按最近号源推荐，就把下午的号推成上午的号。
        assertThat(extractor.spokenTime("我要下午的三点半的")).isEqualTo(LocalTime.of(15, 30));
    }

    @Test
    void arabicClockBehavesExactlyAsBefore() {
        assertThat(extractor.spokenTime("下午3点")).isEqualTo(LocalTime.of(15, 0));
        assertThat(extractor.spokenTime("9:15")).isEqualTo(LocalTime.of(9, 15));
        // 「3点半」与「三点半」必须是同一个答案，否则两种写法会落到不同的号上。
        assertThat(extractor.spokenTime("下午3点半")).isEqualTo(LocalTime.of(15, 30));
    }

    @Test
    void chineseMinutesAndPeriodWordsRideAlongWithTheSharedParser() {
        assertThat(extractor.spokenTime("下午三点一刻")).isEqualTo(LocalTime.of(15, 15));
        assertThat(extractor.spokenTime("上午七点二十五")).isEqualTo(LocalTime.of(7, 25));
        assertThat(extractor.spokenTime("晚上七点")).isEqualTo(LocalTime.of(19, 0));
    }

    @Test
    void slightlyAndALittleAreNotClocks() {
        // MemoParser 的护栏原样继承过来：「差一点」「一点点」里的「点」不是钟点。
        assertThat(extractor.spokenTime("差一点忘记吃药了")).isNull();
        assertThat(extractor.spokenTime("一点点药就够了")).isNull();
    }

    @Test
    void messagesWithoutAClockStayNull() {
        assertThat(extractor.spokenTime("上午的号")).isNull();
        assertThat(extractor.spokenTime("我要挂专家号")).isNull();
        // 读不出合法钟点时不猜：原来返回 null，现在也一样，不能被 MemoParser 的夹取范围悄悄改成 23:00。
        assertThat(extractor.spokenTime("25点")).isNull();
    }
}
