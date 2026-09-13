package com.team.silveragent.application;

import com.team.silveragent.application.ConfirmationService.PendingOperation;
import com.team.silveragent.application.care.CareBookingService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.tool.AppointmentTool;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 代约取消执行器：<b>取消的必须是卡片上那一条</b>，别的什么都不许动。
 *
 * <p>这个类是四B那一轮补回来的。改之前，执行时会按"当前那份代约安排"重新查一遍要取消谁——
 * 那个口径本身没错，错在用它在<b>执行这一刻</b>重新决定对象。发卡之后别人又代约了一条更早的，
 * 同一张卡按下去取消的就是那条更早的，而界面从头到尾写的是原来那条：卡片与执行分了家。
 * 现在要取消的是凭据上冻结的 {@code appointmentId}，查询只用来取展示与通知要用的字段。
 *
 * <p>归属、是否还存在、是否还能取消，全在 {@code appointmentTool.cancel} 那条 SQL 里
 * （{@code id + user_id + status='CONFIRMED'} 三条同时满足才改）。这里用替身，只问执行器
 * 自己的职责：按的是哪一条、失败时说什么、有没有误发通知、有没有去找一条"替代的当前预约"。
 */
class ManagedCancelExecutorTests {
    private final AppointmentTool appointmentTool = mock(AppointmentTool.class);
    private final CareBookingService careBooking = mock(CareBookingService.class);
    private final AppointmentRecordStore records = mock(AppointmentRecordStore.class);
    private final ManagedCancelExecutor executor = new ManagedCancelExecutor(appointmentTool, careBooking, records);
    private final ConfirmationSupport support = mock(ConfirmationSupport.class);

    private ConversationState conversation() {
        return new ConversationState("conversation", "user-001");
    }

    private AppointmentRecordStore.AppointmentView arranged(String appointmentId, LocalDate date, LocalTime time) {
        return new AppointmentRecordStore.AppointmentView(
                appointmentId, "市第一医院（模拟）", "心内科",
                date, time,
                LocalDateTime.of(date, time.minusHours(1)), "打车",
                "复诊及出发提醒已创建", "已通知女儿小丽", List.of("身份证"),
                List.of("身份证"), "CONFIRMED", LocalDateTime.of(2026, 9, 13, 10, 0),
                "user-f001", "女儿 小丽", null);
    }

    /** 卡片上冻结的那一条。 */
    private AppointmentRecordStore.AppointmentView frozenPlan() {
        return arranged("APPT-1", LocalDate.of(2026, 9, 16), LocalTime.of(9, 0));
    }

    /** 别人后来代约的、时间更早的一条——它绝不该被这张卡取消。 */
    private AppointmentRecordStore.AppointmentView laterArrangedButEarlierPlan() {
        return arranged("APPT-2", LocalDate.of(2026, 9, 14), LocalTime.of(8, 0));
    }

    private PendingOperation card(String... targets) {
        return new PendingOperation("credential", PendingOperation.Kind.CANCEL_MANAGED, List.of(targets));
    }

    private void arrangeAnExistingPlan() {
        when(records.allFor("user-001")).thenReturn(List.of(frozenPlan()));
        when(support.elderName(anyString())).thenReturn("王阿姨");
        when(support.managedShort(any())).thenReturn("2026年9月16日 09:00，市第一医院（模拟） 心内科");
    }

    /**
     * 发卡之后库里多出一条更早的代约：确认时取消的仍然是卡片上那一条。
     *
     * <p>这正是四B审查指出的那个缺口——按列表顺序取"当前那一条"的话，这里取消的会是 APPT-2。
     */
    @Test
    void aNewerEarlierArrangedPlanDoesNotBecomeTheTarget() {
        when(records.allFor("user-001")).thenReturn(List.of(laterArrangedButEarlierPlan(), frozenPlan()));
        when(support.elderName(anyString())).thenReturn("王阿姨");
        when(support.managedShort(any())).thenReturn("2026年9月16日 09:00，市第一医院（模拟） 心内科");

        executor.commit(conversation(), card("APPT-1"), support);

        verify(appointmentTool).cancel("conversation", "APPT-1", "user-001");
        verify(appointmentTool, never()).cancel(any(), eq("APPT-2"), any());
    }

