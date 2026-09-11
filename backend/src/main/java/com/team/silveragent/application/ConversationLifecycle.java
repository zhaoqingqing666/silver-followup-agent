package com.team.silveragent.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 会话生命周期策略：空闲超时、轮次上限、Token 上限的判定与面向老人的提示文案。
 *
 * 阈值全部来自配置（agent.conversation.*），演示时可临时调小，例如
 * idle-timeout-seconds=30 / warn-turns=2 / max-turns=3，无需改代码。
 *
 * 这里只管"什么时候该提醒、什么时候该结束"，不碰办理进度（ConversationState.stage），
 * 也不碰会话内容本身，保持职责单一。
 */
@Component
class ConversationLifecycle {

    private final long idleTimeoutSeconds;
    private final int warnTurns;
    private final int maxTurns;
    private final int warnTokens;
    private final int maxTokens;
    private final int recentMessages;
    private final int recentImages;
    private final long maxImageBytes;

    ConversationLifecycle(
            @Value("${agent.conversation.idle-timeout-seconds:600}") long idleTimeoutSeconds,
            @Value("${agent.conversation.warn-turns:80}") int warnTurns,
            @Value("${agent.conversation.max-turns:100}") int maxTurns,
            @Value("${agent.conversation.warn-tokens:8000}") int warnTokens,
            @Value("${agent.conversation.max-tokens:12000}") int maxTokens,
            @Value("${agent.conversation.recent-messages:8}") int recentMessages,
            @Value("${agent.conversation.recent-images:5}") int recentImages,
            @Value("${agent.conversation.max-image-bytes:10485760}") long maxImageBytes) {
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.warnTurns = warnTurns;
        this.maxTurns = maxTurns;
        this.warnTokens = warnTokens;
        this.maxTokens = maxTokens;
        this.recentMessages = recentMessages;
        this.recentImages = recentImages;
        this.maxImageBytes = maxImageBytes;
    }

    /** 空闲超时由后端按 last_message_at 判断，前端不参与。时间未知时不判超时。 */
    boolean isIdleExpired(LocalDateTime lastMessageAt) {
        if (lastMessageAt == null) return false;
        return Duration.between(lastMessageAt, LocalDateTime.now()).getSeconds() >= idleTimeoutSeconds;
    }

    /**
     * 会话结束判定。轮次和 Token 任一先到上限即结束；预警只在"刚触碰阈值"的那一轮给一次，
     * 避免之后每一轮都重复提醒。
     */
    Verdict evaluate(int previousTurns, int previousTokens, int turnCount, int estimatedTokens) {
        if (turnCount >= maxTurns) return new Verdict(false, true, "TURN_LIMIT");
        if (estimatedTokens >= maxTokens) return new Verdict(false, true, "TOKEN_LIMIT");
        boolean crossedTurns = previousTurns < warnTurns && turnCount >= warnTurns;
        boolean crossedTokens = previousTokens < warnTokens && estimatedTokens >= warnTokens;
        return new Verdict(crossedTurns || crossedTokens, false, null);
    }

    /** 已经结束时，向老人解释为什么换了一段新对话。 */
    String closeText(Verdict verdict) {
        if ("TOKEN_LIMIT".equals(verdict.reason())) {
            return "（这段对话内容已经很多了，我为您开启了一段新对话；刚才聊过的内容都已经保存，随时可以查看。）";
        }
        return "（我们聊的轮次已经不少了，我为您开启了一段新对话；刚才聊过的内容都已经保存，随时可以查看。）";
    }

    /** 快到上限时只提醒，不结束会话。 */
    String warningText() {
        return "（小提示：这段对话已经比较长了。如果您要问新的问题，可以点右上角的「新对话」重新开始。）";
    }

    /** 一次判定的结果：只提醒 / 直接结束（附结束原因）。 */
    record Verdict(boolean warn, boolean close, String reason) { }

    /** 图片是否超出大小限制。dataUrl 为 base64，按 4/3 膨胀估算原始字节数。 */
    boolean imageTooLarge(String dataUrl) {
        if (dataUrl == null) return false;
        return dataUrl.length() > maxImageBytes / 3L * 4L + 64L;
    }

    String imageTooLargeText() {
        return "这张图片太大了，我这边看不了。麻烦您换一张小一点的图片（10MB 以内），或者直接告诉我药盒上的名字。";
    }

    int recentMessages() { return recentMessages; }

    int recentImages() { return recentImages; }
}
