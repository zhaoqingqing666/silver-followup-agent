package com.team.silveragent.agent;

import com.team.silveragent.application.CareCatalogRepository;
import com.team.silveragent.domain.model.Periods;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RuleFactExtractor implements FactExtractor {
    private final CareCatalogRepository catalog;
    private static final Pattern CN_DATE = Pattern.compile("(\\d{1,2})月(\\d{1,2})[日号]?");
    private static final Pattern ISO_DATE = Pattern.compile("(20\\d{2})-(\\d{1,2})-(\\d{1,2})");
    private static final Pattern SHORT_DATE = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*[./-]\\s*(\\d{1,2})(?!\\d)");
    private static final Pattern TIME = Pattern.compile("(\\d{1,2})[:：点时](\\d{1,2})?");
    /**
     * 用户只说“9月18日”不带年份时的容忍度：这一天即使刚刚过去也仍算今年，
     * 否则刚过零点就会把“昨天的日期”理解成明年。
     */
    private static final int RECENT_PAST_TOLERANCE_DAYS = 1;

    public RuleFactExtractor(CareCatalogRepository catalog) {
        this.catalog = catalog;
    }

    @Override
    public ExtractedFacts extract(String message, AgentContext context) {
        String hospital = catalog.hospitalNames().stream().filter(message::contains).findFirst().orElse(null);
        String department = catalog.departmentNames().stream().filter(message::contains).findFirst().orElse(null);
        String intent = detectIntent(message);
        if ("ASK_ALTERNATIVE".equals(context.stage()) || "NO_SLOT".equals(context.stage())) {
            if (containsAny(message, "换日期", "其他日期", "只要这一天")) intent = "PROVIDE_INFORMATION";
        }
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
        if (MedicalBoundaryRules.looksLikeMedicalAdvice(value)) return "MEDICAL_ADVICE";
        if (containsAny(value, "开始办理", "开始吧", "开始执行", "执行吧", "开始预约", "办理吧", "下一步")) return "START_EXECUTION";
        if (containsAny(value, "取消整个", "不办了", "停止办理", "不想预约了", "退出预约", "先不约了")) return "CANCEL_TASK";
        if (value.contains("取消") && containsAny(value, "预约", "复诊")) return "CANCEL_APPOINTMENT";
        if (containsAny(value, "确认", "执行操作", "确定执行")) return "CONFIRM_ACTION";
        if (containsAny(value, "不执行", "暂不执行", "返回修改", "先别执行")) return "DENY_ACTION";
        if (containsAny(value, "我的预约", "我的复诊时间", "预约情况", "查预约", "查询预约", "已经约了")) return "QUERY_APPOINTMENTS";
        if (containsAny(value, "重新开始", "重新办理", "从头开始")) return "RESTART_TASK";
        if (containsAny(value, "继续刚才", "继续办理", "接着办理")) return "RESUME_TASK";
        if (containsAny(value, "有哪些时间", "什么时候有号", "哪天有号", "可预约时间", "可预约日期", "查询号源")) return "QUERY_AVAILABLE_SLOTS";
        if (containsAny(value, "有哪些科室", "有什么科室", "开设哪些科室", "科室列表")) return "QUERY_DEPARTMENTS";
        if (containsAny(value, "有哪些医院", "有什么医院", "医院列表")) return "QUERY_HOSPITALS";
        if (containsAny(value, "医院怎么样", "医院介绍", "医院资料", "了解医院")) return "QUERY_HOSPITAL_INFO";
        if (containsAny(value, "推荐", "哪家医院好", "怎么选医院", "选哪家医院")) return "REQUEST_RECOMMENDATION";
        if (containsAny(value, "换医院", "修改医院")) return "CHANGE_HOSPITAL";
        if (containsAny(value, "换科室", "修改科室", "改科室")) return "CHANGE_DEPARTMENT";
        if (containsAny(value, "换日期", "改日期", "改成", "不行了")) return "CHANGE_DATE";
        if (containsAny(value, "换时间", "修改时间", "改时间")) return "CHANGE_TIME";
        if (containsAny(value, "材料", "带什么", "准备什么")) return "ASK_MATERIALS";
        if (containsAny(value, "复诊", "预约")) return "CREATE_FOLLOWUP";
        return "PROVIDE_INFORMATION";
    }

    private LocalDate parseDate(String message, LocalDate today) {
        if (message.contains("后天")) return today.plusDays(2);
        if (message.contains("明天")) return today.plusDays(1);
        if (message.contains("今天")) return today;
        Matcher relative = Pattern.compile("(下周|本周|这周|周|星期)([一二三四五六日天])").matcher(message);
        if (relative.find()) {
            int day = "一二三四五六日天".indexOf(relative.group(2)) + 1;
            if (day == 8) day = 7;
            LocalDate monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
            LocalDate result = monday.plusDays(day - 1);
            if (relative.group(1).equals("下周") || result.isBefore(today)) result = result.plusWeeks(1);
            return result;
        }
        Matcher iso = ISO_DATE.matcher(message);
        if (iso.find()) return safeDate(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
        Matcher shortDate = SHORT_DATE.matcher(message);
        if (shortDate.find()) {
            int month = Integer.parseInt(shortDate.group(1));
            int day = Integer.parseInt(shortDate.group(2));
            LocalDate candidate = safeDate(today.getYear(), month, day);
            if (candidate != null && candidate.isBefore(today.minusDays(RECENT_PAST_TOLERANCE_DAYS))) candidate = safeDate(today.getYear() + 1, month, day);
            return candidate;
        }
        Matcher cn = CN_DATE.matcher(message);
        if (!cn.find()) return null;
        int month = Integer.parseInt(cn.group(1));
        int day = Integer.parseInt(cn.group(2));
        LocalDate candidate = safeDate(today.getYear(), month, day);
        if (candidate != null && candidate.isBefore(today.minusDays(RECENT_PAST_TOLERANCE_DAYS))) candidate = safeDate(today.getYear() + 1, month, day);
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
        if (containsAny(value, "不需要", "不用", "不要", "不想", "不接受", "否", "只要这一天")) return false;
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
        if (containsAny(value, "上午", "早上", "早一点", "最早")) return Periods.MORNING;
        if (containsAny(value, "下午", "午后", "晚一点")) return Periods.AFTERNOON;
        return null;
    }

    private boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }
}
