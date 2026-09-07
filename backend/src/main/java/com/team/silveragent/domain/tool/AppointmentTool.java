package com.team.silveragent.domain.tool;

import com.team.silveragent.domain.model.ToolModels.Slot;
import java.time.LocalDate;
import java.util.List;

public interface AppointmentTool {
    List<Slot> queryAvailableSlots(String conversationId, String hospitalId, String department, LocalDate date);
    List<Slot> queryAlternatives(String conversationId, String hospitalId, String department, LocalDate date);
    String submit(String conversationId, String slotId, String userId);
    String reschedule(String conversationId, String appointmentId, String slotId, String userId);
    String cancel(String conversationId, String appointmentId, String userId);
}
