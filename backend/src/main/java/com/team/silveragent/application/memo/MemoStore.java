package com.team.silveragent.application.memo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
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

    /**
     * 处理掉一条备忘；仅本人进行中的备忘可处理。
     *
     * <p>“只提醒一次”的标记完成，整条就结束了。<b>重复提醒的不结束</b>——“每天八点吃药”
     * 那条上按「已完成」，老人说的是“这次吃完了”，不是“以后都别提醒我吃药了”。
     * 所以这里把 {@code remind_at} 顺延到下一次，条目继续留着提醒。
     *
     * @return 处理后的这条：调用方要拿新的 {@code remindAt} 告诉老人“下次什么时候提醒”
     *         （界面不变的话他会以为没点着，再点一次就把下一次也推掉了）；
     *         这条不存在、或已经处理过，返回 null。
     */
    public MemoView complete(String userId, String memoId) {
        List<MemoView> found = jdbc.query("""
                SELECT id,text,remind_at,repeat_rule,status,created_at FROM memos
                WHERE id=? AND user_id=? AND status='ACTIVE'
                """, (rs, row) -> new MemoView(
                rs.getString(1), rs.getString(2),
                rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toLocalDateTime(),
                rs.getString(4), rs.getString(5), rs.getTimestamp(6).toLocalDateTime()), memoId, userId);
        if (found.isEmpty()) return null;
        MemoView memo = found.get(0);
        String repeat = normalizeRepeat(memo.repeatRule());
        if (repeat == null || memo.remindAt() == null) {
            return jdbc.update("UPDATE memos SET status='DONE' WHERE id=? AND user_id=? AND status='ACTIVE'",
                    memoId, userId) > 0
                    ? new MemoView(memo.id(), memo.text(), null, null, "DONE", memo.createdAt())
                    : null;
        }
        LocalDateTime next = nextOccurrence(memo.remindAt(), repeat, MemoParser.nowInDemoZone());
        return jdbc.update("UPDATE memos SET remind_at=? WHERE id=? AND user_id=? AND status='ACTIVE'",
                Timestamp.valueOf(next), memoId, userId) > 0
                ? new MemoView(memo.id(), memo.text(), next, repeat, "ACTIVE", memo.createdAt())
                : null;
    }

    /**
     * 重复提醒的下一次到点：从锚点按周期往后找，直到<b>晚于</b> {@code now}。
     *
     * <p>不能只加一个周期就算完——一条“每天八点吃药”的锚点可能是上周设的，加一天还是在过去，
     * 老人按一下「已完成」界面上的时间没变（会以为没点着）。也不能把中间漏掉的那些天补出来，
     * 那会一次连弹好几条；漏了就漏了，下一次就是下一个还没到的点。
     *
     * <p>MONTHLY 必须按“几号”找，不能让日期落到别的号上：2 月没有 31 号就跳到 3 月。
     * {@code remind_at} 同时充当“每月几号”的锚点（见类注释），一旦被顺延成 28 号，
     * “每月31号”就被永久改写成“每月28号”了，再也回不去。
     *
     * @param anchor 锚点（存着的 {@code remind_at}）：既给出时刻，也给出星期几/几号
     * @param repeat 见 {@link #normalizeRepeat}；调用方保证非 null
     * @param now    业务时区的“现在”（{@link MemoParser#nowInDemoZone()}），不是 JVM 时钟——
     *               {@code remind_at} 是按业务时区存进去的
     */
    public static LocalDateTime nextOccurrence(LocalDateTime anchor, String repeat, LocalDateTime now) {
        LocalTime time = anchor.toLocalTime();
        if ("MONTHLY".equals(repeat)) {
            int dayOfMonth = anchor.getDayOfMonth();
            YearMonth month = YearMonth.from(anchor);
            // 上界只是防死循环（一百个月，够用了），正常一两次就找到
            for (int step = 0; step < 1200; step++) {
                if (month.lengthOfMonth() >= dayOfMonth && month.atDay(dayOfMonth).atTime(time).isAfter(now)) {
                    return month.atDay(dayOfMonth).atTime(time);
                }
                month = month.plusMonths(1);
            }
            return anchor;
        }
        int periodDays = "WEEKLY".equals(repeat) ? 7 : 1;
        LocalDate day = anchor.toLocalDate();
        for (int step = 0; step < 4000 && !day.atTime(time).isAfter(now); step++) {
            day = day.plusDays(periodDays);
        }
        return day.atTime(time);
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
