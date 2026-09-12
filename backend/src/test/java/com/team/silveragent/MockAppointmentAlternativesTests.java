package com.team.silveragent;

import com.team.silveragent.domain.model.ToolModels.Slot;
import com.team.silveragent.domain.tool.AppointmentTool;
import com.team.silveragent.support.DemoSeed;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「附近日期」候选的口径：只往后看，不把当天其它时段混进来。
 *
 * 上一句刚说完「这一天暂无号源」，下一句就列出当天别的时段，是自相矛盾的；
 * 当天之内换时段由「上午没有下午有」那条路负责，不走这个工具。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-test;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=false"})
class MockAppointmentAlternativesTests {

    @Autowired AppointmentTool appointments;

    @Test
    void alternativesAreStrictlyAfterTheRequestedDate() {
        // 体检那天自己有号（09:00 / 10:30 / 14:00 / 15:30），旧窗口会把它们当成“附近日期”
        List<Slot> slots = appointments.queryAlternatives("t-alternatives", "h001", "心内科", DemoSeed.checkupDay());

        assertThat(slots).as("下周三之后的周四、周五都有号").isNotEmpty();
        assertThat(slots).allMatch(slot -> slot.date().isAfter(DemoSeed.checkupDay()));
    }

    @Test
    void theEmptyWeekendStillHasRealAlternativesToOffer() {
        // 场景二：下周六刻意没有号，往后三天里周一、周二有号，老人得有得选
        List<Slot> slots = appointments.queryAlternatives("t-alternatives", "h001", "心内科", DemoSeed.emptyDay());

        assertThat(slots).isNotEmpty();
        assertThat(slots).allMatch(slot -> slot.date().isAfter(DemoSeed.emptyDay())
                && !slot.date().isAfter(DemoSeed.emptyDay().plusDays(3)));
    }
}
