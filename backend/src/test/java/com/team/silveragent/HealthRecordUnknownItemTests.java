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
 * 老人报了一个数、却没说清是哪一项（“我今天量了，是135”）：问一句“这是哪一项”，他点一下就记上。
 *
 * <p>为什么要有这一问：猜一个项目记下去是<b>假记录</b>——它会落到记录页，还会跟着“把这个月的血压
 * 发给女儿”出门，发到女儿手机上的是一个他从来没量到过的读数。而直接回“没听准”又让他把整句话
 * 重说一遍，可他要说的其实已经说完了，缺的只是“这是哪一项”这一个词。
 *
 * <p>所以守三件事：<b>不能猜</b>（点这一下之前库里一条都不能有）、<b>不能丢</b>（他说过的数得留着）、
 * 以及<b>归属由他自己定</b>（点的项目就是写进库的项目；答不上来时宁可再问一遍，也不替他挑一个）。
 *
 * <p>还有一条反向的：他手上那张健康记录卡还没点头时，这一问<b>不抢话</b>——“是135”那时更可能是
 * 对卡上那个数的回答，而不是新量的一次。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-record-unknown-item;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=false"})
class HealthRecordUnknownItemTests {

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

    private AgentTurnResponse start() {
        return service.start("user-001");
    }

    /** 问对了问题：这一问要带上他说的那个数，而不是一句干巴巴的“没听准”。 */
    @Test
    void aNumberWithoutAnItemIsAskedWhichItem() {
        AgentTurnResponse start = start();
        AgentTurnResponse ask = service.chat(start.conversationId(), "我今天量了，是135");

        assertThat(ask.reply()).contains("135", "哪一项");
        // 这一问什么都还没写：项目是猜的，落库就是一条假记录
        assertThat(rows()).isEmpty();
    }

    /** 给的是按钮，不是让他把项目名打出来：老人点一下的成本远低于重新组织一句话。 */
    @Test
    void theQuestionComesWithButtonsHeCanJustTap() {
        AgentTurnResponse start = start();
        AgentTurnResponse ask = service.chat(start.conversationId(), "我今天量了，是135");

        assertThat(ask.quickReplies()).extracting(AgentTurnResponse.QuickReply::action)
                .containsOnly("RECORD_ITEM");
        assertThat(ask.quickReplies()).extracting(AgentTurnResponse.QuickReply::label)
                .contains("血压", "血糖", "体温", "其他", "不用记");
    }

    /** 他点了“血压”：那个数按血压记下来，回读里念的也必须是血压 135。 */
    @Test
    void pickingAnItemRecordsTheNumberUnderIt() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "我今天量了，是135");

        AgentTurnResponse saved = service.act(start.conversationId(), "RECORD_ITEM", "血压", "血压");

        assertThat(saved.reply()).contains("已记下", "血压", "135");
        assertThat(rows()).containsExactly("血压|135");
    }

    /** 直接说出项目名（“血压”）和点按钮是同一条路：不是每个人都愿意点那排按钮。 */
    @Test
    void sayingTheItemOutLoudWorksAsWellAsTapping() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "我今天量了，是135");

        AgentTurnResponse saved = service.chat(start.conversationId(), "血压");

        assertThat(saved.reply()).contains("已记下", "血压", "135");
        assertThat(rows()).containsExactly("血压|135");
    }

    /** 他挑“其他”：那是他自己给的归属，照他说的原话存，数值留着。 */
    @Test
    void pickingOtherStoresHisOwnWords() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "我今天量了，是135");

        service.act(start.conversationId(), "RECORD_ITEM", "其他", "其他");

        assertThat(rows()).containsExactly("其他|我今天量了，是135");
    }

    /**
     * 点成配不上的组合（血压 800）照样先反问：他点的只是“这是哪一项”，
     * 不是“别问了直接写”——写一条 800 的血压进库，比多问一句糟得多。
     */
    @Test
    void aValueThatDoesNotFitTheItemIsAskedAboutBeforeWriting() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "我今天量了，是800");

        AgentTurnResponse asked = service.act(start.conversationId(), "RECORD_ITEM", "血压", "血压");

        assertThat(asked.reply()).contains("不太对", "800");
        assertThat(rows()).isEmpty();
    }

    /** “没量过”这种没有数的一句不问“这是哪一项”：没有数就没有要记的东西，问了也是难为他。 */
    @Test
    void aSentenceWithNoNumberIsNotAskedWhichItem() {
        AgentTurnResponse start = start();

        AgentTurnResponse reply = service.chat(start.conversationId(), "我今天量了");

        assertThat(reply.reply()).doesNotContain("哪一项");
        assertThat(rows()).isEmpty();
    }

    /** 咨询口气（“我尿酸高怎么办”）不是上报数值：这一问问的是记哪一项，不是给他看指标。 */
    @Test
    void anAdviceSentenceIsNotAskedWhichItem() {
        AgentTurnResponse start = start();

        AgentTurnResponse reply = service.chat(start.conversationId(), "我尿酸高怎么办");

        assertThat(reply.reply()).doesNotContain("哪一项");
        assertThat(rows()).isEmpty();
    }

    /** 他答了别的事：这一问作废，那句话照常办理，不把他卡在这个提问上。 */
    @Test
    void sayingSomethingElseDropsTheQuestion() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "我今天量了，是135");

        AgentTurnResponse reply = service.chat(start.conversationId(), "明天早上八点提醒我吃药");

        assertThat(reply.reply()).contains("提醒");
        assertThat(rows()).isEmpty();
    }

    /** 答得不清楚（“这个”）时再问一遍：草稿不能丢，丢了就得让他把整句话重说一次。 */
    @Test
    void anUnclearAnswerAsksAgainWithoutLosingTheNumber() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "我今天量了，是135");

        AgentTurnResponse again = service.chat(start.conversationId(), "这个");

        assertThat(again.reply()).contains("哪一项");
        AgentTurnResponse saved = service.act(start.conversationId(), "RECORD_ITEM", "血压", "血压");
        assertThat(saved.reply()).contains("已记下");
        assertThat(rows()).containsExactly("血压|135");
    }

    /** 点“不用记”：他报了数又决定不记，得说清楚没记，而不是让它悬在那儿。 */
    @Test
    void pickingNotToRecordDropsItAndSaysSo() {
        AgentTurnResponse start = start();
        service.chat(start.conversationId(), "我今天量了，是135");

        AgentTurnResponse dropped = service.act(start.conversationId(), "RECORD_ITEM", "不用记", "不用记");

        assertThat(dropped.reply()).contains("不记");
        assertThat(rows()).isEmpty();
    }

    /**
     * 手上那张卡还悬着时不抢话：那一刻的“是135”更可能是对卡上那个数的回答，
     * 此时再问“这是哪一项”恰好问错——卡上已经写着是哪一项了。
     */
    @Test
    void aWaitingCardIsNotInterruptedByThisQuestion() {
        AgentTurnResponse start = start();
        AgentTurnResponse card = service.chat(start.conversationId(), "我的体温是36.5");
        assertThat(card.confirmation()).as(card.reply()).isNotNull();

        AgentTurnResponse reply = service.chat(start.conversationId(), "我今天量了，是135");

        assertThat(reply.reply()).doesNotContain("哪一项");
        // 那张卡还是那张卡：点下去写进库的仍旧只有体温那一条，135 一个字都没进去
        service.confirm(start.conversationId(), true, card.confirmation().confirmationId());
        assertThat(rows()).containsExactly("体温|36.5");
    }
}
