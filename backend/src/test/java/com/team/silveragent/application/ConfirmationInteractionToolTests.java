package com.team.silveragent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.silveragent.domain.model.ToolModels.AppointmentSummary;
import com.team.silveragent.infrastructure.mock.ToolTraceStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 确认卡的权威字段全部由 Java 按数据库快照生成，模型最多在卡片前面写一句不提业务事实的开场白。
 *
 * <p>闸门不是一张中文完成态黑名单，而是一条结构性约束：那句话里出现业务词（取消/预约/号源/提醒）、
 * 数字或完成态说法，就整句丢弃。标题尤其不能交给模型——它看不见数据库，写错过
 * “已经帮您取消预约了吗？”这种标题。
 */
class ConfirmationInteractionToolTests {
    private final ConfirmationInteractionTool tool = new ConfirmationInteractionTool(
            new ToolTraceStore(mock(JdbcTemplate.class), new ObjectMapper(), mock(TurnProgress.class)));

    @Test
    void modelDraftWithNumbersNeverReachesTheReply() {
        ConfirmationInteractionTool.Prompt prompt = tool.requestCancellation("conversation", "cid",
                List.of(target()), "您要取消9月12日那条复诊预约吗？");

        assertThat(prompt.reply()).doesNotContain("9月12日", "您要取消");
        assertThat(prompt.card().title()).isEqualTo("是否取消这次复诊预约");
    }

    @Test
    void modelDraftClaimingTheCancellationAlreadyHappenedIsRejected() {
        ConfirmationInteractionTool.Prompt prompt = tool.requestCancellation("conversation", "cid",
                List.of(target()), "已经帮您取消好了。");

        assertThat(prompt.reply()).doesNotContain("已经帮您取消");
        assertThat(prompt.card().title()).isEqualTo("是否取消这次复诊预约");
    }

    /** 标题里那句“是否取消这次复诊预约”必须由 Java 写，模型写得再干净也不许顶替它。 */
    @Test
    void cardTitleIsAlwaysGeneratedByJavaEvenWhenTheDraftLooksClean() {
        ConfirmationInteractionTool.Prompt prompt = tool.requestCancellation("conversation", "cid",
                List.of(target()), "好的，我再和您确认一下。");

        assertThat(prompt.card().title()).isEqualTo("是否取消这次复诊预约");
        // 干净的开场白只出现在卡片前面那一句，事实句、影响、按钮仍按数据库快照生成。
        assertThat(prompt.reply()).startsWith("好的，我再和您确认一下。");
        assertThat(prompt.reply()).contains("您准备取消", "2026年9月20日", "确认取消", "保留预约");
    }

    /** 批量标题里的条数来自真实预约对象，不是模型说几条就几条。 */
    @Test
    void batchTitleCountComesFromTheTargetsNotFromTheModelDraft() {
        ConfirmationInteractionTool.Prompt prompt = tool.requestCancellation("conversation", "cid",
                List.of(target(), target()), "我再和您确认一下。");

        assertThat(prompt.card().title()).isEqualTo("是否取消这2条复诊预约");
    }

    /** 开场白一旦提到业务本身，就整句丢弃——它一个业务词都说不了，也就编不出完成态结论。 */
    @Test
    void openerThatMentionsTheBusinessItselfIsDroppedEntirely() {
        ConfirmationInteractionTool.Prompt prompt = tool.requestCancellation("conversation", "cid",
                List.of(target()), "这次复诊预约要帮您取消吗？");

        assertThat(prompt.reply()).doesNotContain("这次复诊预约要帮您取消吗");
        assertThat(prompt.card().title()).isEqualTo("是否取消这次复诊预约");
    }

    @Test
    void appointmentFactsAndButtonsComeFromTheTargetsNotTheModel() {
        ConfirmationInteractionTool.Prompt prompt = tool.requestCancellation("conversation", "cid",
                List.of(target(), target()), "都听您的。");

        assertThat(prompt.card().operations()).contains("取消预约：2026年9月20日 09:00 市第一医院（模拟） · 心内科");
        assertThat(prompt.card().confirmText()).isEqualTo("确认全部取消");
        assertThat(prompt.card().cancelText()).isEqualTo("全部保留");
        assertThat(prompt.card().confirmationId()).isEqualTo("cid");
    }

    private AppointmentSummary target() {
        return new AppointmentSummary("AP-1", "市第一医院（模拟）", "心内科",
                LocalDate.of(2026, 9, 20), LocalTime.of(9, 0), "CONFIRMED",
                null, null, null, null, List.of());
    }
}
