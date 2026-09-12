package com.team.silveragent.api;

import com.team.silveragent.application.MemoStore;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/** 老人端“健康备忘”：仅当前就诊人本人（userId）可读自己的备忘并标记完成/删除。 */
@RestController
@RequestMapping("/api/users/{userId}/memos")
public class MemoController {
    private final MemoStore memos;

    public MemoController(MemoStore memos) { this.memos = memos; }

    /**
     * 进行中的备忘列表。
     *
     * @param kind   "standing" 只要长期备忘；"timed" 只要到点提醒的；缺省/其它表示全都要。
     * @param limit  最多几条；缺省 0 表示不限（提醒页要一次拿全）。
     * @param offset 跳过前几条：长期备忘页一页页往回翻用。
     */
    @GetMapping
    public List<MemoStore.MemoView> list(@PathVariable("userId") String userId,
                                         @RequestParam(name = "kind", defaultValue = "") String kind,
                                         @RequestParam(name = "limit", defaultValue = "0") int limit,
                                         @RequestParam(name = "offset", defaultValue = "0") int offset) {
        return memos.activeFor(userId, kind, limit, offset);
    }

    /** 两类各有几条进行中的备忘：首页两个按钮上的条数用。 */
    @GetMapping("/count")
    public MemoStore.MemoCounts count(@PathVariable("userId") String userId) {
        return memos.count(userId);
    }

    @PutMapping("/{memoId}")
    public void update(@PathVariable("userId") String userId, @PathVariable("memoId") String memoId,
                       @RequestBody MemoUpdateRequest body) {
        if (body == null || body.text() == null || body.text().isBlank()) {
            throw new IllegalArgumentException("要记的内容不能为空");
        }
        if (!memos.update(userId, memoId, body.text(), body.remindAt(), body.repeatRule())) {
            throw new IllegalArgumentException("这条备忘不存在或已经处理过了");
        }
    }

    @PostMapping("/{memoId}/done")
    public void complete(@PathVariable("userId") String userId, @PathVariable("memoId") String memoId) {
        if (!memos.complete(userId, memoId)) {
            throw new IllegalArgumentException("这条备忘不存在或已经处理过了");
        }
    }

    /**
     * 修改请求：text 为要记的新内容；remindAt 传 null 表示转成长期备忘（不再提醒）；
     * repeatRule 为 DAILY/WEEKLY/MONTHLY，传 null 表示只提醒一次。
     */
    public record MemoUpdateRequest(String text, LocalDateTime remindAt, String repeatRule) { }

    @DeleteMapping("/{memoId}")
    public void delete(@PathVariable("userId") String userId, @PathVariable("memoId") String memoId) {
        if (!memos.remove(userId, memoId)) {
            throw new IllegalArgumentException("这条备忘不存在或已经处理过了");
        }
    }
}
