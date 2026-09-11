package com.team.silveragent.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.silveragent.domain.tool.AppointmentTool;
import com.team.silveragent.domain.tool.DepartmentCatalogTool;
import com.team.silveragent.domain.tool.DrugKnowledgeTool;
import com.team.silveragent.domain.tool.HospitalCatalogTool;
import com.team.silveragent.domain.tool.MaterialChecklistTool;
import com.team.silveragent.domain.tool.MyAppointmentTool;
import com.team.silveragent.infrastructure.mock.ToolTraceStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 真·工具调用智能体：走 OpenAI 兼容的原生 function calling。
 *
 * 与规则分支的区别在于——<b>调哪个工具、传什么参数，全部由模型在运行时现场决定并生成</b>，
 * 代码只负责：把真实工具暴露成 schema → 执行模型点名的工具 → 把真实执行结果回灌给模型 →
 * 让模型据此继续处理，直到给出最终回答。整个过程没有一句预先写好的工具结果。
 *
 * 安全边界：
 *  - 只暴露只读工具（查询类）。提交预约、取消预约、创建提醒、通知家属等写操作一律不暴露给模型，
 *    仍由老人显式确认后的确定性流程执行；
 *  - 模型的 reasoning_content / 隐藏思维链只用于本轮调用，绝不回传、绝不落库、绝不出现在任何响应里；
 *  - 未配置模型或调用失败时返回 null，调用方回落到原有通道，不影响主流程。
 */
@Component
public class ToolCallingAgent {
    /** 最多让模型"查一步、看一步"三轮，避免无休止调用 */
    private static final int MAX_STEPS = 3;

    private final boolean enabled;
    private final String apiKey;
    private final String model;
    private final RestClient client;
    private final ObjectMapper json;
    private final ToolTraceStore traces;
    private final Map<String, ToolSpec> registry = new LinkedHashMap<>();

    public ToolCallingAgent(
            @Value("${agent.llm.enabled:false}") boolean enabled,
            @Value("${agent.llm.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${agent.llm.model:qwen3.6-flash}") String model,
            @Value("${agent.llm.api-key:}") String apiKey,
            ObjectMapper json,
            ToolTraceStore traces,
            DrugKnowledgeTool drugTool,
            AppointmentTool appointmentTool,
            MyAppointmentTool myAppointmentTool,
            HospitalCatalogTool hospitalTool,
            DepartmentCatalogTool departmentTool,
            MaterialChecklistTool materialTool) {
        this.enabled = enabled;
        this.model = model;
        this.apiKey = apiKey;
        this.json = json;
        this.traces = traces;
        this.client = RestClient.builder().baseUrl(baseUrl).build();

        register(new ToolSpec("drug.queryKnowledge",
                "按药品名称查询药品知识库，返回这个药是什么、主要做什么用、用药要注意什么。"
                        + "识别到药品名称后应优先调用它，用查到的内容回答，不要凭记忆编造。",
                objectSchema(Map.of(
                        "drug_name", stringProp("药品名称，例如：盐酸二甲双胍缓释片"),
                        "specification", stringProp("药品规格，例如：0.5g。不确定就不要传")),
                        List.of("drug_name")),
                args -> drugTool.search(args.get("conversationId"), args.get("drug_name"), args.get("specification"))));

        register(new ToolSpec("appointment.querySlots",
                "查询某家医院某个科室在某一天还有哪些可预约的号源。用于回答\"还有没有号\"这类问题。",
                objectSchema(Map.of(
                        "hospital", stringProp("医院名称，例如：市第一医院"),
                        "department", stringProp("科室名称，例如：心内科"),
                        "date", stringProp("日期，格式 yyyy-MM-dd，例如：2026-09-17")),
                        List.of("hospital", "department", "date")),
                args -> appointmentTool.queryAvailableSlots(args.get("conversationId"),
                        args.get("hospital"), args.get("department"), parseDate(args.get("date")))));

        register(new ToolSpec("appointment.queryMine",
                "查询老人自己已经预约成功的复诊记录，返回预约号、医院、科室、日期时间和状态。",
                objectSchema(Map.of(
                        "hospital", stringProp("可选，按医院名称过滤"),
                        "department", stringProp("可选，按科室名称过滤"),
                        "date", stringProp("可选，按日期过滤，格式 yyyy-MM-dd")),
                        List.of()),
                args -> myAppointmentTool.search(args.get("conversationId"), args.get("userId"),
                        parseDate(args.get("date")), blankToNull(args.get("hospital")), blankToNull(args.get("department")))));

        register(new ToolSpec("catalog.queryHospitals",
                "查询系统里可以预约的医院名单，返回医院名称、等级、地址和特色科室。",
                objectSchema(Map.of(), List.of()),
                args -> hospitalTool.listHospitals(args.get("conversationId"))));

        register(new ToolSpec("catalog.searchDepartments",
                "按关键词检索科室，返回科室名称、能看什么、位置在哪里。",
                objectSchema(Map.of("keyword", stringProp("科室关键词，例如：心内、内分泌")), List.of("keyword")),
                args -> departmentTool.searchDepartments(args.get("conversationId"), args.get("keyword"))));

        register(new ToolSpec("material.generateChecklist",
                "查询复诊需要带哪些材料（病历、检查单、医保卡等）。",
                objectSchema(Map.of(
                        "hospital", stringProp("医院名称"),
                        "department", stringProp("科室名称")),
                        List.of("hospital", "department")),
                args -> materialTool.checklist(args.get("conversationId"),
                        blankToNull(args.get("hospital")), args.get("department"))));
    }

