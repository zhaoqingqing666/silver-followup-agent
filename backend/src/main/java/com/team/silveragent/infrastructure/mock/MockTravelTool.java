package com.team.silveragent.infrastructure.mock;

import com.team.silveragent.domain.model.ToolModels.TravelPlan;
import com.team.silveragent.domain.tool.TravelTool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Component
public class MockTravelTool implements TravelTool {
    private final JdbcTemplate jdbc;
    private final ToolTraceStore traces;

    public MockTravelTool(JdbcTemplate jdbc, ToolTraceStore traces) {
        this.jdbc = jdbc;
        this.traces = traces;
    }

    @Override
    public TravelPlan plan(String conversationId, String userId, String hospital,
                           LocalDateTime appointmentAt, String transport) {
        String origin = single("SELECT home_address FROM users WHERE id=?", userId, "幸福小区（模拟）");
        String destination = single("""
                SELECT address FROM hospitals
                WHERE REPLACE(name,'（模拟）','')=?
                """, cleanHospital(hospital), hospital + "（模拟地址）");
        Integer configured = duration(origin, destination, transport);
        int duration = configured != null ? configured : fallbackDuration(transport);
        LocalDateTime departure = appointmentAt.minusMinutes(duration + 20L);
        TravelPlan result = new TravelPlan(transport, duration, departure,
                "从" + origin + "前往" + destination + "，预计" + duration +
                        "分钟，并预留20分钟取号时间");
        traces.record(conversationId, "travel.plan",
                Map.of("userId", userId, "origin", origin, "destination", destination,
                        "appointmentAt", appointmentAt, "transport", transport), result, true);
        return result;
    }

    private Integer duration(String origin, String destination, String transport) {
        List<Integer> rows = jdbc.query("""
                SELECT duration_minutes FROM travel_routes
                WHERE origin=? AND destination=? AND transport=?
                ORDER BY id LIMIT 1
                """, (rs, row) -> rs.getInt(1), origin, destination, transport);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String single(String sql, String parameter, String fallback) {
        List<String> rows = jdbc.query(sql, (rs, row) -> rs.getString(1), parameter);
        return rows.isEmpty() ? fallback : rows.get(0);
    }

    private int fallbackDuration(String transport) {
        return "步行".equals(transport) ? 70 : "公交".equals(transport) ? 50 :
                "家属开车".equals(transport) ? 30 : 35;
    }

    private String cleanHospital(String hospital) {
        return hospital.replace("（模拟）", "").replace("(模拟)", "");
    }
}
