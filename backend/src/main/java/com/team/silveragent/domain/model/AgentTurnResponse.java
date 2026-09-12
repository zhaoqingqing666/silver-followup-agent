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
        UiDirective uiDirective,
        Notice notice
) {
    /**
     * 兼容构造：不带提示块时补 null。加这一条是为了让「提示块」这个新字段
     * 不牵动既有的十几处构造点——它们构造出来的响应本来就没有提示块。
     */
    public AgentTurnResponse(String conversationId, String stage, String reply,
                             List<QuickReply> quickReplies, PlanCard plan,
                             ConfirmationCard confirmation, ResultCard result,
                             List<ToolTrace> toolTraces, TaskProgress task,
                             String speechText, UiDirective uiDirective) {
        this(conversationId, stage, reply, quickReplies, plan, confirmation, result,
                toolTraces, task, speechText, uiDirective, null);
    }

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

    /**
     * 需要与普通聊天气泡区分显示的提示块，目前只有医疗越界一种。
     *
     * <p>只影响展示：不切 {@code stage}、不让待确认的 {@code confirmationId} 失效。
     * 老人问一句“这个药还能吃吗”不等于想中断办理，所以办理进度原样保留（见 DECISIONS）。
     */
    public record Notice(String type, String title, String message) {
        public static final String MEDICAL_BOUNDARY = "MEDICAL_BOUNDARY";
    }
}
