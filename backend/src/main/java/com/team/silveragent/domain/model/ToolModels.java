package com.team.silveragent.domain.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public final class ToolModels {
    private ToolModels() { }

    public record Slot(String id, String hospitalId, String hospitalName, String department,
                       LocalDate date, LocalTime time) { }
    public record Conflict(String id, String title, LocalDateTime startAt, LocalDateTime endAt) { }
    public record Contact(String id, String name, String relationship, String maskedPhone) { }
    public record TravelPlan(String transport, int durationMinutes, LocalDateTime departureAt,
                             String summary) { }
    public record HospitalProfile(String id, String name, String level, String address,
                                  String description, List<String> specialtyTags,
                                  List<String> elderlyServices) { }
    public record DepartmentProfile(String id, String hospitalId, String name,
                                    String description, List<String> specialtyTags,
                                    String followupScope, String location) { }
}
