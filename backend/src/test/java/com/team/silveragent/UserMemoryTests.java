package com.team.silveragent;

import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.application.memory.MemoryStore;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.support.DemoSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 长期记忆：记住的是「实际办成的事」，不是老人某一轮随口说过的想法。
 *
 * <p>所以这一组测试的重心在两处：草稿阶段一个字都不许记；办成之后的覆盖要真的覆盖，
 * 而不是把换过的医院和旧医院一起记着——两版偏好同时进提示词，模型只会更糊涂。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-memory-test;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=false",
})
class UserMemoryTests {

    /** 演示种子：心内科「下周三」上午、内分泌科次日 14:00，两笔都真实存在且互不冲突。 */
    private static final String DAY = DemoSeed.day(DemoSeed.checkupDay());
    private static final String MORNING_SLOT = DemoSeed.morningSlot();
    private static final String LATER_DAY = DemoSeed.day(DemoSeed.laterDay());
    private static final String ENDOCRINOLOGY_SLOT = DemoSeed.endocrinologySlot();

    @Autowired FollowupAgentService service;
    @Autowired MemoryStore memories;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        for (String table : List.of("user_memories", "appointments", "reminders", "family_notifications")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE appointment_slots SET available=TRUE");
    }

    AgentTurnResponse action(String id, String action, String value) {
        return service.act(id, action, value, action);
    }

    /** 走到「等待确认」。这一步之后库里、以及记忆里都应该还什么都没有。 */
    AgentTurnResponse prepare(String id, String hospitalId, String departmentId, String date, String slotId) {
        action(id, "SET_HOSPITAL", hospitalId);
        action(id, "SET_DEPARTMENT", departmentId);
        action(id, "SET_DATE", date);
        action(id, "SELECT_SLOT", slotId);
        action(id, "SET_ALTERNATIVE", "true");
        action(id, "SET_COMPANION", "false");
        action(id, "SET_TRAVEL", "false");
        action(id, "SET_TRANSPORT", "打车");
        action(id, "SET_NOTIFY", "false");
        AgentTurnResponse turn = action(id, "START_PLAN", "");
        assertThat(turn.stage()).as(turn.reply()).isEqualTo("AWAITING_CONFIRMATION");
        return turn;
    }

    void approve(AgentTurnResponse turn) {
        AgentTurnResponse done = service.confirm(turn.conversationId(), true, turn.confirmation().confirmationId());
        assertThat(done.stage()).as(done.reply()).isEqualTo("COMPLETED");
    }

    private List<String> contents() {
        return service.memories("user-001").stream().map(MemoryStore.Memory::content).toList();
    }

    @Test
    void draftBookingRemembersNothing() {
        String id = service.start().conversationId();
        prepare(id, "h001", "d001", DAY, MORNING_SLOT);
        // 还没确认：预约没有落库，也就不该有任何记忆。
        assertThat(service.memories("user-001")).isEmpty();
        assertThat(memories.digest("user-001")).isEmpty();
    }

    @Test
    void confirmedBookingRemembersHospitalDepartmentAndPeriod() {
        String id = service.start().conversationId();
        approve(prepare(id, "h001", "d001", DAY, MORNING_SLOT));

        assertThat(contents()).containsExactlyInAnyOrder(
                "常去的医院是市第一医院（模拟）",
                "常去的科室是心内科",
                "习惯上午复诊");
        // 明天开一段新对话，助手应该已经认得他——这是这一个功能存在的全部意义。
        assertThat(memories.digest("user-001")).contains("常去的医院是市第一医院（模拟）");
    }

    @Test
    void afternoonSlotIsRememberedAsAfternoon() {
        String id = service.start().conversationId();
        approve(prepare(id, "h002", "d003", LATER_DAY, ENDOCRINOLOGY_SLOT));
        assertThat(contents()).contains("习惯下午复诊");
    }

    @Test
    void aLaterBookingOverwritesTheEarlierPreference() {
        approve(prepare(service.start().conversationId(), "h001", "d001", DAY, MORNING_SLOT));
        approve(prepare(service.start().conversationId(), "h002", "d003", LATER_DAY, ENDOCRINOLOGY_SLOT));

        // 换了医院就要覆盖：两版偏好都留着，下一轮提示词里就会同时出现两家医院。
        assertThat(contents()).hasSize(3);
        assertThat(contents()).containsExactlyInAnyOrder(
                "常去的医院是市人民医院（模拟）",
                "常去的科室是内分泌科",
                "习惯下午复诊");
    }

    @Test
    void forgettingOneEntryLeavesTheOthers() {
        approve(prepare(service.start().conversationId(), "h001", "d001", DAY, MORNING_SLOT));

        assertThat(service.forgetMemory("user-001", "habit.hospital")).isTrue();
        assertThat(contents()).containsExactlyInAnyOrder("常去的科室是心内科", "习惯上午复诊");
        // 忘掉的东西不能再进提示词。
        assertThat(memories.digest("user-001")).doesNotContain("市第一医院");
        // 再忘一次：已经没有了，如实返回 false，不报错。
        assertThat(service.forgetMemory("user-001", "habit.hospital")).isFalse();
    }

    /** 取消预约不该抹掉记忆：他确实常去那家医院，只是这次不去了。 */
    @Test
    void cancellingTheAppointmentKeepsWhatWasLearned() {
        String id = service.start().conversationId();
        approve(prepare(id, "h001", "d001", DAY, MORNING_SLOT));

        AgentTurnResponse cancelCard = service.act(id, "CANCEL_APPOINTMENT", "", "取消预约");
        assertThat(cancelCard.confirmation()).isNotNull();
        service.confirm(id, true, cancelCard.confirmation().confirmationId());

        assertThat(contents()).contains("常去的医院是市第一医院（模拟）");
    }

    /** 忘记这条路径只走人自己按的按钮：模型与前端都够不着它。 */
    @Test
    void memoriesAreScopedToOneUser() {
        approve(prepare(service.start().conversationId(), "h001", "d001", DAY, MORNING_SLOT));
        assertThat(service.memories("user-002")).isEmpty();
        assertThat(memories.digest("user-002")).isEmpty();
    }
}
