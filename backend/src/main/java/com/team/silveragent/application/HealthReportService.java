package com.team.silveragent.application;

import com.team.silveragent.domain.model.ToolModels.Contact;
import com.team.silveragent.domain.tool.FamilyNotificationTool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把老人的健康记录汇总成一段话，发给家属。
 *
 * <p>三条路都走这里，免得三处各写一份汇总、各说各话：
 * 健康记录页的「发给家属」按钮、助手里一句“把这个月的血压发给女儿”、每周一早上的自动小结。
 *
 * <p>汇总只做算术——几次、平均多少、最高最低。**不判断数值好不好**：
 * “这个数偏高”是医学判断，助手不做，和 {@link HealthRecordParser} 那条线保持一致。
 */
@Service
public class HealthReportService {
    /** 周报标题。同时也是“本周发过没有”的标记，改字要连带想清楚去重还认不认得出。 */
    public static final String WEEKLY_TITLE = "本周健康小结";

    /** family_notifications.content 是 VARCHAR(500)。超了要截断，不然插库直接报错。 */
    private static final int MAX_CONTENT = 500;

    /** 一次汇总最多看多少条：够覆盖一个月；再多说明数据异常，别把整表拉进内存。 */
    private static final int MAX_SAMPLES = 1000;

    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("M月d日");

    /** 汇总里项目的先后：先血压再血糖，跟老人平时报的顺序一致；表里没有的项目不出现。 */
    private static final List<String> ITEM_ORDER = List.of("血压", "血糖", "心率", "体温", "体重", "血氧");

    /** 血压不说小数（“138/86”），别的项目留一位（“6.4”“36.6”）。 */
    private static final String BLOOD_PRESSURE = "血压";

    private final HealthRecordStore records;
    private final FamilyNotificationTool familyTool;
    private final JdbcTemplate jdbc;

    public HealthReportService(HealthRecordStore records, FamilyNotificationTool familyTool, JdbcTemplate jdbc) {
        this.records = records;
        this.familyTool = familyTool;
        this.jdbc = jdbc;
    }

    /**
     * 一段汇总：给老人回了什么、发给家属的就是什么。
     *
     * @param rangeLabel 覆盖的日期范围，如“9月3日至9月10日”。
     * @param text       完整的一条消息；这一段没有记录时为 null。
     */
    public record Report(String rangeLabel, int recordCount, String text) { }

    /**
     * 发送结果。发不出去时 {@code sent=false}，{@code reason} 是能直接念给老人听的一句话
     * （没配家属联系人、这段时间没有记录），调用方不用自己去分辨失败原因。
     *
     * @param message 真发出去的那段话；没发出去时为 null。
     */
    public record SendResult(boolean sent, String reason, String contactLabel, String rangeLabel,
                             int recordCount, String message) { }

    /**
     * 汇总并发送。
     *
     * @param item  只发某一个项目（“这个月的血压”）；传 null 表示全部项目。
     * @param title 开头那句的写法：手动发是“健康记录”，周报是“本周健康小结”。
     */
    public SendResult send(String conversationId, String userId, HealthReportParser.Window window,
                           String item, String title) {
        Contact contact = primaryContact(conversationId, userId);
        if (contact == null) {
            return new SendResult(false, "还没有配置家属联系人，这条暂时发不出去。", null, null, 0, null);
        }
        String contactLabel = contact.relationship() + " " + contact.name();
        Report report = summarize(userId, window, item, title);
        if (report.text() == null) {
            String what = item == null ? "健康记录" : item + "记录";
            return new SendResult(false, window.label() + "没有" + what + "，没有可以发送的内容。",
                    contactLabel, report.rangeLabel(), 0, null);
        }
        familyTool.notify(conversationId, contact.id(), report.text());
        return new SendResult(true, null, contactLabel, report.rangeLabel(), report.recordCount(), report.text());
    }

    /** 每周一早上那份小结：时间跨度固定为最近一周、全部项目。 */
    public SendResult sendWeekly(String userId) {
        return send("health-report-weekly", userId, HealthReportParser.Window.WEEK, null, WEEKLY_TITLE);
    }

