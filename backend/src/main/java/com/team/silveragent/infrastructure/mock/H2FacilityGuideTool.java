package com.team.silveragent.infrastructure.mock;

import com.team.silveragent.domain.model.ToolModels.FacilityGuide;
import com.team.silveragent.domain.tool.FacilityGuideTool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class H2FacilityGuideTool implements FacilityGuideTool {
    private final JdbcTemplate jdbc;
    private final ToolTraceStore traces;

    public H2FacilityGuideTool(JdbcTemplate jdbc, ToolTraceStore traces) {
        this.jdbc = jdbc;
        this.traces = traces;
    }

    @Override
    public FacilityGuide find(String conversationId, String locationId,
                              String hospitalId, String departmentId) {
        List<FacilityGuide> rows = jdbc.query("""
                SELECT hospital_id,department_id,building_name,entrance_name,floor_name,room_name,
                       check_in_point,landmark,accessible_route_hint,help_desk,verified_at
                FROM clinic_locations
                WHERE enabled=TRUE AND ((? IS NOT NULL AND id=?)
                   OR (? IS NULL AND hospital_id=? AND department_id=?))
                ORDER BY id LIMIT 1
                """, (rs, row) -> new FacilityGuide(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getString(10), rs.getTimestamp(11).toLocalDateTime()),
                locationId, locationId, locationId, hospitalId, departmentId);
        if (rows.isEmpty()) throw new IllegalStateException("这条预约还没有配置院内位置指引");
        FacilityGuide result = rows.get(0);
        traces.record(conversationId, "hospital.locationGuide",
                Map.of("locationId", locationId == null ? "" : locationId,
                        "hospitalId", hospitalId, "departmentId", departmentId), result, true);
        return result;
    }
}
