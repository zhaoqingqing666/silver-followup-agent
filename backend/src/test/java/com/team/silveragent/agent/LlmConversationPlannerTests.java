package com.team.silveragent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.silveragent.agent.model.ModelGateway;
import com.team.silveragent.agent.model.ModelRequest;
import com.team.silveragent.agent.planning.LlmConversationPlanner;
import com.team.silveragent.agent.planning.PlannerActionType;
import com.team.silveragent.agent.planning.PlannerTool;
import com.team.silveragent.agent.planning.RuleConversationPlanner;
import com.team.silveragent.application.CareCatalogRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LlmConversationPlannerTests {
    @Test
    void modelCanProposeAWhitelistedReadToolWithoutExecutingIt() {
        LlmConversationPlanner planner = planner("""
                {"actionType":"CALL_READ_TOOL","intent":"QUERY_APPOINTMENTS",
                 "toolName":"appointment.queryMine","arguments":{"date":"2026-09-18"},
                 "replyDraft":null,"dialogueMode":"FOLLOWUP_FLOW",
                 "facts":{"date":"2026-09-18"}}
                """);

        var decision = planner.plan("我9月18日有预约吗", context(), List.of(
                new PlannerTool("appointment.queryMine", "查询本人预约", "READ_ONLY", List.of("date"))));

        assertThat(decision.actionType()).isEqualTo(PlannerActionType.CALL_READ_TOOL);
        assertThat(decision.toolName()).isEqualTo("appointment.queryMine");
        assertThat(decision.facts().date()).isEqualTo(LocalDate.of(2026, 9, 18));
        assertThat(decision.source()).isEqualTo("MODEL_PLANNER");
    }

    @Test
    void modelCanAnswerSupportivelyWithoutForcingTheWorkflow() {
        LlmConversationPlanner planner = planner("""
                {"actionType":"ANSWER","intent":"EMOTIONAL_SUPPORT","toolName":null,"arguments":{},
                 "replyDraft":"听起来您现在很累，我们先不着急办理。您愿意说说吗？","dialogueMode":"SUPPORT",
                 "facts":{"emotion":"TIRED","concern":"很累"}}
                """);

        var decision = planner.plan("我很累", context(), List.of());

        assertThat(decision.actionType()).isEqualTo(PlannerActionType.ANSWER);
        assertThat(decision.dialogueMode()).isEqualTo("SUPPORT");
        assertThat(decision.replyDraft()).doesNotContain("医院");
    }

    @Test
    void modelCanProposeMultipleIndependentReadTools() {
        LlmConversationPlanner planner = planner("""
                {"actionType":"CALL_READ_TOOLS","intent":"EXPLAIN_PROCESS",
                 "toolName":null,"arguments":{},"dialogueMode":"FOLLOWUP_FLOW",
                 "toolCalls":[
                   {"toolName":"careGuide.search","arguments":{"query":"复诊流程"}},
                   {"toolName":"material.checklist","arguments":{"department":"心内科"}}
                 ],"facts":{"department":"心内科"}}
                """);

        var decision = planner.plan("复诊流程是什么，要带什么材料", context(), List.of(
                new PlannerTool("careGuide.search", "查询办事流程", "READ_ONLY", List.of("query")),
                new PlannerTool("material.checklist", "查询材料", "READ_ONLY", List.of("department"))));

        assertThat(decision.actionType()).isEqualTo(PlannerActionType.CALL_READ_TOOLS);
        assertThat(decision.toolCalls()).extracting(item -> item.toolName())
                .containsExactly("careGuide.search", "material.checklist");
    }

    @Test
    void modelCanExtractSeveralDraftFieldsAndFamilyContactInOneTurn() {
        LlmConversationPlanner planner = planner("""
                {"actionType":"PROPOSE_WORKFLOW_ACTION","intent":"PROVIDE_INFORMATION",
                 "replyDraft":"我先核对科室简称，再为您查上午号源。","dialogueMode":"FOLLOWUP_FLOW",
                 "facts":{"department":"神内","date":"2026-09-18","timePreference":"MORNING",
                 "needCompanion":true,"needTravel":true,"transport":"家属开车",
                 "notifyFamily":true,"familyContact":"女儿小丽"}}
                """);

        var decision = planner.plan("神内，9月18日上午，让女儿小丽开车陪我并通知她",
                context(), List.of());

        assertThat(decision.facts().department()).isEqualTo("神内");
        assertThat(decision.facts().date()).isEqualTo(LocalDate.of(2026, 9, 18));
        assertThat(decision.facts().timePreference()).isEqualTo("MORNING");
        assertThat(decision.facts().familyContact()).isEqualTo("女儿小丽");
        assertThat(decision.facts().needCompanion()).isTrue();
    }

    @Test
    void toolEvidenceIsReturnedToTheSamePlannerWithTheOriginalRequest() {
        AtomicReference<ModelRequest> captured = new AtomicReference<>();
        ModelGateway gateway = new ModelGateway() {
            @Override public String complete(ModelRequest request) {
                captured.set(request);
                return """
                        {"actionType":"ANSWER","intent":"QUERY_NEARBY_SLOTS","arguments":{},
                         "replyDraft":"9月19日没有号，9月20日上午还有可选时间。",
                         "dialogueMode":"FOLLOWUP_FLOW","facts":{}}
                        """;
            }
            @Override public boolean available() { return true; }
            @Override public String providerName() { return "test"; }
            @Override public String modelName() { return "test"; }
        };
        RuleFactExtractor extractor = new RuleFactExtractor(mock(CareCatalogRepository.class));
        LlmConversationPlanner planner = new LlmConversationPlanner(gateway, new ObjectMapper(),
                new RuleConversationPlanner(extractor));

        var decision = planner.continueAfterTools("帮我查9月19日的号", context(), List.of(),
                "{\"status\":\"NO_SLOT\",\"allowedNextActions\":[\"CHANGE_DATE\"]}");

        assertThat(decision.source()).isEqualTo("MODEL_TOOL_CONTINUATION");
        assertThat(decision.replyDraft()).contains("9月19日没有号");
        assertThat(captured.get().messages().get(captured.get().messages().size() - 1).content())
                .contains("帮我查9月19日的号", "NO_SLOT", "allowedNextActions");
    }

    private LlmConversationPlanner planner(String response) {
        ModelGateway gateway = new ModelGateway() {
            @Override public String complete(ModelRequest request) { return response; }
            @Override public boolean available() { return true; }
            @Override public String providerName() { return "test"; }
            @Override public String modelName() { return "test"; }
        };
        CareCatalogRepository catalog = mock(CareCatalogRepository.class);
        RuleFactExtractor extractor = new RuleFactExtractor(catalog);
        return new LlmConversationPlanner(gateway, new ObjectMapper(), new RuleConversationPlanner(extractor));
    }

    private AgentContext context() {
        return new AgentContext("ASK_HOSPITAL", "对话模式=SUPPORT", LocalDate.of(2026, 9, 9), List.of());
    }
}
