package com.team.silveragent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 百炼 Qwen3-TTS-Flash 语音合成服务。
 * 走 DashScope multimodal-generation 接口，返回音频 URL。
 * 支持可选的 voice（音色）和 speed（语速，0.5-2.0）覆盖默认值。
 */
@Service
public class TtsService {
    private final boolean enabled;
    private final String apiKey;
    private final String model;
    private final String voice;
    private final double speed;
    private final RestClient client;

    public TtsService(
            @Value("${agent.tts.enabled:false}") boolean enabled,
            @Value("${agent.tts.base-url:https://dashscope.aliyuncs.com}") String baseUrl,
            @Value("${agent.tts.model:qwen3-tts-flash}") String model,
            @Value("${agent.tts.voice:Cherry}") String voice,
            @Value("${agent.tts.speed:1.0}") double speed,
            @Value("${agent.tts.api-key:${agent.llm.api-key:}}") String apiKey) {
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.model = model;
        this.voice = voice;
        this.speed = speed;
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * 将文本合成为语音。
     * @param text  要合成的文本（中文为主）
     * @return 音频 URL，失败返回 null
     */
    public String synthesize(String text) {
        return synthesize(text, null, null);
    }

    /**
     * 将文本合成为语音，支持覆盖音色和语速。
     * @param text     要合成的文本
     * @param voice    音色名，null 则用默认配置
     * @param speed    语速 0.5-2.0，null 则用默认配置
     * @return 音频 URL，失败返回 null
     */
    public String synthesize(String text, String voice, Double speed) {
        if (!enabled || apiKey.isBlank() || text == null || text.isBlank()) return null;
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("text", text);
            input.put("voice", (voice != null && !voice.isBlank()) ? voice : this.voice);
            double s = (speed != null) ? speed : this.speed;
            if (s != 1.0) input.put("speed", s);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("input", input);

            String raw = client.post().uri("/api/v1/services/aigc/multimodal-generation/generation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body).retrieve().body(String.class);

            // 简单解析：提取 output.audio.url
            int urlStart = raw.indexOf("\"audio\":");
            if (urlStart < 0) return null;
            int urlContentStart = raw.indexOf("\"url\":\"", urlStart);
            if (urlContentStart < 0) return null;
            int start = urlContentStart + 7;
            int end = raw.indexOf("\"", start);
            if (end < 0) return null;
            return raw.substring(start, end).replace("\\/", "/");
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isEnabled() { return enabled && !apiKey.isBlank(); }

    public String model() { return model; }

    public String voice() { return voice; }

    public double speed() { return speed; }
}
