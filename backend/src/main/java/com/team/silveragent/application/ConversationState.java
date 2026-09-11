package com.team.silveragent.application;

import com.team.silveragent.domain.model.ToolModels.Contact;
import com.team.silveragent.domain.model.ToolModels.Slot;
import com.team.silveragent.domain.model.ToolModels.TravelPlan;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

final class ConversationState {
    enum DialogueMode { GENERAL_CHAT, FOLLOWUP_FLOW, SUPPORT, SMALL_TALK }
    enum TaskStatus { NONE, ACTIVE, PAUSED, AWAITING_CONFIRMATION, COMPLETED, CANCELLED }

    enum Stage {
        ASK_HOSPITAL, ASK_DEPARTMENT, ASK_DATE, ASK_ALTERNATIVE,
        ASK_COMPANION, ASK_TRAVEL, ASK_TRANSPORT, ASK_NOTIFY,
        READY_TO_PLAN, SELECT_PERIOD, CONFIRM_SLOT, SELECT_SLOT, NO_SLOT, CONFLICT,
        AWAITING_CONFIRMATION, COMPLETED, CANCELLED, EMERGENCY_PAUSED, PARTIAL, TOOL_ERROR
    }

    final String id;
    String userId;
    Stage stage = Stage.ASK_HOSPITAL;
    DialogueMode dialogueMode = DialogueMode.GENERAL_CHAT;
    TaskStatus taskStatus = TaskStatus.NONE;
    String hospitalId;
    String hospital;
    String departmentId;
    String department;
    LocalDate date;
    Boolean acceptAlternative;
    Boolean needCompanion;
    Boolean needTravel;
    Boolean notifyFamily;
    String transport;
    Slot selectedSlot;
    Slot recommendedSlot;
    String timePreference;
    LocalTime requestedTime;
    List<Slot> alternatives = List.of();
    TravelPlan travelPlan;
    Contact contact;
    List<String> materials = List.of();
    String pendingAction = "CREATE";
    String appointmentId;
    String pendingAppointmentId;
    Stage interruptedStage;
    String interruptedPendingAction;
    String interruptedPendingAppointmentId;
    String sideTask;
    String returnPolicy;
    /** 医院/科室口语只命中一个近似候选时，先保存候选并等待用户确认。 */
    String pendingEntityType;
    String pendingEntityId;
    String pendingEntityName;
    String pendingEntityRaw;
    /** 地图候选支线选中的方向：true=院内指引，false=院外路线。与取消支线互不共用。 */
    boolean travelInside;
    String confirmationId;
    String originalAppointmentId;
    boolean materialReminderDone;
    boolean departureReminderDone;
    boolean notificationDone;
    boolean scheduleChecked;

    ConversationState(String id) { this(id, "user-001"); }

    ConversationState(String id, String userId) {
        this.id = id;
        this.userId = userId;
    }
}
