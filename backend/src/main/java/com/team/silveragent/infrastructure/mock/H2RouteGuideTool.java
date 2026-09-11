package com.team.silveragent.infrastructure.mock;

import com.team.silveragent.domain.model.ToolModels.GeoPoint;
import com.team.silveragent.domain.model.ToolModels.RouteGuide;
import com.team.silveragent.domain.tool.RouteGuideTool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Component
public class H2RouteGuideTool implements RouteGuideTool {
    private final JdbcTemplate jdbc;
    private final ToolTraceStore traces;

    public H2RouteGuideTool(JdbcTemplate jdbc, ToolTraceStore traces) {
        this.jdbc = jdbc;
        this.traces = traces;
    }

    @Override
    public RouteGuide plan(String conversationId, String userId, String hospitalId,
                           LocalDateTime appointmentAt, String transport) {
        List<RouteGuide> rows = jdbc.query("""
                SELECT r.transport,r.duration_minutes,COALESCE(r.distance_meters,0),
                       u.home_longitude,u.home_latitude,h.longitude,h.latitude,
                       r.polyline,r.route_steps,COALESCE(r.route_source,'SIMULATED')
                FROM users u JOIN hospitals h ON h.id=?
                JOIN travel_routes r ON r.origin=u.home_address AND r.destination=h.address AND r.transport=?
                WHERE u.id=? ORDER BY r.id LIMIT 1
                """, (rs, row) -> new RouteGuide(
                rs.getString(1), rs.getInt(2), rs.getInt(3),
                appointmentAt.minusMinutes(rs.getInt(2) + 20L),
                new GeoPoint(rs.getDouble(4), rs.getDouble(5)),
                new GeoPoint(rs.getDouble(6), rs.getDouble(7)),
                points(rs.getString(8)), split(rs.getString(9)), rs.getString(10)),
                hospitalId, transport, userId);
        if (rows.isEmpty()) throw new IllegalStateException("没有配置该交通方式的路线指引");
        RouteGuide result = rows.get(0);
        traces.record(conversationId, "travel.routePlan",
                Map.of("userId", userId, "hospitalId", hospitalId,
                        "transport", transport, "appointmentAt", appointmentAt), result, true);
        return result;
    }

    private List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split("\\|"))
                .map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private List<GeoPoint> points(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(";"))
                .map(item -> item.split(","))
                .filter(parts -> parts.length == 2)
                .map(parts -> new GeoPoint(Double.parseDouble(parts[0]), Double.parseDouble(parts[1])))
                .toList();
    }
}
