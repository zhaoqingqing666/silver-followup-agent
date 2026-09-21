package com.team.silveragent.api;

import com.team.silveragent.application.AppointmentRecordStore;

import com.team.silveragent.application.care.CareBookingService;
import com.team.silveragent.application.care.CareCatalogRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 协同照护端“帮助预约”：家属 / 志愿者替就诊人查可选号并一次性办理复诊。
 *
 * <p>三个写操作（代约 / 改期 / 取消）都是<b>两段式</b>：先 POST 到 {@code /prepare} 拿到确认卡
 * 和一张票据，用户看清之后再把票据连同请求一起提交。票据比对放在这里而不是服务里——
 * 助手那条路也调同一批服务方法，但它的确认由会话状态管，{@code confirmationId} 在服务里
 * 挡不住「表单没确认就直接提交」这一种。
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

    /** 代约第 3 步：算出这次会办理什么（含替谁办、记在谁名下）并开票据，不写任何数据。 */
    @PostMapping("/prepare")
    public CareBookingService.BookingPreview prepareBooking(
            @PathVariable("cid") String caregiverId,
            @PathVariable("uid") String elderUserId,
            @RequestBody CareBookingService.BookingRequest request) {
        return service.previewBooking(caregiverId, elderUserId, request);
    }

    /** 代约提交：票据必须对得上，之后才真正落库。 */
    @PostMapping
    public com.team.silveragent.application.AppointmentRecordStore.AppointmentView book(
            @PathVariable("cid") String caregiverId,
            @PathVariable("uid") String elderUserId,
            @RequestParam("confirmationId") String confirmationId,
            @RequestBody CareBookingService.BookingRequest request) {
        return service.bookConfirmed(caregiverId, elderUserId, request, confirmationId);
    }

    /** 改期预览：不动预约本身，先说明改完之后是什么样。 */
    @PostMapping("/modify/prepare")
    public CareBookingService.BookingPreview prepareModify(
            @PathVariable("cid") String caregiverId,
            @PathVariable("uid") String elderUserId,
            @RequestBody CareBookingService.BookingRequest request) {
        return service.previewModify(caregiverId, elderUserId, request);
    }

    /** 修改长辈当前这张进行中的预约：原位更新同一条记录（换号源），不留“已取消”记录。 */
    @PostMapping("/modify")
    public com.team.silveragent.application.AppointmentRecordStore.AppointmentView modify(
            @PathVariable("cid") String caregiverId,
            @PathVariable("uid") String elderUserId,
            @RequestParam("confirmationId") String confirmationId,
            @RequestBody CareBookingService.BookingRequest request) {
        return service.modifyConfirmed(caregiverId, elderUserId, request, confirmationId);
    }

    /** 只切换当前照护者是否陪同这张进行中的复诊（不动预约本身）。 */
    @PostMapping("/accompany")
    public com.team.silveragent.application.AppointmentRecordStore.AppointmentView accompany(
            @PathVariable("cid") String caregiverId,
            @PathVariable("uid") String elderUserId,
            @RequestBody AccompanyRequest request) {
        return service.toggleAccompany(caregiverId, elderUserId, request.willAccompany());
    }

    /** 取消预览：取消是唯一不可逆的动作，先让人看清要取消的是哪一张。 */
    @PostMapping("/cancel/prepare")
    public CareBookingService.BookingPreview prepareCancel(
            @PathVariable("cid") String caregiverId,
            @PathVariable("uid") String elderUserId) {
        return service.previewCancel(caregiverId, elderUserId);
    }

    /** 取消该长辈当前进行中的预约（先取消旧安排，再重新代约时使用）。 */
    @PostMapping("/cancel")
    public Map<String, String> cancel(
            @PathVariable("cid") String caregiverId,
            @PathVariable("uid") String elderUserId,
            @RequestParam("confirmationId") String confirmationId) {
        var view = service.cancelConfirmed(caregiverId, elderUserId, confirmationId);
        return Map.of("message", "已取消" + view.date().getMonthValue() + "月"
                + view.date().getDayOfMonth() + "日的复诊预约");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException error) {
        return Map.of("message", error.getMessage());
    }
}
