package com.team.silveragent.application;

import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.model.AgentTurnResponse.QuickReply;
import com.team.silveragent.application.health.HealthRecordStore;
import com.team.silveragent.application.memo.MemoStore;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 各业务执行器执行完之后，需要编排层替它做的几件事。
 *
 * <p>确认通过之后的执行分两层：<b>业务执行器</b>（{@link BookingExecutor} 这一类）只负责
 * 「这件事到底怎么写进库、写完会话停在哪儿」；而「这段话怎么出口播、这一轮算不算完成」
 * 仍由编排层决定——那些出口在重构之前就存在，各自带着整理好的措辞与统一的收尾
 * （存会话、出站对账、生成回答），搬一次家只会多出一份迟早走样的副本。所以执行器不自己
 * 拼 {@code AgentTurnResponse}，而是通过这个口子把「执行完了」交回来。
 *
 * <p><b>为什么是抽象类不是接口：</b>接口的方法隐式 {@code public}，而实现方
 * {@link FollowupAgentService} 是公开类——用接口就得把这十几个方法一起提到它的公开面上。
 * 包级私有的抽象类可以让它们保持包级私有，与 DEC-018「编排簇刻意留在根包、可见性不放宽」
 * 是同一条理由。
 *
 * <p>这个口子<b>只服务于确认执行器</b>，且刻意只开执行器真正用到的那几个方法：
 * 它是一份名单，不是可以随手往里加东西的公共门面。要用新方法，先问一句
 * 「这件事是不是应该留在编排层、由执行器把结果交回来」。
 */
abstract class ConfirmationSupport {
    /** 尽快造一个快捷按钮（列表里的 action 与 label 是配对出现的）。 */
    static QuickReply q(String label, String action, String value) {
        return new QuickReply(label, action, value);
    }

    /** 带计划卡的回复（办理过程中的回答都用它）。 */
    abstract AgentTurnResponse respondWithPlan(ConversationState state, String reply, List<QuickReply> replies);

    /** 不带计划卡的纯文本回复（用于「已有安排的管理」这类话术）。 */
    abstract AgentTurnResponse respondSimple(ConversationState state, String reply, List<QuickReply> replies);

    /** 口播与文字同一份、不经回答模型改写的回复。 */
    abstract AgentTurnResponse respondWithoutModel(ConversationState state, String reply, List<QuickReply> replies);

    /**
     * 执行器自己装配好整个回答时走这里（代约那条路的 ResultCard 来自 {@link CareBookingService}
     * 写好的记录，编排层的 {@link #completionResult} 会把它覆盖成另一套口径，所以不能借用）。
     */
    abstract AgentTurnResponse finishWithoutModel(ConversationState state, AgentTurnResponse response);

    /** 本会话到目前为止的工具调用记录（回答里要带上）。 */
    abstract List<AgentTurnResponse.ToolTrace> traceList(ConversationState state);

    /** 预约完成路径：结果卡与口播全部由权威字段确定性生成。 */
    abstract AgentTurnResponse completionResult(ConversationState state);

    /** 交回漏斗继续下一步（信息不齐时）。 */
    abstract AgentTurnResponse advance(ConversationState state);

    /** 确认时发现卡上那个时段已经过去：退回重选日期。 */
    abstract AgentTurnResponse reaskAfterPassedSlot(ConversationState state);

    /** 工具执行失败时的统一出口（记痕、定阶段、给话术）。 */
    abstract AgentTurnResponse toolError(ConversationState state, RuntimeException error);

    /** 记完备忘/健康数值后把话头交回原来的办理上下文。 */
    abstract AgentTurnResponse memoHandoff(ConversationState state, String note);

    /**
     * 备忘落库成功后的回读话术（“已记下…到点提醒您”）。
     *
     * <p>收的是<b>真写进库的那几条</b>而不是要写的那几条：一句话说几天就是几条，回读要逐条念
     * 日期，老人才能发现其中哪天听错了；念的又必须是库里那几条，不能是执行器手里那份打算写的。
     */
    abstract String memoRecordedReply(List<MemoStore.MemoView> created, String repeatRule);

    /**
     * 多天备忘只写成了其中几条时的回读话术：写成的逐条念，没写成的如实说清楚是哪几天。
     *
     * <p>这条口子存在的理由就是"不许含糊"：一句话说了三天、第二天写库失败时，既不能把三天
     * 都算成记下了，也不能说"没记上"把已经落库的那条抹掉——老人照着回读去首页核对，看到的
     * 必须和话里说的一致。
     */
    abstract String memoPartlyRecordedReply(List<MemoStore.MemoView> created,
                                            List<LocalDateTime> missed, String repeatRule);

    /** 健康数值落库成功后的回读话术（“已记下：9月14日 21:30 血压 100 mmHg…”）。 */
    abstract String healthRecordedReply(String item, String valueText, String unit, LocalDateTime at);

    /**
     * 一句话报了几项、一次记下几条时的回读话术。
     *
     * <p>和备忘那边 {@link #memoRecordedReply} 同一条理由：收的是<b>真写进库的那几条</b>。
     * 卡上列着两行（"体温 36.5""心率 80"），回读就得逐条念出来，老人才能发现其中哪个听错了；
     * 念的又必须是库里那几条，不能是执行器手里那份打算写的。
     */
    abstract String healthRecordedReply(List<HealthRecordStore.RecordView> recorded);

    /** 这张卡是不是「照草稿开新预约」、而且草稿里那个时段已经过去了。 */
    abstract boolean draftSlotExpired(ConversationState state);

    /** 草稿信息是否齐全（原 {@code ready(state)}）。 */
    abstract boolean draftComplete(ConversationState state);

    /** 预约办完之后能做的事（查看地图、查看预约…）。 */
    abstract List<QuickReply> bookedActions(ConversationState state);

    /** 被打断过的那件事的「继续办理」按钮。 */
    abstract List<QuickReply> resumeInterruptedReplies(ConversationState state);

    /** 记一次工具调用的事实（失败时记下原因再抛出）。 */
    abstract <T> T callTool(ConversationState state, String toolName, Map<String, ?> parameters,
                            Supplier<T> operation);

    /** 把这次预约沉淀进长期记忆。 */
    abstract void rememberBookingPreferences(ConversationState state);

    /** 这次要发给家属的复诊安排文本。 */
    abstract String notificationMessage(ConversationState state);

    /** 一份安排的短说法（“9月16日 09:00，市一 心内科”）。 */
    abstract String managedShort(AppointmentRecordStore.AppointmentView plan);

    /** 代约完成后的按钮。 */
    abstract List<QuickReply> caregiverBookedActions(String subjectName);

    /** 业务执行器自己接住异常时，把这次失败记下来（不然库里就只剩一次没头没尾的写）。 */
    abstract void recordToolFailure(ConversationState state, RuntimeException error);

    /** 就诊人姓名（查不到就退成 userId）。 */
    abstract String elderName(String userId);
}
