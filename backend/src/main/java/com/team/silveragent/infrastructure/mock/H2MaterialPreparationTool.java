package com.team.silveragent.infrastructure.mock;

import com.team.silveragent.application.ConversationStore;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class H2MaterialPreparationTool implements MaterialPreparationTool {
    private static final List<String> ALLOWED_STATUSES =
            List.of("NOT_PREPARED", "PREPARED", "PHOTO_CONFIRMED");
    /** 确认来源：USER 是老人自己勾的，PHOTO 是拍了照确认的。 */
    private static final Set<String> ALLOWED_SOURCES = Set.of("USER", "PHOTO");
    /** photo_url 里存的是这种短引用，真正的图片本体在 conversation_attachments。 */
    private static final String PHOTO_REF_PREFIX = "attachment:";
    /** 照片 data URL 的长度上限，约 1.5MB 二进制。 */
    private static final int MAX_PHOTO_CHARS = 2_000_000;

    private final JdbcTemplate jdbc;
    private final ToolTraceStore traces;
    private final ConversationStore conversations;

    public H2MaterialPreparationTool(JdbcTemplate jdbc, ToolTraceStore traces,
                                     ConversationStore conversations) {
        this.jdbc = jdbc;
        this.traces = traces;
        this.conversations = conversations;
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
        if (status == null || !ALLOWED_STATUSES.contains(status)) {
            throw new IllegalArgumentException("不支持的材料状态：" + status);
        }
        String safeSource = normalizeSource(confirmSource);
        String safePhoto = storePhoto(appointmentId, photoUrl);
        int changed = jdbc.update("""
                UPDATE appointment_materials
                SET status=?,confirm_source=?,photo_url=?,updated_at=?
                WHERE id=? AND appointment_id=?
                """, status, safeSource, safePhoto, Timestamp.valueOf(LocalDateTime.now()),
                materialId, appointmentId);
        if (changed != 1) throw new IllegalArgumentException("没有找到这项预约材料");
        return jdbc.queryForObject("""
                SELECT id,appointment_id,material_code,material_name,required,status,
                       confirm_source,photo_url,updated_at
                FROM appointment_materials WHERE id=?
                """, this::mapMaterial, materialId);
    }

    /** 确认来源只认 USER / PHOTO；前端塞别的字符串一律当 USER，不让脏值进库。 */
    private String normalizeSource(String confirmSource) {
        if (confirmSource == null || confirmSource.isBlank()) return "USER";
        String value = confirmSource.trim().toUpperCase(Locale.ROOT);
        return ALLOWED_SOURCES.contains(value) ? value : "USER";
    }

    /**
     * 照片落库：data URL 存进附件表，photo_url 只留 {@code attachment:<id>} 短引用。
     * photoUrl 为空表示这次没有照片（也要把上一张的引用清掉），返回 null 交给 UPDATE 写空。
     */
    private String storePhoto(String appointmentId, String photoUrl) {
        if (photoUrl == null || photoUrl.isBlank()) return null;
        String trimmed = photoUrl.trim();
        // 已经是短引用（同一次确认被重复提交）就原样沿用，别把同一张照片存两遍。
        // 但引用是客户端传上来的，必须确认它真是这条预约自己的照片——
        // 否则改个编号就能把别人的照片挂到自己的材料上。
        if (trimmed.startsWith(PHOTO_REF_PREFIX)) {
            return readPhotoRef(appointmentId, trimmed)
                    .map(ignored -> trimmed)
                    .orElseThrow(() -> new IllegalArgumentException("这张照片已经失效，请重新拍照"));
        }
        if (!trimmed.startsWith("data:image/")) {
            throw new IllegalArgumentException("只支持图片，请重新拍照或用相册选一张图");
        }
        // 压缩后通常是几万到几十万字符；到这个量级说明前端没压或压失败了，宁可让老人重拍
        if (trimmed.length() > MAX_PHOTO_CHARS) {
            throw new IllegalArgumentException("这张照片太大了，请重新拍一张");
        }
        long attachmentId = conversations.addMaterialPhoto(appointmentId, trimmed);
        if (attachmentId < 0) throw new IllegalStateException("照片没有保存成功，请重试");
        return PHOTO_REF_PREFIX + attachmentId;
    }

    /**
     * 解析 {@code attachment:<id>} 引用，取回照片本体；不是这条预约自己的照片就返回空。
     * 调用方不该直接接受附件编号——那等于把「按编号取任意附件」的能力开放出去。
     */
    private Optional<String> readPhotoRef(String appointmentId, String ref) {
        if (ref == null || !ref.startsWith(PHOTO_REF_PREFIX)) return Optional.empty();
        String digits = ref.substring(PHOTO_REF_PREFIX.length());
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) return Optional.empty();
        try {
            return conversations.findMaterialPhoto(appointmentId, Long.parseLong(digits));
        } catch (NumberFormatException tooLong) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> photo(String userId, String appointmentId, String materialId) {
        requireAppointment(userId, appointmentId);
        List<String> refs = jdbc.queryForList("""
                SELECT photo_url FROM appointment_materials WHERE id=? AND appointment_id=?
                """, String.class, materialId, appointmentId);
        if (refs.isEmpty()) throw new IllegalArgumentException("没有找到这项预约材料");
        // 只认这一行自己写下的引用，不接受调用方传附件编号
        return readPhotoRef(appointmentId, refs.get(0));
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
