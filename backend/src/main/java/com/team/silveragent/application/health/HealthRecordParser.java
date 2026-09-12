package com.team.silveragent.application.health;

import com.team.silveragent.application.memo.MemoParser;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 老人上报实测数值的识别（规则版，与 {@link MemoParser} 同一思路）。
 *
 * 与备忘的分工：备忘是“要做的事”（明早八点吃药），这里是“已经量到的数”（我的血压是100）。
 * 所以命中要求「项目词 + 数值或说法」两件都齐 —— 只说“提醒我量血压”是备忘，不是记录。
 */
public final class HealthRecordParser {
    private HealthRecordParser() { }

    public enum Kind { RECORD, QUERY }

    /**
     * 数值本身有没有毛病。
     * IMPOSSIBLE=人不可能量出这个数（血压 800、体温 60），多半是听错或看错；
     * SWAPPED=两个数写反了（血压 60/120）；null=正常。
     * 有毛病不丢数：照常带出来，由助手反问老人是重测还是照记——静默丢弃会让老人
     * 报了个数却什么都没发生（原来还会顺着链路问“去哪家医院”）。
     */
    public enum Issue { IMPOSSIBLE, SWAPPED }

    /**
     * @param kind      RECORD=记一条实测值；QUERY=回查最近几条。
     * @param item      “血压/血糖/心率/体温/体重/血氧”；QUERY 且没点项目时为 null（查全部）。
     * @param valueNum  能取到数时的数（血压 100/60 取 100）；只有说法时为 null。
     * @param valueText 给人看的原值：“100/60”或“有点高”。
     * @param unit      单位：mmHg / mmol/L / 次每分 / °C / kg / %。
     * @param issue     数值有毛病时要先反问老人；正常为 null。
     */
    public record RecordIntent(Kind kind, String item, BigDecimal valueNum, String valueText, String unit, Issue issue) {
        /** 这条数值有毛病、得先问老人一句再决定记不记。 */
        public boolean needsConfirm() { return issue != null; }
    }

    /** 项目词 → 标准项目名 + 单位。长词在前（“收缩压”先于“压”）。 */
    private static final Map<String, String[]> ITEMS = new LinkedHashMap<>();
    static {
        ITEMS.put("收缩压", new String[]{"血压", "mmHg"});
        ITEMS.put("舒张压", new String[]{"血压", "mmHg"});
        ITEMS.put("血压", new String[]{"血压", "mmHg"});
        ITEMS.put("高压", new String[]{"血压", "mmHg"});
        ITEMS.put("低压", new String[]{"血压", "mmHg"});
        ITEMS.put("血糖", new String[]{"血糖", "mmol/L"});
        ITEMS.put("心率", new String[]{"心率", "次每分"});
        ITEMS.put("脉搏", new String[]{"心率", "次每分"});
        ITEMS.put("体温", new String[]{"体温", "°C"});
        ITEMS.put("体重", new String[]{"体重", "kg"});
        ITEMS.put("血氧", new String[]{"血氧", "%"});
    }

    /** 项目词表（“血压”“血糖”…）。给“把血压发给家属”那边认项目复用，免得两份词表各走各的。 */
    public static java.util.Set<String> itemWords() { return ITEMS.keySet(); }

    /** 项目词 → 标准项目名：“高压”“收缩压”都归到“血压”。认不出原样返回。 */
    public static String canonicalItem(String word) {
        String[] entry = ITEMS.get(word);
        return entry == null ? word : entry[0];
    }

    /** 没有数值时的说法（“我血压有点高”）：也算一条记录，只是没有数。 */
    private static final String[] VALUE_WORDS = {
            "有点偏高", "有点偏低", "有点高", "有点低", "偏高", "偏低", "高一些", "低一些", "正常"
    };

    /** 回查口气：“我最近血压多少”“我的血压记录”。 */
    private static final String[] QUERY_WORDS = {
            "多少", "好多", "记录", "查一下", "查查", "看看", "怎么样", "高不高", "低不低",
            "正常吗", "是不是正常"
    };

    /** 不点项目、只想看看全部记录的说法。 */
    private static final String[] QUERY_ALL_WORDS = {
            "健康记录", "我的记录", "量了什么", "测了什么", "最近都量了", "都量了什么"
    };

    /** 咨询口气：不是上报数值，交回“不能诊断”引导，别记成一条记录。 */
    private static final String[] ADVICE_WORDS = {
            "怎么办", "怎么", "要不要", "能不能", "该不该", "吃什么药", "是不是得了", "严重吗"
    };

    /** 血压常写成 “100/60”，两个数一起记。 */
    private static final Pattern PAIR = Pattern.compile(
            "([0-9]{1,3}(?:\\.[0-9]{1,2})?)\\s*[/、]\\s*([0-9]{1,3}(?:\\.[0-9]{1,2})?)");
    private static final Pattern NUMBER = Pattern.compile("([0-9]{1,3}(?:\\.[0-9]{1,2})?)");
    /** 数字后面跟这些字就是钟点/日期/次数，不是测出来的值（“8点量血压”不能记成血压 8）。 */
    private static final String NUMBER_UNITS = "点分号月日时岁周天次片粒杯袋盒颗支滴";
    /** 项目词前后各看多少字：够覆盖“我的血压是100”“血压，100/60”“量了血压结果95”。 */
    private static final int LOOK_BEFORE = 8;
    private static final int LOOK_AFTER = 14;

