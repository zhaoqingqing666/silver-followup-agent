package com.team.silveragent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 百炼 Qwen3-VL-Plus 视觉理解服务。
 * 走 OpenAI 兼容 chat/completions 接口，content 为 image_url + text 混合列表。
 */
@Service
public class VlService {
    private static final Logger LOG = LoggerFactory.getLogger(VlService.class);

    /** 多图并发上限：一次最多 3 张图，正好一批并发跑完。 */
    private static final int MAX_CONCURRENT = 3;

    private static final String RECOGNIZE_SYSTEM_PROMPT = """
            你是一位帮老年人看复诊材料的多模态助手。请一次性完成下面三件事，只输出一个 JSON 对象：

            { "description": "...", "ocr": "...", "keyFacts": "..." }

            【ocr】—— 最重要，优先保证完整准确。
            把图片上出现的文字逐字照抄，不改写、不翻译、不总结、不遗漏数字和单位。
            - 按图片上的阅读顺序分行输出；表格按"项目：数值 单位（参考范围）"逐行列出；
            - 需要换行的地方直接换行；只提取图上真实存在的文字，看不清的字用 □ 占位，绝不编造；
            - 图片上确实没有文字（比如纯照片）时填空字符串。

            【description】—— 用通俗易懂的简体中文向老人说明两点：
            1. 这看起来是什么——尽量说清名称；是药就说药名，图上写有规格就带上规格（如"0.5克/片"）；
            2. 它主要是做什么用的——一句话即可，不展开医学解读。
            - 只做识别和归类，不诊断疾病、不解读检查数值、不判断病情轻重、不给用药或治疗建议；
            - 不确定时用"看起来是""从图片看可能是"，不要把话说绝对；
            - 图片模糊、反光、字看不清时如实说"看不太清"，不要编造名称或规格；
            - 两三句话说完，语气亲切，不要加标题。

            【keyFacts】—— 把对复诊最关键的信息摘成简短条目（药品名、规格、批准文号、用法用量、
            检查日期、异常数值等），每条一行、以"- "开头。只摘录图上真实写有的信息，没有就填空字符串。

            只输出 JSON 本身，不要输出任何其它文字，不要用 markdown 代码块包裹。
            """;

    private final boolean enabled;
    private final String apiKey;
    private final String model;
    private final RestClient client;
    private final ObjectMapper json;
    private final int maxRetries;

