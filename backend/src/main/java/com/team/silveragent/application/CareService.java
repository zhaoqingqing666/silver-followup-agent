package com.team.silveragent.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 协同照护端只读查询：家属 / 志愿者查看其协同就诊人的复诊概况、动态与通知。
 * 本类只做读操作，不写入任何业务数据。
 */
@Service
public class CareService {
    private final JdbcTemplate jdbc;
    private final AppointmentRecordStore records;

    public CareService(JdbcTemplate jdbc, AppointmentRecordStore records) {
        this.jdbc = jdbc;
        this.records = records;
    }

    /** 我协同的长辈（照护总览）。 */
    public List<ElderSummary> elders(String caregiverId) {
        List<ElderSummary> result = new ArrayList<>();
        jdbc.query("""
                SELECT cr.elder_user_id, cr.role, cr.relationship, u.name
                FROM care_relations cr JOIN users u ON u.id = cr.elder_user_id
                WHERE cr.caregiver_id = ?
                ORDER BY cr.id
                """, (rs, row) -> {
            String elderId = rs.getString(1);
            result.add(new ElderSummary(
                    elderId,
                    rs.getString(4),
                    rs.getString(3),
                    rs.getString(2),
                    latestSnapshot(elderId),
                    alertFor(elderId),
                    attentionFor(elderId)));
            return null;
        }, caregiverId);
        return result;
    }

    /** 这位长辈最近一条“请求协助 / 紧急暂停”类的动态（需要照护者关注）；无则 null。 */
    private ElderSummary.Attention attentionFor(String elderUserId) {
        List<ElderSummary.Attention> found = new ArrayList<>();
        jdbc.query("""
                SELECT kind, content, created_at FROM care_notifications
                WHERE elder_user_id = ? AND kind IN ('help','emergency')
                """, (rs, row) -> {
            CareEventLabel label = CARE_LABELS.getOrDefault(rs.getString(1), CARE_LABELS.get("info"));
            found.add(new ElderSummary.Attention(label.title(), label.tone(), rs.getString(2),
                    rs.getTimestamp(3).toLocalDateTime()));
            return null;
        }, elderUserId);
        jdbc.query("""
                SELECT updated_at FROM conversation_sessions
                WHERE user_id = ? AND stage = 'EMERGENCY_PAUSED'
                """, (rs, row) -> {
            found.add(new ElderSummary.Attention("紧急暂停", "danger", "复诊办理已紧急暂停，建议联系家属或人工帮助",
                    rs.getTimestamp(1).toLocalDateTime()));
            return null;
        }, elderUserId);
        found.sort(Comparator.comparing(ElderSummary.Attention::at).reversed());
        return found.isEmpty() ? null : found.get(0);
    }

    /** 收件箱：该照护者绑定的长辈的家属通知 + 定向发给他本人的协同通知，倒序。 */
    public List<NotificationView> notifications(String caregiverId) {
        List<NotificationView> result = new ArrayList<>();
        // 1) 长辈发给家属的通知（沿用 family_notifications，向该照护者绑定的所有长辈归集）
        jdbc.query("""
                SELECT n.id, fc.user_id, u.name, n.content, n.created_at
                FROM family_notifications n
                JOIN family_contacts fc ON fc.id = n.contact_id
                JOIN users u ON u.id = fc.user_id
                WHERE fc.user_id IN (
                    SELECT elder_user_id FROM care_relations WHERE caregiver_id = ?
                )
                """, (rs, row) -> {
            result.add(new NotificationView(
                    rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getTimestamp(5).toLocalDateTime()));
            return null;
        }, caregiverId);
        // 2) 定向发给该照护者的协同通知（代约结果、老人改动、紧急等）
        jdbc.query("""
                SELECT n.id, n.elder_user_id, u.name, n.content, n.created_at
                FROM care_notifications n
                JOIN users u ON u.id = n.elder_user_id
                WHERE n.caregiver_id = ?
                """, (rs, row) -> {
            result.add(new NotificationView(
                    rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getTimestamp(5).toLocalDateTime()));
            return null;
        }, caregiverId);
        result.sort((left, right) -> right.sentAt().compareTo(left.sentAt()));
        return result;
    }

