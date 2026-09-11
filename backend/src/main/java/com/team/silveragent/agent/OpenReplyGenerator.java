package com.team.silveragent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真·模型回复通道：当用户问的是"问答类"问题（一般常识、天气、健康科普、系统能力等，
 * 不需要走预约办理流程）时，调用百炼 DashScope 让大模型直接现写给用户看的中文回复。
 * 只做回复文字生成，不做任何预约/写入。与事实提取（QwenFactExtractor）完全独立。
 *
 * 双轨设计：
 *  - 配置了 agent.llm.enabled=true 且有 api-key 时，本通道可用，回复由模型现写；
 *  - 未配置时 answer() 返回 null，上层自动回落到修好的规则模板，不影响测试。
 */
@Component
public class OpenReplyGenerator {
    private final boolean enabled;
    private final String apiKey;
    private final String model;
    private final RestClient client;
    private final ObjectMapper json;

    public OpenReplyGenerator(
            @Value("${agent.llm.enabled:false}") boolean enabled,
            @Value("${agent.llm.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${agent.llm.model:qwen3.6-flash}") String model,
            @Value("${agent.llm.api-key:}") String apiKey,
            ObjectMapper json) {
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.model = model;
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.json = json;
    }

    public boolean isAvailable() {
        return enabled && !apiKey.isBlank();
    }

    public String model() { return model; }

