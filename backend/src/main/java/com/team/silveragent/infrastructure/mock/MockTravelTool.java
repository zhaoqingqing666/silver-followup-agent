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
        String origin = requiredSingle("SELECT home_address FROM users WHERE id=?", userId, "没有配置用户出发地址");
        // 路线表按 hospital_id 关联医院，目的地名称只用于话术：不再拿地址字符串去匹配路线。
        List<Object[]> targets = jdbc.query("""
                SELECT id,address FROM hospitals
                WHERE REPLACE(name,'（模拟）','')=?
                """, (rs, row) -> new Object[]{rs.getString(1), rs.getString(2)}, cleanHospital(hospital));
        if (targets.isEmpty()) throw new IllegalStateException("没有配置医院地址");
        String hospitalId = (String) targets.get(0)[0];
        String destination = (String) targets.get(0)[1];
        Integer configured = duration(hospitalId, origin, transport);
        if (configured == null) throw new IllegalStateException("没有配置该交通方式的模拟路线");
        int duration = configured;
        LocalDateTime departure = appointmentAt.minusMinutes(duration + 20L);
        TravelPlan result = new TravelPlan(transport, duration, departure,
                "从" + origin + "前往" + destination + "，预计" + duration +
                        "分钟，并预留20分钟取号时间");
        traces.record(conversationId, "travel.plan",
                Map.of("userId", userId, "origin", origin, "destination", destination,
                        "appointmentAt", appointmentAt, "transport", transport), result, true);
        return result;
    }

    private Integer duration(String hospitalId, String origin, String transport) {
        List<Integer> rows = jdbc.query("""
                SELECT duration_minutes FROM travel_routes
                WHERE origin=? AND hospital_id=? AND transport=?
                ORDER BY id LIMIT 1
                """, (rs, row) -> rs.getInt(1), origin, hospitalId, transport);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String requiredSingle(String sql, String parameter, String errorMessage) {
        List<String> rows = jdbc.query(sql, (rs, row) -> rs.getString(1), parameter);
        if (rows.isEmpty() || rows.get(0) == null || rows.get(0).isBlank()) {
            throw new IllegalStateException(errorMessage);
        }
        return rows.get(0);
    }

    private String cleanHospital(String hospital) {
        return hospital.replace("（模拟）", "").replace("(模拟)", "");
    }
}
