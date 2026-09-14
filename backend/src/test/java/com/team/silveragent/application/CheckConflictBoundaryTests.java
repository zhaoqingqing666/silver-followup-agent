package com.team.silveragent.application;

import com.team.silveragent.agent.model.ModelGateway;
import com.team.silveragent.agent.model.ModelRequest;
import com.team.silveragent.application.time.BusinessClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「现在」这一刻的号源到底还能不能选：三个口径必须同时说同一个答案。
 *
 * <p>号源查询的口径是 {@code appointment_time > 当前时间}，确认闸门用的是
 * {@code !时间.isAfter(now)}（{@code slotAlreadyPassed}）。日程比较原来写的是 {@code isBefore}，
 * 于是整整一个小时里，老人问「九点冲突吗」会被告知「不冲突、可以选」，而点下去确认时又说
 * 「已经过去了」——同一个整点，两个答案。这里把它钉在同一个口径上。
 *
 * <p><b>为什么必须钉死时钟</b>：边界只在「正好等于此刻」的那一瞬间才成立，跟着真实时间跑
 * 一秒都复现不了。时钟固定在北京时间 2026-09-16（周三）09:00 整，用例拿 09:00 和 09:30
 * 两个时刻去试，一个是边界本身，一个是同一小时里晚一点的普通时刻。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-conflict-boundary;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=true"})
class CheckConflictBoundaryTests {

    /** 固定瞬间：北京时间 2026-09-16 09:00 整。 */
    private static final Instant FIXED = Instant.parse("2026-09-16T01:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 16);

    @Autowired FollowupAgentService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired BoundaryModelGateway gateway;

    @BeforeEach void reset() {
        gateway.planning = "";
        gateway.lastToolPhasePrompt = "";
        jdbc.update("DELETE FROM appointments");
        jdbc.update("UPDATE appointment_slots SET available=TRUE");
        jdbc.update("DELETE FROM tool_call_logs WHERE tool_name='schedule.checkConflict'");
    }

    /** 正好等于此刻的整点算已过去：不拿它去查日程，更不能说它「不冲突」。 */
    @Test void theExactCurrentInstantCountsAsAlreadyPassed() {
        String id = service.start().conversationId();
        gateway.planning = conflictPlan("09:00");

        service.chat(id, "九点这个时间和我日程冲突吗？");

        assertThat(gateway.lastToolPhasePrompt).as("等于此刻也算过去了")
                .contains("已经过去了").contains("请告诉我另一个时间");
        assertThat(gateway.lastToolPhasePrompt).as("不能反过来说这个时间没冲突")
                .doesNotContain("与您已有的日程不冲突");
        assertThat(traceCount(id)).as("根本没有去查日程").isZero();
    }

    /** 同一小时里晚一点的时间照常比较：边界只卡在「正好等于此刻」这一点上。 */
    @Test void aMomentLaterInTheSameHourIsStillCompared() {
        String id = service.start().conversationId();
        gateway.planning = conflictPlan("09:30");

        service.chat(id, "九点半这个时间和我日程冲突吗？");

        assertThat(gateway.lastToolPhasePrompt).as("还没到的时间不该被当成过去")
                .doesNotContain("已经过去了");
        assertThat(traceCount(id)).as("真的去查了日程").isEqualTo(1);
    }

    private String conflictPlan(String time) {
        return """
                {"actionType":"CALL_READ_TOOL","intent":"REQUEST_RECOMMENDATION","toolName":"schedule.checkConflict",
                 "arguments":{"date":"%s","time":"%s"},"replyDraft":null,"dialogueMode":"FOLLOWUP_FLOW","facts":{}}
                """.formatted(TODAY, time);
    }

    private int traceCount(String conversationId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tool_call_logs WHERE conversation_id=? AND tool_name='schedule.checkConflict'
                """, Integer.class, conversationId);
        return count == null ? 0 : count;
    }

    /** 只回两件事：规划阶段说用例编好的那句，其余（工具证据轮、回答轮）一律回固定回答。 */
    static class BoundaryModelGateway implements ModelGateway {
        private static final String ANSWER = """
                {"actionType":"ANSWER","intent":"UNKNOWN","toolName":null,"arguments":{},
                 "replyDraft":"我按您说的查过了。","dialogueMode":"FOLLOWUP_FLOW","facts":{}}
                """;

        volatile String planning = "";
        volatile String lastToolPhasePrompt = "";

        @Override public String complete(ModelRequest request) {
            String latest = request.messages().isEmpty() ? ""
                    : request.messages().get(request.messages().size() - 1).content();
            if (latest.contains("同一用户轮次内刚刚执行完成的真实只读工具结果")) {
                lastToolPhasePrompt = latest;
                return ANSWER;
            }
            if (latest.contains("权威回复草稿")) return ANSWER;
            return planning;
        }

        @Override public boolean available() { return true; }
        @Override public String providerName() { return "test"; }
        @Override public String modelName() { return "test"; }
    }

    /** 把业务时钟换成固定时钟：整点边界只能对着一个真正的整点验。 */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean @Primary
        BoundaryModelGateway boundaryModelGateway() {
            return new BoundaryModelGateway();
        }

        @Bean @Primary
        BusinessClock businessClock() {
            return new BusinessClock(Clock.fixed(FIXED, BusinessClock.DEFAULT_ZONE));
        }
    }
}
