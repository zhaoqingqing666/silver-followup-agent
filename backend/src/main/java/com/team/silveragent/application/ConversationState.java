package com.team.silveragent.application;

import com.team.silveragent.agent.ExtractedFacts;
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
    /**
     * 用户在这场对话里提出过的多任务拆解与动态约束（跨轮累积）。
     * 与固定槽位不同，这些内容<b>不随换医院/换日期清空</b>——用户说过的诉求不会因为改了个日期就消失；
     * 它们只用于「理解、记录、展示、喂给模型」，不参与任何写操作的权限判定。
     */
    List<ExtractedFacts.TaskItem> tasks = List.of();
    List<String> constraints = List.of();
    List<String> preferences = List.of();
    List<String> additionalRequests = List.of();
    /** 当前还缺哪些关键信息，每轮用最新的理解结果整体替换。 */
    List<String> missingInformation = List.of();
    /**
     * 只在这一轮里用一次的提示（例如"上一段对话已结束，已为您开了一段新对话"）。
     * 不写库，回复完即清空。
     */
    String pendingNotice;

    ConversationState(String id) { this.id = id; }
}
