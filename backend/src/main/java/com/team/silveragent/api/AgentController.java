package com.team.silveragent.api;

import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.application.memory.MemoryStore;
import com.team.silveragent.application.TurnProgress;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.model.ConversationHistoryResponse;
import com.team.silveragent.domain.model.ConversationSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private final FollowupAgentService service;

    public AgentController(FollowupAgentService service) { this.service = service; }

    /**
     * 建立会话。
     * userId 是本次要服务的<b>就诊人</b>，actorId 是真正在操作的人。
     * 两者不同即代他人办理（家属/志愿者端），后端会查 care_relations 校验；查不到关系就拒绝。
     * actorId 省略时按本人自办处理，老人端现有调用不受影响。
     */
    @PostMapping("/conversations")
    public AgentTurnResponse start(@RequestParam(value = "userId", required = false) String userId,
                                   @RequestParam(value = "actorId", required = false) String actorId) {
        return service.start(userId, actorId);
    }

    @GetMapping("/conversations/{conversationId}")
    public ConversationHistoryResponse resume(@PathVariable("conversationId") String conversationId) {
        return service.resume(conversationId);
    }

    @PostMapping("/messages")
    public AgentTurnResponse chat(@Valid @RequestBody MessageRequest request) {
        return service.chat(request.conversationId(), request.message());
    }

    /**
     * 拍照 / 选图后的一轮。图片走的是和 {@code /messages} 完全同一条主链路：
     * 识别结论作为上下文交给同一个主模型，写操作仍然只能由确认门禁放行。
     *
     * <p>没有配置视觉模型时会在第一步就返回一句友好说明，不会让老人干等一次失败的请求。
     */
    @PostMapping("/images")
    public AgentTurnResponse image(@Valid @RequestBody ImageRequest request) {
        return service.handleImages(request.conversationId(), request.imageDataUrls(), request.hint());
    }

    @PostMapping("/actions")
    public AgentTurnResponse act(@Valid @RequestBody ActionRequest request) {
        return service.act(request.conversationId(), request.action(), request.value(), request.label());
    }

    @PostMapping("/confirmations")
    public AgentTurnResponse confirm(@Valid @RequestBody ConfirmationRequest request) {
        return service.confirm(request.conversationId(), request.approved(), request.confirmationId());
    }

    /**
     * 历史记录：最近聊过的会话列表，新的在前。
     *
     * <p>只返回标题、状态和最后活动时间，不返回会话内容——点进某一条再走
     * {@code /conversations/{id}} 取完整的，免得一次把一堆 state_json 全读出来。
     */
    @GetMapping("/conversations")
    public List<ConversationSummary> conversations(
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return service.conversations(userId, limit);
    }

    /**
     * 结束一段对话。「新对话」按钮先关掉旧的、再建新的。
     *
     * <p>关掉之后这个会话只读：能翻看，但新消息、按钮、确认一律被拒。
     * 幂等——对已经结束的会话再调一次不报错。
     */
    @PostMapping("/conversations/{conversationId}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(@PathVariable("conversationId") String conversationId) {
        service.closeConversation(conversationId);
    }

    /**
     * 一轮进行中的真实进度：模型生成了什么参数、调了哪个工具、工具返回了什么。
     *
     * <p>只读，且只存在内存里——进程重启后进度本来就无从谈起，也没有必要为「此刻在做什么」落库。
     * 前端带 {@code afterSeq} 增量拉取，只渲染新事件。工具参数与结果在这里已经脱敏
     * （手机号、图片 base64），可以安全地展示给评审。
     */
    @GetMapping("/conversations/{conversationId}/progress")
    public TurnProgress.Snapshot progress(@PathVariable("conversationId") String conversationId,
                                         @RequestParam(value = "afterSeq", defaultValue = "0") long afterSeq) {
        return service.progressSnapshot(conversationId, afterSeq);
    }

    /**
     * 助手跨对话记住的事：常去的医院、科室、习惯的时段。
     *
     * <p>这些不是模型随手记的，而是确认门禁放行、预约真的写进库之后沉淀下来的偏好。
     * 「我的」页面把它整段摆出来，老人能看见、也能逐条忘掉。
     */
    @GetMapping("/memories")
    public List<MemoryStore.Memory> memories(@RequestParam(value = "userId", required = false) String userId) {
        return service.memories(userId);
    }

    @DeleteMapping("/memories")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgetMemory(@RequestParam("key") String key,
                             @RequestParam(value = "userId", required = false) String userId) {
        service.forgetMemory(userId, key);
    }

    @GetMapping("/model-status")
    public Map<String, Object> modelStatus() { return service.modelStatus(); }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException error) {
        return Map.of("message", error.getMessage());
    }

    public record MessageRequest(@NotBlank String conversationId, @NotBlank String message) { }
    /** imageDataUrls 是 data URL 列表（最多 3 张）；hint 是老人随图说的那句话，可以为空。 */
    public record ImageRequest(@NotBlank String conversationId, List<String> imageDataUrls, String hint) { }
    public record ActionRequest(@NotBlank String conversationId, @NotBlank String action, String value, String label) { }
    public record ConfirmationRequest(@NotBlank String conversationId, boolean approved, @NotBlank String confirmationId) { }
}
