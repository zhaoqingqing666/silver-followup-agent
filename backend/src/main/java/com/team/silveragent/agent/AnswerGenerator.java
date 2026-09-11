package com.team.silveragent.agent;

public interface AnswerGenerator {
    String generate(ReplyContext context);
    String mode();
    String providerName();
    String modelName();
}
