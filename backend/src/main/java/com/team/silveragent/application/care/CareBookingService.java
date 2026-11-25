package com.team.silveragent.application.care;

import com.team.silveragent.application.AppointmentRecordStore;
import com.team.silveragent.application.time.BusinessClock;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 协同照护端“帮助预约”：
 * 家属 / 志愿者替其协同的就诊人一次性安排复诊（方案 B）。
 * 复用老人端同一套号源 / 出行 / 材料 / 提醒逻辑，把预约落在老人本人账号下，
 * 并标记 arranged_by 以便老人端助手识别与后续通知。
 *
 * <p>写操作分两条路进来，确认机制不同：助手路径由 {@code ConversationState.confirmationId}
 * 把关，直接调 {@link #book}/{@link #modify}/{@link #cancelUpcoming}；表单路径没有会话，
 * 走 {@link #previewBooking} 等预览方法拿票据，再由 {@link #bookConfirmed} 等核销票据后执行。
 * 门禁必须留在这一层之外（控制器），否则会把助手那条已经确认过的路一起挡掉。
 */
@Service
public class CareBookingService {
    /** 表单路径的动作名。放进票据里，防止拿「代约」的票据去执行「取消」。 */
    public static final String ACTION_BOOK = "BOOK";
    public static final String ACTION_MODIFY = "MODIFY";
    public static final String ACTION_CANCEL = "CANCEL";

    /** 票据对不上时统一说这一句：家属听得懂，也知道下一步该做什么。 */
    public static final String CONFIRMATION_STALE = "这份确认已经失效，请重新查看要办理的内容后再确认。";

    private final JdbcTemplate jdbc;
    private final CareCatalogRepository catalog;
    private final AppointmentRecordStore records;
    private final AppointmentTool appointmentTool;
    private final MaterialChecklistTool materialTool;
    private final ScheduleTool scheduleTool;
    private final TravelTool travelTool;
    private final BusinessClock clock;
    private final CareConfirmationStore confirmations;

    public CareBookingService(JdbcTemplate jdbc, CareCatalogRepository catalog, AppointmentRecordStore records,
                              AppointmentTool appointmentTool, MaterialChecklistTool materialTool,
                              ScheduleTool scheduleTool, TravelTool travelTool, BusinessClock clock,
                              CareConfirmationStore confirmations) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.records = records;
        this.appointmentTool = appointmentTool;
        this.materialTool = materialTool;
        this.scheduleTool = scheduleTool;
        this.travelTool = travelTool;
        this.clock = clock;
        this.confirmations = confirmations;
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

    /**
     * 表单路径的确认卡内容，整份由后端从真实目录和真实预约算出来，前端只负责展示。
     *
     * <p>{@code serviceSubject} 与 {@code arrangement} 是需求 N-03 点名要有的两条：替谁办、
     * 这份预约记在谁名下。以前表单只显示医院科室时间，这两条是缺的。
     *
     * @param operations 实际会执行的动作清单。只列真会做的事，用户拒绝的部分不出现。
     */
    public record BookingPreview(String confirmationId, String action, String serviceSubject,
                                 String arrangement, String hospital, String department,
                                 String date, String time, String transport,
                                 boolean needTravel, boolean willAccompany,
                                 List<String> operations) { }

    /**
     * 把请求里真正会改变执行结果的字段抽成一张快照：确认时逐项比对，防止用户看到的
     * 和提交的不是一回事。顺序无关（用 Map 相等判定），值一律不走 null。
     */
    public static Map<String, String> snapshotOf(BookingRequest request) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("hospitalId", orEmpty(request.hospitalId()));
        snapshot.put("departmentId", orEmpty(request.departmentId()));
        snapshot.put("date", orEmpty(request.date()));
        snapshot.put("slotId", orEmpty(request.slotId()));
        snapshot.put("transport", orEmpty(request.transport()));
        snapshot.put("needTravel", String.valueOf(Boolean.TRUE.equals(request.needTravel())));
        snapshot.put("willAccompany", String.valueOf(Boolean.TRUE.equals(request.willAccompany())));
        return Map.copyOf(snapshot);
    }

    /**
     * 取消没有请求参数，快照记的是<b>那张预约的身份</b>：医院、科室、日期、时间都要对上。
     *
     * <p>光比 appointmentId 不够——改期是「原地更新同一条记录」，id 不变而日期已经换过了。
     * 家属看到的还是「周三 神经内科」，实际要取消的已经变成「周五 心内科」。
     */
    public static Map<String, String> cancelSnapshotOf(AppointmentRecordStore.AppointmentView view) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("appointmentId", orEmpty(view.appointmentId()));
        snapshot.put("hospital", orEmpty(view.hospital()));
        snapshot.put("department", orEmpty(view.department()));
        snapshot.put("date", view.date() == null ? "" : view.date().toString());
        snapshot.put("time", view.time() == null ? "" : view.time().toString());
        return Map.copyOf(snapshot);
    }

    private static String orEmpty(String value) { return value == null ? "" : value; }

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
        List<LocalDate> dates = catalog.availableDates(hospitalId, department.name(), clock.today(), 3);
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

    /**
     * 表单路径第 3 步的预览：把「点确认之后到底会发生什么」整份算出来给人看，并开一张票据。
     *
     * <p>这里跑的是和真正办理同一套解析逻辑，所以预览里的医院、科室、时间、材料就是待办的那一份，
     * 不是另算的一份近似值。{@code operations} 只列真会执行的动作——用户勾了不陪同就不出现陪同。
     */
    public BookingPreview previewBooking(String caregiverId, String elderUserId, BookingRequest request) {
        requireBound(caregiverId, elderUserId);
        if (hasUpcoming(elderUserId)) {
            throw new IllegalArgumentException("这位就诊人已有一个进行中的复诊预约，请先调整或取消已有预约，再重新安排");
        }
        Resolved resolved = resolve(elderUserId, request, "");
        List<String> operations = new ArrayList<>(List.of(
                "在 " + resolved.hospital().name() + " " + resolved.department().name() + " 提交复诊预约",
                "预约记在" + elderName(elderUserId) + "名下，" + arrangerText(caregiverId, elderUserId),
                "按所选的 " + resolved.materials().size() + " 项材料生成准备清单",
                "创建复诊材料准备提醒（就诊前一天）"));
        if (Boolean.TRUE.equals(request.needTravel())) {
            operations.add("创建复诊出发提醒（按 " + request.transport() + " 的出发时间提前 10 分钟）");
        }
        if (Boolean.TRUE.equals(request.willAccompany())) {
            operations.add("把陪同人记为" + caregiverName(caregiverId));
        }
        String ticket = confirmations.open(caregiverId, elderUserId, ACTION_BOOK, snapshotOf(request)).id();
        return new BookingPreview(ticket, ACTION_BOOK, elderName(elderUserId),
                arrangerText(caregiverId, elderUserId),
                resolved.hospital().name(), resolved.department().name(),
                resolved.slot().date().toString(), resolved.slot().time().toString(),
                orEmpty(request.transport()),
                Boolean.TRUE.equals(request.needTravel()), Boolean.TRUE.equals(request.willAccompany()),
                List.copyOf(operations));
    }

    /** 表单路径的改期预览：不动预约本身，先把改完之后的样子说清楚。 */
    public BookingPreview previewModify(String caregiverId, String elderUserId, BookingRequest request) {
        requireBound(caregiverId, elderUserId);
        AppointmentRecordStore.AppointmentView target = requireUpcoming(elderUserId,
                "这位长辈当前没有可修改的进行中复诊预约");
        Resolved resolved = resolve(elderUserId, request, "");
        String arrangement = target.arrangedBy() == null
                ? "老人本人自约，本次改动不改归属"
                : arrangerText(target.arrangedBy(), elderUserId);
        List<String> operations = new ArrayList<>(List.of(
                "把当前这张预约改到 " + resolved.hospital().name() + " " + resolved.department().name(),
                "原号源释放，改用新的号源",
                "停用旧的复诊提醒，按新时间重建",
                "预约归属不变（" + arrangement + "）"));
        if (Boolean.TRUE.equals(request.needTravel())) {
            operations.add("创建复诊出发提醒（按新的出发时间提前 10 分钟）");
        }
        if (Boolean.TRUE.equals(request.willAccompany())) {
            operations.add("把陪同人改为" + caregiverName(caregiverId));
        }
        String ticket = confirmations.open(caregiverId, elderUserId, ACTION_MODIFY, snapshotOf(request)).id();
        return new BookingPreview(ticket, ACTION_MODIFY, elderName(elderUserId), arrangement,
                resolved.hospital().name(), resolved.department().name(),
                resolved.slot().date().toString(), resolved.slot().time().toString(),
                orEmpty(request.transport()),
                Boolean.TRUE.equals(request.needTravel()), Boolean.TRUE.equals(request.willAccompany()),
                List.copyOf(operations));
    }

    /**
     * 表单路径的取消预览。取消是这里唯一不可逆的动作——号源一放出去就可能被别人抢走，
     * 所以更要让家属看清「现在要取消的是哪一张」，而不是凭页面上的旧信息点下去。
     */
    public BookingPreview previewCancel(String caregiverId, String elderUserId) {
        requireBound(caregiverId, elderUserId);
        AppointmentRecordStore.AppointmentView target = requireUpcoming(elderUserId,
                "这位长辈当前没有进行中的复诊预约，无需取消");
        String arrangement = target.arrangedBy() == null ? "老人本人自约" : arrangerText(target.arrangedBy(), elderUserId);
        List<String> operations = List.of(
                "取消这张复诊预约：" + managedText(target),
                "释放该号源，其他人可以再预约",
                "停用关联的复诊提醒",
                "取消不可撤销，需要重新安排只能再约一次");
        String ticket = confirmations.open(caregiverId, elderUserId, ACTION_CANCEL, cancelSnapshotOf(target)).id();
        return new BookingPreview(ticket, ACTION_CANCEL, elderName(elderUserId), arrangement,
                target.hospital(), target.department(),
                target.date().toString(), target.time().toString(),
                "", false, false, operations);
    }

    /**
     * 表单路径的正式办理：先核销票据，再走原本的写入逻辑。
     *
     * <p>票据由预览时开的、内容由预览时定的，此刻再拿请求重算一遍快照来比对——
     * 用户看到的和提交的必须是同一套。
     */
    @Transactional
    public AppointmentRecordStore.AppointmentView bookConfirmed(String caregiverId, String elderUserId,
                                                                BookingRequest request, String confirmationId) {
        requireBound(caregiverId, elderUserId);
        confirmations.consume(confirmationId, caregiverId, elderUserId, ACTION_BOOK, snapshotOf(request))
                .orElseThrow(() -> new IllegalArgumentException(CONFIRMATION_STALE));
        return book(caregiverId, elderUserId, request);
    }

    /** 见 {@link #bookConfirmed}。 */
    @Transactional
    public AppointmentRecordStore.AppointmentView modifyConfirmed(String caregiverId, String elderUserId,
                                                                  BookingRequest request, String confirmationId) {
        requireBound(caregiverId, elderUserId);
        confirmations.consume(confirmationId, caregiverId, elderUserId, ACTION_MODIFY, snapshotOf(request))
                .orElseThrow(() -> new IllegalArgumentException(CONFIRMATION_STALE));
        return modify(caregiverId, elderUserId, request);
    }

    /** 见 {@link #bookConfirmed}。取消的快照对的是「那张预约」，所以要先把当前这张读出来。 */
    @Transactional
    public AppointmentRecordStore.AppointmentView cancelConfirmed(String caregiverId, String elderUserId,
                                                                  String confirmationId) {
        requireBound(caregiverId, elderUserId);
        AppointmentRecordStore.AppointmentView target = requireUpcoming(elderUserId,
                "这位长辈当前没有进行中的复诊预约，无需取消");
        confirmations.consume(confirmationId, caregiverId, elderUserId, ACTION_CANCEL, cancelSnapshotOf(target))
                .orElseThrow(() -> new IllegalArgumentException(CONFIRMATION_STALE));
        return cancelUpcoming(caregiverId, elderUserId);
    }

    /** 替老人完成一次复诊预约：老人本人名下只保留一个进行中的预约。 */
    @Transactional
    public AppointmentRecordStore.AppointmentView book(String caregiverId, String elderUserId, BookingRequest request) {
        requireBound(caregiverId, elderUserId);
        if (hasUpcoming(elderUserId)) {
            throw new IllegalArgumentException("这位就诊人已有一个进行中的复诊预约，请先调整或取消已有预约，再重新安排");
        }
        String bookingId = "CB-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Resolved resolved = resolve(elderUserId, request, bookingId);
        CareCatalogRepository.Hospital hospital = resolved.hospital();
        CareCatalogRepository.Department department = resolved.department();
        Slot slot = resolved.slot();
        List<String> materials = resolved.materials();
        LocalDateTime at = LocalDateTime.of(slot.date(), slot.time());
        TravelPlan travel = resolved.travel();
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
            notifyCaregiver(caregiverId, elderUserId,
                    "已为" + elderName(elderUserId) + "代约复诊：" + hospital.name() + " " + department.name() + "，"
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
        AppointmentRecordStore.AppointmentView target = requireUpcoming(elderUserId,
                "这位长辈当前没有可修改的进行中复诊预约");
        String bookingId = "CB-MOD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Resolved resolved = resolve(elderUserId, request, bookingId);
        CareCatalogRepository.Hospital hospital = resolved.hospital();
        CareCatalogRepository.Department department = resolved.department();
        Slot slot = resolved.slot();
        List<String> materials = resolved.materials();
        LocalDateTime at = LocalDateTime.of(slot.date(), slot.time());
        TravelPlan travel = resolved.travel();
        LocalDate date = slot.date();
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
        AppointmentRecordStore.AppointmentView target = requireUpcoming(elderUserId,
                "这位长辈当前没有进行中的复诊预约");
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

    /** 请求解析结果：代约与改期共用。预览和执行各跑一次，预览那次的结果就是给人看的那份。 */
    private record Resolved(CareCatalogRepository.Hospital hospital,
                            CareCatalogRepository.Department department,
                            Slot slot, List<String> materials, TravelPlan travel) { }

    /**
     * 代约与改期共用的只读解析：校验字段齐不齐、医院科室日期号源在不在、算材料与出行。
     *
     * <p>不写任何业务数据，也不判断「能不能办」——已有一张预约时不许新约、没有预约时不许改期，
     * 那是各自调用方的事，这里不替它们决定。
     *
     * @param traceId 工具日志挂靠的会话号。真正办理时传本次的 bookingId，纯预览时传空串。
     */
    private Resolved resolve(String elderUserId, BookingRequest request, String traceId) {
        if (request.hospitalId() == null || request.departmentId() == null
                || request.date() == null || request.slotId() == null || request.transport() == null) {
            throw new IllegalArgumentException("请补齐医院、科室、日期、号源和交通方式");
        }
        CareCatalogRepository.Hospital hospital = catalog.hospital(request.hospitalId())
                .orElseThrow(() -> new IllegalArgumentException("没有找到该医院"));
        CareCatalogRepository.Department department = catalog.department(request.hospitalId(), request.departmentId())
                .orElseThrow(() -> new IllegalArgumentException("没有找到该科室"));
        LocalDate date;
        try {
            date = LocalDate.parse(request.date());
        } catch (java.time.format.DateTimeParseException cause) {
            throw new IllegalArgumentException("日期格式不对，请重新选择");
        }
        if (date.isBefore(clock.today())) throw new IllegalArgumentException("该日期已经过去，请重新选择");

        List<Slot> available = appointmentTool.queryAvailableSlots(traceId, hospital.id(), department.name(), date);
        Slot slot = available.stream().filter(item -> item.id().equals(request.slotId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("所选号源已不可用或不在该日期的可选范围内，请重新选择"));

        List<String> materials = materialTool.checklist(traceId, hospital.name(), department.name());
        LocalDateTime at = LocalDateTime.of(slot.date(), slot.time());
        TravelPlan travel = travelTool.plan(traceId, elderUserId, hospital.name(), at, request.transport());
        return new Resolved(hospital, department, slot, materials, travel);
    }

    /** 取当前这张进行中的预约；没有就按调用方给的说法拒绝（三种入口对「没有」的说法不一样）。 */
    private AppointmentRecordStore.AppointmentView requireUpcoming(String elderUserId, String missingMessage) {
        return records.allFor(elderUserId).stream()
                .filter(item -> "CONFIRMED".equals(item.status()) && !item.date().isBefore(clock.today()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(missingMessage));
    }

    private String elderName(String elderUserId) {
        return jdbc.queryForObject("SELECT name FROM users WHERE id=?", String.class, elderUserId);
    }

    private String caregiverName(String caregiverId) {
        return jdbc.queryForObject("SELECT name FROM users WHERE id=?", String.class, caregiverId);
    }

    private boolean hasUpcoming(String elderUserId) {
        return records.allFor(elderUserId).stream()
                .anyMatch(item -> "CONFIRMED".equals(item.status()) && !item.date().isBefore(clock.today()));
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
        AppointmentRecordStore.AppointmentView target = requireUpcoming(elderUserId,
                "这位长辈当前没有进行中的复诊预约，无需取消");
        appointmentTool.cancel("CB-CANCEL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                target.appointmentId(), elderUserId);

        notifyCaregiver(caregiverId, elderUserId,
                "已取消" + elderName(elderUserId) + "的复诊：" + managedText(target) + "。号源已释放，可重新安排。",
                "cancel");
        if (target.arrangedBy() != null && !target.arrangedBy().equals(caregiverId)) {
            notifyCaregiver(target.arrangedBy(), elderUserId,
                    "取消通知：已取消您代约的复诊：" + managedText(target) + "。预约与关联提醒已失效。",
                    "cancel");
        }
        // 重新读一遍再返回：target 是取消前的那份快照，status 还停在 CONFIRMED，
        // 直接返回给调用方会让人以为没取消成。通知里的 managedText(target) 仍用旧快照——
        // 那里说的是「被取消的是什么」，本来就该是取消前的内容。
        return records.allFor(elderUserId).stream()
                .filter(item -> item.appointmentId().equals(target.appointmentId()))
                .findFirst().orElseThrow(() -> new IllegalStateException("预约已取消，但读取结果失败，请刷新查看"));
    }

    /**
     * 家属/志愿者取消长辈名下<b>指定的那一条</b>预约（确认卡上写的是哪一条，就只取消哪一条）。
     *
     * <p>与 {@link #cancelUpcoming} 的区别只在"取哪一条"：那个取的是"当前进行中的那一条"，
     * 适合照护端页面上直接点取消；这里取的是调用方给死的一条，因为助手那张确认卡在签发时
     * 就已经把对象写给老人看过了，执行时再按"当前那一条"现找，中间别人又代约了一条更早的，
     * 同一张卡按下去取消的就是另一条——而界面上从头到尾写的是原来那条。
     *
     * <p>三道校验一个都没少，只是都对着指定的那一条做：照护关系（{@code requireBound}）、
     * 预约归属（记录按 {@code elderUserId} 取，且 {@code appointmentTool.cancel} 那条 SQL 自带
     * {@code user_id}）、当前状态（{@code status='CONFIRMED'}）。任何一道不过就抛，<b>绝不改取消别的</b>。
     * 通知与 {@code cancelUpcoming} 保持一致：给操作者一条回执，原安排者是别人时再通知对方。
     */
    @Transactional
    public AppointmentRecordStore.AppointmentView cancelAppointment(String caregiverId, String elderUserId,
                                                                    String appointmentId) {
        requireBound(caregiverId, elderUserId);
        if (appointmentId == null || appointmentId.isBlank()) {
            throw new IllegalArgumentException("这张取消卡上没有指定要取消的预约，没有执行任何操作");
        }
        AppointmentRecordStore.AppointmentView target = records.allFor(elderUserId).stream()
                .filter(item -> item.appointmentId().equals(appointmentId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("这条预约不属于这位就诊人，或已经不存在，没有执行取消"));
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
