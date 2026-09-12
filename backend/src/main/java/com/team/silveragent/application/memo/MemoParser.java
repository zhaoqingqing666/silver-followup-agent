package com.team.silveragent.application.memo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 老人端备忘意图识别（规则版，对应 RuleFactExtractor 的兜底思路）。
 * 一句话决定：该不该记成健康备忘（备忘 vs 预约/紧急/医疗问答），并把内容拆成 {text, remind_at}。
 *
 * 命中规则（与产品约定一致）：
 * - 显式托付词（记一下/记住/提醒我/别忘…）且无预约词 → 直接记下；
 * - 隐式（无托付词）但含健康词 + 明确时间、且无预约词 → 先回读确认再记；
 * - 含预约/挂号词的句子是约复诊，交给预约链路；医疗咨询类句子落到“不能诊断”引导。
 */
public final class MemoParser {
    private MemoParser() { }

    /**
     * 演示按中国时区(Asia/Shanghai)起算“现在/今天”：备忘存的是无时区钟点，
     * 中国时区浏览器按本地解析后与真实北京钟点一致（开发容器本身是 UTC）。
     */
    private static final ZoneId DEMO_ZONE = ZoneId.of("Asia/Shanghai");
    /** memos.text 列上限(VARCHAR 300)，超出截断以免插库报错。 */
    private static final int MAX_TEXT = 300;

    /**
     * @param needsTime    提到时间但没给钟点（明早/周X/每天…）：先追问具体几点再落库。
     * @param repeatRule   DAILY 每天 / WEEKLY 每周 / MONTHLY 每月；null = 只提醒一次。
     * @param needsDay     “这个星期三”这类说的那天已经过去了：先追问是哪一天，不猜上周还是下周。
     * @param repeatDayGap 说了“每周/每月”却没说星期几/几号（原话只有“每周提醒我量血压”）：
     *                     没有锚点这条重复提醒永远不会到点，先追问 WEEKLY / MONTHLY；说清了是 null。
     */
    public record MemoIntent(String text, LocalDateTime remindAt, boolean explicit,
                             boolean needsTime, String repeatRule, boolean needsDay,
                             String repeatDayGap) { }

