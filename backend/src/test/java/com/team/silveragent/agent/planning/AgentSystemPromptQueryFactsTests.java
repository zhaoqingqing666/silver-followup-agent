package com.team.silveragent.agent.planning;

import com.team.silveragent.agent.AgentContext;
import com.team.silveragent.application.time.BusinessClock;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只读查询的条件与办理草稿的改动必须分开写：查询条件进 arguments，facts 只在老人明确要改草稿时才有值。
 *
 * <p>这条规则来自 2026-09-13 的实测——老人只是问一句「下周三有没有号」，模型把参数抄进 facts，
 * Java 照单收下就把待办理的预约日期改了。Java 侧已经有三处剥离兜底（见 {@code SlotQueryArgumentTests}），
 * 这里钉住的是提示词侧：模型本身要被告知别这么写，以及查询结果不许说成已经办成。
 */
class AgentSystemPromptQueryFactsTests {

    private final AgentSystemPrompt prompt = new AgentSystemPrompt();

    private String planningPrompt() {
        return prompt.planning(new AgentContext("ASK_DATE", "医院=市第一医院",
                LocalDate.of(2026, 9, 16), List.of()), "[]");
    }

    /** 查询条件只写 arguments；用户只是询问别的医院/科室/日期时不要同时写进 facts。 */
    @Test
    void readOnlyConditionsBelongInArgumentsNotFacts() {
        String text = planningPrompt();

        assertThat(text).contains(
                "只读查询（appointment.querySlots、appointment.queryNearbySlots 以及所有 READ_ONLY 工具）",
                "条件写在该工具的 arguments 里",
                "不代表老人改了手头的预约草稿",
                "facts 只表示用户明确要求写入或修改办理草稿的业务事实");
    }

    /** 「问一处、改另一处」是这条规则最容易踩的坑，必须把「不要同时写进 facts」说死。 */
    @Test
    void askingAboutAnotherHospitalOrDayMustNotAlsoWriteFacts() {
        String text = planningPrompt();

        assertThat(text).contains(
                "用户只是询问另一家医院、另一个科室、另一个日期或别处号源时",
                "把条件放进这次查询的 arguments，不要同时写进 facts",
                "草稿保持原样，等他明说要改再改");
    }

    /** 查到号源 == 已经预约，是这类改动里最危险的误读，提示词必须把三件事分别否掉。 */
    @Test
    void queryResultsMayNotBeDescribedAsCompletedWrites() {
        String text = planningPrompt();

        assertThat(text).contains(
                "查询结果只是查到的信息，不能被描述成已经办成",
                "查到号源不等于已经预约",
                "查到别的日期不等于",
                "已经改期，查到可取消的预约不等于已经取消",
                "只有确认门禁真正执行成功后，才能说已经预约、改期或取消");
    }

    /** 这一轮没有新增字段、没有新增工具：JSON 规划骨架保持原样。 */
    @Test
    void theJsonPlanningShapeIsUnchanged() {
        String text = planningPrompt();

        assertThat(text).contains(
                "{\"actionType\":\"ANSWER|ASK_USER|CALL_READ_TOOL|CALL_READ_TOOLS|CALL_CONFIRMATION_TOOL|PROPOSE_WORKFLOW_ACTION\"",
                "\"facts\":{\"hospital\":null,\"department\":null,\"date\":null,\"acceptAlternative\":null,");
    }
}
