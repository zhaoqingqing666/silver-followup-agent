package com.team.silveragent.domain.tool;

import com.team.silveragent.domain.model.ToolModels.Slot;
import java.time.LocalDate;
import java.util.List;

public interface AppointmentTool {
    List<Slot> queryAvailableSlots(String conversationId, String hospital, String department, LocalDate date);
    List<Slot> queryAlternatives(String conversationId, String hospital, String department, LocalDate date);
    /** 查询所有未来可用号源（跨医院/科室），按日期、时间升序，用于"最近可预约日期"概览 */
    List<Slot> queryUpcomingAvailable(String conversationId);
    String submit(String conversationId, String slotId, String userId);
    String cancel(String conversationId, String appointmentId, String userId);
}
