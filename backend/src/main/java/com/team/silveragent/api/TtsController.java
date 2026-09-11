package com.team.silveragent.api;

import com.team.silveragent.service.TtsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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
            return ResponseEntity.status(503).body(Map.of("ok", false, "message", "TTS 未启用"));
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
        if (speedObj instanceof Number) speed = ((Number) speedObj).doubleValue();
        else if (speedObj != null) {
            try { speed = Double.parseDouble(speedObj.toString()); }
            catch (NumberFormatException ignored) {}
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

    /** 可用音色列表（前端下拉框用） */
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
