package com.team.silveragent;

import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.application.demo.DemoScenarioService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.model.AgentTurnResponse.QuickReply;
import com.team.silveragent.support.DemoSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段 2「选医交互」的漏斗回归（docs/proposals/选医交互方案（阶段2）.md）。
 *
 * <p>规则出处：R1 指定日期无专家号 → 明说 + 7 天内最近专家号，绝不静默换日期；
 * R3 单轮最多 2 个候选、口播只报姓氏职称与预估自付；R6 日期定了先报出诊医生；
 * 写操作照旧必须过确认门禁。
 *
 * <p>第二批 R7~R9（DEC-027，出处 `docs/proposals/推荐标记与时段可见性方案.md`）：
 * <b>自动推荐轮</b>要说「推荐」并把医生信息与时间讲清（点名医生、指定具体时刻这两轮都不说）；
 * 报出当天**另一半**的条数（未点名时半天候选数恒等于该半天号源数，数它没有信息量）；
 * 推荐轮按钮恒 ≤3 个——「改选另一半」并进「看全部X时间」，不再被折进「查看更多选项」。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-test;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=false"})
class DoctorIntentTests {

    @Autowired FollowupAgentService service;
    @Autowired DemoScenarioService scenarios;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void startFromAKnownState() {
        scenarios.reset("normal");
    }

    private AgentTurnResponse act(String id, String action, String value) {
        return service.act(id, action, value, action);
    }

    /** 开到「上午还是下午」这一步：医院、科室、日期都已确定。 */
    private String conversationAtPeriodSelection() {
        String id = service.start().conversationId();
        act(id, "SET_HOSPITAL", "h001");
        act(id, "SET_DEPARTMENT", "d001");
        act(id, "SET_DATE", DemoSeed.day(DemoSeed.checkupDay()));
        return id;
    }

    /**
     * 下午**也有专家号**，直接给出——不再降级到上午。
     *
     * <p>排班口径变过一次：早先「下午一律不排专家号」，于是「下午要专家号」只能走
     * R1 的**时段级**降级（把你引到上午）。现在一天 4 格里 14:00 就是专家格，
     * 下午要专家号是能直接满足的正常诉求。
     */
    @Test void askingForAnExpertInTheAfternoonOffersTheAfternoonExpert() {
        String id = conversationAtPeriodSelection();

        AgentTurnResponse turn = service.chat(id, "我要挂专家号，下午去");

        assertThat(turn.stage()).isEqualTo("CONFIRM_SLOT");
        List<QuickReply> choices = turn.quickReplies().stream()
                .filter(reply -> "SELECT_SLOT".equals(reply.action())).toList();
        assertThat(choices).as("下午的专家号要直接摆出来").isNotEmpty();
        assertThat(choices.get(0).label())
                .as("下午第一个候选应是 14:00 的专家格（张建国主任医师）")
                .contains("张建国").contains("专家号");
        assertThat(turn.quickReplies())
                .extracting(QuickReply::action)
                .as("绝不静默改日期：不提供替老人换日期的既成动作")
                .doesNotContain("SET_DATE");
    }

    @Test void soldOutMorningStillOffersTheNearestExpertWithinSevenDays() {
        String id = service.start().conversationId();
        act(id, "SET_HOSPITAL", "h001");
        act(id, "SET_DEPARTMENT", "d001");
        LocalDate day = DemoSeed.checkupDay();
        List<String> expertSlots = jdbc.queryForList("""
                SELECT id FROM appointment_slots
                WHERE hospital_id='h001' AND department='心内科'
                  AND appointment_date=? AND slot_type='EXPERT' AND available=TRUE
                """, String.class, java.sql.Date.valueOf(day));
        assertThat(expertSlots).as("体检日专家格有 09:00 / 14:00 两条，必然查得到").isNotEmpty();
        // 手工「约满」：名额口径下占满一整班要写 booked=capacity，available 跟着派生为 FALSE。
        jdbc.update("UPDATE appointment_slots SET booked=capacity, available=FALSE WHERE id IN ("
                + String.join(",", expertSlots.stream().map(s -> "'" + s + "'").toList()) + ")");
        try {
            act(id, "SET_DATE", DemoSeed.day(day));
            AgentTurnResponse turn = service.chat(id, "我要专家号");

            assertThat(turn.reply()).as("R1：先明说当日无专家号").contains("当日没有专家号");
            assertThat(turn.reply()).as("R1：主动给出 7 天内最近的专家号，且是问句不是动作")
                    .contains("7天内最近").contains("要看这一天的号吗");
            assertThat(turn.quickReplies()).extracting(QuickReply::action)
                    .contains("SET_DATE", "SET_EXPERT", "RETRY_QUERY");
        } finally {
            // 精确还原：只放开这次故意占满的那几条，不动库里其他预约状态。
            jdbc.update("UPDATE appointment_slots SET booked=0, available=TRUE WHERE id IN ("
                    + String.join(",", expertSlots.stream().map(s -> "'" + s + "'").toList()) + ")");
        }
    }

