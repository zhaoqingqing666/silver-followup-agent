package com.team.silveragent.application;

import com.team.silveragent.application.care.CareBookingService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.support.DemoSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确认通过之后到底执行了哪件事——走完整的服务（真实的库、真实的工作流）。
 *
 * <p>分派器自己的路由选择由 {@code ConfirmationDispatcherTests} 用替身钉住；这里问的是另一半：
 * 把执行器接到真实的写操作上之后，<b>凭据、会话状态和库里那一行</b>是不是还符合原有的规矩。
 * 归纳起来三条：
 *
 * <ol>
 *   <li><b>执行的是签发时那一件事。</b>凭据签发时冻下动作类型与完整目标；之后会话里任何字段
 *       被改成什么，都换不掉它。老人按的是卡，卡上写的是什么，执行的就必须是什么。</li>
 *   <li><b>凭据只能消费一次。</b>重复点同一张卡不会写第二遍库，不管中间有没有重启。</li>
 *   <li><b>失败也把凭据烧掉。</b>执行到一半失败不是「没发生过」：那份授权已经用掉了，
 *       再点一次不能凭空重来一件新事。</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-dispatch-flow;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=false"})
class ConfirmationDispatchFlowTests {

    private static final LocalDate DAY = DemoSeed.checkupDay();
    private static final String SLOT = DemoSeed.slot(DemoSeed.CARDIOLOGY, DAY, DemoSeed.MORNING);

    @Autowired FollowupAgentService service;
    @Autowired ConversationStore conversations;
    @Autowired CareBookingService careBooking;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        for (String table : List.of("appointments", "reminders", "family_notifications",
                "care_notifications", "memos")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE appointment_slots SET available=TRUE");
        // 会话不能跨用例串：内存里那份缓存清掉，让下一轮从库里读。
        sessions().clear();
    }

