package com.team.silveragent.application.health;

import com.team.silveragent.application.ChineseNumbers;
import com.team.silveragent.application.memo.MemoParser;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 老人上报实测数值的识别（规则版，与 {@link com.team.silveragent.application.memo.MemoParser} 同一思路）。
 *
 * 与备忘的分工：备忘是“要做的事”（明早八点吃药），这里是“已经量到的数”（我的血压是100）。
 * 所以命中要求「项目词 + 数值或说法」两件都齐 —— 只说“提醒我量血压”是备忘，不是记录。
 */
public final class HealthRecordParser {
    private HealthRecordParser() { }

    /** RECORD=记一条实测值；QUERY=回查最近几条；UNDO=老人发现记错了，删掉最近那一条。 */
    public enum Kind { RECORD, QUERY, UNDO }

    /**
     * 数值本身有没有毛病。
     * IMPOSSIBLE=人不可能量出这个数（血压 800、体温 60），多半是听错或看错；
     * SWAPPED=两个数写反了（血压 60/120）；
     * UNIT=数没问题，是**单位**没交代（“我体重190”：190 斤和 190 公斤都说得通）；
     * NO_VALUE=项目认得出，可整句话里**一个数都没有**，只有个说法（“我血压有点高”）；
     * null=正常。
     * 有毛病不丢数：照常带出来，由助手反问老人是重测还是照记——静默丢弃会让老人
     * 报了个数却什么都没发生（原来还会顺着链路问“去哪家医院”）。
     */
    public enum Issue { IMPOSSIBLE, SWAPPED, UNIT, NO_VALUE }

    /**
     * @param kind      RECORD=记一条实测值；QUERY=回查最近几条。
     * @param item      “血压/血糖/心率/体温/体重/血氧”，认不出项目时归到 {@link #OTHER}；
     *                  QUERY 且没点项目时为 null（查全部）。
     * @param valueNum  能取到数时的数（血压 100/60 取 100）；只有说法时为 null。
     * @param valueText 给人看的原值：“100/60”“低压95”或“有点高”。
     * @param unit      单位：mmHg / mmol/L / 次每分 / °C / kg / %；只有说法时为 null
     *                  （“有点高”是多少 mmHg 谁也不知道，挂一个上去就是编出来的）。
     * @param issue     数值有毛病时要先反问老人；正常为 null。
     */
    public record RecordIntent(Kind kind, String item, BigDecimal valueNum, String valueText, String unit, Issue issue) {
        /** 这条数值有毛病、得先问老人一句再决定记不记。 */
        public boolean needsConfirm() { return issue != null; }
    }

    /**
     * 认不出项目时归到这一项：整个“其他”只有一个标签，桶里的内容就是老人的原话。
     *
     * <p>为什么要它：老人说“我的尿酸是420”“我今天走了5000步”，词表里都没有，原来一律不记——
     * 而助手嘴上还说“我记下了”，等于骗人。宁可按原话记下来（原话存在 value_text），
     * 也不静默丢掉：回看时老人看到的就是自己说过的那句话，记错了当场能发现。
     */
    public static final String OTHER = "其他";

    /** value_text 列是 VARCHAR(60)：整句原话直接当值存，超了截断，别让插库失败。 */
    private static final int MAX_TEXT = 60;

    /**
     * 撤销刚才那条：“记错了”“删掉刚才那条”。
     *
     * <p>只在说得够明确时才认，不用光秃秃一个“删掉/删除”：那是备忘的删除用词
     * （“把吃药的提醒删掉”），抢过来会把老人的提醒删了。带“备忘/提醒”的句子一律不认。
     */
    private static final String[] UNDO_WORDS = {
            "记错了", "记错啦", "记的不对", "记的不准",
            "删掉刚才", "删除刚才", "删了刚才", "刚才那条删", "把刚才那条删",
            "撤销刚才", "刚才的删", "删掉最后一条", "最后一条删", "上一条删"
    };

