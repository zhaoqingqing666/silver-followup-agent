package com.team.silveragent.api;

import com.team.silveragent.service.TtsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 语音合成端点。前端<b>优先用浏览器本地朗读</b>，只有在没有中文音色时才回落到这里，
 * 所以未启用不是问题，返回 503 前端会自己走本地路径。
 */
@RestController
@RequestMapping("/api/tts")
public class TtsController {
    private final TtsService tts;

    public TtsController(TtsService tts) { this.tts = tts; }

    /**
     * 文字 → 语音。
     * 请求体：{ "text": "...", "voice": "Cherry" (可选), "speed": 1.2 (可选, 0.5-2.0) }
     * 返回：{ "ok": true, "audioUrl": "https://..." }
     */
    @PostMapping("/synthesize")
    public ResponseEntity<Map<String, Object>> synthesize(@RequestBody Map<String, Object> body) {
        if (!tts.isEnabled()) {
            return ResponseEntity.status(503).body(Map.of("ok", false, "message", "云端语音合成未启用"));
        }
        Object textObj = body.get("text");
        String text = textObj == null ? null : textObj.toString();
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "缺少 text 字段"));
        }
        Object voiceObj = body.get("voice");
        String voice = voiceObj == null ? null : voiceObj.toString();

        Object speedObj = body.get("speed");
        Double speed = null;
        if (speedObj instanceof Number number) speed = number.doubleValue();
        else if (speedObj != null) {
            try { speed = Double.parseDouble(speedObj.toString()); }
            catch (NumberFormatException ignored) { /* 传了看不懂的语速就用默认值，不值得报错 */ }
        }

        String url = tts.synthesize(text, voice, speed);
        if (url == null) {
            return ResponseEntity.status(502).body(Map.of("ok", false, "message", "语音合成失败"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "audioUrl", url));
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("enabled", tts.isEnabled(), "model", tts.model(), "voice", tts.voice(), "speed", tts.speed());
    }

    /** 可用音色列表（前端下拉框用）。 */
    @GetMapping("/voices")
    public Map<String, Object> voices() {
        return Map.of("voices", new Object[][] {
                {"Cherry", "芊悦 · 阳光甜美小姐姐 (推荐)"},
                {"Serena", "苏瑶 · 温柔小姐姐"},
                {"Chelsie", "千雪 · 二次元虚拟女友"},
                {"Momo", "茉兔 · 撒娇搞怪"},
                {"Vivian", "十三 · 可爱小暴躁"},
                {"Maia", "四月 · 知性温柔"},
                {"Ethan", "晨煦 · 阳光男声"},
                {"Moon", "月白 · 帅气男声"},
        });
    }
}
