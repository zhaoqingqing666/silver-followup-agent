package com.team.silveragent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.silveragent.application.memo.MemoParser;
import com.team.silveragent.domain.model.AgentTurnResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 备忘确认卡的会话恢复——走<b>真实的库</b>（存进 state_json，清掉内存缓存，再读回来）。
 *
 * <p>这张卡跟别的卡不一样：授权三样（凭据 / 类型 / 目标集合）说的是"记一条备忘"，
 * 可<b>记什么内容、提醒哪一刻</b>并不在其中，它们另有草稿字段。那些字段同样是签发那一刻定下的，
 * 所以必须和凭据一起进快照、一起回来。这里钉两条：
 *
 * <ol>
 *   <li><b>重启后写进去的就是卡片上那一条。</b>正文与原提醒时间逐字不变——不是重新解析一遍
 *       老人当时那句话（他这会儿可能又说了别的），更不是拿 {@code null} 凑一段。</li>
 *   <li><b>旧快照缺草稿时整份作废。</b>更早版本写下的快照里没有这几个字段，读回来是一段没有
 *       正文的备忘。那种凭据当场失效、请他重新说一遍，<b>绝不写一条残缺的</b>。</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-memo-recovery;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=false"})
class MemoConfirmationRecoveryTests {
    private static final String SAYS = "明天早上8点要去抽血，最好空腹";

    @Autowired FollowupAgentService service;
    @Autowired ConversationStore conversations;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM memos");
        // 会话不能跨用例串：内存里那份缓存清掉，让下一轮从库里读。
        sessions().clear();
    }

    /** 签发 → 落库 → 重启 → 确认：写进去的正文与提醒时间，和卡片上展示的那一份逐字相同。 */
    @Test
    void aMemoCardWritesTheSameTextAndReminderTimeAfterASessionReload() {
        String conversationId = service.start("user-001").conversationId();
        AgentTurnResponse card = service.chat(conversationId, SAYS);
        assertThat(card.stage()).as(card.reply()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(card.confirmation().title()).contains("健康备忘");
        String confirmationId = card.confirmation().confirmationId();

        // 卡片上写的那一份（= 签发时冻住的那一份），先从库里读回来对一遍。
        ConversationState issued = conversations.find(conversationId).orElseThrow();
        assertThat(issued.confirmationKind).isEqualTo(ConfirmationService.PendingOperation.Kind.MEMO.name());
        assertThat(issued.pendingMemoText).as(SAYS).isNotBlank();
        LocalDateTime shownAt = issued.pendingMemoAt;
        assertThat(shownAt).as("卡片上写了提醒时间，快照里就不能是空的").isNotNull();

        // 重启：服务内存里那份没了，这一轮只能从库里读。
        sessions().clear();
        AgentTurnResponse done = service.confirm(conversationId, true, confirmationId);

        assertThat(done.reply()).as(done.reply()).contains("已记下");
        assertThat(activeMemoCount()).isEqualTo(1);
        assertThat(storedMemoText()).as("写的是卡片上那段（时间由「提醒」那一行承担，正文里摘掉）")
                .isEqualTo(MemoParser.stripSchedule(issued.pendingMemoText));
        assertThat(storedMemoAt()).isEqualTo(shownAt);
    }

    /**
     * 旧快照：只有凭据、没有备忘草稿。
     *
     * <p>拿一张真卡改出来——把这次才进快照的六个草稿字段删掉，等价于上一个版本写下的那一行。
     * 那种快照读回来只有一个"要记备忘"的空壳；照着它执行，写进库的要么是 {@code null}，
     * 要么是按会话里别的什么凑的一段——两条路都是<b>写进去一条他从来没在卡上看到过的备忘</b>。
     * 所以这里要求整份作废。
     */
    @Test
    void anOldSnapshotWithoutTheMemoDraftIsDroppedInsteadOfWrittenPartly() {
        String conversationId = service.start("user-001").conversationId();
        AgentTurnResponse card = service.chat(conversationId, SAYS);
        String confirmationId = card.confirmation().confirmationId();

        stripTheMemoDraft(conversationId);

        sessions().clear();
        AgentTurnResponse refused = service.confirm(conversationId, true, confirmationId);

        // 关键在「在哪一道失效」：这里是凭据在<b>门口</b>就被判不可信（校验那一步），
        // 不是进门之后执行到写库才发现没有正文。后者也拦得住"没写库"，但那已经算执行过一次了。
        assertThat(refused.reply()).as(refused.reply()).contains("这份确认已经失效或已办理");
        assertThat(activeMemoCount()).isZero();
        // 也不许它停在等待确认上：那份凭据已经作废了，屏幕上那个按钮不会再亮。
        assertThat(refused.stage()).isNotEqualTo("AWAITING_CONFIRMATION");
        // 而且凭据是当场清掉的：老人重说一遍之后拿到的是一把新钥匙，不是这把。
        assertThat(conversations.find(conversationId).orElseThrow().confirmationId).isNull();
    }

    // —— 工具方法 ——

    /** 把库里的快照改成"上一个版本写下的样子"：删掉备忘草稿那六个字段。 */
    private void stripTheMemoDraft(String conversationId) {
        String stateJson = jdbc.queryForObject(
                "SELECT state_json FROM conversation_sessions WHERE id=?", String.class, conversationId);
        try {
            ObjectNode state = (ObjectNode) json.readTree(stateJson);
            for (String field : List.of("pendingMemoText", "pendingMemoAt", "pendingMemoRepeat",
                    "pendingMemoDay", "memoReturnStage", "memoNeedsApproval")) {
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
        assertThat(legacy.confirmationKind).isEqualTo(ConfirmationService.PendingOperation.Kind.MEMO.name());
        assertThat(legacy.pendingMemoText).isNull();
    }

    @SuppressWarnings("unchecked")
    private Map<String, ConversationState> sessions() {
        return (Map<String, ConversationState>) ReflectionTestUtils.getField(service, "sessions");
    }

    private int activeMemoCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM memos WHERE status='ACTIVE'", Integer.class);
    }

    private String storedMemoText() {
        return jdbc.queryForObject("SELECT text FROM memos WHERE status='ACTIVE'", String.class);
    }

    private LocalDateTime storedMemoAt() {
        return jdbc.queryForObject("SELECT remind_at FROM memos WHERE status='ACTIVE'",
                (rs, row) -> rs.getTimestamp(1).toLocalDateTime());
    }
}
