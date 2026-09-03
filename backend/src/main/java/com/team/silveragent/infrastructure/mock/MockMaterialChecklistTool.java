package com.team.silveragent.infrastructure.mock;

import com.team.silveragent.domain.tool.MaterialChecklistTool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class MockMaterialChecklistTool implements MaterialChecklistTool {
    private final JdbcTemplate jdbc;
    private final ToolTraceStore traces;

    public MockMaterialChecklistTool(JdbcTemplate jdbc, ToolTraceStore traces) {
        this.jdbc = jdbc;
        this.traces = traces;
    }

    @Override
    public List<String> checklist(String conversationId, String hospital, String department) {
        List<String> result = jdbc.query("""
                SELECT material_name FROM material_templates
                WHERE department='通用' OR department=?
                ORDER BY required DESC, sort_order
                """, (rs, row) -> rs.getString(1), department);
        traces.record(conversationId, "material.generateChecklist",
                Map.of("hospital", hospital, "department", department), result, true);
        return result;
    }
}
