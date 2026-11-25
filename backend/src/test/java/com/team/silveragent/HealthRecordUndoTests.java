package com.team.silveragent;

import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 老人说“记错了”：删掉最近那一条，并把删掉的那条念给他听。
 *
 * <p>为什么这条要有：他报了一个数、点了头，回头发现数听错了——除了“删掉重记”没有别的出路。
 * 而一条他自己知道是错的数留在库里，会被“把这个月的血压发给女儿”算进平均里，
 * 发到女儿手机上的是一个他从来没量到过的读数。
 *
 * <p>守两件事：<b>删对了哪一条</b>（说的“刚才那条”＝时间上最近的那一条），
 * 以及<b>删完得说清楚删的是哪条</b>——不念出来，他以为删掉的是 A、实际没的是 B，而 A 还在。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-record-undo;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=false"})
class HealthRecordUndoTests {

    @Autowired FollowupAgentService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM health_records");
        jdbc.update("DELETE FROM memos");
    }

    private List<String> rows() {
        return jdbc.queryForList("SELECT item, value_text FROM health_records").stream()
                .map(row -> row.get("ITEM") + "|" + row.get("VALUE_TEXT"))
                .toList();
    }

    /** 报一个数并点头确认：现在每条实测数值都要过一道确认卡才落库。 */
    private void report(String conversationId, String sentence) {
        AgentTurnResponse ask = service.chat(conversationId, sentence);
        assertThat(ask.confirmation()).as(ask.reply()).isNotNull();
        service.confirm(conversationId, true, ask.confirmation().confirmationId());
    }

    @Test
    void sayingItWasWrongDeletesTheMostRecentOneAndReadsItBack() {
        AgentTurnResponse start = start();
        report(start.conversationId(), "我的血压是100");
        report(start.conversationId(), "我的体温是36.5");

        AgentTurnResponse undone = service.chat(start.conversationId(), "记错了");

        // 删的是最近那条（体温），而且要说出来删的是哪条——不说，他以为删掉的是血压
        assertThat(undone.reply()).contains("体温", "36.5");
        assertThat(rows()).containsExactly("血压|100");
    }

    /** 连着说两次“记错了”删掉两条：他要是想撤的不止一条，不该被卡住。 */
    @Test
    void sayingItTwiceDeletesTwo() {
        AgentTurnResponse start = start();
        report(start.conversationId(), "我的血压是100");
        report(start.conversationId(), "我的体温是36.5");

        service.chat(start.conversationId(), "记错了");
        AgentTurnResponse second = service.chat(start.conversationId(), "记错了");

        assertThat(second.reply()).contains("血压", "100");
        assertThat(rows()).isEmpty();
    }

    /** 一条都没记过时不能编一句“好的已删除”：那是他说了话、库里什么都没发生。 */
    @Test
    void nothingToUndoIsSaidOutLoud() {
        AgentTurnResponse start = start();

        AgentTurnResponse undone = service.chat(start.conversationId(), "记错了");

        assertThat(undone.reply()).contains("没有可以删的");
        assertThat(undone.reply()).doesNotContain("已经把");
    }

    /** 撤销只删自己名下的：陈阿婆说“记错了”，不能把李爷爷那条删掉。 */
    @Test
    void undoOnlyTouchesTheEldersOwnRecords() {
        AgentTurnResponse other = service.start("user-002");
        report(other.conversationId(), "我的血压是138");

        AgentTurnResponse mine = start();
        AgentTurnResponse undone = service.chat(mine.conversationId(), "记错了");

        assertThat(undone.reply()).contains("没有可以删的");
        assertThat(rows()).containsExactly("血压|138");
    }

    /** 带“备忘/提醒”的删除是另一件事：那句话不能把健康记录删了。 */
    @Test
    void deletingAReminderDoesNotDeleteARecord() {
        AgentTurnResponse start = start();
        report(start.conversationId(), "我的血压是100");
        service.chat(start.conversationId(), "每天下午三点提醒我量血压");

        service.chat(start.conversationId(), "把量血压的提醒删掉");

        assertThat(rows()).containsExactly("血压|100");
    }

    private AgentTurnResponse start() {
        return service.start("user-001");
    }
}
