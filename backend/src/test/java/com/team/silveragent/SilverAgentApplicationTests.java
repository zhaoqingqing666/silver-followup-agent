package com.team.silveragent;

import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:silver-agent-test;DB_CLOSE_DELAY=-1")
class SilverAgentApplicationTests {
    @Autowired FollowupAgentService service;

    @Test
    void normalFlowRequiresConfirmationAndCreatesResult() {
        AgentTurnResponse turn = service.start();
        String id = turn.conversationId();
        service.chat(id, "市第一医院", false);
        service.chat(id, "心内科", false);
        service.chat(id, "9月18日", false);
        service.chat(id, "可以换日期", false);
        service.chat(id, "需要陪同", false);
        service.chat(id, "需要出行提醒", false);
        turn = service.chat(id, "通知女儿", false);

        assertThat(turn.stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(turn.confirmation()).isNotNull();
        assertThat(turn.toolTraces()).extracting(AgentTurnResponse.ToolTrace::toolName)
                .contains("appointment.querySlots", "schedule.checkConflict", "travel.plan", "family.queryContact");

        AgentTurnResponse completed = service.confirm(id, true);
        assertThat(completed.stage()).isEqualTo("COMPLETED");
        assertThat(completed.result().appointmentId()).startsWith("AP-");
        assertThat(completed.toolTraces()).extracting(AgentTurnResponse.ToolTrace::toolName)
                .contains("appointment.submit", "schedule.createReminder", "family.sendNotification");
    }

    @Test
    void noSlotReturnsAlternativeInsteadOfEndingConversation() {
        AgentTurnResponse turn = service.start();
        String id = turn.conversationId();
        service.chat(id, "市第一医院", false);
        service.chat(id, "心内科", false);
        service.chat(id, "9月19日", false);
        service.chat(id, "可以换日期", false);
        service.chat(id, "不需要陪同", false);
        service.chat(id, "不需要出行提醒", false);
        turn = service.chat(id, "不用通知", false);

        assertThat(turn.stage()).isEqualTo("NO_SLOT");
        assertThat(turn.quickReplies()).isNotEmpty();
        assertThat(turn.reply()).contains("没有可预约时段");
    }

    @Test
    void medicalQuestionDoesNotCallBusinessTools() {
        AgentTurnResponse turn = service.start();
        AgentTurnResponse refused = service.chat(turn.conversationId(), "检查结果是不是说明我得病了", false);
        assertThat(refused.reply()).contains("不能诊断");
        assertThat(refused.toolTraces()).isEmpty();
    }
}
