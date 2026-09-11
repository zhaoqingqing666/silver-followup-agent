package com.team.silveragent.domain.model;

import java.util.List;

/**
 * 场景重置结果：可复现的起始会话，以及按编号给出的演示步骤。
 */
public record DemoScenarioResponse(String scenarioId, String title, List<String> steps,
                                   List<String> availableScenarios, AgentTurnResponse turn) { }
