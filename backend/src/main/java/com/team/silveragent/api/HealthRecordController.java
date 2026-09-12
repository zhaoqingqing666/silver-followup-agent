package com.team.silveragent.api;

import com.team.silveragent.application.health.HealthRecordStore;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 老人端“健康记录”：老人上报的实测数值（血压/血糖/心率…）。
 * 和 memos（要做的事）分家：这里只读，写入只走助手对话，页面不提供编辑/删除。
 */
@RestController
@RequestMapping("/api/users/{userId}/health-records")
public class HealthRecordController {
    private static final int DEFAULT_LIMIT = 3;

    private final HealthRecordStore records;

    public HealthRecordController(HealthRecordStore records) { this.records = records; }

    /**
     * 按测量时间倒序取记录。
     *
     * @param limit  最多几条，缺省 3 条（首页只露最近几条）。
     * @param offset 跳过前几条：记录页一页页往回翻历史用。
     */
    @GetMapping
    public List<HealthRecordStore.RecordView> list(@PathVariable("userId") String userId,
                                                   @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit,
                                                   @RequestParam(name = "offset", defaultValue = "0") int offset) {
        return records.recent(userId, null, limit, offset);
    }

    /** 这人一共记了多少条：首页按钮上的“共N条”和“后面还有没有更早的”都靠它。 */
    @GetMapping("/count")
    public Map<String, Integer> count(@PathVariable("userId") String userId) {
        return Map.of("total", records.count(userId, null));
    }
}
