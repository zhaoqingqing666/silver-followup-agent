package com.team.silveragent.api;

import com.team.silveragent.application.AppointmentRecordStore;
import com.team.silveragent.domain.model.ToolModels.AppointmentMaterial;
import com.team.silveragent.domain.tool.MaterialPreparationTool;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users/{userId}/appointments")
public class AppointmentController {
    private final AppointmentRecordStore records;
    private final MaterialPreparationTool materials;

    public AppointmentController(AppointmentRecordStore records, MaterialPreparationTool materials) {
        this.records = records;
        this.materials = materials;
    }

    @GetMapping
    public List<AppointmentRecordStore.AppointmentView> list(@PathVariable("userId") String userId) {
        return records.allFor(userId);
    }

    @DeleteMapping("/{appointmentId}")
    public Map<String, Object> cancel(@PathVariable("userId") String userId,
                                      @PathVariable("appointmentId") String appointmentId) {
        boolean ok = records.cancel(appointmentId, userId);
        return Map.of("success", ok, "appointmentId", appointmentId);
    }

    @GetMapping("/{appointmentId}/materials")
    public List<AppointmentMaterial> materials(@PathVariable("userId") String userId,
                                               @PathVariable("appointmentId") String appointmentId) {
        return materials.list(userId, appointmentId);
    }

    @PatchMapping("/{appointmentId}/materials/{materialId}")
    public AppointmentMaterial updateMaterial(@PathVariable("userId") String userId,
                                              @PathVariable("appointmentId") String appointmentId,
                                              @PathVariable("materialId") String materialId,
                                              @RequestBody MaterialStatusRequest request) {
        return materials.updateStatus(userId, appointmentId, materialId,
                request.status(), request.confirmSource(), request.photoUrl());
    }

    public record MaterialStatusRequest(String status, String confirmSource, String photoUrl) { }
}
