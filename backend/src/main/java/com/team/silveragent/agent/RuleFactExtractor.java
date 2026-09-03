package com.team.silveragent.agent;

import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
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

    @Override
    public ExtractedFacts extract(String message, AgentContext context) {
        String hospital = HOSPITALS.stream().filter(message::contains).findFirst().orElse(null);
        String department = DEPARTMENTS.stream().filter(message::contains).findFirst().orElse(null);
        String intent = detectIntent(message);
        Boolean stageAnswer = genericYesNo(message);
        return new ExtractedFacts(intent, hospital, department, parseDate(message, context.currentDate()),
                "ASK_ALTERNATIVE".equals(context.stage()) ? first(stageAnswer, yesNo(message, "换日期", "其他日期", "附近日期")) : yesNo(message, "换日期", "其他日期", "附近日期"),
                "ASK_COMPANION".equals(context.stage()) ? first(stageAnswer, yesNo(message, "陪同", "陪我")) : yesNo(message, "陪同", "陪我"),
                "ASK_TRAVEL".equals(context.stage()) ? first(stageAnswer, yesNo(message, "出行提醒", "出发提醒", "提醒出发")) : yesNo(message, "出行提醒", "出发提醒", "提醒出发"),
                "ASK_NOTIFY".equals(context.stage()) ? first(stageAnswer, yesNo(message, "通知", "告诉女儿", "告诉儿子", "告诉家属")) : yesNo(message, "通知", "告诉女儿", "告诉儿子", "告诉家属"),
                transport(message), parseTime(message), timePreference(message),
                "CONFIRM_SLOT".equals(context.stage()) ? genericYesNo(message) : null, null);
    }

    @Override
    public String mode() { return "RULE_FALLBACK"; }

    private String detectIntent(String value) {
        if (containsAny(value, "胸痛", "呼吸困难", "昏迷", "大出血", "喘不上气")) return "EMERGENCY";
        if (containsAny(value, "怎么用药", "药量", "诊断", "检查结果", "是不是得了")) return "MEDICAL_ADVICE";
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
