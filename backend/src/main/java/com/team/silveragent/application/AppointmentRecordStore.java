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

    /** 家属/志愿者代约或代约改期后回填预约详情、安排者与陪同照护者标记。 */
    public void applyBooking(String appointmentId, java.sql.Timestamp departure, String transport,
                             String reminderStatus, String familyStatus, List<String> materials,
                             String arrangedBy, String accompaniedBy) {
        jdbc.update("""
                UPDATE appointments SET departure_time=?,transport=?,reminder_status=?,family_status=?,materials=?,
                arranged_by=?,accompanied_by=? WHERE id=?
                """, departure, transport, reminderStatus, familyStatus,
                String.join("、", materials), arrangedBy, accompaniedBy, appointmentId);
    }

    public List<AppointmentView> allFor(String userId) {
        List<AppointmentView> rows = jdbc.query("""
                SELECT a.id,s.hospital_name,s.department,s.appointment_date,s.appointment_time,
                       a.departure_time,a.transport,a.reminder_status,a.family_status,a.materials,
                       a.status,a.created_at,a.arranged_by,a.accompanied_by,
                       (SELECT r.relationship FROM care_relations r
                        WHERE r.caregiver_id=a.arranged_by AND r.elder_user_id=a.user_id) AS arr_rel,
                       arr.name AS arr_name
                FROM appointments a JOIN appointment_slots s ON s.id=a.slot_id
                LEFT JOIN users arr ON arr.id=a.arranged_by
                WHERE a.user_id=?
                ORDER BY a.created_at DESC
                """, (rs, row) -> {
            String arrangedBy = rs.getString(13);
            String accompaniedBy = rs.getString(14);
            String relationship = rs.getString(15);
            String arrangerName = rs.getString(16);
            String arrangedLabel = arrangedBy == null ? null
                    : (relationship == null || relationship.isBlank() ? "家属" : relationship)
                    + " " + (arrangerName == null ? "" : arrangerName);
            return new AppointmentView(
                    rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getDate(4).toLocalDate(), rs.getTime(5).toLocalTime(),
                    rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toLocalDateTime(),
                    rs.getString(7), rs.getString(8), rs.getString(9),
                    splitMaterials(rs.getString(10)), List.of(), rs.getString(11),
                    rs.getTimestamp(12).toLocalDateTime(), arrangedBy, arrangedLabel, accompaniedBy);
        }, userId);
        return rows.stream().map(row -> new AppointmentView(
                row.appointmentId(), row.hospital(), row.department(), row.date(), row.time(),
                row.departureAt(), row.transport(), row.reminderStatus(), row.familyStatus(),
                row.materials(), requiredMaterials(row.department()), row.status(), row.createdAt(),
                row.arrangedBy(), row.arrangedLabel(), row.accompaniedBy())).toList();
    }

    private List<String> requiredMaterials(String department) {
        return jdbc.query("""
                SELECT material_name FROM material_templates
                WHERE (department='通用' OR department=?) AND required=TRUE
                ORDER BY sort_order
                """, (rs, row) -> rs.getString(1), department);
    }

    private List<String> splitMaterials(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split("、"));
    }

    public record AppointmentView(
            String appointmentId, String hospital, String department,
            java.time.LocalDate date, java.time.LocalTime time,
            LocalDateTime departureAt, String transport,
            String reminderStatus, String familyStatus, List<String> materials,
            List<String> requiredMaterials, String status, LocalDateTime createdAt,
            String arrangedBy, String arrangedLabel, String accompaniedBy
    ) { }
}