    private static final String[] EXPLICIT = {
            "帮我记一下", "帮我记着", "帮我记住", "帮我记下来", "请记住", "记一下", "记着",
            "记下来", "记住", "帮我记", "给我记", "提醒我", "记得提醒", "别忘了", "别忘", "备忘"
    };
    /** 隐式命中时要求的“健康/复诊相关”词（用药、体征、医嘱、复诊前准备）。 */
    private static final String[] HEALTH = {
            "药", "血压", "血糖", "抽血", "空腹", "胰岛素", "过敏", "忌口", "化验", "量血压",
            "测血糖", "吃药", "复查", "胸片", "片子", "报告", "病历", "医保卡", "心电图",
            "打针", "复诊前", "就诊材料", "就诊卡", "门诊单", "处方"
    };
    /** 明确预约/挂号词：句子主目的是约复诊，不当备忘。 */
    private static final String[] BOOKING = {
            "预约", "挂号", "挂个", "号源", "代约", "帮我约", "麻烦约", "帮我挂", "订号", "排号", "帮我订"
    };
    /** 查备忘的话术（备忘由首页查看，不是托付小记）。 */
    private static final String[] QUERY_MEMO = {
            "有没有备忘", "看看备忘", "查一下备忘", "查看备忘", "我的备忘", "备忘录在哪", "备忘都记了啥"
    };
    /** 医疗咨询类话术：不是托付小记，交回现有“不能诊断”引导。 */
    private static final String[] MEDICAL_ADVICE = {
            "怎么用药", "药量", "减量", "加量", "停药", "吃什么药", "推荐药", "是不是得了",
            "怎么治", "治疗方案", "诊断结果", "检查结果"
    };
    private static final String DAY_WORD = "(?:大后天|后天|明天|明早|明晚|明日|今天|今早|今晚|今日|本周|这周|下周)";
    /** “这/本/下”后面的“个”可有可无（“这个星期三 = 这星期三”），否则限定词认不出来、过去日期不会追问。 */
    private static final Pattern WEEKDAY = Pattern.compile("(?:(下|这|本)\\s*个?\\s*)?(?:周|星期|礼拜)\\s*([一二三四五六日天])");
    /** 相对时间写法：“2分钟后 / 十分钟以后 / 一分钟之后 / 半小时后 / 一个钟头后 / 3个小时后”。 */
    private static final String RELATIVE_PHRASE =
            "(?:半|[0-9]{1,3}|[一二两三四五六七八九十]+)\\s*个?\\s*(?:分钟|小时|钟头)\\s*(?:之?后|以后)";
    /** 提到了“一段时间”但可能没给准时刻（“2分钟提醒我”“饭后半小时吃药”）：只用来决定要不要追问，不用来取值。 */
    private static final Pattern TIME_MENTION = Pattern.compile(
            "(?:半|[0-9]{1,3}|[一二两三四五六七八九十]+)\\s*个?\\s*(?:分钟|小时|钟头)");
    /** 更含混的时间说法：解析不出钟点就该追问，不能默默记成“长期备忘”。 */
    private static final String[] VAGUE_TIME = {
            "一会儿", "等一会", "等会儿", "待会", "待会儿", "过会", "过会儿", "马上", "稍后"
    };
    /** 重复提醒：“每天/每日/天天/每晚/每早”算每天；“每周三”算每周；“每月5号”算每月。 */
    private static final String[] DAILY_WORDS = {
            "每天", "每日", "天天", "每晚", "每早", "每早晨", "每天早", "每天晚"
    };
    private static final Pattern WEEKLY_REPEAT = Pattern.compile("每\\s*个?\\s*(?:周|星期|礼拜)\\s*([一二三四五六日天])");
    private static final Pattern MONTHLY_REPEAT = Pattern.compile("每\\s*个?\\s*月\\s*([0-9]{1,2})\\s*[号日]");
    /** “每周/每星期/每礼拜”后面没跟星期几：只说了周期、没说锚在哪天。 */
    private static final Pattern WEEKLY_BARE = Pattern.compile("每\\s*个?\\s*(?:周|星期|礼拜)(?![一二三四五六日天])");
    /** “每月”后面没跟几号。 */
    private static final Pattern MONTHLY_BARE = Pattern.compile("每\\s*个?\\s*月(?![0-9]{1,2}\\s*[号日])");
    /** 条件性时间说法（饭后/睡前/空腹…）：只说了“什么之后”没说准钟点，该追问而不是丢弃。 */
    private static final String[] CONDITIONAL_TIME = {
            "饭后", "饭后半小时", "饭前", "随餐", "餐后", "餐前", "睡前", "起床后", "起床", "睡醒", "醒来", "空腹"
    };
    /** 中文数字钟点。前后护栏：“差一点/有一点/一点点/一点儿”里的“点”不是钟点，不能当时间认。 */
    private static final String CN_CLOCK_ANCHOR =
            "(?<![差有])[一二两三四五六七八九十]{1,3}\\s*点(?!点|儿)";
    /** 隐式备忘需要“明确时间”：几点（阿拉伯/中文数字）、相对时间、绝对日期、时段词或星期。 */
    private static final Pattern TIME_ANCHOR = Pattern.compile(
            "(?:[0-9]{1,2}\\s*[:：点]|" + CN_CLOCK_ANCHOR + "|" + RELATIVE_PHRASE
                    + "|[0-9]{1,2}\\s*月\\s*[0-9]{1,2}\\s*[号日]|[0-9]{1,2}\\s*[号日]"
                    + "|上午|中午|下午|晚上|傍晚|早上|早晨|清晨|"
                    + DAY_WORD + "|周[一二三四五六日天]|星期[一二三四五六日天]|礼拜[一二三四五六日天])");
    private static final Pattern CLOCK = Pattern.compile(
            "(上午|中午|下午|晚上|傍晚|早上|早晨|清晨|凌晨|夜里)?\\s*([0-9]{1,2})\\s*[:：点]\\s*([0-9]{1,2})?(分)?");
    /** 中文数字钟点（一点…十二点、点半、点一刻/三刻、点零五分），如“早上七点/下午三点半/八点零五分”。 */
    private static final Pattern CN_CLOCK = Pattern.compile(
            "(上午|中午|下午|晚上|傍晚|早上|早晨|清晨|凌晨|夜里)?\\s*"
                    + "(?<![差有])((?:十[一二]?|[一二三四五六七八九两]))\\s*点(?!点|儿)"
                    + "(?:(半|一刻|三刻|零[一二三四五六七八九]|[0-9]{1,2}"
                    + "|(?:十(?:[一二三四五六七八九])?)|(?:[一二三四五]十(?:[一二三四五六七八九])?)|(?:[一二三四五六七八九]))分?)?");
    private static final String[] PERIOD_FALLBACK = {
            "晚上", "傍晚", "中午", "下午", "上午", "早上", "早晨", "清晨"
    };

