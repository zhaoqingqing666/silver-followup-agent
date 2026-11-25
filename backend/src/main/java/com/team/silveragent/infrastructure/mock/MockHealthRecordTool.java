package com.team.silveragent.infrastructure.mock;

import com.team.silveragent.application.health.HealthRecordStore;
import com.team.silveragent.domain.tool.HealthRecordTool;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class MockHealthRecordTool implements HealthRecordTool {
    private final HealthRecordStore records;
    private final ToolTraceStore traces;

    public MockHealthRecordTool(HealthRecordStore records, ToolTraceStore traces) {
        this.records = records;
        this.traces = traces;
    }

    @Override
    public HealthRecordStore.RecordView create(String conversationId, String userId, String item,
                                               BigDecimal valueNum, String valueText, String unit, String rawText,
                                               LocalDateTime recordedAt) {
        HealthRecordStore.RecordView view = records.create(userId, item, valueNum, valueText, unit, rawText,
                conversationId, recordedAt);
        Map<String, Object> request = new HashMap<>();
        request.put("userId", userId);
        request.put("item", item);
        request.put("value", valueText);
        request.put("unit", unit);
        traces.record(conversationId, "healthRecord.create", request,
                Map.of("recordId", view.id(), "recordedAt", view.recordedAt()), true);
        return view;
    }

    @Override
    public List<HealthRecordStore.RecordView> recent(String conversationId, String userId, String item, int limit) {
        List<HealthRecordStore.RecordView> rows = records.recent(userId, item, limit);
        traces.record(conversationId, "healthRecord.query",
                Map.of("userId", userId, "item", item == null ? "全部" : item, "limit", limit),
                Map.of("count", rows.size()), true);
        return rows;
    }

    /**
     * 删掉最近一条。没有可删的也留一条留痕、判成失败：老人说了“记错了”而库里一条都没有，
     * 这件事本身得看得见，不能因为“本来就没东西可删”就当它成功了。
     */
    @Override
    public HealthRecordStore.RecordView deleteLatest(String conversationId, String userId) {
        HealthRecordStore.RecordView removed = records.deleteLatest(userId);
        Map<String, Object> request = new HashMap<>();
        request.put("userId", userId);
        request.put("item", removed == null ? null : removed.item());
        request.put("value", removed == null ? null : removed.valueText());
        traces.record(conversationId, "healthRecord.delete", request,
                Map.of("removed", removed != null), removed != null);
        return removed;
    }
}
