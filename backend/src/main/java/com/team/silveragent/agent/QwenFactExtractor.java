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

/**
 * 百炼 DashScope OpenAI 兼容模式的事实提取器。
 * 支持所有走 chat/completions 的模型（核心 Agent / ASR / VL 多模态等场景复用同一基类）。
 */
@Component
public class QwenFactExtractor implements FactExtractor {
    private final boolean enabled;
    private final String apiKey;
    private final String model;
    private final RestClient client;
    private final ObjectMapper json;
    private final RuleFactExtractor fallback;

    public QwenFactExtractor(
            @Value("${agent.llm.enabled:false}") boolean enabled,
            @Value("${agent.llm.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${agent.llm.model:qwen3.6-flash}") String model,
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
    public ExtractedFacts extract(String message, AgentContext context, boolean isVoice) {
        if (!enabled || apiKey.isBlank()) return fallback.extract(message, context, isVoice);
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt(context, isVoice)));
            for (AgentContext.Message item : context.recentMessages()) {
                messages.add(Map.of("role", item.role(), "content", item.content()));
            }
            messages.add(Map.of("role", "user", "content", message));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("response_format", Map.of("type", "json_object"));
            body.put("temperature", 0.1);
            // 多任务拆解 + 动态约束比原来的 13 个槽位要多写不少内容，上限给宽一点，
            // 免得复合需求被截断成半句话的 JSON。
            body.put("max_tokens", 800);

