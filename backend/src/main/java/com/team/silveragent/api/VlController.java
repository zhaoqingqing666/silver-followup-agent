package com.team.silveragent.api;

import com.team.silveragent.service.VlService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/vl")
public class VlController {
    private final VlService vl;

    public VlController(VlService vl) { this.vl = vl; }

    /**
     * 视觉识别。
     * 请求体：{ "imageDataUrl": "data:image/png;base64,...", "prompt": "帮我看看这张检查单是否齐全" }
     */
    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyze(@RequestBody Map<String, String> body) {
        if (!vl.isEnabled()) {
            return ResponseEntity.status(503).body(Map.of("ok", false, "message", "VL 未启用"));
        }
        String imageDataUrl = body.get("imageDataUrl");
        String prompt = body.getOrDefault("prompt", "请描述这张图片");
        if (imageDataUrl == null || imageDataUrl.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "缺少 imageDataUrl 字段"));
        }
        String result = vl.analyze(imageDataUrl, prompt);
        if (result == null) {
            return ResponseEntity.status(502).body(Map.of("ok", false, "message", "视觉识别失败"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "result", result));
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("enabled", vl.isEnabled(), "model", vl.model());
    }
}
