package com.team.silveragent.infrastructure.mock;

import com.team.silveragent.domain.model.SimulatedData;
import com.team.silveragent.domain.model.ToolModels.TravelPlan;
import com.team.silveragent.domain.tool.TravelTool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Component
public class MockTravelTool implements TravelTool {
    /** 到院后取号、找诊室要留出的缓冲时间。 */
    private static final int CHECKIN_BUFFER_MINUTES = 20;

    private final JdbcTemplate jdbc;
    private final ToolTraceStore traces;

    public MockTravelTool(JdbcTemplate jdbc, ToolTraceStore traces) {
        this.jdbc = jdbc;
        this.traces = traces;
    }

    @Override
    public TravelPlan plan(String conversationId, String userId, String hospital,
                           LocalDateTime appointmentAt, String transport) {
        String origin = requiredSingle("SELECT home_address FROM users WHERE id=?", "没有配置用户出发地址", userId);
        String destination = requiredSingle("""
                SELECT address FROM hospitals
                WHERE REPLACE(name,?,'')=?
                """, "没有配置医院地址",
                SimulatedData.MARKER_FULL_WIDTH, SimulatedData.stripMarker(hospital));
        Integer configured = duration(origin, destination, transport);
        if (configured == null) throw new IllegalStateException("没有配置该交通方式的模拟路线");
        int duration = configured;
        LocalDateTime departure = appointmentAt.minusMinutes(duration + CHECKIN_BUFFER_MINUTES);
        TravelPlan result = new TravelPlan(transport, duration, departure,
                "从" + origin + "前往" + destination + "，预计" + duration +
                        "分钟，并预留" + CHECKIN_BUFFER_MINUTES + "分钟取号时间");
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

    private String requiredSingle(String sql, String errorMessage, Object... parameters) {
        List<String> rows = jdbc.query(sql, (rs, row) -> rs.getString(1), parameters);
        if (rows.isEmpty() || rows.get(0) == null || rows.get(0).isBlank()) {
            throw new IllegalStateException(errorMessage);
        }
        return rows.get(0);
    }
}
