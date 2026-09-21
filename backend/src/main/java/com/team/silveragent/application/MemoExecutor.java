package com.team.silveragent.application;

import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.application.memo.MemoParser;
import com.team.silveragent.application.memo.MemoStore;
import com.team.silveragent.application.time.BusinessClock;
import com.team.silveragent.domain.tool.MemoTool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
 *
 * <p><b>一句话说几天就写几条。</b>memos 一条只有一个 remind_at，拆条比另加一个多日期字段改动
 * 小得多，也顺带让每条能单独完成、单独删除——老人今天吃完周二那条点掉，剩下的两天还在。
 */
@Component
final class MemoExecutor {
    private final MemoTool memoTool;
    private final BusinessClock clock;

    MemoExecutor(MemoTool memoTool, BusinessClock clock) {
        this.memoTool = memoTool;
        this.clock = clock;
    }

    /** 老人点了「确认记下」：把草稿落成备忘（说了几天就是几条）。 */
    AgentTurnResponse commit(ConversationState state, ConfirmationSupport support) {
        String text = state.pendingMemoText;
        List<LocalDateTime> ats = state.pendingMemoAts;
        String repeat = state.pendingMemoRepeat;
        leaveConfirmation(state);
        if (text == null || text.isBlank()) {
            return support.memoHandoff(state, "这条备忘内容已失效，请重新对我说一遍。");
        }
        return write(state, text, ats, repeat, support);
    }

    /** 老人点了「先不用」：草稿丢掉，不落库。 */
    AgentTurnResponse refuse(ConversationState state, ConfirmationSupport support) {
        leaveConfirmation(state);
        return support.memoHandoff(state, "好的，这条没有记下。");
    }

    /**
     * 落库备忘并回到原办理上下文（显式直写、追问后落库、确认后落库共用）。
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
     *
     * <p><b>写到一半失败</b>是另一回事，也是这个方法里唯一需要多想一步的地方：前面几条已经落库了，
     * 这时候<b>停下、不再往下写</b>（继续写只会让"成了几条"更难说清），然后如实回读——
     * 写成的逐条念，没写成的说清是哪几天。前半句不能含糊成"都记好了"，后半句也不能被
     * "成功了 N 条"盖过去：老人照着这句话去首页核对，看到的必须和听到的一致。
     */
    AgentTurnResponse write(ConversationState state, String text, List<LocalDateTime> ats, String repeatRule,
                            ConfirmationSupport support) {
        String repeat = MemoStore.normalizeRepeat(repeatRule);
        List<LocalDateTime> times = ats == null ? List.of()
                : ats.stream().filter(at -> at != null).distinct().sorted().toList();
        List<MemoStore.MemoView> created = new ArrayList<>();
        if (times.isEmpty()) {
            // 没有提醒的长期备忘不能摘正文——“9月20号家人来接我”里的日期是内容本身，
            // 不是提醒，摘掉就把事记残了。
            try {
                created.add(create(state, text, null, repeat, support));
            } catch (RuntimeException error) {
                return support.toolError(state, error);
            }
            return finish(state, created, repeat, support);
        }
        for (int index = 0; index < times.size(); index++) {
            LocalDateTime at = times.get(index);
            try {
                created.add(create(state, textFor(text, at), at, repeat, support));
            } catch (RuntimeException error) {
                // 一条都没落地：这跟只写一条时的失败是同一件事，走同一个出口（TOOL_ERROR、“本步骤未完成”）。
                // 套上下面那套“部分成功”的话术会把“全没记上”说成“记了几天、漏了几天”。
                if (created.isEmpty()) {
                    state.pendingMemoRepeat = null;
                    state.pendingMemoDays = null;
                    return support.toolError(state, error);
                }
                // 前面几条已经在库里了，凭据也消费掉了：这里只能把已经发生的事说清楚，
                // 不能回滚（删掉刚写的那几条等于把老人已经看到的东西又拿走），也不能假装没发生。
                support.recordToolFailure(state, error);
                state.pendingMemoRepeat = null;
                state.pendingMemoDays = null;
                return support.memoHandoff(state, support.memoPartlyRecordedReply(created,
                        times.subList(index, times.size()), repeat));
            }
        }
        return finish(state, created, repeat, support);
    }

    /**
     * 这一条该用哪段正文。
     *
     * <p>正文只留“事项”，时间只由“提醒”那一行负责。不摘的话首页会变成
     * “明早八点提醒我吃药 / 提醒：明天 08:00”，同一件事说两遍，改过时间的旧备忘又是另一种长相。
     *
     * <p>每条正文只说自己那天（“每周一三五早上八点吃药”→“每周三早上八点吃药”）：
     * 整串星期词直接喂进 {@code stripSchedule} 会漏残渣，实测“每周一三五早上八点吃药”摘完是
     * “三五吃药”、“下周一和周三早上八点吃药”摘完是“和吃药”——单天句子走的才是那条被断言
     * 钉住的老路。只有一条时也走这里：句中的“这周三”里的“这周”会被当成范围词摘掉，
     * 不换字同样会留下“三吃药”。
     */
    private String textFor(String text, LocalDateTime at) {
        return MemoParser.stripSchedule(MemoParser.textForDay(text, at.toLocalDate(), clock.today()));
    }

    /** 真往库里写一条备忘；拆条时逐条调用。 */
    private MemoStore.MemoView create(ConversationState state, String text, LocalDateTime at, String repeat,
                                      ConfirmationSupport support) {
        return support.callTool(state, "memo.create",
                Map.of("text", text, "remindAt", at == null ? "长期" : at.toString(),
                        "repeat", repeat == null ? "仅一次" : repeat),
                () -> memoTool.create(state.id, state.userId, text, at, repeat));
    }

    /** 写完了：清掉草稿里剩下的东西，回读库里的那几条。 */
    private AgentTurnResponse finish(ConversationState state, List<MemoStore.MemoView> created, String repeat,
                                    ConfirmationSupport support) {
        state.pendingMemoRepeat = null;
        state.pendingMemoDays = null;
        return support.memoHandoff(state, support.memoRecordedReply(created, repeat));
    }

    /** 退出「等确认」：回到备忘提出前的那一页，草稿字段一并清掉（凭据已由 ConfirmationService 消费）。 */
    private void leaveConfirmation(ConversationState state) {
        state.pendingAction = "CREATE";
        state.stage = state.memoReturnStage == null
                ? ConversationState.Stage.READY_TO_PLAN : state.memoReturnStage;
        state.memoReturnStage = null;
        state.memoNeedsApproval = false;
        state.pendingMemoText = null;
        state.pendingMemoAts = null;
        state.pendingMemoRepeat = null;
        state.pendingMemoDays = null;
    }
}
