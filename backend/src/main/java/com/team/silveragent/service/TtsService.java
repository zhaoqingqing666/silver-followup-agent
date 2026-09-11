package com.team.silveragent.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 百炼 Qwen3-TTS-Flash 语音合成服务。
 *
 * <p>注意：它走的是 DashScope <b>原生</b>端点
 * {@code /api/v1/services/aigc/multimodal-generation/generation}，不是 OpenAI 兼容的
 * {@code /chat/completions}，所以 base-url 必须单独配置 —— 复用 {@code AGENT_MODEL_BASE_URL}
 * （可能指向任何 OpenAI 兼容厂商）一定会打错端点。返回的是音频 URL 而不是音频字节。
 */
@Service
public class TtsService {
    private static final Logger LOG = LoggerFactory.getLogger(TtsService.class);

    private static final String LABEL = "TTS";

    private static final String SYNTHESIZE_PATH = "/api/v1/services/aigc/multimodal-generation/generation";

    private final boolean enabled;
    private final String apiKey;
    private final String model;
    private final String voice;
    private final double speed;
    private final RestClient client;
    private final DashScopeHttp http;
    private final int maxRetries;

    public TtsService(
            @Value("${agent.tts.enabled:false}") boolean enabled,
            @Value("${agent.tts.base-url:}") String baseUrl,
            @Value("${agent.tts.model:qwen3-tts-flash}") String model,
            @Value("${agent.tts.voice:Cherry}") String voice,
            @Value("${agent.tts.speed:1.0}") double speed,
            @Value("${agent.tts.api-key:}") String apiKey,
            @Value("${agent.tts.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${agent.tts.read-timeout-ms:30000}") int readTimeoutMs,
            @Value("${agent.tts.max-retries:1}") int maxRetries,
            DashScopeHttp http) {
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        this.voice = voice == null ? "" : voice.trim();
        this.speed = speed;
        this.http = http;
        this.maxRetries = Math.max(0, maxRetries);
        this.client = http.client(baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    /**
     * 将文本合成为语音。
     * @param text 要合成的文本（中文为主）
     * @return 音频 URL，失败返回 null
     */
    public String synthesize(String text) {
        return synthesize(text, null, null);
    }

    /**
     * 将文本合成为语音，支持覆盖音色和语速。
     * @param text  要合成的文本
     * @param voice 音色名，null 则用默认配置
     * @param speed 语速 0.5-2.0，null 则用默认配置
     * @return 音频 URL，失败返回 null
     */
    public String synthesize(String text, String voice, Double speed) {
        if (!isEnabled() || text == null || text.isBlank()) return null;
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("text", text);
        input.put("voice", (voice != null && !voice.isBlank()) ? voice : this.voice);
        double value = (speed != null) ? speed : this.speed;
        if (value != 1.0) input.put("speed", value);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", input);

        String raw = http.postJson(client, SYNTHESIZE_PATH, apiKey, body, maxRetries, LABEL);
        String url = audioUrl(http.tree(raw));
        if (url == null) LOG.warn("{} 未返回音频地址，前端将回落浏览器朗读", LABEL);
        return url;
    }

    /**
     * 从原生响应里取 {@code output.audio.url}。
     * 用 Jackson 按路径取值，不再手写 indexOf("url":"") —— 那种写法遇到字段顺序变化或转义就截错。
     */
    public static String audioUrl(JsonNode root) {
        if (root == null) return null;
        JsonNode url = root.path("output").path("audio").path("url");
        if (!url.isTextual()) return null;
        String text = url.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    public boolean isEnabled() { return enabled && !apiKey.isBlank(); }

    public String model() { return model; }

    public String voice() { return voice; }

    public double speed() { return speed; }
}
