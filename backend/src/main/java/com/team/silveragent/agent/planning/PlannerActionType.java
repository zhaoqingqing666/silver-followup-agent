package com.team.silveragent.agent.planning;

/** 大模型每轮只能提出的四类受控动作。 */
public enum PlannerActionType {
    ANSWER,
    ASK_USER,
    CALL_READ_TOOL,
    CALL_READ_TOOLS,
    PROPOSE_WORKFLOW_ACTION
}
