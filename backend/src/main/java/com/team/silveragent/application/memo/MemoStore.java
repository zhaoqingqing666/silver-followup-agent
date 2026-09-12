package com.team.silveragent.application.memo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 老人端“健康备忘”：复诊助手中老人托付的健康/复诊相关小记。
 * 写入只来自助手对话（MemoTool），首页只读/标记完成/删除。
 */
@Repository
public class MemoStore {
    /** memos.text 列上限(VARCHAR 300)，与 MemoParser.MAX_TEXT 一致。 */
    private static final int MAX_TEXT = 300;

    /** 分页一次最多取几条：防的是 limit 传个离谱的值把整表拉进内存。 */
    private static final int MAX_PAGE = 100;

    private final JdbcTemplate jdbc;

    public MemoStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 重复规则合法值：DAILY 每天 / WEEKLY 每周 / MONTHLY 每月；其它一律当“只提醒一次”(null)。 */
    public static String normalizeRepeat(String repeatRule) {
        if (repeatRule == null) return null;
        String value = repeatRule.trim().toUpperCase();
        return switch (value) {
            case "DAILY", "WEEKLY", "MONTHLY" -> value;
            default -> null;
        };
    }

    /** 只提醒一次的备忘。 */
    public MemoView create(String userId, String text, LocalDateTime remindAt) {
        return create(userId, text, remindAt, null);
    }

    /** @param repeatRule 见 {@link #normalizeRepeat}；remindAt 存下一次到点，重复提醒时兼作锚点。 */
    public MemoView create(String userId, String text, LocalDateTime remindAt, String repeatRule) {
        String repeat = normalizeRepeat(repeatRule);
        String id = "memo-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO memos(id,user_id,text,remind_at,repeat_rule,status,created_at) VALUES (?,?,?,?,?,?,?)",
                id, userId, text, remindAt == null ? null : Timestamp.valueOf(remindAt), repeat, "ACTIVE",
                Timestamp.valueOf(now));
        return new MemoView(id, text, remindAt, repeat, "ACTIVE", now);
    }

    /** 进行中的备忘：到点的在前、无提醒时间（长期备忘）排在最后。 */
    public List<MemoView> activeFor(String userId) {
        return activeFor(userId, null, 0, 0);
    }

    /**
     * 按类分页取进行中的备忘。长期备忘是只增不减的（记下了就一直留着），攒多了页面得
     * 一页页往回翻，不能再一次性全拉下来。
     *
     * @param kind   "standing" 只要长期备忘（没提醒时间的）；"timed" 只要到点提醒的；
     *               其它或 null 表示全都要（助手列清单、提醒页用）。
     * @param limit  最多几条；&lt;= 0 表示不限（首页要把有提醒的全拿走算“最近到点”）。
     * @param offset 跳过前几条（从 0 开始），负数按 0 算。
     */
    public List<MemoView> activeFor(String userId, String kind, int limit, int offset) {
        boolean standing = "standing".equals(kind);
        String where = standing ? " AND remind_at IS NULL"
                : "timed".equals(kind) ? " AND remind_at IS NOT NULL" : "";
        // 长期备忘没有“到点”可言，能排的只有“什么时候记下的”，所以按记录时间倒序；
        // 全都要时保持老样子：到点的按到点时间从早到晚在前，长期备忘垫底。
        String order = standing
                ? " ORDER BY created_at DESC, id DESC"
                : " ORDER BY (remind_at IS NULL), remind_at ASC, created_at DESC";
        String tail = limit <= 0 ? ""
                : " LIMIT %d OFFSET %d".formatted(Integer.min(MAX_PAGE, limit), Integer.max(0, offset));
        return jdbc.query("""
                SELECT id,text,remind_at,repeat_rule,status,created_at FROM memos
                WHERE user_id=? AND status='ACTIVE'%s%s%s
                """.formatted(where, order, tail), (rs, row) -> new MemoView(
                rs.getString(1), rs.getString(2),
                rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toLocalDateTime(),
                rs.getString(4), rs.getString(5), rs.getTimestamp(6).toLocalDateTime()), userId);
    }

    /** 两类各有几条进行中的备忘：首页两个按钮上的条数用（不必为了算条数把长期备忘全拉下来）。 */
    public MemoCounts count(String userId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN remind_at IS NULL THEN 1 ELSE 0 END), 0) AS standing,
                       COALESCE(SUM(CASE WHEN remind_at IS NULL THEN 0 ELSE 1 END), 0) AS timed
                FROM memos WHERE user_id=? AND status='ACTIVE'
                """, (rs, row) -> new MemoCounts(rs.getInt("timed"), rs.getInt("standing")), userId);
    }

    /** @param timed 到点提醒的条数；@param standing 长期备忘的条数。 */
    public record MemoCounts(int timed, int standing) { }

    /** 标记完成；仅本人进行中的备忘可完成。返回是否更新到。 */
    public boolean complete(String userId, String memoId) {
        return jdbc.update("UPDATE memos SET status='DONE' WHERE id=? AND user_id=? AND status='ACTIVE'",
                memoId, userId) > 0;
    }

    /** 删除备忘；仅本人可删。返回是否更新到。 */
    public boolean remove(String userId, String memoId) {
        return jdbc.update("UPDATE memos SET status='DELETED' WHERE id=? AND user_id=? AND status<>'DELETED'",
                memoId, userId) > 0;
    }

    /**
     * 修改进行中的备忘：改要记的内容和/或提醒时间。remindAt 传 null 表示转成长期备忘（不再提醒）。
     * 仅本人进行中的备忘可改；内容去空白校验、超长截断。返回是否更新到。
     */
    public boolean update(String userId, String memoId, String text, LocalDateTime remindAt) {
        return update(userId, memoId, text, remindAt, null);
    }

    /** @param repeatRule 见 {@link #normalizeRepeat}；传 null 即改成“只提醒一次”，remindAt 传 null 即转成长期备忘。 */
    public boolean update(String userId, String memoId, String text, LocalDateTime remindAt, String repeatRule) {
        if (text == null || text.isBlank()) return false;
        String safe = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
        return jdbc.update(
                "UPDATE memos SET text=?, remind_at=?, repeat_rule=? WHERE id=? AND user_id=? AND status='ACTIVE'",
                safe, remindAt == null ? null : Timestamp.valueOf(remindAt),
                normalizeRepeat(repeatRule), memoId, userId) > 0;
    }

    public record MemoView(String id, String text, LocalDateTime remindAt, String repeatRule,
                           String status, LocalDateTime createdAt) { }
}
