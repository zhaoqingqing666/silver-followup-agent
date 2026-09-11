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
    public record GeoPoint(double longitude, double latitude) { }
    public record RouteGuide(String transport, int durationMinutes, int distanceMeters,
                             LocalDateTime departureAt, GeoPoint origin, GeoPoint destination,
                             List<GeoPoint> polyline, List<String> steps, String source) { }
    public record FacilityGuide(String hospitalId, String departmentId, String building,
                                String entrance, String floor, String room, String checkInPoint,
                                String landmark, String accessibleRouteHint, String helpDesk,
                                LocalDateTime verifiedAt) { }
    public record AppointmentTravelGuide(String appointmentId, String hospital, String department,
                                         LocalDateTime appointmentAt, RouteGuide route,
                                         FacilityGuide facility, boolean simulated) { }
    public record HospitalProfile(String id, String name, String level, String address,
                                  String description, List<String> specialtyTags,
                                  List<String> elderlyServices) { }
    public record DepartmentProfile(String id, String hospitalId, String name,
                                    String description, List<String> specialtyTags,
                                    String followupScope, String location) { }
    public record AppointmentMaterial(String id, String appointmentId, String materialCode,
                                      String materialName, boolean required, String status,
                                      String confirmSource, String photoUrl,
                                      LocalDateTime updatedAt) { }
    /** 药品知识库里的一条真实记录：只讲「是什么、做什么用、要注意什么」，不含剂量与用药调整建议。 */
    public record DrugKnowledge(String name, String specification, String category, String purpose,
                                String reminder, String followupTip, List<String> aliases) { }
    public record AppointmentSummary(String appointmentId, String hospital, String department,
                                     LocalDate date, LocalTime time, String status,
                                     LocalDateTime departureAt, String transport,
                                     String reminderStatus, String familyStatus,
                                     List<String> materials) { }
}
