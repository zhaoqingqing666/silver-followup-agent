package com.team.silveragent.application;

import com.team.silveragent.agent.model.ModelGateway;
import com.team.silveragent.agent.model.ModelRequest;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.support.DemoSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 号源查询的参数执行一致性。
 *
 * <p>规则是结构性的两条（<b>本轮范围只到号源查询</b>）：
 * <ol>
 *   <li><b>模型发起的号源查询只查不改</b>——{@code appointment.querySlots} 与
 *       {@code appointment.queryNearbySlots}，<b>参数给没给都一样</b>。参数（省略的项回草稿里取）
 *       决定这次实际查了什么，但一律不写草稿、不推进阶段、不动候选与确认卡。</li>
 *   <li><b>要改预约，得由老人明说。</b>模型提出业务动作（{@code PROPOSE_WORKFLOW_ACTION}）那一轮
 *       走正常办理，不经过这个只读出口；完全不经过模型的按钮流程照旧。</li>
 * </ol>
 * 其余只读工具（如 {@code hospital.search}）仍可能更新会话里的解析状态，尚未逐个按同一口径核对。
 * 解析不出来（日期过了、医院对不上、不唯一）就停下说清楚，绝不拿旧条件顶替后装作答了这一问。
 *
 * <p>断言看的是工具留痕里的真实参数与返回号源、草稿的实际取值，而不是回复话术——
 * 话术由模型润色，条件由 Java 定，两者混在一起就分不清哪一步出了错。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-test;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=true"})
class SlotQueryArgumentTests {

    /** 下周三：心内科有号（上午 09:00 等）。 */
    private static final LocalDate CHECKUP_DAY = DemoSeed.checkupDay();
    /** 下周六：刻意没有号，用来说「这一天无号」。 */
    private static final LocalDate EMPTY_DAY = DemoSeed.emptyDay();
    /** 体检那天的后一天：心内科下午 14:00、内分泌科下午 14:00 有号。 */
    private static final LocalDate LATER_DAY = DemoSeed.laterDay();

    /** 目录里的名字（界面上的写法带「（模拟）」，解析器会归一化，两种写法都认）。 */
    private static final String FIRST_HOSPITAL = "市第一医院";
    private static final String PEOPLE_HOSPITAL = "市人民医院";
    private static final String CARDIOLOGY = "心内科";
    private static final String ENDOCRINOLOGY = "内分泌科";

    @Autowired FollowupAgentService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired ScriptedModelGateway gateway;

    /** 摆草稿时已经留下的留痕条数，之后的断言只看这一轮新增的。 */
    private int traceBaseline;

    /**
     * 按脚本回答的规划模型。第一轮给什么工具、带什么参数由用例决定；
     * 工具结果回到模型的那一轮固定回一句普通回答，让最终话术不干扰对条件的断言。
     */
    static class ScriptedModelGateway implements ModelGateway {
        private static final String ANSWER = """
                {"actionType":"ANSWER","intent":"UNKNOWN","toolName":null,"arguments":{},
                 "replyDraft":"我按您说的医院和日期查过了，上面就是这次的结果。",
                 "dialogueMode":"FOLLOWUP_FLOW","facts":{}}
                """;

        volatile String planning = "";
        /** 工具结果回到模型时它回什么；默认是一句普通回答，用例可换成别的形状。 */
        volatile String continuation = ANSWER;
        /** 工具结果回到模型时，模型实际看到的那段证据（含实际使用的查询条件）。 */
        volatile String lastToolPhasePrompt = "";
        final AtomicInteger calls = new AtomicInteger();

        @Override public String complete(ModelRequest request) {
            calls.incrementAndGet();
            String latest = request.messages().isEmpty() ? ""
                    : request.messages().get(request.messages().size() - 1).content();
            // 续跑阶段的判据是模型收到的那段证据本身：工具结果以「同一用户轮次内刚刚执行完成的
            // 真实只读工具结果」开头（与 VoiceFirstP1 的桩同一口径），而不是靠 system prompt 里
            // 的某个标记——那个标记只存在于另一条生成路径，续跑阶段看不到。
            if (latest.contains("同一用户轮次内刚刚执行完成的真实只读工具结果")) {
                lastToolPhasePrompt = latest;
                return continuation;
            }
            return planning;
        }

        @Override public boolean available() { return true; }
        @Override public String providerName() { return "test"; }
        @Override public String modelName() { return "test"; }
    }

    @TestConfiguration
    static class ScriptedModelConfig {
        @Bean @Primary
        ScriptedModelGateway scriptedModelGateway() { return new ScriptedModelGateway(); }
    }

