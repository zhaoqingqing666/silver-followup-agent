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

    /**
     * 主联系人。family_contacts 没有“主”这一列，取同一用户下 id 最小的那条当主联系人；
     * 没有配置就返回 null（调用方据此提示“还没有配置家属联系人”，不是异常）。
     *
     * <p>电话在 Java 层脱敏后再放进 Contact：这个记录类型的第四个字段本来就叫 maskedPhone。
     * 调用方（发周报给家属）只需要联系人的 id、姓名和关系，拿不到也不需要明文号码。
     */
    @Override
    public Contact findPrimaryContact(String conversationId, String userId) {
        return jdbc.query("SELECT id,name,relationship,phone FROM family_contacts WHERE user_id=? ORDER BY id LIMIT 1",
                        (rs, row) -> new Contact(rs.getString(1), rs.getString(2), rs.getString(3),
                                maskPhone(rs.getString(4))),
                        userId)
                .stream().findFirst().orElse(null);
    }

    private String maskPhone(String phone) {
        if (phone == null) return "";
        if (phone.length() < 8) return phone;
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
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
}
