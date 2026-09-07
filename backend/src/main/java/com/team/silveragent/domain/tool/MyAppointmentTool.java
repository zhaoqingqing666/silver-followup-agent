package com.team.silveragent.domain.tool;

import com.team.silveragent.domain.model.ToolModels.AppointmentSummary;

import java.time.LocalDate;
import java.util.List;

/** 查询当前用户已经写入数据库的预约，不读取当前对话中的临时草稿。 */
public interface MyAppointmentTool {
    List<AppointmentSummary> search(String conversationId, String userId, LocalDate date,
                                    String hospital, String department);
}
