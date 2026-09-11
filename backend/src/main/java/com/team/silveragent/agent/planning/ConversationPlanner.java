package com.team.silveragent.agent.planning;

import com.team.silveragent.agent.AgentContext;

import java.util.List;

public interface ConversationPlanner {
    PlannerDecision plan(String message, AgentContext context, List<PlannerTool> allowedTools);
    String mode();
}
