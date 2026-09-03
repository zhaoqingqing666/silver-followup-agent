package com.team.silveragent.agent;

public interface FactExtractor {
    ExtractedFacts extract(String message, AgentContext context);
    String mode();
}
