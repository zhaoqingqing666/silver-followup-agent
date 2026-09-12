package com.team.silveragent;

import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.model.AgentTurnResponse.QuickReply;
import com.team.silveragent.domain.model.AgentTurnResponse.UiDirectiveType;
import com.team.silveragent.support.DemoSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自然语言地图请求必须绑定数据库里的真实预约。
 * 重点：用户说出的日期不能在 Java 阶段丢掉，多条预约不能替用户挑，紧急与确认门禁不被绕过。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-travel;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=false"})
class TravelAppointmentBindingTests {

    /** 办理流程用演示种子的「下周三」上午号源；历史预约那条固定用 9 月 8 日（绑定只比月/日，不看年份）。 */
    private static final String DAY = DemoSeed.day(DemoSeed.checkupDay());
    private static final String SLOT = DemoSeed.morningSlot();

    @Autowired FollowupAgentService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void resetData() {
        for (String table : List.of("appointments", "reminders", "family_notifications")) {
            jdbc.update("DELETE FROM " + table);
        }
        // 只清本类自己塞进去的历史号源：演示号源已经是滚动的，不再需要在这里挑日子放行。
        jdbc.update("DELETE FROM appointment_slots WHERE id LIKE 'slot-0908-%'");
        jdbc.update("UPDATE appointment_slots SET available=TRUE");
    }

    /** 造一条历史（已过去）的已确认预约：9月8日 市第一医院 心内科。 */
    private String seedSept8(String suffix, String slotId, String hospitalId, String hospitalName,
                             String department, String time, String locationId) {
        jdbc.update("""
                INSERT INTO appointment_slots(id,hospital_id,hospital_name,department,
                    appointment_date,appointment_time,available,clinic_location_id)
                VALUES (?,?,?,?,?,?,TRUE,?)
                """, slotId, hospitalId, hospitalName, department, "2026-09-08", time, locationId);
        String appointmentId = "appt-0908-" + suffix;
        jdbc.update("""
                INSERT INTO appointments(id,slot_id,user_id,status,created_at,conversation_id,transport,
                    reminder_status,family_status,materials)
                VALUES (?,?,'user-001','CONFIRMED',CURRENT_TIMESTAMP,?,'打车','已设置','已通知','身份证、医保卡或电子医保凭证')
                """, appointmentId, slotId, "conv-" + suffix);
        return appointmentId;
    }

    private AgentTurnResponse action(String id, String action, String value) {
        return service.act(id, action, value, action);
    }

    /** 走一遍完整办理，用于制造确认卡 / COMPLETED / CANCELLED 这些业务状态。 */
    private AgentTurnResponse prepare(String id) {
        action(id, "SET_HOSPITAL", "h001");
        action(id, "SET_DEPARTMENT", "d001");
        action(id, "SET_DATE", DAY);
        action(id, "SELECT_SLOT", SLOT);
        action(id, "SET_ALTERNATIVE", "true");
        action(id, "SET_COMPANION", "true");
        action(id, "SET_TRAVEL", "true");
        action(id, "SET_TRANSPORT", "打车");
        action(id, "SET_NOTIFY", "true");
        AgentTurnResponse turn = action(id, "SET_CONTACT", "family-001");
        assertThat(turn.stage()).as(turn.reply()).isEqualTo("AWAITING_CONFIRMATION");
        return turn;
    }

