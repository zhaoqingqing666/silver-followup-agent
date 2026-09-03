package com.team.silveragent.infrastructure.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    public ToolTraceStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(String conversationId, String toolName, Object request, Object response, boolean success) {
        jdbc.update("INSERT INTO tool_call_logs(conversation_id,tool_name,request_json,response_json,success,created_at) VALUES (?,?,?,?,?,?)",
                conversationId, toolName, toJson(request), toJson(response), success, Timestamp.valueOf(LocalDateTime.now()));
    }

    public List<ToolTrace> findByConversation(String conversationId) {
        return jdbc.query("SELECT tool_name,request_json,response_json,success FROM tool_call_logs WHERE conversation_id=? ORDER BY id",
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