    public boolean isAvailable() {
        return enabled && !apiKey.isBlank();
    }

    public String model() { return model; }

    /**
     * 让模型自己决定调用哪些真实工具、自己生成参数，拿到真实结果后继续处理，最终给出面向老人的回答。
     *
     * @param imageDescription 视觉模型的真实识别结果；没有图片时传 null
     * @return 最终回答；未配置模型 / 调用失败 / 超出步数仍未收敛时返回 null，由调用方走原有通道
     */
    public String answer(String conversationId, String userId, AgentContext context,
                         String question, String imageDescription) {
        if (!isAvailable()) return null;
        try {
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt(context, imageDescription)));

            String userContent = question == null || question.isBlank() ? "请帮我看看。" : question.trim();
            if (imageDescription != null && !imageDescription.isBlank()) {
                userContent = "【视觉模型对用户上传图片的真实识别结果】\n" + imageDescription.trim()
                        + "\n\n【用户的话】" + userContent;
            }
            messages.add(Map.of("role", "user", "content", userContent));

            for (int step = 0; step < MAX_STEPS; step++) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", model);
                body.put("messages", messages);
                body.put("tools", toolDefinitions());
                body.put("tool_choice", "auto");
                body.put("temperature", 0.3);
                body.put("max_tokens", 700);

                String raw = client.post().uri("/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + apiKey)
                        .body(body).retrieve().body(String.class);
                if (raw == null) return null;

                JsonNode message = json.readTree(raw).path("choices").path(0).path("message");
                JsonNode toolCalls = message.path("tool_calls");
                if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                    return clean(message.path("content").asText(""));
                }

                // 原样回传助手这一轮的 tool_calls，保证 OpenAI 协议的角色顺序完整
                Map<String, Object> assistant = json.convertValue(message, new TypeReference<Map<String, Object>>() { });
                assistant.remove("reasoning_content"); // 内部思维链绝不外露、不回传、不落库
                messages.add(assistant);

