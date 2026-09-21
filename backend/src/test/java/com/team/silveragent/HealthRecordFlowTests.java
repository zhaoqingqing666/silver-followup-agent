package com.team.silveragent;

import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.application.health.HealthRecordStore;
import com.team.silveragent.domain.model.AgentTurnResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 老人上报实测数值（“我的血压是100”）：落到 health_records 表、回读给老人核对、之后能回查；
 * 与“要做的事”的备忘分家，互相不抢句子。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-record;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=false"})
class HealthRecordFlowTests {

    @Autowired FollowupAgentService service;
    @Autowired HealthRecordStore records;
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

    /** 报一个数并点头确认：现在每条实测数值都要过一道确认卡才落库。 */
    private AgentTurnResponse report(String conversationId, String sentence) {
        AgentTurnResponse ask = service.chat(conversationId, sentence);
        assertThat(ask.confirmation()).as(ask.reply()).isNotNull();
        return service.confirm(conversationId, true, ask.confirmation().confirmationId());
    }

    /** 只报一个数，停在确认卡上（不确认）。 */
    private AgentTurnResponse reportAndWait(String conversationId, String sentence) {
        return service.chat(conversationId, sentence);
    }

    /** 平静报一个数：先出确认卡，点了头才落库。 */
    @Test
    void measuredValueIsConfirmedOnACardBeforeItIsStored() {
        AgentTurnResponse start = start();
        AgentTurnResponse ask = reportAndWait(start.conversationId(), "我的血压是100");

        // 卡上要把“记哪一项、记成什么数、什么时候量的”一起复述清楚，他才能发现听错了数
        assertThat(ask.stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(ask.confirmation().title()).contains("记下");
        assertThat(String.join(" ", ask.confirmation().operations())).contains("血压", "100", "mmHg");
        assertThat(recordCount()).isZero();

        AgentTurnResponse saved = service.confirm(start.conversationId(), true,
                ask.confirmation().confirmationId());

        // 回读记下的项目和数值，老人才能发现听错了数
        assertThat(saved.reply()).contains("已记下", "血压", "100", "mmHg");
        assertThat(recordCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT item FROM health_records", String.class)).isEqualTo("血压");
        assertThat(jdbc.queryForObject("SELECT value_text FROM health_records", String.class)).isEqualTo("100");
        // 实测数值不是“要做的事”，不能顺手写一条备忘
        assertThat(memoCount()).isZero();
    }

    @Test
    void decliningTheCardStoresNothing() {
        AgentTurnResponse start = start();
        AgentTurnResponse ask = reportAndWait(start.conversationId(), "我的血压是100");

        AgentTurnResponse declined = service.confirm(start.conversationId(), false,
                ask.confirmation().confirmationId());

        assertThat(declined.reply()).contains("没有记下");
        assertThat(recordCount()).isZero();
        assertThat(memoCount()).isZero();
    }

    /**
     * 卡还悬着时他又报了一条新数：旧卡撤下，只留新的一条。
     *
     * <p>两个数不能各占一张卡等他挑（同一会话手上只有一张），也不能把新报的那条丢在一边
     * ——那正是“他说了、库里却没有”的那类错。
     */
    @Test
    void reReportingANewValueReplacesThePendingCard() {
        AgentTurnResponse start = start();
        AgentTurnResponse first = reportAndWait(start.conversationId(), "我的血压是138");

        AgentTurnResponse second = reportAndWait(start.conversationId(), "血糖6.4");

        // 撤下的是哪一条要说出来，否则他以为两个数都记了
        assertThat(second.reply()).contains("血压", "138", "还没确认");
        assertThat(second.stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(second.confirmation().confirmationId())
                .isNotEqualTo(first.confirmation().confirmationId());
        assertThat(String.join(" ", second.confirmation().operations())).contains("血糖", "6.4", "mmol/L");
        assertThat(recordCount()).isZero();   // 换卡这一步一个写操作都没有

        AgentTurnResponse saved = service.confirm(start.conversationId(), true,
                second.confirmation().confirmationId());

        assertThat(saved.reply()).contains("已记下", "血糖", "6.4");
        assertThat(recordCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT item FROM health_records", String.class)).isEqualTo("血糖");
    }

    /** 被换掉的那张卡点不动了：凭据当场作废，写进去的只能是手上最后那张卡上的那一条。 */
    @Test
    void theReplacedCardCannotBeClicked() {
        AgentTurnResponse start = start();
        AgentTurnResponse first = reportAndWait(start.conversationId(), "我的血压是138");
        reportAndWait(start.conversationId(), "血糖6.4");

        AgentTurnResponse stale = service.confirm(start.conversationId(), true,
                first.confirmation().confirmationId());

        assertThat(stale.reply()).contains("失效");
        assertThat(recordCount()).isZero();
    }

    /** 新卡点了「先不用」：被撤下的那条和新的一条都不落库，也不留半条。 */
    @Test
    void decliningTheReplacementCardStoresNeitherValue() {
        AgentTurnResponse start = start();
        reportAndWait(start.conversationId(), "我的血压是138");
        AgentTurnResponse second = reportAndWait(start.conversationId(), "血糖6.4");

        AgentTurnResponse declined = service.confirm(start.conversationId(), false,
                second.confirmation().confirmationId());

        assertThat(declined.reply()).contains("没有记下");
        assertThat(recordCount()).isZero();
    }

    /**
     * 卡还悬着时说的不是一个能记的数（“90”）：那是卡片的回答，不是新量的一次测量。
     *
     * <p>“90”解析不出项目（{@code HealthRecordParser} 要求句子里有项目词），所以它换不掉手上那张卡；
     * 卡原样留着，他还能按原来那个按钮。
     */
    @Test
    void aBareNumberDoesNotReplaceThePendingCard() {
        AgentTurnResponse start = start();
        AgentTurnResponse first = reportAndWait(start.conversationId(), "我的血压是138");

        AgentTurnResponse reply = service.chat(start.conversationId(), "90");

        assertThat(reply.stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(reply.confirmation().confirmationId())
                .isEqualTo(first.confirmation().confirmationId());
        assertThat(String.join(" ", reply.confirmation().operations())).contains("血压", "138");
        assertThat(recordCount()).isZero();
    }

    /**
     * 量不出来的那条（“血压800”）也不换卡：它要先被反问一句，而反问那条路不换卡，
     * 放它进来就会出现“屏幕上写着 138、点下去写的是 800”。
     */
    @Test
    void aValueNeedingASecondLookDoesNotReplaceThePendingCard() {
        AgentTurnResponse start = start();
        AgentTurnResponse first = reportAndWait(start.conversationId(), "我的血压是138");

        AgentTurnResponse reply = service.chat(start.conversationId(), "我的血压是800");

        assertThat(reply.stage()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(reply.confirmation().confirmationId())
                .isEqualTo(first.confirmation().confirmationId());
        assertThat(recordCount()).isZero();
    }

    /** 换卡换掉的只是那张卡：老人正办的事（复诊办到哪一步）要照旧接上。 */
    @Test
    void replacingTheCardKeepsTheBookingHeWasInTheMiddleOf() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "我想预约复诊");      // 停在“请问您想去哪家医院”
        reportAndWait(start.conversationId(), "我的血压是138");
        AgentTurnResponse second = reportAndWait(start.conversationId(), "血糖6.4");

        AgentTurnResponse saved = service.confirm(start.conversationId(), true,
                second.confirmation().confirmationId());

        assertThat(saved.reply()).contains("已记下", "血糖", "我们继续办理复诊", "医院");
    }

    /** 同一张卡点两次只落一条：凭据消费一次就作废。 */
    @Test
    void clickingTheSameCardTwiceWritesOnlyOneRecord() {
        AgentTurnResponse start = start();
        AgentTurnResponse ask = reportAndWait(start.conversationId(), "我的血压是100");
        String confirmationId = ask.confirmation().confirmationId();

        service.confirm(start.conversationId(), true, confirmationId);
        AgentTurnResponse again = service.confirm(start.conversationId(), true, confirmationId);

        assertThat(again.reply()).contains("失效");
        assertThat(recordCount()).isEqualTo(1);
    }

    /** 记录时间取“他报数的那一刻”，不是“他点确认的那一刻”——卡上写着哪一刻，库里就是哪一刻。 */
    @Test
    void recordedTimeIsTheMomentHeReportedIt() {
        AgentTurnResponse start = start();
        AgentTurnResponse ask = reportAndWait(start.conversationId(), "我的血压是100");
        String onCard = ask.confirmation().operations().get(1);
        assertThat(onCard).startsWith("记录时间：");
        String stamped = onCard.substring("记录时间：".length());

        AgentTurnResponse saved = service.confirm(start.conversationId(), true,
                ask.confirmation().confirmationId());

        // 回读里那个时间就是库里那条记录的时间，也就是卡上写着的那个
        assertThat(saved.reply()).contains("已记下：" + stamped);
    }

    @Test
    void storedValueCanBeQueriedAfterwards() {
        AgentTurnResponse start = start();
        report(start.conversationId(), "我的血压是100");

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
        report(start.conversationId(), "我的血压是130/85");
        report(start.conversationId(), "血糖7.2");

        AgentTurnResponse reply = service.chat(start.conversationId(), "我最近都量了什么");

        // 没点项目时每行要带项目名，否则老人看到两个数不知道哪个是哪个
        assertThat(reply.reply()).contains("130/85", "血糖", "7.2", "mmol/L");
        assertThat(recordCount()).isEqualTo(2);
    }

    @Test
    void recordsCanBeFilteredByItemForTheRecordPage() {
        // 记录页顶部那排项目按钮：列表、条数、翻页三处都得跟着筛。
        // 只筛列表不筛条数，“共N条”和“后面还有没有更早的”就都是错的
        AgentTurnResponse start = start();
        report(start.conversationId(), "我的血压是130/85");
        report(start.conversationId(), "血糖7.2");
        report(start.conversationId(), "我的血压是128/82");

        assertThat(records.count("user-001", null)).isEqualTo(3);
        assertThat(records.count("user-001", "血压")).isEqualTo(2);
        assertThat(records.count("user-001", "血糖")).isEqualTo(1);

        assertThat(records.recent("user-001", "血糖", 50, 0))
                .extracting(HealthRecordStore.RecordView::valueText).containsExactly("7.2");

        // 一页一条地翻：筛过的两页正好是两条血压，第三页就空了——
        // 筛选要是漏到翻页上，这里会翻出别的项目或者多翻出一页
        List<String> paged = new ArrayList<>();
        for (int offset = 0; offset < 3; offset++) {
            records.recent("user-001", "血压", 1, offset).forEach(row -> {
                assertThat(row.item()).isEqualTo("血压");
                paged.add(row.valueText());
            });
        }
        assertThat(paged).containsExactlyInAnyOrder("130/85", "128/82");
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

        // 高得离谱但人真能量得出来：不反问“这个数不太对”，只过一次确认卡
        AgentTurnResponse reply = report(start.conversationId(), "我的血压是200");

        assertThat(reply.reply()).contains("已记下");
        assertThat(recordCount()).isEqualTo(1);
    }
}
