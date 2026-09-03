package com.team.silveragent.api;

import com.team.silveragent.application.AppointmentRecordStore;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users/{userId}/appointments")
public class AppointmentController {
    private final AppointmentRecordStore records;

    public AppointmentController(AppointmentRecordStore records) { this.records = records; }

    @GetMapping
    public List<AppointmentRecordStore.AppointmentView> list(@PathVariable("userId") String userId) {
        return records.allFor(userId);
    }
}
