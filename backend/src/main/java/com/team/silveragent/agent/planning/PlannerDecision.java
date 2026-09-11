package com.team.silveragent.agent.planning;

import com.team.silveragent.agent.ExtractedFacts;

import java.util.List;
import java.util.Map;

/** 模型提出的建议动作。它不是执行命令，仍需 Java 策略层审核。 */
public record PlannerDecision(
        PlannerActionType actionType,
        String intent,
        String toolName,
        Map<String, String> arguments,
        String replyDraft,
        String dialogueMode,
        ExtractedFacts facts,
        String source,
        List<PlannerToolCall> toolCalls
) {
    public PlannerDecision(PlannerActionType actionType, String intent, String toolName,
                           Map<String, String> arguments, String replyDraft, String dialogueMode,
                           ExtractedFacts facts, String source) {
        this(actionType, intent, toolName, arguments, replyDraft, dialogueMode, facts, source,
                toolName == null ? List.of() : List.of(new PlannerToolCall(toolName, arguments)));
    }

    public PlannerDecision {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        facts = facts == null ? ExtractedFacts.empty() : facts;
        source = source == null ? "UNKNOWN" : source;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
