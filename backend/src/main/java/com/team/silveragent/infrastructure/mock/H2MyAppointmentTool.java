package com.team.silveragent.infrastructure.mock;

import com.team.silveragent.domain.model.ToolModels.AppointmentSummary;
import com.team.silveragent.domain.tool.MyAppointmentTool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Component
public class H2MyAppointmentTool implements MyAppointmentTool {
    private final JdbcTemplate jdbc;
    private final ToolTraceStore traces;

    public H2MyAppointmentTool(JdbcTemplate jdbc, ToolTraceStore traces) {
        this.jdbc = jdbc;
        this.traces = traces;
    }

    @Override
    public List<AppointmentSummary> search(String conversationId, String userId, LocalDate date,
                                           String hospital, String department) {
        Date sqlDate = date == null ? null : Date.valueOf(date);
        String hospitalLike = blank(hospital) ? null : "%" + hospital.trim() + "%";
        String departmentLike = blank(department) ? null : "%" + department.trim() + "%";
        List<AppointmentSummary> rows = jdbc.query("""
                SELECT a.id,s.hospital_name,s.department,s.appointment_date,s.appointment_time,
                       a.status,a.departure_time,a.transport,a.reminder_status,a.family_status,a.materials
                FROM appointments a JOIN appointment_slots s ON s.id=a.slot_id
                WHERE a.user_id=? AND a.status='CONFIRMED'
                  AND (? IS NULL OR s.appointment_date=?)
                  AND (? IS NULL OR s.hospital_name LIKE ?)
                  AND (? IS NULL OR s.department LIKE ?)
                ORDER BY s.appointment_date,s.appointment_time
                """, (rs, row) -> new AppointmentSummary(
                rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getDate(4).toLocalDate(), rs.getTime(5).toLocalTime(), rs.getString(6),
                rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toLocalDateTime(),
                rs.getString(8), rs.getString(9), rs.getString(10), split(rs.getString(11))),
                userId, sqlDate, sqlDate, hospitalLike, hospitalLike, departmentLike, departmentLike);
        traces.record(conversationId, "appointment.queryMine",
                Map.of("userId", userId, "date", value(date), "hospital", value(hospital),
                        "department", value(department)),
                Map.of("count", rows.size(), "appointments", rows), true);
        return rows;
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    private String value(Object value) { return value == null ? "未指定" : value.toString(); }

    private List<String> split(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split("、"));
    }
}
