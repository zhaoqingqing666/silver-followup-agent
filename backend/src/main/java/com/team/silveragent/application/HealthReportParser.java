package com.team.silveragent.application;

/**
 * “把健康记录发给家属”的识别（规则版，和 {@link MemoParser}/{@link HealthRecordParser} 同一思路）。
 *
 * <p>三种说法都要命中才认，缺一不认，交回原链路：
 * <ol>
 *   <li><b>发送动作</b>——“发给/发送/传给”；</li>
 *   <li><b>收件人</b>——“家属/女儿/儿子/老伴”这类称呼；</li>
 *   <li><b>发的东西</b>——“健康记录/数值/数据”，或者直接点名项目（“把血压发给女儿”）。</li>
 * </ol>
 *
 * <p>第三条是防误伤的关键：“我把复诊安排发给女儿”里有“发给”也有“女儿”，但说的不是健康记录，
 * 不该被当成要发一份体检汇总。所以宁可漏认（漏了就走回普通对话，老人再说一遍就是），
 * 不可错认——发出去的消息撤不回来。
 */
public final class HealthReportParser {
    private HealthReportParser() { }

    /**
     * 汇总的时间跨度。
     *
     * @param days  往回数几天（含今天）。
     * @param label 说给老人听的叫法。
     */
    public enum Window {
        WEEK(7, "最近一周"),
        MONTH(30, "最近一个月");

        private final int days;
        private final String label;

        Window(int days, String label) {
            this.days = days;
            this.label = label;
        }

        public int days() { return days; }
        public String label() { return label; }
    }

    /**
     * @param window   汇总多长时间；老人没说时给 {@link Window#WEEK}（助手会把这个默认念出来，老人不满意当场能改）。
     * @param item     只发某一个项目（“这个月的血压”）；没点名项目时为 null，表示全部。
     * @param explicit 老人是不是自己说了时间跨度；没说时助手要在回复里讲明“按最近一周算的”。
     */
    public record ReportIntent(Window window, String item, boolean explicit) { }

    /** 发送动作。 */
    private static final String[] SEND_WORDS = {"发给", "发送", "传给", "转发给", "发到"};

    /** 收件人称呼。演示数据里的联系人叫“小丽”，但这儿只认称呼，不把某个人名写进规则。 */
    private static final String[] RECIPIENT_WORDS = {
            "家属", "家人", "家里人", "亲属", "女儿", "闺女", "儿子", "孩子", "子女",
            "老伴", "爱人", "老公", "老婆", "媳妇", "女婿", "照护", "志愿者", "护工", "护理员"
    };

    /** “发的东西是健康记录”的兜底说法；点名项目（“血压”）时不用它。 */
    private static final String[] CONTENT_WORDS = {"健康记录", "健康数据", "健康数值", "记录", "数据", "数值"};

    private static final String[] MONTH_WORDS = {"这个月", "本月", "这一个月", "最近一个月", "一个月", "上个月", "近一个月"};
    private static final String[] WEEK_WORDS = {"这周", "本周", "这个星期", "这星期", "最近一周", "一周", "近一周"};

    /** 识别不出返回 null，交回原链路。 */
    public static ReportIntent detect(String message) {
        String raw = message == null ? "" : message.trim();
        if (raw.isEmpty()) return null;
        if (!containsAny(raw, SEND_WORDS)) return null;
        if (!containsAny(raw, RECIPIENT_WORDS)) return null;

        String item = null;
        for (String word : HealthRecordParser.itemWords()) {
            if (raw.contains(word)) {
                item = HealthRecordParser.canonicalItem(word);
                break;
            }
        }
        if (item == null && !containsAny(raw, CONTENT_WORDS)) return null;

        // 先认“一个月”再认“一周”：两句都带“一X”，顺序反了“最近一个月”会被读成一周
        if (containsAny(raw, MONTH_WORDS)) return new ReportIntent(Window.MONTH, item, true);
        if (containsAny(raw, WEEK_WORDS)) return new ReportIntent(Window.WEEK, item, true);
        return new ReportIntent(Window.WEEK, item, false);
    }

    private static boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }
}
