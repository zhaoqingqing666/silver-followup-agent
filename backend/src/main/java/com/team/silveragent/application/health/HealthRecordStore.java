package com.team.silveragent.application.health;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 老人上报的实测健康数值（血压/血糖/心率/体温/体重/血氧）。
 * 与 {@link com.team.silveragent.application.memo.MemoStore} 分家：备忘是“要做的事”（带提醒、可完成可删除），这里是“已经量到的数”（用来回查）。
 * 写入只来自助手对话（HealthRecordTool），首页只读。删只有一个口子（{@link #deleteLatest}）：
 * 老人说“记错了”，删掉他自己最近的那一条——除此之外没有改和删的路径，量过的数不会被人悄悄改掉。
 */
@Repository
public class HealthRecordStore {
    /** raw_text 列上限(VARCHAR 200)，超出截断以免插库报错。 */
    private static final int MAX_RAW = 200;

    /**
     * 一次最多取几条。原来卡在 50——那时只有“首页取最近几条”这一种用法，50 够用；
     * 记录页现在要一页页往回翻历史，50 就成了翻不过去的墙（第 50 条以前的永远看不到）。
     * 上限仍然留一个，防的是 limit 传个离谱的值把整表拉进内存。
     */
    private static final int MAX_PAGE = 200;

    /** 按时间窗汇总时最多看几条：正常一个月远远用不到，只是别让异常数据把整表拉进内存。 */
    private static final int MAX_WINDOW = 1000;

    private final JdbcTemplate jdbc;

    public HealthRecordStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * @param valueNum  能取到数值时的数（血压 100/60 取 100）；只有说法（“有点高”）时为 null。
     * @param valueText 给人看的原值：“100/60”或“有点高”。
     * @param unit      单位：mmHg / mmol/L / 次每分 / °C / kg / %。
     *                  <b>他没说单位、也没有数时传 null，落库成空串</b>——“血压 有点高”这条压根没量过，
     *                  替他填一个 mmHg 就等于说他量过了；unit 列是 NOT NULL，而 schema.sql 只在全新库上跑，
     *                  放宽列约束对已有的库不生效，所以这里收敛成空串（读的地方一律把空单位当作“不印”）。
     */
    public RecordView create(String userId, String item, BigDecimal valueNum, String valueText, String unit,
                             String rawText, String conversationId, LocalDateTime recordedAt) {
        String id = "hr-" + UUID.randomUUID();
        String safeRaw = rawText == null ? null : (rawText.length() > MAX_RAW ? rawText.substring(0, MAX_RAW) : rawText);
        String safeUnit = unit == null ? "" : unit;
        jdbc.update("INSERT INTO health_records(id,user_id,item,value_num,value_text,unit,raw_text,conversation_id,recorded_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                id, userId, item, valueNum, valueText, safeUnit, safeRaw, conversationId, Timestamp.valueOf(recordedAt));
        return new RecordView(id, item, valueText, valueNum, safeUnit, recordedAt);
    }

    /**
     * 老人说“记错了”：删掉他自己最近的那一条，返回删掉的那条；一条都没有返回 null。
     *
     * <p>为什么只给“最近一条”这一个口子，而不是让对话按 id 删：老人看不见 id，他说的“刚才那条”
     * 指的就是时间上最近的那一条。把面放窄，误删的可能就小。
     *
     * <p>为什么真的删而不是标记作废：这条是他自己拿给医生看的数据，留着一条他知道是错的数，
     * 汇总（“把这个月的血压发给女儿”）会把它算进平均里——那比少一条糟得多。
     *
     * @return 被删掉的那一条（回读给他看删对了没有）；没有可删的返回 null
     */
    public RecordView deleteLatest(String userId) {
        List<RecordView> latest = recent(userId, null, 1, 0);
        if (latest.isEmpty()) return null;
        RecordView row = latest.get(0);
        jdbc.update("DELETE FROM health_records WHERE user_id=? AND id=?", userId, row.id());
        return row;
    }

    /** 按人回查最近几条；item 为 null 表示不限项目（“我最近都量了什么”）。最新的在前。 */
    public List<RecordView> recent(String userId, String item, int limit) {
        return recent(userId, item, limit, 0);
    }

    /**
     * 同上，但可以跳过前若干条：记录页一页页往回翻历史用。
     *
     * @param offset 跳过前几条（从 0 开始），负数按 0 算。
     */
    public List<RecordView> recent(String userId, String item, int limit, int offset) {
        int size = Integer.max(1, Integer.min(MAX_PAGE, limit));
        int skip = Integer.max(0, offset);
        String sql = """
                SELECT id,item,value_text,value_num,unit,recorded_at FROM health_records
                WHERE user_id=?%s
                ORDER BY recorded_at DESC, id DESC
                LIMIT %d OFFSET %d
                """.formatted(item == null ? "" : " AND item=?", size, skip);
        Object[] args = item == null ? new Object[]{userId} : new Object[]{userId, item};
        return jdbc.query(sql, (rs, row) -> new RecordView(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getString(5),
                rs.getTimestamp(6).toLocalDateTime()), args);
    }

    /**
     * 取一段时间里的记录（含两端）：给“把最近一周的血压发给家属”那类汇总用。
     *
     * <p>和 {@link #recent} 的区别是这里**按时间切、不按条数切**——汇总要算平均和最高最低，
     * 少拿几条就把平均数算歪了。上限只是防止异常数据把整表拉进内存，正常一个月远远用不到。
     *
     * @param item 传 null 表示不限项目。
     */
    public List<RecordView> inWindow(String userId, String item, LocalDateTime from, LocalDateTime to, int limit) {
        int size = Integer.max(1, Integer.min(MAX_WINDOW, limit));
        String sql = """
                SELECT id,item,value_text,value_num,unit,recorded_at FROM health_records
                WHERE user_id=? AND recorded_at>=? AND recorded_at<=?%s
                ORDER BY recorded_at DESC, id DESC
                LIMIT %d
                """.formatted(item == null ? "" : " AND item=?", size);
        Object[] args = item == null
                ? new Object[]{userId, Timestamp.valueOf(from), Timestamp.valueOf(to)}
                : new Object[]{userId, Timestamp.valueOf(from), Timestamp.valueOf(to), item};
        return jdbc.query(sql, (rs, row) -> new RecordView(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getString(5),
                rs.getTimestamp(6).toLocalDateTime()), args);
    }

    /**
     * 这人一共记了多少条；item 为 null 表示不限项目。
     *
     * <p>翻页之后就不能再拿“这一页取回来几条”当总数了——首页按钮上的“共N条”、
     * 以及“后面还有没有更早的”，都得问这个数。
     */
    public int count(String userId, String item) {
        String sql = "SELECT COUNT(*) FROM health_records WHERE user_id=?" + (item == null ? "" : " AND item=?");
        Integer total = item == null
                ? jdbc.queryForObject(sql, Integer.class, userId)
                : jdbc.queryForObject(sql, Integer.class, userId, item);
        return total == null ? 0 : total;
    }

    public record RecordView(String id, String item, String valueText, BigDecimal valueNum, String unit,
                             LocalDateTime recordedAt) { }
}