    /**
     * 点名一位「今天没有可约号」的医生：报出诊名单，并给出他最近一次出诊日（只问，不擅自换）。
     *
     * <p>排法改过之后，**每位医生每个放号日都在岗**，自然排班里造不出「他今天不出诊」，
     * 所以这里手工把他当天的号**约满**（{@code booked=capacity}）来构造这个场景——
     * 对助手而言「这位医生今天一条可约号都没有」，走的正是同一条兜底路径。
     */
    @Test void namingADoctorWhoseSlotsAreGoneStillReportsTheOnDutyListAndHerNextDay() {
        String id = conversationAtPeriodSelection();
        // 李秀英（doc-d001-02）周三在 15:30 出诊；把她当天那班约满，她就「今天没得约」了。
        jdbc.update("""
                UPDATE appointment_slots SET booked=capacity, available=FALSE
                WHERE doctor_id='doc-d001-02' AND appointment_date=?
                """, java.sql.Date.valueOf(DemoSeed.checkupDay()));
        try {
            AgentTurnResponse turn = service.chat(id, "我找李秀英医生");

            assertThat(turn.reply()).contains("李秀英医生没有出诊");
            assertThat(turn.reply()).as("R6 兜底：报出真实出诊名单，不说查无此人").contains("出诊的有");
            assertThat(turn.quickReplies()).extracting(QuickReply::action).contains("SET_DATE", "CHANGE_DATE");
        } finally {
            jdbc.update("""
                    UPDATE appointment_slots SET booked=0, available=TRUE
                    WHERE doctor_id='doc-d001-02' AND appointment_date=?
                    """, java.sql.Date.valueOf(DemoSeed.checkupDay()));
        }
    }

    @Test void namingADoctorOnDutyScopesTheCandidatesToThatDoctor() {
        String id = conversationAtPeriodSelection();

        // 张建国（doc-d001-01）体检日下午出诊（14:00 专家格），下午只有这一条。
        AgentTurnResponse turn = service.chat(id, "我找张建国医生，下午的号");

        assertThat(turn.stage()).isEqualTo("CONFIRM_SLOT");
        assertThat(turn.reply()).as("候选收缩到这位医生").contains("张建国");
        List<QuickReply> choices = turn.quickReplies().stream()
                .filter(reply -> "SELECT_SLOT".equals(reply.action())).toList();
        // 一天 4 格 = 4 位医生，每人恰好 1 格，所以点名医生后当天只有 1 条号源，
        // 走的是**单候选**路径——按钮 label 固定是「这个时间可以」，不带医生名。
        // 因此这里盯号源 id（id 里含医生）而不是 label，才是真正在验「收缩到了这位医生」。
        assertThat(choices).as("点名医生后只出这位医生的号，当天最多 1 条").isNotEmpty()
                .hasSizeLessThanOrEqualTo(2)
                .allSatisfy(reply -> assertThat(reply.value()).contains("doc-d001-01"));
        // R7 的轮次边界（DEC-027）：点名是「按您说的给」，不说「推荐」；
        // 也**不报**当天的另一半——此刻数出来的是别的医生的号，与老人刚说出口的诉求相反。
        assertThat(turn.reply()).as("点名医生后不说「推荐」").doesNotContain("推荐");
        assertThat(turn.reply()).as("点名医生后也不报别的医生的号").doesNotContain("个时段");
    }

