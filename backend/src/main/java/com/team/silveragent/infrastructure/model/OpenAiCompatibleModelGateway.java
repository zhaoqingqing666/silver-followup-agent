package com.team.silveragent.infrastructure.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.silveragent.agent.model.ModelGateway;
import com.team.silveragent.agent.model.ModelRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class OpenAiCompatibleModelGateway implements ModelGateway {
    private final boolean enabled;
    private final boolean jsonModeSupported;
    private final boolean apiKeyRequired;
    private final String provider;
    private final String model;
    private final String apiKey;
    private final String baseUrl;
    private final RestClient client;
    private final ObjectMapper json;

    public OpenAiCompatibleModelGateway(
            @Value("${agent.model.enabled:false}") boolean enabled,
            @Value("${agent.model.provider:openai-compatible}") String provider,
            @Value("${agent.model.base-url:}") String baseUrl,
            @Value("${agent.model.model:}") String model,
            @Value("${agent.model.api-key:}") String apiKey,
            @Value("${agent.model.api-key-required:true}") boolean apiKeyRequired,
            @Value("${agent.model.json-mode-supported:true}") boolean jsonModeSupported,
            @Value("${agent.model.connect-timeout-ms:2500}") int connectTimeoutMs,
            @Value("${agent.model.read-timeout-ms:12000}") int readTimeoutMs,
            ObjectMapper json) {
        this.enabled = enabled;
        this.provider = provider;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.model = model == null ? "" : model.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.jsonModeSupported = jsonModeSupported;
        this.apiKeyRequired = apiKeyRequired;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(Math.max(500, connectTimeoutMs)));
        requestFactory.setReadTimeout(Duration.ofMillis(Math.max(1000, readTimeoutMs)));
        RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory);
        if (!this.baseUrl.isBlank()) builder.baseUrl(this.baseUrl);
        this.client = builder.build();
        this.json = json;
    }

    @Override
    public String complete(ModelRequest request) {
        if (!available()) throw new IllegalStateException("大模型服务未启用或配置不完整");
        // 这个模型会间歇性把回答吐成纯空白：思维链完整、finish_reason=stop、content 只剩空格。
        // 同一条请求重发通常就能恢复，所以先重试两次，三次都拿不到内容才判失败。
        IllegalStateException blank = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return completeOnce(request);
            } catch (BlankContentException error) {
                blank = error;
            }
        }
        throw blank;
    }

    private String completeOnce(ModelRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", request.messages());
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());
        if (request.jsonOutput() && jsonModeSupported) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        var call = client.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON);
        if (!apiKey.isBlank()) call.header("Authorization", "Bearer " + apiKey);
        String raw = call.body(body).retrieve().body(String.class);
        JsonNode root;
        try {
            root = json.readTree(raw);
        } catch (Exception error) {
            throw new IllegalStateException("无法读取大模型返回内容", error);
        }
        String content = root.path("choices").path(0).path("message").path("content").asText("").trim();
        if (content.isBlank()) {
            // 空内容是会反复出现的故障，必须留下 finish_reason、usage 和响应体，否则只能靠猜。
            // 这段检查必须放在 try 之外：放进 try 会被下面的 catch 重新包装，诊断信息全丢。
            throw new BlankContentException("大模型没有返回内容 | finish_reason="
                    + root.path("choices").path(0).path("finish_reason").asText("?")
                    + " usage=" + root.path("usage")
                    + " raw=" + raw.substring(0, Math.min(400, raw.length())));
        }
        return content;
    }

    /** 空内容专用内部异常，只用来触发重试。 */
    private static class BlankContentException extends IllegalStateException {
        BlankContentException(String message) {
            super(message);
        }
    }

    @Override
    public boolean available() {
        return enabled && !baseUrl.isBlank() && !model.isBlank() && (!apiKeyRequired || !apiKey.isBlank());
    }

    @Override public String providerName() { return provider; }
    @Override public String modelName() { return model; }
}
