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

    /**
     * 用户报的那条：草稿还在问「几点」，回复却宣布「已经记下了」——而这一轮一条都没写。
     *
     * <p>用真实会话里的原话与那条被改写出来的回复，因为这条 bug 的形状就是「措辞听起来完全合理」，
     * 换成抽象的“某件事办好了”反而测不出它。
     */
    @Test
    void replyCannotAnnounceAWriteTheDraftIsStillAskingAbout() {
        String draft = "还差具体几点，请告诉我几点提醒。";
        LlmAnswerGenerator generator = generator(
                "{\"reply\":\"好的，9月16日（周三）吃药这件事已经记下了。接下来办复诊：请问您想去哪家医院？\"}");

        assertThat(generator.generate(context("ASK_HOSPITAL", "FOLLOWUP_FLOW", draft, false)))
                .isEqualTo(draft);
    }

    @Test
    void memorizedClaimWithNoDraftBehindItIsRejected() {
        // 「帮我记一下我对青霉素过敏」被当成闲聊：回复说记下了，库里一条都没有
        String draft = "这件事您可以再说一遍，我帮您记下来。";
        LlmAnswerGenerator generator = generator("{\"reply\":\"好的，我记下了：您对青霉素过敏。\"}");

        assertThat(generator.generate(context("ASK_HOSPITAL", "FOLLOWUP_FLOW", draft, false)))
                .isEqualTo(draft);
    }

    /**
     * 反过来：这一轮真的落库了，草稿本身就在说「已记下」，模型换一种完成说法是允许的。
     * 判据跟着草稿走，不是禁用某几个词——否则模型连复述已办成的事都不敢。
     */
    @Test
    void modelWordingIsKeptWhenTheDraftItselfAnnouncesTheWrite() {
        String draft = "好的，已记下：“我对青霉素过敏”。这条作为长期备忘保留。";
        LlmAnswerGenerator generator = generator(
                "{\"reply\":\"已经记好了：您对青霉素过敏，这条我给您长期留着。\"}");

        assertThat(generator.generate(context("ASK_HOSPITAL", "FOLLOWUP_FLOW", draft, false)))
                .contains("长期");
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
