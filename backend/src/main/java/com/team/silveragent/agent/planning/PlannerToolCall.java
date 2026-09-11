package com.team.silveragent.agent.planning;

import java.util.Map;

/** 模型提出的一次工具调用建议；只有通过 Java 策略检查后才会执行。 */
public record PlannerToolCall(String toolName, Map<String, String> arguments) {
    public PlannerToolCall {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
