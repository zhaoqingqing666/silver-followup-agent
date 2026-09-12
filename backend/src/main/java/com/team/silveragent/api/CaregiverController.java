package com.team.silveragent.api;

import com.team.silveragent.application.care.CareService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 协同照护端只读接口：家属 / 志愿者查询其协同的就诊人信息。
 * 本控制器不提供任何写操作。
 */
@RestController
@RequestMapping("/api/caregivers")
public class CaregiverController {
    private final CareService service;

    public CaregiverController(CareService service) { this.service = service; }

    @GetMapping("/{cid}/elders")
    public List<CareService.ElderSummary> elders(@PathVariable("cid") String caregiverId) {
        return service.elders(caregiverId);
    }

    @GetMapping("/{cid}/elders/{uid}/timeline")
    public List<CareService.TimelineEvent> timeline(
            @PathVariable("cid") String caregiverId,
            @PathVariable("uid") String elderUserId) {
        return service.timeline(caregiverId, elderUserId);
    }

    @GetMapping("/{cid}/notifications")
    public List<CareService.NotificationView> notifications(@PathVariable("cid") String caregiverId) {
        return service.notifications(caregiverId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException error) {
        return Map.of("message", error.getMessage());
    }
}
