package com.team.silveragent.infrastructure.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.silveragent.domain.model.AgentTurnResponse.ToolTrace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
                (rs, row) -> {
                    String toolName = rs.getString(1);
                    String responseJson = rs.getString(3);
                    return new ToolTrace(toolName, ToolTrace.friendlyName(toolName), rs.getString(2),
                            responseJson, summarize(responseJson), rs.getBoolean(4));
                }, conversationId);
    }

    /**
     * 把真实的工具返回结果压成一句老人能看懂的话。只做概括，不改写事实：
     * 数组说条数，对象取关键字段，文本直接截断。表结构不变，历史记录同样适用。
     */
    private String summarize(String responseJson) {
        if (responseJson == null || responseJson.isBlank()) return "已完成";
        try {
            JsonNode node = json.readTree(responseJson);
            if (node.isArray()) {
                int size = node.size();
                return size == 0 ? "没有查到相关记录" : "查询完成，返回 " + size + " 条记录";
            }
            if (node.isObject()) {
                JsonNode message = node.get("message");
                if (message != null && message.isValueNode()) return message.asText();
                List<String> parts = new ArrayList<>();
                node.fields().forEachRemaining(entry -> {
                    JsonNode value = entry.getValue();
                    if (parts.size() < 2 && value != null && value.isValueNode() && !value.isNull()) {
                        parts.add(truncate(value.asText(), 60));
                    }
                });
                return parts.isEmpty() ? "查询完成" : "查询完成：" + String.join("；", parts);
            }
            if (node.isValueNode()) return truncate(node.asText(), 80);
        } catch (Exception ignored) {
            // 结果不是合法 JSON 时走下面的兜底文案
        }
        return "已返回结果";
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            return "{\"message\":\"unable to serialize trace\"}";
        }
    }
}
