package com.team.silveragent.domain.model;

import java.time.LocalDateTime;
import java.util.List;

public record ConversationHistoryResponse(
        String conversationId,
        String stage,
        List<Message> messages,
        AgentTurnResponse current
) {
    public record Message(long id, String role, String content, LocalDateTime createdAt) { }
}
