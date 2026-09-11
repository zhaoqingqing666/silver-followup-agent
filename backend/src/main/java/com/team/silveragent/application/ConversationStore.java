package com.team.silveragent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.silveragent.agent.AgentContext;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.model.ConversationHistoryResponse;
import com.team.silveragent.domain.model.ToolModels.Contact;
import com.team.silveragent.domain.model.ToolModels.Slot;
import com.team.silveragent.domain.model.ToolModels.TravelPlan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
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
class ConversationStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ConversationLifecycle lifecycle;
    private final String userId;

    ConversationStore(JdbcTemplate jdbc, ObjectMapper json, ConversationLifecycle lifecycle,
                      @Value("${demo.user-id:user-001}") String userId) {
        this.jdbc = jdbc;
        this.json = json;
        this.lifecycle = lifecycle;
        this.userId = userId;
    }

    void save(ConversationState state, AgentTurnResponse response) {
        try {
            String stateJson = json.writeValueAsString(Snapshot.from(state));
            String responseJson = response == null ? null : json.writeValueAsString(response);
            jdbc.update("""
                    MERGE INTO conversation_sessions(id,user_id,stage,state_json,updated_at,last_response_json)
                    KEY(id) VALUES (?,?,?,?,?,?)
                    """, state.id, userId, state.stage.name(), stateJson,
                    Timestamp.valueOf(LocalDateTime.now()), responseJson);
        } catch (Exception error) {
            throw new IllegalStateException("保存会话状态失败", error);
        }
    }

    // ---------- 会话生命周期 ----------

    /**
     * 会话的生命周期信息。status 与办理进度（stage）是两回事：
     * ACTIVE 才能接收新消息，CLOSED 只读。
     */
    record SessionMeta(String id, String status, LocalDateTime lastMessageAt, int turnCount, int estimatedTokens) { }

    Optional<SessionMeta> meta(String id) {
        List<SessionMeta> rows = jdbc.query("""
                SELECT id, status, COALESCE(last_message_at, updated_at), turn_count, estimated_tokens
                FROM conversation_sessions WHERE id=?
                """, (rs, row) -> new SessionMeta(
                rs.getString(1), rs.getString(2),
                rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toLocalDateTime(),
                rs.getInt(4), rs.getInt(5)), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    String status(String id) {
        return meta(id).map(SessionMeta::status).orElse("ACTIVE");
    }

    /** 当前仍在进行的会话（应当最多一个）。 */
    Optional<String> activeSessionId() {
        List<String> ids = jdbc.query("""
                SELECT id FROM conversation_sessions
                WHERE user_id=? AND status='ACTIVE'
                ORDER BY COALESCE(last_message_at, updated_at) DESC LIMIT 1
                """, (rs, row) -> rs.getString(1), userId);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    void close(String conversationId, String reason) {
        jdbc.update("""
                UPDATE conversation_sessions SET status='CLOSED', closed_at=?, close_reason=?
                WHERE id=? AND status<>'CLOSED'
                """, Timestamp.valueOf(LocalDateTime.now()), reason, conversationId);
    }

    /** 这条会话除了问候语之外，用户还什么都没说过——用来避免连点「新对话」堆出一串空会话。 */
    boolean isEmptyConversation(String conversationId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversation_messages WHERE conversation_id=? AND role='user'",
                Integer.class, conversationId);
        return count == null || count == 0;
    }

    /**
     * 刷新会话的 last_message_at / turn_count / estimated_tokens。
     * Token 用字符数保守估算（中文约 1 字 1 token），只用于触发上限提醒，不做计费。
     */
    void touch(String conversationId) {
        List<Object[]> rows = jdbc.query("SELECT role,content FROM conversation_messages WHERE conversation_id=?",
                (rs, row) -> new Object[]{rs.getString(1), rs.getString(2)}, conversationId);
        int turns = 0;
        long tokens = 0;
        for (Object[] row : rows) {
            if ("user".equals(row[0])) turns++;
            String content = (String) row[1];
            if (content != null) tokens += content.length();
        }
        jdbc.update("""
                UPDATE conversation_sessions SET last_message_at=?, turn_count=?, estimated_tokens=?
                WHERE id=?
                """, Timestamp.valueOf(LocalDateTime.now()), turns,
                (int) Math.min(Integer.MAX_VALUE, tokens), conversationId);
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
        addMessage(conversationId, role, content, "TEXT", null);
    }

    /** 返回新消息的 id，便于把图片附件挂到这条消息上。 */
    long addMessage(String conversationId, String role, String content, String messageType, Long attachmentId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO conversation_messages(conversation_id,role,content,message_type,attachment_id,created_at)
                    VALUES (?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, conversationId);
            statement.setString(2, role);
            statement.setString(3, content);
            statement.setString(4, messageType);
            if (attachmentId == null) statement.setNull(5, Types.BIGINT);
            else statement.setLong(5, attachmentId);
            statement.setTimestamp(6, Timestamp.valueOf(LocalDateTime.now()));
            return statement;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    /** 改写最后一条助手消息（同一轮里追加"对话已经比较长"之类的提示时用）。 */
    void replaceLastAssistantMessage(String conversationId, String content) {
        jdbc.update("""
                UPDATE conversation_messages SET content=?
                WHERE id=(SELECT MAX(id) FROM conversation_messages WHERE conversation_id=? AND role='assistant')
                """, content, conversationId);
    }

    List<AgentContext.Message> recentMessages(String conversationId) {
        return jdbc.query("""
                SELECT role,content FROM (
                  SELECT id,role,content FROM conversation_messages
                  WHERE conversation_id=? ORDER BY id DESC LIMIT ?
                ) recent ORDER BY id
                """, (rs, row) -> new AgentContext.Message(rs.getString(1), rs.getString(2)),
                conversationId, lifecycle.recentMessages());
    }

    // ---------- 图片附件与视觉结果 ----------

    /** 存图片并按"最近 N 张"裁剪。业务层只拿 id，不关心底层是 dataUrl 还是文件路径。 */
    long addAttachment(String conversationId, long messageId, String kind, String dataUrl) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO conversation_attachments(conversation_id,message_id,kind,data_url,created_at)
                    VALUES (?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, conversationId);
            statement.setLong(2, messageId);
            statement.setString(3, kind);
            statement.setString(4, dataUrl);
            statement.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now()));
            return statement;
        }, keys);
        pruneAttachments(conversationId);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    void saveVisionResult(long attachmentId, String conversationId, String hint, String description, String ocr) {
        jdbc.update("""
                INSERT INTO vision_results(attachment_id,conversation_id,hint,description,ocr,created_at)
                VALUES (?,?,?,?,?,?)
                """, attachmentId, conversationId, hint, description, ocr, Timestamp.valueOf(LocalDateTime.now()));
    }

    /** 本会话最近几张图片的识别结果（新的在前），供后续轮次复用，避免重复调用视觉模型。 */
    List<AgentContext.VisionNote> recentVision(String conversationId) {
        return jdbc.query("""
                SELECT hint,description,ocr,created_at FROM vision_results
                WHERE conversation_id=? ORDER BY id DESC LIMIT ?
                """, (rs, row) -> new AgentContext.VisionNote(
                rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toLocalDateTime()),
                conversationId, lifecycle.recentImages());
    }

    private void pruneAttachments(String conversationId) {
        jdbc.update("""
                DELETE FROM conversation_attachments WHERE conversation_id=? AND id NOT IN (
                  SELECT id FROM (
                    SELECT id FROM conversation_attachments WHERE conversation_id=? ORDER BY id DESC LIMIT ?
                  ) keep_rows
                )
                """, conversationId, conversationId, lifecycle.recentImages());
        jdbc.update("""
                DELETE FROM vision_results WHERE conversation_id=? AND attachment_id NOT IN (
                  SELECT id FROM conversation_attachments WHERE conversation_id=?
                )
                """, conversationId, conversationId);
    }

    List<ConversationHistoryResponse.Message> messages(String conversationId) {
        return jdbc.query("""
                SELECT id,role,content,created_at FROM conversation_messages
                WHERE conversation_id=? ORDER BY id
                """, (rs, row) -> new ConversationHistoryResponse.Message(
                rs.getLong(1), rs.getString(2), displayContent(rs.getString(3)),
                rs.getTimestamp(4).toLocalDateTime()), conversationId);
    }

    /** 最近 3 天的会话摘要，按更新时间倒序（历史对话面板用）。只列用户真正说过话的会话。 */
    List<com.team.silveragent.domain.model.ConversationSummary> summaries() {
        LocalDateTime threeDaysAgo = LocalDateTime.now().minusDays(3);
        return jdbc.query("""
                SELECT s.id, s.stage, s.updated_at, s.status,
                  (SELECT m.content FROM conversation_messages m
                   WHERE m.conversation_id = s.id AND m.role = 'user' ORDER BY m.id LIMIT 1) AS first_user,
                  (SELECT COUNT(*) FROM conversation_messages m WHERE m.conversation_id = s.id) AS msg_count
                FROM conversation_sessions s
                WHERE s.updated_at >= ?
                  AND EXISTS (SELECT 1 FROM conversation_messages m
                              WHERE m.conversation_id = s.id AND m.role = 'user')
                ORDER BY s.updated_at DESC
                """, (rs, row) -> {
            String first = displayContent(rs.getString(5));
            String title = first == null || first.isBlank() ? "新的复诊对话" : first;
            if (title.length() > 24) title = title.substring(0, 24) + "…";
            return new com.team.silveragent.domain.model.ConversationSummary(
                    rs.getString(1), title, rs.getString(2),
                    rs.getTimestamp(3).toLocalDateTime(), rs.getString(4), rs.getLong(6));
        }, Timestamp.valueOf(threeDaysAgo));
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
            ConversationState.Stage stage, String hospital, String department, LocalDate date,
            Boolean acceptAlternative, Boolean needCompanion, Boolean needTravel, Boolean notifyFamily,
            String transport, Slot selectedSlot, Slot recommendedSlot, String timePreference,
            List<Slot> alternatives, TravelPlan travelPlan,
            Contact contact, List<String> materials, String pendingAction, String appointmentId
    ) {
        static Snapshot from(ConversationState state) {
            return new Snapshot(state.stage, state.hospital, state.department, state.date,
                    state.acceptAlternative, state.needCompanion, state.needTravel, state.notifyFamily,
                    state.transport, state.selectedSlot, state.recommendedSlot, state.timePreference,
                    state.alternatives, state.travelPlan,
                    state.contact, state.materials, state.pendingAction, state.appointmentId);
        }

        ConversationState toState(String id) {
            ConversationState state = new ConversationState(id);
            state.stage = stage;
            state.hospital = hospital;
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
            state.alternatives = alternatives == null ? List.of() : alternatives;
            state.travelPlan = travelPlan;
            state.contact = contact;
            state.materials = materials == null ? List.of() : materials;
            state.pendingAction = pendingAction;
            state.appointmentId = appointmentId;
            return state;
        }
    }
}
