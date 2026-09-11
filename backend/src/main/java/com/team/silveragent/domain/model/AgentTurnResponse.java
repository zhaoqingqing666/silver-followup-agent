package com.team.silveragent.domain.model;

import java.util.List;

public record AgentTurnResponse(
        String conversationId,
        String stage,
        String reply,
        List<QuickReply> quickReplies,
        PlanCard plan,
        ConfirmationCard confirmation,
        ResultCard result,
        List<ToolTrace> toolTraces,
        TaskProgress task,
        String speechText,
        UiDirective uiDirective
) {
    /**
     * 兼容构造：旧调用不传语音与页面指令时，默认朗读权威 reply，且不打开任何页面。
     */
    public AgentTurnResponse(String conversationId, String stage, String reply,
                             List<QuickReply> quickReplies, PlanCard plan,
                             ConfirmationCard confirmation, ResultCard result,
                             List<ToolTrace> toolTraces, TaskProgress task) {
        this(conversationId, stage, reply, quickReplies, plan, confirmation, result, toolTraces, task, reply, null);
    }

    public AgentTurnResponse(String conversationId, String stage, String reply,
                             List<QuickReply> quickReplies, PlanCard plan,
                             ConfirmationCard confirmation, ResultCard result,
                             List<ToolTrace> toolTraces) {
        this(conversationId, stage, reply, quickReplies, plan, confirmation, result, toolTraces, null);
    }

    public static AgentTurnResponse message(String conversationId, String stage, String reply, List<QuickReply> quickReplies) {
        return new AgentTurnResponse(conversationId, stage, reply, quickReplies, null, null, null, List.of());
    }

    public record QuickReply(String label, String action, String value) { }

    public record PlanCard(String hospital, String department, String date, String selectedTime,
                           List<String> tasks, List<String> materials, String departureTime,
                           String familyContact, List<String> taskStatuses) { }

    public record ConfirmationCard(String title, List<String> operations, String impact,
                                   String confirmText, String cancelText, String confirmationId) { }

    public record ResultCard(String appointmentId, String hospital, String department, String date,
                             String time, List<String> materials, String departureTime,
                             String reminderStatus, String familyStatus) { }

    public record ToolTrace(String toolName, String parameters, String result, boolean success) { }

    /** 封闭枚举的页面指令类型；前端遇到未知类型必须安全忽略，不跳空白页。 */
    public enum UiDirectiveType {
        NONE, OPEN_ASSISTANT, OPEN_TASKS, OPEN_MATERIALS, OPEN_TRAVEL,
        SHOW_OUTSIDE_ROUTE, SHOW_INSIDE_GUIDE, FOCUS_CONFIRMATION
    }

    public record UiDirective(UiDirectiveType type, String appointmentId, String focus) {
        public static UiDirective travel(String appointmentId, boolean inside) {
            return new UiDirective(inside ? UiDirectiveType.SHOW_INSIDE_GUIDE : UiDirectiveType.OPEN_TRAVEL,
                    appointmentId, inside ? "inside" : null);
        }
    }

    public record TaskProgress(boolean active, String status, String currentStage,
                               String summary, String missingField) { }
}
