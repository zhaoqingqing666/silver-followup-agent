package com.team.silveragent.domain.model;

import java.time.LocalDateTime;

/**
 * 历史记录里的一行。
 *
 * <p>刻意只带列表要用的这几个字段，不带 {@code state_json}：一次拉一整页会话，
 * 每条都把完整状态读出来再反序列化，纯属浪费——列表上又不显示草稿细节。
 * 想看某一条就点进去走 {@code /conversations/{id}}。
 *
 * @param conversationId 会话 id，点进去查看时用它
 * @param title          从这条会话的第一句用户消息截出来的，用作列表标题
 * @param status         会话生命周期状态（ACTIVE / CLOSED / EXPIRED），列表上的「已结束」徽标看它
 * @param stage          办理阶段，用于在列表上说明「办到哪儿了」
 * @param messageCount   这条会话有多少条消息
 * @param updatedAt      最后一次活动时间，列表按它倒序
 */
public record ConversationSummary(
        String conversationId,
        String title,
        String status,
        String stage,
        int messageCount,
        LocalDateTime updatedAt
) { }
