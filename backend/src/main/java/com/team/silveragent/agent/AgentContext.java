package com.team.silveragent.agent;

import java.time.LocalDate;
import java.util.List;

public record AgentContext(
        String stage,
        String knownFacts,
        LocalDate currentDate,
        List<Message> recentMessages
) {
    public record Message(String role, String content) { }
}