    public static MemoIntent detect(String message) {
        String raw = message == null ? "" : message.trim();
        if (raw.isEmpty()) return null;
        for (String word : QUERY_MEMO) if (raw.contains(word)) return null;
        for (String word : MEDICAL_ADVICE) if (raw.contains(word)) return null;
        for (String word : BOOKING) if (raw.contains(word)) return null;

        // 时间解析用归一化后的句子（“每晚八点”按“每天晚上八点”理解）；备忘正文仍存老人原话
        String value = normalizePeriodWords(raw);
        boolean explicit = containsAny(raw, EXPLICIT);
        String repeatRule = repeatRuleOf(value);
        boolean timeMentioned = TIME_ANCHOR.matcher(value).find()
                || TIME_MENTION.matcher(value).find()
                || containsAny(value, VAGUE_TIME)
                || containsAny(value, CONDITIONAL_TIME)
                || repeatRule != null;
        if (!explicit) {
            // 疑问/咨询口气（要不要/怎么/什么/吗…）不是托付小记，交回对话与医疗引导
            if (containsAny(value, "要不要", "能不能", "该不该", "需不需要", "怎么", "什么", "是否", "吗", "？", "?")) return null;
            if (!containsAny(value, HEALTH)) return null;
            if (!timeMentioned) return null;
        }
        String text = trimToMax(cleanText(raw, explicit));
        // “这个星期三”但那天已经过去了：不猜上周还是下周，先追问哪一天
        boolean needsDay = pastWeekdayMention(value);
        // “每周/每月”没说周几/几号：先追问锚点，否则这条重复提醒永远不到点
        String repeatGap = repeatDayGap(value);
        boolean askFirst = needsDay || repeatGap != null;
        LocalDateTime remindAt = askFirst ? null : remindAtOf(value);
        // 提到了时间却算不出准时刻（“明早”“2分钟提醒我”“一会儿提醒我”“饭后半小时”）：追问几点，不默默记成长期备忘
        boolean needsTime = !askFirst && remindAt == null && timeMentioned;
        return new MemoIntent(text, remindAt, explicit, needsTime, repeatRule, needsDay, repeatGap);
    }

    /**
     * “每晚/每早”补全成“每天晚上/每天早上”，让时段词折算（晚上→20 点）照常生效。
     * 后两条带 (?!上) 护栏：老人本来就说的“每天晚上八点”不能再补一次变成“每天晚上上八点”，
     * 否则时段词被顶掉、“晚上八点”会被当成早上 8 点存下来。
     */
    private static String normalizePeriodWords(String value) {
        return value.replace("每晚", "每天晚上")
                .replace("每早", "每天早上")
                .replaceAll("每天早(?!上)", "每天早上")
                .replaceAll("每天晚(?!上)", "每天晚上");
    }

    /** 重复规则：每月 > 每周 > 每天；没有重复词返回 null（只提醒一次）。没说清几号/周几也算重复。 */
    private static String repeatRuleOf(String value) {
        if (MONTHLY_REPEAT.matcher(value).find()) return "MONTHLY";
        if (WEEKLY_REPEAT.matcher(value).find()) return "WEEKLY";
        if (MONTHLY_BARE.matcher(value).find()) return "MONTHLY";
        if (WEEKLY_BARE.matcher(value).find()) return "WEEKLY";
        return containsAny(value, DAILY_WORDS) ? "DAILY" : null;
    }

    /**
     * 正文里可能是“时间说法”的正则；改提醒时间时要把命中的这一段从正文里摘掉。
     * 写成方法（而不是字段）：这些正则在类里声明得比这里晚，字段初始化器不允许前向引用。
     */
    private static Pattern[] schedulePatterns() {
        return new Pattern[]{RELATIVE, WEEKLY_REPEAT, MONTHLY_REPEAT, MONTH_DAY, DAY_ONLY, WEEKDAY,
                WEEKLY_BARE, MONTHLY_BARE, CLOCK, CN_CLOCK};
    }

