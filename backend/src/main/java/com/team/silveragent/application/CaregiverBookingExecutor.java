package com.team.silveragent.application;

import com.team.silveragent.application.care.CareBookingService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.model.AgentTurnResponse.ResultCard;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.team.silveragent.application.ConfirmationSupport.q;

/**
 * 「家属/志愿者替长辈确认」这一类已确认操作的执行器。
 *
 * <p>老人端那两条路（{@link BookingExecutor}、{@link CancellationExecutor}）把预约直接建到
 * {@code state.userId} 名下，不写 {@code arranged_by}、也不跑协同通知——界面上看不出这份安排
 * 是别人代约的。代为办理必须走照护端那条真实链路，所以它落在这里：<b>两个方法（新约/改期、
 * 取消）共用同一个入口，因为它们共用的东西才是关键</b>——同一条归属校验、同一条真实号源、
 * 同一套协同通知，都由 {@link CareBookingService} 提供；拆成两个类只会把这份共用拆散。
 *
 * <p><b>它不看 {@code pendingAction}。</b>重构前这里判的是
 * {@code "CANCEL"/"CANCEL_EXISTING"} 两个字符串——但 {@code "CANCEL"} 全仓没有任何一处赋值，
 * 而 {@code "CANCEL_EXISTING"} 只有 {@code prepareExistingCancellation} 会写，且它紧接着签发的
 * 就是 {@code CANCEL_APPOINTMENTS}。也就是说那半个条件一直是死的，改成按签发时定下的
 * {@code Kind} 分派，行为逐条等价，还去掉了「改一个可变字段就能换一种执行」的可能。
 */
@Component
final class CaregiverBookingExecutor {
    private final CareBookingService careBooking;
    private final ConversationStore conversations;

    CaregiverBookingExecutor(CareBookingService careBooking, ConversationStore conversations) {
        this.careBooking = careBooking;
        this.conversations = conversations;
    }

    /** 家属/志愿者确认「照草稿代办这次复诊」：新约走 book，改期走 modify。 */
    AgentTurnResponse book(ConversationState state, ConfirmationSupport support) {
        String subject = support.elderName(state.userId);
        try {
            if (!support.draftComplete(state)) {
                state.stage = ConversationState.Stage.READY_TO_PLAN;
                return support.advance(state);
            }
            CareBookingService.BookingRequest request = new CareBookingService.BookingRequest(
                    state.hospitalId, state.departmentId, state.date.toString(), state.selectedSlot.id(),
                    state.needTravel, state.transport, state.needCompanion);
            // 改期走 modify（原位换号源、不停旧记录）；新约才走 book。
            AppointmentRecordStore.AppointmentView booked = state.originalAppointmentId == null
                    ? careBooking.book(state.actorUserId, state.userId, request)
                    : careBooking.modify(state.actorUserId, state.userId, request);
            state.appointmentId = booked.appointmentId();
            state.originalAppointmentId = null;
            // 提醒、材料与协同通知都已由 CareBookingService 落库，这里只把会话状态对齐，
            // 免得界面上继续显示“未完成”。
            state.materialReminderDone = true;
            state.departureReminderDone = Boolean.TRUE.equals(state.needTravel);
            state.notificationDone = true;
            state.stage = ConversationState.Stage.COMPLETED;
            state.taskStatus = ConversationState.TaskStatus.COMPLETED;
            conversations.save(state, null);
            String reply = "已经替" + subject + "安排好复诊：" + support.managedShort(booked)
                    + "。复诊提醒已经建好，" + subject + "下次打开助手就会看到这次安排。";
            // 结果卡直接取 CareBookingService 写好的那条记录：它已经写明了代约人和陪同人，
            // 再走一遍老人端的 recordResult 会把这两项覆盖成“不通知家属”那套口径。
            ResultCard result = new ResultCard(booked.appointmentId(), booked.hospital(), booked.department(),
                    booked.date().format(FollowupAgentService.DATE_LABEL),
                    booked.time().format(FollowupAgentService.TIME_LABEL),
                    state.materials.isEmpty() ? booked.materials() : state.materials,
                    booked.departureAt() == null ? "未提供出发建议（路线或时间信息不足）"
                            : booked.departureAt().format(FollowupAgentService.TIME_LABEL),
                    booked.reminderStatus(), booked.familyStatus());
            return support.finishWithoutModel(state, new AgentTurnResponse(state.id, state.stage.name(), reply,
                    support.caregiverBookedActions(subject), null, null, result,
                    support.traceList(state), null, reply, null));
        } catch (RuntimeException error) {
            return failure(state, error, support);
        }
    }

