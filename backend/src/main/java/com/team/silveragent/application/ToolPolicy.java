package com.team.silveragent.application;

import com.team.silveragent.agent.planning.PlannerDecision;
import org.springframework.stereotype.Component;

/** 工具权限层：目前只允许注册表中的只读工具自动进入执行路线。 */
@Component
final class ToolPolicy {
    enum Decision { ALLOW, DENY_UNKNOWN_TOOL, DENY_SIDE_EFFECT }

    Decision evaluate(PlannerDecision proposal, ToolRegistry.RegisteredTool tool) {
        if (tool == null) return Decision.DENY_UNKNOWN_TOOL;
        if (!"READ_ONLY".equals(tool.definition().risk())) return Decision.DENY_SIDE_EFFECT;
        return Decision.ALLOW;
    }
}
