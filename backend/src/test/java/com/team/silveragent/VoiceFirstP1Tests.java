package com.team.silveragent;

import com.team.silveragent.agent.model.ModelGateway;
import com.team.silveragent.agent.model.ModelRequest;
import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.model.AgentTurnResponse.UiDirectiveType;
import com.team.silveragent.support.DemoSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型主导语音 P1：页面和医疗意图都由主模型理解；
 * 有确认卡等待处理时页面指令不得把确认流程挤掉。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-test;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=true"})
class VoiceFirstP1Tests {
    /** 演示种子：下周三（体检那天，有号可约）与下周六（刻意没有号源，用来演「当天没号」）。 */
    private static final String DAY = DemoSeed.day(DemoSeed.checkupDay());
    private static final String CHINESE_DAY = DemoSeed.chineseDay(DemoSeed.checkupDay());
    private static final String EMPTY_DAY = DemoSeed.day(DemoSeed.emptyDay());
    private static final String EMPTY_DAY_TEXT = DemoSeed.chineseDay(DemoSeed.emptyDay());
    private static final String MORNING_SLOT = DemoSeed.morningSlot();

    @Autowired FollowupAgentService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired CountingModelGateway gateway;

    /** 可用的计数网关：只要有一轮对话真的走到了规划模型，计数就会增加。 */
    static class CountingModelGateway implements ModelGateway {
        final AtomicInteger calls = new AtomicInteger();

        @Override public String complete(ModelRequest request) {
            calls.incrementAndGet();
            String system = request.messages().isEmpty() ? "" : request.messages().get(0).content();
            String latest = request.messages().isEmpty() ? ""
                    : request.messages().get(request.messages().size() - 1).content();
            if (system.contains("工具结果后的回答阶段")) {
                return "{\"reply\":\"我已经根据真实记录为您打开对应指引。\"}";
            }
            if (latest.contains("同一用户轮次内刚刚执行完成的真实只读工具结果")) {
                if (latest.contains("material.checklist")) {
                    return """
                            {"actionType":"ANSWER","intent":"EXPLAIN_PROCESS","toolName":null,
                             "arguments":{},"replyDraft":"流程和材料我都查好了，以上内容来自真实模拟知识库。",
                             "dialogueMode":"FOLLOWUP_FLOW","facts":{}}
                            """;
                }
                if (latest.contains("careGuide.search")) {
                    return """
                            {"actionType":"CALL_READ_TOOL","intent":"ASK_MATERIALS",
                             "toolName":"material.checklist","arguments":{},
                             "replyDraft":null,"dialogueMode":"FOLLOWUP_FLOW","facts":{}}
                            """;
                }
                if (latest.contains("NO_SLOT")) {
                    return """
                            {"actionType":"ANSWER","intent":"QUERY_NEARBY_SLOTS","toolName":null,
                             "arguments":{},
                             "replyDraft":"%s暂时没有号。我已经查看真实号源，附近日期还有可选时间，请从下面选择。",
                             "dialogueMode":"FOLLOWUP_FLOW","facts":{}}
                            """.formatted(EMPTY_DAY_TEXT);
                }
            }
            if (latest.contains("连续查流程和材料")) {
                return """
                        {"actionType":"CALL_READ_TOOL","intent":"EXPLAIN_PROCESS",
                         "toolName":"careGuide.search","arguments":{"query":"复诊流程"},
                         "replyDraft":null,"dialogueMode":"FOLLOWUP_FLOW","facts":{}}
                        """;
            }
            if (latest.contains(EMPTY_DAY_TEXT)) {
                return """
                        {"actionType":"CALL_READ_TOOL","intent":"QUERY_AVAILABLE_SLOTS",
                         "toolName":"appointment.querySlots",
                         "arguments":{"hospital":"市第一医院","department":"心内科","date":"%s"},
                         "replyDraft":null,"dialogueMode":"FOLLOWUP_FLOW",
                         "facts":{"date":"%s","acceptAlternative":true}}
                        """.formatted(EMPTY_DAY, EMPTY_DAY);
            }
            if (latest.contains("胸口疼")) {
                return """
                        {"actionType":"ANSWER","intent":"EMERGENCY","toolName":null,"arguments":{},
                         "replyDraft":"这可能是紧急情况，请立即联系身边人员并拨打120。",
                         "dialogueMode":"SUPPORT","facts":{}}
                        """;
            }
            if (latest.contains("院内指引")) {
                return """
                        {"actionType":"CALL_READ_TOOL","intent":"ASK_LOCATION_GUIDE",
                         "toolName":"hospital.locationGuide","arguments":{},
                         "dialogueMode":"FOLLOWUP_FLOW","facts":{}}
                        """;
            }
            if (latest.contains("地图")) {
                return """
                        {"actionType":"CALL_READ_TOOL","intent":"ASK_TRAVEL_ROUTE",
                         "toolName":"travel.routePlan","arguments":{},
                         "dialogueMode":"FOLLOWUP_FLOW","facts":{}}
                        """;
            }
            return """
                    {"actionType":"ANSWER","intent":"SMALL_TALK","toolName":null,"arguments":{},
                     "replyDraft":"好的，我在听。","dialogueMode":"SMALL_TALK","facts":{}}
                    """;
        }

