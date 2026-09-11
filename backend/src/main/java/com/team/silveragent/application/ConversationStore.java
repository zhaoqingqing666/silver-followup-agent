package com.team.silveragent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.silveragent.agent.AgentContext;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.model.ConversationHistoryResponse;
import com.team.silveragent.domain.model.ToolModels.Contact;
import com.team.silveragent.domain.model.ToolModels.Slot;
import com.team.silveragent.domain.model.ToolModels.TravelPlan;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
class ConversationStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final int modelHistoryLimit;

    ConversationStore(JdbcTemplate jdbc, ObjectMapper json,
                      @Value("${agent.model.history-limit:16}") int modelHistoryLimit) {
        this.jdbc = jdbc;
        this.json = json;
        this.modelHistoryLimit = Math.max(4, Math.min(modelHistoryLimit, 40));
    }

    void save(ConversationState state, AgentTurnResponse response) {
        try {
            String stateJson = json.writeValueAsString(Snapshot.from(state));
            String responseJson = response == null ? null : json.writeValueAsString(response);
            jdbc.update("""
                    MERGE INTO conversation_sessions(id,user_id,stage,state_json,updated_at,last_response_json)
                    KEY(id) VALUES (?,?,?,?,?,?)
                    """, state.id, state.userId, state.stage.name(), stateJson,
                    Timestamp.valueOf(LocalDateTime.now()), responseJson);
        } catch (Exception error) {
            throw new IllegalStateException("保存会话状态失败", error);
        }
    }

    Optional<ConversationState> find(String id) {
        List<String> rows = jdbc.query("SELECT state_json FROM conversation_sessions WHERE id=?",
                (rs, row) -> rs.getString(1), id);
        if (rows.isEmpty()) return Optional.empty();
        try {
            Snapshot snapshot = json.readValue(rows.get(0), Snapshot.class);
            return Optional.of(snapshot.toState(id));
        } catch (Exception error) {
            return Optional.empty();
        }
    }

    Optional<AgentTurnResponse> lastResponse(String id) {
        List<String> rows = jdbc.query(
                "SELECT last_response_json FROM conversation_sessions WHERE id=? AND last_response_json IS NOT NULL",
                (rs, row) -> rs.getString(1), id);
        if (rows.isEmpty()) return Optional.empty();
        try {
            return Optional.of(json.readValue(rows.get(0), AgentTurnResponse.class));
        } catch (Exception error) {
            return Optional.empty();
        }
    }

    void addMessage(String conversationId, String role, String content) {
        jdbc.update("INSERT INTO conversation_messages(conversation_id,role,content,created_at) VALUES (?,?,?,?)",
                conversationId, role, content, Timestamp.valueOf(LocalDateTime.now()));
    }

    List<AgentContext.Message> recentMessages(String conversationId) {
        return jdbc.query("""
                SELECT role,content FROM (
                  SELECT id,role,content FROM conversation_messages
                  WHERE conversation_id=? ORDER BY id DESC LIMIT ?
                ) recent ORDER BY id
                """, (rs, row) -> new AgentContext.Message(rs.getString(1), rs.getString(2)),
                conversationId, modelHistoryLimit);
    }

    List<ConversationHistoryResponse.Message> messages(String conversationId) {
        return jdbc.query("""
                SELECT id,role,content,created_at FROM conversation_messages
                WHERE conversation_id=? ORDER BY id
                """, (rs, row) -> new ConversationHistoryResponse.Message(
                rs.getLong(1), rs.getString(2), displayContent(rs.getString(3)),
                rs.getTimestamp(4).toLocalDateTime()), conversationId);
    }

    private String displayContent(String content) {
        if (content == null) return "";
        if (content.startsWith("[确认] ")) return content.substring(5);
        if (content.startsWith("[按钮] ")) {
            int split = content.indexOf('：');
            return split >= 0 ? content.substring(split + 1) : "继续办理";
        }
        return content;
    }

    private record Snapshot(
            ConversationState.Stage stage, ConversationState.DialogueMode dialogueMode,
            ConversationState.TaskStatus taskStatus,
            String userId, String hospitalId, String hospital,
            String departmentId, String department, LocalDate date,
            Boolean acceptAlternative, Boolean needCompanion, Boolean needTravel, Boolean notifyFamily,
            String transport, Slot selectedSlot, Slot recommendedSlot, String timePreference,
            java.time.LocalTime requestedTime,
            List<Slot> alternatives, TravelPlan travelPlan,
            Contact contact, List<String> materials, String pendingAction, String appointmentId,
            String pendingAppointmentId, ConversationState.Stage interruptedStage,
            String interruptedPendingAction, String interruptedPendingAppointmentId,
            String sideTask, String returnPolicy,
            String pendingEntityType, String pendingEntityId, String pendingEntityName, String pendingEntityRaw,
            String confirmationId, String originalAppointmentId,
            boolean materialReminderDone, boolean departureReminderDone,
            boolean notificationDone, boolean scheduleChecked
    ) {
        static Snapshot from(ConversationState state) {
            return new Snapshot(state.stage, state.dialogueMode, state.taskStatus,
                    state.userId, state.hospitalId, state.hospital,
                    state.departmentId, state.department, state.date,
                    state.acceptAlternative, state.needCompanion, state.needTravel, state.notifyFamily,
                    state.transport, state.selectedSlot, state.recommendedSlot, state.timePreference,
                    state.requestedTime, state.alternatives, state.travelPlan,
                    state.contact, state.materials, state.pendingAction, state.appointmentId,
                    state.pendingAppointmentId, state.interruptedStage,
                    state.interruptedPendingAction, state.interruptedPendingAppointmentId,
                    state.sideTask, state.returnPolicy,
                    state.pendingEntityType, state.pendingEntityId, state.pendingEntityName, state.pendingEntityRaw,
                    state.confirmationId, state.originalAppointmentId,
                    state.materialReminderDone, state.departureReminderDone,
                    state.notificationDone, state.scheduleChecked);
        }

        ConversationState toState(String id) {
            ConversationState state = new ConversationState(id, userId == null ? "user-001" : userId);
            state.stage = stage;
            state.dialogueMode = dialogueMode == null ? ConversationState.DialogueMode.GENERAL_CHAT : dialogueMode;
            state.taskStatus = taskStatus == null ? inferTaskStatus(stage) : taskStatus;
            state.hospitalId = hospitalId;
            state.hospital = hospital;
            state.departmentId = departmentId;
            state.department = department;
            state.date = date;
            state.acceptAlternative = acceptAlternative;
            state.needCompanion = needCompanion;
            state.needTravel = needTravel;
            state.notifyFamily = notifyFamily;
            state.transport = transport;
            state.selectedSlot = selectedSlot;
            state.recommendedSlot = recommendedSlot;
            state.timePreference = timePreference;
            state.requestedTime = requestedTime;
            state.alternatives = alternatives == null ? List.of() : alternatives;
            state.travelPlan = travelPlan;
            state.contact = contact;
            state.materials = materials == null ? List.of() : materials;
            state.pendingAction = pendingAction;
            state.appointmentId = appointmentId;
            state.pendingAppointmentId = pendingAppointmentId;
            state.interruptedStage = interruptedStage;
            state.interruptedPendingAction = interruptedPendingAction;
            state.interruptedPendingAppointmentId = interruptedPendingAppointmentId;
            state.sideTask = sideTask;
            state.returnPolicy = returnPolicy;
            state.pendingEntityType = pendingEntityType;
            state.pendingEntityId = pendingEntityId;
            state.pendingEntityName = pendingEntityName;
            state.pendingEntityRaw = pendingEntityRaw;
            state.confirmationId = confirmationId;
            state.originalAppointmentId = originalAppointmentId;
            state.materialReminderDone = materialReminderDone;
            state.departureReminderDone = departureReminderDone;
            state.notificationDone = notificationDone;
            state.scheduleChecked = scheduleChecked;
            return state;
        }

        private ConversationState.TaskStatus inferTaskStatus(ConversationState.Stage stage) {
            if (stage == ConversationState.Stage.CANCELLED) return ConversationState.TaskStatus.CANCELLED;
            if (stage == ConversationState.Stage.COMPLETED || stage == ConversationState.Stage.PARTIAL)
                return ConversationState.TaskStatus.COMPLETED;
            if (stage == ConversationState.Stage.EMERGENCY_PAUSED) return ConversationState.TaskStatus.PAUSED;
            return ConversationState.TaskStatus.ACTIVE;
        }
    }
}
