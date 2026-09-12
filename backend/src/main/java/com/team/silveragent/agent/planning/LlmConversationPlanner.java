package com.team.silveragent.agent.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.silveragent.agent.AgentContext;
import com.team.silveragent.agent.ExtractedFacts;
import com.team.silveragent.agent.model.ModelGateway;
import com.team.silveragent.agent.model.ModelRequest;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 受控规划器：模型提出回答、补问、只读工具或工作流动作，Java 决定是否允许执行。
 * 使用结构化 JSON 作为兼容适配层，避免业务代码绑定某一家模型的 function-calling 格式。
 */
@Primary
@Component
public class LlmConversationPlanner implements ConversationPlanner {
    private static final Logger log = LoggerFactory.getLogger(LlmConversationPlanner.class);
    private final ModelGateway gateway;
    private final ObjectMapper json;
    private final RuleConversationPlanner fallback;
    private final AgentSystemPrompt prompt;

    @Autowired
    public LlmConversationPlanner(ModelGateway gateway, ObjectMapper json, RuleConversationPlanner fallback,
                                  AgentSystemPrompt prompt) {
        this.gateway = gateway;
        this.json = json;
        this.fallback = fallback;
        this.prompt = prompt;
    }

    /** 兼容不启动 Spring 的单元测试和外部适配代码。 */
    public LlmConversationPlanner(ModelGateway gateway, ObjectMapper json, RuleConversationPlanner fallback) {
        this(gateway, json, fallback, new AgentSystemPrompt());
    }

    @Override
    public PlannerDecision plan(String message, AgentContext context, List<PlannerTool> allowedTools) {
        if (!gateway.available()) return fallback.plan(message, context, allowedTools);
        try {
            return requestModel(message, context, allowedTools, "MODEL_PLANNER");
        } catch (Exception error) {
            log.warn("Conversation planner failed; using rule fallback: {}", error.toString());
            return fallback.plan(message, context, allowedTools);
        }
    }

    @Override
    public PlannerDecision continueAfterTools(String originalMessage, AgentContext context,
                                              List<PlannerTool> allowedTools, String toolResults) {
        if (!gateway.available()) return fallback.plan(originalMessage, context, allowedTools);
        String continuation = """
                这是同一用户轮次内刚刚执行完成的真实只读工具结果：
                %s

                请继续完成用户原始请求：“%s”。
                不要重复调用工具名和参数都相同的工具。你可以继续调用另一个必要的只读工具，
                也可以依据结果直接回答或提出下一项待确认动作。回复只能使用工具返回的事实，
                下一步只能从 allowedNextActions 中选择。
                """.formatted(toolResults, originalMessage);
        try {
            return requestModel(continuation, context, allowedTools, "MODEL_TOOL_CONTINUATION");
        } catch (Exception error) {
            log.warn("Tool-result continuation failed; using authoritative tool result: {}", error.toString());
            return new PlannerDecision(PlannerActionType.ANSWER, "UNKNOWN", null, Map.of(),
                    null, "FOLLOWUP_FLOW", ExtractedFacts.empty(), "TOOL_RESULT_FALLBACK", List.of());
        }
    }

    private PlannerDecision requestModel(String message, AgentContext context,
                                         List<PlannerTool> allowedTools, String source) throws Exception {
        List<ModelRequest.Message> messages = new ArrayList<>();
        messages.add(new ModelRequest.Message("system",
                prompt.planning(context, json.writeValueAsString(allowedTools))));
        for (AgentContext.Message item : context.recentMessages()) {
            messages.add(new ModelRequest.Message(item.role(), item.content()));
        }
        messages.add(new ModelRequest.Message("user", message));
        // max_tokens 同时包含思维链和正式输出。业务轮的思考加结构化 JSON 需要足够空间，
        // 否则会截断成半截 JSON 再静默回退规则。
        JsonNode root = json.readTree(jsonText(gateway.complete(new ModelRequest(messages, true, 3000, 0.10))));
        PlannerActionType actionType = PlannerActionType.valueOf(text(root, "actionType", "PROPOSE_WORKFLOW_ACTION"));
        String intent = text(root, "intent", "PROVIDE_INFORMATION");
        JsonNode factsNode = root.path("facts");
        Map<String, String> arguments = stringMap(root.path("arguments"));
        List<PlannerToolCall> toolCalls = toolCalls(root, arguments);
        ExtractedFacts facts = facts(factsNode.isObject() ? factsNode : root, intent, arguments);
        return new PlannerDecision(actionType, intent, nullableText(root, "toolName"), arguments,
                nullableText(root, "replyDraft"), text(root, "dialogueMode", dialogueMode(intent)),
                facts, source, toolCalls);
    }

