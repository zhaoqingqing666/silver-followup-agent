package com.team.silveragent.api;

import com.team.silveragent.agent.AgentContext;
import com.team.silveragent.agent.OpenReplyGenerator;
import com.team.silveragent.agent.QwenFactExtractor;
import com.team.silveragent.agent.ToolCallingAgent;
import com.team.silveragent.service.AsrService;
import com.team.silveragent.service.TtsService;
import com.team.silveragent.service.VlService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 排查用只读端点：一眼看清各模型通道到底有没有被启用、用的哪个模型。
 * 只报告"是否配置"，绝不返回密钥内容本身。
 *
 * GET /api/demo/channels          —— 只看配置状态
 * GET /api/demo/channels?probe=true —— 额外真实调用一次回复模型，确认模板通道真能通
 */
@RestController
@RequestMapping("/api/demo")
public class DiagnosticsController {
    private final OpenReplyGenerator openChat;
    private final ToolCallingAgent toolAgent;
    private final QwenFactExtractor extractor;
    private final VlService vlService;
    private final AsrService asrService;
    private final TtsService ttsService;

    public DiagnosticsController(OpenReplyGenerator openChat,
                                 ToolCallingAgent toolAgent,
                                 QwenFactExtractor extractor,
                                 VlService vlService,
                                 AsrService asrService,
                                 TtsService ttsService) {
        this.openChat = openChat;
        this.toolAgent = toolAgent;
        this.extractor = extractor;
        this.vlService = vlService;
        this.asrService = asrService;
        this.ttsService = ttsService;
    }

    @GetMapping("/channels")
    public Map<String, Object> channels(@RequestParam(name = "probe", defaultValue = "false") boolean probe) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("llmEnabled", openChat.isAvailable());
        out.put("understanding", channel(extractor.model(), openChat.isAvailable()));
        out.put("toolCalling", channel(toolAgent.model(), toolAgent.isAvailable()));
        out.put("openReply", channel(openChat.model(), openChat.isAvailable()));
        out.put("vision", channel(vlService.model(), vlService.isEnabled()));
        out.put("asr", channel(asrService.model(), asrService.isEnabled()));
        out.put("tts", channel(ttsService.model(), ttsService.isEnabled()));
        if (probe) out.put("probe", probeReply());
        return out;
    }

    private Map<String, Object> channel(String model, boolean available) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("available", available);
        item.put("model", model);
        return item;
    }

    /** 真实发一次最小请求，确认"回复模板"走的通道确实通；失败只报原因类别，不外泄密钥。 */
    private Map<String, Object> probeReply() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!openChat.isAvailable()) {
            result.put("ok", false);
            result.put("reason", "未启用：缺少 AGENT_LLM_ENABLED=true 或 DASHSCOPE_API_KEY");
            return result;
        }
        AgentContext context = new AgentContext("IDLE", "", LocalDate.now(), List.of());
        String reply = openChat.answer(context, "请回复两个字：收到", "PROVIDE_INFORMATION");
        result.put("ok", reply != null);
        if (reply == null) {
            result.put("reason", "已配置但调用失败：密钥无效、余额不足或网络不通");
        } else {
            result.put("reply", reply.length() > 80 ? reply.substring(0, 80) + "…" : reply);
        }
        return result;
    }
}
