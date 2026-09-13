package com.team.silveragent.application;

import com.team.silveragent.domain.model.AgentTurnResponse;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.team.silveragent.application.ConfirmationSupport.q;

/**
 * 一次「确认通过之后」的分派：动作类型 → 该由谁执行。
 *
 * <p><b>唯一的判据是 {@link ConfirmationService.PendingOperation#kind()}。</b>那是在发卡那一刻
 * 就定下、随凭据一起进快照、随凭据一起作废的东西。重构之前这里看的是 {@code pendingAction}、
 * {@code caregiving()} 甚至卡上有没有过期时段，都是可变的会话字段——老人一句新话改掉其中一个，
 * 同一张卡点的就是另一件事了。改完之后，{@code pendingAction} 只剩「给界面看」的用途。
 *
 * <p><b>「谁的业务链路来执行」也在那个类型里。</b>本人自办与代他人办理曾经合并成一个
 * {@code BOOKING}（或 {@code CANCEL_APPOINTMENTS}），再在执行这一刻看 {@code state.caregiving()}
 * 决定走哪个执行器——那等于把「这次确认动谁的业务」这件已经冻在凭据里的事，重新交给一个
 * 会话字段。现在它们是四个不同的取值（{@code BOOKING} / {@code BOOKING_CAREGIVER} /
 * {@code CANCEL_APPOINTMENTS} / {@code CANCEL_APPOINTMENTS_CAREGIVER}），下面这个 switch
 * 里<b>一次都不再出现 {@code caregiving()}</b>——看一眼就能确认这件事。
 *
 * <p>这个类<b>不写库、也不拼业务话术</b>：它只负责「轮到谁」，以及把执行器交回来的事实
 * 翻成对老人说的话（取消那两条本来就是这么长的——执行器只说“取消了几条、要不要接着办”，
 * 措辞留在编排层）。业务写操作一件都不在这里。
 *
 * <p>分派的先后次序本身是有意义的，逐条与重构前对齐：备忘和代约取消各自连「拒绝」一起接管
 * （它们被拒时也要把自己那份草稿收干净）；剩下两类先处理拒绝，再处理执行。
 */
@Component
final class ConfirmationDispatcher {
    private final BookingExecutor bookings;
    private final MemoExecutor memos;
    private final ManagedCancelExecutor managedCancels;
    private final CaregiverBookingExecutor caregiverBookings;
    private final CancellationExecutor cancellations;

    ConfirmationDispatcher(BookingExecutor bookings, MemoExecutor memos, ManagedCancelExecutor managedCancels,
                           CaregiverBookingExecutor caregiverBookings, CancellationExecutor cancellations) {
        this.bookings = bookings;
        this.memos = memos;
        this.managedCancels = managedCancels;
        this.caregiverBookings = caregiverBookings;
        this.cancellations = cancellations;
    }

    /**
     * 执行一次已经通过校验、且凭据<b>已经被消费</b>的确认。
     *
     * <p>走到这里就意味着不会再有第二次：凭据在 {@code ConfirmationService.consume} 里当场作废，
     * 重复点同一个卡拿不到 {@code operation}，压根到不了这一步。
     */
    AgentTurnResponse dispatch(ConversationState state, ConfirmationService.PendingOperation operation,
                               boolean approved, ConfirmationSupport support) {
        switch (operation.kind()) {
            case MEMO -> {
                return approved ? memos.commit(state, support) : memos.refuse(state, support);
            }
            case CANCEL_MANAGED -> {
                return approved ? managedCancels.commit(state, operation, support)
                        : managedCancels.refuse(state, operation, support);
            }
            // 取消这一类：拒绝走"原预约保留"（本人与代他人办理共用同一条收场），确认时分两条路。
            case CANCEL_APPOINTMENTS -> {
                return approved ? executeCancellation(state, operation, support)
                        : abandonCancellation(state, cancellations.abandon(state), support);
            }
            case CANCEL_APPOINTMENTS_CAREGIVER -> {
                return approved ? caregiverBookings.cancel(state, operation, support)
                        : abandonCancellation(state, cancellations.abandon(state), support);
            }
            // 开新预约这一类。卡是早先生成的，老人可能过了很久才按「确认办理」，甚至隔了一夜。
            // 卡上那个「今天 09:00」到这时候已经过去了——这种时段不能再写进预约，代他人办理
            // 也绕不过去。
            //
            // 只挡「照着草稿开新预约」这一条路：完成过的会话仍然留着当初那份 selectedSlot，
            // 取消的是老预约、跟这个时段没关系，拿它去拦会让老人越等越取消不了。
            case BOOKING, BOOKING_CAREGIVER -> {
                if (!approved) return bookings.refuse(state, support);
                if (support.draftSlotExpired(state)) return support.reaskAfterPassedSlot(state);
                return book(state, operation, support);
            }
        }
        // switch 覆盖了全部取值，走到这里说明有人加了新的 Kind 却没在这里接上。
        throw new IllegalStateException("没有为确认类型 " + operation.kind() + " 接上执行器");
    }

