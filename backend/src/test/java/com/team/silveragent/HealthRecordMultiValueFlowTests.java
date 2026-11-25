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
 * 老人一句话报了两项：<b>两条都记，一次点头</b>。
 *
 * <p>这一层守的是解析器之外剩下的那半截：解析器认得出两条（见 {@code HealthRecordMultiValueTests}），
 * 但会话层原来是只取第一条的——认出来两条、只写进去一条，老人看见的还是“好的已记下”，
 * 回看时第二条根本不在。所以这里从老人的话一路查到库里的行。
 *
 * <p>两条路各一个例子：
 * <ul>
 *   <li>两条都不带疑问（“我的体温是36.5，心率80”）→ 一张卡列两行，点一次“确认记下”两条一起写；</li>
 *   <li>其中一条还带着疑问（“我的身高是180 体重是190”，190 没说单位）→ 先把那一问问清楚，
 *       答完了剩下的照旧一张卡一次点头。卡上写着什么，写进库的就必须是什么。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-record-multi;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=false"})
class HealthRecordMultiValueFlowTests {

    @Autowired FollowupAgentService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM health_records");
        jdbc.update("DELETE FROM memos");
    }

    /** 库里那几行，一行一个“项目|数值|单位”。列名走 H2 的大写，所以拼成字符串再比。 */
    private List<String> rows() {
        return jdbc.queryForList("SELECT item, value_text, unit FROM health_records ORDER BY recorded_at, item")
                .stream()
                .map(row -> row.get("ITEM") + "|" + row.get("VALUE_TEXT") + "|" + row.get("UNIT"))
                .toList();
    }

    private AgentTurnResponse start() {
        return service.start("user-001");
    }

    /** 一句话报两项，两项都不用再问：一张卡列两行，点一次头两条一起落库。 */
    @Test
    void twoValuesInOneSentenceShareOneCardAndBothLand() {
        AgentTurnResponse start = start();
        AgentTurnResponse ask = service.chat(start.conversationId(), "我的体温是36.5，心率80");

        // 卡上两行都在：他只看到一行就会以为另一项没被听见
        assertThat(ask.reply()).as(ask.reply()).isNotNull();
        String card = String.join(" ", ask.confirmation().operations());
        assertThat(card).contains("36.5", "80", "体温", "心率");
        assertThat(ask.confirmation().title()).contains("这两条");
        assertThat(rows()).isEmpty();

        AgentTurnResponse confirmed = service.confirm(start.conversationId(), true,
                ask.confirmation().confirmationId());

        // 回读要把两条都念出来：只说“都记下了”，他看不出哪一个听错了
        assertThat(confirmed.reply()).contains("36.5", "80");
        assertThat(rows()).containsExactlyInAnyOrder("体温|36.5|°C", "心率|80|次每分");
    }

    /** 一条带着疑问时：先把那一问问清楚，答完剩下的照旧一张卡一次点头。 */
    @Test
    void aValueWithoutAUnitIsAskedBeforeTheCard() {
        AgentTurnResponse start = start();
        AgentTurnResponse ask = service.chat(start.conversationId(), "我的身高是180 体重是190");

        // 190 斤和 190 公斤都说得通，替他挑一个就是替他改数据：先问，两个按钮各一个答案
        assertThat(ask.reply()).contains("190", "斤还是公斤");
        assertThat(ask.quickReplies()).extracting(AgentTurnResponse.QuickReply::action)
                .containsExactly("RECORD_UNIT", "RECORD_UNIT");
        assertThat(rows()).isEmpty();

        // 答了单位之后，手上这条和等着的身高一起上卡
        AgentTurnResponse card = service.act(start.conversationId(), "RECORD_UNIT", "公斤", "190 公斤");
        String listed = String.join(" ", card.confirmation().operations());
        assertThat(listed).contains("180", "190", "身高", "体重");

        service.confirm(start.conversationId(), true, card.confirmation().confirmationId());

        assertThat(rows()).containsExactlyInAnyOrder("身高|180|cm", "体重|190|kg");
    }

    /** 同上一句，但老人说的是“斤”：库里就得是斤，不能替他折成公斤。 */
    @Test
    void theUnitHeSaidIsTheUnitThatIsStored() {
        AgentTurnResponse start = start();
        AgentTurnResponse ask = service.chat(start.conversationId(), "我的身高是180 体重是190");

        AgentTurnResponse card = service.act(start.conversationId(), "RECORD_UNIT", "斤", "190 斤");
        service.confirm(start.conversationId(), true, card.confirmation().confirmationId());

        assertThat(rows()).containsExactlyInAnyOrder("身高|180|cm", "体重|190|斤");
    }

    /** 一句话只有一条时，还是原来那张卡、原来那条路：别为了多值把单值也改了。 */
    @Test
    void oneValueStillGetsTheSameSingleCard() {
        AgentTurnResponse start = start();
        AgentTurnResponse ask = service.chat(start.conversationId(), "我的血压是100");

        assertThat(ask.confirmation().title()).contains("这条");
        assertThat(String.join(" ", ask.confirmation().operations())).contains("血压", "100", "mmHg");

        service.confirm(start.conversationId(), true, ask.confirmation().confirmationId());
        assertThat(rows()).containsExactly("血压|100|mmHg");
    }
}
