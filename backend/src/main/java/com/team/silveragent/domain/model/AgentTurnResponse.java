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
        Notice notice
) {
    public static AgentTurnResponse message(String conversationId, String stage, String reply, List<QuickReply> quickReplies) {
        return new AgentTurnResponse(conversationId, stage, reply, quickReplies, null, null, null, List.of(), null);
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

    /**
     * 需要与普通对话气泡区分显示的提示块，例如医疗越界说明。
     * 只影响展示，不改变 {@code stage}，也不使待确认操作失效。
     */
    public record Notice(String type, String title, String message) {
        public static final String MEDICAL_BOUNDARY = "MEDICAL_BOUNDARY";
    }
}