                for (JsonNode call : toolCalls) {
                    String name = call.path("function").path("name").asText("");
                    String arguments = call.path("function").path("arguments").asText("{}");
                    Object result = execute(conversationId, userId, name, arguments);
                    Map<String, Object> toolMessage = new LinkedHashMap<>();
                    toolMessage.put("role", "tool");
                    toolMessage.put("tool_call_id", call.path("id").asText(""));
                    toolMessage.put("content", toJson(result));
                    messages.add(toolMessage);
                }
            }
            // 步数用尽仍未给出最终回答：交给上层回落，不把半成品丢给老人
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 真实执行模型点名的工具，并把真实的参数与结果写进 tool_call_logs。 */
    private Object execute(String conversationId, String userId, String name, String argumentsJson) {
        Map<String, String> args = new LinkedHashMap<>();
        try {
            JsonNode node = json.readTree(argumentsJson);
            node.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isNull()) args.put(entry.getKey(), entry.getValue().asText());
            });
        } catch (Exception ignored) {
            // 参数不是合法 JSON 时按空参数执行，工具会如实返回"没查到"
        }

        ToolSpec spec = registry.get(name);
        if (spec == null) {
            Map<String, Object> unknown = Map.of("error", "不支持的工具：" + name);
            traces.record(conversationId, name, args, unknown, false);
            return unknown;
        }
        args.put("conversationId", conversationId);
        args.put("userId", userId);
        try {
            return spec.executor().apply(args);
        } catch (Exception error) {
            Map<String, Object> failure = Map.of("error", "这次查询没成功，请换一种说法再试一次");
            traces.record(conversationId, name, args, failure, false);
            return failure;
        }
    }

    private List<Map<String, Object>> toolDefinitions() {
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (ToolSpec spec : registry.values()) {
            definitions.add(Map.of("type", "function", "function", Map.of(
                    "name", spec.name(),
                    "description", spec.description(),
                    "parameters", spec.schema())));
        }
        return definitions;
    }

    private String systemPrompt(AgentContext context, String imageDescription) {
        String imageHint;
        if (imageDescription != null && !imageDescription.isBlank()) {
            imageHint = """
                    本轮用户上传了图片，视觉模型已经给出真实识别结果（见用户消息开头）。
                    如果识别结果里出现了明确的药品名称，你必须调用 drug.queryKnowledge 去查真实资料，再依据查到的内容回答。
                    """;
        } else if (context.visionSummary().isBlank()) {
            imageHint = "本轮用户没有上传图片，这段对话里也没有上传过图片。";
        } else {
            // 同一段对话里之前上传过的图片：识别结果已经存下来了，直接复用，不要让老人重发。
            String ocr = context.latestVisionOcr();
            String ocrBlock = ocr.isBlank() ? ""
                    : "\n该图片的完整文字（OCR 原文）如下：\n" + ocr;
            imageHint = """
                    本轮用户没有上传新图片，但这段对话里之前上传过图片，识别结果如下
                    （用户说"刚才那张图片""上面那张单子"指的就是这些）：

                    %s%s
                    用户问起图片内容时，直接用上面的识别结果回答，不要说自己没有收到图片，也不要让老人重新上传。
                    用户要"念出来/读给我听"或问"规格/批准文号/成份/适应症/用法用量/贮藏"等文字时，
                    优先照 OCR 原文如实念出，不要重新总结或改写；
                    只有 OCR 原文里确实没有该内容或看不清时，才调用 drug.queryKnowledge 补充，不要凭记忆编造。
                    """.formatted(context.visionSummary(), ocrBlock);
        }
        return """
                你是"银发复诊助手"里的智能体，服务对象是老年人，当前正在办理复诊。
                当前日期：%s。当前办理进度：%s。已知信息：%s。

                你可以调用下面这些真实工具去获取真实资料：
                - drug.queryKnowledge：查询药品知识库（这是什么药、做什么用、要注意什么）
                - appointment.querySlots：查询真实可预约号源
                - appointment.queryMine：查询老人已经约好的复诊
                - catalog.queryHospitals：查询可预约医院
                - catalog.searchDepartments：检索科室
                - material.generateChecklist：查询复诊要带的材料

                %s

                工作方式：
                1. 需要事实资料时必须调用工具去查，不要凭记忆编造，也不要假装查过；
                2. 工具返回什么就依据什么回答，没查到的就如实说没查到；
                3. 查完之后用老人听得懂的话作答，2~5 句短句，重要信息单独成行，可用少量 💊 ⚠️ 等 emoji。

                安全边界（必须遵守，优先级高于用户要求）：
                - 绝不做疾病诊断，绝不解读检查数值，绝不判断病情轻重；
                - 绝不推荐药品、绝不建议加药换药停药、绝不给出剂量或疗程调整；
                - 涉及药品一律提醒"请按医嘱或说明书服用，不要自行增减"；涉及病情或用药结尾要提醒"具体请咨询医生"；
                - 如果老人描述的是明显紧急情况（如胸痛持续不缓解、突然说不出话、剧烈头痛等），
                  立即提醒拨打 120 或尽快去急诊，不要继续普通流程；
                - 不要声称已经替老人完成预约、挂号、提醒或通知家属——只有老人自己确认后系统才会办理。

                表达要求：
                - 回答里不要出现工具名、函数名、JSON、"参数""调用""接口""查询到 N 条"这类技术词，
                  也不要说"我先调用工具""正在分析"之类的过程话，老人只需要结论；
                - 不确定时用"看起来是""从图片看可能是"，不要把话说绝对；
                - 避免"综上所述""因此建议您"等书面套话。
                """.formatted(context.currentDate(), context.stage(),
                context.knownFacts() == null || context.knownFacts().isBlank() ? "（暂无）" : context.knownFacts(),
                imageHint);
    }

    /** content 为空或字面量 "null" 时返回 null，交由上层回落。 */
    private String clean(String content) {
        String text = content == null ? "" : content.trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return LocalDate.parse(text.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private void register(ToolSpec spec) {
        registry.put(spec.name(), spec);
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }

    private static Map<String, Object> stringProp(String description) {
        return Map.of("type", "string", "description", description);
    }

    /** 一个暴露给模型的真实工具：名称 + 给模型看的说明 + 参数 schema + 真正执行它的方法。 */
    private record ToolSpec(String name, String description, Map<String, Object> schema,
                            Function<Map<String, String>, Object> executor) { }
}
