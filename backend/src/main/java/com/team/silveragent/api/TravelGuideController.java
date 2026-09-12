package com.team.silveragent.api;

import com.team.silveragent.application.travel.TravelGuideService;
import com.team.silveragent.domain.model.ToolModels.AppointmentTravelGuide;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/{userId}/appointments/{appointmentId}/travel-guide")
public class TravelGuideController {
    private final TravelGuideService guides;

    public TravelGuideController(TravelGuideService guides) {
        this.guides = guides;
    }

    @GetMapping
    public AppointmentTravelGuide get(@PathVariable("userId") String userId,
                                      @PathVariable("appointmentId") String appointmentId) {
        return guides.forAppointment(userId, appointmentId);
    }
}
