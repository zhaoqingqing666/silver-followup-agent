package com.team.silveragent.application;

import com.team.silveragent.application.care.CareBookingService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.tool.AppointmentTool;
import com.team.silveragent.domain.tool.FamilyNotificationTool;
import com.team.silveragent.domain.tool.MaterialPreparationTool;
import com.team.silveragent.domain.tool.ScheduleTool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.team.silveragent.application.ConfirmationSupport.q;

/**
 * 「照草稿开新预约 / 改期」这一类已确认操作的执行器。
 *
 * <p>它只管一件事：<b>把老人刚点头的那次复诊写进库</b>——提交（或改期）、按需建两条提醒、
 * 通知家属、初始化材料清单、改期过则通知安排者，最后把会话收在「已完成」。
 * 执行完怎么说这句话、这一轮怎么出站，交回 {@link ConfirmationSupport}（那是编排层的事，
 * 而且那些出口本来就带着整理好的措辞）。
 *
 * <p><b>它不看 {@code pendingAction}，也不重新判断「这次确认的是哪一类操作」</b>：
 * 能够走到这里，就意味着凭据上写的就是 {@code BOOKING}（签发时定下、随快照恢复）。
 * 中间任何一步都改不动这一点——这正是 4A 把动作类型与凭据绑在一起的原因。
 *
 * <p>工具本身的归属校验、重复预约检查、真实号源与那条事务都在各自的工具里，
 * 这里只负责「什么时候轮到它们」。
 */
@Component
final class BookingExecutor {
    private final AppointmentTool appointmentTool;
    private final ScheduleTool scheduleTool;
    private final FamilyNotificationTool familyTool;
    private final MaterialPreparationTool materialPreparationTool;
    private final CareBookingService careBooking;
    private final ConversationStore conversations;

    BookingExecutor(AppointmentTool appointmentTool, ScheduleTool scheduleTool, FamilyNotificationTool familyTool,
                    MaterialPreparationTool materialPreparationTool, CareBookingService careBooking,
                    ConversationStore conversations) {
        this.appointmentTool = appointmentTool;
        this.scheduleTool = scheduleTool;
        this.familyTool = familyTool;
        this.materialPreparationTool = materialPreparationTool;
        this.careBooking = careBooking;
        this.conversations = conversations;
    }

    /** 老人点了「确认办理」：照草稿把预约落下来，并把跟着它走的提醒、材料、通知一起办掉。 */
    AgentTurnResponse book(ConversationState state, ConfirmationSupport support) {
        if (!support.draftComplete(state) || !state.scheduleChecked || state.travelPlan == null) {
            state.stage = ConversationState.Stage.READY_TO_PLAN;
            return support.advance(state);
        }
        if (state.appointmentId == null) {
            state.appointmentId = state.originalAppointmentId == null
                    ? appointmentTool.submit(state.id, state.selectedSlot.id(), state.userId)
                    : appointmentTool.reschedule(state.id, state.originalAppointmentId, state.selectedSlot.id(), state.userId);
            state.originalAppointmentId = null;
            conversations.save(state, null);
            support.rememberBookingPreferences(state);
        }
        LocalDateTime at = LocalDateTime.of(state.selectedSlot.date(), state.selectedSlot.time());
        if (!state.materialReminderDone) {
            support.callTool(state, "schedule.createReminder",
                    Map.of("title", "复诊材料准备提醒", "remindAt", at.minusDays(1)),
                    () -> scheduleTool.createReminder(state.id, state.userId, "复诊材料准备提醒", at.minusDays(1)));
            state.materialReminderDone = true;
            conversations.save(state, null);
        }
        if (Boolean.TRUE.equals(state.needTravel) && !state.departureReminderDone) {
            support.callTool(state, "schedule.createReminder",
                    Map.of("title", "复诊出发提醒", "remindAt", state.travelPlan.departureAt().minusMinutes(10)),
                    () -> scheduleTool.createReminder(state.id, state.userId, "复诊出发提醒",
                            state.travelPlan.departureAt().minusMinutes(10)));
            state.departureReminderDone = true;
            conversations.save(state, null);
        }
        if (Boolean.TRUE.equals(state.notifyFamily) && !state.notificationDone) {
            support.callTool(state, "family.notify",
                    Map.of("contactId", state.contact.id(), "message", support.notificationMessage(state)),
                    () -> familyTool.notify(state.id, state.contact.id(), support.notificationMessage(state)));
            state.notificationDone = true;
            conversations.save(state, null);
        }
        materialPreparationTool.initialize(state.id, state.appointmentId, state.department, state.materials);
        // 老人把“由安排者约好的复诊”改期成功 → 通知安排者
        if (state.arrangedArrangerId != null && state.appointmentId != null && state.selectedSlot != null) {
            careBooking.notifyCaregiver(state.arrangedArrangerId, state.userId,
                    arrangerRescheduleMessage(state, support), "reschedule");
            state.arrangedArrangerId = null;
        }
        state.stage = ConversationState.Stage.COMPLETED;
        state.taskStatus = ConversationState.TaskStatus.COMPLETED;
        return support.completionResult(state);
    }

    /**
     * 老人点了「返回修改」：这次确认什么也不做，已经有预约的保留着。
     *
     * <p>这里只复位会话状态与话术，<b>一个业务动作都不执行</b>（连 {@code appointmentId} 都是
     * 只读它来决定停在「已完成」还是「部分完成」）。
     */
    AgentTurnResponse refuse(ConversationState state, ConfirmationSupport support) {
        state.pendingAction = "CREATE";
        state.stage = state.appointmentId == null ? ConversationState.Stage.READY_TO_PLAN
                : (state.materialReminderDone && (!Boolean.TRUE.equals(state.needTravel) || state.departureReminderDone)
                && (!Boolean.TRUE.equals(state.notifyFamily) || state.notificationDone)
                ? ConversationState.Stage.COMPLETED : ConversationState.Stage.PARTIAL);
        state.taskStatus = state.appointmentId == null
                ? ConversationState.TaskStatus.ACTIVE : ConversationState.TaskStatus.COMPLETED;
        return support.respondWithPlan(state, "没有执行本次操作，已有预约保留。您可以返回修改。",
                state.appointmentId == null
                        ? List.of(q("修改日期", "CHANGE_DATE", ""), q("修改偏好", "EDIT_PREFERENCES", ""),
                        q("检查计划", "START_PLAN", ""))
                        : support.bookedActions(state));
    }

    /** 改期成功通知安排者的话术（“改期通知：某某已将复诊改期至…”）。 */
    private String arrangerRescheduleMessage(ConversationState state, ConfirmationSupport support) {
        return "改期通知：" + support.elderName(state.userId) + "已将复诊改期至"
                + state.selectedSlot.date().format(FollowupAgentService.DATE_LABEL) + " "
                + state.selectedSlot.time().format(FollowupAgentService.TIME_LABEL)
                + "（" + state.hospital + " " + state.department + "）。相关提醒已按新安排更新。";
    }
}
