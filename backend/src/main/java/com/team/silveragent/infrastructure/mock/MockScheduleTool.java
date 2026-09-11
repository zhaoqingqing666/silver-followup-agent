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
    public String createReminder(String conversationId, String userId, String title, LocalDateTime remindAt) {
        String id = "RM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbc.update("INSERT INTO reminders(id,user_id,title,remind_at,created_at) VALUES (?,?,?,?,?)",
                id, userId, title, Timestamp.valueOf(remindAt), Timestamp.valueOf(LocalDateTime.now()));
        traces.record(conversationId, "schedule.createReminder",
                Map.of("userId", userId, "title", title, "remindAt", remindAt),
                Map.of("reminderId", id, "status", "CREATED"), true);
        return id;
    }
}