    /** 同上，按字面词找（没有正则形态的日常说法）。长词在前，重叠的片段后面会合并。 */
    private static String[] scheduleWords() {
        return new String[]{
                "大后天", "后天", "明早", "明晚", "明天", "明日", "今早", "今晚", "今天", "今日",
                "下个月", "本周", "这周", "下周",
                "每天早", "每天晚", "每早晨", "每天", "每日", "天天", "每晚", "每早"
        };
    }

    /** 两段时间说法之间只有这些字符时算同一段（“每天早上八点”是一个整体，不能只摘掉“每天”）。 */
    private static final Pattern SCHEDULE_GAP = Pattern.compile("[\\s，,、]*");

    /**
     * 摘掉正文里的时间说法，只留“事项”本身。
     *
     * <p>为什么要有这个：老人说“改第1条，改到每周五下午三点”时，原来只改了 remind_at，
     * 正文还写着“每天早上八点”——首页就成了“事项：每天早上八点量血压 / 提醒：每周五 15:00”，
     * 自己跟自己打架。改提醒时间时用这个方法把正文里的时间一并摘掉，以后时间只由“提醒”那一行负责。
     *
     * <p>只摘能确认是时间的那一段（“明天早上八点”“每周三下午三点”“9月15号”），其余原话照旧；
     * “饭后/空腹/睡前”这类条件词不算时间，不动。摘完空了或只剩标点就退回原话，不能把事项摘没。
     */
    public static String stripSchedule(String text) {
        String raw = text == null ? "" : text.trim();
        if (raw.isEmpty()) return raw;
        List<int[]> spans = new ArrayList<>();
        for (Pattern pattern : schedulePatterns()) {
            Matcher matcher = pattern.matcher(raw);
            while (matcher.find()) spans.add(new int[]{matcher.start(), matcher.end()});
        }
        for (String word : scheduleWords()) {
            for (int at = raw.indexOf(word); at >= 0; at = raw.indexOf(word, at + 1)) {
                spans.add(new int[]{at, at + word.length()});
            }
        }
        if (spans.isEmpty()) return raw;
        spans.sort(Comparator.comparingInt(span -> span[0]));
        // 要摘掉的片段之间夹着的原话照旧保留，按顺序拼起来
        StringBuilder rest = new StringBuilder();
        int cursor = 0;
        for (int[] span : mergedSpans(raw, spans)) {
            rest.append(raw, cursor, span[0]);
            cursor = span[1];
        }
        rest.append(raw.substring(cursor));
        String result = cleanLeftover(rest.toString());
        return result.isEmpty() ? raw : result;
    }

    /** 把重叠、以及只隔着空格/顿号的片段并成一段。 */
    private static List<int[]> mergedSpans(String raw, List<int[]> spans) {
        List<int[]> merged = new ArrayList<>();
        int start = spans.get(0)[0];
        int end = spans.get(0)[1];
        for (int[] span : spans.subList(1, spans.size())) {
            if (span[0] <= end || SCHEDULE_GAP.matcher(raw.substring(end, span[0])).matches()) {
                end = Integer.max(end, span[1]);
                continue;
            }
            merged.add(new int[]{start, end});
            start = span[0];
            end = span[1];
        }
        merged.add(new int[]{start, end});
        return merged;
    }

