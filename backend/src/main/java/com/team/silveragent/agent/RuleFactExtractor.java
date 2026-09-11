package com.team.silveragent.agent;

import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RuleFactExtractor implements FactExtractor {
    private static final List<String> HOSPITALS = List.of("市第一医院", "市人民医院", "中心医院");
    private static final List<String> DEPARTMENTS = List.of("心内科", "内分泌科", "神经内科", "骨科", "眼科");
    private static final Pattern CN_DATE = Pattern.compile("(\\d{1,2})月(\\d{1,2})[日号]?");
    private static final Pattern ISO_DATE = Pattern.compile("(20\\d{2})-(\\d{1,2})-(\\d{1,2})");
    private static final Pattern TIME = Pattern.compile("(\\d{1,2})[:：点时](\\d{1,2})?");
    /** 时间硬约束：「必须上午」这类不是偏好，是不许商量的限制。 */
    private static final Pattern TIME_LIMIT = Pattern.compile("(必须|只能|一定要|务必)(上午|下午|早上|晚上)");
    /** 日期硬约束：保留用户原话里的说法（「不能周三」「避开周末」）。 */
    private static final Pattern DATE_LIMIT = Pattern.compile("(不能|不要|别安排在?|避开)(周[一二三四五六日天]|星期[一二三四五六日天]|周末)");
    /** 截止期限：「国庆前」这类。 */
    private static final Pattern DEADLINE = Pattern.compile("(国庆|春节|月底|年底|这个月|本月|这周|下周)(之前|以前|前)");
    /** 软性偏好：不影响能不能办，只影响怎么办。 */
    private static final Pattern PREFERENCE = Pattern.compile("人少(?:一点|一些)?|安静(?:一点|一些)?|女医生|男医生|老医生|专家号?|离家近|近一点|便宜(?:一点|一些)?|快一点");
    /** 用户主动补充、但归类不到任何固定事项的诉求。 */
    private static final Pattern EXTRA_REQUEST = Pattern.compile("(?:另外|顺便|还有|对了|再帮我|帮我再)[，,]?([^，,。；;！!？?]+)");

    @Override
    public ExtractedFacts extract(String message, AgentContext context, boolean isVoice) {
        String hospital = HOSPITALS.stream().filter(message::contains).findFirst().orElse(null);
        String department = DEPARTMENTS.stream().filter(message::contains).findFirst().orElse(null);
        String intent = detectIntent(message);
        Boolean stageAnswer = genericYesNo(message);
        LocalDate date = parseDate(message, context.currentDate());
        return new ExtractedFacts(intent, hospital, department, date,
                "ASK_ALTERNATIVE".equals(context.stage()) ? first(stageAnswer, yesNo(message, "换日期", "其他日期", "附近日期")) : yesNo(message, "换日期", "其他日期", "附近日期"),
                "ASK_COMPANION".equals(context.stage()) ? first(stageAnswer, yesNo(message, "陪同", "陪我")) : yesNo(message, "陪同", "陪我"),
                "ASK_TRAVEL".equals(context.stage()) ? first(stageAnswer, yesNo(message, "出行提醒", "出发提醒", "提醒出发")) : yesNo(message, "出行提醒", "出发提醒", "提醒出发"),
                "ASK_NOTIFY".equals(context.stage()) ? first(stageAnswer, yesNo(message, "通知", "告诉女儿", "告诉儿子", "告诉家属")) : yesNo(message, "通知", "告诉女儿", "告诉儿子", "告诉家属"),
                transport(message), parseTime(message), timePreference(message),
                "CONFIRM_SLOT".equals(context.stage()) ? genericYesNo(message) : null, null,
                tasks(message, intent), constraints(message), preferences(message),
                extraRequests(message), missingInformation(intent, message, hospital, department, date));
    }

    /**
     * 规则兜底下的多任务拆解。命中哪些关键词就拆出哪些事项，顺序固定为
     * 预约查询 → 时间安排 → 材料准备 → 出行规划 → 家属通知 → 日程提醒，
     * 这样和模型通道的输出顺序一致。
     */
    private List<ExtractedFacts.TaskItem> tasks(String value, String intent) {
        List<ExtractedFacts.TaskItem> tasks = new ArrayList<>();
        boolean booking = "CREATE_FOLLOWUP".equals(intent)
                || containsAny(value, "复诊", "预约", "挂号", "安排", "约个", "约一下");
        if (booking) {
            tasks.add(new ExtractedFacts.TaskItem("预约查询", "查一下能约到的号源"));
            tasks.add(new ExtractedFacts.TaskItem("时间安排", "挑一个合适的复诊时间"));
            tasks.add(new ExtractedFacts.TaskItem("材料准备", "列出复诊要带的材料"));
        }
        if (containsAny(value, "出发", "出行", "怎么去", "路上", "接送")) {
            tasks.add(new ExtractedFacts.TaskItem("出行规划", "算好出发时间"));
        }
        if (containsAny(value, "女儿", "儿子", "家属", "家人", "老伴", "孩子", "闺女")) {
            tasks.add(new ExtractedFacts.TaskItem("家属通知", "把复诊安排告诉家属"));
        }
        if (containsAny(value, "提醒", "别忘", "记一下", "到时候叫我")) {
            tasks.add(new ExtractedFacts.TaskItem("日程提醒", "到点提醒"));
        }
        return tasks;
    }

    /** 硬性约束：保留用户原话的说法，不做归一化改写。 */
    private List<String> constraints(String value) {
        List<String> constraints = new ArrayList<>();
        addMatches(constraints, TIME_LIMIT, value, 0);
        addMatches(constraints, DATE_LIMIT, value, 0);
        addMatches(constraints, DEADLINE, value, 0);
        return constraints;
    }

    private List<String> preferences(String value) {
        List<String> preferences = new ArrayList<>();
        addMatches(preferences, PREFERENCE, value, 0);
        return preferences;
    }

    /**
     * 「另外…」「顺便…」这类补充诉求，整句原样留下，绝不因为不好归类就丢掉。
     * 只取引子后面的正文（捕获组 1），避免展示成「另外记下：另外帮我记一下…」这种重复。
     */
    private List<String> extraRequests(String value) {
        List<String> extra = new ArrayList<>();
        Matcher matcher = EXTRA_REQUEST.matcher(value);
        while (matcher.find()) {
            String item = matcher.group(1).trim();
            if (!item.isEmpty() && !extra.contains(item)) extra.add(item);
        }
        return extra;
    }

    /** 要把本轮这些事项办完还缺的信息；用户没提出要办的事时不必追问。 */
    private List<String> missingInformation(String intent, String message, String hospital, String department, LocalDate date) {
        if (!"CREATE_FOLLOWUP".equals(intent) && tasks(message, intent).isEmpty()) return List.of();
        List<String> missing = new ArrayList<>();
        if (hospital == null) missing.add("想去哪家医院");
        if (department == null) missing.add("看哪个科室");
        if (date == null) missing.add("具体想约哪一天");
        return missing;
    }

    private void addMatches(List<String> target, Pattern pattern, String value, int group) {
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            String item = matcher.group(group).trim();
            if (!item.isEmpty() && !target.contains(item)) target.add(item);
        }
    }

    @Override
    public String mode() { return "RULE_FALLBACK"; }

    private String detectIntent(String value) {
        if (containsAny(value, "胸痛", "呼吸困难", "昏迷", "大出血", "喘不上气")) return "EMERGENCY";
        if (containsAny(value, "怎么用药", "怎么吃药", "药怎么吃", "药量", "停药", "加药", "减药", "副作用", "剂量", "诊断", "检查结果", "是不是得了")) return "MEDICAL_ADVICE";
        // 健康科普/生活方式咨询：答复时必须附带"请咨询专业医生"提醒，避免误入预约流程
        if (containsAny(value, "科普", "平时要注意", "平时注意", "注意些什么", "注意啥", "日常注意", "日常要注意", "怎么注意", "怎么预防", "如何预防", "预防", "养生", "保健", "吃什么好", "吃点什么好", "饮食上", "饮食注意", "高血压", "血压高", "血压低", "低血压", "血糖高", "血糖低", "高血糖", "低血糖", "血脂高", "高血脂", "尿酸高", "糖尿病", "生活方式", "注意事项")) return "HEALTH_ADVICE";
        if (containsAny(value, "事项", "任务列表", "看任务", "查看任务")) return "VIEW_TASKS";
        if (containsAny(value, "可预约", "可约", "最近哪天", "哪天能", "有哪些日期", "可以约什么时候", "最近什么时候", "有空吗", "有号吗", "还有号")) return "QUERY_AVAILABLE_DATES";
        if (containsAny(value, "我的预约", "查预约", "查询预约", "有哪些预约", "看看预约", "预约记录", "复诊记录",
                "就诊医院", "约的医院", "预约的医院", "约在哪家", "在哪家医院", "预约信息")) return "QUERY_APPOINTMENTS";
        if (containsAny(value, "取消整个", "不办了", "停止办理")) return "CANCEL_TASK";
        if (containsAny(value, "取消预约", "取消这次")) return "CANCEL_APPOINTMENT";
        if (containsAny(value, "换医院", "修改医院")) return "CHANGE_HOSPITAL";
        if (containsAny(value, "换日期", "改日期", "改成", "不行了")) return "CHANGE_DATE";
        if (containsAny(value, "材料", "带什么", "准备什么")) return "ASK_MATERIALS";
        if (containsAny(value, "复诊", "预约")) return "CREATE_FOLLOWUP";
        return "PROVIDE_INFORMATION";
    }

    private LocalDate parseDate(String message, LocalDate today) {
        Matcher iso = ISO_DATE.matcher(message);
        if (iso.find()) return safeDate(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
        Matcher cn = CN_DATE.matcher(message);
        if (!cn.find()) return null;
        int month = Integer.parseInt(cn.group(1));
        int day = Integer.parseInt(cn.group(2));
        LocalDate candidate = safeDate(today.getYear(), month, day);
        if (candidate != null && candidate.isBefore(today.minusDays(1))) candidate = safeDate(today.getYear() + 1, month, day);
        return candidate;
    }

    private LocalTime parseTime(String message) {
        Matcher matcher = TIME.matcher(message);
        if (!matcher.find()) return null;
        try {
            int hour = Integer.parseInt(matcher.group(1));
            int minute = matcher.group(2) == null || matcher.group(2).isBlank() ? 0 : Integer.parseInt(matcher.group(2));
            if (message.contains("下午") && hour < 12) hour += 12;
            return LocalTime.of(hour, minute);
        } catch (RuntimeException ignored) { return null; }
    }

    private LocalDate safeDate(int year, int month, int day) {
        try { return LocalDate.of(year, month, day); }
        catch (DateTimeException ignored) { return null; }
    }

    private Boolean first(Boolean preferred, Boolean fallback) {
        return preferred != null ? preferred : fallback;
    }

    private Boolean genericYesNo(String value) {
        if (containsAny(value, "不需要", "不用", "不要", "不想", "不接受", "否")) return false;
        if (containsAny(value, "需要", "可以", "接受", "好的", "好", "是")) return true;
        return null;
    }

    private Boolean yesNo(String value, String... topics) {
        boolean related = false;
        for (String topic : topics) if (value.contains(topic)) related = true;
        if (!related) return null;
        if (containsAny(value, "不需要", "不用", "不要", "不想", "不接受", "只要这一天")) return false;
        return true;
    }

    private String transport(String value) {
        for (String item : List.of("家属开车", "打车", "公交", "步行")) if (value.contains(item)) return item;
        return null;
    }

    private String timePreference(String value) {
        if (containsAny(value, "上午", "早上", "早一点", "最早")) return "MORNING";
        if (containsAny(value, "下午", "午后", "晚一点")) return "AFTERNOON";
        return null;
    }

    private boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }
}