    /**
     * 项目词 → 标准项目名 + 单位。长词在前（“收缩压”先于“压”）。
     *
     * <p>高压/收缩压/低压/舒张压都归到血压：血压本来就是**一对数**（“130/85”），
     * 只说了一半（“我低压90”）也是一次血压读数，不该另立一项。立成一项的代价实测过：
     * 那个 90 在汇总里跟收缩压挤进同一个平均，家属收到“血压 2 次，平均 110/85”——
     * 110 谁也没量到过。发给家属的话里出现一个不存在的读数，比少说一句糟得多。
     *
     * <p>只说了一半时把“低压”两个字写在值里（见 {@link #DIASTOLIC_MARK}），
     * 不然回看时一个光数 90，认不出是上面那个数还是下面那个数。
     */
    private static final Map<String, String[]> ITEMS = new LinkedHashMap<>();
    static {
        ITEMS.put("收缩压", new String[]{"血压", "mmHg"});
        ITEMS.put("血压", new String[]{"血压", "mmHg"});
        ITEMS.put("高压", new String[]{"血压", "mmHg"});
        ITEMS.put("舒张压", new String[]{"血压", "mmHg"});
        ITEMS.put("低压", new String[]{"血压", "mmHg"});
        // 老人更常说“上压/下压”（“我上压一百五”）。下压是舒张压那一半，和低压同一处理
        ITEMS.put("上压", new String[]{"血压", "mmHg"});
        ITEMS.put("下压", new String[]{"血压", "mmHg"});
        ITEMS.put("血糖", new String[]{"血糖", "mmol/L"});
        ITEMS.put("心率", new String[]{"心率", "次每分"});
        ITEMS.put("脉搏", new String[]{"心率", "次每分"});
        ITEMS.put("体温", new String[]{"体温", "°C"});
        ITEMS.put("体重", new String[]{"体重", "kg"});
        // 身高：老人量身体的数据里最常报的一项之一，和体重一样是“量出来的数”。
        // 原来表里没有它，于是“我的身高是180”整句落进“其他”，回看时是一条谁也筛不出来的原话。
        ITEMS.put("身高", new String[]{"身高", "cm"});
        ITEMS.put("血氧", new String[]{"血氧", "%"});
    }

    /**
     * 老人自己说出来的单位词 → 库里存的单位标签。
     *
     * <p>为什么单列：{@link #NUMBER_UNITS} 里的量词是为了挡“买了2斤苹果”“住3楼”那种数量，
     * 数后面跟量词的一律不算测量值——可“我体重190斤”里的斤是**单位**，不是买菜的斤两。
     * 项目词已经点明是体重时，紧跟其后的斤只可能是单位，所以先让这张表认走。
     *
     * <p>“公斤/千克”本来就在 {@link #NUMBER_UNITS} 外头、能被当成普通数值取到，登记它们是为了
     * 另一件事：单位**说清楚了**就不该再问“是斤还是公斤”（见 {@link #weightUnitUnknown}）。
     *
     * <p>**按老人说的原话存**（190 斤就是 190 斤），不替他折算成公斤——库里替他换个数，
     * 回读念的就不再是他说的那句了。要算术的地方（发给家属的汇总）自己折算。
     */
    private static final Map<String, Map<String, String>> SPOKEN_UNITS = Map.of(
            "体重", Map.of("斤", "斤", "公斤", "kg", "千克", "kg"));

    /**
     * 没带单位的体重到这个数以上，斤和公斤都说得通，得问一句。
     *
     * <p>为什么拿 100 划线：成年人 100 公斤以上少见但真有（“我体重105”是公斤），
     * 100 斤=50 公斤对老人又太平常（“我体重190”多半是斤）。两边都讲得通，就不替老人挑。
     * 100 以下不用问：60 公斤正常，60 斤=30 公斤不像一个能自己说话的老人。
     *
     * <p>说了斤的走 {@link #SPOKEN_UNITS}，问都不问；这条只管“没说单位”的。
     */
    private static final BigDecimal WEIGHT_UNIT_ASK_FROM = BigDecimal.valueOf(100);

    /** 项目词表（“血压”“血糖”…）。给“把血压发给家属”那边认项目复用，免得两份词表各走各的。 */
    public static java.util.Set<String> itemWords() { return ITEMS.keySet(); }

    /**
     * 页面上让老人点的那排项目（顺序和记录页一致）。别名都归到标准名：
     * “上压/高压/收缩压”对老人来说都是同一个“血压”，不该在按钮里出现五次。
     */
    private static final java.util.List<String> ITEM_CHOICES =
            java.util.List.of("血压", "血糖", "心率", "体温", "体重", "身高", "血氧", OTHER);

    public static java.util.List<String> itemChoices() { return ITEM_CHOICES; }

    /**
     * 老人回答“这是哪一项”时，把他的话对到标准项目上（“就血压”“上压”都算血压）；
     * 对不上返回 null——那说明他其实在说别的，别硬塞进这个提问里。
     */
    public static String matchItem(String answer) {
        String value = answer == null ? "" : answer.trim();
        if (value.isEmpty()) return null;
        for (String word : ITEMS.keySet()) {          // 长词在前：先撞“收缩压”再撞“血压”
            if (value.contains(word)) return canonicalItem(word);
        }
        return value.contains(OTHER) ? OTHER : null;
    }