        @Override public boolean available() { return true; }
        @Override public String providerName() { return "test"; }
        @Override public String modelName() { return "test"; }
    }

    @TestConfiguration
    static class CountingModelConfig {
        @Bean @Primary
        CountingModelGateway countingModelGateway() { return new CountingModelGateway(); }
    }

    @BeforeEach void resetData() {
        gateway.calls.set(0);
        for (String table : List.of("appointments", "reminders", "family_notifications")) jdbc.update("DELETE FROM " + table);
        // 周六本来就没有号源（滚动初始化刻意留出的空档），不用再手动关掉某一天。
        jdbc.update("UPDATE appointment_slots SET available=TRUE");
    }

    private AgentTurnResponse action(String id, String action, String value) {
        return service.act(id, action, value, action);
    }

    private AgentTurnResponse prepare(String id, String slotId) {
        action(id, "SET_HOSPITAL", "h001");
        action(id, "SET_DEPARTMENT", "d001");
        action(id, "SET_DATE", DAY);
        action(id, "SELECT_SLOT", slotId);
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

    @Test void bookingThenPageRequestsAreUnderstoodByTheMainModel() {
        String id = service.start().conversationId();
        AgentTurnResponse prepared = prepare(id, MORNING_SLOT);
        service.confirm(id, true, prepared.confirmation().confirmationId());
        gateway.calls.set(0);

        AgentTurnResponse travel = service.chat(id, "我想看地图");
        assertThat(travel.uiDirective()).isNotNull();
        assertThat(travel.uiDirective().type()).isEqualTo(UiDirectiveType.OPEN_TRAVEL);
        assertThat(gateway.calls.get()).as("看地图应由主模型识别并选择工具").isPositive();

        // 只有不带筛选条件的短口令才走快速通道；“到医院里面了怎么走”这类完整问句按设计要交给规划模型。
        AgentTurnResponse inside = service.chat(id, "打开院内指引");
        assertThat(inside.uiDirective()).isNotNull();
        assertThat(inside.uiDirective().type()).isEqualTo(UiDirectiveType.SHOW_INSIDE_GUIDE);
        assertThat(gateway.calls.get()).as("院内指引也应由主模型识别").isPositive();

        // 对照组：这句真的需要模型理解，计数必须增加，证明上面的 0 不是因为桩失效。
        service.chat(id, "今天天气不错");
        assertThat(gateway.calls.get()).as("闲聊仍应交给规划模型").isPositive();
    }

    @Test void pendingConfirmationIsNeverBypassedByPageCommands() {
        String id = service.start().conversationId();
        AgentTurnResponse prepared = prepare(id, MORNING_SLOT);
        gateway.calls.set(0);

        AgentTurnResponse asked = service.chat(id, "打开地图");

        assertThat(asked.confirmation()).isNotNull();
        assertThat(asked.stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(asked.uiDirective()).isNull();
        assertThat(asked.reply()).isNotBlank();
        assertThat(confirmedAppointments()).isZero();

        // 确认卡没有被换掉或清空，待确认的仍然是同一批操作。
        assertThat(asked.confirmation().operations())
                .isEqualTo(prepared.confirmation().operations());
        assertThat(asked.confirmation().confirmationId())
                .isEqualTo(prepared.confirmation().confirmationId());
    }

    @Test void finalCollectedFieldImmediatelyBuildsSpokenConfirmation() {
        String id = service.start().conversationId();

        AgentTurnResponse confirmation = prepare(id, MORNING_SLOT);

        assertThat(confirmation.reply()).isEqualTo(confirmation.speechText());
        assertThat(confirmation.reply())
                .contains("请确认本次复诊安排", CHINESE_DAY, "上午9点", "市第一医院", "心内科")
                .contains("家属陪同", "请携带", "确认办理", "返回修改");
        assertThat(confirmedAppointments()).isZero();
    }

    @Test void emergencyClassificationFromTheMainModelPreemptsPageCommands() {
        String id = service.start().conversationId();
        gateway.calls.set(0);

        AgentTurnResponse emergency = service.chat(id, "我胸口疼，帮我打开地图");

        assertThat(emergency.stage()).isEqualTo("EMERGENCY_PAUSED");
        assertThat(emergency.reply()).contains("120");
        assertThat(emergency.uiDirective()).isNull();
        assertThat(gateway.calls.get()).as("紧急语义应由主模型识别").isPositive();
    }

    @Test void medicalBoundarySurvivesAModelThatMissesIt() {
        String id = service.start().conversationId();
        gateway.calls.set(0);

        // 这个桩对认不出的句子一律回 SMALL_TALK：模拟主模型漏判越界。
        // 漏判的表现是整句话被当成普通信息静默忽略，老人会以为得到了答复，所以规则要兜住。
        AgentTurnResponse boundary = service.chat(id, "我血压有点高，要不要紧？");

        assertThat(boundary.reply()).contains("不能诊断");
        assertThat(gateway.calls.get()).as("兜底判定不额外调用模型").isEqualTo(1);

        // 对照组：同样是“血压”，问自己量过的数属于健康记录，不能被越界判定吞掉。
        AgentTurnResponse record = service.chat(id, "我最近的血压是多少");
        assertThat(record.reply()).as("查自己记过的数不该被当成问诊").doesNotContain("不能诊断");
    }

    @Test void noSlotToolResultReturnsToTheSameModelAndKeepsRealChoices() {
        String id = service.start().conversationId();
        action(id, "SET_HOSPITAL", "h001");
        action(id, "SET_DEPARTMENT", "d001");
        gateway.calls.set(0);

        AgentTurnResponse result = service.chat(id, "帮我查" + EMPTY_DAY_TEXT + "的号");

        assertThat(gateway.calls.get()).as("一次规划加一次工具结果续跑").isEqualTo(2);
        assertThat(result.stage()).isEqualTo("NO_SLOT");
        assertThat(result.reply()).contains(EMPTY_DAY_TEXT + "暂时没有号", "真实号源", "附近日期");
        assertThat(result.quickReplies()).anyMatch(item -> item.action().equals("SELECT_SLOT"));
        assertThat(result.toolTraces()).anyMatch(item -> item.toolName().equals("appointment.querySlots"));
        assertThat(result.toolTraces()).anyMatch(item -> item.toolName().equals("appointment.queryAlternatives"));
    }

    @Test void modelCanChainTwoReadToolsBeforeOneFinalAnswer() {
        String id = service.start().conversationId();
        gateway.calls.set(0);

        AgentTurnResponse result = service.chat(id, "连续查流程和材料");

        assertThat(gateway.calls.get()).as("规划、第二个工具、最终回答").isEqualTo(3);
        assertThat(result.reply()).contains("流程和材料我都查好了", "真实模拟知识库");
        assertThat(result.toolTraces()).anyMatch(item -> item.toolName().equals("careGuide.search"));
        assertThat(result.toolTraces()).anyMatch(item -> item.toolName().equals("material.generateChecklist"));
    }
}
