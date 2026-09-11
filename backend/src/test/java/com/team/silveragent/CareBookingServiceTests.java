package com.team.silveragent;

import com.team.silveragent.application.CareBookingService;
import com.team.silveragent.application.CareService;
import com.team.silveragent.application.AppointmentRecordStore;
import com.team.silveragent.support.DemoSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 家属/志愿者代约（方案 B）集成测试：完整跑通 号源校验 → 出行 → 材料 → 提醒 → 落库 → 定向通知，
 * 以及重复预约拦截与绑定越权校验。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-care;DB_CLOSE_DELAY=-1",
        "agent.llm.enabled=false"})
class CareBookingServiceTests {

    @Autowired CareBookingService booking;
    @Autowired CareService care;
    @Autowired AppointmentRecordStore records;
    @Autowired JdbcTemplate jdbc;

    /** 演示用的日期与号源都跟着今天走，别再写死（见 DemoSeed）。 */
    private static final String DAY = DemoSeed.checkupDay().toString();
    private static final String PLAIN = DemoSeed.plainSlot();
    private static final String CLASH = DemoSeed.conflictingSlot();

    @BeforeEach
    void resetData() {
        for (String table : List.of("care_notifications", "family_notifications", "reminders", "appointments")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE appointment_slots SET available=TRUE");
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private CareBookingService.BookingRequest request(String date, String slotId, String transport, boolean needTravel) {
        return new CareBookingService.BookingRequest(
                "h001", "d001", date, slotId, needTravel, transport);
    }

    @Test
    void familyBooksForElderCreatesAppointmentRemindersAndSlotsTaken() {
        AppointmentRecordStore.AppointmentView view = booking.book(
                "user-f001", "user-001",
                request(DAY, PLAIN, "打车", true));

        assertThat(view.status()).isEqualTo("CONFIRMED");
        assertThat(view.hospital()).isEqualTo("市第一医院（模拟）");
        assertThat(view.department()).isEqualTo("心内科");
        assertThat(view.date()).isEqualTo(DemoSeed.checkupDay());
        assertThat(view.transport()).isEqualTo("打车");
        assertThat(view.familyStatus()).isEqualTo("由女儿 小丽 代约");
        assertThat(view.materials()).contains("身份证", "医保卡或电子医保凭证");

        // 落库安排者标记
        String arrangedBy = jdbc.queryForObject(
                "SELECT arranged_by FROM appointments WHERE id=?", String.class, view.appointmentId());
        assertThat(arrangedBy).isEqualTo("user-f001");

        // 号源被占用
        Integer available = jdbc.queryForObject(
                "SELECT available FROM appointment_slots WHERE id=?", Integer.class, PLAIN);
        assertThat(available).isZero();

        // 提醒：材料准备(前1天) + 出发(前10分钟)
        assertThat(count("reminders")).isEqualTo(2);
        Integer material = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reminders WHERE title='复诊材料准备提醒' AND appointment_id=?",
                Integer.class, view.appointmentId());
        Integer departure = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reminders WHERE title='复诊出发提醒' AND appointment_id=?",
                Integer.class, view.appointmentId());
        assertThat(material).isEqualTo(1);
        assertThat(departure).isEqualTo(1);

        // 家属本人代约未触发家人广播，但会给操作者留一条 kind=book 回执，保证就诊动态里能看到这次新建
        assertThat(count("care_notifications")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT kind FROM care_notifications", String.class)).isEqualTo("book");

        // 老人端可见该预约
        assertThat(records.allFor("user-001")).extracting(AppointmentRecordStore.AppointmentView::appointmentId)
                .contains(view.appointmentId());
    }

    @Test
    void volunteerBookingNotifiesFamilyCaregiversOnlyWhenPresent() {
        // 志愿者 v001 替 user-001 代约 → 通知家属 f001（小丽）
        AppointmentRecordStore.AppointmentView v1 = booking.book(
                "user-v001", "user-001",
                request(DAY, PLAIN, "家属开车", false));
        assertThat(v1.familyStatus()).isEqualTo("由社区志愿者 李阿姨 代约");
        List<CareService.NotificationView> inbox = care.notifications("user-f001");
        assertThat(inbox).extracting(CareService.NotificationView::content)
                .anyMatch(text -> text.contains("李阿姨 已为王阿姨代约复诊"));

        // 志愿者 v001 替 user-002（张伯伯）代约 → 无家庭照护者可通知,不发
        AppointmentRecordStore.AppointmentView v2 = booking.book(
                "user-v001", "user-002",
                request(DAY, CLASH, "公交", false));
        assertThat(v2.status()).isEqualTo("CONFIRMED");
        Long targetedToF001 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM care_notifications WHERE caregiver_id='user-f001' AND elder_user_id='user-002'",
                Long.class);
        assertThat(targetedToF001).isZero();
    }

