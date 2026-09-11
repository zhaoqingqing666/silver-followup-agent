package com.team.silveragent;

import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复合多任务需求的端到端流程。
 *
 * <p>一句话里同时提出"预约 + 出行提醒 + 通知家属 + 另外记一件事"，走完整套固定办理流程之后：
 * 用户说过的诉求必须仍然出现在最终计划里，而所有写操作仍然只能在用户确认之后发生。
 *
 * <p>用独立的库，避免和别的测试共用内存库时相互影响号源占用。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:silver-agent-compound-test;DB_CLOSE_DELAY=-1")
class CompoundDemandFlowTests {
    @Autowired FollowupAgentService service;

    @Test
    void compoundDemandSurvivesTheWholeFlowAndWritesOnlyAfterConfirmation() {
        String id = service.start().conversationId();

        AgentTurnResponse turn = service.chat(id,
                "我下周想去医院复诊，帮我安排一下，必须上午，出发前提醒我，并告诉女儿，另外帮我记一下要问医生的问题。",
                false);
        assertThat(turn.stage()).isEqualTo("ASK_HOSPITAL");

        service.chat(id, "市第一医院", false);
        service.chat(id, "心内科", false);
        turn = service.chat(id, "9月18日", false);
        assertThat(turn.stage()).isEqualTo("SELECT_PERIOD");

        service.chat(id, "上午", false);
        service.chat(id, "可以", false);
        service.chat(id, "不需要其他日期", false);
        service.chat(id, "需要陪同", false);
        service.chat(id, "需要出行提醒", false);
        turn = service.chat(id, "打车", false);
        assertThat(turn.stage()).isEqualTo("READY_TO_PLAN");

        turn = service.act(id, "START_PLAN", "");
        assertThat(turn.stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(turn.confirmation()).isNotNull();
        // 首轮说的硬性要求和补充诉求，跨了十来轮之后仍然在计划里，没有被后来的话冲掉。
        assertThat(turn.plan().tasks())
                .contains("按您的要求：必须上午")
                .anyMatch(item -> item.contains("要问医生的问题"));
        // 只读工具查过了，写操作一个都还没发生。
        assertThat(turn.toolTraces()).extracting(AgentTurnResponse.ToolTrace::toolName)
                .contains("appointment.querySlots", "schedule.checkConflict", "travel.plan", "family.queryContact")
                .doesNotContain("appointment.submit", "schedule.createReminder", "family.notify");

        AgentTurnResponse completed = service.confirm(id, true);
        assertThat(completed.stage()).isEqualTo("COMPLETED");
        assertThat(completed.result().appointmentId()).startsWith("AP-");
        assertThat(completed.toolTraces()).extracting(AgentTurnResponse.ToolTrace::toolName)
                .contains("appointment.submit", "schedule.createReminder", "family.notify");
    }

    /**
     * 旧单任务场景不能因为这次升级出现回归：不额外说约束、偏好、补充诉求时，
     * 计划里就不该凭空多出这些行。
     */
    @Test
    void plainSingleTaskFlowAddsNoExtraPlanLines() {
        String id = service.start().conversationId();
        service.chat(id, "市第一医院", false);
        service.chat(id, "心内科", false);
        // 用 9月17日：上面那个用例会在同一个内存库里真的占掉 9月18日 09:00 的号，
        // 两个用例撞同一天的话这里会走到日程冲突分支，测不到本用例真正想测的"计划里别多出多余行"。
        service.chat(id, "9月17日", false);
        service.chat(id, "上午", false);
        service.chat(id, "可以", false);
        // "要不要接受附近日期"这一步走按钮：文字回答"只要这一天"目前落不进槽位（见测试报告），
        // 而按钮路径就是前端真正在用的那条。
        service.act(id, "SET_ALTERNATIVE", "false");
        service.chat(id, "不需要陪同", false);
        service.chat(id, "不需要出行提醒", false);
        service.chat(id, "不用通知", false);
        AgentTurnResponse turn = service.act(id, "START_PLAN", "");

        assertThat(turn.stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(turn.plan().tasks())
                .noneMatch(item -> item.startsWith("按您的要求：")
                        || item.startsWith("尽量满足：")
                        || item.startsWith("另外记下：")
                        || item.startsWith("其他："));
    }
}
