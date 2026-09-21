package com.team.silveragent;

import com.team.silveragent.application.AppointmentRecordStore;
import com.team.silveragent.application.care.CareBookingService;
import com.team.silveragent.application.care.CareService;
import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.support.DemoSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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
        "agent.model.enabled=false"})
class CaregiverSessionTests {

    @Autowired FollowupAgentService service;
    @Autowired CareBookingService booking;
    @Autowired CareService care;
    @Autowired JdbcTemplate jdbc;

    /** 演示用的日期与号源都跟着今天走，别再写死（见 DemoSeed）。 */
    private static final String DAY = DemoSeed.checkupDay().toString();
    private static final String PLAIN = DemoSeed.plainSlot();
    private static final String SECOND = DemoSeed.secondSlot();

    @BeforeEach
    void resetData() {
        for (String table : List.of("memos", "care_notifications", "family_notifications", "reminders", "appointments")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE appointment_slots SET available=TRUE");
    }

    /** 女儿小丽替王阿姨 user-001 代约一份下周三的心内科复诊。 */
    private void bookForWang() {
        bookForWang(PLAIN);
    }

    private void bookForWang(String slotId) {
        AppointmentRecordStore.AppointmentView view = booking.book("user-f001", "user-001",
                new CareBookingService.BookingRequest(
                        "h001", "d001", DAY, slotId, false, "打车"));
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
        service.act(id, "SET_DATE", DAY, "选日期");
        service.act(id, "SELECT_SLOT", PLAIN, "选号源");
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
        // 先用 15:30 那格占住“已有进行中的预约”，把 14:00 留给这次代约
        // （10:30 会撞上「社区体检」，走的是冲突那条路，不是这里要验的路）。
        bookForWang(SECOND);
        AgentTurnResponse start = service.start("user-001", "user-f001");
        String id = start.conversationId();
        service.act(id, "SET_HOSPITAL", "h001", "选医院");
        service.act(id, "SET_DEPARTMENT", "d001", "选科室");
        service.act(id, "SET_DATE", DAY, "选日期");
        service.act(id, "SELECT_SLOT", PLAIN, "选号源");
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
        AgentTurnResponse reply = service.chat(start.conversationId(), "提醒我妈明天上午八点带身份证");

        assertThat(reply.reply()).contains("王阿姨");
        String text = jdbc.queryForObject("SELECT text FROM memos WHERE user_id='user-001'", String.class);
        assertThat(text).contains("小丽", "带身份证");
        // 反向也要留痕，操作者能在自己的协同通知里看到发过什么。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM care_notifications WHERE caregiver_id='user-f001'",
                Integer.class)).isEqualTo(1);
    }

    /**
     * 家属只说了时段（“上午”）、没说几点：跟老人端同一套判据，<b>反问，什么都不留</b>。
     *
     * <p>以前这种句子是按 08:00 落库的——家属听到“已经给王阿姨留好提醒”就当留好了，
     * 而他说的“上午”未必是八点，等长辈那天上午没被叫起来，两边都查不出错在哪。
     * 上午只说清了一半，跟“这周”没说哪天是同一种缺，都先问再留。
     */
    @Test
    void caregiverReminderWithOnlyAPeriodWordIsAskedInsteadOfGuessed() {
        AgentTurnResponse start = service.start("user-001", "user-f001");
        AgentTurnResponse reply = service.chat(start.conversationId(), "提醒我妈明天上午带身份证");

        assertThat(reply.reply()).as(reply.reply()).contains("几点");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memos WHERE user_id='user-001'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM care_notifications WHERE caregiver_id='user-f001'",
                Integer.class)).isZero();
    }

    /**
     * 家属一句话里说了两件事（两个时间点）：跟老人端同一套判据，<b>反问，什么都不留</b>。
     *
     * <p>这里比老人端还多一层：“已经给王阿姨留好提醒”这句话家属是当<b>回执</b>看的，听了就放心了。
     * 所以不光库里不能有备忘，协同通知也不能发——留一条通知，等于给一件没发生的事开了收据。
     */
    @Test
    void caregiverSentenceWithTwoMomentsIsAskedBackAndLeavesNothing() {
        AgentTurnResponse start = service.start("user-001", "user-f001");
        AgentTurnResponse reply = service.chat(start.conversationId(),
                "提醒我妈明天上午八点吃药，后天下午三点复查");

        assertThat(reply.reply()).contains("一件一件说").contains("先没有给王阿姨留");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memos WHERE user_id='user-001'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM care_notifications WHERE caregiver_id='user-f001'",
                Integer.class)).isZero();
    }

    /**
     * 一句话说了三天：给长辈留<b>三条</b>，每条只说自己那天，回读念的是中文日期。
     *
     * <p>前半句以前就有了（只留第一天，长辈会以为后面几天也设好了，到点却不响）；
     * 这一版补的是正文和回读：整段原句抄进每一条会让周三那条说自己周一，
     * 而回读里贴 {@code LocalDate.toString()} 出来的“2026-09-21T08:00”，家属得自己换算才敢核对。
     */
    @Test
    void caregiverMultiDayReminderLeavesOneMemoPerDayWithReadableReadback() {
        AgentTurnResponse start = service.start("user-001", "user-f001");
        AgentTurnResponse reply = service.chat(start.conversationId(),
                "提醒我妈下周周一周二周三早上八点量血压");

        assertThat(reply.reply()).as(reply.reply()).contains("已经给王阿姨留好提醒");
        // 回读念得出、核得对：中文日期 + 星期几，不是 2026-09-21T08:00 这种生日期
        assertThat(reply.reply()).doesNotContain("2026-").contains("（周");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT text, remind_at FROM memos WHERE user_id='user-001' ORDER BY remind_at");
        assertThat(rows).hasSize(3);
        // 每条正文都长这样：抬头写明是谁留的（关系 + 名字）、“我妈”这个称呼摘掉、
        // 不残留星期串（带上就是那条在说别人那天的事）
        assertThat(rows).allSatisfy(row -> assertThat((String) row.get("text"))
                .isEqualTo("「女儿 小丽」提醒：量血压"));
        List<Object> ats = rows.stream().map(row -> row.get("remind_at")).toList();
        assertThat(ats.get(1)).isEqualTo(java.sql.Timestamp.valueOf(
                ((java.sql.Timestamp) ats.get(0)).toLocalDateTime().plusDays(1)));
        assertThat(ats.get(2)).isEqualTo(java.sql.Timestamp.valueOf(
                ((java.sql.Timestamp) ats.get(0)).toLocalDateTime().plusDays(2)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM care_notifications WHERE caregiver_id='user-f001'",
                Integer.class)).isEqualTo(1);
    }

    /**
     * 只说了“这周”，没说哪天、也没说几点：<b>先问清楚再留</b>，问的时候一条都不许写。
     *
     * <p>以前这条路是直接落库的：存下来的是一条“这周量血压”，没有提醒时间——
     * 家属听到的是“已经给王阿姨留好提醒”，长辈那边到哪天都不响，还说不出哪里错了。
     * 补答齐了之后仍然要落成<b>家属留的</b>那条（抬头写是谁留的、回一条协同通知），
     * 不能因为多问了一轮就退回老人端的口吻。
     */
    @Test
    void caregiverReminderWithNoDayOrClockIsAskedBeforeAnythingIsWritten() {
        AgentTurnResponse start = service.start("user-001", "user-f001");
        AgentTurnResponse askDay = service.chat(start.conversationId(), "提醒我妈这周量血压");

        assertThat(askDay.reply()).as(askDay.reply()).contains("哪一天");
        assertThat(memoCount()).isZero();
        assertThat(careNotificationCount()).isZero();

        AgentTurnResponse askClock = service.chat(start.conversationId(), "下周三");
        assertThat(askClock.reply()).as(askClock.reply()).contains("几点");
        assertThat(memoCount()).isZero();

        AgentTurnResponse done = service.chat(start.conversationId(), "早上八点");

        assertThat(done.reply()).as(done.reply())
                .contains("已经给王阿姨留好提醒").doesNotContain("2026-");
        assertThat(jdbc.queryForObject("SELECT text FROM memos WHERE user_id='user-001'", String.class))
                .contains("小丽", "量血压");
        assertThat(jdbc.queryForObject("SELECT repeat_rule FROM memos WHERE user_id='user-001'", String.class))
                .isNull();
        assertThat(careNotificationCount()).isEqualTo(1);
    }

    /**
     * “每周提醒我妈量血压”少了周几：先问周几，再问几点，最后存成<b>重复</b>提醒。
     *
     * <p>不追问的两层错都在这一条里：一是静默落一条永远不到点的备忘，
     * 二是就算补上钟点，重复规则也丢了——长辈以为是每周，其实只响一次。
     */
    @Test
    void caregiverWeeklyReminderAsksForTheWeekdayAndKeepsTheRepeatRule() {
        AgentTurnResponse start = service.start("user-001", "user-f001");
        AgentTurnResponse askWeekday = service.chat(start.conversationId(), "提醒我妈每周量血压");

        assertThat(askWeekday.reply()).as(askWeekday.reply()).contains("每周几");
        assertThat(memoCount()).isZero();

        AgentTurnResponse askClock = service.chat(start.conversationId(), "每周三");
        assertThat(askClock.reply()).as(askClock.reply()).contains("几点");

        AgentTurnResponse done = service.chat(start.conversationId(), "早上八点");

        assertThat(done.reply()).as(done.reply()).contains("每周三");
        assertThat(jdbc.queryForObject("SELECT repeat_rule FROM memos WHERE user_id='user-001'", String.class))
                .isEqualTo("WEEKLY");
        assertThat(jdbc.queryForObject("SELECT text FROM memos WHERE user_id='user-001'", String.class))
                .contains("小丽", "量血压");
        assertThat(careNotificationCount()).isEqualTo(1);
    }

    private int memoCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM memos WHERE user_id='user-001'", Integer.class);
    }

    /**
     * 解析器认不出的说法（“提醒姥姥量血压”）：正文取的是原句，指令词也跟着留在里面。
     *
     * <p>于是长辈以前看到的是“「女儿 小丽」提醒：<b>提醒</b>姥姥量血压”，一句里两个“提醒”，
     * 还带着“姥姥”这个称呼。摘称呼那一步顺手把指令词也摘了——这些都是“他在叫谁/在下什么指令”，
     * 不是事项本身。
     */
    @Test
    void caregiverPhraseTheParserCannotParseStillLosesItsCommandWord() {
        AgentTurnResponse start = service.start("user-001", "user-f001");
        AgentTurnResponse reply = service.chat(start.conversationId(), "提醒姥姥量血压");

        assertThat(reply.reply()).as(reply.reply()).contains("已经给王阿姨留好提醒").contains("量血压");
        assertThat(jdbc.queryForObject("SELECT text FROM memos WHERE user_id='user-001'", String.class))
                .isEqualTo("「女儿 小丽」提醒：量血压");
    }

    private int careNotificationCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM care_notifications WHERE caregiver_id='user-f001'",
                Integer.class);
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
        service.act(elder, "SET_DATE", DAY, "选日期");
        service.act(elder, "SELECT_SLOT", PLAIN, "选号源");
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

    /**
     * 同一次工具调用，成了和没成在家属那条时间线上是两件事。只按工具名取标签，
     * 「复诊提醒没建成」会被显示成绿色的「已创建复诊提醒」——家属据此以为提醒已经建好了。
     */
    @Test
    void timelineTellsAFailedToolCallApartFromASuccessfulOne() {
        jdbc.update("INSERT INTO conversation_sessions(id,user_id,stage,state_json,updated_at) VALUES (?,?,?,?,?)",
                "conv-elder-001", "user-001", "IDLE", "{}", Timestamp.valueOf(LocalDateTime.now()));
        logTool("schedule.createReminder", true);
        logTool("schedule.createReminder", false);

        List<CareService.TimelineEvent> events = care.timeline("user-f001", "user-001");

        assertThat(events).anyMatch(item ->
                item.title().equals("已创建复诊提醒") && item.tone().equals("success"));
        assertThat(events).anyMatch(item ->
                item.title().equals("复诊提醒未创建") && item.tone().equals("danger"));
        // 失败的那条不能再落进成功的说法里
        assertThat(events.stream().filter(item -> item.title().contains("已创建复诊提醒"))).hasSize(1);
    }

    private void logTool(String name, boolean success) {
        jdbc.update("""
                INSERT INTO tool_call_logs(conversation_id,tool_name,request_json,response_json,success,created_at)
                VALUES (?,?,?,?,?,?)
                """, "conv-elder-001", name, "{}", "{}", success, Timestamp.valueOf(LocalDateTime.now()));
    }
}