    @Test void recommendationOffersAtMostTwoCandidatesWithDoctorLabelsAndSimplifiedSpeech() {
        String id = conversationAtPeriodSelection();

        AgentTurnResponse turn = service.chat(id, "上午的号");

        assertThat(turn.stage()).isEqualTo("CONFIRM_SLOT");
        List<QuickReply> choices = turn.quickReplies().stream()
                .filter(reply -> "SELECT_SLOT".equals(reply.action())).toList();
        assertThat(choices).as("R3：单轮最多 2 个候选，且来自两位不同医生")
                .hasSize(2);
        // 上午两格：09:00 是专家格（王建华副主任医师），10:30 是普通格（陈凤兰主治医师）。
        // 排序按职称降序，副主任医师(2) 在主治医师(1) 之前 —— 候选 0 是专家格。
        assertThat(choices.get(0).label()).contains("王建华副主任医师").contains("专家号");
        assertThat(choices.get(1).label()).as("第二候选应是普通格的那位")
                .contains("陈凤兰主治医师").contains("普通号");
        assertThat(turn.reply()).as("R6：候选里带着号别").contains("专家号").contains("普通号");

        // R7（DEC-027）：「推荐」+ 医生信息在前、时间顺带跟在后面。
        assertThat(turn.reply()).as("R7：自动推荐轮要说「推荐」，并把医生信息与时间一起讲清")
                .contains("我先推荐王建华副主任医师").contains("09:00");
        // R8：报的是**另一个半天**的条数。未点名时上午的 2 个候选就是上午全部，
        // 数一遍等于重复老人已经看到的信息；下午那 2 个才是他看不到的一半。
        assertThat(turn.reply()).as("R8：报当天另一半的条数").contains("今天下午还有2个时段");

        // R9：按钮一屏装得下（前端每屏只渲染 3 个）。「改选下午」并进「看全部下午时间」，
        // 不再需要先点一次「查看更多选项」才露得出来——而它正是下午那两个时段的入口。
        assertThat(turn.quickReplies()).as("R9：推荐轮按钮 ≤3").hasSizeLessThanOrEqualTo(3);
        assertThat(turn.quickReplies()).extracting(QuickReply::action)
                .as("R9：改选另一半并进「看全部」，出口没丢（那一页里本来就有一条）")
                .contains("SHOW_PERIOD_SLOTS").doesNotContain("SET_PERIOD");

        // R3 口播：姓氏职称 + 预估自付；不读全名，不播「挂号费」原价口径。
        assertThat(turn.speechText()).isNotNull();
        assertThat(turn.speechText()).contains("王副主任医师").contains("专家号").contains("预估自付40元");
        assertThat(turn.speechText()).doesNotContain("王建华").doesNotContain("挂号费");
        // R7（口播部分）：也说「推荐」；R8 的条数只落在卡片文字上，口播里不出现。
        assertThat(turn.speechText()).as("R7：口播也要说「推荐」").contains("我推荐");
        assertThat(turn.speechText()).as("R8：口播不带计数，保持 R3 的短句").doesNotContain("个时段");
    }

    /**
     * 自动推荐轮也会落在**单候选**上：把上午 09:00 的专家格约满，上午就只剩 10:30 那位医生。
     *
     * <p>这时话术照样说「推荐」，并且报出「下午还有 2 个时段」——老人停在上午看的时候，
     * 那才是他看不到的一半。
     */
    @Test void aLoneCandidateStillSaysRecommendedAndPointsAtTheOtherHalfDay() {
        // 顺序要紧：**先约满、再定日期**。定日期那一步（SET_DATE）就会查一次号源并把结果缓存在
        // state.alternatives 里，`recommendPeriod` 直接用它、不会重查——约满晚一步就白约了。
        String morningExpert = DemoSeed.morningSlot();
        jdbc.update("UPDATE appointment_slots SET booked=capacity, available=FALSE WHERE id=?", morningExpert);
        try {
            String id = conversationAtPeriodSelection();
            AgentTurnResponse turn = service.chat(id, "上午的号");

            assertThat(turn.stage()).isEqualTo("CONFIRM_SLOT");
            assertThat(turn.quickReplies().stream()
                    .filter(reply -> "SELECT_SLOT".equals(reply.action())).toList())
                    .as("上午只剩一位医生").hasSize(1);
            assertThat(turn.reply()).as("R7：单候选也要说「推荐」，并带上医生与时间")
                    .contains("我推荐").contains("陈凤兰主治医师").contains("10:30");
            assertThat(turn.reply()).as("R8：报的是当天的另一半，不是眼前这一半")
                    .contains("今天下午还有2个时段");
        } finally {
            jdbc.update("UPDATE appointment_slots SET booked=0, available=TRUE WHERE id=?", morningExpert);
        }
    }

