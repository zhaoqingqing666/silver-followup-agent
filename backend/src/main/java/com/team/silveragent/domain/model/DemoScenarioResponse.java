package com.team.silveragent.domain.model;

import java.util.List;

/**
 * 场景重置的结果：一个全新的起始会话，以及按编号给出的演示步骤。
 *
 * <p>步骤由后端下发而不是写在演示文档里，是因为步骤里的日期（「选 9月16日」）跟着今天走，
 * 文档抄一份就会过期；这里的每一步都按真实流程生成，照着念即可。
 */
public record DemoScenarioResponse(String scenarioId, String title, List<String> steps,
                                   List<String> availableScenarios, AgentTurnResponse turn) { }
