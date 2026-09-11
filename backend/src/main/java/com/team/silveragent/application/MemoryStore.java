package com.team.silveragent.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 用户复诊历史记忆存储：结构化保存每次复诊办理结果，供 Agent 跨对话检索历史。
 */
@Repository
public class MemoryStore {
    private final JdbcTemplate jdbc;

    public MemoryStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 记录一条复诊记忆（CONFIRMED / CANCELLED）。 */
    public void save(String userId, String hospital, String department,
                     LocalDate appointmentDate, String result,
                     String appointmentId, String conversationId) {
        jdbc.update("""
                INSERT INTO user_memories(id,user_id,hospital,department,appointment_date,
                    result,appointment_id,conversation_id,created_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, "MEM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                userId, hospital, department,
                appointmentDate == null ? null : Date.valueOf(appointmentDate),
                result, appointmentId, conversationId, Timestamp.valueOf(LocalDateTime.now()));
    }

    /** 取用户最近 limit 条复诊记忆（按时间倒序）。 */
    public List<UserMemory> recentByUser(String userId, int limit) {
        return jdbc.query("""
                SELECT id,user_id,hospital,department,appointment_date,result,appointment_id,conversation_id,created_at
                FROM user_memories WHERE user_id=? ORDER BY created_at DESC LIMIT ?
                """, (rs, row) -> new UserMemory(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getDate(5) == null ? null : rs.getDate(5).toLocalDate(),
                rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getTimestamp(9).toLocalDateTime()), userId, limit);
    }

    public record UserMemory(
            String id, String userId, String hospital, String department,
            LocalDate appointmentDate, String result, String appointmentId,
            String conversationId, LocalDateTime createdAt
    ) { }
}
