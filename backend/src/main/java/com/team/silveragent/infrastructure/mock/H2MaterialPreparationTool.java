package com.team.silveragent.infrastructure.mock;

import com.team.silveragent.domain.model.ToolModels.AppointmentMaterial;
import com.team.silveragent.domain.tool.MaterialPreparationTool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class H2MaterialPreparationTool implements MaterialPreparationTool {
    private static final List<String> ALLOWED_STATUSES =
            List.of("NOT_PREPARED", "PREPARED", "PHOTO_CONFIRMED");

    private final JdbcTemplate jdbc;
    private final ToolTraceStore traces;

    public H2MaterialPreparationTool(JdbcTemplate jdbc, ToolTraceStore traces) {
        this.jdbc = jdbc;
        this.traces = traces;
    }

    @Override
    @Transactional
    public List<AppointmentMaterial> initialize(String conversationId, String appointmentId,
                                                String department, List<String> materialNames) {
        if (materialNames == null || materialNames.isEmpty()) return List.of();
        for (String materialName : materialNames) {
            TemplateRow template = findTemplate(department, materialName);
            String code = template == null ? slug(materialName) : template.id();
            boolean required = template != null && template.required();
            jdbc.update("""
                    INSERT INTO appointment_materials
                    (id,appointment_id,material_code,material_name,required,status,confirm_source,photo_url,updated_at)
                    SELECT ?,?,?,?,?,'NOT_PREPARED',NULL,NULL,?
                    WHERE NOT EXISTS (
                      SELECT 1 FROM appointment_materials WHERE appointment_id=? AND material_code=?
                    )
                    """, "AM-" + UUID.randomUUID().toString().substring(0, 12), appointmentId,
                    code, materialName, required, Timestamp.valueOf(LocalDateTime.now()),
                    appointmentId, code);
        }
        List<AppointmentMaterial> result = queryMaterials(appointmentId);
        if (conversationId != null && !conversationId.isBlank()) {
            traces.record(conversationId, "material.initializePreparation",
                    Map.of("appointmentId", appointmentId, "department", department,
                            "materials", materialNames), result, true);
        }
        return result;
    }

    @Override
    @Transactional
    public List<AppointmentMaterial> list(String userId, String appointmentId) {
        AppointmentSource source = requireAppointment(userId, appointmentId);
        List<AppointmentMaterial> rows = queryMaterials(appointmentId);
        if (!rows.isEmpty()) return rows;
        List<String> legacyMaterials = splitMaterials(source.materials());
        if (legacyMaterials.isEmpty()) {
            legacyMaterials = jdbc.query("""
                    SELECT material_name FROM material_templates
                    WHERE department='通用' OR department=?
                    ORDER BY required DESC,sort_order
                    """, (rs, row) -> rs.getString(1), source.department());
        }
        return initialize(null, appointmentId, source.department(), legacyMaterials);
    }

    @Override
    @Transactional
    public AppointmentMaterial updateStatus(String userId, String appointmentId, String materialId,
                                            String status, String confirmSource, String photoUrl) {
        requireAppointment(userId, appointmentId);
        if (!ALLOWED_STATUSES.contains(status)) {
            throw new IllegalArgumentException("不支持的材料状态：" + status);
        }
        String safeSource = confirmSource == null || confirmSource.isBlank()
                ? "USER" : confirmSource.trim();
        int changed = jdbc.update("""
                UPDATE appointment_materials
                SET status=?,confirm_source=?,photo_url=?,updated_at=?
                WHERE id=? AND appointment_id=?
                """, status, safeSource, photoUrl, Timestamp.valueOf(LocalDateTime.now()),
                materialId, appointmentId);
        if (changed != 1) throw new IllegalArgumentException("没有找到这项预约材料");
        return jdbc.queryForObject("""
                SELECT id,appointment_id,material_code,material_name,required,status,
                       confirm_source,photo_url,updated_at
                FROM appointment_materials WHERE id=?
                """, this::mapMaterial, materialId);
    }

    private AppointmentSource requireAppointment(String userId, String appointmentId) {
        List<AppointmentSource> rows = jdbc.query("""
                SELECT s.department,a.materials
                FROM appointments a JOIN appointment_slots s ON s.id=a.slot_id
                WHERE a.id=? AND a.user_id=?
                """, (rs, row) -> new AppointmentSource(rs.getString(1), rs.getString(2)),
                appointmentId, userId);
        if (rows.isEmpty()) throw new IllegalArgumentException("没有找到这条预约记录");
        return rows.get(0);
    }

    private List<AppointmentMaterial> queryMaterials(String appointmentId) {
        return jdbc.query("""
                SELECT id,appointment_id,material_code,material_name,required,status,
                       confirm_source,photo_url,updated_at
                FROM appointment_materials
                WHERE appointment_id=?
                ORDER BY required DESC,material_name
                """, this::mapMaterial, appointmentId);
    }

    private AppointmentMaterial mapMaterial(ResultSet rs, int row) throws SQLException {
        return new AppointmentMaterial(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getBoolean(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getTimestamp(9).toLocalDateTime());
    }

    private TemplateRow findTemplate(String department, String materialName) {
        List<TemplateRow> rows = jdbc.query("""
                SELECT id,required FROM material_templates
                WHERE material_name=? AND (department='通用' OR department=?)
                ORDER BY CASE WHEN department=? THEN 0 ELSE 1 END
                """, (rs, row) -> new TemplateRow(rs.getString(1), rs.getBoolean(2)),
                materialName, department, department);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<String> splitMaterials(String value) {
        if (value == null || value.isBlank()) return new ArrayList<>();
        return Arrays.stream(value.split("、")).map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private String slug(String value) {
        return "custom-" + Integer.toUnsignedString(value.hashCode(), 36);
    }

    private record TemplateRow(String id, boolean required) { }
    private record AppointmentSource(String department, String materials) { }
}