    private int confirmedAppointments() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE status='CONFIRMED'", Integer.class);
    }

    // ① “9月8号”是过去的日子，不能被滚动成下一年，也不能退回默认的最近一条预约。
    @Test void spokenMonthDayBindsTheRealHistoricalAppointment() {
        String appointmentId = seedSept8("h001", "slot-0908-0900", "h001", "市第一医院（模拟）", "心内科",
                "09:00:00", "loc-d001");
        String id = service.start().conversationId();

        AgentTurnResponse asked = service.chat(id, "我想看看9月8号的地图");

        assertThat(asked.uiDirective()).isNotNull();
        assertThat(asked.uiDirective().type()).isEqualTo(UiDirectiveType.OPEN_TRAVEL);
        assertThat(asked.uiDirective().appointmentId()).isEqualTo(appointmentId);
        assertThat(asked.reply()).contains("2026年9月8日", "市第一医院", "心内科");
    }

    // ② 完整口语句子（不是短口令）同样要落到院内指引，且楼层、诊室来自数据库。
    @Test void naturalInsideGuideFallsThroughToTheRealFacility() {
        seedSept8("h001", "slot-0908-0900", "h001", "市第一医院（模拟）", "心内科", "09:00:00", "loc-d001");
        String id = service.start().conversationId();

        AgentTurnResponse asked = service.chat(id, "我到医院里面了怎么走？");

        assertThat(asked.uiDirective()).isNotNull();
        assertThat(asked.uiDirective().type()).isEqualTo(UiDirectiveType.SHOW_INSIDE_GUIDE);
        assertThat(asked.uiDirective().focus()).isEqualTo("inside");
        // 事实必须来自 clinic_locations，不能由模型或文案编造。
        assertThat(asked.reply()).contains("门诊楼", "三层", "308诊室", "南门");
    }

    // ③ 同一天多条预约不替用户选：只给候选，不返回任何 appointmentId。
    @Test void twoAppointmentsOnTheSameDayAskInsteadOfGuessing() {
        seedSept8("h001", "slot-0908-0900", "h001", "市第一医院（模拟）", "心内科", "09:00:00", "loc-d001");
        seedSept8("h002", "slot-0908-1430", "h002", "市人民医院（模拟）", "骨科", "14:30:00", "loc-d004");
        String id = service.start().conversationId();

        AgentTurnResponse asked = service.chat(id, "我想看看9月8号的地图");

        assertThat(asked.uiDirective()).as("澄清阶段不能返回 appointmentId").isNull();
        assertThat(asked.reply()).contains("9月8日", "2条", "市第一医院", "心内科", "市人民医院", "骨科");
        assertThat(asked.quickReplies()).extracting(QuickReply::action)
                .containsOnly("SELECT_TRAVEL_APPOINTMENT");
        assertThat(confirmedAppointments()).isEqualTo(2);
    }

    // ③ 续：候选支线选中的是地图，绝不能变成取消预约的确认卡。
    @Test void choosingATravelCandidateOpensTheMapAndNeverACancellation() {
        String first = seedSept8("h001", "slot-0908-0900", "h001", "市第一医院（模拟）", "心内科",
                "09:00:00", "loc-d001");
        String second = seedSept8("h002", "slot-0908-1430", "h002", "市人民医院（模拟）", "骨科",
                "14:30:00", "loc-d004");
        String id = service.start().conversationId();
        service.chat(id, "我想看看9月8号的地图");

        AgentTurnResponse picked = action(id, "SELECT_TRAVEL_APPOINTMENT", first);

        assertThat(picked.confirmation()).as("地图候选不得进入确认门禁").isNull();
        assertThat(picked.uiDirective()).isNotNull();
        assertThat(picked.uiDirective().appointmentId()).isEqualTo(first);
        assertThat(picked.uiDirective().appointmentId()).isNotEqualTo(second);
        assertThat(confirmedAppointments()).isEqualTo(2);
    }

    // ④ 没有对应日期的预约时明确说没查到，绝不回退到另一条已有预约。
    @Test void missingDateSaysSoAndNeverFallsBackToAnotherAppointment() {
        String other = seedSept8("h001", "slot-0908-0900", "h001", "市第一医院（模拟）", "心内科",
                "09:00:00", "loc-d001");
        String id = service.start().conversationId();

        AgentTurnResponse asked = service.chat(id, "我想看看9月20号的地图");

        assertThat(asked.uiDirective()).isNull();
        assertThat(asked.reply()).contains("没有查到", "9月20日").doesNotContain(other);
    }

    // ④ 续：正在追问候选时改成另一个日期重新问，不能被旧候选支线接管，也不能回退到别的预约。
    @Test void switchingDateInsideTheCandidateBranchAnswersFromScratch() {
        seedSept8("h001", "slot-0908-0900", "h001", "市第一医院（模拟）", "心内科", "09:00:00", "loc-d001");
        seedSept8("h002", "slot-0908-1430", "h002", "市人民医院（模拟）", "骨科", "14:30:00", "loc-d004");
        String id = service.start().conversationId();
        service.chat(id, "我想看看9月8号的地图");

        AgentTurnResponse asked = service.chat(id, "我想看看9月20号的地图");

        assertThat(asked.uiDirective()).isNull();
        assertThat(asked.reply()).contains("没有查到", "9月20日");
    }

    // ⑤ 只说“打开地图”仍然走快速通道：没有筛选条件时用当前会话或最近的有效预约。
    @Test void plainMapCommandStillUsesTheEffectiveAppointment() {
        AgentTurnResponse prepared = prepare(service.start().conversationId());
        AgentTurnResponse done = service.confirm(prepared.conversationId(), true,
                prepared.confirmation().confirmationId());
        assertThat(done.stage()).isEqualTo("COMPLETED");

        AgentTurnResponse asked = service.chat(done.conversationId(), "打开地图");

        assertThat(asked.uiDirective()).isNotNull();
        assertThat(asked.uiDirective().appointmentId()).isEqualTo(done.result().appointmentId());
    }

    // ⑥ 确认卡等待处理时，带日期的地图请求也不得把确认卡挤掉。
    @Test void dateQualifiedMapRequestNeverDismissesTheConfirmationCard() {
        seedSept8("h001", "slot-0908-0900", "h001", "市第一医院（模拟）", "心内科", "09:00:00", "loc-d001");
        AgentTurnResponse prepared = prepare(service.start().conversationId());
        String id = prepared.conversationId();

        AgentTurnResponse asked = service.chat(id, "我想看9月8号的地图");

        assertThat(asked.stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(asked.confirmation()).isNotNull();
        assertThat(asked.confirmation().confirmationId())
                .isEqualTo(prepared.confirmation().confirmationId());
        assertThat(asked.uiDirective()).as("确认卡优先，不跳转页面").isNull();
        // 只有预置的那条历史预约，本轮既没有新增也没有取消。
        assertThat(confirmedAppointments()).isEqualTo(1);
    }

    // ⑦ CANCELLED 只是“本次未提交的办理”停止，数据库里已有的预约仍然能看地图。
    @Test void cancelledTaskCanStillOpenTheMapOfAnExistingAppointment() {
        String appointmentId = seedSept8("h001", "slot-0908-0900", "h001", "市第一医院（模拟）", "心内科",
                "09:00:00", "loc-d001");
        AgentTurnResponse prepared = prepare(service.start().conversationId());
        AgentTurnResponse done = service.confirm(prepared.conversationId(), true,
                prepared.confirmation().confirmationId());
        String id = done.conversationId();

        AgentTurnResponse cancelled = service.chat(id, "不办了");
        assertThat(cancelled.stage()).isEqualTo("CANCELLED");

        AgentTurnResponse asked = service.chat(id, "我想看9月8号的地图");
        assertThat(asked.uiDirective()).isNotNull();
        assertThat(asked.uiDirective().appointmentId()).isEqualTo(appointmentId);
        assertThat(asked.uiDirective().appointmentId()).isNotEqualTo(done.result().appointmentId());
    }

    // ⑧ 紧急暂停期间安全提示优先：明确要求看地图也只给文字和按钮，不自动跳页。
    @Test void emergencyPauseKeepsTheSafetyMessageForEveryMapRequest() {
        seedSept8("h001", "slot-0908-0900", "h001", "市第一医院（模拟）", "心内科", "09:00:00", "loc-d001");
        String id = service.start().conversationId();
        assertThat(service.chat(id, "我胸口疼").stage()).isEqualTo("EMERGENCY_PAUSED");

        for (String message : List.of("打开地图", "我想看9月8号的地图", "我到医院里面了怎么走")) {
            AgentTurnResponse asked = service.chat(id, message);
            assertThat(asked.stage()).as(message).isEqualTo("EMERGENCY_PAUSED");
            assertThat(asked.uiDirective()).as(message).isNull();
            assertThat(asked.reply()).as(message).contains("120");
        }
    }
}
