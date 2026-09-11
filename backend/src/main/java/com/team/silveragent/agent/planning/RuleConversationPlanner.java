package com.team.silveragent.agent.planning;

import com.team.silveragent.agent.AgentContext;
import com.team.silveragent.agent.ExtractedFacts;
import com.team.silveragent.agent.RuleFactExtractor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 模型不可用时保留原有稳定业务路径。 */
@Component
public class RuleConversationPlanner implements ConversationPlanner {
    private final RuleFactExtractor extractor;

    public RuleConversationPlanner(RuleFactExtractor extractor) {
        this.extractor = extractor;
    }

    @Override
    public PlannerDecision plan(String message, AgentContext context, List<PlannerTool> allowedTools) {
        ExtractedFacts facts = extractor.extract(message, context);
        String intent = facts.intent();
        PlannerActionType type = switch (intent) {
            case "EMOTIONAL_SUPPORT", "SMALL_TALK", "CLARIFY_DISCOMFORT" -> PlannerActionType.ANSWER;
            case "EXPLAIN_PROCESS", "QUERY_HOSPITALS", "QUERY_HOSPITAL_INFO", "QUERY_DEPARTMENTS",
                    "QUERY_AVAILABLE_SLOTS", "QUERY_APPOINTMENTS", "ASK_MATERIALS",
                    "ASK_TRAVEL_ROUTE", "ASK_LOCATION_GUIDE" -> PlannerActionType.CALL_READ_TOOL;
            default -> PlannerActionType.PROPOSE_WORKFLOW_ACTION;
        };
        String toolName = switch (intent) {
            case "EXPLAIN_PROCESS" -> "careGuide.search";
            case "QUERY_HOSPITALS", "QUERY_HOSPITAL_INFO" -> "hospital.list";
            case "QUERY_DEPARTMENTS" -> "department.list";
            case "QUERY_AVAILABLE_SLOTS" -> "appointment.querySlots";
            case "QUERY_APPOINTMENTS" -> "appointment.queryMine";
            case "ASK_MATERIALS" -> "material.checklist";
            case "ASK_TRAVEL_ROUTE" -> "travel.routePlan";
            case "ASK_LOCATION_GUIDE" -> "hospital.locationGuide";
            default -> null;
        };
        return new PlannerDecision(type, intent, toolName, Map.of(), null,
                dialogueMode(intent), facts, "RULE_FALLBACK");
    }

    private String dialogueMode(String intent) {
        if ("EMOTIONAL_SUPPORT".equals(intent) || "CLARIFY_DISCOMFORT".equals(intent)) return "SUPPORT";
        if ("SMALL_TALK".equals(intent)) return "SMALL_TALK";
        return "FOLLOWUP_FLOW";
    }

    @Override public String mode() { return "RULE_PLANNER_FALLBACK"; }
}
