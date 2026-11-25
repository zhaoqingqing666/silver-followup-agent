package com.team.silveragent.api;

import com.team.silveragent.application.AppointmentRecordStore;
import com.team.silveragent.domain.model.ToolModels.AppointmentMaterial;
import com.team.silveragent.domain.tool.AppointmentTool;
import com.team.silveragent.domain.tool.MaterialPreparationTool;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/users/{userId}/appointments")
public class AppointmentController {
    private final AppointmentRecordStore records;
    private final MaterialPreparationTool materials;
    private final AppointmentTool appointments;

    public AppointmentController(AppointmentRecordStore records, MaterialPreparationTool materials,
                                 AppointmentTool appointments) {
        this.records = records;
        this.materials = materials;
        this.appointments = appointments;
    }

    @GetMapping
    public List<AppointmentRecordStore.AppointmentView> list(@PathVariable("userId") String userId) {
        return records.allFor(userId);
    }

    /**
     * 老人自己在事项页点垃圾桶取消这张预约。
     *
     * <p>走的是助手那条**完全相同的取消链**（{@code AppointmentTool.cancel}）：释放号源、
     * 把关联提醒置为 CANCELLED、预约置为 CANCELLED。区别只有一处——这里没有确认卡，
     * 因为调用它之前前端会先弹一次「页内二次确认」，那一步就是这次写操作的确认环节。
     *
     * <p>归属和状态由工具里那句带 {@code id + user_id + status='CONFIRMED'} 的查询把关，
     * 所以路径里换个别人的 appointmentId 也拿不到数据，这里不额外查一遍。
     *
     * <p>记录**不删除**，只标 CANCELLED：materials / reminders 挂在 appointment_id 上，
     * 而且「什么时候取消的」本身也是个事实。
     */
    @PostMapping("/{appointmentId}/cancel")
    public Map<String, String> cancel(@PathVariable("userId") String userId,
                                      @PathVariable("appointmentId") String appointmentId) {
        try {
            appointments.cancel("ELDER-CANCEL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                    appointmentId, userId);
        } catch (IncorrectResultSizeDataAccessException error) {
            // 进这一支说明工具第一句就没查到行。那句查询同时带着 id、user_id 和 status='CONFIRMED'，
            // 所以「记录不存在」「不是这位老人的预约」「已经取消过」三种情况在这里长得一模一样。
            // 故意给同一句话：分开说就得先暗示这条记录存不存在，那是给人一个探测别人预约的口子。
            // 也只认这一种异常——别的数据库故障是真故障，不该被伪装成「没有这条预约」，
            // 否则线上排查会被这行代码骗过去。
            throw new IllegalArgumentException("没有找到这条可以取消的预约，请刷新后再看。");
        } catch (IllegalStateException error) {
            // 兜底，平时走不到：上面那句查询会先把已经取消的挡在门外，只有「查到行之后、
            // UPDATE 之前状态被改了」（例如同一台设备开着两个页面各点了一次）才会落到这里。
            throw new IllegalArgumentException("这条预约已经不是「已预约」状态了，请刷新后再看。");
        }
        return Map.of("appointmentId", appointmentId, "status", "CANCELLED");
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
