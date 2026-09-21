package com.team.silveragent.application;

import com.team.silveragent.agent.WriteClaims;

import org.springframework.stereotype.Component;

/** 校验模型可直接说给用户的 L0 回复，防止越过工具和确认声称业务成功。 */
@Component
final class ActionValidator {
    boolean safeDirectReply(String reply) {
        if (reply == null || reply.isBlank() || reply.length() > 500) return false;
        // 这条路（DIRECT_ANSWER）一个工具都不执行，所以任何「已经办好了」的说法都是凭空的。
        // 词表与回答阶段的守卫共用一份（见 WriteClaims）：这里原本只拦了预约/提醒几种说法，
        // 「好的，我记下了：您对青霉素过敏」正是从没收进去的那一半漏出去的。
        if (WriteClaims.announces(reply)) return false;
        return !containsAny(reply,
                "建议服用", "建议吃药", "增加剂量", "减少剂量", "可以停药",
                "告诉我您的年龄", "既往病史", "判断是什么病", "帮您诊断");
    }

    private boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }
}
