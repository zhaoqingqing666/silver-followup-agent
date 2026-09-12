package com.team.silveragent.agent;

import org.springframework.stereotype.Component;

@Component
public class TemplateAnswerGenerator implements AnswerGenerator {
    @Override public String generate(ReplyContext context) { return context.authoritativeDraft(); }
    @Override public String mode() { return "TEMPLATE_FALLBACK"; }
    @Override public String providerName() { return "local"; }
    @Override public String modelName() { return "none"; }
}
