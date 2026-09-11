package com.team.silveragent.application;

import org.springframework.stereotype.Component;

/** 校验模型可直接说给用户的 L0 回复，防止越过工具和确认声称业务成功。 */
@Component
final class ActionValidator {
    boolean safeDirectReply(String reply) {
        if (reply == null || reply.isBlank() || reply.length() > 500) return false;
        return !containsAny(reply,
                "预约成功", "已经预约", "已为您预约", "取消成功", "已经取消",
                "已创建提醒", "已经提醒", "已通知家属", "已经通知",
                "建议服用", "建议吃药", "增加剂量", "减少剂量", "可以停药",
                "告诉我您的年龄", "既往病史", "判断是什么病", "帮您诊断");
    }

    private boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }
}
