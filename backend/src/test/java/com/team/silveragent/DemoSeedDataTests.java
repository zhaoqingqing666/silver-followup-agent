package com.team.silveragent;

import com.team.silveragent.agent.AgentContext;
import com.team.silveragent.agent.ExtractedFacts;
import com.team.silveragent.agent.RuleFactExtractor;
import com.team.silveragent.application.time.BusinessClock;
import com.team.silveragent.domain.model.ToolModels.Conflict;
import com.team.silveragent.domain.tool.ScheduleTool;
import com.team.silveragent.support.DemoSeed;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
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
        "agent.model.enabled=false"})
class DemoSeedDataTests {

    @Autowired ScheduleTool scheduleTool;
    @Autowired RuleFactExtractor ruleFactExtractor;
    @Autowired JdbcTemplate jdbc;
    /**
     * 「今天」一律走业务时钟。{@code LocalDate.now()} 取的是 JVM 默认时区（容器是 UTC），
     * 播种和口语解析用的却是业务时区（Asia/Shanghai）——北京时间 00:00–08:00 这八个小时里
     * 两者差一天，跨过周一那道坎时「下周三」会整整差一周，这条用例就会在半夜自己变红。
     */
    @Autowired BusinessClock clock;

    @Test
    void spokenNextWednesdayLandsOnTheSeededCheckupDay() {
        ExtractedFacts facts = ruleFactExtractor.extract("我下周三想去市第一医院心内科复诊，上午方便",
                new AgentContext("WAITING_DATE", "", clock.today(), List.of()));

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
            // 「未来」的基准同样用业务时钟，不用 SQL 的 CURRENT_DATE：后者取的是数据库连接的
            // JVM 默认时区（UTC），和号源生成用的业务时区不是同一个钟面。
            Integer days = jdbc.queryForObject("""
                    SELECT COUNT(DISTINCT appointment_date) FROM appointment_slots
                    WHERE department=? AND available=TRUE AND appointment_date > ?
                    """, Integer.class, department, Date.valueOf(clock.today()));
            assertThat(days).as("科室 %s 未来还有几天可约", department).isGreaterThanOrEqualTo(3);
        }
    }

    private int countAvailable(String slotId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM appointment_slots WHERE id=? AND available=TRUE", Integer.class, slotId);
        return count == null ? 0 : count;
    }
}
