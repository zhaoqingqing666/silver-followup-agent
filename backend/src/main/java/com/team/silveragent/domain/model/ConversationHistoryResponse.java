package com.team.silveragent.domain.model;

import java.time.LocalDateTime;
import java.util.List;

public record ConversationHistoryResponse(
        String conversationId,
        String stage,
        /** 会话状态：ACTIVE 可继续聊；CLOSED 已结束，只能查看。 */
        String status,
        List<Message> messages,
        AgentTurnResponse current
) {
    public record Message(long id, String role, String content, LocalDateTime createdAt) { }
}
