package com.team.silveragent.domain.model;

import java.time.LocalDateTime;

/** 会话历史列表里的一条摘要（参考豆包的历史对话面板） */
public record ConversationSummary(
        String id,
        String title,
        String stage,
        LocalDateTime updatedAt,
        /** 会话状态：ACTIVE 可继续聊；CLOSED 已结束，只能查看。 */
        String status,
        long messageCount
) {
}
