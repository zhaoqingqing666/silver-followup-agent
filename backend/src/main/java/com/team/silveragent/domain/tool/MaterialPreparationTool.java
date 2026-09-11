package com.team.silveragent.domain.tool;

import com.team.silveragent.domain.model.ToolModels.AppointmentMaterial;

import java.util.List;

public interface MaterialPreparationTool {
    List<AppointmentMaterial> initialize(String conversationId, String appointmentId,
                                         String department, List<String> materialNames);

    List<AppointmentMaterial> list(String userId, String appointmentId);

    AppointmentMaterial updateStatus(String userId, String appointmentId, String materialId,
                                     String status, String confirmSource, String photoUrl);
}
