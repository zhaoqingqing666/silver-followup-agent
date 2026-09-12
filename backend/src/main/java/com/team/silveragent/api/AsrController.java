package com.team.silveragent.api;

import com.team.silveragent.service.AsrService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 语音识别端点。未启用或识别失败时前端一律回落到浏览器原生识别，
 * 所以这里的失败不是致命错误，返回 503/502 让前端知道该走哪条路。
 */
@RestController
@RequestMapping("/api/asr")
public class AsrController {
    private final AsrService asr;

    public AsrController(AsrService asr) { this.asr = asr; }

    /**
     * 识别语音 → 返回文字。
     * 请求体：{ "audio": "base64...", "mimeType": "audio/webm" }
     */
    @PostMapping("/transcribe")
    public ResponseEntity<Map<String, Object>> transcribe(@RequestBody Map<String, String> body) {
        if (!asr.isEnabled()) {
            return ResponseEntity.status(503).body(Map.of("ok", false, "message", "语音识别未启用"));
        }
        String audio = body.get("audio");
        String mimeType = body.getOrDefault("mimeType", "audio/webm");
        if (audio == null || audio.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "缺少 audio 字段"));
        }
        String text = asr.recognize(audio, mimeType);
        if (text == null) {
            return ResponseEntity.status(502).body(Map.of("ok", false, "message", "语音识别失败"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "text", text));
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("enabled", asr.isEnabled(), "model", asr.model());
    }
}
