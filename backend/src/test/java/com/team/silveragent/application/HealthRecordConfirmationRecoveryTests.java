package com.team.silveragent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.silveragent.domain.model.AgentTurnResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 健康记录确认卡的会话恢复——走<b>真实的库</b>（存进 state_json，清掉内存缓存，再读回来）。
 *
 * <p>与 {@link MemoConfirmationRecoveryTests} 同构，钉的是同一件事：这张卡的授权三样只说
 * "写一条健康数值"，而<b>记哪一项、记成什么数、什么时候量的</b>都在草稿字段里。那些字段是
 * 签发那一刻定下的，所以必须和凭据一起进快照、一起回来。
 *
 * <ol>
 *   <li><b>重启后写进去的就是卡片上那一条。</b>项目、数值、记录时间逐字不变——尤其时间：
 *       它不是"点确认的那一刻"，是卡上写着的、他真正量到的那一刻。</li>
 *   <li><b>旧快照缺草稿时整份作废。</b>更早版本写下的快照里没有这几个字段，读回来是一条
 *       没有项目/没有时间的数值。那种凭据当场失效、请他重新说一遍，绝不写一条残缺的。</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-record-recovery;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=false"})
class HealthRecordConfirmationRecoveryTests {
    private static final String SAYS = "我的血压是138";

