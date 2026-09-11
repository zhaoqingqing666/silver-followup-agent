package com.team.silveragent.api;

import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.model.ConversationHistoryResponse;
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

    @PostMapping("/conversations")
    public AgentTurnResponse start() { return service.start(); }

    @GetMapping("/conversations")
    public List<com.team.silveragent.domain.model.ConversationSummary> list() { return service.listConversations(); }

    @GetMapping("/conversations/{conversationId}")
    public ConversationHistoryResponse resume(@PathVariable("conversationId") String conversationId) {
        return service.resume(conversationId);
    }

    @PostMapping("/messages")
    public AgentTurnResponse chat(@Valid @RequestBody MessageRequest request) {
        return service.chat(request.conversationId(), request.message(), request.isVoice());
    }

    @PostMapping("/images")
    public AgentTurnResponse image(@Valid @RequestBody ImageRequest request) {
        return service.handleImages(request.conversationId(), request.imageDataUrls(), request.hint());
    }

    @PostMapping("/actions")
    public AgentTurnResponse act(@Valid @RequestBody ActionRequest request) {
        return service.act(request.conversationId(), request.action(), request.value());
    }

    @PostMapping("/confirmations")
    public AgentTurnResponse confirm(@Valid @RequestBody ConfirmationRequest request) {
        return service.confirm(request.conversationId(), request.approved());
    }

    @GetMapping("/model-status")
    public Map<String, Object> modelStatus() { return service.modelStatus(); }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException error) {
        return Map.of("message", error.getMessage());
    }

    public record MessageRequest(@NotBlank String conversationId, @NotBlank String message, Boolean isVoice) { }
    public record ImageRequest(@NotBlank String conversationId, List<String> imageDataUrls, String hint) { }
    public record ActionRequest(@NotBlank String conversationId, @NotBlank String action, String value) { }
    public record ConfirmationRequest(@NotBlank String conversationId, boolean approved) { }
}