    /**
     * 用模型生成一段面向老人的中文回复。
     * 返回 null 表示：未配置模型 / 网络或解析失败 / 模型没有给出可用文字，此时应走规则兜底。
     */
    public String answer(AgentContext context, String question, String intent) {
        if (!isAvailable()) return null;
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt(context, intent)));
            // 只带上最近几轮，避免过长
            List<AgentContext.Message> recent = context.recentMessages();
            int from = Math.max(0, recent.size() - 4);
            for (int i = from; i < recent.size(); i++) {
                AgentContext.Message item = recent.get(i);
                messages.add(Map.of("role", item.role(), "content", item.content()));
            }
            messages.add(Map.of("role", "user", "content", question));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("temperature", 0.6);
            body.put("max_tokens", 700);

            String raw = client.post().uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body).retrieve().body(String.class);
            JsonNode root = json.readTree(raw);
            String content = root.path("choices").path(0).path("message").path("content").asText("").trim();
            return content.isEmpty() || "null".equalsIgnoreCase(content) ? null : content;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 图片问答协作通道：视觉模型先识别出 imageDescription，这里让核心理解模型结合当前
     * 办理进度，把结论整理成一句面向老人的总结性回复。失败/未配置返回 null，
     * 调用方应回落到视觉描述原文。
     */
    public String answerAboutImage(AgentContext context, String imageDescription, String userHint) {
        if (!isAvailable() || imageDescription == null || imageDescription.isBlank()) return null;
        try {
            String user = "视觉模型对用户上传的复诊图片的识别结果是：\n" + imageDescription.trim() + "\n";
            if (userHint != null && !userHint.isBlank()) user += "用户当时的原话：" + userHint.trim() + "。\n";
            user += "请按系统要求，向老人给出简洁、好懂、能照着做的回复。";

            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content", systemPromptAboutImage(context)),
                    Map.of("role", "user", "content", user));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("temperature", 0.4);
            body.put("max_tokens", 500);

            String raw = client.post().uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body).retrieve().body(String.class);
            JsonNode root = json.readTree(raw);
            String content = root.path("choices").path(0).path("message").path("content").asText("").trim();
            return content.isEmpty() || "null".equalsIgnoreCase(content) ? null : content;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String systemPromptAboutImage(AgentContext context) {
        return """
                你是"银发复诊助手"里负责看图回答的话务员，用户是老年人，正在办理复诊。
                当前日期：%s。当前流程节点：%s。已知信息：%s。

                下面给你视觉模型对用户上传图片的识别结果，请把它整理成老人看得懂、记得住、能照着做的回复。
                按这个顺序组织（内容简单时可自然合并成 2~4 句话，不必每项都出现，不要机械套标题）：
                ① 先直接说"这看起来是什么"（是药就说药名，图片里有规格就带上规格）；
                ② 用一句大白话说明它主要是做什么用的；
                ③ 说一个最重要的温馨提醒；
                ④ 如果和复诊有关，给一句简单的复诊建议，例如"复诊时带上，方便医生核对"。

                写作要求：
                - 简体中文、口语化、亲切，像医护人员当面给老人解释；
                - 2~5 句，句子尽量短，重要信息单独成行；可用 💊 ⚠️ 等少量 emoji，不要堆砌；
                - 不堆医学术语，必须用专业名时用大白话补一句解释；不说"AI识别""模型判断""您补充了"等过程话；
                - 不确定时用"看起来是""从图片看可能是"，不要把话说绝对；
                - 涉及药品：不根据图片推断剂量、疗程，不劝老人停药、换药、加药，可提醒"药量请按医嘱或说明书，别自行增减"；
                - 绝不根据一张图片诊断疾病、解读检查数值、判断病情轻重；
                - 识别结果是"看不太清"时，如实请老人重拍药盒正面或药品名称，不要编造；
                - 不要声称已经查询号源、预约或提醒——系统不会自动做这些；
                - 如果对话已办到某一步，把回复自然接在进度后面，不要突兀；
                - 避免"综上所述""因此建议您"等书面套话。
                """.formatted(context.currentDate(), context.stage(),
                context.knownFacts() == null ? "" : context.knownFacts());
    }

    /**
     * 本会话之前上传过的图片识别结果。用户后面说"刚才那张图片""提取图片上的文字"时，
     * 靠这段上下文接得住，而不是回答"没有收到图片"。
     */
    private String visionBlock(AgentContext context) {
        if (context.visionSummary().isBlank()) return "";
        String ocr = context.latestVisionOcr();
        String ocrBlock = ocr.isBlank() ? ""
                : "\n该图片的完整文字（OCR 原文）如下：\n" + ocr;
        return """

                这段对话里之前上传过图片，识别结果如下（用户说"刚才那张图片""上面那张单子"指的就是这些）：
                %s%s
                涉及图片内容时直接用上面的识别结果回答，不要说自己没有收到图片，也不要让老人重新上传。
                用户要"念出来/读给我听"或问"规格/批准文号/成份/适应症/用法用量/贮藏"等文字时，优先照 OCR 原文如实念出，不要重新总结或改写。
                """.formatted(context.visionSummary(), ocrBlock);
    }

    private String systemPrompt(AgentContext context, String intent) {
        return """
                你是"银发复诊助手"中负责直接回答用户一般问题的话务员，用户是老年人。
                当前日期：%s。当前流程节点：%s。已知信息：%s。
                系统判定本轮问题类型：%s。
                %s

                请直接回答，要求：
                - 用简体中文，口语化、亲切、简洁，一句能说清就不要说两句；
                - 只回答一般性问题（生活常识、天气、健康科普、就医分诊常识、对助手能力的说明等），
                  不涉及任何预约办理的具体执行（不要替用户决定医院、时间、是否通知家属等）；
                - 涉及健康、疾病、就诊时，只给常识性建议，绝不诊断疾病、绝不解读检查结果、
                  绝不推荐或调整药物与剂量，并且结尾必须加上一句
                  "以上为一般性参考，不能替代医生诊断与用药建议，请咨询专业医生"；
                - 但要分清"照着念"和"替人拿主意"：老人问"图片上写了什么""说明书上还写了什么"
                  "这个药是治什么的""一天吃几次"时，如果识别结果里有，就照原文如实说出来并告诉他
                  "以说明书和医嘱为准"——这是在念原文、帮他看清字，不属于诊断，不要拒绝；
                  只有当他让你替他决定吃几片、要不要停药换药、判断病情轻重、解读检查数值时，
                  才说"这个得听医生的，我不能替您拿主意"，并建议他问医生；
                - 不要声称你已经查询过号源、预约、提醒或通知——这些系统并不会自动做；
                - 如果确实答不上来，就直说不知道，并提醒"我主要帮您办理复诊预约，也可以为您预约复诊"。
                """.formatted(context.currentDate(), context.stage(), context.knownFacts(),
                intent == null ? "一般问题" : intent, visionBlock(context));
    }
}
