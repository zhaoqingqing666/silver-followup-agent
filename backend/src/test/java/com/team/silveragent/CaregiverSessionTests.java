package com.team.silveragent;

import com.team.silveragent.application.AppointmentRecordStore;
import com.team.silveragent.application.CareBookingService;
import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 家属/志愿者端的助手会话：身份由后端按 care_relations 判定，
 * 会话服务的对象是被协同的长辈（数据轴），操作者只用来校验和话术（能力轴）。
 *
 * <p>这里守的是最初实测出来的那个 bug：直接拿 flow 的 userId 开会话，
 * 查的是操作者自己的预约，长辈的安排一条都看不到。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-caregiver;DB_CLOSE_DELAY=-1",
        "agent.llm.enabled=false"})
class CaregiverSessionTests {

    @Autowired FollowupAgentService service;
    @Autowired CareBookingService booking;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        for (String table : List.of("memos", "care_notifications", "family_notifications", "reminders", "appointments")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE appointment_slots SET available=TRUE");
    }

    /** 女儿小丽替王阿姨 user-001 代约一份 09-18 心内科复诊。 */
    private void bookForWang() {
        bookForWang("slot-0918-0900");
    }

    private void bookForWang(String slotId) {
        AppointmentRecordStore.AppointmentView view = booking.book("user-f001", "user-001",
                new CareBookingService.BookingRequest(
                        "h001", "d001", "2026-09-18", slotId, false, "打车"));
        assertThat(view.appointmentId()).isNotBlank();
    }

    /** 同一条查询工具，换成服务对象，结果就必须跟着换——这正是整个改动的目的。 */
    @Test
    void caregiverQueriesAreScopedToTheElderNotTheOperator() {
        bookForWang();

        // 小丽以本人身份开会话：查的是她自己名下，应当什么都没有。
        AgentTurnResponse asSelf = service.start("user-f001");
        AgentTurnResponse selfQuery = service.act(asSelf.conversationId(), "QUERY_APPOINTMENTS", "", "查询我的预约");
        assertThat(selfQuery.reply()).contains("没有找到");

        // 小丽替王阿姨办理：同一个动作，查的是王阿姨名下。
        AgentTurnResponse asCaregiver = service.start("user-001", "user-f001");
        AgentTurnResponse careQuery = service.act(asCaregiver.conversationId(), "QUERY_APPOINTMENTS", "", "查看王阿姨的复诊安排");
        assertThat(careQuery.reply()).contains("心内科");
    }

    @Test
    void greetingNamesBothTheOperatorAndTheSubject() {
        AgentTurnResponse start = service.start("user-001", "user-f001");
        assertThat(start.reply()).contains("小丽", "王阿姨");
    }