    /** 写库失败：原预约保留、安排者不被打扰、会话收在「已完成」，并且这次失败要留痕。 */
    @Test
    void aFailedCancellationKeepsTheAppointmentAndDoesNotNotifyTheArranger() {
        arrangeAnExistingPlan();
        IllegalStateException failure = new IllegalStateException("没有找到可取消的预约");
        org.mockito.Mockito.doThrow(failure).when(appointmentTool).cancel(any(), any(), any());

        executor.commit(conversation(), card("APPT-1"), support);

        verify(careBooking, never()).notifyCaregiver(any(), any(), anyString(), anyString());
        verify(support).recordToolFailure(any(), any());
        verify(support).respondSimple(any(), contains("原预约保留"), any());
        verify(support, never()).respondSimple(any(), contains("已取消"), any());
    }

    /** 成功时：通知安排者一次，会话收在「已取消」。 */
    @Test
    void aSuccessfulCancellationNotifiesTheArrangerExactlyOnce() {
        arrangeAnExistingPlan();

        executor.commit(conversation(), card("APPT-1"), support);

        verify(appointmentTool).cancel("conversation", "APPT-1", "user-001");
        verify(careBooking).notifyCaregiver(eq("user-f001"), eq("user-001"),
                contains("已取消您代约的复诊"), eq("cancel"));
    }

    /** 老人点了「保留预约」：一个写操作都不做，也不通知任何人。 */
    @Test
    void refusingCancelsNothingAndNotifiesNobody() {
        arrangeAnExistingPlan();

        executor.refuse(conversation(), card("APPT-1"), support);

        verify(appointmentTool, never()).cancel(any(), any(), any());
        verify(careBooking, never()).notifyCaregiver(any(), any(), anyString(), anyString());
        verify(support).respondSimple(any(), contains("仍然保留"), any());
    }

    /**
     * 卡上没有<b>恰好一个</b>目标：一个写操作都不做。
     *
     * <p>空目标来自旧快照或签发缺陷；两个目标说明这张卡的语义不清。两种都不许 {@code get(0)}
     * 取第一个顶上——宁可什么都不取消，也不能取消一条卡片没说过的。
     */
    @Test
    void aCardWithoutExactlyOneTargetCancelsNothing() {
        when(records.allFor("user-001")).thenReturn(List.of(frozenPlan(), laterArrangedButEarlierPlan()));

        executor.commit(conversation(), card(), support);
        executor.commit(conversation(), card("APPT-1", "APPT-2"), support);

        verify(appointmentTool, never()).cancel(any(), any(), any());
        verify(careBooking, never()).notifyCaregiver(any(), any(), anyString(), anyString());
        verify(support, org.mockito.Mockito.times(2))
                .respondSimple(any(), contains("没有指定要取消的安排"), any());
    }

    /**
     * 冻结的那一条已经取消、或者已不属于这位老人：一条替代的预约都不许取消。
     *
     * <p>库里这会儿确实还有一条"当前代约"（APPT-2），权威层也认它；但这张卡不是为它签的。
     * 执行器该做的是把失败如实说出来，然后停手——不是悄悄换个对象把事办了。
     */
    @Test
    void whenTheFrozenTargetIsGoneNoSubstituteIsCancelled() {
        when(records.allFor("user-001")).thenReturn(List.of(laterArrangedButEarlierPlan()));
        org.mockito.Mockito.doThrow(new IllegalStateException("没有找到可取消的预约"))
                .when(appointmentTool).cancel(any(), any(), any());

        executor.commit(conversation(), card("APPT-1"), support);

        // 只碰过卡片上写的那一条；那条更早的代约一动没动。
        verify(appointmentTool).cancel("conversation", "APPT-1", "user-001");
        verify(appointmentTool, never()).cancel(any(), eq("APPT-2"), any());
        verify(careBooking, never()).notifyCaregiver(any(), any(), anyString(), anyString());
        verify(support, never()).respondSimple(any(), contains("已取消"), any());
    }

    /** 展示字段取不到（不属于这个人），只要权威层放行，取消照常完成。 */
    @Test
    void aMissingDisplayRecordDoesNotBlockTheAuthoritativeCancel() {
        when(records.allFor("user-001")).thenReturn(List.of());

        AgentTurnResponse response = executor.commit(conversation(), card("APPT-1"), support);

        // 替身返回 null，这里只关心它有没有走那条「取消成功」的出口。
        assertThat(response).isNull();
        verify(appointmentTool).cancel("conversation", "APPT-1", "user-001");
        verify(support).respondSimple(any(), contains("已取消"), any());
    }
}