    /** 项目词 → 标准项目名：“高压”“收缩压”“低压”“舒张压”都归到“血压”。认不出原样返回。 */
    public static String canonicalItem(String word) {
        String[] entry = ITEMS.get(word);
        return entry == null ? word : entry[0];
    }

    /**
     * 只报了低压时写在值前面的两个字（“低压95”）。
     *
     * <p>库里只有 item 一列认项目，而“我低压90”和“我血压90”都记在血压名下：
     * 光看一个 90 认不出是哪一半。挂上这两个字，回读、记录页、汇总里都还认得出，
     * 而且它本来就是老人自己的说法。
     */
    public static final String DIASTOLIC_MARK = "低压";

    /** 这条记录值是不是“只说了一半”的低压（“低压95”）。 */
    public static boolean isDiastolicOnly(String valueText) {
        return valueText != null && valueText.startsWith(DIASTOLIC_MARK);
    }

    /**
     * 没有数值时的说法（“我血压有点高”）：也算一条记录，只是没有数。
     *
     * <p>带着 {@link Issue#NO_VALUE} 出去：助手先问一句“量出来是多少”。老人答得上就记个实数，
     * 答不上来再按他说的这句记（见 FollowupAgentService 里那一问）。
     */
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
    /**
     * 口语里两个数分开说、不写斜杠的血压（“我血压 135 85”“我血压135和85”）。
     *
     * <p>中间必须有个东西隔着（空格、和、跟、顿号）：“13585”那样连写的是另一个数，
     * 不是一对——真连写的话，{@link #NUMBER} 那边会把它整个读成一个离谱的数、再问老人一句。
     */
    private static final Pattern PAIR_SPOKEN = Pattern.compile(
            "([0-9]{1,3}(?:\\.[0-9]{1,2})?)\\s*(?:和|跟|、|,|，|\\s)\\s*([0-9]{1,3}(?:\\.[0-9]{1,2})?)(?![0-9])");
    /**
     * 项目词后面的那个数。
     *
     * <p>前后都不许再挨着数字：原来上头限 3 位，“我的血压是1350”被截成 **135** 记了下去——
     * 老人报的数、库里存的数、回话里念的数三个不一样，而且没有任何人发现（135 mmHg 看着挺正常）。
     * 现在整段读进来，1350 落在“人不可能量出来”那条边界外（血压 50~300），助手会先反问一句。
     */
    private static final Pattern NUMBER =
            Pattern.compile("(?<![0-9])([0-9]{1,4}(?:\\.[0-9]{1,2})?)(?![0-9])");
    /**
     * “其他”兜底专用的数值：比 {@link #NUMBER} 宽，因为 3 位上限是给“血压/血糖”那几项定的
     * （再高就是听错了），而桶里的东西本来就没有上限——“我今天走了5000步”是老人真会记的一条，
     * 用 3 位的那个正则只能取到 500，记下去的还是个错的数。
     *
     * <p>前后都不许再跟数字：手机号、身份证号那种长数字串整段跳过，不会从中间截出一截当数值。
     */
    private static final Pattern OTHER_NUMBER =
            Pattern.compile("(?<![0-9])([0-9]{1,6}(?:\\.[0-9]{1,2})?)(?![0-9])");
    /**
     * 数字后面跟这些字就是钟点/日期/次数/数量，不是测出来的值（“8点量血压”不能记成血压 8）。
     *
     * <p>后半段的量词是给“其他”兜底用的：认不出项目时唯一的判据就是“句子里有个数”，
     * 而“买了2斤苹果”“住3楼”“考了第3名”里也都有数。它们跟在数后面，一并排掉。
     * 注意“步”不在这里——“走了5000步”是老人真会想记的一条。
     */
    private static final String NUMBER_UNITS =
            "点分号月日年时岁周天次片粒杯袋盒颗支滴斤两克升个瓶只块元米层楼名台件本张回趟遍轮顿";
    /** 项目词往后看多少字：够覆盖“我的血压是100”“血压，100/60”“量了血压结果95”。 */
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
        IMPOSSIBLE.put("身高", new BigDecimal[]{BigDecimal.valueOf(30), BigDecimal.valueOf(250)});
        IMPOSSIBLE.put("血氧", new BigDecimal[]{BigDecimal.valueOf(50), BigDecimal.valueOf(100)});
    }

    /** 血压第二个数（舒张压）的边界：比第一个数宽松。 */
    private static final BigDecimal[] DIASTOLIC_RANGE =
            new BigDecimal[]{BigDecimal.valueOf(20), BigDecimal.valueOf(200)};

    /**
     * 哪个说法报出来的数按哪条边界判。
     *
     * <p>“我低压90”记在血压名下，但判它离不离谱要按舒张压那条 20~200，
     * 不套收缩压的 50~300：舒张压 40 是低，可血压计能量出来，按收缩压那条判就白问老人一句。
     */
    private static final Map<String, BigDecimal[]> WORD_RANGE = Map.of(
            "低压", DIASTOLIC_RANGE,
            "舒张压", DIASTOLIC_RANGE,
            "下压", DIASTOLIC_RANGE);

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

    /**
     * 识别不出返回 null，交回原链路。一句话里报了几项时取最先说的那一项（见 {@link #detectAll}）。
     *
     * <p>留给“问一句这是哪一项”“老人重说一个数”这类只针对一项的场景用。
     */
    public static RecordIntent detect(String message) {
        List<RecordIntent> records = detectAll(message);
        return records.isEmpty() ? null : records.get(0);
    }

    /**
     * 一句话里报了几项就返回几条：“我的身高是180 体重是190”是两条，不是一条。
     *
     * <p>为什么不是只记第一条：老人一口气把身高体重都说了，只落一条、另一条一声不响地丢掉，
     * 他还以为都记上了。真要紧的时候（“我体重190，血糖也高”）少的那条就是最要紧的那条。
     *
     * <p>每一项只在自己那一段里取数——从这个项目词往后，到下一个项目词为止。
     * 原来取的是“项目词前后各一段”（前 8 后 14 个字），那段会越过隔壁的项目词：
     * 实测“我的身高是180 体重是190”记成了**体重 180**（180 是身高的数，被体重抢走），
     * “我的体温是36.5，心率80”记成了**心率 36.5**，两个错数都是悄悄进库的。
     *
     * <p>回查（“我最近血压多少”）和撤销仍是单条：那两件事本来就只冲着一项去。
     */
    public static List<RecordIntent> detectAll(String message) {
        String raw = message == null ? "" : message.trim();
        if (raw.isEmpty()) return List.of();
        // 数值解析统一在“中文数字已改写”的句子上做：老人读出来的“一百五”“九十八”要当数，
        // 而“一三五”“三楼”这种不带数量级的连读一个字都不会被动（见 ChineseNumbers）。
        // 存进库里的“其他”条仍用老人原话 raw。
        String text = ChineseNumbers.normalize(raw);

        // 撤销排在最前：“我刚才血压记错了”是冲上一条记录去的，不是要再记一条
        if (!containsAny(raw, "备忘", "提醒") && containsAny(raw, UNDO_WORDS)) {
            return List.of(new RecordIntent(Kind.UNDO, null, null, null, null, null));
        }

        List<Occurrence> found = occurrences(text);
        // 没点具体项目，但说了“健康记录/我最近都量了什么”：查全部
        if (found.isEmpty()) {
            if (containsAny(raw, QUERY_ALL_WORDS)) {
                return List.of(new RecordIntent(Kind.QUERY, null, null, null, null, null));
            }
            RecordIntent other = otherItem(raw, text);
            return other == null ? List.of() : List.of(other);
        }
        // 回查先于咨询判定： “血压怎么样”是回查，“血压有点高怎么办”才是咨询（前者含“怎么”）
        if (containsAny(raw, QUERY_WORDS)) {
            return List.of(new RecordIntent(Kind.QUERY, found.get(0).item(), null, null, found.get(0).unit(), null));
        }
        if (containsAny(raw, ADVICE_WORDS)) return List.of();

        List<RecordIntent> records = new ArrayList<>();
        List<Occurrence> read = new ArrayList<>();
        for (int index = 0; index < found.size(); index++) {
            Occurrence occurrence = found.get(index);
            // 这一项取数的范围：从这个项目词往后，到下一个项目词或 14 个字为止（哪个先到算哪个）。
            // 往后取是这个顺序在说话——“血压130”是先说项目再说数；往回取才够得着别人的数。
            int until = occurrence.end() + LOOK_AFTER;
            if (index + 1 < found.size()) until = Integer.min(until, found.get(index + 1).start());
            RecordIntent record = readValue(text, Integer.min(until, text.length()), occurrence);
            if (record != null) {
                records.add(record);
                read.add(occurrence);
            }
        }
        return mergeBloodPressureHalves(read, records);
    }

    /**
     * “我高压140 低压90”是**一次**血压读数，不是两次。
     *
     * <p>两半分开记的代价实测过：记录页上两条，发给家属的汇总里两个数各进一个平均
     * （“血压 2 次，平均 110/85”——110 谁也没量到过）。老人说的本来就是一次测量：
     * 先报收缩压、再报舒张压。
     *
     * <p>合的时机很窄：一句话里正好两半、一个收缩压半边一个舒张压半边、两个数都取得出来、
     * 也都正常。只说了一半（“我低压95”）照旧记那一半，值里那个“低压”两个字不动；
     * 有一个数离谱（“我高压140 低压400”）也不合——那时要先问老人，不是替他拼一个数出来。
     */
    private static List<RecordIntent> mergeBloodPressureHalves(List<Occurrence> found, List<RecordIntent> records) {
        if (records.size() != 2) return records;
        Occurrence first = found.get(0);
        Occurrence second = found.get(1);
        boolean halves = isDiastolicWord(first.word()) != isDiastolicWord(second.word());
        if (!halves || !"血压".equals(first.item()) || !"血压".equals(second.item())) return records;
        RecordIntent left = records.get(0);
        RecordIntent right = records.get(1);
        if (left.issue() != null || right.issue() != null) return records;
        if (left.valueNum() == null || right.valueNum() == null) return records;
        RecordIntent systolic = isDiastolicWord(first.word()) ? right : left;
        RecordIntent diastolic = isDiastolicWord(first.word()) ? left : right;
        BigDecimal first1 = systolic.valueNum();
        BigDecimal second1 = diastolic.valueNum();
        String pairText = first1.stripTrailingZeros().toPlainString() + "/" + second1.stripTrailingZeros().toPlainString();
        // 两个数都在范围内、但顺序反了（“血压90 低压140”）：照记会得到一条不可能的血压，先问老人
        Issue issue = first1.compareTo(second1) < 0 ? Issue.SWAPPED : null;
        return List.of(new RecordIntent(Kind.RECORD, "血压", first1, pairText, "mmHg", issue));
    }

    private static boolean isDiastolicWord(String word) {
        return WORD_RANGE.containsKey(word);
    }

    /**
     * 这句话是不是“只报了一个读数”（“我血糖有点高”“我的血糖是7.2”）。
     *
     * <p>认出来的意图得**全是** {@link Kind#RECORD}：回查（“我最近血糖多少”）是 QUERY，
     * “我血糖高怎么办”这类咨询在 {@link #ADVICE_WORDS} 那里就返回空——这两类都不是在报读数，
     * 不能因为句子里有项目词就放行（见 SafetyGuard 里那道读数句放行口）。
     */
    public static boolean isReadingOnly(String message) {
        List<RecordIntent> intents = detectAll(message);
        return !intents.isEmpty() && intents.stream().allMatch(intent -> intent.kind() == Kind.RECORD);
    }

    /**
     * 句子里出现过的项目词，按**在句子里出现的先后**排（不是按词表的先后）。
     *
     * <p>这个顺序是实测出来的：原来按词表顺序找，谁排在词表前面谁先被认走，
     * 于是“我的体温是36.5，心率80”里心率（词表里靠前）抢到了体温的那个数。
     * 同一个位置上有多个词对得上时取最长的（“收缩压”优先于“血压”）。
     */
    private static List<Occurrence> occurrences(String text) {
        List<Occurrence> found = new ArrayList<>();
        int at = 0;
        while (at < text.length()) {
            String hit = null;
            for (String word : ITEMS.keySet()) {
                if (!text.startsWith(word, at)) continue;
                if (hit == null || word.length() > hit.length()) hit = word;
            }
            if (hit == null) {
                at++;
                continue;
            }
            String[] entry = ITEMS.get(hit);
            found.add(new Occurrence(at, at + hit.length(), hit, entry[0], entry[1]));
            at += hit.length();
        }
        return found;
    }

    /** 句子里出现的一个项目词：在哪儿、是哪个说法、归到哪一项、默认什么单位。 */
    private record Occurrence(int start, int end, String word, String item, String unit) { }

    /**
     * 从项目词后面那段话里取出这一项的数；取不到返回 null。
     *
     * <p>整段贴着这个项目词，隔壁项目的数不在这段里——这是修掉“体重抢身高的 180”之后
     * 立下的前提，任何往回看的窗口都会把它重新捅破。
     *
     * @param until 这段到全句的第几个字为止（下一个项目词的开头，或项目词后 14 个字）
     */
    private static RecordIntent readValue(String text, int until, Occurrence occurrence) {
        String item = occurrence.item();
        String unit = occurrence.unit();
        String word = occurrence.word();
        String window = text.substring(occurrence.end(), until);
        Matcher pair = PAIR.matcher(window);
        boolean paired = pair.find();
        // 写没写斜杠都是同一对数（“135/85”和“135 85”）；只有血压有第二个数可配
        if (!paired && "血压".equals(item)) {
            pair = PAIR_SPOKEN.matcher(window);
            paired = pair.find();
        }
        if (paired) {
            BigDecimal first = new BigDecimal(pair.group(1));
            BigDecimal second = new BigDecimal(pair.group(2));
            String pairText = pair.group(1) + "/" + pair.group(2);
            // 两个数都要查：原来只看前一个，“血压 120/800”照样入库
            if (!withinRange(first, rangeOf(item)) || !withinRange(second, pairRangeOf(item))) {
                return new RecordIntent(Kind.RECORD, item, first, pairText, unit, Issue.IMPOSSIBLE);
            }
            // “血压 60/120”：两个数都在范围内，但顺序反了，照记会得到一条不可能的血压
            if ("血压".equals(item) && first.compareTo(second) < 0) {
                return new RecordIntent(Kind.RECORD, item, first, pairText, unit, Issue.SWAPPED);
            }
            return new RecordIntent(Kind.RECORD, item, first, pairText, unit, null);
        }
        // 一对数里两个数都要有；只说了一半时，值带上“低压”两个字（“低压95”），
        // 边界也按那一半算（舒张压 20~200），别拿收缩压那条 50~300 去套
        boolean diastolicOnly = WORD_RANGE.containsKey(word);
        BigDecimal[] range = diastolicOnly ? WORD_RANGE.get(word) : rangeOf(item);
        Matcher number = NUMBER.matcher(window);
        BigDecimal stray = null;
        String strayText = null;
        while (number.find()) {
            int after = occurrence.end() + number.end();
            String spoken = spokenUnit(item, text, after);
            // 数后面跟量词的（“8点”“3楼”“2斤苹果”）不是测量值；但项目已点明是体重时，
            // 紧跟着的“斤”是单位不是量词，上面那步已经认走了
            if (spoken == null && after < text.length() && NUMBER_UNITS.indexOf(text.charAt(after)) >= 0) continue;
            BigDecimal value = new BigDecimal(number.group(1));
            if (!withinRange(value, range)) {
                // 离谱值不再静默跳过（老人报了数却石沉大海），留着让助手反问一句
                if (stray == null) {
                    stray = value;
                    strayText = valueText(number.group(1), diastolicOnly);
                }
                continue;
            }
            String valueText = valueText(number.group(1), diastolicOnly);
            // 体重没带单位、斤和公斤都说得通（“我体重190”）：不替老人挑一个记下去，先问一句
            if (spoken == null && weightUnitUnknown(item, value)) {
                return new RecordIntent(Kind.RECORD, item, value, valueText, null, Issue.UNIT);
            }
            return new RecordIntent(Kind.RECORD, item, value, valueText, spoken == null ? unit : spoken, null);
        }
        if (stray != null) return new RecordIntent(Kind.RECORD, item, stray, strayText, unit, Issue.IMPOSSIBLE);
        for (String spoken : VALUE_WORDS) {
            // 没有数：单位也不跟着进来（原来这条挂着 mmHg，“血压 有点高”在记录页上就成了
            // “有点高 mmHg”，那个单位是替老人编的）
            if (window.contains(spoken)) return new RecordIntent(Kind.RECORD, item, null, spoken, null, Issue.NO_VALUE);
        }
        return null;
    }

    /** 数后面紧跟着的那个单位词（“190斤”的斤）换成的单位标签；不是单位词就返回 null。 */
    private static String spokenUnit(String item, String text, int at) {
        Map<String, String> units = SPOKEN_UNITS.get(item);
        if (units == null || at >= text.length()) return null;
        for (Map.Entry<String, String> entry : units.entrySet()) {
            if (text.startsWith(entry.getKey(), at)) return entry.getValue();
        }
        return null;
    }

    /** 体重没带单位、斤和公斤都说得通：这个数得先问清是斤还是公斤。 */
    private static boolean weightUnitUnknown(String item, BigDecimal value) {
        return "体重".equals(item) && value.compareTo(WEIGHT_UNIT_ASK_FROM) >= 0;
    }

    /** 值写成什么样：只说了一半的舒张压挂上“低压”两个字（“低压95”），别的一律就是那个数。 */
    private static String valueText(String number, boolean diastolicOnly) {
        return diastolicOnly ? DIASTOLIC_MARK + number : number;
    }

    /**
     * 老人自己说要记的说法（“帮我记一下…”）。**不是**一条独立的证据，是另一条证据的附件：
     * 单靠它只会把“帮我记一下我的锁屏密码是09”抬成一条健康记录（见 {@link #onlyTheNumber}）。
     *
     * <p>真正管用的那一份是“托付词后面只剩一个数”——那时它才等于“这是个读数”。
     * 和备忘那套托付词有重叠：句子里带时间或健康事项时备忘先跑（“帮我记一下明早八点吃药”），
     * 这里管的是“说清了要记、但没说清是哪一项”的那半截。
     */
    private static final String[] OTHER_EXPLICIT = {
            "帮我记", "给我记", "记一下", "记下来", "记上", "记住", "我要记", "帮我存"
    };

    /** 证据之二：数值旁边带着单位（“7.8 mmol/L”“98%”）。 */
    private static final String[] OTHER_UNITS = {
            "mmhg", "mmol", "mg/dl", "克每升", "毫米汞柱", "次每分", "次/分", "公斤", "kg", "度", "%"
    };

    /** 证据之三：数值旁边是具体的指标说法，只是词表里没立成项目（尿酸、血脂、步数…）。 */
    private static final String[] OTHER_HINTS = {
            "尿酸", "血脂", "胆固醇", "甘油三酯", "肌酐", "糖化", "血红蛋白", "白细胞", "红细胞",
            "血小板", "尿蛋白", "尿糖", "腰围", "视力", "步", "指标", "数值"
    };

    /**
     * 只说“我量了/我测出来”却没点出是哪一项（“我今天量了，是135”“我测出来是7.8”）。
     *
     * <p>这类句子不直接入库（项目是猜的），也不丢（老人明摆着在报一次测量）：
     * 由助手问一句“这是哪一项”，老人点一下按钮就记上了。见 {@link #unknownMeasurement}。
     */
    private static final String[] MEASURE_ACTIONS = {
            "量了", "量的是", "量出来", "测了", "测的是", "测出来", "读数", "数值是", "结果是"
    };

    /** 数值前后各看几个字，判断它旁边有没有“这是量出来的”线索。 */
    private static final int OTHER_LOOK = 6;

    /**
     * 词表之外的说法：归到 {@link #OTHER}，照记原话。
     *
     * <p>门槛是**两条一起**：句子里有一个数值，并且这个数旁边有“这是量出来的”线索——
     * 有单位、有指标词，或者这句话是在报一个光秃秃的数（“帮我记一下，7.8”）。
     * 原来只有前一条（“句子里有个数”），于是“我的复诊诊室是908”“我住908房间”也被记成了一条
     * 健康记录，再跟着“发给女儿”发出去。**错的记录会出门，漏的记录老人重说一句就行**，
     * 所以这里宁可收窄，也不拿“什么都记”当兜底。
     *
     * <p>托付词（“帮我记一下…”）**不能单独算证据**：它是“我要记一件事”的意思，
     * 不是“这是个读数”的意思。原来带上它就能入库，于是“帮我记一下我的锁屏密码是09”
     * 被记成一条“其他”的健康记录——而老人说的明明是件要记下的事。
     * 判据是托付词后面还剩不剩下话：只剩一个数（“帮我记一下，7.8”）才是报读数；
     * 后面还挂着一句话（“我的锁屏密码是09”）就交给备忘那条路。
     * 这一条也是“有的时候会记错”的根源：模型把那句话判成健康数值，就进去了；
     * 判成备忘，就落在备忘录里。同一句话两个去处，不能再让它由模型的这一念决定。
     *
     * <p>认不出的两种去向分得很清：有测量动作但没项目（“我量了，是135”）交给助手问一句
     * （{@link #unknownMeasurement}）；连动作都没有的（“我住908房间”）不记，
     * 回那句“这句话我还没听准”。
     *
     * <p>这里**不查数值范围**。其他项没有“人不可能量出这个数”的边界（尿酸该多少？
     * 步数根本没有上限），硬套一组数只会把“走了5000步”这种正常说法挡在外面；
     * 而且判断这个数离谱与否本来也需要知道它是什么项目。宁可照记原话，
     * 让老人自己在回读里发现记错了——他还有“记错了”可以撤销。
     *
     * <p>存的是**整句原话**，不试着从里面抠出“项目词 + 数值”：抠错了就是又一轮静默失真，
     * 而这套兜底的全部意义就是不再丢老人说过的话。长度按 value_text 列的上限截。
     * 数值按改写过中文数字的句子取（“走了五千步”也要能认出 5000），存的原话照旧。
     */
    private static RecordIntent otherItem(String raw, String text) {
        // 咨询口气（“我尿酸高怎么办”）不是上报数值，交回“不能诊断”引导
        if (containsAny(text, ADVICE_WORDS)) return null;
        String stored = storedText(raw);
        boolean explicit = containsAny(raw, OTHER_EXPLICIT);
        Matcher number = OTHER_NUMBER.matcher(text);
        while (number.find()) {
            int after = number.end();
            char next = after < text.length() ? text.charAt(after) : ' ';
            if (NUMBER_UNITS.indexOf(next) >= 0) continue;
            boolean bareNumber = explicit && onlyTheNumber(text);
            if (!bareNumber && !looksMeasured(text, number.start(), number.end())) continue;
            return new RecordIntent(Kind.RECORD, OTHER, new BigDecimal(number.group(1)), stored, "", null);
        }
        return null;
    }

    /**
     * 托付词后面是不是只剩一个数（“帮我记一下，7.8”）。
     *
     * <p>问的是：这句话说完要记的那件事，是不是就是那个数本身。是 → 老人在报一个读数；
     * 后面还挂着一句话（“我的锁屏密码是09”“我的复诊诊室是908”）→ 他要记的是一件事，
     * 交给备忘那条路。
     *
     * <p>认法是“命令词 + 一个数 = 整句”，不是“把命令词抠掉看剩下什么”：
     * {@link #OTHER_EXPLICIT} 里的词互相咬着（“帮我记一下”里既有“帮我记”又有“记一下”），
     * 抠掉一个会剩下半截“一下，7.8”，那个半截跟真内容长得一样，判不出真假。
     * 命令词后面允许跟一小截补语（“一下”“一条”）和标点——那是命令自己的尾巴，不是内容。
     */
    private static final Pattern BARE_NUMBER_AFTER_ORDER = Pattern.compile(
            "(?:帮我记|给我记|记一下|记下来|记上|记住|我要记|帮我存)"
                    + "\\s*(?:一下|一个|一条|一条儿|这句|这条|这句话)?\\s*"
                    + "[，,。.、：:；;]?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*[。.！!？?~…呗吧啊呀哦]*$");

    private static boolean onlyTheNumber(String text) {
        return BARE_NUMBER_AFTER_ORDER.matcher(text).find();
    }

    /**
     * 兜底那条存进库的正文：把“记一下”这种命令外壳剥掉。
     *
     * <p>实测：老人说“记一下我的身高是180”，存下去的是整句“记一下我的身高是180”——
     * 回看时正文前面挂着一个命令，念给他听也不像人话（身高现在另立了一项，但认不出项目的
     * 那些话还走这条路）。剥壳的词表 {@link com.team.silveragent.application.memo.MemoParser} 里已经有了，是备忘正文同一套，
     * 不再抄一份：两处各写一份，改一处就会漏一处。
     *
     * <p>剥完什么都不剩（“记一下”三个字而已）就照原话存——那说明这句话本来就没说什么，
     * 而记录页上一条空白的记录比一句带着命令的原话更让人摸不着头脑。
     */
    private static String storedText(String raw) {
        MemoParser.MemoIntent memo = MemoParser.detect(raw);
        String text = memo == null ? null : memo.text();
        String stored = text == null || text.isBlank() ? raw : text;
        return stored.length() > MAX_TEXT ? stored.substring(0, MAX_TEXT) : stored;
    }

    /** 这个数旁边有没有单位或指标词（判断它是不是一次测量，而不是门牌号、楼层）。 */
    private static boolean looksMeasured(String text, int start, int end) {
        int from = Integer.max(0, start - OTHER_LOOK);
        int to = Integer.min(text.length(), end + OTHER_LOOK);
        String near = text.substring(from, to).toLowerCase();
        return containsAny(near, OTHER_UNITS) || containsAny(near, OTHER_HINTS);
    }

    /**
     * 老人要记的一个数，但认不出是哪一项（“我今天量了，是135”“我测出来是7.8”）。
     */
    public record MeasurementPrompt(String raw, BigDecimal value) { }

    /**
     * {@link #detect} 认不出项目之后再看一眼：老人是不是在报一次测量，只是没说清哪一项。
     *
     * <p>有这种句子时助手应该问一句“这是哪一项”，让老人点一下按钮：
     * 猜一个项目记下去是假记录，直接回“没听准”又让他把话重说一遍——
     * 老人点一下的成本远低于重新组织一句话，而这一下也把归属变成了他自己定的。
     */
    public static MeasurementPrompt unknownMeasurement(String message) {
        String raw = message == null ? "" : message.trim();
        if (raw.isEmpty()) return null;
        String text = ChineseNumbers.normalize(raw);
        if (containsAny(text, ADVICE_WORDS)) return null;
        if (!containsAny(text, MEASURE_ACTIONS)) return null;
        Matcher number = OTHER_NUMBER.matcher(text);
        while (number.find()) {
            int after = number.end();
            char next = after < text.length() ? text.charAt(after) : ' ';
            if (NUMBER_UNITS.indexOf(next) >= 0) continue;
            return new MeasurementPrompt(raw, new BigDecimal(number.group(1)));
        }
        return null;
    }

    private static boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }
}
