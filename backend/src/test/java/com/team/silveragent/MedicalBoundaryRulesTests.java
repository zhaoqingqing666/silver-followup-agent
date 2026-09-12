package com.team.silveragent;

import com.team.silveragent.agent.MedicalBoundaryRules;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 医疗越界判定。
 *
 * 重点有两个方向：一定要拦住的（漏判会被静默忽略，老人以为得到了答复），
 * 和一定不能误拦的（我们自己的健康记录、备忘、办理话术被当成越界就没法演示了）。
 */
class MedicalBoundaryRulesTests {

    @Test
    void adviceSeekingQuestionsAreCaught() {
        // 四场景里「服务越界」要演的句子
        assertThat(MedicalBoundaryRules.looksLikeMedicalAdvice("我血压有点高，要不要紧？")).isTrue();
        assertThat(MedicalBoundaryRules.looksLikeMedicalAdvice("这个药还能继续吃吗？")).isTrue();
        assertThat(MedicalBoundaryRules.looksLikeMedicalAdvice("阿司匹林一天吃几片？")).isTrue();
        assertThat(MedicalBoundaryRules.looksLikeMedicalAdvice("帮我看看这个化验单")).isTrue();
        assertThat(MedicalBoundaryRules.looksLikeMedicalAdvice("我是不是该住院？")).isTrue();
    }

    @Test
    void mixedSentenceIsNotSilentlySwallowed() {
        // 日期那半句会被正常解析进草稿，越界这半句更要有人管，否则老人以为助手默认了
        assertThat(MedicalBoundaryRules.looksLikeMedicalAdvice("9月18日，我最近头晕是不是血压高了")).isTrue();
        assertThat(MedicalBoundaryRules.looksLikeMedicalAdvice("下周三，这个药量是不是该减半？")).isTrue();
    }

    @Test
    void plainLogisticsIsNotBoundary() {
        assertThat(MedicalBoundaryRules.looksLikeMedicalAdvice("我下周三想去市第一医院心内科复诊")).isFalse();
        assertThat(MedicalBoundaryRules.looksLikeMedicalAdvice("复诊要带什么材料")).isFalse();
        assertThat(MedicalBoundaryRules.looksLikeMedicalAdvice("要不要带医保卡")).isFalse();
        assertThat(MedicalBoundaryRules.looksLikeMedicalAdvice("帮我看看下周的号")).isFalse();
        assertThat(MedicalBoundaryRules.looksLikeMedicalAdvice("那天我要做手术，帮我把复诊改到下周")).isFalse();
    }

    @Test
    void selfRecordedValuesAreOurOwnFeatureNotMedicalAdvice() {
        // 健康记录是 v0.2 的能力：老人查自己量过的数，不能当成问诊拦掉
        assertThat(MedicalBoundaryRules.looksLikeMedicalAdvice("我最近的血压是多少")).isFalse();
        assertThat(MedicalBoundaryRules.looksLikeMedicalAdvice("我的血压怎么样")).isFalse();
        assertThat(MedicalBoundaryRules.looksLikeMedicalAdvice("我的血压正常吗")).isFalse();
        assertThat(MedicalBoundaryRules.looksLikeMedicalAdvice("我的血压是100")).isFalse();
        assertThat(MedicalBoundaryRules.looksLikeMedicalAdvice("血糖6.4")).isFalse();
        // 但“是不是有点高”是在要判断，不是查数
        assertThat(MedicalBoundaryRules.looksLikeMedicalAdvice("我血压是不是有点高")).isTrue();
    }

    @Test
    void memosAndCareStatementsAreNotBoundary() {
        assertThat(MedicalBoundaryRules.looksLikeMedicalAdvice("明早八点提醒我吃药")).isFalse();
        assertThat(MedicalBoundaryRules.looksLikeMedicalAdvice("每周三下午三点量血压")).isFalse();
        assertThat(MedicalBoundaryRules.looksLikeMedicalAdvice("每天提醒我量血压")).isFalse();
        assertThat(MedicalBoundaryRules.looksLikeMedicalAdvice("记一下，我青霉素过敏")).isFalse();
        assertThat(MedicalBoundaryRules.looksLikeMedicalAdvice("把这个月的血压发给女儿")).isFalse();
        assertThat(MedicalBoundaryRules.looksLikeMedicalAdvice("明早要去抽血，记得空腹")).isFalse();
    }

    @Test
    void bodyDiscomfortIsItsOwnDecision() {
        assertThat(MedicalBoundaryRules.bodyDiscomfort("我感觉自己的腿不舒服")).isTrue();
        assertThat(MedicalBoundaryRules.bodyDiscomfort("我腰疼得厉害")).isTrue();
        // 情绪不是身体症状
        assertThat(MedicalBoundaryRules.bodyDiscomfort("我感觉现在心里不舒服")).isFalse();
        assertThat(MedicalBoundaryRules.bodyDiscomfort("今天天气不错")).isFalse();
    }

    @Test
    void blankInputIsNotBoundary() {
        assertThat(MedicalBoundaryRules.looksLikeMedicalAdvice(null)).isFalse();
        assertThat(MedicalBoundaryRules.looksLikeMedicalAdvice("   ")).isFalse();
    }
}
