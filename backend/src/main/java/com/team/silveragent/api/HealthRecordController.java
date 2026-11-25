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
     * @param item   只取某一个项目（血压/血糖…）；不传或传空表示不限项目（“全部”）。
     * @param limit  最多几条，缺省 3 条（首页只露最近几条）。
     * @param offset 跳过前几条：记录页一页页往回翻历史用。
     */
    @GetMapping
    public List<HealthRecordStore.RecordView> list(@PathVariable("userId") String userId,
                                                   @RequestParam(name = "item", required = false) String item,
                                                   @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit,
                                                   @RequestParam(name = "offset", defaultValue = "0") int offset) {
        return records.recent(userId, blankToNull(item), limit, offset);
    }

    /** 这人一共记了多少条：首页按钮上的“共N条”和“后面还有没有更早的”都靠它。按项目筛选时也要跟着变。 */
    @GetMapping("/count")
    public Map<String, Integer> count(@PathVariable("userId") String userId,
                                      @RequestParam(name = "item", required = false) String item) {
        return Map.of("total", records.count(userId, blankToNull(item)));
    }

    /** 空串和只有空白的参数都当“不限项目”看：前端清掉筛选时传的是空串，不是 null。 */
    private static String blankToNull(String item) {
        return item == null || item.isBlank() ? null : item.trim();
    }
}