    /**
     * 一张预约卡重启之后还是那张预约卡，而且凭据只能用一次。
     *
     * <p>动作类型与目标集合都在签发时进了快照，所以重启用的是同一个 Kind；
     * 凭据本身也在同一个库里，第二遍点它拿不到授权。
     */
    @Test
    void aRestoredBookingCardStillBooksOnceAndOnlyOnce() {
        String conversationId = service.start().conversationId();
        AgentTurnResponse card = planCard(conversationId);
        String confirmationId = card.confirmation().confirmationId();
        assertThat(card.stage()).as(card.reply()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(conversations.find(conversationId).orElseThrow().confirmationKind)
                .isEqualTo(ConfirmationService.PendingOperation.Kind.BOOKING.name());

        sessions().clear();
        AgentTurnResponse done = service.confirm(conversationId, true, confirmationId);
        assertThat(done.stage()).as(done.reply()).isEqualTo("COMPLETED");
        assertThat(appointmentCount()).isEqualTo(1);

        // 同一张卡再点一次：凭据已经用掉了，库里不能再多一条。
        AgentTurnResponse again = service.confirm(conversationId, true, confirmationId);
        assertThat(again.reply()).as(again.reply()).contains("失效");
        assertThat(appointmentCount()).isEqualTo(1);
    }

    /**
     * 签发之后有人把会话字段改掉，也换不掉已经签发的那件事。
     *
     * <p>这里把 {@code pendingAction} 改成「正在取消」、目标集合指向一条别人的预约再点确认：
     * 照旧开预约，那条别人的预约一条都不能动。要是执行依据还看这些可变字段，这一按就变成了
     * 一次取消——而卡片上从头到尾写的是「预约复诊」。
     */
    @Test
    void aMutatedSessionFieldCannotTurnTheIssuedBookingIntoACancellation() {
        String conversationId = service.start().conversationId();
        AgentTurnResponse card = planCard(conversationId);
        seedConfirmedAppointment("someone-else", DAY.plusDays(1), DemoSeed.AFTERNOON);

        ConversationState state = liveState(conversationId);
        state.pendingAction = "CANCEL_EXISTING";
        state.pendingAppointmentId = "someone-else";
        state.pendingAppointmentIds = List.of("someone-else");
        conversations.save(state, null);
        sessions().clear();

        AgentTurnResponse done = service.confirm(conversationId, true, card.confirmation().confirmationId());

        assertThat(done.stage()).as(done.reply()).isEqualTo("COMPLETED");
        assertThat(statusOf("someone-else")).isEqualTo("CONFIRMED");
        assertThat(statusOfNewlyBooked()).isEqualTo("CONFIRMED");
    }

    /**
     * 写库那一步失败时：凭据照旧被烧掉，会话停在「本步骤未完成」，库里一条都不能留。
     *
     * <p>号源在老人按确认之前被别人拿走了——这是真实会发生的失败。原来的预约流程本来就
     * 靠 {@code appointment.submit} 的那条原子更新拦住它，这里钉的是拦住之后凭据和会话的
     * 收场：不能因为写失败了就让那张卡再亮一次。
     */
    @Test
    void aFailedBookingBurnsTheCredentialAndLeavesTheSessionInToolError() {
        String conversationId = service.start().conversationId();
        AgentTurnResponse card = planCard(conversationId);
        String confirmationId = card.confirmation().confirmationId();

        // 别人把号抢走了。卡已经签发，只能在这一刻失败。
        jdbc.update("UPDATE appointment_slots SET available=FALSE WHERE id=?", SLOT);

        AgentTurnResponse failed = service.confirm(conversationId, true, confirmationId);

        assertThat(failed.stage()).as(failed.reply()).isEqualTo("TOOL_ERROR");
        assertThat(failed.reply()).contains("本步骤未完成");
        assertThat(appointmentCount()).isZero();

        AgentTurnResponse again = service.confirm(conversationId, true, confirmationId);
        assertThat(again.reply()).as(again.reply()).contains("失效");
        assertThat(appointmentCount()).isZero();
    }

    /**
     * 代约取消卡重启之后执行的仍是「代约取消」，而且只执行一次。
     *
     * <p>这条路重启之后走的还是原来那一件事：要取消的是哪一个预约号写在凭据里，
     * 凭据本身存在同一个库中，读回来一字不差。所以重启既没让它换个对象，也没让它能用第二回。
     */
    @Test
    void aRestoredManagedCancelCardStillCancelsOnceAndNotifiesTheArranger() {
        careBooking.book("user-f001", "user-001", new CareBookingService.BookingRequest(
                "h001", DemoSeed.CARDIOLOGY, DAY.toString(), SLOT, false, "打车"));

        String conversationId = service.start("user-001").conversationId();
        AgentTurnResponse card = service.act(conversationId, "CANCEL_MANAGED", "", "取消这次预约");
        String confirmationId = card.confirmation().confirmationId();
        assertThat(card.stage()).as(card.reply()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(conversations.find(conversationId).orElseThrow().confirmationKind)
                .isEqualTo(ConfirmationService.PendingOperation.Kind.CANCEL_MANAGED.name());

        sessions().clear();
        AgentTurnResponse done = service.confirm(conversationId, true, confirmationId);

        assertThat(done.reply()).as(done.reply()).contains("已取消", "并已通知");
        assertThat(statusOfOnlyAppointment()).isEqualTo("CANCELLED");
        assertThat(careNotifications("cancel")).isEqualTo(1);

        // 再点一次。这一刻会话已经停在「已取消」，所以先被「本次办理已经停止」那道闸拦住——
        // 拦在哪一道不影响要钉的事：凭据没有了，第二条取消通知也发不出去。
        AgentTurnResponse again = service.confirm(conversationId, true, confirmationId);
        assertThat(again.reply()).as(again.reply()).contains("已经停止");
        assertThat(careNotifications("cancel")).isEqualTo(1);
    }

    /**
     * 备忘卡：确认一次落一条，同一张卡再点一次不会再落第二条。
     *
     * <p>这张卡的 {@code Kind} 是 {@code MEMO}，备忘是唯一一个不碰预约的执行结果，
     * 所以只要它没错落到预约链路上，库里就一条预约都不会多出来。
     */
    @Test
    void clickingTheMemoCardTwiceWritesOnlyOneMemo() {
        String conversationId = service.start("user-001").conversationId();
        AgentTurnResponse ask = service.chat(conversationId, "明天早上8点要去抽血，最好空腹");
        assertThat(ask.stage()).as(ask.reply()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(conversations.find(conversationId).orElseThrow().confirmationKind)
                .isEqualTo(ConfirmationService.PendingOperation.Kind.MEMO.name());
        String confirmationId = ask.confirmation().confirmationId();

        AgentTurnResponse done = service.confirm(conversationId, true, confirmationId);
        assertThat(done.reply()).as(done.reply()).contains("已记下", "抽血");
        assertThat(activeMemoCount()).isEqualTo(1);

        AgentTurnResponse again = service.confirm(conversationId, true, confirmationId);
        assertThat(again.reply()).as(again.reply()).contains("失效");
        assertThat(activeMemoCount()).isEqualTo(1);
        assertThat(appointmentCount()).isZero();
    }

    /** 点了「返回修改」：一个业务动作都不执行，原来那份草稿也还留在会话里。 */
    @Test
    void decliningABookingCardWritesNothingAtAll() {
        String conversationId = service.start().conversationId();
        AgentTurnResponse card = planCard(conversationId);

        AgentTurnResponse declined = service.confirm(conversationId, false, card.confirmation().confirmationId());

        assertThat(declined.reply()).as(declined.reply()).contains("没有执行本次操作");
        assertThat(appointmentCount()).isZero();
        assertThat(count("reminders")).isZero();
        assertThat(count("family_notifications")).isZero();
    }

    /**
     * 发卡之后库里又冒出一条更早的代约：确认时取消的仍是卡片上那一条。
     *
     * <p>这是四B审查指出的那个缺口。<b>以前</b>执行时按"当前那份代约安排"现找一遍，查到的是
     * 列表最前面那条新安排——卡片上写的却是老的。所以这条新安排的 {@code created_at} 特意比原来
     * 那条晚一秒：按"取最新那一条"的写法会稳定地取到它，而正确的结果是它一动都不能动。
     */
    @Test
    void aNewerEarlierArrangedPlanDoesNotBecomeTheCancellationTarget() {
        String frozen = careBooking.book("user-f001", "user-001", booking(SLOT)).appointmentId();

        String conversationId = service.start("user-001").conversationId();
        AgentTurnResponse card = service.act(conversationId, "CANCEL_MANAGED", "", "取消这次预约");
        String confirmationId = card.confirmation().confirmationId();

        String newcomer = seedArrangedAppointment("user-f001", DAY.minusDays(1), DemoSeed.MORNING, "APPT-NEWER");

        sessions().clear();
        AgentTurnResponse done = service.confirm(conversationId, true, confirmationId);

        assertThat(done.reply()).as(done.reply()).contains("已取消");
        assertThat(statusOf(frozen)).isEqualTo("CANCELLED");
        assertThat(statusOf(newcomer)).isEqualTo("CONFIRMED");
        // 通知也只发原来那一条的：发错了，家属会以为另一份安排没了。
        assertThat(careNotifications("cancel")).isEqualTo(1);
    }

    /**
     * 照护端那张取消卡：发卡之后长辈的预约列表变了，确认时仍只取消冻结的那一条。
     *
     * <p>这条路的对象以前是"当前进行中的那一条"，现在来自凭据；中间还重启一次，证明冻住的那条
     * 是跟着凭据进库、又跟着凭据回来的，不在内存里。
     */
    @Test
    void aCaregiverCancellationCardCancelsOnlyTheFrozenAppointment() {
        String frozen = careBooking.book("user-f001", "user-001", booking(SLOT)).appointmentId();

        String conversationId = service.start("user-001", "user-f001").conversationId();
        AgentTurnResponse card = service.act(conversationId, "SELECT_APPOINTMENT_TO_CANCEL", frozen, "取消这条");
        assertThat(card.stage()).as(card.reply()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(conversations.find(conversationId).orElseThrow().confirmationKind)
                .isEqualTo(ConfirmationService.PendingOperation.Kind.CANCEL_APPOINTMENTS_CAREGIVER.name());

        String newcomer = seedArrangedAppointment("user-f001", DAY.minusDays(1), DemoSeed.MORNING, "CAREGIVER-NEWER");

        sessions().clear();
        AgentTurnResponse done = service.confirm(conversationId, true, card.confirmation().confirmationId());

        assertThat(done.reply()).as(done.reply()).contains("已取消");
        assertThat(statusOf(frozen)).isEqualTo("CANCELLED");
        assertThat(statusOf(newcomer)).isEqualTo("CONFIRMED");
    }

    /**
     * 卡片要取消的那一条已经没了（别人先取消了、或已不属于这位就诊人）：一条替代的都不许取消。
     *
     * <p>库里这会儿确实还有一条"当前代约"（后插的那条），权威层也认它；但这张卡不是为它签的。
     * 该做的是把失败如实说出来然后停手，不是悄悄换个对象把事办了。
     */
    @Test
    void whenTheFrozenManagedTargetIsAlreadyGoneNoSubstituteIsCancelled() {
        String frozen = careBooking.book("user-f001", "user-001", booking(SLOT)).appointmentId();

        String conversationId = service.start("user-001").conversationId();
        AgentTurnResponse card = service.act(conversationId, "CANCEL_MANAGED", "", "取消这次预约");
        String confirmationId = card.confirmation().confirmationId();

        jdbc.update("UPDATE appointments SET status='CANCELLED' WHERE id=?", frozen);
        String substitute = seedArrangedAppointment("user-f001", DAY.minusDays(1), DemoSeed.MORNING, "SUBSTITUTE");

        sessions().clear();
        AgentTurnResponse done = service.confirm(conversationId, true, confirmationId);

        assertThat(done.reply()).as(done.reply()).contains("原预约保留");
        assertThat(statusOf(frozen)).isEqualTo("CANCELLED");
        assertThat(statusOf(substitute)).isEqualTo("CONFIRMED");
        assertThat(careNotifications("cancel")).isZero();
    }

    /**
     * 卡片要取消的那一条已经<b>不属于这位就诊人</b>：同样一条替代的都不许取消。
     *
     * <p>跟"已经取消"是两种不同的失效，走的是权威层那条 SQL 的另一个条件（{@code user_id}）。
     * 这条更值得盯：库里、列表里，那条替代的预约看起来完全就是"当前那份代约安排"。
     */
    @Test
    void whenTheFrozenTargetNoLongerBelongsToTheElderNoSubstituteIsCancelled() {
        String frozen = careBooking.book("user-f001", "user-001", booking(SLOT)).appointmentId();

        String conversationId = service.start("user-001").conversationId();
        AgentTurnResponse card = service.act(conversationId, "CANCEL_MANAGED", "", "取消这次预约");
        String confirmationId = card.confirmation().confirmationId();

        jdbc.update("UPDATE appointments SET user_id='user-f001' WHERE id=?", frozen);
        String substitute = seedArrangedAppointment("user-f001", DAY.minusDays(1), DemoSeed.MORNING, "NOT-MINE");

        sessions().clear();
        AgentTurnResponse done = service.confirm(conversationId, true, confirmationId);

        assertThat(done.reply()).as(done.reply()).contains("原预约保留");
        assertThat(statusOf(frozen)).isEqualTo("CONFIRMED");
        assertThat(statusOf(substitute)).isEqualTo("CONFIRMED");
        assertThat(careNotifications("cancel")).isZero();
    }

    /** 点「返回修改 / 先不用」时，一个写工具都不许被调用。 */
    @Test
    void decliningTheMemoAndCancellationCardsWritesNothing() {
        String memoConversation = service.start("user-001").conversationId();
        AgentTurnResponse memoCard = service.chat(memoConversation, "明天早上8点要去抽血，最好空腹");
        assertThat(memoCard.stage()).as(memoCard.reply()).isEqualTo("AWAITING_CONFIRMATION");

        AgentTurnResponse memoDeclined = service.confirm(memoConversation, false,
                memoCard.confirmation().confirmationId());

        assertThat(memoDeclined.reply()).as(memoDeclined.reply()).contains("没有记下");
        assertThat(activeMemoCount()).isZero();

        String frozen = careBooking.book("user-f001", "user-001", booking(SLOT)).appointmentId();
        String cancelConversation = service.start("user-001").conversationId();
        AgentTurnResponse cancelCard = service.act(cancelConversation, "CANCEL_MANAGED", "", "取消这次预约");

        AgentTurnResponse cancelDeclined = service.confirm(cancelConversation, false,
                cancelCard.confirmation().confirmationId());

        assertThat(cancelDeclined.reply()).as(cancelDeclined.reply()).contains("仍然保留");
        assertThat(statusOf(frozen)).isEqualTo("CONFIRMED");
        assertThat(careNotifications("cancel")).isZero();
    }

    // —— 工具方法 ——

    private CareBookingService.BookingRequest booking(String slotId) {
        return new CareBookingService.BookingRequest("h001", DemoSeed.CARDIOLOGY, DAY.toString(), slotId,
                false, "打车");
    }

    /**
     * 直接往库里插一条"别人代约的、日期更早的"预约。
     *
     * <p>{@code created_at} 比原来那条晚一秒，是为了让"按列表最前面那条取对象"的写法稳定地取到
     * 它——不这样一来，顺序就成了巧合，测不出"卡上写 A、执行 B"这件事。
     */
    private String seedArrangedAppointment(String arrangedBy, LocalDate day, java.time.LocalTime time, String id) {
        String slotId = id + "-slot";
        jdbc.update("""
                INSERT INTO appointment_slots(id,hospital_id,hospital_name,department,
                    appointment_date,appointment_time,available)
                VALUES (?,'h001','市第一医院（模拟）','心内科',?,?,FALSE)
                """, slotId, day, time);
        jdbc.update("""
                INSERT INTO appointments(id,slot_id,user_id,status,created_at,conversation_id,arranged_by)
                VALUES (?,?,'user-001','CONFIRMED',DATEADD('SECOND', 1, CURRENT_TIMESTAMP),'dispatch-seed',?)
                """, id, slotId, arrangedBy);
        return id;
    }

    /** 走完整办理漏斗，停在确认卡上（还没确认）。 */
    private AgentTurnResponse planCard(String conversationId) {
        action(conversationId, "SET_HOSPITAL", "h001");
        action(conversationId, "SET_DEPARTMENT", DemoSeed.CARDIOLOGY);
        action(conversationId, "SET_DATE", DAY.toString());
        action(conversationId, "SELECT_SLOT", SLOT);
        action(conversationId, "SET_ALTERNATIVE", "true");
        action(conversationId, "SET_COMPANION", "false");
        action(conversationId, "SET_TRAVEL", "false");
        action(conversationId, "SET_TRANSPORT", "打车");
        action(conversationId, "SET_NOTIFY", "false");
        return action(conversationId, "START_PLAN", "");
    }

    private AgentTurnResponse action(String conversationId, String action, String value) {
        return service.act(conversationId, action, value, action);
    }

    /** 会话在内存里的那一份（执行依据里那些可变字段就是改它）。 */
    private ConversationState liveState(String conversationId) {
        return sessions().get(conversationId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, ConversationState> sessions() {
        return (Map<String, ConversationState>) ReflectionTestUtils.getField(service, "sessions");
    }

    private void seedConfirmedAppointment(String id, LocalDate date, java.time.LocalTime time) {
        String slotId = id + "-slot";
        jdbc.update("""
                INSERT INTO appointment_slots(id,hospital_id,hospital_name,department,
                    appointment_date,appointment_time,available)
                VALUES (?,'h001','市第一医院（模拟）','心内科',?,?,FALSE)
                """, slotId, date, time);
        jdbc.update("""
                INSERT INTO appointments(id,slot_id,user_id,status,created_at,conversation_id)
                VALUES (?,?,'user-001','CONFIRMED',CURRENT_TIMESTAMP,'dispatch-seed')
                """, id, slotId);
    }

    private String statusOf(String appointmentId) {
        return jdbc.queryForObject("SELECT status FROM appointments WHERE id=?", String.class, appointmentId);
    }

    private String statusOfNewlyBooked() {
        return jdbc.queryForObject(
                "SELECT status FROM appointments WHERE id <> 'someone-else'", String.class);
    }

    private String statusOfOnlyAppointment() {
        return jdbc.queryForObject("SELECT status FROM appointments", String.class);
    }

    private int appointmentCount() {
        return count("appointments");
    }

    private int activeMemoCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM memos WHERE status='ACTIVE'", Integer.class);
    }

    private int careNotifications(String kind) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM care_notifications WHERE kind=?", Integer.class, kind);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