    /**
     * 每个项目“人不可能量出这个数”的下限/上限。注意这不是正常值范围——
     * “正常多少”是医学判断，助手不做；这里只挡血压 800、体温 60 这种：
     * 量不出来，一定是听错、看错或者单位说错了。
     */
    private static final Map<String, BigDecimal[]> IMPOSSIBLE = new LinkedHashMap<>();
    static {
        IMPOSSIBLE.put("血压", new BigDecimal[]{BigDecimal.valueOf(50), BigDecimal.valueOf(300)});
        IMPOSSIBLE.put("血糖", new BigDecimal[]{BigDecimal.valueOf(1), BigDecimal.valueOf(40)});
        IMPOSSIBLE.put("心率", new BigDecimal[]{BigDecimal.valueOf(20), BigDecimal.valueOf(250)});
        IMPOSSIBLE.put("体温", new BigDecimal[]{BigDecimal.valueOf(30), BigDecimal.valueOf(45)});
        IMPOSSIBLE.put("体重", new BigDecimal[]{BigDecimal.valueOf(2), BigDecimal.valueOf(400)});
        IMPOSSIBLE.put("血氧", new BigDecimal[]{BigDecimal.valueOf(50), BigDecimal.valueOf(100)});
    }

    /** 血压第二个数（舒张压）的边界：比第一个数宽松。 */
    private static final BigDecimal[] DIASTOLIC_RANGE =
            new BigDecimal[]{BigDecimal.valueOf(20), BigDecimal.valueOf(200)};

    /** 认不出项目时用的兜底边界，只为挡住年份、门牌号这类认错的数。 */
    private static final BigDecimal[] FALLBACK_RANGE =
            new BigDecimal[]{BigDecimal.ONE, BigDecimal.valueOf(500)};

    private static BigDecimal[] rangeOf(String item) {
        BigDecimal[] range = IMPOSSIBLE.get(item);
        return range == null ? FALLBACK_RANGE : range;
    }

    /** 血压配对的第二个数按舒张压算，别的项目两个数都按本项目算。 */
    private static BigDecimal[] pairRangeOf(String item) {
        return "血压".equals(item) ? DIASTOLIC_RANGE : rangeOf(item);
    }

    private static boolean withinRange(BigDecimal value, BigDecimal[] range) {
        return value.compareTo(range[0]) >= 0 && value.compareTo(range[1]) <= 0;
    }

    /** 识别不出返回 null，交回原链路。 */
    public static RecordIntent detect(String message) {
        String raw = message == null ? "" : message.trim();
        if (raw.isEmpty()) return null;

        String item = null;
        String unit = null;
        int itemStart = -1;
        int itemEnd = -1;
        for (Map.Entry<String, String[]> entry : ITEMS.entrySet()) {
            int at = raw.indexOf(entry.getKey());
            if (at < 0) continue;
            item = entry.getValue()[0];
            unit = entry.getValue()[1];
            itemStart = at;
            itemEnd = at + entry.getKey().length();
            break;
        }
        // 没点具体项目，但说了“健康记录/我最近都量了什么”：查全部
        if (item == null) {
            return containsAny(raw, QUERY_ALL_WORDS)
                    ? new RecordIntent(Kind.QUERY, null, null, null, null, null) : null;
        }
        // 回查先于咨询判定： “血压怎么样”是回查，“血压有点高怎么办”才是咨询（前者含“怎么”）
        if (containsAny(raw, QUERY_WORDS)) return new RecordIntent(Kind.QUERY, item, null, null, unit, null);
        if (containsAny(raw, ADVICE_WORDS)) return null;

        String window = window(raw, itemStart, itemEnd);
        int windowStart = Integer.max(0, itemStart - LOOK_BEFORE);
        Matcher pair = PAIR.matcher(window);
        if (pair.find()) {
            BigDecimal first = new BigDecimal(pair.group(1));
            BigDecimal second = new BigDecimal(pair.group(2));
            String text = pair.group(1) + "/" + pair.group(2);
            // 两个数都要查：原来只看前一个，“血压 120/800”照样入库
            if (!withinRange(first, rangeOf(item)) || !withinRange(second, pairRangeOf(item))) {
                return new RecordIntent(Kind.RECORD, item, first, text, unit, Issue.IMPOSSIBLE);
            }
            // “血压 60/120”：两个数都在范围内，但顺序反了，照记会得到一条不可能的血压
            if ("血压".equals(item) && first.compareTo(second) < 0) {
                return new RecordIntent(Kind.RECORD, item, first, text, unit, Issue.SWAPPED);
            }
            return new RecordIntent(Kind.RECORD, item, first, text, unit, null);
        }
        Matcher number = NUMBER.matcher(window);
        BigDecimal stray = null;
        String strayText = null;
        while (number.find()) {
            int after = windowStart + number.end();
            char next = after < raw.length() ? raw.charAt(after) : ' ';
            if (NUMBER_UNITS.indexOf(next) >= 0) continue;
            BigDecimal value = new BigDecimal(number.group(1));
            if (!withinRange(value, rangeOf(item))) {
                // 离谱值不再静默跳过（老人报了数却石沉大海），留着让助手反问一句
                if (stray == null) {
                    stray = value;
                    strayText = number.group(1);
                }
                continue;
            }
            return new RecordIntent(Kind.RECORD, item, value, number.group(1), unit, null);
        }
        if (stray != null) return new RecordIntent(Kind.RECORD, item, stray, strayText, unit, Issue.IMPOSSIBLE);
        for (String word : VALUE_WORDS) {
            if (window.contains(word)) return new RecordIntent(Kind.RECORD, item, null, word, unit, null);
        }
        return null;
    }

    /** 取项目词前后的一段作为“这一条说法”的范围，避免把句子别处的数字当成测出来的值。 */
    private static String window(String raw, int itemStart, int itemEnd) {
        int from = Integer.max(0, itemStart - LOOK_BEFORE);
        int to = Integer.min(raw.length(), itemEnd + LOOK_AFTER);
        return raw.substring(from, to);
    }

    private static boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }
}
