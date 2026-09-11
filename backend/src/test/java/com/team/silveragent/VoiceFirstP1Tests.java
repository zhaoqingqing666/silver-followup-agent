package com.team.silveragent;

import com.team.silveragent.agent.model.ModelGateway;
import com.team.silveragent.agent.model.ModelRequest;
import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.model.AgentTurnResponse.UiDirectiveType;
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
        jdbc.update("UPDATE appointment_slots SET available=TRUE");
        jdbc.update("UPDATE appointment_slots SET available=FALSE WHERE appointment_date='2026-09-19'");
    }

    private AgentTurnResponse action(String id, String action, String value) {
        return service.act(id, action, value, action);
    }

    private AgentTurnResponse prepare(String id, String slotId) {
        action(id, "SET_HOSPITAL", "h001");
        action(id, "SET_DEPARTMENT", "d001");
        action(id, "SET_DATE", "2026-09-18");
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
        AgentTurnResponse prepared = prepare(id, "slot-0918-0900");
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
        AgentTurnResponse prepared = prepare(id, "slot-0918-0900");
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

        AgentTurnResponse confirmation = prepare(id, "slot-0918-0900");

        assertThat(confirmation.reply()).isEqualTo(confirmation.speechText());
        assertThat(confirmation.reply())
                .contains("请确认本次复诊安排", "9月18日", "上午9点", "市第一医院", "心内科")
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
}
