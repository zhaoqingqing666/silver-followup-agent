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
        // department_id 为空就是通用模板；否则在同一家医院里按科室名折算成的那一个科室编号匹配。
        // 模板按科室编号存，两家医院各有一个"心内科"时，不会把另一家的模板带过来。
        List<String> result = jdbc.query("""
                SELECT material_name FROM material_templates
                WHERE department_id IS NULL
                   OR department_id=(
                        SELECT MIN(d.id) FROM departments d JOIN hospitals h ON h.id=d.hospital_id
                        WHERE d.name=? AND (? IS NULL OR h.name=?))
                ORDER BY required DESC, sort_order
                """, (rs, row) -> rs.getString(1), department, hospital, hospital);
        traces.record(conversationId, "material.generateChecklist",
                Map.of("hospital", hospital, "department", department), result, true);
        return result;
    }
}
