package com.team.silveragent.application.care;

import com.team.silveragent.application.AppointmentRecordStore;

import com.team.silveragent.domain.model.ToolModels.Slot;
import com.team.silveragent.domain.model.ToolModels.TravelPlan;
import com.team.silveragent.domain.tool.AppointmentTool;
import com.team.silveragent.domain.tool.MaterialChecklistTool;
import com.team.silveragent.domain.tool.ScheduleTool;
import com.team.silveragent.domain.tool.TravelTool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 协同照护端“帮助预约”：
 * 家属 / 志愿者替其协同的就诊人一次性安排复诊（方案 B）。
 * 复用老人端同一套号源 / 出行 / 材料 / 提醒逻辑，把预约落在老人本人账号下，
 * 并标记 arranged_by 以便老人端助手识别与后续通知。
 */
@Service
public class CareBookingService {
    private final JdbcTemplate jdbc;
    private final CareCatalogRepository catalog;
    private final AppointmentRecordStore records;
    private final AppointmentTool appointmentTool;
    private final MaterialChecklistTool materialTool;
    private final ScheduleTool scheduleTool;
    private final TravelTool travelTool;

    public CareBookingService(JdbcTemplate jdbc, CareCatalogRepository catalog, AppointmentRecordStore records,
                              AppointmentTool appointmentTool, MaterialChecklistTool materialTool,
                              ScheduleTool scheduleTool, TravelTool travelTool) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.records = records;
        this.appointmentTool = appointmentTool;
        this.materialTool = materialTool;
        this.scheduleTool = scheduleTool;
        this.travelTool = travelTool;
    }

    public record BookingRequest(
            String hospitalId, String departmentId, String date,
            String slotId, Boolean needTravel, String transport, Boolean willAccompany) {
        /** 兼容旧调用（未显式确认陪同按不陪同处理）。 */
        public BookingRequest(String hospitalId, String departmentId, String date,
                              String slotId, Boolean needTravel, String transport) {
            this(hospitalId, departmentId, date, slotId, needTravel, transport, null);
        }
    }

    /** 可约日期 + 该日期下仍可预约的号源（真号源，只给有号的）。 */
    public record DateWindow(String date, List<SlotOption> slots) {
        public record SlotOption(String slotId, String time) { }
    }

    public List<CareCatalogRepository.Hospital> hospitals(String caregiverId, String elderUserId) {
        requireBound(caregiverId, elderUserId);
        return catalog.hospitals();
    }

    public List<CareCatalogRepository.Department> departments(String caregiverId, String elderUserId, String hospitalId) {
        requireBound(caregiverId, elderUserId);
        return catalog.departments(hospitalId);
    }

    public List<DateWindow> windows(String caregiverId, String elderUserId, String hospitalId, String departmentId) {
        requireBound(caregiverId, elderUserId);
        CareCatalogRepository.Department department = catalog.department(hospitalId, departmentId)
                .orElseThrow(() -> new IllegalArgumentException("没有找到该科室"));
        List<LocalDate> dates = catalog.availableDates(hospitalId, department.name(), LocalDate.now(), 3);
        List<DateWindow> result = new ArrayList<>();
        for (LocalDate date : dates) {
            List<Slot> slots = appointmentTool.queryAvailableSlots("", hospitalId, department.name(), date);
            List<DateWindow.SlotOption> options = slots.stream()
                    .map(slot -> new DateWindow.SlotOption(slot.id(), slot.time().toString()))
                    .toList();
            if (!options.isEmpty()) result.add(new DateWindow(date.toString(), options));
        }
        return result;
    }

    /** 替老人完成一次复诊预约：老人本人名下只保留一个进行中的预约。 */
    @Transactional
    public AppointmentRecordStore.AppointmentView book(String caregiverId, String elderUserId, BookingRequest request) {
        requireBound(caregiverId, elderUserId);
        if (request.hospitalId() == null || request.departmentId() == null
                || request.date() == null || request.slotId() == null || request.transport() == null) {
            throw new IllegalArgumentException("请补齐医院、科室、日期、号源和交通方式");
        }
        if (hasUpcoming(elderUserId)) {
            throw new IllegalArgumentException("这位就诊人已有一个进行中的复诊预约，请先调整或取消已有预约，再重新安排");
        }
        CareCatalogRepository.Hospital hospital = catalog.hospital(request.hospitalId())
                .orElseThrow(() -> new IllegalArgumentException("没有找到该医院"));
        CareCatalogRepository.Department department = catalog.department(request.hospitalId(), request.departmentId())
                .orElseThrow(() -> new IllegalArgumentException("没有找到该科室"));
        LocalDate date = LocalDate.parse(request.date());
        if (date.isBefore(LocalDate.now())) throw new IllegalArgumentException("该日期已经过去，请重新选择");

        String bookingId = "CB-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        List<Slot> available = appointmentTool.queryAvailableSlots(bookingId, hospital.id(), department.name(), date);
        Slot slot = available.stream().filter(item -> item.id().equals(request.slotId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("所选号源已不可用或不在该日期的可选范围内，请重新选择"));

        List<String> materials = materialTool.checklist(bookingId, hospital.name(), department.name());
        LocalDateTime at = LocalDateTime.of(slot.date(), slot.time());
        TravelPlan travel = travelTool.plan(bookingId, elderUserId, hospital.name(), at, request.transport());
        boolean needTravel = Boolean.TRUE.equals(request.needTravel());

        String appointmentId = appointmentTool.submit(bookingId, slot.id(), elderUserId);
        scheduleTool.createReminder(bookingId, elderUserId, "复诊材料准备提醒", at.minusDays(1));
        if (needTravel) {
            scheduleTool.createReminder(bookingId, elderUserId, "复诊出发提醒", travel.departureAt().minusMinutes(10));
        }
        String reminderStatus = needTravel ? "复诊及出发提醒已创建" : "复诊提醒已创建，不创建出发提醒";
        String familyStatus = arrangerText(caregiverId, elderUserId);
        boolean willAccompany = Boolean.TRUE.equals(request.willAccompany());
        records.applyBooking(appointmentId, Timestamp.valueOf(travel.departureAt()), request.transport(),
                reminderStatus, familyStatus, materials, caregiverId,
                willAccompany ? caregiverId : null);

        // 志愿者代约 → 告知家庭成员照护者；无人在场或家属本人代约 → 给本人留一条“已代约”记录，
        // 保证就诊动态/消息里能看到这次新建（而非只看到取消）。
        int familyNotified = 0;
        if (isRole(caregiverId, elderUserId, "VOLUNTEER")) {
            familyNotified = notifyBookingToFamilyCaregivers(caregiverId, elderUserId,
                    hospital.name(), department.name(), slot.date(), slot.time(), null);
        }
        if (familyNotified == 0) {
            String elderName = jdbc.queryForObject("SELECT name FROM users WHERE id=?", String.class, elderUserId);
            notifyCaregiver(caregiverId, elderUserId,
                    "已为" + elderName + "代约复诊：" + hospital.name() + " " + department.name() + "，"
                            + slot.date().getMonthValue() + "月" + slot.date().getDayOfMonth() + "日 " + slot.time() + "。",
                    "book");
        }
        return records.allFor(elderUserId).stream().filter(item -> item.appointmentId().equals(appointmentId))
                .findFirst().orElseThrow(() -> new IllegalStateException("预约已创建，但读取结果失败，请刷新查看"));
    }

    /**
     * 家属/志愿者修改长辈“当前这张进行中的预约”（改期或换科室），确认后原位更新同一条记录：
     * 换号源、停用旧提醒再建新提醒，不留“已取消”记录。安排者归属保持不变；若原安排者是别人会通知对方。
     */
    @Transactional
    public AppointmentRecordStore.AppointmentView modify(String caregiverId, String elderUserId, BookingRequest request) {
        requireBound(caregiverId, elderUserId);
        if (request.hospitalId() == null || request.departmentId() == null
                || request.date() == null || request.slotId() == null || request.transport() == null) {
            throw new IllegalArgumentException("请补齐医院、科室、日期、号源和交通方式");
        }
        AppointmentRecordStore.AppointmentView target = records.allFor(elderUserId).stream()
                .filter(item -> "CONFIRMED".equals(item.status()) && !item.date().isBefore(LocalDate.now()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("这位长辈当前没有可修改的进行中复诊预约"));
        CareCatalogRepository.Hospital hospital = catalog.hospital(request.hospitalId())
                .orElseThrow(() -> new IllegalArgumentException("没有找到该医院"));
        CareCatalogRepository.Department department = catalog.department(request.hospitalId(), request.departmentId())
                .orElseThrow(() -> new IllegalArgumentException("没有找到该科室"));
        LocalDate date = LocalDate.parse(request.date());
        if (date.isBefore(LocalDate.now())) throw new IllegalArgumentException("该日期已经过去，请重新选择");

        String bookingId = "CB-MOD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        List<Slot> available = appointmentTool.queryAvailableSlots(bookingId, hospital.id(), department.name(), date);
        Slot slot = available.stream().filter(item -> item.id().equals(request.slotId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("所选号源已不可用或不在该日期的可选范围内，请重新选择"));

        List<String> materials = materialTool.checklist(bookingId, hospital.name(), department.name());
        LocalDateTime at = LocalDateTime.of(slot.date(), slot.time());
        TravelPlan travel = travelTool.plan(bookingId, elderUserId, hospital.name(), at, request.transport());
        boolean needTravel = Boolean.TRUE.equals(request.needTravel());

        // 原位换号源：同一张预约行换 slot/科室/时间，旧提醒作废，号源释放与占用由工具处理
        appointmentTool.reschedule(bookingId, target.appointmentId(), slot.id(), elderUserId);
        scheduleTool.createReminder(bookingId, elderUserId, "复诊材料准备提醒", at.minusDays(1));
        if (needTravel) {
            scheduleTool.createReminder(bookingId, elderUserId, "复诊出发提醒", travel.departureAt().minusMinutes(10));
        }
        String reminderStatus = needTravel ? "复诊及出发提醒已创建" : "复诊提醒已创建，不创建出发提醒";
        // 安排者归属不变（老人自约的仍归老人）；老人自约的保留原 family_status，代约的按安排者重新生成
        String arrangedBy = target.arrangedBy();
        String familyStatus = arrangedBy == null ? target.familyStatus() : arrangerText(arrangedBy, elderUserId);
        boolean willAccompany = Boolean.TRUE.equals(request.willAccompany());
        records.applyBooking(target.appointmentId(), Timestamp.valueOf(travel.departureAt()), request.transport(),
                reminderStatus, familyStatus, materials, arrangedBy, willAccompany ? caregiverId : null);

        // 保证这次改动在就诊动态里可见：至少写一条 care_notifications（原安排者/家人），
        // 都没写到（家属改自己约的、或无人可通知）→ 给本人留一条 kind=reschedule 的回执。
        int written = 0;
        // 改的是别人代约的安排 → 通知原安排者
        if (arrangedBy != null && !arrangedBy.equals(caregiverId)) {
            notifyCaregiver(arrangedBy, elderUserId,
                    "改期通知：已修改您代约的复诊：" + managedText(target) + "，新的安排："
                            + hospital.name() + " " + department.name() + "，" + date.getMonthValue() + "月"
                            + date.getDayOfMonth() + "日 " + slot.time() + "。", "reschedule");
            written++;
        }
        // 志愿者改动他人安排 → 知会家庭成员照护者（避开原安排者，避免重复通知）
        if (isRole(caregiverId, elderUserId, "VOLUNTEER")) {
            written += notifyBookingToFamilyCaregivers(caregiverId, elderUserId, hospital.name(), department.name(),
                    slot.date(), slot.time(), arrangedBy);
        }
        if (written == 0) {
            String elderName = jdbc.queryForObject("SELECT name FROM users WHERE id=?", String.class, elderUserId);
            notifyCaregiver(caregiverId, elderUserId,
                    "已把" + elderName + "的复诊改到：" + hospital.name() + " " + department.name() + "，"
                            + date.getMonthValue() + "月" + date.getDayOfMonth() + "日 " + slot.time() + "。",
                    "reschedule");
        }
        return records.allFor(elderUserId).stream().filter(item -> item.appointmentId().equals(target.appointmentId()))
                .findFirst().orElseThrow(() -> new IllegalStateException("预约已修改，但读取结果失败，请刷新查看"));
    }

    /**
     * 只改“当前照护者是否陪同这次复诊”（不动预约本身）：
     * 陪同 → 把 accompanied_by 记为本人；不陪同 → 仅当此前记的是本人时才清空，避免误清别人的标记。
     */
    @Transactional
    public AppointmentRecordStore.AppointmentView toggleAccompany(String caregiverId, String elderUserId, boolean accompany) {
        requireBound(caregiverId, elderUserId);
        AppointmentRecordStore.AppointmentView target = records.allFor(elderUserId).stream()
                .filter(item -> "CONFIRMED".equals(item.status()) && !item.date().isBefore(LocalDate.now()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("这位长辈当前没有进行中的复诊预约"));
        String columnValue = accompany ? caregiverId
                : (caregiverId.equals(target.accompaniedBy()) ? null : target.accompaniedBy());
        jdbc.update("UPDATE appointments SET accompanied_by=? WHERE id=?",
                columnValue, target.appointmentId());
        return records.allFor(elderUserId).stream().filter(item -> item.appointmentId().equals(target.appointmentId()))
                .findFirst().orElseThrow(() -> new IllegalStateException("陪同状态已更新，但读取结果失败，请刷新查看"));
    }

    /** 给某位照护者发一条定向协同通知（改动/求助/紧急等场景复用）。 */
    public void notifyCaregiver(String caregiverId, String elderUserId, String content, String kind) {
        jdbc.update("INSERT INTO care_notifications(id,caregiver_id,elder_user_id,content,kind,created_at) VALUES (?,?,?,?,?,?)",
                "CN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                caregiverId, elderUserId, content, kind, Timestamp.valueOf(LocalDateTime.now()));
    }

    private void requireBound(String caregiverId, String elderUserId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM care_relations
                WHERE caregiver_id = ? AND elder_user_id = ?
                """, Integer.class, caregiverId, elderUserId);
        if (count == null || count == 0) throw new IllegalArgumentException("该照护者未绑定这位就诊人");
    }

    private boolean hasUpcoming(String elderUserId) {
        return records.allFor(elderUserId).stream()
                .anyMatch(item -> "CONFIRMED".equals(item.status()) && !item.date().isBefore(LocalDate.now()));
    }

    private boolean isRole(String caregiverId, String elderUserId, String role) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM care_relations
                WHERE caregiver_id = ? AND elder_user_id = ? AND role = ?
                """, Integer.class, caregiverId, elderUserId, role);
        return count != null && count > 0;
    }

    private String arrangerText(String caregiverId, String elderUserId) {
        List<String> rows = jdbc.query("""
                SELECT COALESCE(cr.relationship, u.name), u.name
                FROM care_relations cr JOIN users u ON u.id = cr.caregiver_id
                WHERE cr.caregiver_id = ? AND cr.elder_user_id = ?
                """, (rs, row) -> rs.getString(1) + "|" + rs.getString(2), caregiverId, elderUserId);
        String rel = rows.isEmpty() ? "家属" : rows.get(0).split("\\|")[0];
        String name = rows.isEmpty() ? caregiverId : rows.get(0).split("\\|")[1];
        return "由" + rel + " " + name + " 代约";
    }

    /** 志愿者代约/改动 → 通知该长辈非志愿者的家庭照护者，便于家人知晓安排；返回实际通知到的人数。 */
    private int notifyBookingToFamilyCaregivers(String volunteerId, String elderUserId,
                                                String hospital, String department, LocalDate date, java.time.LocalTime time,
                                                String excludeCaregiverId) {
        String elderName = jdbc.queryForObject("SELECT name FROM users WHERE id=?", String.class, elderUserId);
        String volunteerName = jdbc.queryForObject("SELECT name FROM users WHERE id=?", String.class, volunteerId);
        String content = volunteerName + " 已为" + elderName + "代约复诊：" + hospital + " " + department
                + "，" + date.getMonthValue() + "月" + date.getDayOfMonth() + "日 " + time + "。";
        List<String> caregivers = jdbc.query("""
                SELECT caregiver_id FROM care_relations
                WHERE elder_user_id = ? AND caregiver_id <> ? AND role <> 'VOLUNTEER' AND caregiver_id <> ?
                """, (rs, row) -> rs.getString(1), elderUserId, volunteerId,
                excludeCaregiverId == null ? "" : excludeCaregiverId);
        for (String caregiverId : caregivers) notifyCaregiver(caregiverId, elderUserId, content, "book");
        return caregivers.size();
    }

    /**
     * 家属/志愿者替老人“取消当前这张进行中的预约”，以便随后重新代约。
     * 复用老人端助手同一条取消链：释放号源、停用关联提醒、标记 CANCELLED；
     * 若这张预约本由其他照护者代约，顺带通知原安排者。
     */
    @Transactional
    public AppointmentRecordStore.AppointmentView cancelUpcoming(String caregiverId, String elderUserId) {
        requireBound(caregiverId, elderUserId);
        AppointmentRecordStore.AppointmentView target = records.allFor(elderUserId).stream()
                .filter(item -> "CONFIRMED".equals(item.status()) && !item.date().isBefore(LocalDate.now()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("这位长辈当前没有进行中的复诊预约，无需取消"));
        appointmentTool.cancel("CB-CANCEL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                target.appointmentId(), elderUserId);

        String elderName = jdbc.queryForObject("SELECT name FROM users WHERE id=?", String.class, elderUserId);
        notifyCaregiver(caregiverId, elderUserId,
                "已取消" + elderName + "的复诊：" + managedText(target) + "。号源已释放，可重新安排。",
                "cancel");
        if (target.arrangedBy() != null && !target.arrangedBy().equals(caregiverId)) {
            notifyCaregiver(target.arrangedBy(), elderUserId,
                    "取消通知：已取消您代约的复诊：" + managedText(target) + "。预约与关联提醒已失效。",
                    "cancel");
        }
        return target;
    }

    private String managedText(AppointmentRecordStore.AppointmentView view) {
        return view.hospital() + " " + view.department() + "，"
                + view.date().getMonthValue() + "月" + view.date().getDayOfMonth() + "日 "
                + view.time().getHour() + ":" + String.format("%02d", view.time().getMinute());
    }

}
