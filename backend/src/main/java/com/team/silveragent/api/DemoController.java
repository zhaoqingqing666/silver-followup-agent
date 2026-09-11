package com.team.silveragent.api;

import com.team.silveragent.application.DemoScenarioService;
import com.team.silveragent.domain.model.DemoScenarioResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/demo")
public class DemoController {
    private final DemoScenarioService scenarios;

    public DemoController(DemoScenarioService scenarios) { this.scenarios = scenarios; }

    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "ok", "dataMode", "h2-mock-database"); }

    /**
     * 重置并返回一个可复现的演示会话。属于破坏性接口：
     * 会清空预约、提醒、通知、工具记录和全部会话，仅用于录屏与评审查验。
     */
    @PostMapping("/scenarios/{scenarioId}")
    public DemoScenarioResponse resetScenario(@PathVariable("scenarioId") String scenarioId) {
        return scenarios.reset(scenarioId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException error) {
        return Map.of("message", error.getMessage());
    }
}