    /** 单长辈复诊动态：带时间戳的审计时间线，只读。 */
    public List<TimelineEvent> timeline(String caregiverId, String elderUserId) {
        if (!bound(caregiverId, elderUserId)) {
            throw new IllegalArgumentException("该照护者未绑定这位就诊人");
        }
        List<TimelineEvent> events = new ArrayList<>();

        // 1) 有意义的工具执行记录（排除内部查询与已在通知里体现的 family.notify）
        jdbc.query("""
                SELECT tl.tool_name, tl.created_at
                FROM tool_call_logs tl
                JOIN conversation_sessions cs ON cs.id = tl.conversation_id
                WHERE cs.user_id = ? AND tl.tool_name IN (
                    'appointment.submit','appointment.cancel','appointment.reschedule',
                    'appointment.queryAlternatives','schedule.checkConflict',
                    'schedule.createReminder','travel.plan','workflow.error'
                )
                """, (rs, row) -> {
            ToolLabel label = TOOL_LABELS.get(rs.getString(1));
            if (label != null) {
                events.add(new TimelineEvent(rs.getTimestamp(2).toLocalDateTime(),
                        label.tone(), label.title(), null));
            }
            return null;
        }, elderUserId);

        // 2) 已发送给家属的通知
        jdbc.query("""
                SELECT n.content, n.created_at
                FROM family_notifications n
                JOIN family_contacts fc ON fc.id = n.contact_id
                WHERE fc.user_id = ?
                """, (rs, row) -> {
            events.add(new TimelineEvent(rs.getTimestamp(2).toLocalDateTime(),
                    "info", "通知家属", rs.getString(1)));
            return null;
        }, elderUserId);

        // 3) 协同端定向通知（代约、老人改动、求助、紧急等）
        jdbc.query("""
                SELECT n.kind, n.content, n.created_at
                FROM care_notifications n
                WHERE n.elder_user_id = ?
                """, (rs, row) -> {
            String kind = rs.getString(1);
            CareEventLabel label = CARE_LABELS.getOrDefault(kind, CARE_LABELS.get("info"));
            events.add(new TimelineEvent(rs.getTimestamp(3).toLocalDateTime(),
                    label.tone(), label.title(), rs.getString(2)));
            return null;
        }, elderUserId);

        // 4) 紧急暂停（会话停留在 EMERGENCY_PAUSED）
        jdbc.query("""
                SELECT updated_at FROM conversation_sessions
                WHERE user_id = ? AND stage = 'EMERGENCY_PAUSED'
                """, (rs, row) -> {
            events.add(new TimelineEvent(rs.getTimestamp(1).toLocalDateTime(),
                    "danger", "紧急暂停", "复诊办理已紧急暂停，建议联系家属或人工帮助"));
            return null;
        }, elderUserId);

        events.sort(Comparator.comparing(TimelineEvent::at).reversed());
        return events.size() > 50 ? events.subList(0, 50) : events;
    }

    /**
     * 照护关系本身：role 决定会话身份，relationship 只用于话术。
     * 建立会话时用它做归属校验，并把关系称呼固定进会话；查不到就是没授权。
     */
    public java.util.Optional<CareRelation> relation(String caregiverId, String elderUserId) {
        if (caregiverId == null || elderUserId == null) return java.util.Optional.empty();
        return jdbc.query("""
                SELECT cr.role, cr.relationship FROM care_relations cr
                WHERE cr.caregiver_id = ? AND cr.elder_user_id = ?
                ORDER BY cr.id LIMIT 1
                """, (rs, row) -> new CareRelation(rs.getString(1), rs.getString(2)),
                caregiverId, elderUserId).stream().findFirst();
    }

