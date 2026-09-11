package com.team.silveragent.agent;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则兜底通道的多任务拆解与动态约束提取。
 *
 * <p>模型通道关掉（或调用失败）时，用户说的复合需求必须仍然被拆成多项、约束仍然被原话保留，
 * 不能因为走了兜底就退化成"只认一个意图"。
 */
class RuleFactExtractorTests {
    private final RuleFactExtractor extractor = new RuleFactExtractor();
    private final AgentContext context = new AgentContext("ASK_HOSPITAL", "", LocalDate.of(2026, 9, 11), List.of());

    @Test
    void compoundSentenceSplitsIntoAllSixTasks() {
        ExtractedFacts facts = extractor.extract(
                "我下周想去医院复诊，帮我安排一下，出发前提醒我，并告诉女儿。", context, false);

        assertThat(facts.tasks()).extracting(ExtractedFacts.TaskItem::kind)
                .containsExactly("预约查询", "时间安排", "材料准备", "出行规划", "家属通知", "日程提醒");
        assertThat(facts.intent()).isEqualTo("CREATE_FOLLOWUP");
        // 医院、科室、日期都还没说，兜底也要能把"还缺什么"列出来。
        assertThat(facts.missingInformation())
                .containsExactly("想去哪家医院", "看哪个科室", "具体想约哪一天");
    }

    @Test
    void keepsHardConstraintsVerbatimAndNeverDropsExtraRequests() {
        ExtractedFacts facts = extractor.extract(
                "帮我约市第一医院心内科复诊，必须上午，另外帮我记一下要问医生的问题。", context, false);

        assertThat(facts.constraints()).containsExactly("必须上午");
        // 引子（"另外"）不算诉求本身，只留后面的正文。
        assertThat(facts.additionalRequests()).containsExactly("帮我记一下要问医生的问题");
        assertThat(facts.extraRequirements())
                .containsExactly("必须上午", "帮我记一下要问医生的问题");
    }

    @Test
    void separatesSoftPreferencesFromHardConstraints() {
        ExtractedFacts facts = extractor.extract("帮我约复诊，人少一点，想找女医生", context, false);

        assertThat(facts.preferences()).containsExactly("人少一点", "女医生");
        // 偏好不是限制：不能把它记成"必须满足"，否则会挡住本来能办的号。
        assertThat(facts.constraints()).isEmpty();
    }

    @Test
    void dateLimitsAreKeptAsSaid() {
        ExtractedFacts facts = extractor.extract("帮我约复诊，不能周三", context, false);
        assertThat(facts.constraints()).containsExactly("不能周三");
    }

    @Test
    void plainChatProducesNoTasksAndNoFollowUpQuestions() {
        ExtractedFacts facts = extractor.extract("今天天气不错", context, false);

        assertThat(facts.tasks()).isEmpty();
        assertThat(facts.missingInformation()).isEmpty();
        assertThat(facts.hasStructuredDemand()).isFalse();
    }
}