    /**
     * 老人直接说了具体时刻 → 走「按您说的给」，**不说**「推荐」。
     *
     * <p>但仍然报当天的另一半：他要的那个点恰好没号，这正是最需要知道「还有别的时段」的时候。
     * 15:00 这天没有号（排班是 14:00 / 15:30），会落到「最接近且仍可约」的那一格。
     */
    @Test void aRequestedTimeKeepsTheOldWordingAndStillReportsTheOtherHalfDay() {
        String id = conversationAtPeriodSelection();

        AgentTurnResponse turn = service.chat(id, "下午3点的号");

        assertThat(turn.reply()).as("R7 的轮次边界：指定时刻不是「推荐」").doesNotContain("推荐");
        assertThat(turn.reply()).as("给出最接近且仍可约的那一格").contains("15:30");
        assertThat(turn.reply()).as("R8：另一半照样报").contains("今天上午还有2个时段");
    }

    /**
     * 老人说的钟点是中文数字：「我要下午的三点半的」= 15:30（方案 C，说话这条路）。
     *
     * <p>老人不会说「15:30」，说出口的是「三点半」。原来的钟点正则只认阿拉伯数字，
     * 这句话一个字也抽不出来，只会退化成「下午」这一档——于是又被问一遍「上午还是下午」。
     * 而且「下午」和「三点半」中间隔着一个「的」，只看紧挨着钟点的那几个字还会算成凌晨 3:30。
     */
    @Test void aSpokenChineseTimeAlsoLandsOnTheRightSlot() {
        String id = conversationAtPeriodSelection();

        AgentTurnResponse turn = service.chat(id, "我要下午的三点半的");

        assertThat(turn.reply()).as("三点半落在 15:30 —— 下午的普通格").contains("15:30");
        assertThat(turn.stage()).isEqualTo("CONFIRM_SLOT");
        assertThat(turn.quickReplies()).extracting(QuickReply::action).contains("SELECT_SLOT");
    }

    @Test void onDutyBriefSaysWhichHalfDayEachDoctorWorks() {
        String id = service.start().conversationId();
        act(id, "SET_HOSPITAL", "h001");
        act(id, "SET_DEPARTMENT", "d001");

        // 定日期那轮的播报（periodSummary）就该说清「谁出诊、上午还是下午」。
        // 体检日是 d001 的 role1 天：两位主任上下午对调、两位主治也上下午对调，
        // 于是 4 格 4 位医生，每人恰好半天。
        AgentTurnResponse turn = act(id, "SET_DATE", DemoSeed.day(DemoSeed.checkupDay()));

        assertThat(turn.reply()).contains("出诊的有");
        assertThat(turn.reply()).as("医生要带上半天，老人才能对上「上午找谁」")
                .contains("王建华副主任医师（专家号，上午出诊，挂号费40元）")
                .contains("陈凤兰主治医师（普通号，上午出诊，挂号费25元）")
                .contains("张建国主任医师（专家号，下午出诊，挂号费40元）")
                .contains("李秀英主治医师（普通号，下午出诊，挂号费25元）");
    }

    @Test void packedCandidateStillGoesThroughTheConfirmationGate() {
        String id = conversationAtPeriodSelection();
        AgentTurnResponse turn = service.chat(id, "上午的号");

        String slotId = turn.quickReplies().stream()
                .filter(reply -> "SELECT_SLOT".equals(reply.action()))
                .findFirst().orElseThrow(() -> new AssertionError("应有候选按钮")).value();
        AgentTurnResponse picked = act(id, "SELECT_SLOT", slotId);

        // 门禁（C-02/DEC-002）：选定号源 ≠ 预约成立，仍要走完整确认卡。
        assertThat(picked.stage()).isNotEqualTo("COMPLETED");
        assertThat(picked.stage()).isNotEqualTo("AWAITING_CONFIRMATION");
        Integer booked = jdbc.queryForObject("SELECT COUNT(*) FROM appointments", Integer.class);
        assertThat(booked).as("没过确认卡，库里不能有预约").isZero();
    }
}
