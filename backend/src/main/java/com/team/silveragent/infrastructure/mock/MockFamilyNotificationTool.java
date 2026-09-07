package com.team.silveragent.infrastructure.mock;

import com.team.silveragent.domain.model.ToolModels.Contact;
import com.team.silveragent.domain.tool.FamilyNotificationTool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Component
public class MockFamilyNotificationTool implements FamilyNotificationTool {
    private final JdbcTemplate jdbc;
    private final ToolTraceStore traces;

    public MockFamilyNotificationTool(JdbcTemplate jdbc, ToolTraceStore traces) {
        this.jdbc = jdbc;
        this.traces = traces;
    }

    @Override
    public Contact findPrimaryContact(String conversationId, String userId) {
        Contact result = jdbc.queryForObject("""
                SELECT id,name,relationship,phone FROM family_contacts
                WHERE user_id=? ORDER BY id LIMIT 1
                """, (rs, row) -> new Contact(rs.getString(1), rs.getString(2),
                rs.getString(3), mask(rs.getString(4))), userId);
        traces.record(conversationId, "family.queryContact", Map.of("userId", userId), result, true);
        return result;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public String notify(String conversationId, String contactId, String message) {
        String id = "NT-" + UUID.nameUUIDFromBytes((conversationId + contactId + message).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!jdbc.query("SELECT id FROM family_notifications WHERE id=?", (rs, row) -> rs.getString(1), id).isEmpty()) return id;
        jdbc.update("INSERT INTO family_notifications(id,contact_id,content,status,created_at,conversation_id) VALUES (?,?,?,?,?,?)",
                id, contactId, message, "SENT", Timestamp.valueOf(LocalDateTime.now()), conversationId);
        traces.record(conversationId, "family.notify",
                Map.of("contactId", contactId, "message", message),
                Map.of("notificationId", id, "status", "SENT"), true);
        return id;
    }

    private String mask(String phone) {
        return phone.length() < 7 ? phone : phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
