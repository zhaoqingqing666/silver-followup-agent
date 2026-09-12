package com.team.silveragent.domain.model;

import java.time.LocalDateTime;
import java.util.List;

public record ConversationHistoryResponse(
        String conversationId,
        /**
         * 会话生命周期状态：ACTIVE / CLOSED / EXPIRED。
         * 前端靠它决定这段历史是「还能接着聊」还是「只能翻看」——
         * 已结束的会话不该把输入框摆在那儿，让人打完字才被拒绝。
         */
        String status,
        String stage,
        List<Message> messages,
        AgentTurnResponse current
) {
    public record Message(long id, String role, String content, LocalDateTime createdAt) { }
}
