package com.team.silveragent;

import com.team.silveragent.agent.AgentContext;
import com.team.silveragent.agent.ExtractedFacts;
import com.team.silveragent.agent.RuleFactExtractor;
import com.team.silveragent.domain.model.ToolModels.Conflict;
import com.team.silveragent.domain.tool.ScheduleTool;
import com.team.silveragent.support.DemoSeed;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 演示种子数据的“保质期”回归。
 *
 * 演示数据原来写死在某几天，日期一过，冲突场景就再也造不出来，而用例要等到那天之后才开始变红。
 * 这几条断言把「演示当天必须成立的前提」提前钉住：口语里的「下周三」就是体检那天、
 * 那天上午的号源必然撞车、下午的不撞、周末没有号源、每个启用科室都有号可约。
 * 只要哪一条不再成立，这里先失败，而不是等到录屏当天才发现。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-demo-seed;DB_CLOSE_DELAY=-1",
        "agent.llm.enabled=false"})
class DemoSeedDataTests {

    @Autowired ScheduleTool scheduleTool;
    @Autowired RuleFactExtractor ruleFactExtractor;
    @Autowired JdbcTemplate jdbc;

    @Test
    void spokenNextWednesdayLandsOnTheSeededCheckupDay() {
        ExtractedFacts facts = ruleFactExtractor.extract("我下周三想去市第一医院心内科复诊，上午方便",
                new AgentContext("WAITING_DATE", "", LocalDate.now(), List.of()));

        assertThat(facts.date())
                .as("演示话术里的「下周三」必须落在体检日程那天，否则冲突场景演不出来")
                .isEqualTo(DemoSeed.checkupDay());
    }

    @Test
    void morningSlotOnTheCheckupDayConflictsWithTheSeededSchedule() {
        LocalDateTime start = LocalDateTime.of(DemoSeed.checkupDay(), LocalTime.of(10, 30));

        assertThat(scheduleTool.findConflicts("demo-seed", "user-001", start, start.plusMinutes(60)))
                .extracting(Conflict::title)
                .contains("社区体检");
    }

    @Test
    void afternoonSlotsOnTheCheckupDayDoNotConflict() {
        for (LocalTime time : List.of(LocalTime.of(14, 0), LocalTime.of(15, 30))) {
            LocalDateTime start = LocalDateTime.of(DemoSeed.checkupDay(), time);
            assertThat(scheduleTool.findConflicts("demo-seed", "user-001", start, start.plusMinutes(60)))
                    .as("%s 的号源不该被误判成冲突", time)
                    .isEmpty();
        }
    }

    @Test
    void theSlotsUsedByTheDemoAreGenerated() {
        assertThat(countAvailable(DemoSeed.plainSlot())).as("普通办理用的号源").isEqualTo(1);
        assertThat(countAvailable(DemoSeed.conflictingSlot())).as("冲突场景用的号源").isEqualTo(1);
    }

    @Test
    void weekendHasNoSlotsSoTheNoSlotSceneStaysReproducible() {
        Integer slots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM appointment_slots WHERE appointment_date=? AND available=TRUE",
                Integer.class, DemoSeed.emptyDay());

        assertThat(slots).as("周六必须没有号源").isZero();
    }

    @Test
    void everyEnabledDepartmentStillHasBookableSlots() {
        List<String> departments = jdbc.query("""
                SELECT DISTINCT d.name FROM departments d
                JOIN hospitals h ON h.id = d.hospital_id
                WHERE d.enabled = TRUE AND h.enabled = TRUE
                """, (rs, row) -> rs.getString(1));

        assertThat(departments).isNotEmpty();
        for (String department : departments) {
            Integer days = jdbc.queryForObject("""
                    SELECT COUNT(DISTINCT appointment_date) FROM appointment_slots
                    WHERE department=? AND available=TRUE AND appointment_date > CURRENT_DATE
                    """, Integer.class, department);
            assertThat(days).as("科室 %s 未来还有几天可约", department).isGreaterThanOrEqualTo(3);
        }
    }

    private int countAvailable(String slotId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM appointment_slots WHERE id=? AND available=TRUE", Integer.class, slotId);
        return count == null ? 0 : count;
    }
}
