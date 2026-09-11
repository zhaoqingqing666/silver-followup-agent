package com.team.silveragent.application;

import com.team.silveragent.agent.ExtractedFacts;
import com.team.silveragent.agent.MedicalBoundaryRules;
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
        // 越界判定共用 MedicalBoundaryRules 的“默认怀疑”口径，不再在这里维护第二张词表。
        if (MedicalBoundaryRules.looksLikeMedicalAdvice(message)) return Decision.MEDICAL_BOUNDARY;
        if (MedicalBoundaryRules.bodyDiscomfort(message)) return Decision.MEDICAL_BOUNDARY;
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

    /**
     * 模型主导模式：业务意图仍只认主模型的分类，不拿 Java 关键词去覆盖它；
     * 但医疗越界要补一道规则兜底——模型漏判的表现是整句话被当成普通信息静默忽略，
     * 老人以为得到了答复，这个失败模式比多提示一次更贵。
     */
    Decision evaluateModel(String message, ExtractedFacts facts) {
        if (facts == null) return Decision.NONE;
        if ("EMERGENCY".equals(facts.intent())) return Decision.EMERGENCY;
        if ("MEDICAL_ADVICE".equals(facts.intent()) || "HEALTH_CONCERN".equals(facts.intent())) {
            return Decision.MEDICAL_BOUNDARY;
        }
        if (MedicalBoundaryRules.looksLikeMedicalAdvice(message)) return Decision.MEDICAL_BOUNDARY;
        return Decision.NONE;
    }

    private boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }
}
