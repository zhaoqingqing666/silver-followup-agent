package com.team.silveragent.application;

import com.team.silveragent.domain.model.ToolModels.AppointmentTravelGuide;
import com.team.silveragent.domain.model.ToolModels.FacilityGuide;
import com.team.silveragent.domain.model.ToolModels.RouteGuide;
import com.team.silveragent.domain.tool.FacilityGuideTool;
import com.team.silveragent.domain.tool.RouteGuideTool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TravelGuideService {
    private final JdbcTemplate jdbc;
    private final RouteGuideTool routes;
    private final FacilityGuideTool facilities;

    public TravelGuideService(JdbcTemplate jdbc, RouteGuideTool routes, FacilityGuideTool facilities) {
        this.jdbc = jdbc;
        this.routes = routes;
        this.facilities = facilities;
    }

    public AppointmentTravelGuide forAppointment(String userId, String appointmentId) {
        return forAppointment(userId, appointmentId, null);
    }

    public AppointmentTravelGuide forAppointment(String userId, String appointmentId,
                                                  String traceConversationId) {
        List<Context> rows = jdbc.query("""
                SELECT a.id,COALESCE(a.conversation_id,'appointment-' || a.id),
                       s.hospital_id,h.name,s.department,d.id,s.clinic_location_id,
                       s.appointment_date,s.appointment_time,
                       COALESCE(a.transport,u.preferred_transport,'家属开车')
                FROM appointments a
                JOIN appointment_slots s ON s.id=a.slot_id
                JOIN hospitals h ON h.id=s.hospital_id
                JOIN departments d ON d.hospital_id=s.hospital_id AND d.name=s.department
                JOIN users u ON u.id=a.user_id
                WHERE a.user_id=? AND a.id=?
                ORDER BY d.id LIMIT 1
                """, (rs, row) -> new Context(
                rs.getString(1), rs.getString(2), rs.getString(3), clean(rs.getString(4)),
                rs.getString(5), rs.getString(6), rs.getString(7),
                LocalDateTime.of(rs.getDate(8).toLocalDate(), rs.getTime(9).toLocalTime()),
                rs.getString(10)), userId, appointmentId);
        if (rows.isEmpty()) throw new IllegalArgumentException("没有找到这条复诊预约");
        Context context = rows.get(0);
        String conversationId = traceConversationId == null || traceConversationId.isBlank()
                ? context.conversationId() : traceConversationId;
        RouteGuide route = routes.plan(conversationId, userId, context.hospitalId(),
                context.appointmentAt(), context.transport());
        FacilityGuide facility = facilities.find(conversationId, context.locationId(),
                context.hospitalId(), context.departmentId());
        return new AppointmentTravelGuide(context.appointmentId(), context.hospital(), context.department(),
                context.appointmentAt(), route, facility, "SIMULATED".equals(route.source()));
    }

    private String clean(String value) {
        return value == null ? "" : value.replace("（模拟）", "").replace("(模拟)", "");
    }

    private record Context(String appointmentId, String conversationId, String hospitalId,
                           String hospital, String department, String departmentId,
                           String locationId, LocalDateTime appointmentAt, String transport) { }
}
