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
    /** 一轮里认下来的排除目标条数上限（见 {@link #names}）。 */
    private static final int MAX_EXCLUDED_TARGETS = 5;
    /** 从一次模型输出里最多读几条结构化推荐；3 条的业务上限由校验层判，见 {@link #recommendations}。 */
    private static final int MAX_PARSED_RECOMMENDATIONS = 8;
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
        ExtractedFacts facts = facts(factsNode.isObject() ? factsNode : root, intent,
                readTool(actionType) ? Map.of() : arguments);
        return new PlannerDecision(actionType, intent, nullableText(root, "toolName"), arguments,
                nullableText(root, "replyDraft"), text(root, "dialogueMode", dialogueMode(intent)),
                facts, source, toolCalls, recommendations(root.path("recommendations")),
                answering(root));
    }

    /**
     * 推荐轮的结构化清单。
     *
     * <p>只认数组：一个字符串或者一个对象都不算，宁可当作「没给推荐」让校验层去判，
     * 也不替模型猜它想推荐谁。读不出来的项跳过，一项畸形不该把整轮作废。
     *
     * <p>条数封顶 {@value #MAX_PARSED_RECOMMENDATIONS}，但<b>故意不按 3 截断</b>：上限 3 是业务规则，
     * 由校验层判 {@code TOO_MANY} 并让模型自己改，解析层悄悄砍掉多余的，等于把「模型多给了一条」
     * 这件事藏起来。
     */
    private List<HospitalRecommendation> recommendations(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<HospitalRecommendation> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) continue;
            result.add(new HospitalRecommendation(nullableText(item, "hospitalId"),
                    nullableText(item, "reason"), strings(item.path("evidenceRefs"))));
            if (result.size() == MAX_PARSED_RECOMMENDATIONS) break;
        }
        return result;
    }

    /**
     * 最终话语。模型写 {@code answering}；仍写成 {@code replyDraft} 的按等义回退——
     * 两者都来自这同一次输出，所以回退不等于多要一次调用。
     */
    private String answering(JsonNode root) {
        return first(nullableText(root, "answering"), nullableText(root, "replyDraft"));
    }

    private List<String> strings(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isValueNode()) continue;
            String text = item.asText("").trim();
            if (!text.isEmpty()) result.add(text);
        }
        return result;
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

    /**
     * 只读工具的参数是「这次要查什么」，不是「老人当场改动了草稿」。
     *
     * <p>下面 {@link #facts} 有一层兼容：模型把参数写在 {@code arguments} 而不是 {@code facts}
     * 节点时，仍按老格式抽出来当事实。这层兼容对办理/确认类动作是必要的——那类动作的参数就是
     * 老人刚说的条件；但对只读查询是有害的：老人问一句「市二院下周三有号吗」，参数里的医院和
     * 日期会被当成改草稿的意愿写进待办理的预约，一次查询就顺手改掉了他手头正在办的那笔。
     * 所以只读工具的参数只用于执行这次查询，事实只认 {@code facts} 节点。
     */
    private static boolean readTool(PlannerActionType actionType) {
        return actionType == PlannerActionType.CALL_READ_TOOL
                || actionType == PlannerActionType.CALL_READ_TOOLS;
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
                nullableText(node, "concern"), nullableText(node, "familyContact"),
                names(node, "excludedHospitals"));
    }

    /**
     * 「老人这一轮明确不要」的目标名，可能不止一个。
     *
     * <p>只认两种写法：JSON 数组（每一项是一个完整的目标名），或者一个字符串——那表示
     * <b>恰好一个</b>排除目标。故意<b>不</b>按「、」「,」「，」切分字符串：切分等于让 Java
     * 替模型猜它想排除谁，而医院名本身就可能含这些字符；模型真要排除两家，写成数组就行。
     *
     * <p>读不出来的项直接跳过（空串、空白），不因为一个畸形字段就打断这一轮。条数封顶
     * {@value #MAX_EXCLUDED_TARGETS} 条：这个字段影响的是「过滤掉谁」，不该由模型决定
     * 能把候选清空到什么规模。
     */
    private List<String> names(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) return List.of();
        List<String> result = new ArrayList<>();
        if (value.isArray()) {
            value.forEach(item -> {
                String text = item.asText("").trim();
                if (!text.isEmpty()) result.add(text);
            });
        } else if (value.isValueNode()) {
            String text = value.asText("").trim();
            if (!text.isEmpty()) result.add(text);
        }
        return result.size() > MAX_EXCLUDED_TARGETS ? List.copyOf(result.subList(0, MAX_EXCLUDED_TARGETS)) : result;
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