    public VlService(
            @Value("${agent.vl.enabled:false}") boolean enabled,
            @Value("${agent.vl.base-url:${agent.llm.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}}") String baseUrl,
            @Value("${agent.vl.model:qwen3-vl-plus}") String model,
            @Value("${agent.vl.api-key:${agent.llm.api-key:}}") String apiKey,
            @Value("${agent.vl.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${agent.vl.read-timeout-ms:60000}") int readTimeoutMs,
            @Value("${agent.vl.max-retries:2}") int maxRetries,
            ObjectMapper json) {
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.model = model;
        this.json = json;
        this.maxRetries = Math.max(0, maxRetries);
        // 没有超时的 RestClient 一旦对端卡住就会一直挂着，必须显式设置连接/读取超时。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** 一次视觉识别的完整结果：给老人看的描述 + 逐字照抄的 OCR 原文 + 关键信息摘要。 */
    public record VisionResult(String description, String ocr, String keyFacts) {
        public boolean isBlank() {
            return (description == null || description.isBlank()) && (ocr == null || ocr.isBlank());
        }
    }

    /**
     * OCR 识别图片内容（纯文字提取，供 VL 专用端点使用）。
     * @param imageDataUrl data URL，如 "data:image/png;base64,iVBOR..."
     * @param prompt       问模型的问题
     * @return 模型回复文本，失败返回 null
     */
    public String analyze(String imageDataUrl, String prompt) {
        String systemPrompt = """
                你是一个OCR工具，只做文字提取，不做任何解读、评价、建议或诊断。
                看到什么文字就原样输出什么文字，包括日期、数字、药品名、科室、检查项目等。
                不要添加任何额外信息，不要总结，不要评论，不要判断是否需要注意。""";
        return complete(systemPrompt, imageDataUrl,
                prompt == null ? "请提取图片中所有文字，原样输出" : prompt);
    }

    /**
     * 描述模式（复诊材料走对话问答时用）：用通俗中文说明图片是什么材料、
     * 做什么用、复诊时要不要带上，而不是 OCR 原样吐字。
     * @return 面向老人的总结性描述文本，失败/看不清返回 null
     */
    public String describe(String imageDataUrl, String userHint) {
        String systemPrompt = """
                你是一位帮老年人看复诊材料的助手。用户上传了一张和复诊有关的图片，
                可能是药盒/药瓶、病历、检查单、化验单、出院小结、收费票据、就诊卡等。
                请用通俗易懂的简体中文，向老人说明两点：
                1. 这看起来是什么——尽量说清名称；是药就说药名，图片里写有规格就带上规格（如"0.5克/片"）；
                2. 它主要是做什么用的——一句话即可，不展开医学解读。
                要求：
                - 只做识别和归类，不诊断疾病、不解读检查数值、不判断病情轻重、不给用药或治疗建议；
                - 不确定时用"看起来是""从图片看可能是"，不要把话说绝对；
                - 图片模糊、反光、字看不清时，如实说"看不太清"，不要编造名称或规格；
                - 两三句话说完，语气亲切，不要加标题、不要长篇大论。""";
        String promptText = (userHint == null || userHint.isBlank())
                ? "请告诉我这张图看起来是什么、主要做什么用。"
                : "用户还说：" + userHint.trim() + "。请结合这一点，告诉我这张图看起来是什么、主要做什么用。";
        return complete(systemPrompt, imageDataUrl, promptText);
    }

    /**
     * 一次请求同时拿到「描述 + OCR 原文 + 关键信息」，替代过去"每张图调两次视觉模型"的做法。
     * OCR 在提示词里排第一并强调逐字照抄，避免合并调用削弱文字准确率。
     *
     * @return 识别结果；失败返回 null（调用方按"看不清"处理）
     */
    public VisionResult recognize(String imageDataUrl, String userHint) {
        String promptText = (userHint == null || userHint.isBlank())
                ? "请按要求识别这张复诊材料。"
                : "用户还说：" + userHint.trim() + "。请结合这一点识别这张复诊材料。";
        String raw = execute(RECOGNIZE_SYSTEM_PROMPT, imageDataUrl, promptText,
                Map.of("response_format", Map.of("type", "json_object")));
        String content = extractContent(raw);
        if (content == null) return null;
        try {
            JsonNode node = json.readTree(stripCodeFence(content));
            String ocr = textOrEmpty(node, "ocr");
            String description = textOrEmpty(node, "description");
            String keyFacts = textOrEmpty(node, "keyFacts");
            // 模型偶尔会把整段识别结果放进 description 而不给 ocr，此时按 OCR 兜底，保证不丢字。
            if (ocr.isBlank() && !description.isBlank() && keyFacts.isBlank()) {
                return new VisionResult(description, description, "");
            }
            return new VisionResult(description, ocr, keyFacts);
        } catch (Exception e) {
            // JSON 解析失败：模型仍然看懂了图，把原文当 OCR 用，绝不因为格式问题丢掉识别内容。
            LOG.warn("VL 结构化识别结果解析失败，按纯 OCR 文本兜底：{}", e.getMessage());
            return new VisionResult(content, content, "");
        }
    }

    /**
     * 多张图并发识别：最多 3 张同批并发，单张失败返回 null 占位，不拖垮整体。
     * 返回顺序与入参一一对应。
     */
    public List<VisionResult> recognizeAll(List<String> imageDataUrls, String userHint) {
        List<VisionResult> results = new ArrayList<>();
        if (imageDataUrls == null || imageDataUrls.isEmpty()) return results;
        int size = imageDataUrls.size();
        if (!isEnabled()) {
            for (int i = 0; i < size; i++) results.add(null);
            return results;
        }

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(MAX_CONCURRENT, size), runnable -> {
            Thread thread = new Thread(runnable, "vl-recognize");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<VisionResult>> futures = new ArrayList<>(size);
            for (String url : imageDataUrls) {
                futures.add(pool.submit(() -> recognize(url, userHint)));
            }
            for (int i = 0; i < size; i++) {
                try {
                    results.add(futures.get(i).get());
                } catch (Exception e) {
                    LOG.warn("第 {} 张图片识别失败，跳过该图继续：{}", i + 1, e.getMessage());
                    results.add(null);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return results;
    }

    /** 发送一次补全请求并解析回复文本。失败返回 null。 */
    private String complete(String systemPrompt, String imageDataUrl, String promptText) {
        String raw = execute(systemPrompt, imageDataUrl, promptText, Map.of());
        return extractContent(raw);
    }

    /**
     * 真正发请求：带连接/读取超时，区分限流(429)、服务端错误(5xx)、网络/超时，可恢复的按退避重试。
     * @return 响应体原文；不可恢复或重试用尽返回 null
     */
    private String execute(String systemPrompt, String imageDataUrl, String promptText, Map<String, Object> extraBody) {
        if (!isEnabled() || imageDataUrl == null || promptText == null) return null;

        List<Map<String, Object>> content = List.of(
                Map.of("type", "image_url", "image_url", Map.of("url", imageDataUrl)),
                Map.of("type", "text", "text", promptText));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", content)));
        body.putAll(extraBody);

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0 && !backoff(attempt)) return null;
            try {
                return client.post().uri("/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + apiKey)
                        .body(body).retrieve().body(String.class);
            } catch (HttpClientErrorException e) {
                int code = e.getStatusCode().value();
                if (code == 429) {
                    LOG.warn("VL 被限流(429)，第 {}/{} 次尝试", attempt + 1, maxRetries + 1);
                    continue; // 限流是暂时的，退避后重试
                }
                LOG.warn("VL 请求被拒绝({})，不再重试：{}", code, e.getMessage());
                return null; // 参数/鉴权问题重试也不会好
            } catch (HttpServerErrorException e) {
                LOG.warn("VL 服务端错误({})，第 {}/{} 次尝试：{}",
                        e.getStatusCode().value(), attempt + 1, maxRetries + 1, e.getMessage());
            } catch (ResourceAccessException e) {
                LOG.warn("VL 网络或超时异常，第 {}/{} 次尝试：{}", attempt + 1, maxRetries + 1, e.getMessage());
            } catch (RestClientException e) {
                LOG.error("VL 请求失败：{}", e.getMessage());
                return null;
            } catch (Exception e) {
                LOG.error("VL 请求出现未预期异常", e);
                return null;
            }
        }
        LOG.warn("VL 请求重试 {} 次后仍然失败，放弃本次识别", maxRetries);
        return null;
    }

    /** 退避等待；被中断说明整体流程要停，返回 false 让调用方直接放弃。 */
    private boolean backoff(int attempt) {
        long millis = 600L * (long) Math.pow(3, attempt - 1);
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 从 OpenAI 兼容响应里取出 choices[0].message.content。 */
    private String extractContent(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            JsonNode node = json.readTree(raw).path("choices").path(0).path("message").path("content");
            String text = node.isTextual() ? node.asText() : node.toString();
            return text == null || text.isBlank() ? null : text.trim();
        } catch (Exception e) {
            LOG.warn("VL 响应解析失败：{}", e.getMessage());
            return null;
        }
    }

    private static String textOrEmpty(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").trim();
    }

    /** 模型偶尔会用 ```json 包裹，去掉外层代码块再解析。 */
    private static String stripCodeFence(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("```")) return trimmed;
        int firstLineEnd = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstLineEnd < 0 || lastFence <= firstLineEnd) return trimmed;
        return trimmed.substring(firstLineEnd + 1, lastFence).trim();
    }

    public boolean isEnabled() { return enabled && !apiKey.isBlank(); }

    public String model() { return model; }
}
