package com.team.silveragent.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 会话生命周期策略：什么时候该判一段对话「已经结束」。
 *
 * <p>只管这一件事——不碰办理进度（{@link ConversationState#stage}），不碰会话内容，
 * 也不负责裁剪上下文（那是 {@link ConversationStore} 的 history-limit 在管）。
 *
 * <p>阈值全部来自配置，演示时可以临时调小：
 * {@code agent.conversation.idle-timeout-seconds=30}，不用改代码。
 *
 * <p><b>和「轮次/Token 上限」的关系</b>：这一版没有照搬按轮次或 Token 强制结束会话。
 * 原因是那两种上限会在老人办理到一半时把会话关掉，而关闭之后确认操作必须被拒——
 * 等于用一条治理规则把用户正做到一半的事弄坏。上下文膨胀由 history-limit 与本类
 * 的「该开新对话了」提示分别处理，不需要靠中断会话来解决。
 */
@Component
class ConversationLifecycle {

    private final long idleTimeoutSeconds;
    private final int suggestNewAfterTurns;

    ConversationLifecycle(
            @Value("${agent.conversation.idle-timeout-seconds:600}") long idleTimeoutSeconds,
            @Value("${agent.conversation.suggest-new-after-turns:40}") int suggestNewAfterTurns) {
        this.idleTimeoutSeconds = Math.max(1, idleTimeoutSeconds);
        this.suggestNewAfterTurns = Math.max(1, suggestNewAfterTurns);
    }

    /**
     * 是否已经空闲太久。时间未知时不判超时——宁可当成进行中，
     * 也不要因为读不到时间就把一段还能用的会话判死。
     */
    boolean isIdleExpired(LocalDateTime lastActiveAt) {
        if (lastActiveAt == null) return false;
        return Duration.between(lastActiveAt, LocalDateTime.now()).getSeconds() >= idleTimeoutSeconds;
    }

    /**
     * 这段对话是不是已经该换个新的了。
     *
     * <p>只用来提示，绝不用来结束会话：长对话本身不是错误，
     * 老人也不必理解「轮次」这个概念，这里的作用是让助手在合适的时候主动给出「新对话」的建议。
     */
    boolean shouldSuggestNewConversation(int turns) {
        return turns >= suggestNewAfterTurns;
    }

    /** 建议开新对话时附带的一句解释，写成人话，不出现「轮次」「上限」这类词。 */
    String suggestionText() {
        return "（这段对话已经比较长了。您要是想问新的事情，点上面的「新对话」就能重新开始，"
                + "之前聊过的都还在历史记录里。）";
    }
}
