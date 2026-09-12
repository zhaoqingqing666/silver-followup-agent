package com.team.silveragent.agent.planning;

/** 大模型每轮只能提出的受控动作；查询、确认交互和业务工作流使用彼此独立的通道。 */
public enum PlannerActionType {
    ANSWER,
    ASK_USER,
    CALL_READ_TOOL,
    CALL_READ_TOOLS,
    /** 调用只创建/处理确认交互的工具；不能直接执行预约写操作。 */
    CALL_CONFIRMATION_TOOL,
    PROPOSE_WORKFLOW_ACTION
}
