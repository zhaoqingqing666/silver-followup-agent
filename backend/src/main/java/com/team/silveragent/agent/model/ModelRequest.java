package com.team.silveragent.agent.model;

import java.util.List;

public record ModelRequest(
        List<Message> messages,
        boolean jsonOutput,
        int maxTokens,
        double temperature
) {
    public record Message(String role, String content) { }
}