    @Test
    void secondBookingWhileUpcomingExistsIsRejected() {
        booking.book("user-f001", "user-001",
                request(DAY, PLAIN, "家属开车", false));
        assertThatThrownBy(() -> booking.book("user-f001", "user-001",
                request(DAY, CLASH, "家属开车", false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("进行中的复诊预约");
    }

    @Test
    void unboundCaregiverCannotBook() {
        assertThatThrownBy(() -> booking.book("user-f001", "user-002",
                request(DAY, PLAIN, "家属开车", false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未绑定");
    }

    @Test
    void caregiverCancelsUpcomingAppointmentThenCanRebook() {
        AppointmentRecordStore.AppointmentView made = booking.book(
                "user-f001", "user-001",
                request(DAY, PLAIN, "家属开车", false));

        AppointmentRecordStore.AppointmentView cancelled = booking.cancelUpcoming("user-f001", "user-001");
        assertThat(cancelled.appointmentId()).isEqualTo(made.appointmentId());
        assertThat(jdbc.queryForObject("SELECT status FROM appointments", String.class)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT available FROM appointment_slots WHERE id=?", Integer.class, PLAIN))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reminders WHERE status='CREATED'", Integer.class))
                .isZero();
        // 建约 book 回执 + 本次取消回执各一条，取消动态可见
        assertThat(count("care_notifications")).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM care_notifications WHERE kind='cancel'", Integer.class)).isEqualTo(1);

        // 取消后即可重新代约
        assertThat(booking.book("user-f001", "user-001",
                request(DAY, CLASH, "家属开车", false)).status()).isEqualTo("CONFIRMED");
    }

    @Test
    void cancelWhenNoUpcomingAppointmentIsRejected() {
        assertThatThrownBy(() -> booking.cancelUpcoming("user-f001", "user-001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有进行中的复诊预约");
    }

    @Test
    void bookStoresAccompanyCaregiverFlag() {
        AppointmentRecordStore.AppointmentView view = booking.book(
                "user-f001", "user-001",
                new CareBookingService.BookingRequest(
                        "h001", "d001", DAY, PLAIN, false, "打车", true));
        assertThat(view.accompaniedBy()).isEqualTo("user-f001");

        // 未确认陪同（或志愿者代约不去）→ accompanied_by 为空
        AppointmentRecordStore.AppointmentView other = booking.book(
                "user-v001", "user-002",
                new CareBookingService.BookingRequest(
                        "h001", "d001", DAY, CLASH, false, "打车", false));
        assertThat(other.accompaniedBy()).isNull();
    }

    @Test
    void caregiverTogglesAccompanyOnCurrentAppointmentWithoutTouchingOthers() {
        AppointmentRecordStore.AppointmentView made = booking.book(
                "user-f001", "user-001",
                request(DAY, PLAIN, "打车", false));
        assertThat(made.accompaniedBy()).isNull();

        // 家属改为陪同 → accompanied_by 记为本人；再改回不陪同 → 清空
        assertThat(booking.toggleAccompany("user-f001", "user-001", true).accompaniedBy()).isEqualTo("user-f001");
        assertThat(booking.toggleAccompany("user-f001", "user-001", false).accompaniedBy()).isNull();

        // 家属陪同已打开；志愿者说自己“不陪同”不应清掉家属的标记
        booking.toggleAccompany("user-f001", "user-001", true);
        AppointmentRecordStore.AppointmentView afterVolunteer = booking.toggleAccompany("user-v001", "user-001", false);
        assertThat(afterVolunteer.accompaniedBy()).isEqualTo("user-f001");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointments", Integer.class)).isEqualTo(1);
    }

    @Test
    void modifyKeepsSameRowSwapsSlotAndRefreshesReminders() {
        AppointmentRecordStore.AppointmentView made = booking.book(
                "user-f001", "user-001",
                request(DAY, PLAIN, "家属开车", false));
        assertThat(made.accompaniedBy()).isNull();

        AppointmentRecordStore.AppointmentView updated = booking.modify(
                "user-f001", "user-001",
                new CareBookingService.BookingRequest(
                        "h001", "d001", DAY, CLASH, true, "家属开车", true));

        // 同一条记录原位更新：不产生新的 CANCELLED 记录
        assertThat(updated.appointmentId()).isEqualTo(made.appointmentId());
        assertThat(updated.status()).isEqualTo("CONFIRMED");
        assertThat(updated.time()).isEqualTo(java.time.LocalTime.of(10, 30));
        assertThat(updated.accompaniedBy()).isEqualTo("user-f001");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointments", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT arranged_by FROM appointments", String.class)).isEqualTo("user-f001");

        // 号源释放与占用互换
        assertThat(jdbc.queryForObject("SELECT available FROM appointment_slots WHERE id=?", Integer.class, PLAIN)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT available FROM appointment_slots WHERE id=?", Integer.class, CLASH)).isZero();

        // 旧提醒作废，新建材料+出发提醒
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reminders WHERE status='CREATED'", Integer.class)).isEqualTo(2);
        // 本人改自己代约的安排 → 未广播他人，但会给自己留一条 kind=reschedule 回执，就诊动态可见改期
        assertThat(count("care_notifications")).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM care_notifications WHERE kind='reschedule'", Integer.class)).isEqualTo(1);
    }

    @Test
    void modifyWithoutUpcomingAppointmentIsRejected() {
        assertThatThrownBy(() -> booking.modify("user-f001", "user-001",
                request(DAY, PLAIN, "家属开车", false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有可修改的进行中复诊预约");
    }

    @Test
    void alertOnlyWhenCancelledWithoutReplacement() {
        booking.book("user-f001", "user-001", request(DAY, PLAIN, "家属开车", false));

        // 取消后未补新预约 → 提示“尽快重新安排”
        booking.cancelUpcoming("user-f001", "user-001");
        CareService.ElderSummary without = care.elders("user-f001").stream()
                .filter(item -> item.elderId().equals("user-001")).findFirst().orElseThrow();
        assertThat(without.alert()).contains("尽快重新安排");

        // 重新代约后 → 警告消失
        booking.book("user-f001", "user-001", request(DAY, CLASH, "家属开车", false));
        CareService.ElderSummary replaced = care.elders("user-f001").stream()
                .filter(item -> item.elderId().equals("user-001")).findFirst().orElseThrow();
        assertThat(replaced.alert()).isNull();
    }
}
