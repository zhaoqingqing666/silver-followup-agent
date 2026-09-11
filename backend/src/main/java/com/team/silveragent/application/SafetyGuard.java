package com.team.silveragent.application;

import com.team.silveragent.agent.ExtractedFacts;
import org.springframework.stereotype.Component;

@Component
final class SafetyGuard {
    enum Decision { NONE, EMERGENCY, MEDICAL_BOUNDARY }

    Decision precheck(String message) {
        if (containsAny(message,
                "胸痛", "心口疼", "心口痛", "胸口疼", "胸口痛", "心前区疼", "心前区痛",
                "胸闷", "胸部压迫", "呼吸困难", "昏迷", "大出血", "喘不上气", "想不开", "不想活了")) {
            return Decision.EMERGENCY;
        }
        if (containsAny(message, "怎么用药", "药量", "诊断", "检查结果", "是不是得了", "吃什么药", "推荐药", "加量", "减量", "治疗方案", "停药")) {
            return Decision.MEDICAL_BOUNDARY;
        }
        if (physicalDiscomfort(message)) return Decision.MEDICAL_BOUNDARY;
        return Decision.NONE;
    }

    Decision evaluate(String message, ExtractedFacts facts) {
        Decision direct = precheck(message);
        if (direct != Decision.NONE) return direct;
        if ("EMERGENCY".equals(facts.intent())) return Decision.EMERGENCY;
        if ("MEDICAL_ADVICE".equals(facts.intent()) || "HEALTH_CONCERN".equals(facts.intent())) {
            return Decision.MEDICAL_BOUNDARY;
        }
        return Decision.NONE;
    }

    /** 模型主导模式只采用主模型的医疗分类，不再用 Java 关键词覆盖本轮意图。 */
    Decision evaluateModel(ExtractedFacts facts) {
        if (facts == null) return Decision.NONE;
        if ("EMERGENCY".equals(facts.intent())) return Decision.EMERGENCY;
        if ("MEDICAL_ADVICE".equals(facts.intent()) || "HEALTH_CONCERN".equals(facts.intent())) {
            return Decision.MEDICAL_BOUNDARY;
        }
        return Decision.NONE;
    }

    private boolean physicalDiscomfort(String value) {
        if (value.contains("心里不舒服")) return false;
        boolean bodyPart = containsAny(value, "腿", "脚", "膝", "腰", "背", "肩", "胳膊", "手", "头", "肚子", "腹部", "胃", "身体");
        boolean symptom = containsAny(value, "不舒服", "疼", "痛", "麻", "无力", "难受");
        return bodyPart && symptom;
    }

    private boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }
}
