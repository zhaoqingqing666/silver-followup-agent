package com.team.silveragent.domain.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public final class ToolModels {
    private ToolModels() { }

    public record Slot(String id, String hospitalId, String hospitalName, String department,
                       LocalDate date, LocalTime time) { }
    public record Conflict(String id, String title, LocalDateTime startAt, LocalDateTime endAt) { }
    public record Contact(String id, String name, String relationship, String maskedPhone) { }
    public record TravelPlan(String transport, int durationMinutes, LocalDateTime departureAt,
                             String summary) { }
}
