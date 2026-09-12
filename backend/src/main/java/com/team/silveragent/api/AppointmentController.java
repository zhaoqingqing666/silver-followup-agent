package com.team.silveragent.api;

import com.team.silveragent.application.AppointmentRecordStore;
import com.team.silveragent.domain.model.ToolModels.AppointmentMaterial;
import com.team.silveragent.domain.tool.MaterialPreparationTool;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    /**
     * 取回这项材料拍过的照片。
     * 归属校验在工具里：先确认这条预约属于这位用户，再只认这条材料自己记下的附件引用——
     * 路径里不接受附件编号，否则这个接口就成了「按编号取任意附件」的读取器。
     * 没拍过（或引用已失效）回 404：这是「还没有照片」，不是出错。
     */
    @GetMapping("/{appointmentId}/materials/{materialId}/photo")
    public ResponseEntity<Map<String, String>> materialPhoto(@PathVariable("userId") String userId,
                                                             @PathVariable("appointmentId") String appointmentId,
                                                             @PathVariable("materialId") String materialId) {
        return materials.photo(userId, appointmentId, materialId)
                .map(dataUrl -> ResponseEntity.ok(Map.of("dataUrl", dataUrl)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 状态非法 / 照片太大这类问题要回 400 带原因，前端才能原样念给老人听。 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException error) {
        return Map.of("message", error.getMessage());
    }

    public record MaterialStatusRequest(String status, String confirmSource, String photoUrl) { }
}
