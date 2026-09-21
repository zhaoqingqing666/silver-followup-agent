package com.team.silveragent.application;

import com.team.silveragent.agent.AgentRole;
import com.team.silveragent.application.ConfirmationService.PendingOperation;
import com.team.silveragent.domain.model.AgentTurnResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 分派本身：一张已经通过的确认卡，到底交给谁执行。
 *
 * <p>这里钉的是一条<b>不能被别的字段改变</b>的性质：执行的是哪个执行器，只由签发时冻在
 * {@link PendingOperation} 里的 {@code Kind} 决定。重构之前这里读的是 {@code pendingAction}、
 * 是不是代他人办理、卡上的时段有没有过期——全是可变的会话字段。老人按的是那张卡，
 * 卡上写的是什么，执行的就必须是什么；中间任何一句新话、任何一个被改掉的字段，都改不动它。
 *
 * <p><b>「谁在办」也在类型里。</b>本人自办与代他人办理是四个不同的 {@code Kind}
 * （{@code BOOKING} / {@code BOOKING_CAREGIVER} / {@code CANCEL_APPOINTMENTS} /
 * {@code CANCEL_APPOINTMENTS_CAREGIVER}），所以这个类里判「走哪条链路」时<b>一次都不看
 * {@code caregiving()}</b>。下面有两条用例专门把两个方向都钉住：会话说是本人、类型说是代办，
 * 就走代办；会话说是代办、类型说是本人，就走本人。判据只有一个，而且它是冻住的。
 *
 * <p>用的全是替身：真实的执行器里每一次调用都意味着一次写库，而这里要问的问题只是
 * 「轮到谁」，不该真写。分派之后的业务行为另有端到端的测试。
 */
class ConfirmationDispatcherTests {
    private final BookingExecutor bookings = mock(BookingExecutor.class);
    private final MemoExecutor memos = mock(MemoExecutor.class);
    private final ManagedCancelExecutor managedCancels = mock(ManagedCancelExecutor.class);
    private final CaregiverBookingExecutor caregiverBookings = mock(CaregiverBookingExecutor.class);
    private final CancellationExecutor cancellations = mock(CancellationExecutor.class);
    private final HealthRecordExecutor healthRecords = mock(HealthRecordExecutor.class);
    private final ConfirmationSupport support = mock(ConfirmationSupport.class);
    private final ConfirmationDispatcher dispatcher = new ConfirmationDispatcher(
            bookings, memos, managedCancels, caregiverBookings, cancellations, healthRecords);

    private ConversationState conversation() {
        return new ConversationState("conversation", "user-001");
    }

    private PendingOperation operation(PendingOperation.Kind kind, String... targets) {
        return new PendingOperation("credential", kind, List.of(targets));
    }

    /** 七个 Kind，各归各家。 */
    @Test
    void eachKindGoesToItsOwnExecutor() {
        when(cancellations.cancel(any(), anyList())).thenReturn(new CancellationExecutor.Result(List.of(), false));

        dispatcher.dispatch(conversation(), operation(PendingOperation.Kind.BOOKING), true, support);
        verify(bookings).book(any(), any());
        verify(caregiverBookings, never()).book(any(), any());

        dispatcher.dispatch(conversation(), operation(PendingOperation.Kind.BOOKING_CAREGIVER), true, support);
        verify(caregiverBookings).book(any(), any());

        dispatcher.dispatch(conversation(), operation(PendingOperation.Kind.MEMO), true, support);
        verify(memos).commit(any(), any());

        // 健康记录与备忘同一族（都往自己的表里写一条），但落到各自的执行器：
        // 记一条备忘和记一次血压不是一件事，写错地方的后果是一条看起来正常的错数据
        dispatcher.dispatch(conversation(), operation(PendingOperation.Kind.HEALTH_RECORD), true, support);
        verify(healthRecords).commit(any(), any());
        verify(memos, org.mockito.Mockito.times(1)).commit(any(), any());

        dispatcher.dispatch(conversation(), operation(PendingOperation.Kind.CANCEL_MANAGED, "appt-1"), true, support);
        verify(managedCancels).commit(any(), any(), any());

        dispatcher.dispatch(conversation(), operation(PendingOperation.Kind.CANCEL_APPOINTMENTS, "appt-1"), true, support);
        verify(cancellations).cancel(any(), anyList());

        dispatcher.dispatch(conversation(), operation(PendingOperation.Kind.CANCEL_APPOINTMENTS_CAREGIVER, "appt-1"),
                true, support);
        verify(caregiverBookings).cancel(any(), any(), any());
        // 照护端这张取消卡一次都不许走老人端那条整批取消：账要记在建约人身上，不是长辈自己。
        verify(cancellations, org.mockito.Mockito.times(1)).cancel(any(), anyList());
    }