    public boolean bound(String caregiverId, String elderUserId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM care_relations
                WHERE caregiver_id = ? AND elder_user_id = ?
                """, Integer.class, caregiverId, elderUserId);
        return count != null && count > 0;
    }

    private ElderSummary.AppointmentSnapshot latestSnapshot(String elderUserId) {
        List<AppointmentRecordStore.AppointmentView> all = records.allFor(elderUserId);
        AppointmentRecordStore.AppointmentView chosen = all.stream()
                .filter(item -> "CONFIRMED".equals(item.status()))
                .findFirst()
                .orElse(all.isEmpty() ? null : all.get(0));
        if (chosen == null) return null;
        String departure = chosen.departureAt() == null
                ? null : chosen.departureAt().toLocalTime().toString();
        return new ElderSummary.AppointmentSnapshot(
                chosen.appointmentId(), chosen.date().toString(), chosen.time().toString(),
                chosen.hospital(), chosen.department(), chosen.status(), departure,
                chosen.transport(), chosen.familyStatus(), chosen.accompaniedBy());
    }

    private String alertFor(String elderUserId) {
        List<AppointmentRecordStore.AppointmentView> all = records.allFor(elderUserId);
        boolean cancelled = all.stream().anyMatch(item -> "CANCELLED".equals(item.status()));
        boolean hasUpcoming = all.stream().anyMatch(item -> "CONFIRMED".equals(item.status())
                && !item.date().isBefore(LocalDate.now()));
        // 只有“取消了旧预约且没有再安排新的”才提示，避免已重新安排仍出现旧警告
        return cancelled && !hasUpcoming ? "有预约已取消，建议尽快重新安排" : null;
    }

    private record ToolLabel(String tone, String title) { }

    private record CareEventLabel(String tone, String title) { }

    private static final Map<String, CareEventLabel> CARE_LABELS = Map.of(
            "book", new CareEventLabel("success", "家属已代约复诊"),
            "reschedule", new CareEventLabel("warning", "复诊已改期"),
            "cancel", new CareEventLabel("danger", "预约已取消"),
            "help", new CareEventLabel("warning", "老人请求协助"),
            "emergency", new CareEventLabel("danger", "紧急暂停"),
            "info", new CareEventLabel("info", "协同提醒"));

    private static final Map<String, ToolLabel> TOOL_LABELS = Map.of(
            "appointment.submit", new ToolLabel("success", "预约已提交并确认"),
            "appointment.cancel", new ToolLabel("danger", "预约已取消"),
            "appointment.reschedule", new ToolLabel("warning", "复诊已改期"),
            "appointment.queryAlternatives", new ToolLabel("info", "提供可替代号源"),
            "schedule.checkConflict", new ToolLabel("info", "已检查日程冲突"),
            "schedule.createReminder", new ToolLabel("success", "已创建复诊提醒"),
            "travel.plan", new ToolLabel("info", "已生成出行建议"),
            "workflow.error", new ToolLabel("danger", "办理遇到问题"));

    /** 一条照护关系：role = FAMILY / VOLUNTEER，relationship = 女儿 / 社区志愿者 等称呼。 */
    public record CareRelation(String role, String relationship) { }

    /** 照护总览里的一位长辈及其最近预约快照。 */
    public record ElderSummary(
            String elderId, String name, String relationship, String role,
            AppointmentSnapshot latestAppointment, String alert, Attention attention) {
        public record AppointmentSnapshot(
                String appointmentId, String date, String time,
                String hospital, String department, String status,
                String departureAt, String transport, String familyStatus,
                String accompaniedBy) { }
        public record Attention(String title, String tone, String detail, LocalDateTime at) { }
    }

    /** 收件箱条目。 */
    public record NotificationView(
            String notificationId, String elderId, String elderName,
            String content, LocalDateTime sentAt) { }

    /** 复诊动态事件。tone: info | success | warning | danger */
    public record TimelineEvent(
            LocalDateTime at, String tone, String title, String detail) { }
}