    @BeforeEach void resetData() {
        gateway.planning = "";
        gateway.continuation = ScriptedModelGateway.ANSWER;
        gateway.lastToolPhasePrompt = "";
        gateway.calls.set(0);
        for (String table : List.of("appointments", "reminders", "family_notifications")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE appointment_slots SET available=TRUE");
    }

    // ---------------------------------------------------------------- 用例

    /** 草稿还没有日期：查的是参数点名的那天，但草稿的日期<b>仍然是空的</b>。 */
    @Test void draftWithoutDateQueriesExactlyTheDayTheModelNamed() {
        String id = draft(FIRST_HOSPITAL, DemoSeed.CARDIOLOGY, null);
        planQuery("appointment.querySlots", args(FIRST_HOSPITAL, CARDIOLOGY, CHECKUP_DAY));

        AgentTurnResponse result = service.chat(id, "帮我查" + DemoSeed.chineseDay(CHECKUP_DAY) + "的号");

        AgentTurnResponse.ToolTrace trace = lastTrace(result, "appointment.querySlots");
        assertThat(trace.parameters()).as("实际查的就是参数里点名的那一天").contains(CHECKUP_DAY.toString());
        assertThat(trace.result()).as("返回的是那一天的真实号源").contains(DemoSeed.MORNING.toString());
        assertThat(liveState(id).date).as("只查不写：草稿的日期仍然是空的").isNull();
        assertThat(result.plan().date()).as("界面上的草稿也没被这次查询填上").isEqualTo("待确认");
        assertThat(gateway.lastToolPhasePrompt).as("回到模型的证据里带着这次实际用的条件，并问一句要不要按它来")
                .contains(DemoSeed.chineseDay(CHECKUP_DAY)).contains("要按这个来吗");
    }

    /** 参数与草稿完全一致时也一样：这一轮只查不改，草稿不会因为「查过了」前进一步。 */
    @Test void queryingTheDayTheDraftAlreadyHasAlsoLeavesTheDraftAlone() {
        String id = draft(FIRST_HOSPITAL, DemoSeed.CARDIOLOGY, CHECKUP_DAY);
        String stageBefore = liveState(id).stage.name();
        planQuery("appointment.querySlots", args(FIRST_HOSPITAL, CARDIOLOGY, CHECKUP_DAY));

        AgentTurnResponse result = service.chat(id, "帮我查" + DemoSeed.chineseDay(CHECKUP_DAY) + "的号");

        AgentTurnResponse.ToolTrace trace = lastTrace(result, "appointment.querySlots");
        assertThat(trace.parameters()).contains(CHECKUP_DAY.toString());
        assertThat(liveState(id).date).isEqualTo(CHECKUP_DAY);
        assertThat(liveState(id).selectedSlot).as("没有替老人选中任何时段").isNull();
        assertThat(liveState(id).stage.name()).as("阶段也没跟着这次查询往前推").isEqualTo(stageBefore);
        assertThat(result.plan().date()).isEqualTo(DemoSeed.chineseDay(CHECKUP_DAY));
    }

    @Test void explicitOtherDayQueriesOnlyThatDayAndLeavesTheDraftDateAlone() {
        // 草稿停在下周六（无号），模型明确传的是下周三（有号）
        String id = draft(FIRST_HOSPITAL, DemoSeed.CARDIOLOGY, EMPTY_DAY);
        planQuery("appointment.querySlots", args(FIRST_HOSPITAL, CARDIOLOGY, CHECKUP_DAY));

        AgentTurnResponse result = service.chat(id, "帮我查" + DemoSeed.chineseDay(CHECKUP_DAY) + "的号");

        AgentTurnResponse.ToolTrace trace = lastTrace(result, "appointment.querySlots");
        assertThat(trace.parameters()).contains(CHECKUP_DAY.toString());
        assertThat(trace.result()).contains(DemoSeed.MORNING.toString());
        assertThat(result.plan().date()).as("原草稿日期仍是下周六").isEqualTo(DemoSeed.chineseDay(EMPTY_DAY));
        assertThat(result.quickReplies()).as("别处的号源不能拿来改动手头这笔预约")
                .noneMatch(item -> "SELECT_SLOT".equals(item.action()));
        assertThat(gateway.lastToolPhasePrompt).contains("我查了").contains(DemoSeed.chineseDay(CHECKUP_DAY));
    }

    @Test void explicitOtherHospitalAndDepartmentWinOverTheDraftAndLeaveItAlone() {
        // 草稿是市第一医院心内科下周三；模型要查的是市人民医院内分泌科，明确传的另一天
        String id = draft(FIRST_HOSPITAL, DemoSeed.CARDIOLOGY, CHECKUP_DAY);
        planQuery("appointment.querySlots", args(PEOPLE_HOSPITAL, ENDOCRINOLOGY, LATER_DAY));

        AgentTurnResponse result = service.chat(id, "帮我查" + DemoSeed.chineseDay(LATER_DAY) + "的号");

        AgentTurnResponse.ToolTrace trace = lastTrace(result, "appointment.querySlots");
        assertThat(trace.parameters()).contains(LATER_DAY.toString()).contains(ENDOCRINOLOGY);
        assertThat(trace.result()).as("返回的是内分泌科那天的号源").contains(ENDOCRINOLOGY)
                .contains(LATER_DAY.toString());
        assertThat(result.plan().hospital()).contains(FIRST_HOSPITAL);
        assertThat(result.plan().department()).contains(CARDIOLOGY);
        assertThat(result.plan().date()).isEqualTo(DemoSeed.chineseDay(CHECKUP_DAY));
    }

    @Test void aQueryThatAlsoEchoesTheDayIntoFactsStillLeavesTheDraftAlone() {
        // 真模型会把这个日期同时写进工具参数和 facts 节点。只读工具这一轮只查不改，
        // 两条路都写不进草稿：参数那一路进了只读出口，facts 那一路在进业务流程前就被拿掉了。
        String id = draft(FIRST_HOSPITAL, DemoSeed.CARDIOLOGY, EMPTY_DAY);
        planQuery("appointment.querySlots", args(FIRST_HOSPITAL, CARDIOLOGY, CHECKUP_DAY),
                "{\"department\":\"" + CARDIOLOGY + "\",\"date\":\"" + CHECKUP_DAY + "\"}");

        AgentTurnResponse result = service.chat(id, "帮我查" + DemoSeed.chineseDay(CHECKUP_DAY) + "的号");

        AgentTurnResponse.ToolTrace trace = lastTrace(result, "appointment.querySlots");
        assertThat(trace.parameters()).contains(CHECKUP_DAY.toString());
        assertThat(result.plan().date()).as("问的是另一天，草稿仍是原来那天")
                .isEqualTo(DemoSeed.chineseDay(EMPTY_DAY));
        assertThat(result.quickReplies()).as("别处的号源不能拿来改动手头这笔预约")
                .noneMatch(item -> "SELECT_SLOT".equals(item.action()));
        assertThat(result.quickReplies()).as("只读回答也得给老人留下一步能按的").isNotEmpty();
        assertThat(liveState(id).date).as("会话状态里的日期也没被这次查询顶掉").isEqualTo(EMPTY_DAY);
    }

    @Test void aContinuationThatOnlyRepeatsTheQueryDoesNotRunItAgainNorMoveTheDraft() {
        // 真模型在收到工具结果后不再提工具，而是把它刚查到的条件又写回 facts，用一句话作答。
        // 这一轮要是再按「查询」走一遍，同一句话就既改了草稿、又白查一次。
        String id = draft(FIRST_HOSPITAL, DemoSeed.CARDIOLOGY, EMPTY_DAY);
        planQuery("appointment.querySlots", args(FIRST_HOSPITAL, CARDIOLOGY, CHECKUP_DAY));
        gateway.continuation = """
                {"actionType":"ASK_USER","intent":"QUERY_AVAILABLE_SLOTS","toolName":null,"arguments":{},
                 "toolCalls":[],"replyDraft":"查到了，市第一医院心内科那天有号，您想约哪个时间？",
                 "dialogueMode":"FOLLOWUP_FLOW","facts":{"hospital":"%s","department":"%s","date":"%s"}}
                """.formatted(FIRST_HOSPITAL, CARDIOLOGY, CHECKUP_DAY);

        AgentTurnResponse result = service.chat(id, "帮我查" + DemoSeed.chineseDay(CHECKUP_DAY) + "的号");

        assertThat(newTraces(result).stream()
                .filter(item -> "appointment.querySlots".equals(item.toolName())).count())
                .as("只查了参数里点名的那一天，续跑轮没有把它当成新查询再跑一遍").isEqualTo(1);
        assertThat(result.reply()).as("模型那句话照常交付").contains("您想约哪个时间");
        assertThat(result.plan().date()).as("草稿日期没被这次查询顶掉")
                .isEqualTo(DemoSeed.chineseDay(EMPTY_DAY));
        assertThat(liveState(id).date).isEqualTo(EMPTY_DAY);
        assertThat(result.quickReplies()).as("别处的号源不能拿来改动手头这笔预约")
                .noneMatch(item -> "SELECT_SLOT".equals(item.action()));
    }

    @Test void aContinuationLabelledChangeDateStillOnlyAnswersTheQuery() {
        // 真模型给同一句复述贴的标签并不稳定：实测出现过 QUERY_AVAILABLE_SLOTS、CREATE_FOLLOWUP，
        // 也出现过 CHANGE_DATE。标签一换，落点就成了改日期流程——先是 resetAfterDate 把草稿日期抹掉，
        // 再按续跑轮 facts 里带回来的那一天重新查，老人手头那笔预约就这么被一次查询改掉了。
        // 所以收口不看 intent 标签：工具循环里的每一轮都只交付结果与措辞，不重新进预约业务流。
        String id = draft(FIRST_HOSPITAL, DemoSeed.CARDIOLOGY, EMPTY_DAY);
        planQuery("appointment.querySlots", args(FIRST_HOSPITAL, CARDIOLOGY, CHECKUP_DAY));
        gateway.continuation = """
                {"actionType":"PROPOSE_WORKFLOW_ACTION","intent":"CHANGE_DATE","toolName":null,"arguments":{},
                 "toolCalls":[],"replyDraft":"我查到了，市第一医院心内科下周三有号，您想约哪个时间？",
                 "dialogueMode":"FOLLOWUP_FLOW","facts":{"hospital":"%s","department":"%s","date":"%s"}}
                """.formatted(FIRST_HOSPITAL, CARDIOLOGY, CHECKUP_DAY);

        AgentTurnResponse result = service.chat(id, "帮我查" + DemoSeed.chineseDay(CHECKUP_DAY) + "的号");

        assertThat(newTraces(result).stream()
                .filter(item -> "appointment.querySlots".equals(item.toolName())).count())
                .as("续跑轮没有提工具，不该再查一遍").isEqualTo(1);
        assertThat(result.reply()).as("模型那句话照常交付").contains("您想约哪个时间");
        assertThat(liveState(id).date).as("改期标签也不能把草稿日期抹掉").isEqualTo(EMPTY_DAY);
        assertThat(liveState(id).stage).as("更没有进改日期流程").isNotEqualTo(ConversationState.Stage.ASK_DATE);
        assertThat(result.quickReplies()).as("仍然给老人留下继续办这笔预约的入口").isNotEmpty();
        assertThat(result.quickReplies()).as("但不能摆出改日期/选时段的按钮")
                .noneMatch(item -> "SET_DATE".equals(item.action()) || "SELECT_SLOT".equals(item.action()));
    }

    @Test void anAmbiguousHospitalIsNotAnsweredWithTheDraftOneInTheToolLoop() {
        // 实测（真模型）：老人问「市医院下周三的号」，模型先用 hospital.search 查出两家医院，
        // 续跑轮把问题问回给老人：「系统里有两家…就是这家吗？」。旧流程拿续跑轮 facts 里带回来的
        // 日期，配上草稿里那家医院直接查了一次号并作答——老人明说的「市医院」没被当真，
        // 反而被草稿旧条件顶了，模型那句追问也就没了。
        String id = draft(FIRST_HOSPITAL, DemoSeed.CARDIOLOGY, EMPTY_DAY);
        gateway.planning = """
                {"actionType":"CALL_READ_TOOL","intent":"QUERY_HOSPITALS","toolName":"hospital.search",
                 "arguments":{"keyword":"市医院"},"replyDraft":null,"dialogueMode":"FOLLOWUP_FLOW",
                 "facts":{"department":"%s","date":"%s"}}
                """.formatted(CARDIOLOGY, CHECKUP_DAY);
        gateway.continuation = """
                {"actionType":"ASK_USER","intent":"PROVIDE_INFORMATION","toolName":null,"arguments":{},
                 "toolCalls":[],"replyDraft":"您说的“市医院”，系统里有两家：市第一医院、市人民医院。您之前选的是市第一医院心内科，就是这家吗？",
                 "dialogueMode":"FOLLOWUP_FLOW","facts":{"department":"%s","date":"%s"}}
                """.formatted(CARDIOLOGY, CHECKUP_DAY);

        AgentTurnResponse result = service.chat(id, "帮我查一下市医院" + DemoSeed.chineseDay(CHECKUP_DAY) + "的号");

        assertThat(newTraces(result).stream().map(AgentTurnResponse.ToolTrace::toolName).toList())
                .as("只查了医院目录，没有拿草稿里那家医院去查号")
                .anyMatch(name -> name.startsWith("catalog."))
                .noneMatch(name -> name.startsWith("appointment.query"));
        assertThat(result.reply()).as("模型那句「就是这家吗」照常交付，而不是被 Java 的号源答复顶掉")
                .contains("就是这家吗");
        assertThat(liveState(id).date).as("这一轮 facts 里的查询日期没有提前写进草稿").isEqualTo(EMPTY_DAY);
    }

    @Test void anAmbiguousHospitalStopsForClarificationWithoutTouchingTheDraftOrTheCard() {
        // 「查询医院 → 查询号源」这条链路的第一段：模型先问是哪一家。老人手上还有一张待确认的卡，
        // 这一轮只能停在澄清上——不能把续跑轮带回来的日期写进草稿，更不能拿旧医院接着查号。
        AgentTurnResponse prepared = bookedCard();
        String id = prepared.conversationId();
        gateway.planning = """
                {"actionType":"CALL_READ_TOOL","intent":"QUERY_HOSPITALS","toolName":"hospital.search",
                 "arguments":{"keyword":"市医院"},"replyDraft":null,"dialogueMode":"FOLLOWUP_FLOW",
                 "facts":{"department":"%s","date":"%s"}}
                """.formatted(CARDIOLOGY, CHECKUP_DAY);

        AgentTurnResponse result = service.chat(id, "帮我查一下市医院" + DemoSeed.chineseDay(CHECKUP_DAY) + "的号");

        assertThat(newTraces(result).stream().map(AgentTurnResponse.ToolTrace::toolName).toList())
                .as("歧义医院停在澄清上，没有接着去查号源")
                .anyMatch(name -> name.startsWith("catalog."))
                .noneMatch(name -> name.startsWith("appointment.query"));
        assertThat(liveState(id).hospital).as("原草稿的医院没被换个说法顶掉").isEqualTo(FIRST_HOSPITAL);
        assertThat(liveState(id).date).as("原草稿的日期也没被这次查询的日期顶掉").isEqualTo(CHECKUP_DAY);
        assertThat(result.confirmation()).as("确认卡还在").isNotNull();
        assertThat(result.confirmation().confirmationId())
                .isEqualTo(prepared.confirmation().confirmationId());
        assertThat(result.confirmation().operations()).isEqualTo(prepared.confirmation().operations());
    }

    @Test void anExplicitChangeOfDateOnTheNextTurnStillRunsTheNormalFlowAndDropsTheOldCard() {
        // 只查不改不等于改不了预约：先问一句、再明确要改，两轮分开走。
        AgentTurnResponse prepared = bookedCard();
        String id = prepared.conversationId();
        planQuery("appointment.querySlots", args(FIRST_HOSPITAL, CARDIOLOGY, LATER_DAY));

        AgentTurnResponse queried = service.chat(id, "帮我查" + DemoSeed.chineseDay(LATER_DAY) + "的号");
        assertThat(queried.confirmation()).as("第一轮只是问号，确认卡还在").isNotNull();
        assertThat(liveState(id).date).as("第一轮没改草稿").isEqualTo(CHECKUP_DAY);

        // 下一轮老人明确说「就改到 B 日」：这一轮提的是业务动作，走正常改期流程。
        gateway.planning = """
                {"actionType":"PROPOSE_WORKFLOW_ACTION","intent":"CHANGE_DATE","toolName":null,"arguments":{},
                 "toolCalls":[],"replyDraft":"好的，我把复诊日期改成%s。","dialogueMode":"FOLLOWUP_FLOW",
                 "facts":{"date":"%s"}}
                """.formatted(DemoSeed.chineseDay(LATER_DAY), LATER_DAY);

        AgentTurnResponse result = service.chat(id, "就改到" + DemoSeed.chineseDay(LATER_DAY));

        assertThat(liveState(id).date).as("明确要改时，改期流程照常生效").isEqualTo(LATER_DAY);
        assertThat(result.confirmation()).as("旧确认卡按原规则作废，不能拿着旧凭据改期").isNull();
    }

    /**
     * 完全不经过模型的既有办理流程没被动过：老人按按钮明确选了日期，草稿落下来、流程继续往前推。
     * 这一条是上面两条只读用例的反面对照——只读出口收窄了，预约办理入口不能跟着一起坏。
     */
    @Test void theButtonFlowStillWritesWhatTheElderExplicitlyChose() {
        String id = service.start().conversationId();
        action(id, "SET_HOSPITAL", "h001");
        action(id, "SET_DEPARTMENT", DemoSeed.CARDIOLOGY);
        AgentTurnResponse turn = action(id, "SET_DATE", CHECKUP_DAY.toString());

        assertThat(liveState(id).date).as("按钮选的日期照常写进草稿").isEqualTo(CHECKUP_DAY);
        assertThat(turn.stage()).as("按钮那一轮照旧推进到选时段").isEqualTo("SELECT_PERIOD");
        assertThat(turn.quickReplies()).as("并且按原流程摆出可选的上午/下午")
                .anyMatch(item -> "SET_PERIOD".equals(item.action()));

        AgentTurnResponse chosen = action(id, "SELECT_SLOT", DemoSeed.morningSlot());

        assertThat(liveState(id).selectedSlot).as("选中的时段照常落进草稿").isNotNull();
        assertThat(liveState(id).selectedSlot.id()).isEqualTo(DemoSeed.morningSlot());
        assertThat(chosen.reply()).as("办理流程照常往下走，不是被只读出口接住了").isNotBlank();
    }

    @Test void omittedArgumentsFallBackToTheConditionsAlreadyInTheDraft() {
        // 模型只点了医院，科室和日期省略——沿用草稿里已经明确的那两项，草稿本身不动
        String id = draft(FIRST_HOSPITAL, DemoSeed.CARDIOLOGY, CHECKUP_DAY);
        planQuery("appointment.querySlots", args(FIRST_HOSPITAL, null, null));

        AgentTurnResponse result = service.chat(id, "帮我查下周三的号");

        AgentTurnResponse.ToolTrace trace = lastTrace(result, "appointment.querySlots");
        assertThat(trace.parameters()).contains(CHECKUP_DAY.toString()).contains(CARDIOLOGY);
        assertThat(liveState(id).date).isEqualTo(CHECKUP_DAY);
        assertThat(liveState(id).department).isEqualTo(CARDIOLOGY);
        assertThat(result.plan().date()).isEqualTo(DemoSeed.chineseDay(CHECKUP_DAY));
    }

    @Test void aPastDateDoesNotFallBackToTheDraftDay() {
        // 用 UTC 往前两天，避开业务时区（北京）与容器时区之差
        LocalDate past = LocalDate.now().minusDays(2);
        String id = draft(FIRST_HOSPITAL, DemoSeed.CARDIOLOGY, CHECKUP_DAY);
        planQuery("appointment.querySlots", args(FIRST_HOSPITAL, CARDIOLOGY, past));

        AgentTurnResponse result = service.chat(id, "帮我查" + DemoSeed.chineseDay(past) + "的号");

        assertThat(newTraces(result)).as("日期不合法时不查库，更不拿旧日期替它查")
                .noneMatch(item -> "appointment.querySlots".equals(item.toolName()));
        assertThat(result.plan().date()).as("草稿日期没被这次查询改掉")
                .isEqualTo(DemoSeed.chineseDay(CHECKUP_DAY));
        // 证据里本来就会带上当前草稿（模型得知道手头那笔预约是什么），所以这里不看它有没有出现
        // 草稿日期，只看这一问的答复是「这一天已经过去」——而不是一份不知从哪来的号源清单。
        assertThat(gateway.lastToolPhasePrompt).as("告诉模型的是这一天已经过去，而不是别处的号源")
                .contains("已经过去了").contains("authoritativeResult");
    }

    @Test void anAmbiguousHospitalIsReportedInsteadOfQueryingTheOldOne() {
        // 「医院」两个字在目录里对上不止一家；模型没给出能定下来的名字
        String id = draft(FIRST_HOSPITAL, DemoSeed.CARDIOLOGY, CHECKUP_DAY);
        planQuery("appointment.querySlots", args("医院", CARDIOLOGY, LATER_DAY));

        AgentTurnResponse result = service.chat(id, "帮我查" + DemoSeed.chineseDay(LATER_DAY) + "的号");

        assertThat(newTraces(result)).noneMatch(item -> "appointment.querySlots".equals(item.toolName()));
        assertThat(result.plan().hospital()).as("草稿医院保持原样").contains(FIRST_HOSPITAL);
        assertThat(result.plan().date()).isEqualTo(DemoSeed.chineseDay(CHECKUP_DAY));
        assertThat(gateway.lastToolPhasePrompt).as("把真实候选摆出来让老人重说，而不是替它挑一家")
                .contains("不止一家").contains(FIRST_HOSPITAL).contains(PEOPLE_HOSPITAL);
    }

    /** 模型一个参数都没给：条件回草稿里取，仍然只查不改——阶段、草稿和按钮都不动。 */
    @Test void modelQueryWithNoArgumentsUsesTheDraftConditionsAndMovesNothing() {
        String id = draft(FIRST_HOSPITAL, DemoSeed.CARDIOLOGY, CHECKUP_DAY);
        ConversationState before = liveState(id);
        String stageBefore = before.stage.name();
        planQuery("appointment.querySlots", "{}");

        AgentTurnResponse result = service.chat(id, "帮我看看号");

        AgentTurnResponse.ToolTrace trace = lastTrace(result, "appointment.querySlots");
        assertThat(trace.parameters()).as("省略的项回草稿里取：查的就是草稿那家医院、那个科室、那一天")
                .contains(CHECKUP_DAY.toString()).contains(CARDIOLOGY);
        assertThat(trace.result()).contains(DemoSeed.MORNING.toString());
        assertThat(liveState(id).date).as("草稿日期没被这次查询改动").isEqualTo(CHECKUP_DAY);
        assertThat(liveState(id).department).isEqualTo(CARDIOLOGY);
        assertThat(liveState(id).selectedSlot).as("没有替老人选中任何时段").isNull();
        assertThat(liveState(id).stage.name()).as("阶段也没跟着这次查询往前推").isEqualTo(stageBefore);
        assertThat(result.quickReplies()).as("查询不是办理入口，不摆选时段的按钮")
                .noneMatch(item -> "SELECT_SLOT".equals(item.action()));
        assertThat(gateway.lastToolPhasePrompt).as("回来的是这一次的真实结果，并问一句要不要按它来")
                .contains("要按这个来吗");
    }

    @Test void modelNearbyQueryWithNoArgumentsUsesTheDraftConditionsAndMovesNothing() {
        // 老人家在办下周三那笔（有号），随口问一句附近还有没有别的时间；模型没点名任何条件
        String id = draft(FIRST_HOSPITAL, DemoSeed.CARDIOLOGY, CHECKUP_DAY);
        String stageBefore = liveState(id).stage.name();
        Object alternativesBefore = liveState(id).alternatives;
        planQuery("appointment.queryNearbySlots", "{}");

        AgentTurnResponse result = service.chat(id, "要是没号，帮我看看别的时间");

        AgentTurnResponse.ToolTrace trace = lastTrace(result, "appointment.queryAlternatives");
        // 附近窗口是从草稿那一天往后算的（工具留痕里记的是 date+1 .. date+3）
        assertThat(trace.parameters()).as("以草稿里那一天为准去查附近")
                .contains(CHECKUP_DAY.plusDays(1).toString()).contains(CHECKUP_DAY.plusDays(3).toString());
        assertThat(result.stage()).as("只读查询不把阶段推进到 NO_SLOT").isNotEqualTo("NO_SLOT");
        assertThat(liveState(id).stage.name()).as("会话里的阶段原样不动").isEqualTo(stageBefore);
        assertThat(liveState(id).acceptAlternative).as("查一次附近日期不等于同意换日期").isNull();
        assertThat(liveState(id).alternatives).as("附近查到的时段不作为办理候选落到状态里")
                .isEqualTo(alternativesBefore);
        assertThat(liveState(id).date).as("草稿日期没被这次查询改动").isEqualTo(CHECKUP_DAY);
        assertThat(liveState(id).selectedSlot).as("也没有替老人选中任何时段").isNull();
        assertThat(result.quickReplies()).as("不摆出代表草稿已推进的选择按钮")
                .noneMatch(item -> "SELECT_SLOT".equals(item.action()));

        // 显式参数与草稿一致时同样只是查询，不会顺手把意愿记成「接受」
        String second = draft(FIRST_HOSPITAL, DemoSeed.CARDIOLOGY, EMPTY_DAY);
        planQuery("appointment.queryNearbySlots", args(FIRST_HOSPITAL, CARDIOLOGY, EMPTY_DAY));

        service.chat(second, "要是没号，帮我看看别的时间");

        assertThat(liveState(second).acceptAlternative).isNull();
    }

    @Test void anExistingConfirmationCardSurvivesAQueryAboutAnotherDay() {
        AgentTurnResponse prepared = bookedCard();
        String id = prepared.conversationId();
        planQuery("appointment.querySlots", args(FIRST_HOSPITAL, CARDIOLOGY, LATER_DAY));

        AgentTurnResponse result = service.chat(id, "帮我查" + DemoSeed.chineseDay(LATER_DAY) + "的号");

        assertThat(result.confirmation()).as("确认卡还在，没有被这次查询挤掉").isNotNull();
        assertThat(result.confirmation().confirmationId())
                .isEqualTo(prepared.confirmation().confirmationId());
        assertThat(result.confirmation().operations())
                .isEqualTo(prepared.confirmation().operations());
        assertThat(result.stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(result.reply()).as("回答里点名这次实际查的条件").contains(DemoSeed.chineseDay(LATER_DAY));
        assertThat(result.plan().date()).as("确认卡对应的草稿没被改动")
                .isEqualTo(DemoSeed.chineseDay(CHECKUP_DAY));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM appointments WHERE status='CONFIRMED'", Integer.class))
                .as("查询不执行任何写业务操作").isZero();
    }

    // ---------------------------------------------------------------- 脚手架

    /** 起一段新会话并把草稿摆成指定条件；{@code day} 传 null 就是不设日期。 */
    private String draft(String hospital, String departmentId, LocalDate day) {
        String id = service.start().conversationId();
        AgentTurnResponse last = action(id, "SET_HOSPITAL", hospital);
        last = action(id, "SET_DEPARTMENT", departmentId);
        if (day != null) last = action(id, "SET_DATE", day.toString());
        markReady(id, last);
        return id;
    }

    /** 走完整办理漏斗，停在确认卡上（还没确认）——与 VoiceFirstP1 的既有做法一致。 */
    private AgentTurnResponse bookedCard() {
        String id = service.start().conversationId();
        action(id, "SET_HOSPITAL", "h001");
        action(id, "SET_DEPARTMENT", DemoSeed.CARDIOLOGY);
        action(id, "SET_DATE", CHECKUP_DAY.toString());
        action(id, "SELECT_SLOT", DemoSeed.morningSlot());
        action(id, "SET_ALTERNATIVE", "true");
        action(id, "SET_COMPANION", "true");
        action(id, "SET_TRAVEL", "true");
        action(id, "SET_TRANSPORT", "打车");
        action(id, "SET_NOTIFY", "true");
        AgentTurnResponse turn = action(id, "SET_CONTACT", "family-001");
        assertThat(turn.stage()).as(turn.reply()).isEqualTo("AWAITING_CONFIRMATION");
        markReady(id, turn);
        return turn;
    }

    /**
     * 摆草稿的动作本身也会调工具（{@code SET_DATE} 就顺手查了一次号源），那些留痕属于搭建过程，
     * 不属于这一轮对话。记下当时的条数，断言只看向后新增的部分。
     */
    private void markReady(String id, AgentTurnResponse last) {
        traceBaseline = last.toolTraces().size();
        gateway.calls.set(0);
        gateway.lastToolPhasePrompt = "";
    }

    private AgentTurnResponse action(String id, String action, String value) {
        return service.act(id, action, value, action);
    }

    private ConversationState liveState(String id) {
        return sessions().get(id);
    }

    @SuppressWarnings("unchecked")
    private Map<String, ConversationState> sessions() {
        return (Map<String, ConversationState>) ReflectionTestUtils.getField(service, "sessions");
    }

    /** 模型这一轮提哪个工具、带哪几个参数。 */
    private void planQuery(String toolName, String argumentsJson) {
        planQuery(toolName, argumentsJson, "{}");
    }

    /** 同上，另外指定模型写进 {@code facts} 节点的内容。 */
    private void planQuery(String toolName, String argumentsJson, String factsJson) {
        gateway.planning = """
                {"actionType":"CALL_READ_TOOL","intent":"QUERY_AVAILABLE_SLOTS","toolName":"%s","arguments":%s,
                 "replyDraft":null,"dialogueMode":"FOLLOWUP_FLOW","facts":%s}
                """.formatted(toolName, argumentsJson, factsJson);
    }

    /** 只写传了的参数；传 null 就是「模型省略了这一项」。 */
    private static String args(String hospital, String department, LocalDate day) {
        List<String> parts = new ArrayList<>();
        if (hospital != null) parts.add("\"hospital\":\"" + hospital + "\"");
        if (department != null) parts.add("\"department\":\"" + department + "\"");
        if (day != null) parts.add("\"date\":\"" + day + "\"");
        return "{" + String.join(",", parts) + "}";
    }

    /** 这一轮对话新增的留痕（草稿搭建阶段的那些不算）。 */
    private List<AgentTurnResponse.ToolTrace> newTraces(AgentTurnResponse result) {
        return result.toolTraces().subList(traceBaseline, result.toolTraces().size());
    }

    /** 最近一次该工具的留痕：同一段会话里可能查过多次，要看的是这一次。 */
    private AgentTurnResponse.ToolTrace lastTrace(AgentTurnResponse result, String toolName) {
        return newTraces(result).stream()
                .filter(item -> toolName.equals(item.toolName()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("这一轮没有调用 " + toolName));
    }
}
