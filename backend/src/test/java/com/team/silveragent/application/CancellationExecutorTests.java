package com.team.silveragent.application;

import com.team.silveragent.domain.tool.AppointmentTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 取消执行器的行为：谁来决定取消哪几条、取消完会话停在哪一页。
 *
 * <p>归属校验和「整批要么全成要么全不成」<b>不在这里</b>，它们是 {@code cancelAll} 的事（那条 SQL
 * 自带 {@code user_id} 与 {@code status='CONFIRMED'}，并且先整批查一遍再在一个事务里逐条取消）。
 * 这里钉的是另一半：执行器要把目标<b>原样</b>交给它，不要在中间自己再筛一遍——多一套口径，
 * 就有机会把卡片上没写的那几条也取消掉。
 */
class CancellationExecutorTests {
    private final AppointmentTool appointmentTool = mock(AppointmentTool.class);
    private final CancellationExecutor executor = new CancellationExecutor(appointmentTool);

    private ConversationState conversation() {
        return new ConversationState("conversation", "user-001");
    }

    /** 重复的预约号要去重，但一条都不能少、也不能多：整批一次交给那条事务，中间不再自己筛。 */
    @Test
    void theWholeBatchIsHandedToTheAtomicCancelInOneGo() {
        ConversationState state = conversation();

        CancellationExecutor.Result result = executor.cancel(state,
                List.of("appt-001", "appt-002", "appt-001"));

        verify(appointmentTool).cancelAll("conversation", List.of("appt-001", "appt-002"), "user-001");
        assertThat(result.targetIds()).containsExactly("appt-001", "appt-002");
    }

    /** 说出不取消哪条，就不该被当成空操作放过去：照样抛，由编排层转成工具错误。 */
    @Test
    void anEmptyTargetSetIsAnExecutionFailureNotANoOp() {
        ConversationState state = conversation();

        assertThatThrownBy(() -> executor.cancel(state, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有可取消的预约");
        assertThatThrownBy(() -> executor.cancel(state, null))
                .isInstanceOf(IllegalStateException.class);
        verify(appointmentTool, never()).cancelAll(any(), anyList(), any());
    }

    /** 取消掉的就是本会话正在办的那一条时，会话不能再拿着一个已经不存在的预约号往下走。 */
    @Test
    void theSessionStopsHoldingAnAppointmentThatWasJustCancelled() {
        ConversationState state = conversation();
        state.appointmentId = "appt-001";
        state.originalAppointmentId = "appt-000";

        executor.cancel(state, List.of("appt-001"));

        assertThat(state.appointmentId).isNull();
        assertThat(state.originalAppointmentId).isEqualTo("appt-000");
    }

    /** 取消的是别人那条（本会话办的是另一条）时，本会话的预约号必须留着。 */
    @Test
    void anUnrelatedAppointmentSurvivesTheBatch() {
        ConversationState state = conversation();
        state.appointmentId = "appt-001";

        executor.cancel(state, List.of("appt-002"));

        assertThat(state.appointmentId).isEqualTo("appt-001");
    }

    @Test
    void aFinishedCancellationSettlesAsCancelled() {
        ConversationState state = conversation();
        state.pendingAction = "CANCEL_EXISTING";
        state.pendingAppointmentIds = List.of("appt-001");

        CancellationExecutor.Result result = executor.cancel(state, List.of("appt-001"));

        assertThat(result.resumedInterrupted()).isFalse();
        assertThat(state.stage).isEqualTo(ConversationState.Stage.CANCELLED);
        assertThat(state.taskStatus).isEqualTo(ConversationState.TaskStatus.CANCELLED);
        assertThat(state.pendingAction).isEqualTo("CREATE");
        assertThat(state.pendingAppointmentIds).isEmpty();
    }

    /**
     * 办理被打断过就退回去接着办，而且<b>不动任务状态</b>——那一步由那条路自己的出口收尾，
     * 在这里多改一脚会把「还在办理中」的任务标成已取消。
     */
    @Test
    void anInterruptedBookingIsResumedWithoutTouchingItsTaskStatus() {
        ConversationState state = conversation();
        state.interruptedStage = ConversationState.Stage.ASK_DATE;
        state.taskStatus = ConversationState.TaskStatus.ACTIVE;

        CancellationExecutor.Result result = executor.cancel(state, List.of("appt-001"));

        assertThat(result.resumedInterrupted()).isTrue();
        assertThat(state.stage).isEqualTo(ConversationState.Stage.ASK_DATE);
        assertThat(state.taskStatus).isEqualTo(ConversationState.TaskStatus.ACTIVE);
    }

    /** 老人点了「保留预约」：什么都不执行，也不动那个本来就该留着的预约。 */
    @Test
    void abandoningCancelsNothingAndKeepsTheAppointment() {
        ConversationState state = conversation();
        state.pendingAction = "CANCEL_EXISTING";
        state.pendingAppointmentId = "appt-001";
        state.pendingAppointmentIds = List.of("appt-001");
        state.appointmentId = "appt-001";

        CancellationExecutor.Result result = executor.abandon(state);

        verify(appointmentTool, never()).cancelAll(any(), anyList(), any());
        assertThat(result.resumedInterrupted()).isFalse();
        assertThat(state.appointmentId).isEqualTo("appt-001");
        assertThat(state.pendingAction).isEqualTo("CREATE");
        assertThat(state.pendingAppointmentIds).isEmpty();
        // 保留的终态是「已完成」，和取消的「已取消」不是一回事。
        assertThat(state.stage).isEqualTo(ConversationState.Stage.COMPLETED);
        assertThat(state.taskStatus).isEqualTo(ConversationState.TaskStatus.COMPLETED);
    }

    @Test
    void abandoningAnInterruptedBookingGoesBackToWhereItWasInterrupted() {
        ConversationState state = conversation();
        state.interruptedStage = ConversationState.Stage.CONFIRM_SLOT;
        state.taskStatus = ConversationState.TaskStatus.ACTIVE;

        assertThat(executor.abandon(state).resumedInterrupted()).isTrue();
        assertThat(state.stage).isEqualTo(ConversationState.Stage.CONFIRM_SLOT);
        assertThat(state.taskStatus).isEqualTo(ConversationState.TaskStatus.ACTIVE);
    }
}
