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
 * 老人说了一句关于血压的话、却没说数（“我血压有点高”）：先问一句“量出来是多少”。
 *
 * <p>为什么不直接记：一条“血压 有点高”里没有一个数，回看时帮不上任何忙。为什么不丢掉：
 * 他确实说了这么一句，静默扔掉跟骗他记下了是一回事。所以问一句——<b>答得上就落一个实数，
 * 答不上来（“没量过”“记不清”）就按他说的那句记，不追第二遍</b>。
 *
 * <p>守的是这两头：不能静默丢，也不能追着要一个他没有的数。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-record-spoken;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=false"})
class HealthRecordSpokenOnlyTests {

    @Autowired FollowupAgentService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM health_records");
        jdbc.update("DELETE FROM memos");
    }

    private List<String> rows() {
        return jdbc.queryForList("SELECT item, value_text, value_num FROM health_records").stream()
                .map(row -> row.get("ITEM") + "|" + row.get("VALUE_TEXT") + "|" + num(row.get("VALUE_NUM")))
                .toList();
    }

    /** value_num 是 DECIMAL(8,2)，直接打印是 135.00；这里还原成 135，让断言读起来就是“有没有数”。 */
    private static String num(Object value) {
        return value == null ? "null" : new java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
    }

    private AgentTurnResponse start() {
        return service.start("user-001");
    }

    @Test
    void aSpokenWordWithoutANumberIsAskedAbout() {
        AgentTurnResponse start = start();
        AgentTurnResponse ask = service.chat(start.conversationId(), "我血压有点高");

        assertThat(ask.reply()).contains("血压", "有点高", "量出来是多少");
        assertThat(ask.quickReplies()).extracting(AgentTurnResponse.QuickReply::action)
                .containsExactly("RECORD_VALUE", "RECORD_VALUE");
        assertThat(rows()).isEmpty();
    }

    /** 答得上就落一个实数：这一问的全部意义就是这个数。 */
    @Test
    void answeringWithANumberRecordsIt() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "我血压有点高");

        AgentTurnResponse saved = service.chat(start.conversationId(), "135");

        assertThat(saved.reply()).contains("已记下", "血压", "135");
        assertThat(rows()).containsExactly("血压|135|135");
    }

    /** 答不上来就按他说的那句记：追第二遍是难为他，他要是量过，第一遍就说出来了。 */
    @Test
    void sayingHeNeverMeasuredStoresHisWordsWithoutANumber() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "我血压有点高");

        AgentTurnResponse saved = service.act(start.conversationId(), "RECORD_VALUE", "没量过", "没量过");

        // 回读里不拖一个空格、更不印出一个 null
        assertThat(saved.reply()).contains("已记下", "血压", "有点高");
        assertThat(saved.reply()).doesNotContain("null");
        assertThat(rows()).containsExactly("血压|有点高|null");
    }

    /** 没有数的那条不挂单位：挂上 mmHg 就等于说他量过了。 */
    @Test
    void aSpokenOnlyRecordCarriesNoUnit() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "我血压有点高");
        service.chat(start.conversationId(), "记不清");

        assertThat(jdbc.queryForObject("SELECT unit FROM health_records", String.class)).isBlank();
    }

    /** 这一句里还报了别的项时：这一问答完，其余几条照旧上卡。 */
    @Test
    void theOtherValueInTheSameSentenceStillGetsRecorded() {
        AgentTurnResponse start = start();
        AgentTurnResponse ask = service.chat(start.conversationId(), "我血压有点高，体温36.5");
        assertThat(ask.reply()).contains("量出来是多少");

        AgentTurnResponse card = service.chat(start.conversationId(), "没量过");
        assertThat(card.confirmation()).as(card.reply()).isNotNull();
        String listed = String.join(" ", card.confirmation().operations());
        assertThat(listed).contains("有点高", "36.5", "体温");

        service.confirm(start.conversationId(), true, card.confirmation().confirmationId());

        assertThat(rows()).containsExactlyInAnyOrder("血压|有点高|null", "体温|36.5|36.5");
    }

    /** 回查时也要念得顺口：没有单位的那条不能多印一个空格，更不能印出 null。 */
    @Test
    void readingItBackDoesNotPrintAnEmptyUnit() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "我血压有点高");
        service.chat(start.conversationId(), "没量过");

        AgentTurnResponse query = service.chat(start.conversationId(), "我最近血压多少");

        assertThat(query.reply()).contains("有点高");
        assertThat(query.reply()).doesNotContain("null", "  ", "有点高 ");
    }

    /** 他改说了别的事：这一问作废，不硬把话头拽回来，也不偷偷记一条。 */
    @Test
    void changingTheSubjectDropsTheQuestion() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "我血压有点高");

        AgentTurnResponse reply = service.chat(start.conversationId(), "明天早上八点提醒我吃药");

        assertThat(reply.reply()).contains("提醒");
        assertThat(rows()).isEmpty();
    }
}
