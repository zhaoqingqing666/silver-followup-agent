package com.team.silveragent.infrastructure.mock;

import com.team.silveragent.domain.model.ToolModels.Conflict;
import com.team.silveragent.domain.tool.ScheduleTool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class MockScheduleTool implements ScheduleTool {
    private final JdbcTemplate jdbc;
    private final ToolTraceStore traces;

    public MockScheduleTool(JdbcTemplate jdbc, ToolTraceStore traces) {
        this.jdbc = jdbc;
        this.traces = traces;
    }

    @Override
    public List<Conflict> findConflicts(String conversationId, String userId, LocalDateTime start, LocalDateTime end) {
        Map<String, Object> input = Map.of("userId", userId, "start", start, "end", end);
        List<Conflict> result = jdbc.query("""
                SELECT id,title,start_at,end_at FROM user_schedules
                WHERE user_id=? AND start_at < ? AND end_at > ? ORDER BY start_at
                """, (rs, row) -> new Conflict(rs.getString(1), rs.getString(2),
                rs.getTimestamp(3).toLocalDateTime(), rs.getTimestamp(4).toLocalDateTime()),
                userId, Timestamp.valueOf(end), Timestamp.valueOf(start));
        traces.record(conversationId, "schedule.checkConflict", input, result, true);
        return result;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public String createReminder(String conversationId, String userId, String title, LocalDateTime remindAt) {
        String id = "RM-" + UUID.nameUUIDFromBytes((conversationId + title + remindAt).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<String> existing = jdbc.query("SELECT id FROM reminders WHERE id=? AND status='CREATED'", (rs, row) -> rs.getString(1), id);
        if (!existing.isEmpty()) return id;
        String appointmentId = jdbc.queryForObject("SELECT id FROM appointments WHERE conversation_id=? AND status='CONFIRMED' ORDER BY created_at DESC LIMIT 1", String.class, conversationId);
        jdbc.update("MERGE INTO reminders(id,user_id,title,remind_at,created_at,conversation_id,appointment_id,status) KEY(id) VALUES (?,?,?,?,?,?,?,?)",
                id, userId, title, Timestamp.valueOf(remindAt), Timestamp.valueOf(LocalDateTime.now()), conversationId, appointmentId, "CREATED");
        traces.record(conversationId, "schedule.createReminder",
                Map.of("userId", userId, "title", title, "remindAt", remindAt),
                Map.of("reminderId", id, "status", "CREATED"), true);
        return id;
    }
}
