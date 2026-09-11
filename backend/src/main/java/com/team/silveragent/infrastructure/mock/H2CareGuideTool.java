package com.team.silveragent.infrastructure.mock;

import com.team.silveragent.domain.tool.CareGuideTool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** H2 中的模拟复诊办事知识库。所有结果均明确来自本地演示数据。 */
@Component
public class H2CareGuideTool implements CareGuideTool {
    private final JdbcTemplate jdbc;
    private final ToolTraceStore traces;

    public H2CareGuideTool(JdbcTemplate jdbc, ToolTraceStore traces) {
        this.jdbc = jdbc;
        this.traces = traces;
    }

    @Override
    public List<GuideArticle> search(String conversationId, String query, String hospital, String department) {
        String safeQuery = query == null || query.isBlank() ? "复诊办理流程" : query.trim();
        List<Row> candidates = jdbc.query("""
                SELECT title,content,keywords,hospital_name,department,sort_order
                FROM care_guide_articles
                WHERE enabled=TRUE
                  AND (hospital_name IS NULL OR hospital_name=?)
                  AND (department IS NULL OR department=?)
                ORDER BY sort_order
                """, (rs, row) -> new Row(rs.getString("title"), rs.getString("content"),
                        rs.getString("keywords"), rs.getString("hospital_name"),
                        rs.getString("department"), rs.getInt("sort_order")),
                hospital, department);

        List<GuideArticle> result = candidates.stream()
                .map(row -> Map.entry(row, score(row, safeQuery)))
                .filter(item -> item.getValue() > 0)
                .sorted(Map.Entry.<Row, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(item -> item.getKey().sortOrder()))
                .limit(4)
                .map(item -> new GuideArticle(item.getKey().title(), item.getKey().content()))
                .toList();
        if (result.isEmpty()) {
            result = candidates.stream().limit(2)
                    .map(row -> new GuideArticle(row.title(), row.content())).toList();
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("query", safeQuery);
        parameters.put("hospital", hospital == null ? "未指定" : hospital);
        parameters.put("department", department == null ? "未指定" : department);
        traces.record(conversationId, "careGuide.search", parameters, result, true);
        return result;
    }

    private int score(Row row, String query) {
        int score = contains(query, row.title()) ? 4 : 0;
        for (String keyword : splitKeywords(row.keywords())) {
            if (contains(query, keyword)) score += 3;
        }
        if (contains(query, "流程") && contains(row.keywords(), "流程")) score += 2;
        if (contains(query, "材料") && contains(row.keywords(), "材料")) score += 2;
        if (contains(query, "护士") || contains(query, "医生") || contains(query, "咨询")) {
            if (contains(row.keywords(), "咨询")) score += 2;
        }
        return score;
    }

    private List<String> splitKeywords(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String item : value.split("[,，]")) if (!item.isBlank()) result.add(item.trim());
        return result;
    }

    private boolean contains(String value, String part) {
        return value != null && part != null && !part.isBlank() && value.contains(part);
    }

    private record Row(String title, String content, String keywords,
                       String hospital, String department, int sortOrder) { }
}