    /**
     * 本周的周报是不是已经发过了。
     *
     * <p>认的是消息开头的固定标题 + 本周一零点之后。后端每次启动都会补发一次，
     * 靠这个判断“补过了没”，所以同一周里重启多少次都只发一条。
     */
    public boolean weeklySentThisWeek(String userId) {
        LocalDate today = MemoParser.nowInDemoZone().toLocalDate();
        LocalDateTime monday = today.minusDays(today.getDayOfWeek().getValue() - 1L).atStartOfDay();
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM family_notifications n
                JOIN family_contacts fc ON fc.id = n.contact_id
                WHERE fc.user_id=? AND n.content LIKE ? AND n.created_at>=?
                """, Integer.class, userId, WEEKLY_TITLE + "%", Timestamp.valueOf(monday));
        return count != null && count > 0;
    }

    /** 配了家属联系人的老人。周报只给这些人发，没家属可发的不用空转。 */
    public List<String> elderUserIdsWithFamily() {
        return jdbc.queryForList("SELECT DISTINCT user_id FROM family_contacts", String.class);
    }

    /** 主联系人；没有配就是没有（不是错误）。 */
    public Contact primaryContact(String conversationId, String userId) {
        try {
            return familyTool.findPrimaryContact(conversationId, userId);
        } catch (RuntimeException error) {
            return null;
        }
    }

    /** 把一段时间内的记录汇总成一段话；这段时间一条都没有时 {@code text} 为 null。 */
    public Report summarize(String userId, HealthReportParser.Window window, String item, String title) {
        LocalDateTime to = MemoParser.nowInDemoZone();
        LocalDateTime from = to.minusDays(window.days());
        String rangeLabel = from.format(DAY_LABEL) + "至" + to.format(DAY_LABEL);
        List<HealthRecordStore.RecordView> rows = records.inWindow(userId, item, from, to, MAX_SAMPLES);
        if (rows.isEmpty()) return new Report(rangeLabel, 0, null);
        List<String> segments = new ArrayList<>();
        for (Map.Entry<String, List<HealthRecordStore.RecordView>> group : groupByItem(rows).entrySet()) {
            segments.add(segment(group.getKey(), group.getValue()));
        }
        String header = title + "（" + rangeLabel + "）：";
        return new Report(rangeLabel, rows.size(), header + join(segments, MAX_CONTENT - header.length() - 1));
    }

    /**
     * 一条汇总里说一个项目：“血压 3 次，平均 138/86 mmHg（最高 152/94，最低 126/78）”。
     *
     * <p>最高/最低取的是**真实量到的那一对**（“152/94”是某一次的实际读数），
     * 不是把收缩压的最大值和舒张压的最大值拼起来——拼出来的那对血压没人量到过。
     */
    private String segment(String item, List<HealthRecordStore.RecordView> rows) {
        List<Sample> samples = new ArrayList<>();
        int spokenOnly = 0;   // 只说了“有点高”这种、没写数值的
        for (HealthRecordStore.RecordView row : rows) {
            Sample sample = sampleOf(item, row);
            if (sample == null) spokenOnly++;
            else samples.add(sample);
        }
        StringBuilder text = new StringBuilder(item).append(" ").append(rows.size()).append(" 次");
        if (samples.isEmpty()) return text.append("（未写具体数值）").toString();
        if (spokenOnly > 0) text.append("（另有 ").append(spokenOnly).append(" 次未写数值）");

        int scale = BLOOD_PRESSURE.equals(item) ? 0 : 1;
        // 血压两半各按“量到了那一半的次数”平均：老人只说“血压 100”时没有舒张压，
        // 硬按同一个次数去除会把平均值算歪。所以“血压 3 次，平均 125/89”里两个数是
        // 各自真实读数的平均，只是分母可能不同（3 次收缩压、2 次舒张压）。
        List<Sample> paired = samples.stream().filter(sample -> sample.second() != null).toList();
        List<Sample> extremes = paired.isEmpty() ? samples : paired;
        String average = number(average(samples, true), scale);
        if (!paired.isEmpty()) average += "/" + number(average(paired, false), scale);
        text.append(samples.size() == 1 ? "，" : "，平均 ").append(average).append(" ").append(rows.get(0).unit());
        if (samples.size() > 1) {
            Sample high = extreme(extremes, true);
            Sample low = extreme(extremes, false);
            if (high.first().compareTo(low.first()) != 0) {
                text.append("（最高 ").append(render(high, scale))
                        .append("，最低 ").append(render(low, scale)).append("）");
            }
        }
        return text.toString();
    }

    /** 拼一条消息，超过预算就少列几项——宁可少说两项，也不能整条插不进库。 */
    private static String join(List<String> segments, int budget) {
        // 余量留给结尾“等 N 项未列出”那句，不然它自己能把内容顶过上限
        int reserve = 20;
        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (String segment : segments) {
            String next = text.length() == 0 ? segment : text + "；" + segment;
            if (next.length() + reserve > budget) break;
            text.setLength(0);
            text.append(next);
            shown++;
        }
        if (shown < segments.size()) text.append("；等 ").append(segments.size() - shown).append(" 项未列出");
        return text.append("。").toString();
    }

    /** 一次测量。舒张压只有血压有；别的项目第二个数为 null。 */
    private record Sample(BigDecimal first, BigDecimal second) { }

    /**
     * 从一条记录里取出可参与算术的数；取不到（只写了“有点高”）返回 null。
     *
     * <p>血压要从 value_text 的“100/60”里拆：value_num 只存了收缩压那一个数，
     * 舒张压只在原样文本里，不拆就永远算不出“平均 138/86”这种说法。
     */
    private static Sample sampleOf(String item, HealthRecordStore.RecordView row) {
        if (BLOOD_PRESSURE.equals(item)) {
            String text = row.valueText();
            int slash = text == null ? -1 : text.indexOf('/');
            if (slash > 0) {
                BigDecimal systolic = parse(text.substring(0, slash));
                BigDecimal diastolic = parse(text.substring(slash + 1));
                if (systolic != null && diastolic != null) return new Sample(systolic, diastolic);
            }
        }
        return row.valueNum() == null ? null : new Sample(row.valueNum(), null);
    }

    private static BigDecimal parse(String value) {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static BigDecimal average(List<Sample> samples, boolean first) {
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (Sample sample : samples) {
            BigDecimal value = first ? sample.first() : sample.second();
            if (value == null) continue;
            sum = sum.add(value);
            count++;
        }
        return count == 0 ? BigDecimal.ZERO : sum.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
    }

    /** 最高/最低按收缩压比；打平再看舒张压，取到的是某一整对。 */
    private static Sample extreme(List<Sample> samples, boolean max) {
        Sample best = samples.get(0);
        for (Sample sample : samples) {
            int order = compare(sample, best);
            if (max ? order > 0 : order < 0) best = sample;
        }
        return best;
    }

    private static int compare(Sample left, Sample right) {
        int byFirst = left.first().compareTo(right.first());
        if (byFirst != 0 || left.second() == null || right.second() == null) return byFirst;
        return left.second().compareTo(right.second());
    }

    private static String render(Sample sample, int scale) {
        String text = number(sample.first(), scale);
        return sample.second() == null ? text : text + "/" + number(sample.second(), scale);
    }

    /** 按位四舍五入再去掉没用的零：138.0 说成“138”，6.4 还是“6.4”。 */
    private static String number(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static Map<String, List<HealthRecordStore.RecordView>> groupByItem(
            List<HealthRecordStore.RecordView> rows) {
        Map<String, List<HealthRecordStore.RecordView>> groups = new LinkedHashMap<>();
        for (HealthRecordStore.RecordView row : rows) {
            groups.computeIfAbsent(row.item(), key -> new ArrayList<>()).add(row);
        }
        List<String> keys = new ArrayList<>(groups.keySet());
        keys.sort(Comparator.comparingInt(key -> {
            int index = ITEM_ORDER.indexOf(key);
            return index < 0 ? ITEM_ORDER.size() : index;
        }));
        Map<String, List<HealthRecordStore.RecordView>> ordered = new LinkedHashMap<>();
        for (String key : keys) ordered.put(key, groups.get(key));
        return ordered;
    }
}
