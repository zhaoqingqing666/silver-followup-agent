package com.team.silveragent;

import com.team.silveragent.application.DemoScenario;
import com.team.silveragent.application.DemoScenarioService;
import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.model.DemoScenarioResponse;
import com.team.silveragent.support.DemoSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 演示场景重置。
 *
 * <p>这个接口是**破坏性**的，评审和录屏都会直接调它，所以两件事必须钉死：
 * ① 清得干净——本分支自己加的表（健康记录、备忘、长期记忆、识图附件、照护通知）一张都不能漏；
 * ② 清得有序——重置后仍然是一个能直接开始说话的干净会话，旧会话 id 不能再动。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-test;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=false"})
@AutoConfigureMockMvc
class DemoScenarioTests {

    /** 步骤里的日期写法（M月d日）与后端一致：它换写法，这一条就该跟着红。 */
    private static final DateTimeFormatter STEP_DATE = DateTimeFormatter.ofPattern("M月d日");

    @Autowired DemoScenarioService scenarios;
    @Autowired FollowupAgentService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    /** 每个用例都从不带数据的场景一开始：这些用例断言的是「清空后剩什么」，起点必须干净。 */
    @BeforeEach void startFromAKnownState() {
        scenarios.reset("normal");
    }

    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    private boolean slotAvailable(String slotId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT available FROM appointment_slots WHERE id=?", Boolean.class, slotId));
    }

    /** 走一遍真实办理并过确认门禁：这一串是「上一场演示留下的数据」里最完整的一份。 */
    private AgentTurnResponse bookAnAppointment(String slotId) {
        String id = service.start().conversationId();
        action(id, "SET_HOSPITAL", "h001");
        action(id, "SET_DEPARTMENT", "d001");
        action(id, "SET_DATE", DemoSeed.day(DemoSeed.checkupDay()));
        action(id, "SELECT_SLOT", slotId);
        action(id, "SET_ALTERNATIVE", "true");
        action(id, "SET_COMPANION", "true");
        action(id, "SET_TRAVEL", "true");
        action(id, "SET_TRANSPORT", "打车");
        action(id, "SET_NOTIFY", "true");
        action(id, "SET_CONTACT", "family-001");
        AgentTurnResponse turn = action(id, "START_PLAN", "");
        assertThat(turn.stage()).as(turn.reply()).isEqualTo("AWAITING_CONFIRMATION");
        return service.confirm(turn.conversationId(), true, turn.confirmation().confirmationId());
    }

    private AgentTurnResponse action(String id, String action, String value) {
        return service.act(id, action, value, action);
    }

    @Test void resetClearsEveryMutableTableAndLeavesCatalogDataAlone() {
        bookAnAppointment(DemoSeed.morningSlot());
        // 本分支自己加的表：老师的重置清单里没有这几张，最容易漏，这里直接插行验证。
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO memos(id,user_id,text,status,created_at) VALUES (?,?,?,?,?)",
                "m-demo", "user-001", "明早八点提醒我吃药", "ACTIVE", now);
        jdbc.update("INSERT INTO health_records(id,user_id,item,value_num,value_text,unit,recorded_at)"
                + " VALUES (?,?,?,?,?,?,?)", "h-demo", "user-001", "血压", 138, "138/86", "mmHg", now);
        jdbc.update("INSERT INTO care_notifications(id,caregiver_id,elder_user_id,content,kind,created_at)"
                + " VALUES (?,?,?,?,?,?)", "n-demo", "care-001", "user-001", "已为您约好复诊", "BOOKING", now);
        jdbc.update("INSERT INTO conversation_attachments(conversation_id,kind,data_url,created_at)"
                + " VALUES (?,?,?,?)", "c-demo", "USER_IMAGE", "data:image/jpeg;base64,AAAA", now);
        jdbc.update("INSERT INTO vision_results(conversation_id,description,created_at) VALUES (?,?,?)",
                "c-demo", "一张药品说明书", now);

        assertThat(count("appointments")).as("办理确实落库了，后面的断言才有意义").isEqualTo(1);
        assertThat(count("tool_call_logs")).isPositive();
        assertThat(count("user_memories")).as("确认门禁放行后顺手记下的习惯").isPositive();
        assertThat(count("memos")).isEqualTo(1);
        int hospitals = count("hospitals");

        scenarios.reset("conflict");

        for (String table : List.of("appointments", "reminders", "family_notifications", "tool_call_logs",
                "memos", "health_records", "care_notifications",
                "conversation_attachments", "vision_results", "user_memories")) {
            assertThat(count(table)).as(table).isZero();
        }
        // 只剩重置时新建的这一段会话：开场白已经落库，旧的那些一条不剩。
        assertThat(count("conversation_sessions")).isEqualTo(1);
        assertThat(count("conversation_messages")).isPositive();
        // 目录数据（医院、科室、材料模板…）是演示的地基，重置只清演示过程产生的东西。
        assertThat(count("hospitals")).isEqualTo(hospitals);
        assertThat(count("departments")).isPositive();
    }

    @Test void resetFreesTheBookedSlotSoTheSameScenarioCanBeReplayed() {
        String slot = DemoSeed.morningSlot();
        bookAnAppointment(slot);
        assertThat(slotAvailable(slot)).as("约掉之后号源应当被占住").isFalse();

        scenarios.reset("normal");

        assertThat(slotAvailable(slot)).as("重置后同一个号源要能再约一次").isTrue();
    }

    @Test void theOldConversationStopsWorkingAfterAReset() {
        String stale = bookAnAppointment(DemoSeed.morningSlot()).conversationId();

        DemoScenarioResponse reset = scenarios.reset("normal");

        // 内存里那份会话状态没清的话，这里会命中内存、继续在一个库记录已经不存在的草稿上办事。
        assertThatThrownBy(() -> service.chat(stale, "你好"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("会话不存在");
        assertThat(service.resume(reset.turn().conversationId()).conversationId())
                .isEqualTo(reset.turn().conversationId());
    }

    @Test void theStepsCarryThisRunSRollingDates() {
        String checkup = DemoSeed.checkupDay().format(STEP_DATE);
        String weekend = DemoSeed.emptyDay().format(STEP_DATE);

        DemoScenarioResponse conflict = scenarios.reset("conflict");
        DemoScenarioResponse noSlot = scenarios.reset("no-slot");

        // 步骤里的日期每次现算：写成固定日期的演示文稿过一周就会和界面对不上，而这正是重置要解决的问题。
        assertThat(String.join("\n", conflict.steps())).contains(checkup, "社区体检", "仍保留这个时间", "已知冲突");
        assertThat(String.join("\n", noSlot.steps())).contains(weekend, "接受其他日期");
        assertThat(DemoScenario.byId("  NORMAL ")).isEqualTo(DemoScenario.NORMAL);
    }

    @Test void scenarioEndpointReturnsTheStepsAndRejectsUnknownIds() throws Exception {
        mvc.perform(post("/api/demo/scenarios/no-slot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenarioId").value("no-slot"))
                .andExpect(jsonPath("$.title").value("场景二 指定日期无号源"))
                .andExpect(jsonPath("$.steps").isNotEmpty())
                .andExpect(jsonPath("$.availableScenarios", hasSize(4)))
                .andExpect(jsonPath("$.turn.conversationId").isNotEmpty());

        mvc.perform(post("/api/demo/scenarios/not-a-scenario"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("可选值")));
    }
}