    /** 没绑定的长辈一律拒绝：前端传什么 id 都要过关系表这一关。 */
    @Test
    void caregiverCannotOpenASessionForAnUnrelatedElder() {
        assertThatThrownBy(() -> service.start("user-002", "user-f001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有权限");
    }

    /** 志愿者名下有两位长辈：一次会话只钉住其中一位，互不串数据。 */
    @Test
    void volunteerSessionIsPinnedToTheChosenElder() {
        AgentTurnResponse forWang = service.start("user-001", "user-v001");
        AgentTurnResponse forZhang = service.start("user-002", "user-v001");

        assertThat(forWang.conversationId()).isNotEqualTo(forZhang.conversationId());
        assertThat(forWang.reply()).contains("王阿姨").doesNotContain("张伯伯");
        assertThat(forZhang.reply()).contains("张伯伯").doesNotContain("王阿姨");
    }

    /** 老人端不传 actorId，行为必须和改动前一致。 */
    @Test
    void elderStillStartsItsOwnSessionWithoutAnActorId() {
        AgentTurnResponse start = service.start("user-001");
        assertThat(start.reply()).contains("复诊事务助手");
        assertThat(service.start().conversationId()).isNotBlank();
    }

    /** 代办的会话不能用主人的取消链，反之亦然：动态入口对本人会话要给出明确说法。 */
    @Test
    void caregiverOnlyViewsAreUnavailableToTheElder() {
        AgentTurnResponse elder = service.start("user-001");
        AgentTurnResponse timeline = service.act(elder.conversationId(), "QUERY_CARE_TIMELINE", "", "最近的复诊动态");
        assertThat(timeline.reply()).contains("指定一位长辈");
    }

    /**
     * 走完代约漏斗，确认后落在照护端那条链上：arranged_by 必须是小丽。
     *
     * <p>这里刻意不走 SET_NOTIFY / SET_CONTACT：操作者本人就是家属，代约由
     * {@link CareBookingService} 自己通知其他照护者，再问他“通知家里谁”属于多余的一步。
     */
    @Test
    void caregiverBookingIsRecordedAsArrangedByTheOperator() {
        AgentTurnResponse start = service.start("user-001", "user-f001");
        String id = start.conversationId();

        service.act(id, "SET_HOSPITAL", "h001", "选医院");
        service.act(id, "SET_DEPARTMENT", "d001", "选科室");
        service.act(id, "SET_DATE", "2026-09-18", "选日期");
        service.act(id, "SELECT_SLOT", "slot-0918-0900", "选号源");
        service.act(id, "SET_ALTERNATIVE", "true", "接受附近日期");
        service.act(id, "SET_COMPANION", "true", "需要陪同");
        service.act(id, "SET_TRAVEL", "true", "需要出行提醒");
        AgentTurnResponse afterTransport = service.act(id, "SET_TRANSPORT", "打车", "打车");
        // 交通方式问完就直接往下走，中间不再插一步“通知哪位家属”。
        assertThat(afterTransport.stage()).as(afterTransport.reply()).isNotEqualTo("ASK_NOTIFY");
        assertThat(afterTransport.reply()).doesNotContain("通知家属", "通知哪位家属");

        AgentTurnResponse card = service.act(id, "START_PLAN", "", "检查计划");
        assertThat(card.stage()).as(card.reply()).isEqualTo("AWAITING_CONFIRMATION");
        // 确认卡必须说清替谁办，这是按确认前唯一的关口。
        assertThat(card.confirmation().operations()).anySatisfy(line -> assertThat(line).contains("服务对象", "王阿姨"));
        // 卡片和口播不能说两套：卡片写着会通知其他照护者，口播就不能说“不会通知家属”。
        assertThat(card.confirmation().operations()).anySatisfy(line -> assertThat(line).contains("代约归属"));
        assertThat(card.reply()).contains("由您代约").doesNotContain("不会通知家属");

        AgentTurnResponse done = service.confirm(id, true, card.confirmation().confirmationId());

        String arrangedBy = jdbc.queryForObject(
                "SELECT arranged_by FROM appointments WHERE status='CONFIRMED'", String.class);
        assertThat(arrangedBy).isEqualTo("user-f001");
        assertThat(done.reply()).contains("王阿姨");
        // 结果卡要落到界面上，且归属写的是代约人，不是老人端那套“不通知家属”。
        assertThat(done.result()).isNotNull();
        assertThat(done.result().familyStatus()).contains("小丽");
    }

    /**
     * 长辈名下已经有进行中的预约时，代约会被后端拦下；这一轮必须告诉操作者怎么往下走，
     * 而不只是甩一句错误——所以按钮里要给出“先取消已有预约”这条正路。
     */
    @Test
    void caregiverIsGivenAWayForwardWhenTheElderAlreadyHasAnAppointment() {
        // 先用下午那格占住“已有进行中的预约”，把 09:00 留给这次代约
        // （10:20 会撞上 data.sql 里的社区体检，走的是冲突那条路，不是这里要验的路）。
        bookForWang("slot-0918-1430");
        AgentTurnResponse start = service.start("user-001", "user-f001");
        String id = start.conversationId();
        service.act(id, "SET_HOSPITAL", "h001", "选医院");
        service.act(id, "SET_DEPARTMENT", "d001", "选科室");
        service.act(id, "SET_DATE", "2026-09-18", "选日期");
        service.act(id, "SELECT_SLOT", "slot-0918-0900", "选号源");
        service.act(id, "SET_COMPANION", "true", "需要陪同");
        service.act(id, "SET_TRAVEL", "false", "不需要");
        service.act(id, "SET_TRANSPORT", "打车", "打车");

        AgentTurnResponse card = service.act(id, "START_PLAN", "", "检查计划");
        AgentTurnResponse blocked = service.confirm(id, true, card.confirmation().confirmationId());
        assertThat(blocked.reply()).contains("进行中");
        assertThat(blocked.quickReplies()).anySatisfy(reply ->
                assertThat(reply.label()).contains("取消"));

        // 顺着按钮走到取消确认卡，确认后这条旧预约才算真的让位。
        AgentTurnResponse cancelCard = service.act(id, "CANCEL_APPOINTMENT", "", "先取消已有预约");
        assertThat(cancelCard.stage()).as(cancelCard.reply()).isEqualTo("AWAITING_CONFIRMATION");
        service.confirm(id, true, cancelCard.confirmation().confirmationId());

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM appointments WHERE status='CONFIRMED'", Integer.class)).isZero();
    }

    /** 家属留的提醒要落进长辈自己的备忘，并且写清是谁留的。 */
    @Test
    void caregiverReminderLandsInTheEldersMemosAndNamesTheSender() {
        AgentTurnResponse start = service.start("user-001", "user-f001");
        AgentTurnResponse reply = service.chat(start.conversationId(), "提醒我妈明天上午带身份证");

        assertThat(reply.reply()).contains("王阿姨");
        String text = jdbc.queryForObject("SELECT text FROM memos WHERE user_id='user-001'", String.class);
        assertThat(text).contains("小丽", "带身份证");
        // 反向也要留痕，操作者能在自己的协同通知里看到发过什么。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM care_notifications WHERE caregiver_id='user-f001'",
                Integer.class)).isEqualTo(1);
    }

    /**
     * 长辈自己约的那一份，开场就要说清楚。
     * 一次只能有一份进行中的预约，藏着不说，操作者会一路填到确认卡才被拦下。
     */
    @Test
    void greetingWarnsWhenTheElderAlreadyHasHerOwnUpcomingAppointment() {
        // 先让老人本人办成一次（arranged_by 为空），再以家属身份开会话。
        String elder = service.start("user-001").conversationId();
        service.act(elder, "SET_HOSPITAL", "h001", "选医院");
        service.act(elder, "SET_DEPARTMENT", "d001", "选科室");
        service.act(elder, "SET_DATE", "2026-09-18", "选日期");
        service.act(elder, "SELECT_SLOT", "slot-0918-0900", "选号源");
        service.act(elder, "SET_ALTERNATIVE", "true", "接受附近日期");
        service.act(elder, "SET_COMPANION", "false", "不需要陪同");
        service.act(elder, "SET_TRAVEL", "true", "需要出行提醒");
        service.act(elder, "SET_TRANSPORT", "打车", "打车");
        service.act(elder, "SET_NOTIFY", "true", "通知家属");
        service.act(elder, "SET_CONTACT", "family-001", "小丽");
        AgentTurnResponse card = service.act(elder, "START_PLAN", "", "检查计划");
        service.confirm(elder, true, card.confirmation().confirmationId());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM appointments WHERE arranged_by IS NULL", Integer.class)).isEqualTo(1);

        AgentTurnResponse caregiver = service.start("user-001", "user-f001");
        assertThat(caregiver.reply()).contains("王阿姨").contains("名下有一份进行中的复诊预约");
    }

    /** 照护者能看到长辈的动态；这里至少证明入口通了、并且报的是长辈的名字。 */
    @Test
    void caregiverCanReadTheElderTimeline() {
        AgentTurnResponse caregiver = service.start("user-001", "user-f001");
        AgentTurnResponse timeline = service.act(caregiver.conversationId(), "QUERY_CARE_TIMELINE", "", "最近的复诊动态");
        assertThat(timeline.reply()).contains("王阿姨");
    }
}
