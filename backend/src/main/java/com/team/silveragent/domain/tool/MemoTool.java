package com.team.silveragent.domain.tool;

import com.team.silveragent.application.memo.MemoStore;
import java.time.LocalDateTime;
import java.util.List;

public interface MemoTool {
    /** @param repeatRule DAILY 每天 / WEEKLY 每周 / MONTHLY 每月；null = 只提醒一次。 */
    MemoStore.MemoView create(String conversationId, String userId, String text, LocalDateTime remindAt, String repeatRule);

    /** 进行中的备忘（到点的在前、长期备忘在后）：助手侧“我有哪些备忘”用。 */
    List<MemoStore.MemoView> active(String conversationId, String userId);

    /**
     * 改一条进行中的备忘的时间。remindAt 传 null 表示改成长期备忘（不再提醒），
     * repeatRule 传 null 表示只提醒一次。返回是否改到。
     */
    boolean update(String conversationId, String userId, String memoId, String text,
                   LocalDateTime remindAt, String repeatRule);

    /** 删掉一条进行中的备忘。返回是否删到。 */
    boolean remove(String conversationId, String userId, String memoId);
}