    @Autowired FollowupAgentService service;
    @Autowired ConversationStore conversations;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM health_records");
        // 会话不能跨用例串：内存里那份缓存清掉，让下一轮从库里读。
        sessions().clear();
    }

    /** 签发 → 落库 → 重启 → 确认：写进去的项目、数值、记录时间，和卡片上展示的那一份相同。 */
    @Test
    void aHealthCardWritesTheSameValueAndTimeAfterASessionReload() {
        String conversationId = service.start("user-001").conversationId();
        AgentTurnResponse card = service.chat(conversationId, SAYS);
        assertThat(card.stage()).as(card.reply()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(card.confirmation().title()).contains("健康数值");
        String confirmationId = card.confirmation().confirmationId();

        // 卡片上写的那一份（= 签发时冻住的那一份），先从库里读回来对一遍。
        ConversationState issued = conversations.find(conversationId).orElseThrow();
        assertThat(issued.confirmationKind)
                .isEqualTo(ConfirmationService.PendingOperation.Kind.HEALTH_RECORD.name());
        assertThat(issued.pendingRecordItem).isEqualTo("血压");
        assertThat(issued.pendingRecordValueText).isEqualTo("138");
        LocalDateTime shownAt = issued.pendingRecordAt;
        assertThat(shownAt).as("卡片上写了记录时间，快照里就不能是空的").isNotNull();
        // 卡上那一行的时间原文（“记录时间：9月14日 13:21”），下面拿它和库里读回来的那条对
        String stampOnCard = card.confirmation().operations().get(1).substring("记录时间：".length());

        // 重启：服务内存里那份没了，这一轮只能从库里读。
        sessions().clear();
        AgentTurnResponse done = service.confirm(conversationId, true, confirmationId);

        assertThat(done.reply()).as(done.reply()).contains("已记下", "血压", "138");
        assertThat(recordCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT item FROM health_records", String.class)).isEqualTo("血压");
        assertThat(jdbc.queryForObject("SELECT value_text FROM health_records", String.class)).isEqualTo("138");
        // 回读里那个时间是从库里那条记录读出来的（{@code created.recordedAt()}），
        // 它和卡上原样写着的那一行相同 —— 记录时间取的是他报数那一刻，不是点确认那一刻。
        // 逐字比的是显示形态而不是纳秒：库里存的精度到微秒，回读也只念到分钟。
        assertThat(done.reply()).as("卡上写着哪一刻，库里那条记录就是哪一刻")
                .contains("已记下：" + stampOnCard);
        assertThat(java.time.Duration.between(shownAt, storedRecordedAt()).abs())
                .as("卡上那一刻与库里那一刻是同一刻（差异只来自数据库的微秒精度）")
                .isLessThan(java.time.Duration.ofSeconds(1));
    }

    /**
     * 旧快照：只有凭据、没有数值草稿。
     *
     * <p>拿一张真卡改出来——把这次才进快照的字段删掉，等价于上一个版本写下的那一行。
     * 那种快照读回来只有一个"要记数值"的空壳；照着它执行，写进库的要么是一条没有项目的数，
     * 要么时间只能拿确认那一刻顶替——两条路都是<b>写进去一条他从来没在卡上看到过的记录</b>。
     * 所以这里要求整份作废。
     */
    @Test
    void anOldSnapshotWithoutTheValueDraftIsDroppedInsteadOfWrittenPartly() {
        String conversationId = service.start("user-001").conversationId();
        AgentTurnResponse card = service.chat(conversationId, SAYS);
        String confirmationId = card.confirmation().confirmationId();

        stripTheRecordDraft(conversationId);

        sessions().clear();
        AgentTurnResponse refused = service.confirm(conversationId, true, confirmationId);

        // 与备忘卡一样，失效发生在<b>门口</b>（校验凭据那一步），不是写库那一刻才发现没有数值。
        assertThat(refused.reply()).as(refused.reply()).contains("这份确认已经失效或已办理");
        assertThat(recordCount()).isZero();
        assertThat(refused.stage()).isNotEqualTo("AWAITING_CONFIRMATION");
        assertThat(conversations.find(conversationId).orElseThrow().confirmationId).isNull();
    }

    // —— 工具方法 ——

    /** 把库里的快照改成"上一个版本写下的样子"：删掉数值草稿那几个字段。 */
    private void stripTheRecordDraft(String conversationId) {
        String stateJson = jdbc.queryForObject(
                "SELECT state_json FROM conversation_sessions WHERE id=?", String.class, conversationId);
        try {
            ObjectNode state = (ObjectNode) json.readTree(stateJson);
            for (String field : List.of("pendingRecordItem", "pendingRecordValueNum", "pendingRecordValueText",
                    "pendingRecordUnit", "pendingRecordRaw", "pendingRecordAt",
                    "recordReturnAction", "recordReturnStage")) {
                state.remove(field);
            }
            jdbc.update("UPDATE conversation_sessions SET state_json=? WHERE id=?",
                    json.writeValueAsString(state), conversationId);
        } catch (Exception error) {
            throw new IllegalStateException("构造旧快照失败", error);
        }
        // 造完之后先自查一遍：库里那一行确实只剩一个空壳，否则下面的断言是假的。
        ConversationState legacy = conversations.find(conversationId).orElseThrow();
        assertThat(legacy.confirmationId).isNotBlank();
        assertThat(legacy.confirmationKind)
                .isEqualTo(ConfirmationService.PendingOperation.Kind.HEALTH_RECORD.name());
        assertThat(legacy.pendingRecordItem).isNull();
        assertThat(legacy.pendingRecordAt).isNull();
    }

    @SuppressWarnings("unchecked")
    private Map<String, ConversationState> sessions() {
        return (Map<String, ConversationState>) ReflectionTestUtils.getField(service, "sessions");
    }

    private int recordCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM health_records", Integer.class);
    }

    private LocalDateTime storedRecordedAt() {
        return jdbc.queryForObject("SELECT recorded_at FROM health_records",
                (rs, row) -> rs.getTimestamp(1).toLocalDateTime());
    }

    /** 单位也要一起回来（血压的 mmHg 不是可有可无的装饰，缺了这条记录就没法看）。 */
    @Test
    void theUnitAlsoComesBackFromTheSnapshot() {
        String conversationId = service.start("user-001").conversationId();
        AgentTurnResponse card = service.chat(conversationId, "我的血糖是7.2");
        assertThat(card.confirmation().operations().get(0)).contains("血糖", "7.2", "mmol/L");

        sessions().clear();
        service.confirm(conversationId, true, card.confirmation().confirmationId());

        assertThat(jdbc.queryForObject("SELECT unit FROM health_records", String.class)).isEqualTo("mmol/L");
        assertThat(jdbc.queryForObject("SELECT value_num FROM health_records", BigDecimal.class))
                .isEqualByComparingTo("7.2");
    }
}