            String raw = client.post().uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body).retrieve().body(String.class);
            JsonNode root = json.readTree(raw);
            JsonNode facts = json.readTree(root.path("choices").path(0).path("message").path("content").asText("{}"));
            return parseFacts(facts);
        } catch (Exception ignored) {
            return fallback.extract(message, context, isVoice);
        }
    }

    /**
     * 把模型返回的 JSON 对象解析成 {@link ExtractedFacts}。
     *
     * <p>独立成包级静态方法是为了能在不联网、不配置 API Key 的情况下直接单测新增的多任务 /
     * 动态约束字段解析得对不对——解析细节不该只有真的调一次远端模型才能验证。
     */
    static ExtractedFacts parseFacts(JsonNode facts) {
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
                nullableText(facts, "acknowledgement"),
                tasks(facts),
                stringList(facts, "constraints"),
                stringList(facts, "preferences"),
                // 提示词里写的是下划线风格，但模型偶尔会按驼峰返回，两种都接住。
                stringList(facts, "additional_requests", "additionalRequests"),
                stringList(facts, "missing_information", "missingInformation"));
    }

    /**
     * tasks 既可能是对象数组（{"kind":..,"summary":..}），也可能被模型简写成字符串数组，
     * 两种都接住；字段缺失、不是数组、空数组一律返回空列表。
     */
    private static List<ExtractedFacts.TaskItem> tasks(JsonNode node) {
        JsonNode value = node.get("tasks");
        if (value == null || !value.isArray()) return List.of();
        List<ExtractedFacts.TaskItem> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (item.isTextual()) {
                String text = item.asText("").trim();
                if (text.isEmpty()) continue;
                // 模型偶尔把 {"kind":..,"summary":..} 简写成 ["预约查询","顺便问问用药"]。
                // 认得出的任务类型就当 kind，认不出的按「其他」类的说明留下，不丢。
                result.add(ExtractedFacts.TASK_KINDS.contains(text)
                        ? new ExtractedFacts.TaskItem(text, "")
                        : new ExtractedFacts.TaskItem(null, text));
                continue;
            }
            String kind = item.path("kind").asText("").trim();
            String summary = item.path("summary").asText("").trim();
            if (kind.isEmpty() && summary.isEmpty()) continue;
            result.add(new ExtractedFacts.TaskItem(kind.isEmpty() ? null : kind,
                    summary.isEmpty() ? kind : summary));
        }
        return result;
    }

    /**
     * 读一个字符串列表字段：支持真数组，也容忍模型把单个字符串当成一项返回。
     * 按 names 顺序找第一个存在的字段，找不到返回空列表。
     */
    private static List<String> stringList(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) continue;
            List<String> result = new ArrayList<>();
            if (value.isArray()) {
                for (JsonNode item : value) {
                    String text = item.isValueNode()
                            ? item.asText("").trim()
                            : item.path("summary").asText("").trim();
                    if (!text.isEmpty()) result.add(text);
                }
            } else if (value.isValueNode() && !value.asText("").isBlank()) {
                result.add(value.asText("").trim());
            }
            return result;
        }
        return List.of();
    }

    @Override
    public String mode() {
        if (!enabled || apiKey.isBlank()) return fallback.mode();
        return "QWEN_DASHSCOPE_CONTEXTUAL_WITH_RULE_FALLBACK";
    }

    public String model() { return model; }

    public boolean hasApiKey() { return !apiKey.isBlank(); }

    private String systemPrompt(AgentContext context, boolean isVoice) {
        String voiceRule = isVoice
                ? """
                本轮用户输入来自语音识别，可能存在口音、方言、同音歧义导致的识别错误。
                acknowledgement字段必须先复述你听到的用户原话（口语化），
                然后明确你理解到的关键信息（医院/科室/日期/时间等），
                最后简短确认"请问我理解得对吗？"让用户有机会纠正。
                示例：用户说"我想约市第一医院心内科下周三复诊"，
                acknowledgement写"我听到您说想约市第一医院心内科，下周三复诊。请问我理解得对吗？"
                """
                : "acknowledgement只简短确认你理解到的内容。";
        return """
                你是银发复诊协同智能体的语言理解节点，只输出一个JSON对象，不要Markdown。
                当前日期：%s。
                当前流程节点：%s。
                已知业务信息：%s。
                %s
                结合最近对话理解本轮用户真正意图，不要重复询问已知信息。
                JSON字段：
                intent：CREATE_FOLLOWUP、PROVIDE_INFORMATION、CHANGE_HOSPITAL、CHANGE_DATE、
                ASK_MATERIALS、CANCEL_TASK、CANCEL_APPOINTMENT、QUERY_APPOINTMENTS、
                QUERY_AVAILABLE_DATES、VIEW_TASKS、MEDICAL_ADVICE、HEALTH_ADVICE、EMERGENCY、UNKNOWN；
                hospital、department、date、acceptAlternative、needCompanion、needTravel、
                notifyFamily、transport、selectedTime、timePreference、acceptRecommendedTime、acknowledgement。
                tasks：本轮用户希望办理的全部事项，拆成任务数组，每项为{"kind":"...","summary":"..."}。
                kind只能取：预约查询、时间安排、材料准备、出行规划、家属通知、日程提醒、其他。
                一句话里有多件事就拆成多项，按用户说到的先后顺序排列；summary用一句简短中文说明这件事要做什么。
                用户同时问了别的事情（例如顺便问问用药、让帮忙记点别的），也要单独列成一项，kind填其他。
                用户只提出一件事时，tasks就只放一项。
                constraints：用户明确说出的硬性限制，保留用户原话里的说法（例如"必须上午""不能周三""国庆前"），没有就给空数组。
                preferences：用户表达的软性偏好，不是硬性要求（例如"人少一点""想找女医生"），没有就给空数组。
                additional_requests：用户提出、但不属于上面任何一种事项本身的补充诉求；绝不能因为不好归类就丢掉，没有就给空数组。
                missing_information：要把本轮用户提出的这些事项办完，还缺少的关键信息（例如"具体想约哪一天"），没有就给空数组。
                tasks、constraints、preferences、additional_requests、missing_information 这五个字段必须是数组，没有内容时给空数组[]。
                date必须为YYYY-MM-DD；用户没说年份时结合当前日期推断最近的未来日期。
                selectedTime为HH:mm。timePreference只能是MORNING、AFTERNOON或null。
                当流程为CONFIRM_SLOT，用户同意或拒绝推荐时间时设置acceptRecommendedTime。
                可用号源只能从已知业务信息中理解，绝不能自行编造。未知字段填null。布尔字段只能是true、false或null。
                当用户想查看已有的复诊预约/记录（如"我的预约""查预约""有哪些复诊""请告诉我就诊医院""我约的医院""预约信息"），intent=QUERY_APPOINTMENTS。
                当用户想查看最近有哪些可预约的日期/号源（如"最近哪天能预约""有哪些可预约日期""最近可以约什么时候"），intent=QUERY_AVAILABLE_DATES。
                当用户想打开复诊事项/任务页面（如"看看事项""打开任务""查看复诊事项"），intent=VIEW_TASKS。
                当用户询问自己病情/检查结果解读、或用药方法（如"药怎么吃""检查结果""是不是得了某病"），intent=MEDICAL_ADVICE。
                当用户询问健康科普、慢病调理、生活方式注意事项等一般性问题（如"高血压平时要注意什么""怎么预防""饮食上注意什么""吃什么好"），intent=HEALTH_ADVICE。
                %s
                不能声称已查询、已预约、已提醒或已通知，
                不能诊断、解读检查、推荐药物或调整剂量。
                """.formatted(context.currentDate(), context.stage(), context.knownFacts(),
                visionBlock(context), voiceRule);
    }

    /**
     * 本会话之前上传过的图片识别结果。用户后面说"刚才那张图片""提取图片上的文字"时，
     * 靠这段上下文接得住，而不是当成用户没发过图片。
     */
    private String visionBlock(AgentContext context) {
        if (context.visionSummary().isBlank()) return "";
        String ocr = context.latestVisionOcr();
        String ocrBlock = ocr.isBlank() ? ""
                : "\n该图片的完整文字（OCR 原文）如下：\n" + ocr;
        return "这段对话里之前上传过图片，识别结果如下（用户说的「刚才那张图片」指的就是这些）：\n"
                + context.visionSummary()
                + ocrBlock
                + "涉及图片的问题按上面的识别结果理解，不要认为用户没有发过图片。";
    }

    private static String text(JsonNode node, String name, String fallbackValue) {
        String value = node.path(name).asText("").trim();
        return value.isEmpty() || "null".equalsIgnoreCase(value) ? fallbackValue : value;
    }

    private static String nullableText(JsonNode node, String name) {
        String value = text(node, name, null);
        return value == null || value.isBlank() ? null : value;
    }

    private static Boolean nullableBoolean(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.asBoolean();
    }

    private static LocalDate date(JsonNode node) {
        try { return LocalDate.parse(nullableText(node, "date")); }
        catch (Exception ignored) { return null; }
    }

    private static LocalTime time(JsonNode node) {
        try { return LocalTime.parse(nullableText(node, "selectedTime")); }
        catch (Exception ignored) { return null; }
    }
}
