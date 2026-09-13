package com.team.silveragent.application;

import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.application.memo.MemoParser;
import com.team.silveragent.application.memo.MemoStore;
import com.team.silveragent.domain.tool.MemoTool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 「记一条健康备忘」这一类已确认操作的执行器。
 *
 * <p>备忘的写入路径有三条：显式托付直接写、追问补齐时间后写、以及老人按确认卡点头后写。
 * 三条落的是同一条库、同一套回读话术，所以只有<b>一条</b>写实现（{@link #write}），
 * 确认这条路只是它的一个入口——在别处再写一遍，两边迟早在「正文要不要摘掉时间」这类细节上走散。
 *
 * <p>确认与拒绝在这里分开成两个方法（{@link #commit} / {@link #refuse}）：
 * 拒绝那条<b>一个业务动作都不执行</b>，只把草稿收干净、把话头交回去。把两件事写进同一个方法、
 * 靠一个 boolean 分岔，最容易在将来某次改动里让拒绝也走一遍写库。
 */
@Component
final class MemoExecutor {
    private final MemoTool memoTool;

    MemoExecutor(MemoTool memoTool) {
        this.memoTool = memoTool;
    }

    /** 老人点了「确认记下」：把草稿落成一条备忘。 */
    AgentTurnResponse commit(ConversationState state, ConfirmationSupport support) {
        String text = state.pendingMemoText;
        LocalDateTime at = state.pendingMemoAt;
        String repeat = state.pendingMemoRepeat;
        leaveConfirmation(state);
        if (text == null || text.isBlank()) {
            return support.memoHandoff(state, "这条备忘内容已失效，请重新对我说一遍。");
        }
        return write(state, text, at, repeat, support);
    }

    /** 老人点了「先不用」：草稿丢掉，不落库。 */
    AgentTurnResponse refuse(ConversationState state, ConfirmationSupport support) {
        leaveConfirmation(state);
        return support.memoHandoff(state, "好的，这条没有记下。");
    }

    /**
     * 落库一条备忘并回到原办理上下文（显式直写、追问后落库、确认后落库共用）。
     *
     * <p>写库这一步失败<b>必须在这里收住</b>，不能让它穿出去：确认那条路的调用点是
     * {@code FollowupAgentService.confirm()}，异常穿过它就是一个 HTTP 500——老人看到的是一块
     * 白屏或一句"服务器开小差了"，而这一轮到底办没办成，谁也说不清。收住之后走
     * {@link ConfirmationSupport#toolError}，与其它工具的失败同一个出口：记痕、定阶段、给话术。
     *
     * <p>失败之后的账是清楚的：凭据在 {@code ConfirmationService.consume} 里<b>已经消费掉了</b>
     * （走到执行就意味着授权用过了，失败不会把它还回来），草稿也已被
     * {@link #leaveConfirmation} 清干净，所以库里不会多出第二条，页面上也不会再留着一张
     * 实际已经作废的确认卡。
     */
    AgentTurnResponse write(ConversationState state, String text, LocalDateTime at, String repeatRule,
                            ConfirmationSupport support) {
        String repeat = MemoStore.normalizeRepeat(repeatRule);
        // 正文只留“事项”，时间只由“提醒”那一行负责。不摘的话首页会变成
        // “明早八点提醒我吃药 / 提醒：明天 08:00”，同一件事说两遍，改过时间的旧备忘
        // 又是另一种长相。没有提醒的长期备忘不能摘——“9月20号家人来接我”里的日期
        // 是内容本身，不是提醒，摘掉就把事记残了。
        String saved = at == null ? text : MemoParser.stripSchedule(text);
        MemoStore.MemoView created;
        try {
            created = support.callTool(state, "memo.create",
                    Map.of("text", saved, "remindAt", at == null ? "长期" : at.toString(),
                            "repeat", repeat == null ? "仅一次" : repeat),
                    () -> memoTool.create(state.id, state.userId, saved, at, repeat));
        } catch (RuntimeException error) {
            return support.toolError(state, error);
        }
        state.pendingMemoRepeat = null;
        state.pendingMemoDay = null;
        return support.memoHandoff(state, support.memoRecordedReply(created.text(), at, repeat));
    }

    /** 退出「等确认」：回到备忘提出前的那一页，草稿字段一并清掉（凭据已由 ConfirmationService 消费）。 */
    private void leaveConfirmation(ConversationState state) {
        state.pendingAction = "CREATE";
        state.stage = state.memoReturnStage == null
                ? ConversationState.Stage.READY_TO_PLAN : state.memoReturnStage;
        state.memoReturnStage = null;
        state.memoNeedsApproval = false;
        state.pendingMemoText = null;
        state.pendingMemoAt = null;
        state.pendingMemoRepeat = null;
        state.pendingMemoDay = null;
    }
}
