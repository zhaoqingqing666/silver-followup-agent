package com.team.silveragent.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class CareCatalogRepository {
    private final JdbcTemplate jdbc;

    public CareCatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Hospital> hospitals() {
        return jdbc.query("""
                SELECT id,name,address,hospital_level,description,specialty_tags,elderly_services
                FROM hospitals WHERE enabled=TRUE ORDER BY id
                """, (rs, row) -> new Hospital(rs.getString(1), clean(rs.getString(2)),
                rs.getString(3), rs.getString(4), rs.getString(5), split(rs.getString(6)),
                split(rs.getString(7))));
    }

    public Optional<Hospital> hospital(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) return Optional.empty();
        String normalized = clean(idOrName);
        return hospitals().stream()
                .filter(item -> item.id().equalsIgnoreCase(idOrName)
                        || item.name().equals(normalized)
                        || normalized.contains(item.name())
                        || item.name().contains(normalized))
                .findFirst();
    }

    public List<Department> departments(String hospitalId) {
        return jdbc.query("""
                SELECT id,hospital_id,name,description,specialty_tags,followup_scope,location
                FROM departments WHERE hospital_id=? AND enabled=TRUE ORDER BY id
                """, (rs, row) -> department(rs), hospitalId);
    }

    public List<Hospital> hospitalsForDepartment(String departmentName) {
        if (departmentName == null || departmentName.isBlank()) return List.of();
        return jdbc.query("""
                SELECT DISTINCT h.id,h.name,h.address,h.hospital_level,h.description,h.specialty_tags,h.elderly_services
                FROM hospitals h JOIN departments d ON d.hospital_id=h.id
                WHERE h.enabled=TRUE AND d.enabled=TRUE AND d.name=? ORDER BY h.id
                """, (rs, row) -> new Hospital(rs.getString(1), clean(rs.getString(2)),
                rs.getString(3), rs.getString(4), rs.getString(5), split(rs.getString(6)),
                split(rs.getString(7))), departmentName);
    }

    public List<Department> searchDepartments(String keyword) {
        if (keyword == null || keyword.isBlank()) return List.of();
        String like = "%" + keyword.trim() + "%";
        return jdbc.query("""
                SELECT id,hospital_id,name,description,specialty_tags,followup_scope,location
                FROM departments
                WHERE enabled=TRUE AND (name LIKE ? OR specialty_tags LIKE ? OR followup_scope LIKE ?)
                ORDER BY hospital_id,id
                """, (rs, row) -> department(rs), like, like, like);
    }

    public Optional<Department> department(String hospitalId, String idOrName) {
        if (hospitalId == null || idOrName == null || idOrName.isBlank()) return Optional.empty();
        return departments(hospitalId).stream()
                .filter(item -> item.id().equalsIgnoreCase(idOrName) || item.name().equals(idOrName))
                .findFirst();
    }

    public List<String> hospitalNames() {
        return hospitals().stream().map(Hospital::name).toList();
    }

    public List<String> departmentNames() {
        return jdbc.query("SELECT DISTINCT name FROM departments ORDER BY name",
                (rs, row) -> rs.getString(1));
    }

    public List<LocalDate> availableDates(String hospitalId, String department, LocalDate from, int limit) {
        return jdbc.query("""
                SELECT DISTINCT appointment_date FROM appointment_slots
                WHERE hospital_id=? AND department=? AND appointment_date>=? AND available=TRUE
                ORDER BY appointment_date LIMIT ?
                """, (rs, row) -> rs.getDate(1).toLocalDate(),
                hospitalId, department, java.sql.Date.valueOf(from), limit);
    }

    public Optional<UserProfile> user(String userId) {
        List<UserProfile> rows = jdbc.query("SELECT id,name,home_address,preferred_transport FROM users WHERE id=?",
                (rs, row) -> new UserProfile(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4)), userId);
        return rows.stream().findFirst();
    }

    public List<com.team.silveragent.domain.model.ToolModels.Contact> contacts(String userId) {
        return jdbc.query("SELECT id,name,relationship,phone FROM family_contacts WHERE user_id=? ORDER BY id",
                (rs, row) -> new com.team.silveragent.domain.model.ToolModels.Contact(rs.getString(1), rs.getString(2), rs.getString(3), "已隐藏"), userId);
    }

    private String clean(String value) {
        return value == null ? "" : value.replace("（模拟）", "").replace("(模拟)", "").trim();
    }

    private Department department(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Department(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), split(rs.getString(5)), rs.getString(6), rs.getString(7));
    }

    private List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split("[,，]"))
                .map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    public record Hospital(String id, String name, String address, String level,
                           String description, List<String> specialtyTags,
                           List<String> elderlyServices) { }
    public record Department(String id, String hospitalId, String name, String description,
                             List<String> specialtyTags, String followupScope,
                             String location) { }
    public record UserProfile(String id, String name, String homeAddress, String preferredTransport) { }
}
