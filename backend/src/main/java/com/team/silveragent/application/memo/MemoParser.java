package com.team.silveragent.application.memo;

import com.team.silveragent.application.time.BusinessClock;

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
     * 兜底时区：<b>只给没有业务时钟的调用方用（单元测试）</b>。
     *
     * <p>生产路径不读这个常量：`FollowupAgentService` 每个入口都把业务时钟的锚点
     * （{@code BusinessClock.now()} / {@code today()}）当参数传进来，配置改了
     * `business.time.zone`，备忘的「今天/现在」跟着一起改。见各方法带 now/today 的重载。
     *
     * <p>时区值只从 {@link BusinessClock#DEFAULT_ZONE} 取，不在这里另写一遍：
     * 业务时区要改就得一起改，两个常量迟早只剩下一个是对的。
     */
    private static final ZoneId DEMO_ZONE = BusinessClock.DEFAULT_ZONE;
    /** memos.text 列上限(VARCHAR 300)，超出截断以免插库报错。 */
    private static final int MAX_TEXT = 300;

    /**
     * @param remindAts    解析出的到点时间。一句话说几天就是几条（“这周周一周二周三早八吃药”＝3 条），
     *                     为空表示还缺信息，由 needsTime / needsDay 决定该追问哪个。
     * @param needsTime    提到时间但没给钟点（明早/周X/每天…）：先追问具体几点再落库。
     * @param repeatRule   DAILY 每天 / WEEKLY 每周 / MONTHLY 每月；null = 只提醒一次。
     * @param needsDay     日期定不下来，先追问是哪一天：说的那天已经过去了（“这周三”今天周五说），
     *                     或者只说了“这周/下周”这种范围、没说具体哪天（“我这周要吃药”）。
     * @param repeatDayGap 说了“每周/每月”却没说星期几/几号（原话只有“每周提醒我量血压”）：
     *                     没有锚点这条重复提醒永远不会到点，先追问 WEEKLY / MONTHLY；说清了是 null。
     */
    public record MemoIntent(String text, List<LocalDateTime> remindAts, boolean explicit,
                             boolean needsTime, String repeatRule, boolean needsDay,
                             String repeatDayGap) { }

    private static final String[] EXPLICIT = {
            "帮我记一下", "帮我记着", "帮我记住", "帮我记下来", "请记住", "记一下", "记着",
            "记下来", "记住", "帮我记", "给我记", "提醒我", "记得提醒", "别忘了", "别忘", "备忘"
    };
    /**
     * {@link #EXPLICIT} 里<b>让提醒</b>的那几个（“记一下”“备忘”那半不算）。
     *
     * <p>两者在解析上是一回事——都是显式托付，都该记一条；但在一句话夹着两件事时是两回事：
     * “我血压 130，顺便<b>提醒我</b>明天早上吃药”里，让提醒那半是第二件事；
     * 而“记一下我血压 130”整句就是一件事，只是被健康数值那条链路认领了。
     */
    private static final String[] REMINDING = {"提醒我", "提醒一下", "记得提醒", "别忘了", "别忘"};

    /**
     * 这句是不是在让「提醒」，而不是只让记账（判据的用法见
     * {@code FollowupAgentService#alsoATimedMemoNote}：一句话夹着两件事时，第二件得是让提醒才算）。
     */
    public static boolean asksForAReminder(String message) {
        return message != null && containsAny(message, REMINDING);
    }
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
    /**
     * “几号”里的中文数字（一…三十一），与 {@link #chineseNumber} 同一口径：一位数（五）、带十（十五/二十/二十五）都认。
     *
     * <p>做成常量是因为它要被好几张表共用（绝对日期、每月几号、隐式备忘的时间锚点）。分开各写一份会漂：
     * 一处认了“九月五号”、另一处不认，老人就会听到“您说的是哪天”这种自相矛盾的回问——他那句话里明明说了。
     */
    private static final String CN_DAY_NUM = "[一二三四五六七八九十]{1,3}";
    /** “几号”里的数字：阿拉伯数字（15号）或中文（十五号）。 */
    private static final String DAY_NUM = "(?:[0-9]{1,2}|" + CN_DAY_NUM + ")";
    /**
     * “一日三次／一日三餐”里的“日”是频次不是日期。少了这道守卫，老人说“提醒我一日三次吃药”
     * 会被记成每月 1 号的提醒，而他真正要的是先问清几点吃。
     */
    private static final String NOT_FREQUENCY = "(?!\\s*[0-9一二两三四五六七八九十]?\\s*[次遍顿餐])";
    /**
     * “几月几号”的那一段文字（“9月15号”“九月十五”）。
     *
     * <p>号/日<b>可以省</b>，但只在月份写明了的时候：“九月十五”“十月一”照认，光说“十五”不算——
     * 那一串数字没有月份兜底，本来就说不清是十五号还是十五分钟。
     *
     * <p>省掉号/日的那一支要自证不是别的意思：后面不能再跟“号/日/天”（“十月一天都没歇”里的
     * “十月一”不是十月一号），也要过 {@link #NOT_FREQUENCY} 那道频次守卫。
     */
    private static final String DAY_SUFFIX_OR_NONE = "(?:\\s*[号日]|(?![号日天])" + NOT_FREQUENCY + ")";
    /** “几月几号”整段；识别（{@link #TIME_ANCHOR}）、取值（{@link #MONTH_DAY}）共用这一份，不能只宽一处。 */
    private static final String MONTH_DAY_TEXT = DAY_NUM + "\\s*月\\s*" + DAY_NUM + DAY_SUFFIX_OR_NONE;
    /**
     * 中文数字钟点的“几”。必须连两字钟点一起认（十三…二十四）：“二十三点”就是晚上 11 点。
     *
     * <p>只写 {@code 十[一二]?} 时，正则会在“二十三点”里退到“三点”上匹配，老人听到的提醒是凌晨 3 点。
     * 识别（{@link #CN_CLOCK_ANCHOR}）与取时刻（{@link #CN_CLOCK}）两张表共用这一个常量，不能只宽一处。
     *
     * <p>“差/有”的守卫只管“一”这一支：它本意是拦“有一点高”“差一点迟到”，加在整组数字前面
     * 就成了“凡跟在差/有后面的一律不算钟点”，把“还<b>有九点</b>量血压”里的九点一并吃掉。
     *
     * <p>“零”只做<b>单字</b>钟点（零点＝当天零点），后面不接阿拉伯数字：“零点5”这种小数点写法
     * 不是钟点。中文数字小数点（“零点五”）仍会被读成 00:05——这与“五点八”被读成 05:08 是同一处
     * “点”兼作小数点与钟点分隔符的老歧义，不是这次新添的；真要分，得先有“这个数是量出来的值”那层上下文。
     *
     * <p>每一支都写成<b>合式的中文数字</b>（一…九、十、十一…十九、二十、二十一…二十九），
     * 而不是“任意几个数字凑一起”：宽松写法会让“一”粘到后一个字上，把<b>“周一八点”读成“一八点”</b>
     * ＝凌晨 1 点（{@code chineseNumber} 认不出“一八”时取的是头一个字）。星期几后面紧跟钟点是最常见的
     * 说法之一，这种错法不声不响，还正好差在老人说的那个字上。
     */
    private static final String CN_HOUR_NUM =
            "(?:(?<![差有])一|[二两三四五六七八九]|零(?![0-9０-９])|十[一二三四五六七八九]?|二十[一二三四五六七八九]?)";
    /**
     * 只指向<b>某一天</b>的相对词（长词在前，"大后天"里的"后天"才不会被数成第二处）。
     *
     * <p>刻意不含"这周/本周/下周"：那是范围词、不是某一天，说"我这周要吃药"时该走的是
     * {@link #bareWeekWord} 那条追问，不该被这里当成"说了两个时间"。
     */
    private static final String[] RELATIVE_DAYS = {
            "大后天", "后天", "明天", "明早", "明晚", "明日", "今天", "今早", "今晚", "今日"
    };
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
    private static final Pattern MONTHLY_REPEAT = Pattern.compile("每\\s*个?\\s*月\\s*(" + DAY_NUM + ")\\s*[号日]");
    /** “每周/每星期/每礼拜”后面没跟星期几：只说了周期、没说锚在哪天。 */
    private static final Pattern WEEKLY_BARE = Pattern.compile("每\\s*个?\\s*(?:周|星期|礼拜)(?![一二三四五六日天])");
    /** “每月”后面没跟几号。 */
    private static final Pattern MONTHLY_BARE = Pattern.compile("每\\s*个?\\s*月(?!" + DAY_NUM + "\\s*[号日])");
    /**
     * “这周/本周/下周”后面没跟星期几：只给了范围，没说具体哪一天。
     * 负向断言跟 WEEKLY_BARE 一个道理，保证“这个星期三”不被当成范围词——它后面跟着星期几，是确定的某一天。
     */
    private static final Pattern BARE_WEEK = Pattern.compile("(这|本|下)\\s*个?\\s*(?:周|星期|礼拜)(?![一二三四五六日天])");
    /** 一个星期几的开头，可带“这/本/下”限定词。 */
    private static final Pattern WEEKDAY_HEAD = Pattern.compile("(?:(下|这|本)\\s*个?\\s*)?(?:周|星期|礼拜)\\s*([一二三四五六日天])");
    /** 跟在“周一”后面的裸星期字：“每周一三五”＝周一/周三/周五。前导连接符一起吃掉，好把整串换成单天。 */
    private static final Pattern WEEKDAY_TAIL = Pattern.compile("[\\s、和跟与及,]*([一二三四五六日天])");
    /** 两个星期说法之间只有这些字符时算同一串（“周三、周五”是一串，“周三，另外周五”不是）。 */
    private static final Pattern WEEKDAY_GAP = Pattern.compile("[\\s、和跟与及,]*");
    /** 星期串开头那个范围字（这/本/下，可能带“个”），用来换成“下”。 */
    private static final Pattern WEEK_LEADING = Pattern.compile("^(?:这|本|下)\\s*个?\\s*");
    /** 条件性时间说法（饭后/睡前/空腹…）：只说了“什么之后”没说准钟点，该追问而不是丢弃。 */
    private static final String[] CONDITIONAL_TIME = {
            "饭后", "饭后半小时", "饭前", "随餐", "餐后", "餐前", "睡前", "起床后", "起床", "睡醒", "醒来", "空腹"
    };
    /**
     * 中文数字钟点。前后护栏：“差一点/有一点/一点点/一点儿”里的“点”不是钟点，不能当时间认。
     *
     * <p>“差/有”这道守卫只管“一”这一支。它本意是拦“有一点高”“差一点迟到”，但加在整组数字前面
     * 就成了“凡跟在差/有后面的一律不算钟点”，把“还<b>有九点</b>量血压”里的九点一并吃掉——
     * 那半句于是在“一句话说了几个钟点”的判据里消失，两件事两个时间的托付被当成一个时间记下来，
     * 第二个到点永远不响。所以守卫跟着“一”走，其余数字照常认。
     */
    private static final String CN_CLOCK_ANCHOR = CN_HOUR_NUM + "\\s*点(?!点|儿)";
    /** 隐式备忘需要“明确时间”：几点（阿拉伯/中文数字）、相对时间、绝对日期、时段词或星期。 */
    private static final Pattern TIME_ANCHOR = Pattern.compile(
            "(?:[0-9]{1,2}\\s*[:：点]|" + CN_CLOCK_ANCHOR + "|" + RELATIVE_PHRASE
                    + "|" + MONTH_DAY_TEXT + "|" + DAY_NUM + "\\s*[号日]" + NOT_FREQUENCY
                    + "|上午|中午|下午|晚上|傍晚|早上|早晨|清晨|"
                    + DAY_WORD + "|周[一二三四五六日天]|星期[一二三四五六日天]|礼拜[一二三四五六日天])");
    private static final Pattern CLOCK = Pattern.compile(
            "(上午|中午|下午|晚上|傍晚|早上|早晨|清晨|凌晨|夜里)?\\s*([0-9]{1,2})\\s*[:：点]\\s*([0-9]{1,2})?(分)?");
    /** 两处钟点之间只有这些连接符时是一个范围（“三点到五点”“15:00-17:00”），算一处时间。 */
    private static final Pattern CLOCK_RANGE_GAP = Pattern.compile("\\s*(?:到|至|~|～|-|－|—|–)\\s*");
    /**
     * 中文数字钟点（一点…二十四点、点半、点一刻/三刻、点零五分），如“早上七点/下午三点半/八点零五分/二十三点”。
     *
     * <p>“几”这一支的守卫与 {@link #CN_CLOCK_ANCHOR} 同源（{@link #CN_HOUR_NUM}）：识别和取时刻
     * 两张表如果一处收窄一处没收，会出现“认出了两个钟点、取到的却还是那一个”这种自相矛盾的解读。
     */
    private static final Pattern CN_CLOCK = Pattern.compile(
            "(上午|中午|下午|晚上|傍晚|早上|早晨|清晨|凌晨|夜里)?\\s*"
                    + "(" + CN_HOUR_NUM + ")\\s*点(?!点|儿)"
                    + "(?:(半|一刻|三刻|零[一二三四五六七八九]|[0-9]{1,2}"
                    + "|(?:十(?:[一二三四五六七八九])?)|(?:[一二三四五]十(?:[一二三四五六七八九])?)|(?:[一二三四五六七八九]))分?)?");
    private static final String[] PERIOD_FALLBACK = {
            "晚上", "傍晚", "中午", "下午", "上午", "早上", "早晨", "清晨"
    };
    /** 时段词（与 {@link #CLOCK}/{@link #CN_CLOCK} 前缀里那一组同一批）：只说了时段、没点钟点的说法。 */
    private static final Pattern PERIOD = Pattern.compile(
            "上午|中午|下午|晚上|傍晚|早上|早晨|清晨|凌晨|夜里");

    /** 没有业务时钟的调用方（单元测试）走这条；生产走 {@link #detect(String, LocalDateTime)}。 */
    public static MemoIntent detect(String message) {
        return detect(message, nowInDemoZone());
    }

    /**
     * @param now 业务时区的“现在”。判断“这周三已经过去了”“只给时刻、已过点就顺延明天”都用它，
     *            所以配置改了 {@code business.time.zone} 备忘推算跟着一起改。
     */
    public static MemoIntent detect(String message, LocalDateTime now) {
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
        LocalDate today = now.toLocalDate();
        List<DayToken> tokens = dayTokens(value, today);
        List<LocalDate> days = daysOf(value, tokens, today);
        List<LocalDateTime> remindAts = days.isEmpty()
                ? oneOrEmpty(remindAtOf(value, now, false))
                : timesAt(days, clockTimeOfUnsaid(value));
        // 日期定不下来就得先问，两种情形：
        // 一种是“这周三”今天周五说——那天已经过去了，不猜上周还是下周；
        // 另一种是“我这周要吃药”——只说了范围没说哪天。后者原来根本不问，直接按
        // “今天已过点就顺延明天”兜底，老人周五说“这周”会被悄悄记成周六。
        boolean needsDay = days.isEmpty()
                ? bareWeekWord(value) != null
                : tokens.stream().anyMatch(DayToken::past)
                        || remindAts.stream().anyMatch(at -> !at.isAfter(now));
        // “每周/每月”没说周几/几号：先追问锚点，否则这条重复提醒永远不到点
        String repeatGap = repeatDayGap(value);
        boolean askFirst = needsDay || repeatGap != null;
        // 提到了时间却算不出准时刻（“明早”“2分钟提醒我”“一会儿提醒我”“饭后半小时”）：追问几点，不默默记成长期备忘
        boolean needsTime = !askFirst && remindAts.isEmpty() && timeMentioned;
        return new MemoIntent(text, askFirst ? List.of() : remindAts, explicit, needsTime, repeatRule, needsDay, repeatGap);
    }

    /**
     * 一句话里是不是说了<b>不止一个时间点</b>。
     *
     * <p>下面这些说法都只该记一条，但解析器只认一套“钟点 + 日子”，硬记就是两种错：
     * <ul>
     *   <li>“周一八点吃药，周三下午三点复查” → 两天共用同一个钟点，第二条的“下午三点”被顶掉；</li>
     *   <li>“9月15号和9月20号早上八点吃药”“每天早八点和晚八点吃药” → 后面的日子/钟点被静默丢掉。</li>
     * </ul>
     * 所以命中时<b>不猜</b>：调用方请老人一件一件说（见 {@code FollowupAgentService} 的回问）。
     *
     * <p>这些是<b>不该命中</b>的正常说法（本来就能算对）：一连串星期几（“下周一和周三”是一串、一处）、
     * 一个时间范围（“三点到五点”是一处）、一件事一个时间（“明天早上八点吃药”＝一处日子 + 一处钟点）。
     *
     * <p>判过之后才轮到 {@link #detect}：这句话可能同时被别的链路（预约、医疗）认领，
     * 所以调用方要等认出“这是备忘”之后、动手写库之前判它。
     */
    public static boolean severalMomentsInOneSentence(String value) {
        String text = normalizePeriodWords(value == null ? "" : value);
        return clockMoments(text) + barePeriodMoments(text) > 1 || dayMoments(text) > 1;
    }

    /**
     * 句子里只说了时段、没说几点的时段词<b>独立</b>处数（“早上提醒我吃药，中午提醒我吃药，晚上提醒我吃药”＝三处）。
     *
     * <p>为什么得单独数它：这类说法一个钟点都没有，{@link #clockMoments} 一处也数不出来，
     * 而解析器只取<b>其中一个</b>时段词——老人说了三顿，追问回显的是“您说的是‘晚上’”，
     * 早上和中午那两顿就这么没了，他还以为都交代过了。跟“两个钟点挤一句”是同一种错（见
     * {@link #severalMomentsInOneSentence} 的那两条例子），按同一口径请他一件一件说。
     *
     * <p>贴在钟点上的时段词不算独立一处（“早上8点”“晚上八点”是一处时间）：与钟点命中范围
     * <b>相交或相接</b>就不数。所以“晚上八点吃药，早上提醒我吃药”是 1 个钟点 + 1 个独立时段＝两处，照样拦。
     *
     * <p>不并进 {@link #clockMoments}：那条判据还被“补答一个钟点”那条路用着
     * （{@link #severalClocksInOneSentence}），答句里说“早上”本来就该按一处算。
     */
    private static int barePeriodMoments(String value) {
        List<int[]> clocks = new ArrayList<>();
        collect(value, CLOCK, clocks);
        collect(value, CN_CLOCK, clocks);
        collect(value, RELATIVE, clocks);
        List<int[]> periods = new ArrayList<>();
        collect(value, PERIOD, periods);
        int groups = 0;
        for (int[] period : independent(periods)) {
            boolean attachedToClock = false;
            for (int[] clock : clocks) {
                if (period[0] <= clock[1] && clock[0] <= period[1]) {
                    attachedToClock = true;
                    break;
                }
            }
            if (!attachedToClock) groups++;
        }
        return groups;
    }

    /**
     * 一句话里是不是说了<b>不止一个钟点</b>（只看钟点，不看日子）。
     *
     * <p>给“老人补答一个时间”那条路用（{@code FollowupAgentService.applyMemoAnswer} 等）：
     * 那一步的内容已经由前一句定死了，答句只负责补时间，而 {@code resolveRemindAt} 只取<b>一个</b>
     * 钟点——答句里塞了两个，第二个就被静默丢掉，两个日子还会共用第一个钟点。
     *
     * <p>为什么这里不能顺手用 {@link #severalMomentsInOneSentence}：那条判据会把
     * <b>“下周三，下周五”</b>也判成两处（逗号不算星期串的连接符），可那正是补答日子时该支持的说法
     * ——同一件事的两天，本来就该拆成两条。误伤正常的多天答复，比漏拦更贵。
     */
    public static boolean severalClocksInOneSentence(String value) {
        return clockMoments(normalizePeriodWords(value == null ? "" : value)) > 1;
    }

    /** 句中独立的钟点处数：范围连写的算一处（“三点到五点”），相对时刻（“2分钟后”）也算一处。 */
    private static int clockMoments(String value) {
        List<int[]> hits = new ArrayList<>();
        collect(value, CLOCK, hits);
        collect(value, CN_CLOCK, hits);
        collect(value, RELATIVE, hits);
        int groups = 0;
        int[] previous = null;
        for (int[] hit : independent(hits)) {
            if (previous == null || !CLOCK_RANGE_GAP.matcher(value.substring(previous[1], hit[0])).matches()) {
                groups++;
            }
            previous = hit;
        }
        return groups;
    }

    /** 句中独立的日子处数：连写的星期几算一串、算一处（“下周一和周三”），相对日与“9月15号”各算一处。 */
    private static int dayMoments(String value) {
        List<int[]> hits = new ArrayList<>();
        collectWords(value, RELATIVE_DAYS, hits);
        collect(value, MONTH_DAY, hits);
        collect(value, DAY_ONLY, hits);
        return weekdayRunCount(value) + independent(hits).size();
    }

    /**
     * 星期几串的条数：只被连接符隔开的算同一串。
     * “下周一和周三”是一串；“周一吃药，周三复查”是两串。
     *
     * <p>{@code today} 只用来算“这天过没过”（{@code DayToken.past}），串的条数与它无关，所以这里取兜底时区。
     */
    private static int weekdayRunCount(String value) {
        int runs = 0;
        DayToken previous = null;
        for (DayToken token : dayTokens(value, LocalDate.now(DEMO_ZONE))) {
            if (previous == null
                    || !WEEKDAY_GAP.matcher(value.substring(previous.end(), token.start())).matches()) {
                runs++;
            }
            previous = token;
        }
        return runs;
    }

    /** 把某个正则的所有命中记成 [起, 止) 片段。 */
    private static void collect(String value, Pattern pattern, List<int[]> hits) {
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) hits.add(new int[]{matcher.start(), matcher.end()});
    }

    /** 把一组字面词的所有出现记成 [起, 止) 片段。 */
    private static void collectWords(String value, String[] words, List<int[]> hits) {
        for (String word : words) {
            int from = value.indexOf(word);
            while (from >= 0) {
                hits.add(new int[]{from, from + word.length()});
                from = value.indexOf(word, from + word.length());
            }
        }
    }

    /**
     * 按起点排序（起点相同的留长的），再丢掉互相重叠的。
     *
     * <p>不合并的话，一个“9月15号”会被数成“9月15号”+“15号”两处，
     * 于是正常的“9月15号早上八点吃药”也会被判成“说了两个时间”。
     */
    private static List<int[]> independent(List<int[]> hits) {
        hits.sort(Comparator.comparingInt((int[] hit) -> hit[0]).thenComparingInt(hit -> -hit[1]));
        List<int[]> kept = new ArrayList<>();
        for (int[] hit : hits) {
            if (kept.isEmpty() || hit[0] >= kept.get(kept.size() - 1)[1]) kept.add(hit);
        }
        return kept;
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

    /**
     * 家属端专用：把话里对长辈的<b>称呼</b>摘掉（“提醒我<b>妈</b>带身份证”→ “带身份证”）。
     *
     * <p>家属说“提醒我妈…”时，“我妈”是他在叫谁，不是备忘内容——留着的话长辈看到的备忘是
     * “妈带身份证”，一句不像话的话，还会让他琢磨“这是谁说的”。{@link #detect} 认得“提醒我”这个指令，
     * 但“我妈”里的称呼不在它的词表里，所以残渣留在了正文开头。
     *
     * <p>只在<b>家属端</b>调用：老人不会管自己叫“我妈”。
     *
     * <p>三条底线：长词在前（“我妈妈”不能先被“我妈”切成个“妈”）；称呼后面紧跟的那个“的”一起摘
     * （“我妈的药没了”→“药没了”，而不是“的药没了”——那是“谁的”里的“的”，不是内容）；
     * 摘完什么都不剩就<b>原样返回</b>，正文被摘空的备忘比带个称呼难查得多。
     */
    public static String stripElderAddress(String text) {
        String raw = text == null ? "" : text.trim();
        if (raw.isEmpty()) return raw;
        String rest = raw;
        // 识别不出备忘句式时（如“提醒姥姥量血压”），调用方给的是原句，连指令词一起摘
        for (String leading : new String[]{"提醒一下", "提醒我", "提醒"}) {
            if (rest.startsWith(leading)) {
                rest = rest.substring(leading.length()).trim();
                break;
            }
        }
        for (String address : ELDER_ADDRESSES) {
            if (!rest.startsWith(address)) continue;
            String stripped = rest.substring(address.length());
            if (stripped.startsWith("的")) stripped = stripped.substring(1);
            rest = stripped.trim();
            break;
        }
        return rest.isEmpty() ? raw : rest;
    }

    /**
     * 家属称呼长辈的说法。<b>长的排在前面</b>：先命中“我妈妈”，才不会只摘掉一个“我妈”剩个“妈”；
     * “家里人”排在“家里”“家人”前面，否则“家里人量血压”会剩个“人量血压”。
     *
     * <p>刻意收窄到常见亲属称呼，不往“凡是称呼都摘”上走：摘错一刀就是把正经内容删了，
     * 而漏摘一个称呼最多是正文里多两个字，长辈还看得懂。
     */
    private static final String[] ELDER_ADDRESSES = {
            "我妈妈", "我爸爸", "我妈", "我爸", "老妈", "老爸", "妈妈", "爸爸",
            "奶奶", "爷爷", "姥姥", "姥爷", "外婆", "外公", "婆婆", "公公",
            "岳母", "岳父", "母亲", "父亲", "老伴", "爱人", "长辈", "家里人", "家人", "家里",
            "妈", "爸"
    };

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

    /**
     * 句中一个星期几的出处：在正文里的起止（拆条时要按这段换字）+ 星期几 + 生效的范围限定（这/本/下）+ 那天是不是已经过去了。
     */
    private record DayToken(int start, int end, int day, String scope, boolean past) { }

    /**
     * 扫出句中所有星期几，支持一串的说法。
     *
     * <p>“这周周一周二周三”“每周一三五”“下周一和周三”“这周六周日”都要能全认出来——
     * 原来只 find() 一次，第二个以后的星期几被静默丢掉，“每周一三五”（隔天吃药）会塌成每周一次。
     *
     * <p>范围限定会往后传：“这周六周日”里的“周日”也算这一周，不能因为“周日”自己没带“这”就按裸星期几算到下周日。
     */
    private static List<DayToken> dayTokens(String value, LocalDate today) {
        List<DayToken> tokens = new ArrayList<>();
        int todayDow = today.getDayOfWeek().getValue();
        // “这周周一周二周三”里的“这周”是范围前缀，后面那个“周一”自己没带限定词
        String scope = weekScope(value);
        Matcher head = WEEKDAY_HEAD.matcher(value);
        int cursor = 0;
        while (cursor <= value.length() && head.find(cursor)) {
            if (head.group(1) != null) scope = head.group(1);
            addDay(tokens, head.start(), head.end(), head.group(2).charAt(0), scope, todayDow);
            cursor = head.end();
            Matcher tail = WEEKDAY_TAIL.matcher(value);
            while (cursor <= value.length() && tail.find(cursor) && tail.start() == cursor) {
                addDay(tokens, tail.start(), tail.end(), tail.group(1).charAt(0), scope, todayDow);
                cursor = tail.end();
            }
        }
        return tokens;
    }

    private static void addDay(List<DayToken> tokens, int start, int end, char dayChar,
                               String scope, int todayDow) {
        int day = dayNumber(dayChar);
        if (day <= 0) return;
        // 只有“这/本周X”才可能是已经过去的那天；“下周X”“周一”指的都是将来
        boolean thisWeek = "这".equals(scope) || "本".equals(scope);
        tokens.add(new DayToken(start, end, day, scope, thisWeek && day < todayDow));
    }

    /** 句中说到的所有日子；空集 = 日期定不下来（只有“这周”这类范围词，或压根没提）。 */
    private static List<LocalDate> daysOf(String value, List<DayToken> tokens, LocalDate today) {
        LocalDate single = relativeOrAbsoluteDay(value, today);
        if (single != null) return List.of(single);
        return tokens.stream().map(token -> dateFor(token.day(), token.scope(), today))
                .distinct().sorted().toList();
    }

    /** 一组日期 × 一个钟点 = 每条备忘的到点时间；钟点没听懂返回空集。 */
    private static List<LocalDateTime> timesAt(List<LocalDate> days, LocalTime time) {
        if (time == null) return List.of();
        return days.stream().distinct().sorted().map(day -> LocalDateTime.of(day, time)).toList();
    }

    private static List<LocalDateTime> oneOrEmpty(LocalDateTime at) {
        return at == null ? List.of() : List.of(at);
    }

    /**
     * 句中的具体钟点；“早上/下午”这类只给了时段的说法折算成默认钟点（早上 8 点、中午 12 点…）。
     *
     * <p>这条路只在<b>补答</b>时走（{@link #resolveRemindAt}）：我们已经问过“具体几点”，他答的就是
     * “早上”，再问一遍就是死循环。首轮识别不走这里，见 {@link #clockTimeOfUnsaid}。
     */
    private static LocalTime clockTimeOf(String value) {
        return clockTimeOf(value, true);
    }

    /**
     * 首轮识别用的钟点：只有<b>说得出的钟点</b>才算，“早上/下午”这类时段词不算。
     *
     * <p>为什么不折算成默认值：老人说“早上要吃药”，那个“早上”是他的说法、不是我们的答案。
     * 折算成 8 点记下去，他得自己发现不对才改得回来；同一句话换成“明早要吃药”就会追问几点
     * （“明早”不是时段词表里的词），一个说法问、一个说法不问，是同一个意思两种对待。
     * 时段词也是 {@link #TIME_ANCHOR} 认的“提到时间”，所以这里不折算 → 走到 needsTime → 问具体几点。
     *
     * <p>多出来的那一步是值得的：早上的药是几点吃、饭前还是饭后吃，本来就只有他本人知道。
     */
    private static LocalTime clockTimeOfUnsaid(String value) {
        return clockTimeOf(value, false);
    }

    private static LocalTime clockTimeOf(String value, boolean guessPeriod) {
        LocalTime time = clockTime(value);
        if (time != null) return time;
        return guessPeriod ? periodFallbackTime(value) : null;
    }

    /**
     * 句中的“这周/本周/下周”（后面没跟星期几）；没有返回 null。
     * “这个星期三”不算——它后面跟着星期几，是确定的某一天，不是范围。
     */
    public static String bareWeekWord(String value) {
        String scope = weekScope(value);
        return scope == null ? null : scope + "周";
    }

    /**
     * 裸周词的范围限定字（“这/本/下”）；没有返回 null。
     *
     * <p>内部判定只用这一个字：{@link #bareWeekWord} 返回的是给人看的“这周”，
     * 拿它去比 {@code "这".equals(scope)} 永远不成立——“这周周一周二周三”在周五说就漏掉了
     * “这几天已经过去”，会被静默算到下周。
     */
    private static String weekScope(String value) {
        Matcher week = BARE_WEEK.matcher(value == null ? "" : value);
        return week.find() ? week.group(1) : null;
    }

    /**
     * 追问“哪一天”时给一个老人点了一定听得懂的日子。
     *
     * <p>句中有星期几就用它的下周版本（“这周三”→“下周三”）；只说“这周/下周”时给那一周的周日。
     * 原来句中没有星期几时返回裸“下周”，老人点了按钮照样解析不出日期，只会被回一句“没听清是哪一天”。
     */
    public static String weekDaySuggestion(String value, LocalDate today) {
        String raw = value == null ? "" : value;
        // 一句话说了好几天时，快捷回复要把整串带到下周（“这周周一周二周三”→“下周周一周二周三”）：
        // 只给“下周一”的话老人一点按钮，周二周三就没了
        List<DayToken> tokens = dayTokens(raw, today);
        if (tokens.size() > 1) {
            int[] span = weekdayRunSpan(raw, today);
            if (span != null) return nextWeekRun(raw.substring(span[0], span[1]));
        }
        if (WEEKDAY_HEAD.matcher(raw).find()) return nextWeekdayWord(raw);
        return "下周".equals(bareWeekWord(raw)) ? "下周日" : "这周日";
    }

    /**
     * 把一段星期串整个挪到下周：“这周周一周二周三”→“下周周一周二周三”，“周一三五”→“下周一三五”。
     *
     * <p>只换开头那个范围字，不能把“周”一起吃掉——“下周一”里的“周”是“周一”的一部分，
     * 按整词吃掉会变成“下一”。
     */
    private static String nextWeekRun(String run) {
        if (run.startsWith("每")) return "下" + run.substring(1);
        Matcher leading = WEEK_LEADING.matcher(run);
        return leading.find() ? "下" + run.substring(leading.end()) : "下" + run;
    }

    /**
     * 拆条时给某一条用的正文：把句中的星期串换成这一条自己的那个星期几
     * （“每周一三五早上八点吃药” → “每周三早上八点吃药”）。
     *
     * <p>不换的话，多天原句喂进 stripSchedule 会漏残渣：实测“每周一三五早上八点吃药”摘完是“三五吃药”，
     * “下周一和周三早上八点吃药”摘完是“和吃药”。换成单天句子后走的就是已被断言钉住的单天路径。
     * 句中本来没有星期串时原样返回。
     */
    public static String textForDay(String text, LocalDate day, LocalDate today) {
        String raw = text == null ? "" : text;
        int[] span = weekdayRunSpan(raw, today);
        if (span == null || day == null) return raw;
        return raw.substring(0, span[0]) + weekdayWordOf(day) + raw.substring(span[1]);
    }

    /** “周X”。 */
    private static String weekdayWordOf(LocalDate day) {
        return "周" + "一二三四五六日".charAt(day.getDayOfWeek().getValue() - 1);
    }

    /**
     * 句子里整串星期说法的起止：从范围前缀（“这周周一周二周三”里的“这周”）或第一个“周”字，
     * 一直连到最后那个星期几，中间只隔着顿号/和的那些也算同一串。没有星期说法返回 null。
     */
    private static int[] weekdayRunSpan(String raw, LocalDate today) {
        List<DayToken> tokens = dayTokens(raw, today);
        if (tokens.isEmpty()) return null;
        int start = tokens.get(0).start();
        int end = tokens.get(0).end();
        for (DayToken token : tokens.subList(1, tokens.size())) {
            if (!WEEKDAY_GAP.matcher(raw.substring(end, token.start())).matches()) break;
            end = token.end();
        }
        // 前面的“这周/下周”是范围前缀，要一起换掉，否则会拼出“这周这周六”这种话
        Matcher prefix = BARE_WEEK.matcher(raw);
        if (prefix.find() && prefix.end() <= start
                && WEEKDAY_GAP.matcher(raw.substring(prefix.end(), start)).matches()) {
            start = prefix.start();
        }
        return new int[]{start, end};
    }

    /**
     * 追问“是哪一天”时回显已经过去的那几天（“这周周一周二周三”在周五说就是这三天）；取不到返回空集。
     *
     * <p>回显的必须是老人说的过去那几天（本周的周一），不是 {@link #dateFor} 算出的下一次（下周一）——
     * 说“您说的下周一已经过去了”会让人完全摸不着头脑。
     */
    public static List<LocalDate> pastWeekdays(String value) {
        return pastWeekdays(value, LocalDate.now(DEMO_ZONE));
    }

    /** @param today 业务时区的“今天”：本周哪几天已经过去了要靠它算。 */
    public static List<LocalDate> pastWeekdays(String value, LocalDate today) {
        return dayTokens(normalizePeriodWords(value == null ? "" : value), today).stream()
                .filter(DayToken::past)
                .map(token -> today.minusDays((7 - (token.day() - today.getDayOfWeek().getValue() + 7) % 7) % 7L))
                .distinct().sorted().toList();
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
    public static List<LocalDateTime> resolveRemindAt(String memoText, String timeAnswer) {
        return resolveRemindAt(memoText, timeAnswer, List.of());
    }

    /**
     * @param knownDays 前面已经追问清楚的日期（如老人回答“下周三”）。原句里的“这周三”已经作废，
     *                  再拿它当日期会把时间算到错的那一天。一句话说几天就是几天。
     */
    public static List<LocalDateTime> resolveRemindAt(String memoText, String timeAnswer,
                                                      List<LocalDate> knownDays) {
        return resolveRemindAt(memoText, timeAnswer, knownDays, nowInDemoZone());
    }

    /** @param now 业务时区的“现在”：没给日期时按它取今天、已过点顺延到明天。 */
    public static List<LocalDateTime> resolveRemindAt(String memoText, String timeAnswer,
                                                      List<LocalDate> knownDays, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        String memo = normalizePeriodWords(memoText == null ? "" : memoText);
        String answer = normalizePeriodWords(timeAnswer == null ? "" : timeAnswer);
        LocalTime time = clockTimeOf(answer);
        // 老人只回答了“哪一天”（如“下周三”）时，钟点沿用正文里已经听懂的那个
        if (time == null) time = clockTimeOf(memo);
        if (time == null) return List.of();
        // 答句里的日期优先（“这个星期三”改成“下周三”时要听新的，不能还用原句那天）。
        // 答句也可能说了好几天（追问答“下周周一周二周三”），所以取的是整组不是第一个
        List<LocalDate> days = new ArrayList<>();
        List<LocalDate> answered = daysOf(answer, dayTokens(answer, today), today);
        if (!answered.isEmpty()) days.addAll(answered);
        else if (knownDays != null && !knownDays.isEmpty()) days.addAll(knownDays);
        else days.addAll(daysOf(memo, dayTokens(memo, today), today));
        if (!days.isEmpty()) return timesAt(days, time);
        LocalDateTime candidate = LocalDateTime.of(today, time);
        return List.of(candidate.isAfter(now) ? candidate : candidate.plusDays(1));
    }

    /**
     * 句子里说的重复规则（每天/每周X/每月X号）；没说返回 null。
     * 给“把这条提醒改成每周三下午三点”这类改期用：改一次也要能改成重复的。
     */
    public static String repeatRuleIn(String value) {
        return repeatRuleOf(normalizePeriodWords(value == null ? "" : value));
    }

    /**
     * 兜底的“现在”（业务时区）：<b>只给没有业务时钟的调用方用</b>。生产路径由
     * {@code FollowupAgentService} 传 {@code BusinessClock.now()} 进来，不再走这里——
     * 否则配置改了 {@code business.time.zone}，备忘推算还按老时区算。
     */
    public static LocalDateTime nowInDemoZone() {
        return LocalDateTime.now(DEMO_ZONE);
    }

    /**
     * 取出句中说到的所有日子（“下周三”/“9月16号”/“明天”/“下周周一周二周三”）；没给日期返回空集。
     *
     * <p>返回整组而不是一个：老人回答“下周周一周二周三”时只取第一个，周二周三会在问钟点的那一步被丢掉。
     */
    public static List<LocalDate> resolveDays(String value) {
        return resolveDays(value, LocalDate.now(DEMO_ZONE));
    }

    /** @param today 业务时区的“今天”：裸“周X”和“这周X”落在哪一天要靠它算。 */
    public static List<LocalDate> resolveDays(String value, LocalDate today) {
        String normalized = normalizePeriodWords(value == null ? "" : value);
        return daysOf(normalized, dayTokens(normalized, today), today);
    }

    /**
     * 老人回答“每周几/每月几号”后算出的重复锚点日期：本周/本月的这一天（已过则顺延一周/一月）。
     * 解析不出返回 null（说明还是没听清，得再问一次）。只认锚点词，不认“明天”这类相对日期。
     */
    public static LocalDate resolveRepeatAnchor(String repeatRule, String answer) {
        return resolveRepeatAnchor(repeatRule, answer, LocalDate.now(DEMO_ZONE));
    }

    /** @param today 业务时区的“今天”：锚点是本周/本月的这一天，已过则顺延一周/一月。 */
    public static LocalDate resolveRepeatAnchor(String repeatRule, String answer, LocalDate today) {
        String value = normalizePeriodWords(answer == null ? "" : answer);
        if ("MONTHLY".equals(repeatRule)) {
            Matcher day = DAY_ONLY.matcher(value);
            if (!day.find()) return null;
            int want = dateNumber(day.group(1));
            if (want < 1) return null;
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
        // “九月五号”“15号”这类绝对日期也要回显：只听懂到日期就回一句“当天”，
        // 老人会以为我们根本没听见他说了哪天（中文写法现在也认了，见 DAY_NUM）
        Matcher monthDay = MONTH_DAY.matcher(value);
        if (monthDay.find()) return monthDay.group(0);
        Matcher dayOnly = DAY_ONLY.matcher(value);
        if (dayOnly.find()) return dayOnly.group(0);
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

    /** @param guessPeriod 只有时段词（“早上”）时算不算 8 点：首轮识别不算（先问几点），补答才算。 */
    private static LocalDateTime remindAtOf(String value, LocalDateTime now, boolean guessPeriod) {
        // 相对时间提醒（演示常用）：如“2分钟后提醒我量血压”“一分钟之后要吃药”“半小时后吃药”
        // “一个半小时后”这类带“个半”的说法算不准，跳过交给追问，别猜错时间
        Matcher relative = value.contains("个半") ? null : RELATIVE.matcher(value);
        if (relative != null && relative.find()) {
            int ahead = relativeMinutes(relative.group(1), relative.group(2));
            if (ahead >= 1 && ahead <= 1440) return now.plusMinutes(ahead);
        }
        LocalTime time = clockTimeOf(value, guessPeriod);
        if (time == null) return null;
        LocalDate day = dayOf(value, now.toLocalDate());
        LocalDateTime candidate;
        if (day != null) {
            candidate = LocalDateTime.of(day, time);
            return candidate;
        }
        candidate = LocalDateTime.of(now.toLocalDate(), time);
        // 只给了时刻没给日期：已过点则顺延到明天（避免存一条过期的提醒）
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1);
        return candidate;
    }

    private static final Pattern MONTH_DAY = Pattern.compile("(" + DAY_NUM + ")\\s*月\\s*(" + DAY_NUM + ")" + DAY_SUFFIX_OR_NONE);
    private static final Pattern DAY_ONLY = Pattern.compile("(" + DAY_NUM + ")\\s*[号日]" + NOT_FREQUENCY);

    /** “几月几号”里的数字：阿拉伯数字直接转，中文数字（十五/二十三/三十一）走 chineseNumber；认不出返回 -1。 */
    private static int dateNumber(String token) {
        if (token == null || token.isEmpty()) return -1;
        return token.matches("[0-9]{1,2}") ? Integer.parseInt(token) : chineseNumber(token);
    }

    /** 按真实日历拼日期；月/日不合法（如 2月30号）返回 null，交给追问，不猜。 */
    private static LocalDate safeDate(int year, int month, int day) {
        if (month < 1 || month > 12 || day < 1 || day > 31) return null;
        if (day > YearMonth.of(year, month).lengthOfMonth()) return null;
        return LocalDate.of(year, month, day);
    }

    /** 相对日期/星期 → 目标日期。 */
    private static LocalDate dayOf(String value, LocalDate today) {
        LocalDate single = relativeOrAbsoluteDay(value, today);
        if (single != null) return single;
        List<DayToken> tokens = dayTokens(value, today);
        return tokens.isEmpty() ? null : dateFor(tokens.get(0).day(), tokens.get(0).scope(), today);
    }

    /**
     * 星期几落在哪一天。
     * “下X”=下周那一天；“这/本X”=这一周那一天（已经过去的仍是将来那次，由 DayToken.past() 负责追问）；
     * 裸“周X”=从今天往后推，正好是今天就推一周。
     */
    private static LocalDate dateFor(int target, String scope, LocalDate today) {
        if (target <= 0) return null;
        if ("下".equals(scope)) {
            int toMonday = (8 - today.getDayOfWeek().getValue()) % 7;
            if (toMonday == 0) toMonday = 7;
            return today.plusDays(toMonday + (target - 1));
        }
        int delta = (target - today.getDayOfWeek().getValue() + 7) % 7;
        if (delta == 0 && !"这".equals(scope) && !"本".equals(scope)) delta = 7;
        return today.plusDays(delta);
    }

    /** “大后天/后天/明天/今天/9月16号/15号”这类只指向一天的写法；没有返回 null。 */
    private static LocalDate relativeOrAbsoluteDay(String value, LocalDate today) {
        if (containsAny(value, "大后天")) return today.plusDays(3);
        if (value.contains("后天")) return today.plusDays(2);
        if (containsAny(value, "明天", "明早", "明晚", "明日")) return today.plusDays(1);
        if (containsAny(value, "今天", "今早", "今晚", "今日")) return today;
        // 绝对日期：“9月15号 / 15号 / 下个月5号”（只给号数：过了就顺延到下个月）
        Matcher monthDay = MONTH_DAY.matcher(value);
        if (monthDay.find()) {
            LocalDate target = safeDate(today.getYear(),
                    dateNumber(monthDay.group(1)), dateNumber(monthDay.group(2)));
            if (target == null) return null;
            return target.isBefore(today)
                    ? safeDate(today.getYear() + 1, target.getMonthValue(), target.getDayOfMonth())
                    : target;
        }
        Matcher dayOnly = DAY_ONLY.matcher(value);
        if (dayOnly.find()) {
            int day = dateNumber(dayOnly.group(1));
            LocalDate month = value.contains("下个月") ? today.plusMonths(1) : today;
            LocalDate target = safeDate(month.getYear(), month.getMonthValue(), day);
            if (target == null) return null;
            if (!target.isBefore(today)) return target;
            LocalDate next = today.plusMonths(1);
            return safeDate(next.getYear(), next.getMonthValue(), day);
        }
        return null;
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
            // “二十五点”这种说不通的钟点不猜：返回 null 让老人再说一遍，别静默记成 23 点。
            // 下界是 0 不是 1：“零点”是当天零点（0 与 24 都指向同一个时刻，见 periodHour）
            if (hour < 0 || hour > 24) return null;
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

    /** 时段词折算 12/24 小时制，并夹取合法范围；说不通的钟点（25点…）返回 null，交给追问。 */
    private static LocalTime periodHour(String period, int hour, int minute) {
        // 24 点是当天零点（“晚上二十四点”也是零点）。原来是往 23 点夹，整整差一小时还是静默的
        if (hour == 24) return LocalTime.MIDNIGHT;
        if (hour < 0 || hour > 24) return null;
        // 零点同理，但不随时段词走：“凌晨零点”“晚上零点”都是 00:00，没有“下午零点”这种东西。
        // 分钟要留下（“零点三十分”是 00:30），所以不能像 24 点那样整个换成 MIDNIGHT
        if (hour == 0) return LocalTime.of(0, Integer.max(0, Integer.min(59, minute)));
        if (period != null) {
            if (("下午".equals(period) || "晚上".equals(period) || "傍晚".equals(period) || "夜里".equals(period))
                    && hour < 12) hour += 12;
            if ("凌晨".equals(period) && hour == 12) hour = 0;
        }
        hour = Integer.max(0, Integer.min(23, hour));
        minute = Integer.max(0, Integer.min(59, minute));
        return LocalTime.of(hour, minute);
    }

    /** 中文数字小时词 → 1..24；识别不了返回 -1。 */
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
            case '零' -> 0;
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
