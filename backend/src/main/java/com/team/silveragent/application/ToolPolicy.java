package com.team.silveragent.application;

import com.team.silveragent.agent.AgentRole;
import com.team.silveragent.agent.planning.PlannerDecision;
import org.springframework.stereotype.Component;

/**
 * 工具权限层：只读工具和确认交互使用不同入口；确认入口只进入状态机，不放行业务写操作。
 *
 * <p>按角色过滤工具清单只影响模型“看得见”什么，不构成安全边界——模型仍可能吐出一个
 * 它看不见的工具名。所以这里要按当前会话的操作者身份再判一次，两道都得有。
 */
@Component
final class ToolPolicy {
    enum Decision { ALLOW, DENY_UNKNOWN_TOOL, DENY_SIDE_EFFECT, DENY_ROLE }

    Decision evaluate(PlannerDecision proposal, ToolRegistry.RegisteredTool tool) {
        return evaluate(AgentRole.ELDER, tool);
    }

    Decision evaluate(AgentRole role, ToolRegistry.RegisteredTool tool) {
        if (tool == null) return Decision.DENY_UNKNOWN_TOOL;
        if (!tool.visibleTo(role)) return Decision.DENY_ROLE;
        if (!"READ_ONLY".equals(tool.definition().risk())) return Decision.DENY_SIDE_EFFECT;
        return Decision.ALLOW;
    }

    /** 确认工具有独立通道：只允许进入确认状态机，不等于允许执行其后的业务写操作。 */
    Decision evaluateConfirmation(AgentRole role, ToolRegistry.RegisteredTool tool) {
        if (tool == null) return Decision.DENY_UNKNOWN_TOOL;
        if (!tool.visibleTo(role)) return Decision.DENY_ROLE;
        if (!"CONFIRMATION_ONLY".equals(tool.definition().risk())) return Decision.DENY_SIDE_EFFECT;
        return Decision.ALLOW;
    }

    /**
     * 澄清工具有第三条独立通道，而且是最窄的一条：只问一句、摆出候选，不建卡、不发凭据。
     *
     * <p>刻意<b>不</b>复用确认通道：两者都挂着 CALL_CONFIRMATION_TOOL 这个动作类型，
     * 一旦共用入口，「澄清」就能顺手拿到一张确认卡和 {@code confirmationId}，
     * 而凭据正是全部写操作的唯一钥匙——那等于给澄清发了执行授权。
     */
    Decision evaluateClarification(AgentRole role, ToolRegistry.RegisteredTool tool) {
        if (tool == null) return Decision.DENY_UNKNOWN_TOOL;
        if (!tool.visibleTo(role)) return Decision.DENY_ROLE;
        if (!"CLARIFICATION_ONLY".equals(tool.definition().risk())) return Decision.DENY_SIDE_EFFECT;
        return Decision.ALLOW;
    }
}
