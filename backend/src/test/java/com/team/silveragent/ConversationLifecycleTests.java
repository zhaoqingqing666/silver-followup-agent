package com.team.silveragent;

import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.model.ConversationSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话结束之后必须真的是只读。
 *
 * <p>这一组里最要紧的是 {@link #closedConversationCannotBeConfirmed()}：
 * 「结束会话」要是挡不住确认，那就只是一个显示用的标签，已经发出去的确认卡
 * 仍然能把预约写进库——门禁就白设了。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-lifecycle-test;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=false",
        // 演示与测试都把空闲阈值调小，否则要等十分钟才能验到超时那条分支。
        "agent.conversation.idle-timeout-seconds=1",
})
class ConversationLifecycleTests {

    @Autowired FollowupAgentService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        for (String table : List.of("appointments", "reminders", "family_notifications")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE appointment_slots SET available=TRUE");
    }

    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    AgentTurnResponse action(String id, String action, String value) {
        return service.act(id, action, value, action);
    }

    /** 走到「等待确认」——这一步之后库里应该还什么都没有。 */
    AgentTurnResponse prepare(String id) {
        action(id, "SET_HOSPITAL", "h001");
        action(id, "SET_DEPARTMENT", "d001");
        action(id, "SET_DATE", "2026-09-18");
        action(id, "SELECT_SLOT", "slot-0918-0900");
        action(id, "SET_ALTERNATIVE", "true");
        action(id, "SET_COMPANION", "false");
        action(id, "SET_TRAVEL", "false");
        action(id, "SET_TRANSPORT", "打车");
        action(id, "SET_NOTIFY", "false");
        AgentTurnResponse turn = action(id, "START_PLAN", "");
        assertThat(turn.stage()).as(turn.reply()).isEqualTo("AWAITING_CONFIRMATION");
        return turn;
    }

    @Test
    void closedConversationCannotBeConfirmed() {
        String id = service.start().conversationId();
        AgentTurnResponse pending = prepare(id);
        assertThat(count("appointments")).isZero();
        String confirmationId = pending.confirmation().confirmationId();

        service.closeConversation(id);

        AgentTurnResponse rejected = service.confirm(id, true, confirmationId);
        assertThat(rejected.reply()).contains("已经结束");
        // 这条是重点：被拒之后库里必须还是没有预约，否则「结束」就只是句话。
        assertThat(count("appointments")).isZero();
        assertThat(count("reminders")).isZero();
        assertThat(count("family_notifications")).isZero();
    }

    @Test
    void closedConversationRejectsNewTurnsAndActions() {
        String id = service.start().conversationId();
        service.closeConversation(id);

        assertThat(service.chat(id, "我想预约复诊").reply()).contains("已经结束");
        assertThat(action(id, "SET_HOSPITAL", "h001").reply()).contains("已经结束");
        // 再关一次不该报错：前端「新对话」可能重试。
        service.closeConversation(id);
        assertThat(count("appointments")).isZero();
    }

    /** 结束只影响「能不能接着办」，不影响翻看：历史就是留给人看的。 */
    @Test
    void closedConversationCanStillBeRead() {
        String id = service.start().conversationId();
        service.chat(id, "你好");
        service.closeConversation(id);

        var history = service.resume(id);
        assertThat(history.conversationId()).isEqualTo(id);
        assertThat(history.messages()).isNotEmpty();
    }

    @Test
    void historyListsNewestFirstWithTitleFromFirstUserMessage() {
        String first = service.start().conversationId();
        service.chat(first, "我想问一下复诊要带什么材料");
        String second = service.start().conversationId();
        service.chat(second, "帮我看看下周的号");

        List<ConversationSummary> list = service.conversations("user-001", 10);
        assertThat(list).hasSizeGreaterThanOrEqualTo(2);
        assertThat(list.get(0).conversationId()).isEqualTo(second);
        assertThat(list.get(1).conversationId()).isEqualTo(first);
        assertThat(list.get(0).title()).contains("下周的号");
        assertThat(list.get(0).messageCount()).isGreaterThan(0);
    }

    /** 结束过的会话在历史里要能一眼看出来，否则「已结束」这个状态就没有意义。 */
    @Test
    void closedConversationIsMarkedClosedInHistory() {
        String id = service.start().conversationId();
        service.chat(id, "随便聊聊");
        service.closeConversation(id);

        ConversationSummary row = service.conversations("user-001", 10).stream()
                .filter(item -> item.conversationId().equals(id)).findFirst().orElseThrow();
        assertThat(row.status()).isEqualTo("CLOSED");
    }

    /** 太久没说话：历史里记成已结束，但人回来说话就该接着办，不能让他重说一遍。 */
    @Test
    void idleConversationIsMarkedExpiredButResumesOnNextMessage() throws Exception {
        String id = service.start().conversationId();
        service.chat(id, "随便聊聊");
        Thread.sleep(1200);

        ConversationSummary row = service.conversations("user-001", 10).stream()
                .filter(item -> item.conversationId().equals(id)).findFirst().orElseThrow();
        assertThat(row.status()).isEqualTo("EXPIRED");

        // 回来接着说话：状态恢复，正常得到回复，绝不是「已经结束」那一套。
        AgentTurnResponse turn = service.chat(id, "我刚才说到哪儿了");
        assertThat(turn.reply()).doesNotContain("已经结束");
        assertThat(service.conversations("user-001", 10).stream()
                .filter(item -> item.conversationId().equals(id)).findFirst().orElseThrow().status())
                .isEqualTo("ACTIVE");
    }

    /**
     * 有事情正做到一半的会话，不因为「放了一会儿」就被标成已结束。
     * 否则老人回来一看「已结束」，那张还等着他按的确认卡就再也不敢按了。
     */
    @Test
    void pendingConfirmationSurvivesIdleTimeout() throws Exception {
        String id = service.start().conversationId();
        AgentTurnResponse pending = prepare(id);
        Thread.sleep(1200);

        ConversationSummary row = service.conversations("user-001", 10).stream()
                .filter(item -> item.conversationId().equals(id)).findFirst().orElseThrow();
        assertThat(row.status()).isEqualTo("ACTIVE");

        // 而且这时候确认仍然应该能办成——这才是「没被误判结束」的实际意义。
        AgentTurnResponse done = service.confirm(id, true, pending.confirmation().confirmationId());
        assertThat(done.stage()).isEqualTo("COMPLETED");
        assertThat(count("appointments")).isEqualTo(1);
    }
}
