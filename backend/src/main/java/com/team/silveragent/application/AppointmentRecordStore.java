package com.team.silveragent.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class AppointmentRecordStore {
    private final JdbcTemplate jdbc;

    public AppointmentRecordStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void complete(String appointmentId, ConversationState state, String reminderStatus, String familyStatus) {
        Timestamp departure = state.travelPlan == null ? null : Timestamp.valueOf(state.travelPlan.departureAt());
        jdbc.update("""
                UPDATE appointments SET departure_time=?,transport=?,reminder_status=?,family_status=?,materials=?
                WHERE id=?
                """, departure, state.transport, reminderStatus, familyStatus,
                String.join("、", state.materials), appointmentId);
    }

    public List<AppointmentView> allFor(String userId) {
        return jdbc.query("""
                SELECT a.id,s.hospital_name,s.department,s.appointment_date,s.appointment_time,
                       a.departure_time,a.transport,a.reminder_status,a.family_status,a.materials,
                       a.status,a.created_at
                FROM appointments a JOIN appointment_slots s ON s.id=a.slot_id
                WHERE a.user_id=? AND a.status != 'CANCELLED'
                ORDER BY a.created_at DESC
                """, (rs, row) -> new AppointmentView(
                rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getDate(4).toLocalDate(), rs.getTime(5).toLocalTime(),
                rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toLocalDateTime(),
                rs.getString(7), rs.getString(8), rs.getString(9),
                splitMaterials(rs.getString(10)), rs.getString(11),
                rs.getTimestamp(12).toLocalDateTime()), userId);
    }

    /** 取消预约：更新状态为 CANCELLED，并释放对应的模拟号源 */
    @org.springframework.transaction.annotation.Transactional
    public boolean cancel(String appointmentId, String userId) {
        int rows = jdbc.update("UPDATE appointments SET status='CANCELLED' WHERE id=? AND user_id=? AND status='CONFIRMED'",
                appointmentId, userId);
        if (rows == 0) return false;
        // 释放对应号源，与 MockAppointmentTool.cancel 行为对齐
        String slotId = jdbc.queryForObject(
                "SELECT slot_id FROM appointments WHERE id=? AND user_id=?",
                String.class, appointmentId, userId);
        if (slotId != null) {
            jdbc.update("UPDATE appointment_slots SET available=TRUE WHERE id=?", slotId);
        }
        return true;
    }

    private List<String> splitMaterials(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split("、"));
    }

    public record AppointmentView(
            String appointmentId, String hospital, String department,
            java.time.LocalDate date, java.time.LocalTime time,
            LocalDateTime departureAt, String transport,
            String reminderStatus, String familyStatus, List<String> materials,
            String status, LocalDateTime createdAt
    ) { }
}
