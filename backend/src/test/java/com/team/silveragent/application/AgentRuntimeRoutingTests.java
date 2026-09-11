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
