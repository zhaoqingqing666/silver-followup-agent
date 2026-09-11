package com.team.silveragent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.silveragent.service.DashScopeHttp;
import com.team.silveragent.service.TtsService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多模态通道的响应解析。
 *
 * <p>这两处正是手写 indexOf 最容易出错的地方：qwen3-asr 的 content 是数组形态，
 * 而 TTS 的音频地址藏在 output.audio.url 里。解析一旦截错，上层只会看到
 * 「识别失败 / 合成失败」，从日志里完全看不出是解析问题。
 */
class MultimodalParsingTests {
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode tree(String raw) throws Exception {
        return json.readTree(raw);
    }

    @Test
    void asrContentAsPlainString() throws Exception {
        JsonNode root = tree("""
                {"choices":[{"message":{"role":"assistant","content":"下周三上午去心内科复诊"}}]}""");
        assertThat(DashScopeHttp.contentText(root)).isEqualTo("下周三上午去心内科复诊");
    }

    @Test
    void asrContentAsArrayOfTextParts() throws Exception {
        // qwen3-asr-flash 实际返回的就是这个形态，手写 indexOf("\"content\":\"") 在这里取不到值。
        JsonNode root = tree("""
                {"choices":[{"message":{"role":"assistant",
                "content":[{"text":"帮我预约下周三的号"}]}}]}""");
        assertThat(DashScopeHttp.contentText(root)).isEqualTo("帮我预约下周三的号");
    }

    @Test
    void asrContentArrayJoinsMultipleParts() throws Exception {
        JsonNode root = tree("""
                {"choices":[{"message":{"content":[{"text":"血压"},{"text":"135"}]}}]}""");
        assertThat(DashScopeHttp.contentText(root)).isEqualTo("血压135");
    }

    @Test
    void contentEscapesAreDecodedByJackson() throws Exception {
        // 手写 replace 顺序一旦写错就会把 \n 变成字面量反斜杠 n，这里由 Jackson 负责转义。
        JsonNode root = tree("""
                {"choices":[{"message":{"content":"第一行\\n第二行「引号」"}}]}""");
        assertThat(DashScopeHttp.contentText(root)).isEqualTo("第一行\n第二行「引号」");
    }

    @Test
    void blankOrMissingContentIsNull() throws Exception {
        assertThat(DashScopeHttp.contentText(null)).isNull();
        assertThat(DashScopeHttp.contentText(tree("{}"))).isNull();
        assertThat(DashScopeHttp.contentText(tree("{\"choices\":[{\"message\":{\"content\":\"  \"}}]}"))).isNull();
    }

    @Test
    void ttsAudioUrlIsReadByJsonPath() throws Exception {
        JsonNode root = tree("""
                {"output":{"audio":{"url":"https://dashscope-result.oss-cn-beijing.aliyuncs.com/a/b.wav",
                "expires_at":"2026-09-11T12:00:00Z"}},"usage":{"characters":12}}""");
        assertThat(TtsService.audioUrl(root))
                .isEqualTo("https://dashscope-result.oss-cn-beijing.aliyuncs.com/a/b.wav");
    }

    @Test
    void ttsAudioUrlToleratesDifferentFieldOrderAndMissingAudio() throws Exception {
        JsonNode reordered = tree("""
                {"output":{"audio":{"expires_at":"2026-09-11T12:00:00Z","url":"https://x/y.wav"}}}""");
        assertThat(TtsService.audioUrl(reordered)).isEqualTo("https://x/y.wav");
        assertThat(TtsService.audioUrl(tree("{\"output\":{}}"))).isNull();
        assertThat(TtsService.audioUrl(tree("{\"code\":\"InvalidApiKey\"}"))).isNull();
        assertThat(TtsService.audioUrl(null)).isNull();
    }
}
