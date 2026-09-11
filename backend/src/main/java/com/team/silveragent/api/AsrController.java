package com.team.silveragent.api;

import com.team.silveragent.service.AsrService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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
            return ResponseEntity.status(503).body(Map.of("ok", false, "message", "ASR 未启用"));
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