    private List<PlannerToolCall> toolCalls(JsonNode root, Map<String, String> legacyArguments) {
        JsonNode items = root.path("toolCalls");
        if (!items.isArray()) items = root.path("calls");
        List<PlannerToolCall> result = new ArrayList<>();
        if (items.isArray()) {
            for (JsonNode item : items) {
                String name = nullableText(item, "toolName");
                if (name == null) name = nullableText(item, "name");
                if (name != null) result.add(new PlannerToolCall(name, stringMap(item.path("arguments"))));
                if (result.size() == 3) break;
            }
        }
        String legacyName = nullableText(root, "toolName");
        if (result.isEmpty() && legacyName != null) result.add(new PlannerToolCall(legacyName, legacyArguments));
        return result;
    }

    private ExtractedFacts facts(JsonNode node, String intent, Map<String, String> arguments) {
        return new ExtractedFacts(intent,
                first(nullableText(node, "hospital"), arguments.get("hospital")),
                first(nullableText(node, "department"), arguments.get("department")),
                date(first(nullableText(node, "date"), arguments.get("date"))),
                nullableBoolean(node, "acceptAlternative"), nullableBoolean(node, "needCompanion"),
                nullableBoolean(node, "needTravel"), nullableBoolean(node, "notifyFamily"),
                nullableText(node, "transport"), time(nullableText(node, "selectedTime")),
                nullableText(node, "timePreference"), nullableBoolean(node, "acceptRecommendedTime"),
                nullableText(node, "acknowledgement"), nullableText(node, "emotion"),
                nullableText(node, "concern"), nullableText(node, "familyContact"));
    }

    private Map<String, String> stringMap(JsonNode node) {
        if (!node.isObject()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        node.properties().forEach(item -> {
            if (!item.getValue().isNull() && item.getValue().isValueNode()) result.put(item.getKey(), item.getValue().asText());
        });
        return result;
    }

    private String jsonText(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("```")) value = value.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        return value;
    }

    private String text(JsonNode node, String name, String fallbackValue) {
        String value = node.path(name).asText("").trim();
        return value.isEmpty() || "null".equalsIgnoreCase(value) ? fallbackValue : value;
    }
    private String nullableText(JsonNode node, String name) { return text(node, name, null); }
    private Boolean nullableBoolean(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.asBoolean();
    }
    private LocalDate date(String value) { try { return value == null ? null : LocalDate.parse(value); } catch (Exception ignored) { return null; } }
    private LocalTime time(String value) { try { return value == null ? null : LocalTime.parse(value); } catch (Exception ignored) { return null; } }
    private String first(String preferred, String fallbackValue) { return preferred == null ? fallbackValue : preferred; }
    private String dialogueMode(String intent) {
        if ("EMOTIONAL_SUPPORT".equals(intent) || "CLARIFY_DISCOMFORT".equals(intent)) return "SUPPORT";
        if ("SMALL_TALK".equals(intent)) return "SMALL_TALK";
        return "FOLLOWUP_FLOW";
    }

    @Override public String mode() {
        return gateway.available() ? "MODEL_PLANNER_WITH_RULE_FALLBACK" : fallback.mode();
    }
}
