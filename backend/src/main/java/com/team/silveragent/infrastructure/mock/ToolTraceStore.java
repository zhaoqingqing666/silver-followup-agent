package com.team.silveragent.infrastructure.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.silveragent.application.TurnProgress;
import com.team.silveragent.domain.model.AgentTurnResponse.ToolTrace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class ToolTraceStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TurnProgress progress;

    public ToolTraceStore(JdbcTemplate jdbc, ObjectMapper json, TurnProgress progress) {
        this.jdbc = jdbc;
        this.json = json;
        this.progress = progress;
    }

    /**
     * 记一次真实工具调用。
     *
     * <p>这里是全项目唯一的下沉点：所有工具调用都经过它落 {@code tool_call_logs}。
     * 所以实时进度也只在这一个地方打点——不必去 31 个调用点各改一遍，也不会漏。
     */
    public void record(String conversationId, String toolName, Object request, Object response, boolean success) {
        String requestJson = toJson(request);
        String responseJson = toJson(response);
        jdbc.update("INSERT INTO tool_call_logs(conversation_id,tool_name,request_json,response_json,success,created_at) VALUES (?,?,?,?,?,?)",
                conversationId, toolName, requestJson, responseJson, success, Timestamp.valueOf(LocalDateTime.now()));
        progress.toolResult(conversationId, toolName, requestJson, responseJson, success);
    }

    public List<ToolTrace> findByConversation(String conversationId) {
        return jdbc.query("""
                SELECT tool_name,request_json,response_json,success FROM (
                  SELECT id,tool_name,request_json,response_json,success
                  FROM tool_call_logs WHERE conversation_id=? ORDER BY id DESC LIMIT 12
                ) recent ORDER BY id
                """,
                (rs, row) -> new ToolTrace(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)), conversationId);
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            return "{\"message\":\"unable to serialize trace\"}";
        }
    }
}
