package com.team.silveragent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 百炼 Qwen3-ASR-Flash 语音识别服务。
 * 走 OpenAI 兼容 chat/completions 接口，输入为 base64 音频。
 */
@Service
public class AsrService {
    private final boolean enabled;
    private final String apiKey;
    private final String model;
    private final RestClient client;

    public AsrService(
            @Value("${agent.asr.enabled:false}") boolean enabled,
            @Value("${agent.asr.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${agent.asr.model:qwen3-asr-flash}") String model,
            @Value("${agent.asr.api-key:${agent.llm.api-key:}}") String apiKey) {
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.model = model;
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * 识别 base64 编码的音频数据。
     * @param base64Data base64 编码音频（不含 data: 前缀）
     * @param mimeType 音频 MIME 类型，如 audio/webm, audio/wav, audio/mp3
     * @return 识别出的文本，失败返回 null
     */
    public String recognize(String base64Data, String mimeType) {
        if (!enabled || apiKey.isBlank()) return null;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", List.of(Map.of(
                    "role", "user",
                    "content", List.of(Map.of(
                            "type", "input_audio",
                            "input_audio", "data:" + mimeType + ";base64," + base64Data)))));

            String raw = client.post().uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body).retrieve().body(String.class);

            // 简单解析：提取 choices[0].message.content
            int contentStart = raw.indexOf("\"content\":\"");
            if (contentStart < 0) return null;
            int start = contentStart + 11;
            int end = raw.indexOf("\"", start);
            if (end < 0) return null;
            return raw.substring(start, end)
                    .replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isEnabled() { return enabled && !apiKey.isBlank(); }

    public String model() { return model; }
}
