package com.team.silveragent.agent.planning;

import java.util.List;

/** 发送给规划模型的稳定工具契约；不包含 Java 实现和密钥。 */
public record PlannerTool(
        String name,
        String description,
        String risk,
        List<String> arguments
) { }
