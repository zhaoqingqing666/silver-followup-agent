package com.team.silveragent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.silveragent.agent.model.ModelGateway;
import com.team.silveragent.agent.model.ModelRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmAnswerGeneratorTests {
    @Test
    void usesModelWordingWhenItRespectsAuthoritativeFacts() {
        LlmAnswerGenerator generator = generator("{\"reply\":\"听起来您有些累，我们可以慢一点。您想先休息一下吗？\"}");
        ReplyContext context = context("CANCELLED", "SUPPORT",
                "听起来您现在有些累，我们可以先慢一点。您想先休息吗？", false);

        assertThat(generator.generate(context)).contains("慢一点", "休息");
        assertThat(generator.mode()).isEqualTo("MODEL_ANSWER_WITH_TEMPLATE_FALLBACK");
    }

    @Test
    void rejectsAClaimThatBypassesPendingConfirmation() {
        String draft = "请核对本次实际执行内容。";
        LlmAnswerGenerator generator = generator("{\"reply\":\"已经预约成功，也已经通知家属。\"}");

        assertThat(generator.generate(context("AWAITING_CONFIRMATION", "FOLLOWUP_FLOW", draft, true)))
                .isEqualTo(draft);
    }

    @Test
    void supportiveReplyCannotInventABookingQuestion() {
        String draft = "当然可以，您想说什么都可以。";
        LlmAnswerGenerator generator = generator("{\"reply\":\"当然可以。请问您打算去哪家医院复诊？\"}");

        assertThat(generator.generate(context("ASK_HOSPITAL", "SUPPORT", draft, false)))
                .isEqualTo(draft);
    }

    private LlmAnswerGenerator generator(String response) {
        ModelGateway gateway = new ModelGateway() {
            @Override public String complete(ModelRequest request) { return response; }
            @Override public boolean available() { return true; }
            @Override public String providerName() { return "test-provider"; }
            @Override public String modelName() { return "test-model"; }
        };
        return new LlmAnswerGenerator(gateway, new TemplateAnswerGenerator(), new ObjectMapper());
    }

    private ReplyContext context(String stage, String dialogueMode, String draft, boolean awaitingConfirmation) {
        return new ReplyContext(stage, dialogueMode, "医院=待确认", draft, List.of(), "无", "无", "无", "无",
                List.of(), false, false, awaitingConfirmation);
    }
}
