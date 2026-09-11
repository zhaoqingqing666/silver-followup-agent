package com.team.silveragent.agent.planning;

import com.team.silveragent.agent.AgentContext;

import java.util.List;

public interface ConversationPlanner {
    PlannerDecision plan(String message, AgentContext context, List<PlannerTool> allowedTools);

    /**
     * 同一用户轮次内，读取真实工具结果后继续规划。默认实现仍复用 plan，便于规则规划器
     * 和外部适配器保持兼容；模型规划器会把工具结果作为权威上下文再次发给同一模型。
     */
    default PlannerDecision continueAfterTools(String originalMessage, AgentContext context,
                                               List<PlannerTool> allowedTools, String toolResults) {
        return plan("请根据以下真实工具结果继续处理用户刚才的请求。\n" + toolResults,
                context, allowedTools);
    }

    String mode();
}
