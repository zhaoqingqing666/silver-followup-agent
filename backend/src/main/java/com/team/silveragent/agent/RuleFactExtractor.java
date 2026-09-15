package com.team.silveragent.agent;

import com.team.silveragent.application.care.CareCatalogRepository;
import com.team.silveragent.application.memo.MemoParser;
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
    /** 点名医生的口语句式：动词 + 两三个字的人名 + 医生/大夫。 */
    private static final Pattern DOCTOR_NAME = Pattern.compile("[找挂约看]\\s*([\\u4e00-\\u9fa5]{2,3})(?:医生|大夫)");

    public RuleFactExtractor(CareCatalogRepository catalog) {
        this.catalog = catalog;
    }

    @Override
    public ExtractedFacts extract(String message, AgentContext context) {
        String hospital = catalog.hospitalNames().stream().filter(message::contains).findFirst().orElse(null);
        String department = catalog.departmentNames().stream().filter(message::contains).findFirst().orElse(null);
        String intent = detectIntent(message);
        if ("ASK_ALTERNATIVE".equals(context.stage())
                && containsAny(message, "换日期", "其他日期", "只要这一天", "前后几天", "附近几天", "附近日期"))
            intent = "PROVIDE_INFORMATION";
        if ("NO_SLOT".equals(context.stage())
                && containsAny(message, "只要这一天", "前后几天", "附近几天", "附近日期"))
            intent = "PROVIDE_INFORMATION";
        Boolean stageAnswer = genericYesNo(message);
        boolean alternativeStage = "ASK_ALTERNATIVE".equals(context.stage()) || "NO_SLOT".equals(context.stage());
        return new ExtractedFacts(intent, hospital, department, parseDate(message, context.currentDate()),
                alternativeStage ? first(stageAnswer, yesNo(message, "换日期", "其他日期", "附近日期", "前后几天", "附近几天")) : yesNo(message, "换日期", "其他日期", "附近日期", "前后几天", "附近几天"),
                "ASK_COMPANION".equals(context.stage()) ? first(stageAnswer, yesNo(message, "陪同", "陪我")) : yesNo(message, "陪同", "陪我"),
                "ASK_TRAVEL".equals(context.stage()) ? first(stageAnswer, yesNo(message, "出行提醒", "出发提醒", "提醒出发")) : yesNo(message, "出行提醒", "出发提醒", "提醒出发"),
                "ASK_NOTIFY".equals(context.stage()) ? first(stageAnswer, yesNo(message, "通知", "告诉女儿", "告诉儿子", "告诉家属")) : yesNo(message, "通知", "告诉女儿", "告诉儿子", "告诉家属"),
                transport(message), parseTime(message), timePreference(message),
                "CONFIRM_SLOT".equals(context.stage()) ? genericYesNo(message) : null, null,
                emotion(message), concern(message), familyContact(message), doctor(message));
    }

    private String familyContact(String value) {
        for (String name : List.of("小丽", "女儿", "儿子", "老伴", "家属")) {
            if (value.contains(name)) return name;
        }
        return null;
    }

    /**
     * 规则回退也尽量把「医生」从话里捞出来。
     *
     * <p>两种诉求：要专家号（“挂个专家号”“找主任看”→ 记“专家”）与点名医生
     * （“找张建国”→ 记名字）。规则回退拿不到医生名册，姓氏能不能对上由
     * Java 在真实号源里判——这里只负责把话里的医生线索原样带出去。
     */
    private String doctor(String value) {
        // 「找/挂/约 + 两三个字 + 医生/大夫」的点名句式。“看内科医生”这类把科室当宾语的
        // 说法可能误捕一个科室名，但对不上真实医生时走「报出诊名单」的兜底，不会卡流程。
        Matcher named = DOCTOR_NAME.matcher(value);
        if (named.find()) return named.group(1);
        if (containsAny(value, "专家", "主任")) {
            String stripped = value.replace("主任医师", "").replace("副主任医师", "")
                    .replace("主治医师", "").replace("主任", "").replace("医生", "")
                    .replace("找", "").replace("挂", "").replace("看", "").trim();
            // “张主任”“王主任”这类「姓氏+职称」剥掉职称后剩一个姓，仍然是有效线索；
            // 剥完什么都没剩（“找专家”“挂个专家号”）就是纯号别诉求。
            if (!stripped.isEmpty() && !stripped.contains("专家") && stripped.length() <= 3) return stripped;
            return "专家";
        }
        return null;
    }

    @Override
    public String mode() { return "RULE_FALLBACK"; }

    private String detectIntent(String value) {
        if (containsAny(value, "胸痛", "心口疼", "心口痛", "胸口疼", "胸口痛", "心前区疼", "心前区痛",
                "胸闷", "胸部压迫", "呼吸困难", "昏迷", "大出血", "喘不上气")) return "EMERGENCY";
        // 越界口径与 SafetyGuard 共用一份规则：词表写两遍迟早只剩一份是对的。
        if (MedicalBoundaryRules.looksLikeMedicalAdvice(value)) return "MEDICAL_ADVICE";
        if (MedicalBoundaryRules.bodyDiscomfort(value)) return "HEALTH_CONCERN";
        if (containsAny(value, "心里不舒服", "心口不舒服")) return "CLARIFY_DISCOMFORT";
        if (containsAny(value, "害怕", "担心", "紧张", "好累", "很累", "疲惫", "孤单", "一个人去", "没人陪")) return "EMOTIONAL_SUPPORT";
        if (containsAny(value, "可以和你聊天", "能和你聊天", "陪我聊", "聊聊天", "聊点别的", "说说话", "聊一会", "你是谁", "你好", "谢谢", "感谢")) return "SMALL_TALK";
        if (containsAny(value, "取消整个", "不办了", "停止办理", "不想预约了", "不要预约了", "不预约了", "不约了", "退出预约", "先不约了")) return "CANCEL_TASK";
        boolean explicitCancel = containsAny(value, "取消", "退掉", "撤销", "作废");
        boolean discardExisting = value.contains("不要")
                && containsAny(value, "之前", "原来", "已有", "已经", "那个");
        // 「取消预约 / 取消已预约」这类说法都同时含「预约」与「取消」，已被上面这条覆盖，
        // 再写一遍只会让人以为另有分支。
        if (containsAny(value, "预约", "之前的号", "原来的号") && (explicitCancel || discardExisting)) return "CANCEL_APPOINTMENT";
        if (containsAny(value, "还是这个时间", "仍然这个时间", "保留这个时间", "就按这个时间", "时间不改")) return "CONFIRM_ACTION";
        if (containsAny(value, "确认", "执行操作", "确定执行")) return "CONFIRM_ACTION";
        if (containsAny(value, "不执行", "暂不执行", "返回修改", "先别执行")) return "DENY_ACTION";
        if (containsAny(value, "我的预约", "我的复诊时间", "预约情况", "查预约", "查询预约", "已经约了")) return "QUERY_APPOINTMENTS";
        if (containsAny(value, "重新开始", "重新办理", "从头开始")) return "RESTART_TASK";
        if (containsAny(value, "继续刚才", "继续办理", "接着办理", "接着来", "往下办", "继续弄", "回到刚才")
                || value.trim().matches("继续[。！!，,]?")) return "RESUME_TASK";
        if (isProcessQuestion(value)) return "EXPLAIN_PROCESS";
        if (containsAny(value, "怎么去医院", "怎么到医院", "去医院怎么走", "出行路线", "查看路线", "地图")) return "ASK_TRAVEL_ROUTE";
        // 院内指引的规则回退表达。这些是模型不可用时的兜底，不参与地图快速通道，
        // 因此可以覆盖“我已经到医院了”“进去以后怎么走”这类完整口语句子。
        if (containsAny(value, "几楼", "几号房", "哪个诊室", "诊室在哪", "科室在哪", "哪间诊室",
                "哪个房间", "在哪个房间", "院内怎么走", "院内指引", "院内路线", "楼里怎么走",
                "到医院后怎么走", "到医院里面", "医院里面", "进去以后", "进医院以后",
                "我已经到医院了", "已经到医院", "我到医院了")) return "ASK_LOCATION_GUIDE";
        if (containsAny(value, "有哪些时间", "什么时候有号", "哪天有号", "可预约时间", "可预约日期", "查询号源")) return "QUERY_AVAILABLE_SLOTS";
        if (containsAny(value, "有哪些医生", "哪些医生出诊", "哪个医生看", "医生出诊", "出诊医生")) return "QUERY_DOCTORS";
        if (containsAny(value, "有哪些科室", "有什么科室", "开设哪些科室", "科室列表")) return "QUERY_DEPARTMENTS";
        if (containsAny(value, "有哪些医院", "有什么医院", "医院列表")) return "QUERY_HOSPITALS";
        if (containsAny(value, "医院怎么样", "医院介绍", "医院资料", "了解医院")) return "QUERY_HOSPITAL_INFO";
        if (containsAny(value, "推荐", "哪家医院好", "怎么选医院", "选哪家医院")) return "REQUEST_RECOMMENDATION";
        if (containsAny(value, "换医院", "换个医院", "换家医院", "换一个医院", "其他医院", "修改医院")) return "CHANGE_HOSPITAL";
        if (containsAny(value, "换科室", "修改科室", "改科室")) return "CHANGE_DEPARTMENT";
        if (containsAny(value, "换日期", "换一天", "改一天", "改天", "其他日期", "改日期", "改成", "不行了")) return "CHANGE_DATE";
        if (containsAny(value, "换时间", "换个时间", "换一下时间", "其他时间", "修改时间", "改时间")) return "CHANGE_TIME";
        if (containsAny(value, "材料", "带什么", "准备什么")) return "ASK_MATERIALS";
        if (containsAny(value, "复诊", "预约", "我要办理", "开始办理", "办理这个")) return "CREATE_FOLLOWUP";
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
            if (candidate != null && candidate.isBefore(today.minusDays(1))) candidate = safeDate(today.getYear() + 1, month, day);
            return candidate;
        }
        Matcher cn = CN_DATE.matcher(message);
        if (!cn.find()) return null;
        int month = Integer.parseInt(cn.group(1));
        int day = Integer.parseInt(cn.group(2));
        LocalDate candidate = safeDate(today.getYear(), month, day);
        if (candidate != null && candidate.isBefore(today.minusDays(1))) candidate = safeDate(today.getYear() + 1, month, day);
        return candidate;
    }

    private LocalTime parseTime(String message) {
        return spokenTime(message);
    }

    /**
     * 从一句口语里抽「几点几分」。阿拉伯数字先走原来的正则，失配时交给
     * {@link MemoParser#clockIn} 兜中文数字（「下午三点半」→ 15:30，与备忘共用一份钟点口径）。
     *
     * <p>「下午」按整句判，不只看紧挨着钟点的那几个字：老人说「我要下午的三点半的」，
     * 「下午」和「三点半」中间隔着一个「的」，只看紧邻时段词会把它算成凌晨 3:30，
     * 再按最近号源推荐，就会把下午的号推成上午的号。
     *
     * <p>模型的兜底也走这里（{@code AgentRuntime} 补 {@code selectedTime}），保持两条链路同一口径。
     */
    public LocalTime spokenTime(String message) {
        if (message == null) return null;
        Matcher matcher = TIME.matcher(message);
        if (matcher.find()) {
            try {
                boolean minuteMissing = matcher.group(2) == null || matcher.group(2).isBlank();
                int hour = Integer.parseInt(matcher.group(1));
                // 「点半」与中文数字那边对齐：「3点半」和「三点半」必须是同一个答案。
                int minute = minuteMissing && message.contains("点半") ? 30
                        : (minuteMissing ? 0 : Integer.parseInt(matcher.group(2)));
                if (message.contains("下午") && hour < 12) hour += 12;
                return LocalTime.of(hour, minute);
            } catch (RuntimeException ignored) { return null; }
        }
        LocalTime chinese = MemoParser.clockIn(message);
        if (chinese == null) return null;
        int hour = chinese.getHour();
        if (message.contains("下午") && hour < 12) hour += 12;
        return LocalTime.of(hour, chinese.getMinute());
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
        if (containsAny(value, "上午", "早上", "早一点", "最早")) return "MORNING";
        if (containsAny(value, "下午", "午后", "晚一点")) return "AFTERNOON";
        return null;
    }

    private String emotion(String value) {
        if (containsAny(value, "害怕", "担心", "紧张")) return "ANXIOUS";
        if (containsAny(value, "累", "疲惫", "没精神")) return "TIRED";
        if (containsAny(value, "孤单", "一个人", "没人陪")) return "LONELY";
        return null;
    }

    private String concern(String value) {
        return containsAny(value, "害怕", "担心", "紧张", "累", "疲惫", "孤单", "一个人", "没人陪", "不舒服") ? value : null;
    }

    private boolean isProcessQuestion(String value) {
        return containsAny(value, "办理流程", "预约流程", "复诊流程", "怎么办理", "怎么复诊", "到医院怎么办", "到院流程")
                || (value.contains("流程") && containsAny(value, "办理", "复诊", "预约", "到院", "医院"));
    }

    private boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }
}
