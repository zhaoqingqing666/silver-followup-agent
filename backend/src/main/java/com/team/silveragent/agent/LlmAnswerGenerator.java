package com.team.silveragent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.silveragent.agent.model.ModelGateway;
import com.team.silveragent.agent.model.ModelRequest;
import com.team.silveragent.agent.planning.AgentSystemPrompt;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Primary
@Component
public class LlmAnswerGenerator implements AnswerGenerator {
    private static final Logger log = LoggerFactory.getLogger(LlmAnswerGenerator.class);
    private final ModelGateway gateway;
    private final TemplateAnswerGenerator fallback;
    private final ObjectMapper json;
    private final AgentSystemPrompt prompt;

    @Autowired
    public LlmAnswerGenerator(ModelGateway gateway, TemplateAnswerGenerator fallback, ObjectMapper json,
                              AgentSystemPrompt prompt) {
        this.gateway = gateway;
        this.fallback = fallback;
        this.json = json;
        this.prompt = prompt;
    }

    /** 兼容不启动 Spring 的单元测试和外部适配代码。 */
    public LlmAnswerGenerator(ModelGateway gateway, TemplateAnswerGenerator fallback, ObjectMapper json) {
        this(gateway, fallback, json, new AgentSystemPrompt());
    }

    @Override
    public String generate(ReplyContext context) {
        if (!gateway.available()) return fallback.generate(context);
        try {
            List<ModelRequest.Message> messages = new ArrayList<>();
            messages.add(new ModelRequest.Message("system", prompt.toolResultAnswer()));
            for (AgentContext.Message item : context.recentMessages()) {
                messages.add(new ModelRequest.Message(item.role(), item.content()));
            }
            messages.add(new ModelRequest.Message("user", request(context)));
            // 220 同样会被思维链吃光：思考完已经没有额度写 reply，接口返回空 content，
            // 日志表现为“大模型没有返回内容”，随后回退到模板回复。
            String raw = gateway.complete(new ModelRequest(messages, false, 1500, 0.25));
            String reply = replyOf(raw);
            return valid(reply, context) ? reply : fallback.generate(context);
        } catch (Exception error) {
            log.warn("Answer generation failed; using authoritative template: {}", error.toString());
            return fallback.generate(context);
        }
    }

    /**
     * 回答阶段的产物本来就是一句给用户看的话，模型直接说人话比套一层 JSON 更稳。
     * 但模型偶尔仍会裹一层 {"reply":"..."} 或代码块，这里两种都接受。
     */
    private String replyOf(String raw) {
        String value = jsonText(raw);
        if (value.startsWith("{")) {
            try {
                String wrapped = json.readTree(value).path("reply").asText("").trim();
                if (!wrapped.isBlank()) return wrapped;
            } catch (Exception ignored) {
                // 不是合法 JSON 就按纯文本处理
            }
        }
        return value;
    }

    private String request(ReplyContext context) {
        return """
                当前业务阶段：%s
                当前对话模式：%s
                已知业务事实：%s
                权威回复草稿：%s
                可用按钮：%s
                当前计划：%s
                确认信息：%s
                结果信息：%s
                工具结果：%s
                请只根据以上信息生成reply，不要解释你的工作过程。
                """.formatted(context.stage(), context.dialogueMode(), context.knownFacts(),
                context.authoritativeDraft(), context.quickReplies(), context.planSummary(),
                context.confirmationSummary(), context.resultSummary(), context.toolSummary());
    }

    private boolean valid(String reply, ReplyContext context) {
        if (reply == null || reply.isBlank() || reply.length() > 500) return false;
        if (context.emergency() && (!reply.contains("120") || !reply.contains("暂停"))) return false;
        if (context.medicalBoundary() && (!reply.contains("不能") || !containsAny(reply, "医生", "医疗机构"))) return false;
        if (context.awaitingConfirmation() && containsAny(reply, "预约成功", "已经预约", "已创建提醒", "已通知家属")) return false;
        // 权威草稿已经定了这一轮问什么，模型只能改措辞、不能改问题：一旦这轮问的是草稿里没有的
        // 流程问题，界面按钮（跟着草稿走）和回复文字（跟着模型走）就会各说各话。
        if (introducesFlowQuestion(reply) && !introducesFlowQuestion(context.authoritativeDraft())) return false;
        return true;
    }

    private String jsonText(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        return value;
    }

    private boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }

    private boolean introducesFlowQuestion(String value) {
        return containsAny(value, "哪家医院", "就诊医院", "什么科室", "复诊科室", "哪天复诊", "复诊日期",
                "继续办理吗", "陪同", "交通方式", "怎么去医院", "通知家属", "通知家里");
    }

    @Override public String mode() { return gateway.available() ? "MODEL_ANSWER_WITH_TEMPLATE_FALLBACK" : fallback.mode(); }
    @Override public String providerName() { return gateway.available() ? gateway.providerName() : fallback.providerName(); }
    @Override public String modelName() { return gateway.available() ? gateway.modelName() : fallback.modelName(); }
}
