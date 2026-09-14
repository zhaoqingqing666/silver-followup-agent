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

    /**
     * 这个会话当前最大的一条追踪 id；没有记录时是 0。
     *
     * <p>配合 {@link #since} 用来圈出「本轮工具调用」：进循环前取一次，之后读到的都是这一轮真的跑过的。
     * 拿它当分界，比「跑完再去对比整张表」稳——中间没有清理，也没有上一条用户消息的残留。
     */
    public long latestId(String conversationId) {
        Long value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM tool_call_logs WHERE conversation_id = ?",
                Long.class, conversationId);
        return value == null ? 0L : value;
    }

    /**
     * 分界之后新增的真实工具调用，按发生顺序。
     *
     * <p>校验模型给的证据引用时读的就是它：{@code response_json} 是唯一能回答「这次到底查到没有」
     * 的地方——只会话里那段 {@code outcomeKind} 几乎恒为 SUCCESS，拿它当「有证据」等于没查。
     */
    public List<ToolTrace> since(String conversationId, long afterId) {
        return jdbc.query("""
                SELECT tool_name,request_json,response_json,success FROM tool_call_logs
                WHERE conversation_id = ? AND id > ? ORDER BY id
                """,
                (rs, row) -> new ToolTrace(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)),
                conversationId, afterId);
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
