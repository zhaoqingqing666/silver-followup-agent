package com.team.silveragent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DeepSeekFactExtractor implements FactExtractor {
    /** 结构化抽取要的是稳定复现，不是创造性；温度压到接近确定。 */
    private static final double TEMPERATURE = 0.1;
    /** 单轮意图 JSON 很短，500 足够且能限制异常输出。 */
    private static final int MAX_TOKENS = 500;

    private final boolean enabled;
    private final String apiKey;
    private final String model;
    private final RestClient client;
    private final ObjectMapper json;
    private final RuleFactExtractor fallback;

    public DeepSeekFactExtractor(
            @Value("${agent.llm.enabled:false}") boolean enabled,
            @Value("${agent.llm.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${agent.llm.model:deepseek-v4-flash}") String model,
            @Value("${agent.llm.api-key:}") String apiKey,
            ObjectMapper json,
            RuleFactExtractor fallback) {
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.model = model;
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.json = json;
        this.fallback = fallback;
    }

    @Override
    public ExtractedFacts extract(String message, AgentContext context) {
        if (!enabled || apiKey.isBlank()) return fallback.extract(message, context);
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt(context)));
            for (AgentContext.Message item : context.recentMessages()) {
                messages.add(Map.of("role", item.role(), "content", item.content()));
            }
            messages.add(Map.of("role", "user", "content", message));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("response_format", Map.of("type", "json_object"));
            body.put("thinking", Map.of("type", "disabled"));
            body.put("temperature", TEMPERATURE);
            body.put("max_tokens", MAX_TOKENS);

            String raw = client.post().uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body).retrieve().body(String.class);
            JsonNode root = json.readTree(raw);
            JsonNode facts = json.readTree(root.path("choices").path(0).path("message").path("content").asText("{}"));
            return new ExtractedFacts(
                    text(facts, "intent", "PROVIDE_INFORMATION"),
                    nullableText(facts, "hospital"),
                    nullableText(facts, "department"),
                    date(facts),
                    nullableBoolean(facts, "acceptAlternative"),
                    nullableBoolean(facts, "needCompanion"),
                    nullableBoolean(facts, "needTravel"),
                    nullableBoolean(facts, "notifyFamily"),
                    nullableText(facts, "transport"),
                    time(facts),
                    nullableText(facts, "timePreference"),
                    nullableBoolean(facts, "acceptRecommendedTime"),
                    nullableText(facts, "acknowledgement"));
        } catch (Exception ignored) {
            return fallback.extract(message, context);
        }
    }

    @Override
    public String mode() { return enabled && !apiKey.isBlank() ? "DEEPSEEK_CONTEXTUAL_WITH_RULE_FALLBACK" : fallback.mode(); }

    public String model() { return model; }

    private String systemPrompt(AgentContext context) {
        return """
                你是银发复诊协同智能体的语言理解节点，只输出一个JSON对象，不要Markdown。
                当前日期：%s。
                当前流程节点：%s。
                已知业务信息：%s。
                结合最近对话理解本轮用户真正意图，不要重复询问已知信息。
                JSON字段：
                intent：CREATE_FOLLOWUP、PROVIDE_INFORMATION、RESTART_TASK、RESUME_TASK、START_EXECUTION、
                CHANGE_HOSPITAL、CHANGE_DEPARTMENT、CHANGE_DATE、CHANGE_TIME、
                QUERY_HOSPITALS、QUERY_HOSPITAL_INFO、QUERY_DEPARTMENTS、QUERY_AVAILABLE_SLOTS、REQUEST_RECOMMENDATION、
                QUERY_APPOINTMENTS、ASK_MATERIALS、CANCEL_TASK、CANCEL_APPOINTMENT、
                CONFIRM_ACTION、DENY_ACTION、MEDICAL_ADVICE、EMERGENCY、UNKNOWN；
                hospital、department、date、acceptAlternative、needCompanion、needTravel、
                notifyFamily、transport、selectedTime、timePreference、acceptRecommendedTime、acknowledgement。
                date必须为YYYY-MM-DD；用户没说年份时结合当前日期推断最近的未来日期。
                selectedTime为HH:mm。timePreference只能是MORNING、AFTERNOON或null。
                当流程为CONFIRM_SLOT，用户同意或拒绝推荐时间时设置acceptRecommendedTime。
                用户询问有哪些医院时使用QUERY_HOSPITALS；询问某医院资料时使用QUERY_HOSPITAL_INFO；
                询问一家医院有哪些科室时使用QUERY_DEPARTMENTS；要求推荐医院时使用REQUEST_RECOMMENDATION。
                用户询问“有哪些时间可预约”“哪天有号”“什么时候有号”时，必须使用QUERY_AVAILABLE_SLOTS，
                不得误判为查询医院。像“9.17”“9/17”这样的日期也要按当前日期转换成完整date。
                用户问“我的预约是什么时候”“我有哪些复诊”“查询我的预约”时使用QUERY_APPOINTMENTS。
                “我不想预约了”“退出当前办理”“这次先不办了”表示停止尚未提交的当前流程，使用CANCEL_TASK；
                “取消9月18日的预约”“取消我已经约好的复诊”“取消预约”表示管理数据库中已确认预约，使用CANCEL_APPOINTMENT。
                用户说“重新开始”“重新办理复诊”使用RESTART_TASK；说“继续刚才的办理”使用RESUME_TASK。
                用户在确认卡片前说“确认”“执行操作”使用CONFIRM_ACTION；说“返回修改”“不执行”使用DENY_ACTION。
                用户说“开始办理”“下一步”“执行吧”要求提交当前计划时使用START_EXECUTION。
                只要用户在问症状、药物、药量、化验单或检查报告的含义，一律使用MEDICAL_ADVICE，
                即使句子同时包含日期、医院或预约等办理信息，也不得改判为业务意图。
                只有询问“复诊要带什么材料”这类办理事项时才算业务意图。
                用户要求换科室、换时间时分别使用CHANGE_DEPARTMENT、CHANGE_TIME，不要被当前流程节点限制。
                可用号源只能从已知业务信息中理解，绝不能自行编造。未知字段填null。布尔字段只能是true、false或null。
                acknowledgement只简短确认你理解到的内容，不能声称已查询、已预约、已提醒或已通知，
                不能诊断、解读检查、推荐药物或调整剂量。
                """.formatted(context.currentDate(), context.stage(), context.knownFacts());
    }

    private String text(JsonNode node, String name, String fallbackValue) {
        String value = node.path(name).asText("").trim();
        return value.isEmpty() || "null".equalsIgnoreCase(value) ? fallbackValue : value;
    }

    private String nullableText(JsonNode node, String name) {
        String value = text(node, name, null);
        return value == null || value.isBlank() ? null : value;
    }

    private Boolean nullableBoolean(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.asBoolean();
    }

    private LocalDate date(JsonNode node) {
        try { return LocalDate.parse(nullableText(node, "date")); }
        catch (Exception ignored) { return null; }
    }

    private LocalTime time(JsonNode node) {
        try { return LocalTime.parse(nullableText(node, "selectedTime")); }
        catch (Exception ignored) { return null; }
    }
}
