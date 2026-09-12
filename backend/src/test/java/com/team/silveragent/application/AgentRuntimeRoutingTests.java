package com.team.silveragent.application;

import com.team.silveragent.agent.AgentContext;
import com.team.silveragent.agent.ExtractedFacts;
import com.team.silveragent.agent.RuleFactExtractor;
import com.team.silveragent.agent.planning.ConversationPlanner;
import com.team.silveragent.agent.planning.PlannerActionType;
import com.team.silveragent.agent.planning.PlannerDecision;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRuntimeRoutingTests {
    @Test
    void workflowIntentCannotBeHiddenInsideAnAnswerAction() {
        ConversationPlanner planner = mock(ConversationPlanner.class);
        ExtractedFacts facts = facts("CREATE_FOLLOWUP");
        when(planner.plan(any(), any(), any())).thenReturn(new PlannerDecision(
                PlannerActionType.ANSWER, "CREATE_FOLLOWUP", null, Map.of(),
                "好的，我来办理。", "FOLLOWUP_FLOW", facts, "MODEL_PLANNER"));

        AgentRuntime runtime = new AgentRuntime(planner, new ToolRegistry(), new ToolPolicy(),
                new ActionValidator(), new AgentOrchestrator(emptyCatalog()), mock(RuleFactExtractor.class));
        ConversationState state = new ConversationState("conversation", "user-001");
        state.taskStatus = ConversationState.TaskStatus.ACTIVE;

        AgentRuntime.Outcome outcome = runtime.plan("我要办理", new AgentContext(
                "ASK_HOSPITAL", "复诊任务状态=ACTIVE", LocalDate.of(2026, 9, 10), List.of()), state);

        assertThat(outcome.route()).isEqualTo(AgentOrchestrator.Route.RESUME_TASK);
        assertThat(outcome.route()).isNotEqualTo(AgentOrchestrator.Route.DIRECT_ANSWER);
    }

    @Test
    void successfulModelDecisionIsNotOverwrittenByJavaKeywordRouting() {
        ConversationPlanner planner = mock(ConversationPlanner.class);
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(new PlannerDecision(
                PlannerActionType.ANSWER, "UNKNOWN", null, Map.of(),
                "您是在问整个复诊流程，还是到医院后的报到流程？",
                "FOLLOWUP_FLOW", facts("UNKNOWN"), "MODEL_PLANNER"));
        AgentRuntime runtime = runtime(planner);

        AgentRuntime.Outcome outcome = runtime.plan("办理流程是什么", context(),
                new ConversationState("conversation", "user-001"));

        assertThat(outcome.modelDriven()).isTrue();
        assertThat(outcome.route()).isEqualTo(AgentOrchestrator.Route.DIRECT_ANSWER);
        assertThat(outcome.replyDraft()).contains("整个复诊流程");
    }

    @Test
    void modelCanAskNaturallyWhileCollectingInformation() {
        ConversationPlanner planner = mock(ConversationPlanner.class);
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(new PlannerDecision(
                PlannerActionType.ASK_USER, "PROVIDE_INFORMATION", null, Map.of(),
                "您说的同济医院是在武汉还是上海？", "FOLLOWUP_FLOW",
                facts("PROVIDE_INFORMATION"), "MODEL_PLANNER"));

        AgentRuntime.Outcome outcome = runtime(planner).plan("我想去同济", context(),
                new ConversationState("conversation", "user-001"));

        assertThat(outcome.route()).isEqualTo(AgentOrchestrator.Route.DIRECT_ANSWER);
        assertThat(outcome.replyDraft()).contains("武汉还是上海");
    }

    @Test
    void modelChoosesCatalogSearchToolDirectly() {
        ConversationPlanner planner = mock(ConversationPlanner.class);
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(new PlannerDecision(
                PlannerActionType.CALL_READ_TOOL, "PROVIDE_INFORMATION", "hospital.search",
                Map.of("keyword", "市一"), null, "FOLLOWUP_FLOW",
                facts("PROVIDE_INFORMATION"), "MODEL_PLANNER"));

        AgentRuntime.Outcome outcome = runtime(planner).plan("去市一", context(),
                new ConversationState("conversation", "user-001"));

        assertThat(outcome.route()).isEqualTo(AgentOrchestrator.Route.RESOLVE_HOSPITAL);
        assertThat(outcome.proposedTool()).isEqualTo("hospital.search");
    }

    /**
     * 备忘 / 健康数值 / 发周报这三件事必须落库或对外发消息，不能被模型的一句 ANSWER 带过去。
     * 实测过：不挡住的话，模型会对老人说“我给您记一个提醒”，而库里一条记录都没有。
     */
    @Test
    void memoryAndHealthIntentsAreNotSwallowedByAnAnswerAction() {
        for (String intent : List.of("MANAGE_MEMO", "RECORD_HEALTH_VALUE", "SEND_HEALTH_REPORT")) {
            ConversationPlanner planner = mock(ConversationPlanner.class);
            when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
            when(planner.plan(any(), any(), any())).thenReturn(new PlannerDecision(
                    PlannerActionType.ANSWER, intent, null, Map.of(),
                    "好的，我给您记下了。", "SMALL_TALK", facts(intent), "MODEL_PLANNER"));

            AgentRuntime.Outcome outcome = runtime(planner).plan("明早八点提醒我吃药", context(),
                    new ConversationState("conversation", "user-001"));

            assertThat(outcome.route()).as(intent)
                    .isEqualTo(AgentOrchestrator.Route.valueOf(intent));
            assertThat(outcome.route()).as(intent).isNotEqualTo(AgentOrchestrator.Route.DIRECT_ANSWER);
        }
    }

    /** 不相干的 intent 仍旧走原来的“直接回答”，别把日常三类扩成万能兜底。 */
    @Test
    void unrelatedIntentStillReachesDirectAnswer() {
        ConversationPlanner planner = mock(ConversationPlanner.class);
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(new PlannerDecision(
                PlannerActionType.ANSWER, "SMALL_TALK", null, Map.of(),
                "今天天气不错。", "SMALL_TALK", facts("SMALL_TALK"), "MODEL_PLANNER"));

        AgentRuntime.Outcome outcome = runtime(planner).plan("今天天气真好", context(),
                new ConversationState("conversation", "user-001"));

        assertThat(outcome.route()).isEqualTo(AgentOrchestrator.Route.DIRECT_ANSWER);
    }

    /**
     * 问药走真实知识库查询，不能由模型凭记忆回答——它的记忆里没有我们的药品库，
     * 说出来的用法用量也没人核对过。这里断言它被接到 drug.queryKnowledge 上。
     */
    @Test
    void drugQuestionIsRoutedToTheDrugKnowledgeTool() {
        ConversationPlanner planner = mock(ConversationPlanner.class);
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(new PlannerDecision(
                PlannerActionType.CALL_READ_TOOL, "QUERY_DRUG", "drug.queryKnowledge",
                Map.of("drugName", "阿司匹林"), null, "FOLLOWUP_FLOW",
                facts("QUERY_DRUG"), "MODEL_PLANNER"));

        AgentRuntime.Outcome outcome = runtime(planner).plan("阿司匹林是干嘛的", context(),
                new ConversationState("conversation", "user-001"));

        assertThat(outcome.route()).isEqualTo(AgentOrchestrator.Route.QUERY_DRUG_KNOWLEDGE);
        assertThat(outcome.proposedTool()).isEqualTo("drug.queryKnowledge");
    }

    private AgentRuntime runtime(ConversationPlanner planner) {
        return new AgentRuntime(planner, new ToolRegistry(), new ToolPolicy(),
                new ActionValidator(), new AgentOrchestrator(emptyCatalog()), mock(RuleFactExtractor.class));
    }

    private AgentContext context() {
        return new AgentContext("ASK_HOSPITAL", "预约草稿缺失字段=[医院, 科室]",
                LocalDate.of(2026, 9, 10), List.of());
    }

    /** 页面指令快速通道只用到医院/科室名做筛选条件判断，这个用例不涉及任何筛选。 */
    private CareCatalogRepository emptyCatalog() {
        CareCatalogRepository catalog = mock(CareCatalogRepository.class);
        when(catalog.hospitalNames()).thenReturn(List.of());
        when(catalog.departmentNames()).thenReturn(List.of());
        return catalog;
    }

    private ExtractedFacts facts(String intent) {
        return new ExtractedFacts(intent, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }
}
