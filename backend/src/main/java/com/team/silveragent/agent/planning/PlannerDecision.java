package com.team.silveragent.agent.planning;

import com.team.silveragent.agent.ExtractedFacts;

import java.util.List;
import java.util.Map;

/**
 * 模型提出的建议动作。它不是执行命令，仍需 Java 策略层审核。
 *
 * <p>{@code recommendations} 与 {@code answering} 是推荐轮专用的后半段：模型在最后一次调用里
 * 同时给出<b>结构化</b>的推荐清单和给老人看的最终话语，Java 只校验结构化那一半，通过之后
 * {@code answering} 原样交付、不再有第二次润色。其它路由这两项都是空的，
 * {@code replyDraft} 的既有含义一字不变。
 */
public record PlannerDecision(
        PlannerActionType actionType,
        String intent,
        String toolName,
        Map<String, String> arguments,
        String replyDraft,
        String dialogueMode,
        ExtractedFacts facts,
        String source,
        List<PlannerToolCall> toolCalls,
        List<HospitalRecommendation> recommendations,
        String answering
) {
    public PlannerDecision(PlannerActionType actionType, String intent, String toolName,
                           Map<String, String> arguments, String replyDraft, String dialogueMode,
                           ExtractedFacts facts, String source) {
        this(actionType, intent, toolName, arguments, replyDraft, dialogueMode, facts, source,
                toolName == null ? List.of() : List.of(new PlannerToolCall(toolName, arguments)),
                List.of(), null);
    }

    /** 兼容既有调用点：不带结构化推荐的那一轮。 */
    public PlannerDecision(PlannerActionType actionType, String intent, String toolName,
                           Map<String, String> arguments, String replyDraft, String dialogueMode,
                           ExtractedFacts facts, String source, List<PlannerToolCall> toolCalls) {
        this(actionType, intent, toolName, arguments, replyDraft, dialogueMode, facts, source,
                toolCalls, List.of(), null);
    }

    public PlannerDecision {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        facts = facts == null ? ExtractedFacts.empty() : facts;
        source = source == null ? "UNKNOWN" : source;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
    }
}
