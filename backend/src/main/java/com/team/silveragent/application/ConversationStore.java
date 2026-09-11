package com.team.silveragent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.silveragent.agent.AgentContext;
import com.team.silveragent.agent.AgentRole;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.model.ConversationHistoryResponse;
import com.team.silveragent.domain.model.ConversationSummary;
import com.team.silveragent.domain.model.ToolModels.Contact;
import com.team.silveragent.domain.model.ToolModels.Slot;
import com.team.silveragent.domain.model.ToolModels.TravelPlan;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class ConversationStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ConversationLifecycle lifecycle;
    private final int modelHistoryLimit;
    private final int recentImageLimit;

    ConversationStore(JdbcTemplate jdbc, ObjectMapper json, ConversationLifecycle lifecycle,
                      @Value("${agent.model.history-limit:16}") int modelHistoryLimit,
                      @Value("${agent.conversation.recent-images:3}") int recentImageLimit) {
        this.jdbc = jdbc;
        this.json = json;
        this.lifecycle = lifecycle;
        this.modelHistoryLimit = Math.max(4, Math.min(modelHistoryLimit, 40));
        this.recentImageLimit = Math.max(0, Math.min(recentImageLimit, 10));
    }

    void save(ConversationState state, AgentTurnResponse response) {
        try {
            String stateJson = json.writeValueAsString(Snapshot.from(state));
            String responseJson = response == null ? null : json.writeValueAsString(response);
            // status 是独立一列而不是 state_json 里的字段：历史列表要按它筛选和显示徽标，
            // 在一列里查比把每条 state_json 都反序列化出来快得多，也不必为旧快照做兼容。
            jdbc.update("""
                    MERGE INTO conversation_sessions(id,user_id,stage,state_json,updated_at,last_response_json,status)
                    KEY(id) VALUES (?,?,?,?,?,?,?)
                    """, state.id, state.userId, state.stage.name(), stateJson,
                    Timestamp.valueOf(LocalDateTime.now()), responseJson, state.status.name());
        } catch (Exception error) {
            throw new IllegalStateException("保存会话状态失败", error);
        }
    }

    Optional<ConversationState> find(String id) {
        List<Object[]> rows = jdbc.query("SELECT state_json,status,updated_at FROM conversation_sessions WHERE id=?",
                (rs, row) -> new Object[]{rs.getString(1), rs.getString(2), rs.getTimestamp(3)}, id);
        if (rows.isEmpty()) return Optional.empty();
        ConversationState state = readState((String) rows.get(0)[0], id);
        if (state == null) return Optional.empty();
        Timestamp updatedAt = (Timestamp) rows.get(0)[2];
        state.status = resolveStatus(ConversationState.Status.fromStored((String) rows.get(0)[1]),
                updatedAt == null ? null : updatedAt.toLocalDateTime(), state);
        return Optional.of(state);
    }

    /**
     * 历史记录列表。标题取这条会话的第一句用户消息，不单独存一列——
     * 存了就要在写入路径上多维护一处，而它本来就只是展示用的一个截断。
     *
     * <p>这里确实要读 {@code state_json}，看着比「列表只查列」重，但换来的是
     * <b>列表和详情用同一条判定规则</b>：判断一段会话是不是已经结束，必须知道它
     * 有没有事情正做到一半（待确认的卡片、进行中的办理），而这个信息只在状态里。
     * 用另一套简化口径去猜，就会出现「详情页说着进行中、列表上标着已结束」这种
     * 自相矛盾的界面——那比多解析二十个小对象糟糕得多。
     */
    List<ConversationSummary> list(String userId, int limit) {
        return jdbc.query("""
                SELECT s.id, s.stage, s.status, s.updated_at, s.state_json,
                  (SELECT COUNT(*) FROM conversation_messages m WHERE m.conversation_id=s.id) AS message_count,
                  (SELECT m.content FROM conversation_messages m
                    WHERE m.conversation_id=s.id AND m.role='user' ORDER BY m.id LIMIT 1) AS title
                FROM conversation_sessions s
                WHERE s.user_id=?
                ORDER BY s.updated_at DESC
                LIMIT ?
                """,
                (rs, row) -> {
                    Timestamp updatedAt = rs.getTimestamp("updated_at");
                    LocalDateTime at = updatedAt == null ? null : updatedAt.toLocalDateTime();
                    ConversationState.Status status = resolveStatus(
                            ConversationState.Status.fromStored(rs.getString("status")), at,
                            readState(rs.getString("state_json"), rs.getString("id")));
                    return new ConversationSummary(rs.getString("id"), summarize(rs.getString("title")),
                            status.name(), rs.getString("stage"), rs.getInt("message_count"), at);
                }, userId, Math.max(1, Math.min(limit, 50)));
    }

    /** 用户主动结束这段对话。之后它只读：还能翻看，但不再接受新的办理与确认。 */
    void close(String id) {
        jdbc.update("UPDATE conversation_sessions SET status=?, updated_at=? WHERE id=?",
                ConversationState.Status.CLOSED.name(), Timestamp.valueOf(LocalDateTime.now()), id);
    }

    /**
     * 空闲太久的会话在历史里记成「已结束」。
     *
     * <p>但要是有事情正做到一半（办理中的任务、待确认的卡片），就不判超时：
     * 老人离开一会儿再回来，那张确认卡还应该能按下去。会话真正的结束只能由用户主动决定。
     */
    private ConversationState.Status resolveStatus(ConversationState.Status stored,
                                                   LocalDateTime lastActiveAt,
                                                   ConversationState state) {
        if (stored != ConversationState.Status.ACTIVE) return stored;
        if (state != null && hasLiveWork(state)) return ConversationState.Status.ACTIVE;
        return lifecycle.isIdleExpired(lastActiveAt) ? ConversationState.Status.EXPIRED : stored;
    }

    /** 读不出状态时返回 null，判定会退回「只看时间」；宁可少标一个已结束，也不要读失败就报错。 */
    private ConversationState readState(String stateJson, String id) {
        try {
            return json.readValue(stateJson, Snapshot.class).toState(id);
        } catch (Exception error) {
            return null;
        }
    }

    private static boolean hasLiveWork(ConversationState state) {
        return state.confirmationId != null
                || state.taskStatus == ConversationState.TaskStatus.ACTIVE
                || state.taskStatus == ConversationState.TaskStatus.PAUSED
                || state.taskStatus == ConversationState.TaskStatus.AWAITING_CONFIRMATION;
    }

    /** 标题就是第一句话，太长会挤爆列表；省略号由这里统一加，前端不必再判断。 */
    private static String summarize(String title) {
        if (title == null || title.isBlank()) return "（空对话）";
        String flat = title.replaceAll("\\s+", " ").trim();
        return flat.length() > 24 ? flat.substring(0, 24) + "…" : flat;
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
        addMessage(conversationId, role, content, null, null);
    }

    /**
     * 带附件信息的一轮。messageType 用来区分「图片轮」这类特殊消息，attachmentId 指向
     * {@code conversation_attachments}。两者都允许为空，纯文字消息走三参重载即可。
     */
    void addMessage(String conversationId, String role, String content,
                    String messageType, Long attachmentId) {
        jdbc.update("""
                INSERT INTO conversation_messages
                (conversation_id,role,content,created_at,message_type,attachment_id) VALUES (?,?,?,?,?,?)
                """, conversationId, role, content, Timestamp.valueOf(LocalDateTime.now()),
                messageType, attachmentId);
    }

    /**
     * 存一份附件本体（图片 data URL），返回自增主键。
     * 存成独立表而不是塞进消息内容，是为了让 recentMessages 保持纯文本。
     */
    long addAttachment(String conversationId, Long messageId, String kind, String dataUrl) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO conversation_attachments(conversation_id,message_id,kind,data_url,created_at)
                    VALUES (?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, conversationId);
            if (messageId == null) statement.setNull(2, Types.BIGINT);
            else statement.setLong(2, messageId);
            statement.setString(3, kind);
            statement.setString(4, dataUrl);
            statement.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now()));
            return statement;
        }, keys);
        Number key = keys.getKey();
        return key == null ? -1L : key.longValue();
    }

    /**
     * 材料清单「拍照确认」的照片归属键前缀。
     * 材料不属于任何一段对话，但图片本体只该存在这一处；用 appointment: 前缀占住
     * conversation_id 这一列——真实会话 id 是 UUID，撞不上，也不会被 recentVision 捞出来。
     */
    static final String MATERIAL_PHOTO_OWNER_PREFIX = "appointment:";

    /**
     * 存一张材料照片，返回自增主键。
     * 调用方把返回值拼成 {@code attachment:<id>} 这样的短引用再写回 photo_url——
     * appointment_materials.photo_url 是 VARCHAR(500)，几十万字符的 data URL 直接写会被截断。
     */
    public long addMaterialPhoto(String appointmentId, String dataUrl) {
        return addAttachment(MATERIAL_PHOTO_OWNER_PREFIX + appointmentId, null, "MATERIAL_PHOTO", dataUrl);
    }

    /**
     * 取回一条预约自己拍的材料照片。找不到（编号不存在、或不属于这条预约）返回空。
     * 归属判断写在这一处：{@code attachment:<id>} 这个引用是客户端回传的，
     * 谁都能把它改成别的编号，所以每次读都必须连带核对 conversation_id 与 kind。
     */
    public Optional<String> findMaterialPhoto(String appointmentId, long attachmentId) {
        List<String> rows = jdbc.queryForList("""
                SELECT data_url FROM conversation_attachments
                WHERE id=? AND conversation_id=? AND kind='MATERIAL_PHOTO'
                """, String.class, attachmentId, MATERIAL_PHOTO_OWNER_PREFIX + appointmentId);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /** 记一次识图结论。只存文字，图片本体在 attachments 表。 */
    void saveVisionResult(Long attachmentId, String conversationId, String hint,
                          String description, String ocr) {
        jdbc.update("""
                INSERT INTO vision_results(attachment_id,conversation_id,hint,description,ocr,created_at)
                VALUES (?,?,?,?,?,?)
                """, attachmentId, conversationId, hint, description, ocr,
                Timestamp.valueOf(LocalDateTime.now()));
    }

    /**
     * 本会话最近识别过的图片，<b>新的在前</b>。
     * 只取文字结论，不带 data URL —— 每轮提示词都塞 base64 会直接把上下文撑爆。
     */
    List<AgentContext.VisionNote> recentVision(String conversationId) {
        if (recentImageLimit == 0) return List.of();
        return jdbc.query("""
                SELECT hint,description,ocr,created_at FROM vision_results
                WHERE conversation_id=? ORDER BY id DESC LIMIT ?
                """, (rs, row) -> new AgentContext.VisionNote(
                        rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toLocalDateTime()),
                conversationId, recentImageLimit);
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
            String userId, String actorUserId, AgentRole actorRole, String relationLabel,
            String hospitalId, String hospital,
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
                    state.userId, state.actorUserId, state.actorRole, state.relationLabel,
                    state.hospitalId, state.hospital,
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
            // 身份必须随会话恢复：确认卡是另一个请求打进来的，那时只剩 conversationId。
            // 旧快照没有这几个字段，缺省即“本人自办”，与合并前的行为一致。
            state.actorUserId = actorUserId == null || actorUserId.isBlank() ? state.userId : actorUserId;
            state.actorRole = actorRole == null ? AgentRole.ELDER : actorRole;
            state.relationLabel = relationLabel;
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
