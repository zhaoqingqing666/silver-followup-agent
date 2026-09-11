package com.team.silveragent.api;

import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.model.ConversationHistoryResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

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

    @PostMapping("/actions")
    public AgentTurnResponse act(@Valid @RequestBody ActionRequest request) {
        return service.act(request.conversationId(), request.action(), request.value(), request.label());
    }

    @PostMapping("/confirmations")
    public AgentTurnResponse confirm(@Valid @RequestBody ConfirmationRequest request) {
        return service.confirm(request.conversationId(), request.approved(), request.confirmationId());
    }

    @GetMapping("/model-status")
    public Map<String, Object> modelStatus() { return service.modelStatus(); }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException error) {
        return Map.of("message", error.getMessage());
    }

    public record MessageRequest(@NotBlank String conversationId, @NotBlank String message) { }
    public record ActionRequest(@NotBlank String conversationId, @NotBlank String action, String value, String label) { }
    public record ConfirmationRequest(@NotBlank String conversationId, boolean approved, @NotBlank String confirmationId) { }
}