    /**
     * 家属/志愿者确认「取消代约的那次复诊」。
     *
     * <p>取消的是<b>凭据上冻结的那一条</b>（{@link ConfirmationService.PendingOperation#singleTarget()}），
     * 不是"长辈当前进行中的那一条"。以前这里调 {@code cancelUpcoming} 按后一个口径取对象，
     * 于是发卡之后列表里多出一条更早的代约，同一张卡按下去取消的就是那条——卡片上写的却是原来那条。
     *
     * <p>这条业务只处理单条，所以要求<b>恰好一个</b>目标：不是一条就一个写操作都不做，
     * 绝不 {@code get(0)} 取第一条顶上。签发侧另有同样一道（{@code ConfirmationService.issue}），
     * 两边成对，单写一处换个入口就能绕过去。
     *
     * <p>归属校验（照护关系、预约归属、当前状态）与原通知都在
     * {@link CareBookingService#cancelAppointment} 里，这里只负责把冻结的那条交出去。
     */
    AgentTurnResponse cancel(ConversationState state, ConfirmationService.PendingOperation operation,
                             ConfirmationSupport support) {
        String subject = support.elderName(state.userId);
        String targetId = operation.singleTarget();
        if (targetId == null) {
            state.pendingAction = "CREATE";
            state.stage = ConversationState.Stage.COMPLETED;
            return support.respondWithoutModel(state,
                    "这张取消卡上没有指定要取消的预约，我没有执行任何操作。",
                    List.of(q("查看" + subject + "的复诊安排", "QUERY_APPOINTMENTS", "")));
        }
        try {
            careBooking.cancelAppointment(state.actorUserId, state.userId, targetId);
            state.pendingAction = "CREATE";
            state.pendingAppointmentId = null;
            state.pendingAppointmentIds = List.of();
            state.appointmentId = null;
            state.stage = ConversationState.Stage.CANCELLED;
            state.taskStatus = ConversationState.TaskStatus.CANCELLED;
            conversations.save(state, null);
            return support.respondWithoutModel(state, "已取消" + subject + "的这次复诊，号源已经释放。"
                            + "如果这张预约原本是别的照护者安排的，对方也会收到通知。",
                    List.of(q("重新安排", "NEW_BOOKING", ""),
                            q("查看" + subject + "的复诊安排", "QUERY_APPOINTMENTS", "")));
        } catch (RuntimeException error) {
            return failure(state, error, support);
        }
    }

    /**
     * 号源已失效、已有进行中的预约、关系被撤销——这些都是用户听得懂也改得动的，
     * 原样报出来，不要裹成一句“办理遇到问题”。
     */
    private AgentTurnResponse failure(ConversationState state, RuntimeException error, ConfirmationSupport support) {
        if (error instanceof IllegalArgumentException) {
            state.stage = ConversationState.Stage.READY_TO_PLAN;
            return support.respondWithoutModel(state, error.getMessage(),
                    List.of(q("先取消已有预约", "CANCEL_APPOINTMENT", ""),
                            q("重新选择时间", "CHANGE_DATE", ""), q("联系人工帮助", "CONTACT_HUMAN", "")));
        }
        return support.toolError(state, error);
    }
}
