package com.team.silveragent.application;

import com.team.silveragent.agent.AgentContext;
import com.team.silveragent.agent.ReplyContext;
import com.team.silveragent.domain.model.AgentTurnResponse;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
final class ReplyContextBuilder {
    ReplyContext build(ConversationState state, AgentTurnResponse response, String knownFacts,
                       List<AgentContext.Message> recentMessages) {
        String plan = response.plan() == null ? "无" : String.join("；", response.plan().tasks())
                + "；状态=" + response.plan().taskStatuses();
        String confirmation = response.confirmation() == null ? "无"
                : response.confirmation().title() + "；操作=" + response.confirmation().operations()
                + "；影响=" + response.confirmation().impact();
        String result = response.result() == null ? "无"
                : "预约=" + response.result().appointmentId() + "；" + response.result().hospital()
                + "；" + response.result().department() + "；" + response.result().date() + " "
                + response.result().time() + "；提醒=" + response.result().reminderStatus()
                + "；家属=" + response.result().familyStatus();
        List<AgentTurnResponse.ToolTrace> recentTools = response.toolTraces() == null ? List.of()
                : response.toolTraces().stream().skip(Math.max(0, response.toolTraces().size() - 4L)).toList();
        String tools = recentTools.stream().map(item -> item.toolName() + "="
                + (item.success() ? "成功" : "失败") + "，结果=" + item.result()).reduce((a, b) -> a + "；" + b).orElse("无");
        List<String> replies = response.quickReplies() == null ? List.of()
                : response.quickReplies().stream().map(AgentTurnResponse.QuickReply::label).toList();
        String draft = response.reply() == null ? "" : response.reply();
        return new ReplyContext(response.stage(), state.dialogueMode.name(), knownFacts, draft, replies,
                plan, confirmation, result, tools, recentMessages,
                state.stage == ConversationState.Stage.EMERGENCY_PAUSED,
                draft.contains("不能诊断") || draft.contains("不能提供医疗判断"),
                state.stage == ConversationState.Stage.AWAITING_CONFIRMATION);
    }
}
