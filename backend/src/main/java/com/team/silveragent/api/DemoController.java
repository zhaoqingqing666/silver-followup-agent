package com.team.silveragent.api;

import com.team.silveragent.agent.model.ModelGateway;
import com.team.silveragent.agent.model.ModelRequest;
import com.team.silveragent.application.DemoScenarioService;
import com.team.silveragent.domain.model.DemoScenarioResponse;
import com.team.silveragent.service.AsrService;
import com.team.silveragent.service.TtsService;
import com.team.silveragent.service.VlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/demo")
public class DemoController {
    private static final Logger LOG = LoggerFactory.getLogger(DemoController.class);

    /**
     * 自检用的 token 预算，不能给太小：deepseek-v4-flash 这类推理模型会先输出思考内容，
     * 预算被思考链吃光时正文就是空的。曾经写死 16，结果把一条完全正常的通道报成「密钥无效」。
     */
    private static final int PROBE_MAX_TOKENS = 512;

    private final ModelGateway modelGateway;
    private final VlService vlService;
    private final AsrService asrService;
    private final TtsService ttsService;
    private final DemoScenarioService scenarios;

    public DemoController(ModelGateway modelGateway, VlService vlService,
                          AsrService asrService, TtsService ttsService,
                          DemoScenarioService scenarios) {
        this.modelGateway = modelGateway;
        this.vlService = vlService;
        this.asrService = asrService;
        this.ttsService = ttsService;
        this.scenarios = scenarios;
    }

    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "ok", "dataMode", "h2-mock-database"); }

    /**
     * 重置并返回一个可复现的演示会话。属于<b>破坏性</b>接口：清空预约、提醒、通知、工具记录
     * 与全部会话（含图片附件、健康记录、备忘与长期记忆），只用于录屏与评审查验。
     *
     * <p>可选编号：{@code normal} / {@code no-slot} / {@code conflict} / {@code boundary}，
     * 在 {@code DemoScenario} 里维护；返回的 {@code steps} 是照着念就能复现的步骤，
     * 里面的日期每次都按「今天」现算。
     */
    @PostMapping("/scenarios/{scenarioId}")
    public DemoScenarioResponse resetScenario(@PathVariable("scenarioId") String scenarioId) {
        return scenarios.reset(scenarioId);
    }

    /** 未知编号回 400 并说明可选值：手敲 curl 时最常犯的就是拼错编号。 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException error) {
        return Map.of("message", error.getMessage());
    }

    /**
     * 排查用只读端点：一眼看清各模型通道到底有没有被启用、用的哪个模型。
     *
     * <p>只报告「是否配置」，绝不返回密钥内容本身 —— 没配 key 时看到的是
     * {@code available:false}，而不是任何一串看起来像密钥的字符。
     *
     * <p>{@code ?probe=true} 会真实调用一次回复模型，确认通道确实通；
     * 这会给云端发一条最小请求，只在排查时用。
     */
    @GetMapping("/channels")
    public Map<String, Object> channels(@RequestParam(name = "probe", defaultValue = "false") boolean probe) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agentLlm", channel(modelGateway.modelName(), modelGateway.available()));
        out.put("vision", channel(vlService.model(), vlService.isEnabled()));
        out.put("asr", channel(asrService.model(), asrService.isEnabled()));
        out.put("tts", channel(ttsService.model(), ttsService.isEnabled()));
        if (probe) out.put("probe", probeReply());
        return out;
    }

    private Map<String, Object> channel(String model, boolean available) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("available", available);
        item.put("model", model == null || model.isBlank() ? "（未配置）" : model);
        return item;
    }

    /** 真实发一次最小请求，确认回复模型通道确实通；失败只报原因类别，不外泄密钥。 */
    private Map<String, Object> probeReply() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!modelGateway.available()) {
            result.put("ok", false);
            result.put("reason", "未启用：缺少 AGENT_MODEL_ENABLED=true 或模型地址与密钥");
            return result;
        }
        try {
            String trimmed = modelGateway.complete(new ModelRequest(
                    List.of(new ModelRequest.Message("user", "请回复两个字：收到")),
                    false, PROBE_MAX_TOKENS, 0.0)).trim();
            result.put("ok", true);
            result.put("reply", trimmed.length() > 80 ? trimmed.substring(0, 80) + "…" : trimmed);
        } catch (Exception e) {
            // 上游报错原文里可能带着被上游脱敏过的密钥（如 "Your api key: sk-abc… is invalid"），
            // 所以只落在服务端日志，并且写之前再抹一遍；对外一律只回原因类别，不回显上游原文。
            LOG.warn("自检调用回复模型失败：{} | {}", e.getClass().getSimpleName(), redact(e.getMessage()));
            result.put("ok", false);
            result.put("reason", blankContent(e)
                    ? "已配置但模型返回空内容：密钥和网络是通的，是模型没吐出正文（推理模型思考占满预算或上游限流）；细节见服务端日志"
                    : "调用抛异常：密钥无效、余额不足或网络不通；细节见服务端日志");
        }
        return result;
    }

    /** 空内容是独立的一类故障：密钥与网络都通，只是模型没返回正文，不能报成「密钥无效」。
     *  这句话来自 {@code OpenAiCompatibleModelGateway} 的空内容异常，是唯一能识别它的线索。 */
    private static boolean blankContent(Exception error) {
        return String.valueOf(error.getMessage()).contains("没有返回内容");
    }

    /** 上游返回体可能带上被脱敏过的密钥片段，写日志前再抹一遍。 */
    private static String redact(String message) {
        return message == null ? "" : message.replaceAll("sk-[A-Za-z0-9._-]{4,}", "sk-***");
    }
}
