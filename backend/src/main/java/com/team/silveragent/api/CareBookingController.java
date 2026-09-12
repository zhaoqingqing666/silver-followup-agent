package com.team.silveragent.api;

import com.team.silveragent.application.CareBookingService;
import com.team.silveragent.application.CareCatalogRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 协同照护端“帮助预约”：家属 / 志愿者替就诊人查可选号并一次性办理复诊。
 */
@RestController
@RequestMapping("/api/caregivers/{cid}/elders/{uid}/book")
public class CareBookingController {
    private final CareBookingService service;

    public CareBookingController(CareBookingService service) { this.service = service; }

    /** 陪同状态切换请求体。 */
    public record AccompanyRequest(boolean willAccompany) { }

    @GetMapping("/hospitals")
    public List<CareCatalogRepository.Hospital> hospitals(
            @PathVariable("cid") String caregiverId,
            @PathVariable("uid") String elderUserId) {
        return service.hospitals(caregiverId, elderUserId);
    }

    @GetMapping("/departments")
    public List<CareCatalogRepository.Department> departments(
            @PathVariable("cid") String caregiverId,
            @PathVariable("uid") String elderUserId,
            @RequestParam("hospitalId") String hospitalId) {
        return service.departments(caregiverId, elderUserId, hospitalId);
    }

    @GetMapping("/windows")
    public List<CareBookingService.DateWindow> windows(
            @PathVariable("cid") String caregiverId,
            @PathVariable("uid") String elderUserId,
            @RequestParam("hospitalId") String hospitalId,
            @RequestParam("departmentId") String departmentId) {
        return service.windows(caregiverId, elderUserId, hospitalId, departmentId);
    }

    @PostMapping
    public com.team.silveragent.application.AppointmentRecordStore.AppointmentView book(
            @PathVariable("cid") String caregiverId,
            @PathVariable("uid") String elderUserId,
            @RequestBody CareBookingService.BookingRequest request) {
        return service.book(caregiverId, elderUserId, request);
    }

    /** 修改长辈当前这张进行中的预约：原位更新同一条记录（换号源），不留“已取消”记录。 */
    @PostMapping("/modify")
    public com.team.silveragent.application.AppointmentRecordStore.AppointmentView modify(
            @PathVariable("cid") String caregiverId,
            @PathVariable("uid") String elderUserId,
            @RequestBody CareBookingService.BookingRequest request) {
        return service.modify(caregiverId, elderUserId, request);
    }

    /** 只切换当前照护者是否陪同这张进行中的复诊（不动预约本身）。 */
    @PostMapping("/accompany")
    public com.team.silveragent.application.AppointmentRecordStore.AppointmentView accompany(
            @PathVariable("cid") String caregiverId,
            @PathVariable("uid") String elderUserId,
            @RequestBody AccompanyRequest request) {
        return service.toggleAccompany(caregiverId, elderUserId, request.willAccompany());
    }

    /** 取消该长辈当前进行中的预约（先取消旧安排，再重新代约时使用）。 */
    @PostMapping("/cancel")
    public Map<String, String> cancel(
            @PathVariable("cid") String caregiverId,
            @PathVariable("uid") String elderUserId) {
        var view = service.cancelUpcoming(caregiverId, elderUserId);
        return Map.of("message", "已取消" + view.date().getMonthValue() + "月"
                + view.date().getDayOfMonth() + "日的复诊预约");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException error) {
        return Map.of("message", error.getMessage());
    }
}
