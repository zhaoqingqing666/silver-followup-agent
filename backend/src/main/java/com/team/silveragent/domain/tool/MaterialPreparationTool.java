package com.team.silveragent.domain.tool;

import com.team.silveragent.domain.model.ToolModels.AppointmentMaterial;

import java.util.List;
import java.util.Optional;

public interface MaterialPreparationTool {
    List<AppointmentMaterial> initialize(String conversationId, String appointmentId,
                                         String department, List<String> materialNames);

    List<AppointmentMaterial> list(String userId, String appointmentId);

    AppointmentMaterial updateStatus(String userId, String appointmentId, String materialId,
                                     String status, String confirmSource, String photoUrl);

    /**
     * 取回这项材料拍过的照片本体（data URL）。没拍过返回空。
     * 归属校验必须在这一层完成：先确认这条预约属于这位用户，再只认这条材料自己记下的照片引用。
     */
    Optional<String> photo(String userId, String appointmentId, String materialId);
}
