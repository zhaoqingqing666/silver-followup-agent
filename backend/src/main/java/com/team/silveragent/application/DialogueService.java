package com.team.silveragent.application;

import com.team.silveragent.agent.ExtractedFacts;
import com.team.silveragent.domain.model.AgentTurnResponse.QuickReply;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
final class DialogueService {
    record DialogueReply(String draft, List<QuickReply> quickReplies) { }

    DialogueReply support(ConversationState state, String message, ExtractedFacts facts) {
        state.dialogueMode = ConversationState.DialogueMode.SUPPORT;
        pauseActiveTask(state);
        String concern = facts.concern() == null ? message : facts.concern();
        String draft;
        if (containsAny(message, "心里不舒服", "心口不舒服", "不舒服")) {
            draft = "我听到了。" + statusHint(state)
                    + "您说的是胸口或身体不舒服，还是心情难受？如果有明显胸痛、呼吸困难或喘不上气，请立即联系身边人员并拨打120。";
        } else if (containsAny(concern, "害怕", "担心", "紧张", "一个人", "没人陪")) {
            draft = "听起来您对这次复诊有些担心。" + statusHint(state)
                    + "您主要担心一个人去，还是担心复诊过程？";
        } else if (containsAny(concern, "累", "疲惫", "没精神")) {
            draft = "听起来您现在有些累，我们可以先慢一点。" + statusHint(state)
                    + "您想先休息，还是愿意说说哪里让您觉得累？";
        } else {
            draft = "我听到了，您可以慢慢说。" + statusHint(state) + "您现在最担心的是什么？";
        }
        return new DialogueReply(draft, replies(state));
    }

    DialogueReply clarifyDiscomfort(ConversationState state) {
        state.dialogueMode = ConversationState.DialogueMode.SUPPORT;
        pauseActiveTask(state);
        return new DialogueReply(
                "我想先确认一下。" + statusHint(state)
                        + "您说的是胸口或身体不舒服，还是心情难受？如果有明显胸痛、呼吸困难或喘不上气，请立即联系身边人员并拨打120。",
                replies(state));
    }

    DialogueReply smallTalk(ConversationState state, String message) {
        state.dialogueMode = ConversationState.DialogueMode.SMALL_TALK;
        pauseActiveTask(state);
        String draft = containsAny(message, "谢谢", "感谢")
                ? "不用客气。" + statusHint(state) + "您想继续办理，还是先说说别的？"
                : "可以，您可以慢慢说，我会听着。" + statusHint(state)
                        + "涉及诊断和用药的问题，我会建议您咨询医生。您现在想聊什么？";
        return new DialogueReply(draft, replies(state));
    }

    DialogueReply modelReply(ConversationState state, String draft, String requestedMode) {
        state.dialogueMode = "SUPPORT".equals(requestedMode)
                ? ConversationState.DialogueMode.SUPPORT
                : "SMALL_TALK".equals(requestedMode)
                ? ConversationState.DialogueMode.SMALL_TALK
                : ConversationState.DialogueMode.GENERAL_CHAT;
        pauseActiveTask(state);
        String reply = draft == null ? "" : draft.trim();
        if (reply.isBlank()) reply = "我在听，您可以慢慢说。";
        return new DialogueReply(reply, replies(state));
    }

    private String statusHint(ConversationState state) {
        if (state.taskStatus == ConversationState.TaskStatus.NONE) return "";
        return switch (state.stage) {
            case CANCELLED -> "刚才的办理已经停止，不会提交预约。";
            case EMERGENCY_PAUSED -> "普通办理仍处于暂停状态。";
            default -> state.taskStatus == ConversationState.TaskStatus.COMPLETED
                    ? "已经完成的复诊事项可以在事项页查看。" : "原来的办理进度已经保留。";
        };
    }

    private List<QuickReply> replies(ConversationState state) {
        if (state.stage == ConversationState.Stage.CANCELLED) {
            return List.of(q("新建办理", "NEW_BOOKING"), q("查看事项", "OPEN_TASKS"));
        }
        if (state.stage == ConversationState.Stage.EMERGENCY_PAUSED) {
            return List.of(q("人工帮助", "CONTACT_HUMAN"));
        }
        if (state.taskStatus == ConversationState.TaskStatus.ACTIVE
                || state.taskStatus == ConversationState.TaskStatus.PAUSED) {
            return List.of(q("继续刚才的办理", "RETURN_TO_FLOW"), q("人工帮助", "CONTACT_HUMAN"));
        }
        return List.of(q("开始复诊办理", "CONTINUE"), q("人工帮助", "CONTACT_HUMAN"));
    }

    private void pauseActiveTask(ConversationState state) {
        if (state.taskStatus == ConversationState.TaskStatus.ACTIVE) {
            state.taskStatus = ConversationState.TaskStatus.PAUSED;
        }
    }

    private boolean hasProgressHint(String value) {
        return containsAny(value, "办理进度", "预约进度", "已经停止", "不会提交", "普通办理", "先暂停", "先保留");
    }

    private QuickReply q(String label, String action) { return new QuickReply(label, action, ""); }

    private boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }
}
