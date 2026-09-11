package com.team.silveragent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 百炼（DashScope）视觉 / 语音通道共用的 HTTP 薄客户端。
 *
 * <p>存在的意义是把三个模态各自容易写错的三件事收在一处：
 * <ol>
 *   <li><b>必须显式设超时。</b>没有超时的 RestClient 在对端卡住时会一直挂着，
 *       把一次「图片看不清」拖成整个会话线程卡死。</li>
 *   <li><b>可恢复错误才重试。</b>429 与服务端 5xx、网络超时才退避重试；
 *       4xx（密钥错、参数错）重试多少次都一样，立刻放弃并留下 WARN。</li>
 *   <li><b>content 有两种形态。</b>OpenAI 兼容接口的 {@code choices[0].message.content}
 *       既可能是字符串，也可能是 {@code [{"text": "..."}]} 数组（qwen3-asr 就是这样），
 *       手写 indexOf 截字符串会在数组形态下截出错误内容。</li>
 * </ol>
 *
 * <p>日志只记录状态码与响应片段，绝不打印 api-key，也不打印请求体（图片 / 音频 base64 太大且含隐私）。
 */
@Component
public class DashScopeHttp {
    private static final Logger LOG = LoggerFactory.getLogger(DashScopeHttp.class);

    /** 退避基数：第 1 次重试等 600ms，第 2 次 1800ms，第 3 次 5400ms。 */
    private static final long BACKOFF_BASE_MS = 600L;

    private final ObjectMapper json;

    /**
     * RestClient 线程安全，且内部持有连接池；按 (baseUrl, 超时) 复用同一个即可，
     * 每次请求都 new 一个纯属白造连接池。
     */
    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

    public DashScopeHttp(ObjectMapper json) {
        this.json = json;
    }

    /** 取（或首次创建）指定 baseUrl 与超时的客户端。 */
    public RestClient client(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
        String key = baseUrl + "|" + connectTimeoutMs + "|" + readTimeoutMs;
        return clients.computeIfAbsent(key, ignored -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofMillis(Math.max(500, connectTimeoutMs)));
            factory.setReadTimeout(Duration.ofMillis(Math.max(1000, readTimeoutMs)));
            RestClient.Builder builder = RestClient.builder().requestFactory(factory);
            if (baseUrl != null && !baseUrl.isBlank()) builder.baseUrl(baseUrl.trim().replaceAll("/+$", ""));
            return builder.build();
        });
    }

    /**
     * 发一次 JSON POST，失败按需退避重试。
     *
     * @param label 只用于日志的通道名，如 "VL" / "ASR" / "TTS"
     * @return 响应体原文；不可恢复的错误、重试用尽或未配置 key 时返回 null
     */
    public String postJson(RestClient client, String path, String apiKey, Object body,
                           int maxRetries, String label) {
        if (apiKey == null || apiKey.isBlank()) return null;
        int attempts = Math.max(0, maxRetries) + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1 && !backoff(attempt - 1)) return null;
            try {
                return client.post().uri(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + apiKey)
                        .body(body).retrieve().body(String.class);
            } catch (HttpClientErrorException e) {
                int code = e.getStatusCode().value();
                if (code == 429) {
                    LOG.warn("{} 被限流(429)，第 {}/{} 次尝试", label, attempt, attempts);
                    continue; // 限流是暂时的，退避后重试
                }
                LOG.warn("{} 请求被拒绝({})，不再重试：{}", label, code, snippet(e.getResponseBodyAsString()));
                return null; // 参数 / 鉴权问题重试也不会好
            } catch (HttpServerErrorException e) {
                LOG.warn("{} 服务端错误({})，第 {}/{} 次尝试：{}",
                        label, e.getStatusCode().value(), attempt, attempts, snippet(e.getResponseBodyAsString()));
            } catch (ResourceAccessException e) {
                LOG.warn("{} 网络或超时异常，第 {}/{} 次尝试：{}", label, attempt, attempts, e.getMessage());
            } catch (RestClientException e) {
                LOG.error("{} 请求失败：{}", label, e.getMessage());
                return null;
            } catch (Exception e) {
                LOG.error("{} 请求出现未预期异常", label, e);
                return null;
            }
        }
        LOG.warn("{} 请求重试 {} 次后仍然失败，放弃本次调用", label, Math.max(0, maxRetries));
        return null;
    }

    /** 退避等待；被中断说明整体流程要停，返回 false 让调用方直接放弃。 */
    private boolean backoff(int retryIndex) {
        long millis = BACKOFF_BASE_MS * (long) Math.pow(3, Math.max(0, retryIndex - 1));
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 解析响应体；解析不了返回 null（调用方按「没看懂」处理）。 */
    public JsonNode tree(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            LOG.warn("响应不是合法 JSON：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 取 {@code choices[0].message.content} 的文本。
     * 兼容两种形态：字符串，或 {@code [{"text": "..."}]} 数组（qwen3-asr 的返回就是数组）。
     * 转义一律交给 Jackson，不再手写 replace("\\n", "\n")。
     */
    public static String contentText(JsonNode root) {
        if (root == null) return null;
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) return null;
        if (content.isTextual()) return blankToNull(content.asText());
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : content) {
                if (part.isTextual()) text.append(part.asText());
                else if (part.hasNonNull("text")) text.append(part.path("text").asText());
                else if (part.hasNonNull("content")) text.append(part.path("content").asText());
            }
            return blankToNull(text.toString());
        }
        return blankToNull(content.toString());
    }

    /** 日志里只放响应摘要，避免整段 base64 或超长 body 灌进日志。 */
    private static String snippet(String body) {
        if (body == null) return "";
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "…" : oneLine;
    }

    private static String blankToNull(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
