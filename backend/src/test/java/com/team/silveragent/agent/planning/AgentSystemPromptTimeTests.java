package com.team.silveragent.agent.planning;

import com.team.silveragent.agent.AgentContext;
import com.team.silveragent.application.time.BusinessClock;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 每轮提示词都要把真实的日期、星期、时刻、时区交给模型。
 *
 * <p>模型负责理解「明天」「下周三」说的是哪一天，所以它必须知道今天到底是几号、星期几；
 * 不给这些，跨月跨年只能靠猜。这里用固定瞬间把这段渲染钉死。
 */
class AgentSystemPromptTimeTests {

    /** 北京时间 2026-09-16（周三）00:30；同一瞬间 UTC 还停在 09-15。 */
    private static final Instant INSTANT = Instant.parse("2026-09-15T16:30:00Z");

    private final AgentSystemPrompt prompt = new AgentSystemPrompt(
            new BusinessClock(Clock.fixed(INSTANT, BusinessClock.DEFAULT_ZONE)));

    @Test
    void promptCarriesTheBusinessDateWeekdayTimeAndZone() {
        String text = prompt.planning(context(LocalDate.of(2026, 9, 16)), "[]");

        assertThat(text).contains(
                "当前日期：2026年9月16日（星期三），ISO 写法 2026-09-16",
                "现在时间：00:30",
                "时区：Asia/Shanghai（中国时间）");
    }

    /** 相对日期的算法归模型，但「算出来已经过去时不许自己往后推年份」这条必须写死在提示词里。 */
    @Test
    void theRelativeDateRuleForbidsSilentlyAdvancingTheYear() {
        String text = prompt.planning(context(LocalDate.of(2026, 9, 16)), "[]");

        assertThat(text).contains("一律以上面的当前日期和现在时间为基准自己推算",
                "不要自己把年份往后推", "请老人确认",
                "今天已经过去的时段不是可预约时间");
    }

    /** 不启动 Spring 时走兼容构造，同样按业务时区取「现在」，而不是服务器默认时区。 */
    @Test
    void theSpringFreeConstructorAlsoUsesTheBusinessZone() {
        String text = new AgentSystemPrompt().planning(context(BusinessClock.systemDefault().today()), "[]");

        assertThat(text).contains("时区：Asia/Shanghai（中国时间）");
    }

    private AgentContext context(LocalDate today) {
        return new AgentContext("ASK_DATE", "医院=市第一医院", today, List.of());
    }
}
