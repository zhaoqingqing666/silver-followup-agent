package com.team.silveragent.agent;

import java.util.List;

public record ReplyContext(
        String stage,
        String dialogueMode,
        String knownFacts,
        String authoritativeDraft,
        List<String> quickReplies,
        String planSummary,
        String confirmationSummary,
        String resultSummary,
        String toolSummary,
        List<AgentContext.Message> recentMessages,
        boolean emergency,
        boolean medicalBoundary,
        boolean awaitingConfirmation
) { }
