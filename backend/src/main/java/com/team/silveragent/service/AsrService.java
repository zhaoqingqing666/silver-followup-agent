package com.team.silveragent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 百炼 Qwen3-ASR-Flash 语音识别服务。
 * 走 OpenAI 兼容 chat/completions 接口，输入为 base64 音频。
 */
@Service
public class AsrService {
    private static final Logger LOG = LoggerFactory.getLogger(AsrService.class);

    private static final String LABEL = "ASR";

    private final boolean enabled;
    private final String apiKey;
    private final String model;
    private final RestClient client;
    private final DashScopeHttp http;
    private final int maxRetries;

    public AsrService(
            @Value("${agent.asr.enabled:false}") boolean enabled,
            @Value("${agent.asr.base-url:}") String baseUrl,
            @Value("${agent.asr.model:qwen3-asr-flash}") String model,
            @Value("${agent.asr.api-key:}") String apiKey,
            @Value("${agent.asr.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${agent.asr.read-timeout-ms:30000}") int readTimeoutMs,
            @Value("${agent.asr.max-retries:1}") int maxRetries,
            DashScopeHttp http) {
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        this.http = http;
        this.maxRetries = Math.max(0, maxRetries);
        // 一段按住说话的录音只有几秒，30s 还识别不完就是有问题，宁可让前端回落浏览器识别。
        this.client = http.client(baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    /**
     * 识别 base64 编码的音频数据。
     * @param base64Data base64 编码音频（不含 data: 前缀）
     * @param mimeType 音频 MIME 类型，如 audio/webm, audio/wav, audio/mp3
     * @return 识别出的文本，失败返回 null（调用方按「听不清」处理，不是错误）
     */
    public String recognize(String base64Data, String mimeType) {
        if (!isEnabled() || base64Data == null || base64Data.isBlank()) return null;
        String mime = (mimeType == null || mimeType.isBlank()) ? "audio/webm" : mimeType.trim();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of(
                "role", "user",
                "content", List.of(Map.of(
                        "type", "input_audio",
                        "input_audio", "data:" + mime + ";base64," + base64Data)))));
        try {
            String raw = http.postJson(client, "/chat/completions", apiKey, body, maxRetries, LABEL);
            // qwen3-asr 的 content 是 [{"text": "..."}] 数组形态，取值统一走 contentText。
            String text = DashScopeHttp.contentText(http.tree(raw));
            if (text == null) LOG.warn("{} 返回内容为空或解析不出文本", LABEL);
            return text;
        } catch (Exception e) {
            // 识别失败按「听不清」返回 null，但必须留下原因，否则线上只能靠猜。
            LOG.warn("{} 识别失败：{}", LABEL, e.getMessage());
            return null;
        }
    }

    public boolean isEnabled() { return enabled && !apiKey.isBlank(); }

    public String model() { return model; }
}
