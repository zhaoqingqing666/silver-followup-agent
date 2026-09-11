package com.team.silveragent;

import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 老人上报实测数值（“我的血压是100”）：落到 health_records 表、回读给老人核对、之后能回查；
 * 与“要做的事”的备忘分家，互相不抢句子。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-record;DB_CLOSE_DELAY=-1",
        "agent.llm.enabled=false"})
class HealthRecordFlowTests {

    @Autowired FollowupAgentService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM health_records");
        jdbc.update("DELETE FROM memos");
    }

    private int recordCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM health_records", Integer.class);
    }

    private int memoCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM memos WHERE status='ACTIVE'", Integer.class);
    }

    private AgentTurnResponse start() {
        return service.start("user-001");
    }

    @Test
    void bloodPressureIsRecordedAndReadBack() {
        AgentTurnResponse start = start();
        AgentTurnResponse saved = service.chat(start.conversationId(), "我的血压是100");

        // 回读记下的项目和数值，老人才能发现听错了数
        assertThat(saved.reply()).contains("已记下", "血压", "100", "mmHg");
        assertThat(recordCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT item FROM health_records", String.class)).isEqualTo("血压");
        assertThat(jdbc.queryForObject("SELECT value_text FROM health_records", String.class)).isEqualTo("100");
        // 实测数值不是“要做的事”，不能顺手写一条备忘
        assertThat(memoCount()).isZero();
    }

    @Test
    void storedValueCanBeQueriedAfterwards() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "我的血压是100");

        AgentTurnResponse reply = service.chat(start.conversationId(), "我最近的血压是多少");

        assertThat(reply.reply()).contains("最近的血压记录", "100", "mmHg");
    }

    @Test
    void queryWithNothingStoredTeachesHowToReport() {
        AgentTurnResponse start = start();
        AgentTurnResponse reply = service.chat(start.conversationId(), "我最近的血压是多少");

        assertThat(reply.reply()).contains("还没有血压的记录", "我的血压是100");
        assertThat(recordCount()).isZero();
    }

    @Test
    void queryWithoutNamingAnItemListsEverything() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "我的血压是130/85");
        service.chat(start.conversationId(), "血糖7.2");

        AgentTurnResponse reply = service.chat(start.conversationId(), "我最近都量了什么");

        // 没点项目时每行要带项目名，否则老人看到两个数不知道哪个是哪个
        assertThat(reply.reply()).contains("130/85", "血糖", "7.2", "mmol/L");
        assertThat(recordCount()).isEqualTo(2);
    }

    @Test
    void reminderSentencesDoNotBecomeHealthRecords() {
        AgentTurnResponse start = start();

        // 这两句都带“量血压/吃药”，但都是“要做的事”，只能进备忘、不能进健康记录
        service.chat(start.conversationId(), "明天早上八点提醒我吃药");
        service.chat(start.conversationId(), "每周三下午三点提醒我量血压");

        assertThat(recordCount()).isZero();
        assertThat(memoCount()).isEqualTo(2);
    }

    @Test
    void askingAboutAValueDoesNotCreateARecord() {
        AgentTurnResponse start = start();
        AgentTurnResponse reply = service.chat(start.conversationId(), "我的血压怎么样");

        assertThat(reply.reply()).contains("还没有血压的记录");
        assertThat(recordCount()).isZero();
    }

    @Test
    void absurdValueIsQuestionedBeforeItIsStored() {
        AgentTurnResponse start = start();
        AgentTurnResponse reply = service.chat(start.conversationId(), "我的血压是800");

        // 回归：原来 800 被静默丢掉，这句话顺着链路变成“请问您想去哪家医院”
        assertThat(reply.reply()).contains("800", "不太对");
        assertThat(reply.reply()).doesNotContain("医院");
        assertThat(reply.quickReplies()).extracting(AgentTurnResponse.QuickReply::action)
                .containsExactly("RECORD_RETRY", "RECORD_KEEP");
        assertThat(recordCount()).isZero();
    }

    @Test
    void elderInsistingOnTheAbsurdValueGetsItRecorded() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "我的血压是800");

        AgentTurnResponse confirmed = service.act(start.conversationId(), "RECORD_KEEP", "", "就按这个记下来");

        // 老人的数据他做主：说照记就照记，但仍然回读一遍让他看见记的是什么
        assertThat(confirmed.reply()).contains("已记下", "800");
        assertThat(recordCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT value_text FROM health_records", String.class)).isEqualTo("800");
    }

    @Test
    void reReportingANumberReplacesTheQuestionedValue() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "我的血压是800");

        // 老人被反问后直接回一个数，不用再说一遍“我的血压是”
        AgentTurnResponse reply = service.chat(start.conversationId(), "150");

        assertThat(reply.reply()).contains("已记下", "150");
        assertThat(recordCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT value_text FROM health_records", String.class)).isEqualTo("150");
    }

    @Test
    void retryButtonKeepsTheItemSoABareNumberStillLands() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "我的体温是60度");

        AgentTurnResponse retry = service.act(start.conversationId(), "RECORD_RETRY", "", "重新说一个");
        assertThat(retry.reply()).contains("重新量");

        // 点了“重新说一个”之后说“36.8”，还得知道这是体温
        AgentTurnResponse saved = service.chat(start.conversationId(), "36.8");
        assertThat(saved.reply()).contains("已记下", "体温");
        assertThat(jdbc.queryForObject("SELECT item FROM health_records", String.class)).isEqualTo("体温");
    }

    @Test
    void changingTheSubjectDropsTheQuestionedValue() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "我的血压是800");

        // 老人改说了别的事：那条离谱数值作废，绝不偷偷记进去，也要接着办新事
        AgentTurnResponse reply = service.chat(start.conversationId(), "我想预约复诊");

        assertThat(reply.reply()).contains("医院");
        assertThat(recordCount()).isZero();
    }

    @Test
    void swappedBloodPressurePairIsQuestioned() {
        AgentTurnResponse start = start();
        AgentTurnResponse reply = service.chat(start.conversationId(), "我的血压是60/120");

        assertThat(reply.reply()).contains("说反了");
        assertThat(recordCount()).isZero();

        AgentTurnResponse confirmed = service.chat(start.conversationId(), "就按这个记下来");
        assertThat(confirmed.reply()).contains("已记下", "60/120");
        assertThat(recordCount()).isEqualTo(1);
    }

    @Test
    void ordinaryValuesAreNotQuestioned() {
        AgentTurnResponse start = start();

        // 高得离谱但人真能量得出来：直接记，不打扰老人
        AgentTurnResponse reply = service.chat(start.conversationId(), "我的血压是200");

        assertThat(reply.reply()).contains("已记下");
        assertThat(recordCount()).isEqualTo(1);
    }
}
