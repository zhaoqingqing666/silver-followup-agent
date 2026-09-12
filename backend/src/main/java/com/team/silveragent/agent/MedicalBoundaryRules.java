package com.team.silveragent.agent;

import java.util.List;

/**
 * 医疗服务边界判定：只回答“这句话是不是在要一个医疗判断”，不回答“用户想走哪条流程”。
 *
 * <p>口径是“默认怀疑”而不是“关键词命中才拒绝”：越界漏判的失败模式是被当作普通信息
 * 静默忽略，用户会以为得到了答复，比明确拒绝更危险。所以症状/药物/检查类词汇只要与
 * 疑问语气同时出现即判定越界，不再依赖一张必须穷举的动词表。
 *
 * <p>混合句式也要判出来：“9月18日，我最近头晕是不是血压高了”里的日期能被正常解析、
 * 写进草稿，如果越界那半句没人管，老人就会以为助手默认了这件事。
 *
 * <p>判定结果由 {@link RuleFactExtractor} 与
 * {@code com.team.silveragent.application.SafetyGuard} 共用（模型模式与规则模式都走它），
 * 避免两条路径口径不一致。
 */
public final class MedicalBoundaryRules {

    /** 本身即为医疗信息索求的句式，命中即越界，无需疑问语气。 */
    private static final List<String> EXPLICIT = List.of(
            "怎么用药", "吃什么药", "推荐药", "用什么药", "药量", "加量", "减量", "剂量",
            "停药", "副作用", "诊断", "治病", "治疗方案", "是不是得了", "什么病",
            "严不严重", "要不要紧", "检查结果", "化验结果");

    /** 症状与体征。 */
    private static final List<String> SYMPTOMS = List.of(
            "血压", "血糖", "血脂", "头晕", "头疼", "头痛", "发烧", "发热", "咳嗽", "心慌",
            "心悸", "胸闷", "气短", "水肿", "失眠", "恶心", "呕吐", "乏力", "麻木", "抽筋",
            "便秘", "腹泻", "过敏", "感冒", "发炎", "偏高", "偏低", "不舒服", "难受");

    /** 药物与用药行为。 */
    private static final List<String> MEDICATIONS = List.of(
            "阿司匹林", "降压药", "降糖药", "胰岛素", "他汀", "布洛芬", "头孢", "抗生素",
            "中药", "这个药", "这药", "吃的药", "用药", "服药", "吃药", "开药", "买药");

    /**
     * 疾病处置类名词：单独出现未必是在要判断——“那天我要做手术，帮我把复诊改到下周”
     * 是办事，不是问诊。所以要配上疑问语气才算越界。
     */
    private static final List<String> HANDLING = List.of(
            "住院", "手术", "化疗", "输液", "打针", "复查结果");

    /** 检查报告类名词，需与查看诉求同时出现。 */
    private static final List<String> REPORTS = List.of(
            "化验单", "化验报告", "检查单", "检查报告", "报告单", "片子", "影像", "病历",
            "体检报告", "结果单", "化验");

    /** 查看、解读诉求；只与 {@link #REPORTS} 组合生效，不会把“看看还有没有号”误判成越界。 */
    private static final List<String> LOOK_MARKERS = List.of(
            "看看", "看", "读一下", "解读", "什么意思", "帮我读");

    /** 疑问语气。 */
    private static final List<String> QUESTION_MARKERS = List.of(
            "吗", "呢", "要不要", "能不能", "可不可以", "可以吗", "几片", "几粒", "几次",
            "多少", "多久", "该不该", "需不需要", "是不是", "会不会", "怎么吃", "还要不要");

    /** 明确属于办理事项而非医疗咨询的诉求，优先放行。 */
    private static final List<String> LOGISTICS_REQUESTS = List.of(
            "带什么", "要带", "带哪些", "准备什么", "准备哪些", "材料清单", "需要带");

    /** 老人自己量到的项目。 */
    private static final List<String> SELF_RECORD_ITEMS = List.of(
            "血压", "收缩压", "舒张压", "高压", "低压", "血糖", "心率", "脉搏", "体温", "体重", "血氧");

    /**
     * 回看自己记过的数（“我最近的血压是多少”“我的血压怎么样”）：这是我们自己的健康记录功能，
     * 问的是他自己量过的数字，不是要医疗判断，所以放行。词表与
     * {@code com.team.silveragent.application.HealthRecordParser} 的回查口气保持一致。
     */
    private static final List<String> SELF_RECORD_QUERIES = List.of(
            "多少", "好多", "记录", "查一下", "查查", "怎么样", "高不高", "低不低",
            "正常吗", "是不是正常");

    /** 身体部位。 */
    private static final List<String> BODY_PARTS = List.of(
            "腿", "脚", "膝", "腰", "背", "肩", "胳膊", "手", "头", "肚子", "腹部", "胃", "身体");

    /** 不适的形容。 */
    private static final List<String> DISCOMFORT = List.of("不舒服", "疼", "痛", "麻", "无力", "难受");

    private MedicalBoundaryRules() { }

    /** 判断这句话是否在索要医疗判断（诊断、用药、报告解读等）。 */
    public static boolean looksLikeMedicalAdvice(String message) {
        if (message == null || message.isBlank()) return false;
        if (containsAny(message, LOGISTICS_REQUESTS)) return false;
        if (containsAny(message, EXPLICIT)) return true;
        if (selfRecordQuery(message)) return false;
        if (containsAny(message, REPORTS) && containsAny(message, LOOK_MARKERS)) return true;
        return containsAny(message, QUESTION_MARKERS)
                && (containsAny(message, SYMPTOMS)
                        || containsAny(message, MEDICATIONS)
                        || containsAny(message, HANDLING));
    }

    /**
     * 只说身体不舒服、没有要判断（“我感觉自己的腿不舒服”）：同样不接，但要先关心一句，
     * 而不是当成病史采集去追问年龄。措辞与越界分开，判定与越界同级。
     */
    public static boolean bodyDiscomfort(String message) {
        if (message == null || message.isBlank()) return false;
        // “心里不舒服”是情绪，不是身体症状。
        if (message.contains("心里不舒服") || message.contains("心口不舒服")) return false;
        return containsAny(message, BODY_PARTS) && containsAny(message, DISCOMFORT);
    }

    private static boolean selfRecordQuery(String message) {
        return containsAny(message, SELF_RECORD_ITEMS) && containsAny(message, SELF_RECORD_QUERIES);
    }

    private static boolean containsAny(String value, List<String> words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }
}
