package com.team.silveragent.application.care;

import com.team.silveragent.application.BusinessClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class CareCatalogRepository {
    private final JdbcTemplate jdbc;
    private final BusinessClock clock;

    public CareCatalogRepository(JdbcTemplate jdbc, BusinessClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
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

    /**
     * 从 from 起仍可预约的日期，去重、最多 limit 个（「查看可预约日期」那排按钮用它）。
     *
     * <p>「当天已过的时段」在这里一并滤掉，判据与号源查询同一份（{@link BusinessClock}）。
     * 以前这一步交给 H2 的 {@code CURRENT_DATE / CURRENT_TIME}，等于把业务口径挂在容器时区上：
     * 镜像没配时区时 JVM 走 GMT，北京 22:58 在它眼里才 14:58，「今天还剩几个时段」整片算错。
     */
    public List<LocalDate> availableDates(String hospitalId, String department, LocalDate from, int limit) {
        if (hospitalId == null || department == null || from == null) return List.of();
        return jdbc.query("""
                SELECT DISTINCT appointment_date, appointment_time FROM appointment_slots
                WHERE hospital_id=? AND department=? AND appointment_date>=? AND available=TRUE
                ORDER BY appointment_date, appointment_time
                """, (rs, row) -> LocalDateTime.of(rs.getDate(1).toLocalDate(), rs.getTime(2).toLocalTime()),
                hospitalId, department, java.sql.Date.valueOf(from))
                .stream()
                .filter(moment -> !clock.isPast(moment))
                .map(LocalDateTime::toLocalDate)
                .distinct()
                .limit(limit)
                .toList();
    }

    public Optional<UserProfile> user(String userId) {
        List<UserProfile> rows = jdbc.query("SELECT id,name,home_address,preferred_transport FROM users WHERE id=?",
                (rs, row) -> new UserProfile(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4),
                        familyMembers(rs.getString(1))), userId);
        return rows.stream().findFirst();
    }

    /**
     * 读取用于展示的家属联系人（仅脱敏电话，不返回明文）。
     * 与 {@link #contacts(String)} 分工：contacts 供 Agent 流程做候选人选择，只含姓名与关系；
     * 本方法供用户资料页展示家属，电话在 Java 层脱敏。
     */
    private List<com.team.silveragent.domain.model.ToolModels.Contact> familyMembers(String userId) {
        return jdbc.query("SELECT id,name,relationship,phone FROM family_contacts WHERE user_id=? ORDER BY id",
                (rs, row) -> new com.team.silveragent.domain.model.ToolModels.Contact(rs.getString(1),
                        rs.getString(2), rs.getString(3), maskPhone(rs.getString(4))), userId);
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 8) return phone == null ? "" : phone;
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
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
    public record UserProfile(String id, String name, String homeAddress, String preferredTransport,
                              List<com.team.silveragent.domain.model.ToolModels.Contact> contacts) { }
}