    /**
     * 没有任何一个 Kind 会「落到」预约那条路上去。
     *
     * <p>这是分派里最要紧的一条：{@code BOOKING} 是唯一一个会真去开一条新预约的类型，
     * 一个认不出来的、或者被凑合着默认过去的类型落到它身上，就是一次凭空的挂号。
     */
    @Test
    void noKindFallsThroughToTheBookingExecutor() {
        when(cancellations.cancel(any(), anyList())).thenReturn(new CancellationExecutor.Result(List.of(), false));
        when(cancellations.abandon(any())).thenReturn(new CancellationExecutor.Result(List.of(), false));

        dispatcher.dispatch(conversation(), operation(PendingOperation.Kind.MEMO), true, support);
        dispatcher.dispatch(conversation(), operation(PendingOperation.Kind.HEALTH_RECORD), true, support);
        dispatcher.dispatch(conversation(), operation(PendingOperation.Kind.CANCEL_MANAGED, "appt-1"), true, support);
        dispatcher.dispatch(conversation(), operation(PendingOperation.Kind.CANCEL_APPOINTMENTS, "appt-1"), true, support);
        dispatcher.dispatch(conversation(), operation(PendingOperation.Kind.CANCEL_APPOINTMENTS_CAREGIVER, "appt-1"),
                true, support);
        // 连「拒绝」也不许拐到预约那条路上去。
        dispatcher.dispatch(conversation(), operation(PendingOperation.Kind.CANCEL_APPOINTMENTS, "appt-1"), false, support);

        verify(bookings, never()).book(any(), any());
        verify(bookings, never()).refuse(any(), any());
    }

    /** 拒绝确认：一个业务执行器都不写库。备忘与代约取消由它们自己的 refuse 收尾，也不写。 */
    @Test
    void aRefusedConfirmationExecutesNothing() {
        when(cancellations.abandon(any())).thenReturn(new CancellationExecutor.Result(List.of(), false));

        dispatcher.dispatch(conversation(), operation(PendingOperation.Kind.BOOKING), false, support);

        verify(bookings).refuse(any(), any());
        verify(bookings, never()).book(any(), any());
        verify(memos, never()).commit(any(), any());
        verify(managedCancels, never()).commit(any(), any(), any());
        verify(cancellations, never()).cancel(any(), anyList());

        dispatcher.dispatch(conversation(), operation(PendingOperation.Kind.MEMO), false, support);
        verify(memos).refuse(any(), any());
        verify(memos, never()).commit(any(), any());

        // 健康记录也是：点了「先不用」，那条数值一条都不许落库
        dispatcher.dispatch(conversation(), operation(PendingOperation.Kind.HEALTH_RECORD), false, support);
        verify(healthRecords).refuse(any(), any());
        verify(healthRecords, never()).commit(any(), any());

        dispatcher.dispatch(conversation(), operation(PendingOperation.Kind.CANCEL_MANAGED, "appt-1"), false, support);
        verify(managedCancels).refuse(any(), any(), any());
        verify(managedCancels, never()).commit(any(), any(), any());

        // 两条取消路径的「拒绝」都是「原预约保留」：代他人办理也一样，一条都不能被取消。
        dispatcher.dispatch(conversation(), operation(PendingOperation.Kind.CANCEL_APPOINTMENTS_CAREGIVER, "appt-1"),
                false, support);
        verify(caregiverBookings, never()).cancel(any(), any(), any());
        verify(cancellations, never()).cancel(any(), anyList());
    }

    /** 放弃整批取消走 abandon（保留原预约），绝不去执行 cancel。 */
    @Test
    void abandoningABatchCancellationNeverCallsTheCancelExecution() {
        when(cancellations.abandon(any())).thenReturn(new CancellationExecutor.Result(List.of(), false));

        dispatcher.dispatch(conversation(), operation(PendingOperation.Kind.CANCEL_APPOINTMENTS, "appt-1"), false, support);

        verify(cancellations).abandon(any());
        verify(cancellations, never()).cancel(any(), anyList());
    }

    /**
     * 会话字段怎么改都换不掉已经签发的那件事。
     *
     * <p>这里把 {@code pendingAction} 改成「正在取消」、{@code appointmentId} 改成一个别的预约号，
     * 再按那张 {@code BOOKING} 卡点头：执行的仍然是开预约这一步，取消一次都没发生。
     */
    @Test
    void aMutatedSessionFieldCannotChangeWhatTheIssuedCardDoes() {
        ConversationState state = conversation();
        state.pendingAction = "CANCEL_EXISTING";
        state.appointmentId = "someone-elses-appointment";

        dispatcher.dispatch(state, operation(PendingOperation.Kind.BOOKING), true, support);

        verify(bookings).book(any(), any());
        verify(cancellations, never()).cancel(any(), anyList());
        verify(bookings, never()).refuse(any(), any());
    }

