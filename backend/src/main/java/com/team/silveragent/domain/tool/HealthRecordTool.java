package com.team.silveragent.domain.tool;

import com.team.silveragent.application.health.HealthRecordStore;

import java.math.BigDecimal;
import java.util.List;

/** 老人实测数值（血压/血糖/心率…）的读写工具；写入只来自助手对话。 */
public interface HealthRecordTool {
    /** @param valueNum 能取到数时的数（血压 100/60 取 100）；只有说法（“有点高”）时为 null。 */
    HealthRecordStore.RecordView create(String conversationId, String userId, String item,
                                        BigDecimal valueNum, String valueText, String unit, String rawText,
                                        java.time.LocalDateTime recordedAt);

    /** @param item 传 null 表示不限项目；按时间倒序取最近 limit 条。 */
    List<HealthRecordStore.RecordView> recent(String conversationId, String userId, String item, int limit);
}
