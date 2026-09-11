package com.team.silveragent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型返回的 JSON → {@link ExtractedFacts} 的解析细节。
 *
 * <p>不联网、不需要 API Key：解析逻辑本身就该能单独验证，而不是只有真的调一次远端模型才知道对不对。
 */
class QwenFactExtractorTests {
    private final ObjectMapper json = new ObjectMapper();

    private ExtractedFacts parse(String body) throws Exception {
        return QwenFactExtractor.parseFacts(json.readTree(body));
    }

    @Test
    void parsesCompoundDemandWithSnakeCaseKeys() throws Exception {
        ExtractedFacts facts = parse("""
                {"intent":"CREATE_FOLLOWUP","hospital":"市第一医院","department":"心内科",
                 "tasks":[{"kind":"预约查询","summary":"查一下能约到的号源"},
                          {"kind":"出行规划","summary":"算好出发时间"}],
                 "constraints":["必须上午"],
                 "preferences":["人少一点"],
                 "additional_requests":["记一下要问医生的问题"],
                 "missing_information":["具体想约哪一天"]}
                """);

        assertThat(facts.intent()).isEqualTo("CREATE_FOLLOWUP");
        assertThat(facts.hospital()).isEqualTo("市第一医院");
        assertThat(facts.tasks()).extracting(ExtractedFacts.TaskItem::kind)
                .containsExactly("预约查询", "出行规划");
        assertThat(facts.tasks().get(0).label()).isEqualTo("预约查询：查一下能约到的号源");
        assertThat(facts.constraints()).containsExactly("必须上午");
        assertThat(facts.preferences()).containsExactly("人少一点");
        assertThat(facts.additionalRequests()).containsExactly("记一下要问医生的问题");
        assertThat(facts.missingInformation()).containsExactly("具体想约哪一天");
        assertThat(facts.extraRequirements())
                .containsExactly("必须上午", "人少一点", "记一下要问医生的问题");
    }

    @Test
    void acceptsCamelCaseKeysAndPlainStringTasks() throws Exception {
        ExtractedFacts facts = parse("""
                {"tasks":["预约查询","家属通知","顺便问问用药"],
                 "additionalRequests":["顺便问问用药"],
                 "missingInformation":["想去哪家医院"]}
                """);

        // 认得出的任务类型保留成 kind；认不出的按"其他"留下说明，不静默丢弃。
        assertThat(facts.tasks()).extracting(ExtractedFacts.TaskItem::kind)
                .containsExactly("预约查询", "家属通知", "其他");
        assertThat(facts.tasks().get(2).label()).isEqualTo("其他：顺便问问用药");
        assertThat(facts.additionalRequests()).containsExactly("顺便问问用药");
        assertThat(facts.missingInformation()).containsExactly("想去哪家医院");
        // 模型没给 intent 时兜底成"仅提供信息"，不能凭空生成办理意图。
        assertThat(facts.intent()).isEqualTo("PROVIDE_INFORMATION");
    }

    @Test
    void missingOrMalformedFieldsFallBackToEmptyLists() throws Exception {
        ExtractedFacts facts = parse("""
                {"intent":"CREATE_FOLLOWUP","tasks":"预约查询","constraints":null}
                """);

        // tasks 不是数组、constraints 是 null：都当"没说"，而不是抛出异常或塞进垃圾数据。
        assertThat(facts.tasks()).isEmpty();
        assertThat(facts.constraints()).isEmpty();
        assertThat(facts.preferences()).isEmpty();
        assertThat(facts.additionalRequests()).isEmpty();
        assertThat(facts.missingInformation()).isEmpty();
        assertThat(facts.hasStructuredDemand()).isFalse();
    }

    @Test
    void emptyObjectYieldsAllEmptyDemandFields() throws Exception {
        ExtractedFacts facts = parse("{}");

        assertThat(facts.hasStructuredDemand()).isFalse();
        assertThat(facts.extraRequirements()).isEmpty();
        assertThat(facts.date()).isNull();
        assertThat(facts.selectedTime()).isNull();
    }
}
