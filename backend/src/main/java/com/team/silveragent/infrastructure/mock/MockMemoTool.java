package com.team.silveragent.infrastructure.mock;

import com.team.silveragent.application.memo.MemoStore;
import com.team.silveragent.domain.tool.MemoTool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Component
public class MockMemoTool implements MemoTool {
    private final MemoStore memos;
    private final ToolTraceStore traces;

    public MockMemoTool(MemoStore memos, ToolTraceStore traces) {
        this.memos = memos;
        this.traces = traces;
    }

    @Override
    public MemoStore.MemoView create(String conversationId, String userId, String text,
                                     LocalDateTime remindAt, String repeatRule) {
        MemoStore.MemoView view = memos.create(userId, text, remindAt, repeatRule);
        traces.record(conversationId, "memo.create",
                Map.of("userId", userId, "text", text,
                        "remindAt", remindAt == null ? "长期" : remindAt,
                        "repeat", view.repeatRule() == null ? "仅一次" : view.repeatRule()),
                Map.of("memoId", view.id(), "status", view.status(),
                        "remindAt", view.remindAt() == null ? "长期" : view.remindAt()), true);
        return view;
    }

    @Override
    public List<MemoStore.MemoView> active(String conversationId, String userId) {
        List<MemoStore.MemoView> rows = memos.activeFor(userId);
        traces.record(conversationId, "memo.list", Map.of("userId", userId),
                Map.of("count", rows.size()), true);
        return rows;
    }

    @Override
    public boolean update(String conversationId, String userId, String memoId, String text,
                          LocalDateTime remindAt, String repeatRule) {
        boolean changed = memos.update(userId, memoId, text, remindAt, repeatRule);
        traces.record(conversationId, "memo.update",
                Map.of("memoId", memoId, "text", text,
                        "remindAt", remindAt == null ? "长期" : remindAt,
                        "repeat", repeatRule == null ? "仅一次" : repeatRule),
                Map.of("updated", changed), changed);
        return changed;
    }

    @Override
    public boolean remove(String conversationId, String userId, String memoId) {
        boolean removed = memos.remove(userId, memoId);
        traces.record(conversationId, "memo.delete", Map.of("memoId", memoId),
                Map.of("deleted", removed), removed);
        return removed;
    }
}
