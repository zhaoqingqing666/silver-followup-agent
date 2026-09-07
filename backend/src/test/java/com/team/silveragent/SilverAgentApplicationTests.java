package com.team.silveragent;

import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:silver-agent-test;DB_CLOSE_DELAY=-1")
class SilverAgentApplicationTests {
    @Autowired FollowupAgentService service;

    @Test
    void normalFlowRequiresConfirmationAndCreatesResult() {
        AgentTurnResponse turn = service.start();
        String id = turn.conversationId();
        LocalDate date = nextWeekday();
        service.act(id, "SET_HOSPITAL", "h001", "市第一医院");
        service.act(id, "SET_DEPARTMENT", "d001", "心内科");
        turn = service.act(id, "SET_DATE", date.toString(), date.toString());
        turn = service.act(id, "SET_PERIOD", "MORNING", "上午");
        String slotId = turn.quickReplies().get(0).value();
        service.act(id, "SELECT_SLOT", slotId, "这个时间可以");
        service.act(id, "SET_ALTERNATIVE", "true", "可以换日期");
        service.act(id, "SET_COMPANION", "true", "需要陪同");
        service.act(id, "SET_TRAVEL", "true", "需要出行提醒");
        service.act(id, "SET_TRANSPORT", "家属开车", "家属开车");
        service.act(id, "SET_NOTIFY", "true", "通知女儿");
        turn = service.act(id, "START_PLAN", "", "开始办理");

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
        LocalDate weekend = nextWeekendWithoutSeed();
        service.act(id, "SET_HOSPITAL", "h001", "市第一医院");
        service.act(id, "SET_DEPARTMENT", "d001", "心内科");
        turn = service.act(id, "SET_DATE", weekend.toString(), weekend.toString());

        assertThat(turn.stage()).isEqualTo("NO_SLOT");
        assertThat(turn.quickReplies()).isNotEmpty();
        assertThat(turn.reply()).contains("没有可预约时段");
    }

    @Test
    void medicalQuestionDoesNotCallBusinessTools() {
        AgentTurnResponse turn = service.start();
        AgentTurnResponse refused = service.chat(turn.conversationId(), "检查结果是不是说明我得病了");
        assertThat(refused.reply()).contains("不能诊断");
        assertThat(refused.toolTraces()).isEmpty();
    }

    @Test
    void sideQueryCanReturnToInterruptedBookingFlow() {
        AgentTurnResponse turn = service.start();
        String id = turn.conversationId();
        service.act(id, "SET_HOSPITAL", "h002", "市人民医院");

        AgentTurnResponse queried = service.chat(id, "我的复诊时间是什么时候");
        assertThat(queried.quickReplies()).extracting(AgentTurnResponse.QuickReply::action)
                .contains("RESUME_INTERRUPTED");

        AgentTurnResponse resumed = service.act(id, "RESUME_INTERRUPTED", "", "继续刚才办理");
        assertThat(resumed.stage()).isEqualTo("ASK_DEPARTMENT");
        assertThat(resumed.reply()).contains("科室");
    }

    private LocalDate nextWeekday() {
        LocalDate date = LocalDate.now().plusDays(1);
        while (date.getDayOfWeek().getValue() >= 6) date = date.plusDays(1);
        return date;
    }

    private LocalDate nextWeekendWithoutSeed() {
        LocalDate date = LocalDate.now().plusDays(1);
        while (date.getDayOfWeek().getValue() < 6) date = date.plusDays(1);
        if (date.equals(LocalDate.of(2026, 9, 19))) date = date.plusDays(1);
        return date;
    }
}
