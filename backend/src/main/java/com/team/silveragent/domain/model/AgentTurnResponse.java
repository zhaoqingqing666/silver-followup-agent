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
        List<ToolTrace> toolTraces
) {
    public static AgentTurnResponse message(String conversationId, String stage, String reply, List<QuickReply> quickReplies) {
        return new AgentTurnResponse(conversationId, stage, reply, quickReplies, null, null, null, List.of());
    }

    public record QuickReply(String label, String action, String value) { }

    public record PlanCard(String hospital, String department, String date, String selectedTime,
                           List<String> tasks, List<String> materials, String departureTime,
                           String familyContact) { }

    public record ConfirmationCard(String title, List<String> operations, String impact,
                                   String confirmText, String cancelText) { }

    public record ResultCard(String appointmentId, String hospital, String department, String date,
                             String time, List<String> materials, String departureTime,
                             String reminderStatus, String familyStatus) { }

    public record ToolTrace(String toolName, String parameters, String result, boolean success) { }
}