    /** 摘完之后收拾残局：空格、句首句尾多余的标点、以及残留的“提醒我”这类外壳。 */
    private static String cleanLeftover(String value) {
        String text = trimEdge(value.replaceAll("[\\s　]+", ""));
        String[] prefixes = {"帮我记一下", "帮我记着", "帮我记下来", "记一下", "提醒我", "记得提醒", "别忘了", "记得"};
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) {
                text = trimEdge(text.substring(prefix.length()));
                break;
            }
        }
        return text;
    }

    private static String trimEdge(String value) {
        String text = value;
        String edge = "，,。.、!！?？：:；;";
        while (!text.isEmpty() && edge.indexOf(text.charAt(0)) >= 0) text = text.substring(1);
        while (!text.isEmpty() && edge.indexOf(text.charAt(text.length() - 1)) >= 0) text = text.substring(0, text.length() - 1);
        return text;
    }

    /**
     * 重复提醒缺锚点：说了“每周”没说周几、说了“每月”没说几号。
     * 这种句子照原样存只会得到一条永远不到点的备忘（老人以为设好了），所以要先追问；
     * 说清了返回 null。DAILY 不需要锚点，天然不算缺。
     */
    private static String repeatDayGap(String value) {
        if (MONTHLY_REPEAT.matcher(value).find() || WEEKLY_REPEAT.matcher(value).find()) return null;
        if (MONTHLY_BARE.matcher(value).find()) return "MONTHLY";
        if (WEEKLY_BARE.matcher(value).find()) return "WEEKLY";
        return null;
    }

    /** “这周三/本周三”且那天已经过去了（如今天周四说“这周三”）。 */
    private static boolean pastWeekdayMention(String value) {
        Matcher week = WEEKDAY.matcher(value);
        if (!week.find()) return false;
        if (!"这".equals(week.group(1)) && !"本".equals(week.group(1))) return false;
        int target = dayNumber(week.group(2).charAt(0));
        return target > 0 && target < LocalDate.now(DEMO_ZONE).getDayOfWeek().getValue();
    }

    /** 追问“是哪一天”时回显已过去的那天；取不到返回 null。 */
    public static LocalDate pastWeekdayDate(String value) {
        Matcher week = WEEKDAY.matcher(value);
        if (!week.find()) return null;
        int target = dayNumber(week.group(2).charAt(0));
        if (target < 0) return null;
        LocalDate today = LocalDate.now(DEMO_ZONE);
        int forward = (target - today.getDayOfWeek().getValue() + 7) % 7;
        return today.minusDays((7 - forward) % 7L);
    }

    /** 把句中的“这周三”改写成“下周三”，给追问做快捷回复（句中没有星期几时返回“下周”）。 */
    public static String nextWeekdayWord(String value) {
        Matcher week = WEEKDAY.matcher(value);
        if (!week.find()) return "下周";
        return "下周" + week.group(2);
    }

    /** 回显用日期词（追问“几点”时让老人知道已听懂哪一天）。 */
    private static final String[] DAY_HINTS = {
            "大后天", "后天", "明早", "明晚", "今早", "今晚", "明天", "明日", "今天", "今日", "下周", "这周", "本周"
    };
    private static final String[] PERIOD_HINTS = {
            "晚上", "傍晚", "中午", "下午", "上午", "早上", "早晨", "清晨", "夜里", "凌晨"
    };

    /**
     * 老人回答“几点”后，把备忘正文里的日期（明早/周X…）和答句里的钟点拼成到点时间。
     * 正文没给日期时按今天算、已过点顺延到明天；答句解析不出钟点返回 null（表示还要再问一次）。
     */
    public static LocalDateTime resolveRemindAt(String memoText, String timeAnswer) {
        return resolveRemindAt(memoText, timeAnswer, null);
    }

    /**
     * @param knownDay 前面已经追问清楚的日期（如老人回答“下周三”）。原句里的“这周三”已经作废，
     *                 再拿它当日期会把时间算到错的那一天。
     */
    public static LocalDateTime resolveRemindAt(String memoText, String timeAnswer, LocalDate knownDay) {
        String memo = normalizePeriodWords(memoText == null ? "" : memoText);
        String answer = normalizePeriodWords(timeAnswer == null ? "" : timeAnswer);
        LocalTime time = clockTime(answer);
        if (time == null) time = periodFallbackTime(answer);
        // 老人只回答了“哪一天”（如“下周三”）时，钟点沿用正文里已经听懂的那个
        if (time == null) time = clockTime(memo);
        if (time == null) time = periodFallbackTime(memo);
        if (time == null) return null;
        // 答句里的日期优先（“这个星期三”改成“下周三”时要听新的，不能还用原句那天）
        LocalDate day = dayOf(answer);
        if (day == null) day = knownDay;
        if (day == null) day = dayOf(memo);
        if (day != null) return LocalDateTime.of(day, time);
        LocalDateTime candidate = LocalDateTime.of(LocalDate.now(DEMO_ZONE), time);
        return candidate.isAfter(LocalDateTime.now(DEMO_ZONE)) ? candidate : candidate.plusDays(1);
    }

    /**
     * 句子里说的重复规则（每天/每周X/每月X号）；没说返回 null。
     * 给“把这条提醒改成每周三下午三点”这类改期用：改一次也要能改成重复的。
     */
    public static String repeatRuleIn(String value) {
        return repeatRuleOf(normalizePeriodWords(value == null ? "" : value));
    }

    /** 演示统一的“现在”（北京时间）：备忘到点、健康记录时间戳都用它，容器本身是 UTC。 */
    public static LocalDateTime nowInDemoZone() {
        return LocalDateTime.now(DEMO_ZONE);
    }

    /** 取出句中说的是哪一天（“下周三”/“9月16号”/“明天”）；没给日期返回 null。 */
    public static LocalDate resolveDay(String value) {
        return dayOf(normalizePeriodWords(value == null ? "" : value));
    }

    /**
     * 老人回答“每周几/每月几号”后算出的重复锚点日期：本周/本月的这一天（已过则顺延一周/一月）。
     * 解析不出返回 null（说明还是没听清，得再问一次）。只认锚点词，不认“明天”这类相对日期。
     */
    public static LocalDate resolveRepeatAnchor(String repeatRule, String answer) {
        String value = normalizePeriodWords(answer == null ? "" : answer);
        LocalDate today = LocalDate.now(DEMO_ZONE);
        if ("MONTHLY".equals(repeatRule)) {
            Matcher day = DAY_ONLY.matcher(value);
            if (!day.find()) return null;
            int want = Integer.parseInt(day.group(1));
            LocalDate target = safeDate(today.getYear(), today.getMonthValue(), want);
            if (target != null && !target.isBefore(today)) return target;
            LocalDate next = today.plusMonths(1);
            return safeDate(next.getYear(), next.getMonthValue(), want);
        }
        Matcher week = WEEKDAY.matcher(value);
        if (!week.find()) return null;
        int target = dayNumber(week.group(2).charAt(0));
        if (target < 0) return null;
        int delta = (target - today.getDayOfWeek().getValue() + 7) % 7;
        return today.plusDays(delta);
    }

    /** 取出句中已听懂的时间词（明早/周一/下午…）用于追问回显；取不到给兜底词。 */
    public static String timeHintOf(String value) {
        if (value == null) return "当天";
        for (String hint : DAY_HINTS) if (value.contains(hint)) return hint;
        Matcher week = WEEKDAY.matcher(value);
        if (week.find()) return week.group(0);
        for (String hint : PERIOD_HINTS) if (value.contains(hint)) return hint;
        // “饭后半小时”这类条件性说法也要回显原话，否则老人只听到“当天”，不知道我们听懂了什么。
        // 取最长的那条：“饭后半小时”里含“饭后”，只回“饭后”等于把听懂的信息又说丢了
        String conditional = null;
        for (String hint : CONDITIONAL_TIME) {
            if (value.contains(hint) && (conditional == null || hint.length() > conditional.length())) {
                conditional = hint;
            }
        }
        return conditional == null ? "当天" : conditional;
    }

    /** 显式托付时去掉命令外壳，尽量留下“要记的事”。 */
    private static String cleanText(String value, boolean explicit) {
        if (!explicit) return value;
        String[] prefixes = {
                "请帮我记一下", "帮我记一下", "帮我记着", "帮我记下来", "帮我记住", "请记住",
                "记一下", "记下来", "记着", "记住", "帮我记", "给我记", "提醒我", "记得提醒", "备忘"
        };
        String text = value;
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) { text = text.substring(prefix.length()); break; }
        }
        String[] suffixes = {
                "，记得提醒我", "，别忘了提醒我", "，别忘", "，提醒我", "，记得",
                "记得提醒我", "别忘了提醒我", "别忘了", "别忘", "提醒我"
        };
        for (String suffix : suffixes) {
            if (text.endsWith(suffix)) { text = text.substring(0, text.length() - suffix.length()); break; }
        }
        text = text.trim();
        while (!text.isEmpty() && "，。,.、!！?？：:；; ".indexOf(text.charAt(0)) >= 0) {
            text = text.substring(1).trim();
        }
        while (!text.isEmpty() && "，。,.、!！?？：:；; ".indexOf(text.charAt(text.length() - 1)) >= 0) {
            text = text.substring(0, text.length() - 1).trim();
        }
        return text.isEmpty() ? value : text;
    }

    private static final Pattern RELATIVE = Pattern.compile(
            "(半|[0-9]{1,3}|[一二两三四五六七八九十]+)\\s*个?\\s*(分钟|小时|钟头)\\s*(?:之?后|以后)");

    private static LocalDateTime remindAtOf(String value) {
        // 相对时间提醒（演示常用）：如“2分钟后提醒我量血压”“一分钟之后要吃药”“半小时后吃药”
        // “一个半小时后”这类带“个半”的说法算不准，跳过交给追问，别猜错时间
        Matcher relative = value.contains("个半") ? null : RELATIVE.matcher(value);
        if (relative != null && relative.find()) {
            int ahead = relativeMinutes(relative.group(1), relative.group(2));
            if (ahead >= 1 && ahead <= 1440) return LocalDateTime.now(DEMO_ZONE).plusMinutes(ahead);
        }
        LocalTime time = clockTime(value);
        if (time == null) time = periodFallbackTime(value);
        if (time == null) return null;
        LocalDate day = dayOf(value);
        LocalDateTime candidate;
        if (day != null) {
            candidate = LocalDateTime.of(day, time);
            return candidate;
        }
        candidate = LocalDateTime.of(LocalDate.now(DEMO_ZONE), time);
        // 只给了时刻没给日期：已过点则顺延到明天（避免存一条过期的提醒）
        if (!candidate.isAfter(LocalDateTime.now(DEMO_ZONE))) candidate = candidate.plusDays(1);
        return candidate;
    }

    private static final Pattern MONTH_DAY = Pattern.compile("([0-9]{1,2})\\s*月\\s*([0-9]{1,2})\\s*[号日]");
    private static final Pattern DAY_ONLY = Pattern.compile("([0-9]{1,2})\\s*[号日]");

    /** 按真实日历拼日期；月/日不合法（如 2月30号）返回 null，交给追问，不猜。 */
    private static LocalDate safeDate(int year, int month, int day) {
        if (month < 1 || month > 12 || day < 1 || day > 31) return null;
        if (day > YearMonth.of(year, month).lengthOfMonth()) return null;
        return LocalDate.of(year, month, day);
    }

    /** 相对日期/星期 → 目标日期。 */
    private static LocalDate dayOf(String value) {
        LocalDate today = LocalDate.now(DEMO_ZONE);
        if (containsAny(value, "大后天")) return today.plusDays(3);
        if (value.contains("后天")) return today.plusDays(2);
        if (containsAny(value, "明天", "明早", "明晚", "明日")) return today.plusDays(1);
        if (containsAny(value, "今天", "今早", "今晚", "今日")) return today;
        // 绝对日期：“9月15号 / 15号 / 下个月5号”（只给号数：过了就顺延到下个月）
        Matcher monthDay = MONTH_DAY.matcher(value);
        if (monthDay.find()) {
            LocalDate target = safeDate(today.getYear(),
                    Integer.parseInt(monthDay.group(1)), Integer.parseInt(monthDay.group(2)));
            if (target == null) return null;
            return target.isBefore(today)
                    ? safeDate(today.getYear() + 1, target.getMonthValue(), target.getDayOfMonth())
                    : target;
        }
        Matcher dayOnly = DAY_ONLY.matcher(value);
        if (dayOnly.find()) {
            int day = Integer.parseInt(dayOnly.group(1));
            LocalDate month = value.contains("下个月") ? today.plusMonths(1) : today;
            LocalDate target = safeDate(month.getYear(), month.getMonthValue(), day);
            if (target == null) return null;
            if (!target.isBefore(today)) return target;
            LocalDate next = today.plusMonths(1);
            return safeDate(next.getYear(), next.getMonthValue(), day);
        }
        Matcher week = WEEKDAY.matcher(value);
        if (!week.find()) return null;
        boolean nextWeek = "下".equals(week.group(1));
        int target = dayNumber(week.group(2).charAt(0));
        if (nextWeek) {
            int toMonday = (8 - today.getDayOfWeek().getValue()) % 7;
            if (toMonday == 0) toMonday = 7;
            return today.plusDays(toMonday + (target - 1));
        }
        int delta = (target - today.getDayOfWeek().getValue() + 7) % 7;
        if (delta == 0 && !"这".equals(week.group(1)) && !"本".equals(week.group(1))) delta = 7;
        return today.plusDays(delta);
    }

    private static int dayNumber(char c) {
        return switch (c) {
            case '一' -> 1;
            case '二' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '日', '天' -> 7;
            default -> -1;
        };
    }

    /** “几点/几点几分”。阿拉伯数字(7点/7点半/7:30)或中文数字(七点/三点半/十二点)。返回 null 表示没写具体几点。 */
    private static LocalTime clockTime(String value) {
        Matcher m = CLOCK.matcher(value);
        if (m.find()) {
            int minute = m.group(3) == null ? 0 : Integer.min(59, Integer.parseInt(m.group(3)));
            if (value.contains("点半") && m.group(3) == null) minute = 30;
            return periodHour(m.group(1), Integer.parseInt(m.group(2)), minute);
        }
        Matcher cn = CN_CLOCK.matcher(value);
        if (cn.find()) {
            int hour = cnHour(cn.group(2));
            if (hour < 0) return null;
            int minute = 0;
            String minutes = cn.group(3);
            if (minutes != null) {
                if ("半".equals(minutes)) minute = 30;
                else if ("一刻".equals(minutes)) minute = 15;
                else if ("三刻".equals(minutes)) minute = 45;
                else if (minutes.startsWith("零")) minute = smallNumber(minutes.charAt(1));
                else if (minutes.matches("[0-9]{1,2}")) minute = Integer.parseInt(minutes);
                else minute = chineseNumber(minutes);
            }
            return periodHour(cn.group(1), hour, minute);
        }
        return null;
    }

    /** 时段词折算 12/24 小时制，并夹取合法范围。 */
    private static LocalTime periodHour(String period, int hour, int minute) {
        if (period != null) {
            if (("下午".equals(period) || "晚上".equals(period) || "傍晚".equals(period) || "夜里".equals(period))
                    && hour < 12) hour += 12;
            if ("凌晨".equals(period) && hour == 12) hour = 0;
        }
        hour = Integer.max(0, Integer.min(23, hour));
        minute = Integer.max(0, Integer.min(59, minute));
        return LocalTime.of(hour, minute);
    }

    /** 中文数字小时词 → 1..12；识别不了返回 -1。 */
    private static int cnHour(String word) {
        return chineseNumber(word);
    }

    /** 中文数字 一..九 → 1..9。 */
    private static int smallNumber(char c) {
        return switch (c) {
            case '一' -> 1;
            case '二', '两' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> -1;
        };
    }

    /** 相对时间换算成分钟：分钟／小时／钟头都支持，“半个”=30 分钟；识别不了返回 -1。 */
    private static int relativeMinutes(String number, String unit) {
        boolean hours = "小时".equals(unit) || "钟头".equals(unit);
        if ("半".equals(number)) return hours ? 30 : -1;
        int amount = minuteNumber(number);
        if (amount < 0) return -1;
        return hours ? amount * 60 : amount;
    }

    /** 相对分钟数：阿拉伯数字直接转，中文数字（一/十/十五…）走 chineseNumber。 */
    private static int minuteNumber(String token) {
        if (token == null || token.isEmpty()) return -1;
        if (token.matches("[0-9]{1,3}")) return Integer.parseInt(token);
        return chineseNumber(token);
    }

    /** 中文数字整数：一位(五)或带十(十五/二十/二十五)；识别不了返回 -1。 */
    private static int chineseNumber(String token) {
        if (token == null || token.isEmpty()) return -1;
        int idx = token.indexOf('十');
        if (idx < 0) return smallNumber(token.charAt(0));
        int unit = idx + 1 < token.length() ? smallNumber(token.charAt(idx + 1)) : 0;
        int tens = idx == 0 ? 10 : smallNumber(token.charAt(0)) * 10;
        return tens + unit;
    }

    /** 只有“早上/下午/晚上”这类时段词、没写几点时给一个默认时刻。 */
    private static LocalTime periodFallbackTime(String value) {
        for (String period : PERIOD_FALLBACK) {
            if (value.contains(period)) {
                return switch (period) {
                    case "早上", "早晨", "清晨" -> LocalTime.of(8, 0);
                    case "上午" -> LocalTime.of(9, 0);
                    case "中午" -> LocalTime.of(12, 0);
                    case "下午" -> LocalTime.of(14, 0);
                    case "傍晚" -> LocalTime.of(18, 0);
                    default -> LocalTime.of(20, 0);
                };
            }
        }
        return null;
    }

    private static boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }

    /** 超出列宽则截断，避免插库异常。 */
    private static String trimToMax(String text) {
        return text == null ? "" : text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT);
    }
}