    /** 照草稿开新预约：本人自办与代他人办理各走自己那条真实链路，由类型选，不看会话状态。 */
    private AgentTurnResponse book(ConversationState state, ConfirmationService.PendingOperation operation,
                                   ConfirmationSupport support) {
        try {
            return operation.kind() == ConfirmationService.PendingOperation.Kind.BOOKING_CAREGIVER
                    ? caregiverBookings.book(state, support) : bookings.book(state, support);
        } catch (RuntimeException error) {
            return support.toolError(state, error);
        }
    }

    /**
     * 备忘还有两条不经确认卡的直写入口（老人明确托付、追问补齐时间之后）。它们落的是同一条库、
     * 回读的是同一套话术，所以借道这里进同一个执行器，而不是在编排层再写一份。
     *
     * <p>这两条路<b>没有凭据</b>，所以不校验、也不消费凭据——它们本来就不需要老人点头。
     */
    AgentTurnResponse writeMemo(ConversationState state, String text, java.time.LocalDateTime at,
                                String repeatRule, ConfirmationSupport support) {
        return memos.write(state, text, at, repeatRule, support);
    }

    private AgentTurnResponse executeCancellation(ConversationState state,
                                                  ConfirmationService.PendingOperation operation,
                                                  ConfirmationSupport support) {
        try {
            // 整批的归属校验、状态校验和那条事务都在预约工具里（cancelAll），执行器只负责
            // 「什么时候轮到它」，以及执行完之后会话该停在哪一页。
            CancellationExecutor.Result done = cancellations.cancel(state, operation.cancellationTargets());
            int count = done.targetIds().size();
            String cancelled = count == 1 ? "这条已确认预约" : "这" + count + "条已确认预约";
            if (done.resumedInterrupted()) {
                return support.respondWithPlan(state,
                        cancelled + "已经取消，原模拟号源已释放。要继续刚才未完成的办理吗？",
                        support.resumeInterruptedReplies(state));
            }
            return support.respondWithPlan(state, cancelled + "已经取消，原模拟号源已经释放。",
                    List.of(q("查询我的预约", "QUERY_APPOINTMENTS", ""), q("重新办理", "NEW_BOOKING", "")));
        } catch (RuntimeException error) {
            return support.toolError(state, error);
        }
    }

    private AgentTurnResponse abandonCancellation(ConversationState state, CancellationExecutor.Result done,
                                                  ConfirmationSupport support) {
        if (done.resumedInterrupted()) {
            return support.respondWithPlan(state, "好的，原预约已经保留。您要继续刚才的复诊办理吗？",
                    support.resumeInterruptedReplies(state));
        }
        return support.respondWithPlan(state, "好的，原预约已经保留，没有执行取消操作。",
                List.of(q("查询我的预约", "QUERY_APPOINTMENTS", ""), q("重新办理", "NEW_BOOKING", "")));
    }
}
