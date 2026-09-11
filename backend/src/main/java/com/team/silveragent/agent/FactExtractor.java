package com.team.silveragent.agent;

public interface FactExtractor {
    /** 提取事实，isVoice=true 表示来自语音输入，需要更积极地复述确认 */
    ExtractedFacts extract(String message, AgentContext context, boolean isVoice);
    String mode();
}
