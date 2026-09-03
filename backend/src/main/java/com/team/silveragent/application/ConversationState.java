package com.team.silveragent.application;

import com.team.silveragent.domain.model.ToolModels.Contact;
import com.team.silveragent.domain.model.ToolModels.Slot;
import com.team.silveragent.domain.model.ToolModels.TravelPlan;

import java.time.LocalDate;
import java.util.List;

final class ConversationState {
    enum Stage {
        ASK_HOSPITAL, ASK_DEPARTMENT, ASK_DATE, ASK_ALTERNATIVE,
        ASK_COMPANION, ASK_TRAVEL, ASK_TRANSPORT, ASK_NOTIFY,
        READY_TO_PLAN, SELECT_PERIOD, CONFIRM_SLOT, SELECT_SLOT, NO_SLOT, CONFLICT,
        AWAITING_CONFIRMATION, COMPLETED, CANCELLED
    }

    final String id;
    Stage stage = Stage.ASK_HOSPITAL;
    String hospital;
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
    List<Slot> alternatives = List.of();
    TravelPlan travelPlan;
    Contact contact;
    List<String> materials = List.of();
    String pendingAction = "CREATE";
    String appointmentId;

    ConversationState(String id) { this.id = id; }
}
