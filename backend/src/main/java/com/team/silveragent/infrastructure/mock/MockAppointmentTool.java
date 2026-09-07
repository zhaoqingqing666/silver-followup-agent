package com.team.silveragent.infrastructure.mock;

import com.team.silveragent.domain.model.ToolModels.Slot;
import com.team.silveragent.domain.tool.AppointmentTool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class MockAppointmentTool implements AppointmentTool {
    private final JdbcTemplate jdbc;
    private final ToolTraceStore traces;

    public MockAppointmentTool(JdbcTemplate jdbc, ToolTraceStore traces) {
        this.jdbc = jdbc;
        this.traces = traces;
    }

    @Override
    public List<Slot> queryAvailableSlots(String conversationId, String hospitalId, String department, LocalDate date) {
        Map<String, Object> input = Map.of("hospitalId", hospitalId, "department", department, "date", date);
        List<Slot> result = jdbc.query("""
                SELECT id,hospital_id,hospital_name,department,appointment_date,appointment_time
                FROM appointment_slots
                WHERE hospital_id=? AND department=?
                  AND appointment_date=? AND available=TRUE
                ORDER BY appointment_time
                """, slotMapper(), hospitalId, department, Date.valueOf(date));
        traces.record(conversationId, "appointment.querySlots", input, result, true);
        return result;
    }

    @Override
    public List<Slot> queryAlternatives(String conversationId, String hospitalId, String department, LocalDate date) {
        Map<String, Object> input = Map.of("hospitalId", hospitalId, "department", department,
                "from", date.minusDays(3), "to", date.plusDays(3));
        List<Slot> result = jdbc.query("""
                SELECT id,hospital_id,hospital_name,department,appointment_date,appointment_time
                FROM appointment_slots
                WHERE hospital_id=? AND department=?
                  AND appointment_date BETWEEN ? AND ? AND available=TRUE
                ORDER BY appointment_date,appointment_time
                """, slotMapper(), hospitalId, department,
                Date.valueOf(date.minusDays(3)), Date.valueOf(date.plusDays(3)));
        traces.record(conversationId, "appointment.queryAlternatives", input, result, true);
        return result;
    }

    @Override
    @Transactional
    public String submit(String conversationId, String slotId, String userId) {
        List<String> existing = jdbc.query("SELECT id FROM appointments WHERE conversation_id=? AND user_id=? AND status='CONFIRMED'", (rs, row) -> rs.getString(1), conversationId, userId);
        if (!existing.isEmpty()) return existing.get(0);
        int changed = jdbc.update("UPDATE appointment_slots SET available=FALSE WHERE id=? AND available=TRUE", slotId);
        if (changed != 1) throw new IllegalStateException("该号源刚刚已不可用，请重新选择");
        String id = "AP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbc.update("INSERT INTO appointments(id,slot_id,user_id,status,created_at,conversation_id) VALUES (?,?,?,?,?,?)",
                id, slotId, userId, "CONFIRMED", Timestamp.valueOf(LocalDateTime.now()), conversationId);
        traces.record(conversationId, "appointment.submit", Map.of("slotId", slotId, "userId", userId),
                Map.of("appointmentId", id, "status", "CONFIRMED"), true);
        return id;
    }

    @Override
    @Transactional
    public String cancel(String conversationId, String appointmentId, String userId) {
        String slotId = jdbc.queryForObject(
                "SELECT slot_id FROM appointments WHERE id=? AND user_id=? AND status='CONFIRMED'",
                String.class, appointmentId, userId);
        int changed = jdbc.update(
                "UPDATE appointments SET status='CANCELLED' WHERE id=? AND user_id=? AND status='CONFIRMED'",
                appointmentId, userId);
        if (changed != 1) throw new IllegalStateException("没有找到可取消的预约");
        jdbc.update("UPDATE appointment_slots SET available=TRUE WHERE id=?", slotId);
        jdbc.update("UPDATE reminders SET status='CANCELLED' WHERE appointment_id=?", appointmentId);
        jdbc.update("UPDATE appointments SET reminder_status='关联提醒已取消' WHERE id=?", appointmentId);
        traces.record(conversationId, "appointment.cancel",
                Map.of("appointmentId", appointmentId, "userId", userId),
                Map.of("status", "CANCELLED", "slotReleased", true), true);
        return "CANCELLED";
    }

    @Override
    @Transactional
    public String reschedule(String conversationId, String appointmentId, String slotId, String userId) {
        String oldSlot = jdbc.queryForObject("SELECT slot_id FROM appointments WHERE id=? AND user_id=? AND status='CONFIRMED'", String.class, appointmentId, userId);
        if (!slotId.equals(oldSlot)) {
            int changed = jdbc.update("UPDATE appointment_slots SET available=FALSE WHERE id=? AND available=TRUE", slotId);
            if (changed != 1) throw new IllegalStateException("新号源不可用，原预约保留");
            jdbc.update("UPDATE appointment_slots SET available=TRUE WHERE id=?", oldSlot);
        }
        jdbc.update("UPDATE appointments SET slot_id=?,conversation_id=? WHERE id=?", slotId, conversationId, appointmentId);
        jdbc.update("UPDATE reminders SET status='CANCELLED' WHERE appointment_id=?", appointmentId);
        traces.record(conversationId, "appointment.reschedule", Map.of("appointmentId", appointmentId, "slotId", slotId), Map.of("status", "CONFIRMED"), true);
        return appointmentId;
    }

    private org.springframework.jdbc.core.RowMapper<Slot> slotMapper() {
        return (rs, row) -> new Slot(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getDate(5).toLocalDate(), rs.getTime(6).toLocalTime());
    }

}
