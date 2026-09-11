package com.team.silveragent.application;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 助手侧“管理已有备忘”的意图识别：查 / 改 / 删（{@link MemoParser} 管的是“新记一条”）。
 *
 * 一般要求句子里出现“备忘”或“提醒”，否则不动手 —— 老人说“改成下周三”很可能是在改复诊预约，
 * 不能当成改备忘。例外是“第2条”：这是助手摆出备忘清单时教给老人的说法，本身就只可能指清单里的备忘。
 * 识别不出返回 null，交回原链路。
 */
public final class MemoCommandParser {
    private MemoCommandParser() { }

    public enum Kind { LIST, UPDATE, DELETE }

    /**
     * @param kind  要做什么。
     * @param head  句子前半段（“改成/改到”之前），用来说清指的是哪一条；LIST 时为空串。
     * @param tail  “改成/改到”之后的新时间说法；没给新时间时为 null（要追问）。
     */
    public record MemoCommand(Kind kind, String head, String tail) { }

    /** 查备忘（“我都有哪些备忘”“看看我的提醒”）。 */
    private static final String[] LIST_WORDS = {
            "有哪些备忘", "有什么备忘", "我的备忘", "看看备忘", "查看备忘", "查一下备忘", "查查备忘",
            "备忘有哪些", "备忘都记了", "所有的备忘", "全部备忘", "备忘列表",
            "有哪些提醒", "有什么提醒", "我的提醒", "看看提醒", "查看提醒", "哪些提醒"
    };
    /** 删备忘的前缀词；后面还要出现“备忘/提醒”才算数。 */
    private static final String[] DELETE_WORDS = { "删掉", "删除", "删了", "删去", "不要这条", "去掉这条" };
    /** 改备忘的前缀词；后面还要出现“备忘/提醒”才算数。 */
    private static final String[] UPDATE_WORDS = { "改成", "改到", "改一下", "修改", "改改", "推迟", "提前", "换个时间", "换到" };
    /** 没有“备忘/提醒”这两个词时不敢动手：很可能是在说复诊预约。 */
    private static final String[] MEMO_WORDS = { "备忘", "提醒" };
    /** “改成/改到”把句子切成“指的是哪条”和“改成什么时候”。 */
    private static final Pattern CHANGE_SPLIT = Pattern.compile("改\\s*成|改\\s*到|换\\s*到");
    /** 清单里的序号（“改第2条”“删掉第二条”）；只有这种情况才允许句子里没有“备忘/提醒”。 */
    private static final Pattern ORDINAL = Pattern.compile("第\\s*(?:[0-9]{1,2}|[一二两三四五六七八九十]{1,2})\\s*条");
    /** 带序号但不带“改成”时的新时间分界：“改第2条到明天早上八点”。 */
    private static final Pattern ORDINAL_TO = Pattern.compile("[到成]");

    public static MemoCommand detect(String message) {
        String raw = message == null ? "" : message.trim();
        if (raw.isEmpty()) return null;
        for (String word : LIST_WORDS) {
            if (raw.contains(word)) return new MemoCommand(Kind.LIST, "", null);
        }
        Matcher ordinal = ORDINAL.matcher(raw);
        boolean byOrdinal = ordinal.find();
        if (!byOrdinal && !containsAny(raw, MEMO_WORDS)) return null;
        if (containsAny(raw, DELETE_WORDS) || (byOrdinal && containsAny(raw, "删", "去掉", "不要"))) {
            return new MemoCommand(Kind.DELETE, raw, null);
        }
        if (!byOrdinal && !containsAny(raw, UPDATE_WORDS)) return null;
        int from = byOrdinal ? ordinal.end() : 0;
        Matcher split = CHANGE_SPLIT.matcher(raw);
        if (split.find(from)) {
            return new MemoCommand(Kind.UPDATE, raw.substring(0, split.start()).trim(), raw.substring(split.end()).trim());
        }
        if (byOrdinal) {
            Matcher to = ORDINAL_TO.matcher(raw);
            if (to.find(from) && !raw.substring(to.end()).isBlank()) {
                return new MemoCommand(Kind.UPDATE, raw.substring(0, to.end()).trim(), raw.substring(to.end()).trim());
            }
        }
        return new MemoCommand(Kind.UPDATE, raw, null);
    }

    private static boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }
}
