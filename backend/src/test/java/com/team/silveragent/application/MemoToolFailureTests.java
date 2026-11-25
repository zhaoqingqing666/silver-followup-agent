package com.team.silveragent.application;

import com.team.silveragent.application.memo.MemoStore;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.tool.MemoTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 备忘写库失败时的收场——<b>走 HTTP</b>（确认卡那条路是真实的前端调用方式）。
 *
 * <p>确认那条路的调用点是 {@code FollowupAgentService.confirm()}，它本身没有 catch：执行器里
 * 漏出来的异常会一路穿过 Controller，老人看到的是一个 HTTP 500——一块白屏，或者一句"服务器开小差"，
 * 而<b>这一轮到底办没办成谁也说不清</b>。更坏的是凭据在进来的时候就已经被消费掉了，
 * 所以他连"再点一次"都做不到。
 *
 * <p>所以这里钉四件事：响应正常（不是 500）、失败被如实说出来、库里没有多出第二条、
 * 会话没有假装成功（凭据没了，卡片不会再亮）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-memo-failure;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=false"})
@AutoConfigureMockMvc
class MemoToolFailureTests {

    @Autowired FollowupAgentService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    /** 写不进去的那条库。 */
    @MockitoBean MemoTool memoTool;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM memos");
        sessions().clear();
        when(memoTool.create(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("备忘库写不进去"));
        // 只让「写」坏掉：读那一侧照常给个空答案，免得替身的默认 null 把别的路也带崩，
        // 那样测的就不是「写入失败」这一件事了。
        when(memoTool.active(any(), any())).thenReturn(java.util.List.of());
        when(memoTool.remove(any(), any(), any())).thenReturn(false);
    }

    /** 工具抛异常：HTTP 正常返回、话说清楚了、库里没有这条备忘。 */
    @Test
    void aFailingMemoWriteReturnsAFriendlyResponseInsteadOfA500() throws Exception {
        String conversationId = service.start("user-001").conversationId();
        AgentTurnResponse card = service.chat(conversationId, "明天早上8点要去抽血，最好空腹");
        assertThat(card.stage()).as(card.reply()).isEqualTo("AWAITING_CONFIRMATION");
        String confirmationId = card.confirmation().confirmationId();

        String body = mvc.perform(post("/api/agent/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":"%s","approved":true,"confirmationId":"%s"}
                                """.formatted(conversationId, confirmationId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as(body).contains("本步骤未完成").doesNotContain("已记下");
        assertThat(activeMemoCount()).as("写失败了就不能留下半条").isZero();
    }

    /**
     * 失败之后账要清楚：凭据已经消费掉，卡片不许再亮，重按一次也写不出第二条。
     *
     * <p>不这样的话，页面上会停着一张"实际已经无效的确认卡"——他再点一次，要么看到一句
     * 莫名其妙的失效提示，要么（更糟）真写进去一条。
     */
    @Test
    void aFailedMemoWriteBurnsTheCredentialAndDoesNotPretendSuccess() {
        String conversationId = service.start("user-001").conversationId();
        AgentTurnResponse card = service.chat(conversationId, "明天早上8点要去抽血，最好空腹");
        String confirmationId = card.confirmation().confirmationId();

        AgentTurnResponse failed = service.confirm(conversationId, true, confirmationId);

        assertThat(failed.stage()).as(failed.reply()).isEqualTo("TOOL_ERROR");
        assertThat(failed.reply()).as(failed.reply()).contains("本步骤未完成");
        assertThat(failed.reply()).as(failed.reply()).doesNotContain("已记下");
        // 页面拿不到卡，就摆不出一张已经作废的确认卡。
        assertThat(failed.confirmation()).isNull();
        assertThat(activeMemoCount()).isZero();

        AgentTurnResponse again = service.confirm(conversationId, true, confirmationId);
        assertThat(again.reply()).as(again.reply()).contains("失效");
        assertThat(activeMemoCount()).isZero();
    }

    /**
     * 一句话说了三天、写到第二天时库坏了：<b>停下</b>，把已经落库的那天念出来，没写成的如实说清是哪两天。
     *
     * <p>这是多天拆条之后新出现的一种收场，两句话都不能省：只说“已经记下 1 条”，老人以为三天都设好了，
     * 第三天永远不响；只说“没记上”，他又会以为一条都没有、回头重说一遍。已经落库的那条也不许回滚——
     * 那等于把老人已经看到的东西又拿走，而且凭据早就消费掉了，这一轮的事只能照实说。
     *
     * <p>这里数的是“对工具喊了几次”，不是库里的行数：这条工具的替身不写库（见 {@link MemoToolFailureTests}
     * 开头那段），真实落库由 {@code MemoFlowTests} 那几条守着。次数正好说明两件事——第二次失败之后<b>没有
     * 再往下试</b>，以及已经写成的那条<b>没有被删掉回滚</b>。
     */
    @Test
    void aWriteThatFailsOnTheSecondDayKeepsTheFirstAndSaysWhichDaysAreMissing() {
        // 第一条写得进去，第二条开始坏掉。用 doReturn/doThrow 重新打桩而不是 when(...).thenReturn——
        // when(mock.create(...)) 自己就会先调一次那个方法，而 @BeforeEach 的替身是“一调就抛”，
        // 于是异常在打桩这一步就炸出来，新桩根本没装上。
        LocalDate nextMonday = nextMonday();
        doReturn(new MemoStore.MemoView("memo-first", "吃药", nextMonday.atTime(8, 0), null,
                "ACTIVE", LocalDateTime.now()))
                .doThrow(new IllegalStateException("备忘库写不进去"))
                .when(memoTool).create(any(), any(), any(), any(), any());

        String conversationId = service.start("user-001").conversationId();
        AgentTurnResponse done = service.chat(conversationId, "提醒我下周周一周二周三早上八点吃药");

        // 写成的那条照实念，没写成的点名——不能含糊成“都记好了”
        assertThat(done.reply()).as(done.reply()).contains("已记下 1 条").doesNotContain("已记下 3 条");
        assertThat(done.reply()).as(done.reply()).contains("还有 2 条没记上："
                + label(nextMonday.plusDays(1)) + " 08:00、" + label(nextMonday.plusDays(2)) + " 08:00");
        // 第二次失败之后就停：第三天不再试（试了只会让“成了几条”更难说清）
        verify(memoTool, times(2)).create(any(), any(), any(), any(), any());
        // 已经写成的那条留着，不回滚
        verify(memoTool, never()).remove(any(), any(), any());
    }

    private static ZoneId demoZone() {
        return ZoneId.of("Asia/Shanghai");
    }

    /** 从今天起算的下一个周一（今天就是周一时算 7 天后），与 MemoParser 的"下周"口径一致。 */
    private static LocalDate nextMonday() {
        LocalDate today = LocalDate.now(demoZone());
        int toMonday = (8 - today.getDayOfWeek().getValue()) % 7;
        return today.plusDays(toMonday == 0 ? 7 : toMonday);
    }

    /** 回读里那一天的写法：“9月21日（周一）”。 */
    private static String label(LocalDate date) {
        return date.getMonthValue() + "月" + date.getDayOfMonth() + "日（周"
                + "一二三四五六日".charAt(date.getDayOfWeek().getValue() - 1) + "）";
    }

    @SuppressWarnings("unchecked")
    private Map<String, ConversationState> sessions() {
        return (Map<String, ConversationState>) ReflectionTestUtils.getField(service, "sessions");
    }

    private int activeMemoCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM memos", Integer.class);
    }
}