    /**
     * 会话<b>说自己是代他人办理</b>，但类型是本人自办：就走本人那条链路。
     *
     * <p>方向反过来同样成立（下一条）。两条合起来说明的是同一件事：路由只看类型。
     */
    @Test
    void aSelfKindStaysOnTheElderChainEvenInsideACaregiverSession() {
        ConversationState state = conversation();
        state.actorUserId = "user-f001";
        state.actorRole = AgentRole.FAMILY;
        state.relationLabel = "女儿 小丽";

        dispatcher.dispatch(state, operation(PendingOperation.Kind.BOOKING), true, support);
        verify(bookings).book(any(), any());
        verify(caregiverBookings, never()).book(any(), any());
    }

    /**
     * 类型说是代他人办理，会话里却写着本人：仍旧走照护端那条链路。
     *
     * <p>这一条是真正要防的那个方向——照护端的两条路会写代约归属、会通知其他照护者，
     * 走错了就是「以别人的名义动了一份不属于他的安排」。执行的是哪条链路，只能由签发时
     * 冻下来的类型决定，不能由执行这一刻会话长什么样决定。
     */
    @Test
    void aCaregiverKindUsesTheCaregiverChainEvenWhenTheSessionLooksLikeTheElder() {
        ConversationState state = conversation();
        state.actorUserId = "user-001";
        state.actorRole = AgentRole.ELDER;

        dispatcher.dispatch(state, operation(PendingOperation.Kind.BOOKING_CAREGIVER), true, support);
        verify(caregiverBookings).book(any(), any());
        verify(bookings, never()).book(any(), any());

        dispatcher.dispatch(state, operation(PendingOperation.Kind.CANCEL_APPOINTMENTS_CAREGIVER, "appt-1"), true, support);
        verify(caregiverBookings).cancel(any(), any(), any());
        verify(cancellations, never()).cancel(any(), anyList());
    }

    /**
     * 代他人办理点了「返回修改」时，走的还是老人端那条拒绝出口。
     *
     * <p>照着旧代码的次序：拒绝这一支在「是不是代他人办理」之前就处理完了，代约链路根本轮不到。
     * 这是行为保持，不是新规矩——代他人办理的拒绝只需要复位会话，没有第二种含义。
     */
    @Test
    void aRefusedCaregiverBookingStillUsesThePlainRefusal() {
        ConversationState state = conversation();
        state.actorUserId = "user-f001";
        state.actorRole = AgentRole.FAMILY;

        dispatcher.dispatch(state, operation(PendingOperation.Kind.BOOKING_CAREGIVER), false, support);

        verify(bookings).refuse(any(), any());
        verify(caregiverBookings, never()).book(any(), any());
        verify(caregiverBookings, never()).cancel(any(), any(), any());
    }

    /** 卡上那个时段已经过去：先退回重选日期，一个写操作都不做。 */
    @Test
    void anExpiredDraftSlotIsReaskedBeforeAnyWrite() {
        when(support.draftSlotExpired(any())).thenReturn(true);

        dispatcher.dispatch(conversation(), operation(PendingOperation.Kind.BOOKING), true, support);
        dispatcher.dispatch(conversation(), operation(PendingOperation.Kind.BOOKING_CAREGIVER), true, support);

        verify(support, org.mockito.Mockito.times(2)).reaskAfterPassedSlot(any());
        verify(bookings, never()).book(any(), any());
        verify(caregiverBookings, never()).book(any(), any());
        verifyNoInteractions(cancellations);
    }

    /** 执行器抛出来的一切，都从工具错误那条出口走，不在这里另立一套说法。 */
    @Test
    void anythingAnExecutorThrowsLeavesThroughTheToolErrorExit() {
        when(bookings.book(any(), any())).thenThrow(new IllegalStateException("库写不进去"));
        when(support.toolError(any(), any())).thenReturn(mock(AgentTurnResponse.class));

        dispatcher.dispatch(conversation(), operation(PendingOperation.Kind.BOOKING), true, support);

        verify(support).toolError(any(), any());
    }

    /** 备忘的两条直写入口没有凭据，也照样进同一个执行器。 */
    @Test
    void aDirectMemoWriteReachesTheSameExecutorWithoutACredential() {
        dispatcher.writeMemo(conversation(), "内容", null, null, support);

        verify(memos).write(any(), any(), any(), any(), any());
        verifyNoInteractions(bookings, cancellations, managedCancels, caregiverBookings);
    }
}
