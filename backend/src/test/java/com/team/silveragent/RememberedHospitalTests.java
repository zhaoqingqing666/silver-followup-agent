package com.team.silveragent;

import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.support.DemoSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「还是像以前那样去市第一医院（模拟）吗？」——这句话由 Java 说出口，就必须由 Java 认得住。
 *
 * <p>起因是一段真实演示：助手顺着长期记忆问了这一句，老人答「是的」，Java 手上却没有任何候选，
 * 只好回「我还没有确认您说的是哪家医院」。记忆本身没错，错在说这句的人和认这句话的不是同一个。
 * 这一组就盯住这条闭环：说了就必须认；没有记忆时一个字都不许多说。
 *
 * <p>另一半是科室按钮：一个医院目录里 6 个科室要一次摆得下（前端一屏 3 个、翻页看后 3 个），
 * 不能让「查看更多选项」翻开只有一个「我自己说科室」。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-remembered-hospital-test;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=false",
})
class RememberedHospitalTests {

    /** 演示种子里的心内科上午号：真实存在，且与其它测试互不干扰（这里每例都清库重来）。 */
    private static final String DAY = DemoSeed.day(DemoSeed.checkupDay());
    private static final String MORNING_SLOT = DemoSeed.morningSlot();

    @Autowired FollowupAgentService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        for (String table : List.of("user_memories", "appointments", "reminders", "family_notifications")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE appointment_slots SET booked=0, available=TRUE");
    }

    /**
     * 真的办成一笔，让记忆里长出「常去的医院」。
     *
     * <p>刻意走完整个确认门禁，而不是直接往 user_memories 里插一行——记忆唯一被允许的写入路径
     * 就是「预约真的落库」，测试也不该绕开它，否则测的就不是线上那条路。
     */
    private void bookOnce() {
        String id = service.start().conversationId();
        service.act(id, "SET_HOSPITAL", "h001", "选择市第一医院（模拟）");
        service.act(id, "SET_DEPARTMENT", DemoSeed.CARDIOLOGY, "心内科");
        service.act(id, "SET_DATE", DAY, "日期");
        service.act(id, "SELECT_SLOT", MORNING_SLOT, "上午");
        service.act(id, "SET_ALTERNATIVE", "true", "可以换日期");
        service.act(id, "SET_COMPANION", "false", "不需要陪同");
        service.act(id, "SET_TRAVEL", "false", "不需要");
        service.act(id, "SET_TRANSPORT", "打车", "打车");
        service.act(id, "SET_NOTIFY", "false", "不用通知");
        AgentTurnResponse card = service.act(id, "START_PLAN", "", "检查计划");
        AgentTurnResponse done = service.confirm(id, true, card.confirmation().confirmationId());
        assertThat(done.result()).as("这一笔必须真的办成，记忆才有来源").isNotNull();
    }

    private List<String> labels(AgentTurnResponse turn) {
        return turn.quickReplies().stream().map(AgentTurnResponse.QuickReply::label).toList();
    }

    @Test
    void askingHospitalOffersTheRememberedOne() {
        bookOnce();

        AgentTurnResponse asked = service.act(service.start().conversationId(), "CONTINUE", "", "开始复诊办理");

        assertThat(asked.stage()).as(asked.reply()).isEqualTo("ASK_HOSPITAL");
        // 记忆里存的是「常去的医院是市第一医院（模拟）」，但目录返回的名字是清洗过的——文案用目录那一份，
        // 所以这里断言的是「市第一医院」而不是记忆原文。
        assertThat(asked.reply()).contains("还是像以前那样去市第一医院吗");
        assertThat(labels(asked)).containsExactly("是的，市第一医院", "换一家医院");
    }

    @Test
    void aShortYesIsClaimedByJavaInsteadOfBeingAskedAgain() {
        bookOnce();
        String id = service.start().conversationId();
        service.act(id, "CONTINUE", "", "开始复诊办理");

        AgentTurnResponse yes = service.chat(id, "是的");

        // 演示里翻车的就是这一句：不能再回「我还没有确认您说的是哪家医院」。
        assertThat(yes.reply()).doesNotContain("我还没有确认您说的是哪家医院");
        assertThat(yes.stage()).as(yes.reply()).isEqualTo("ASK_DEPARTMENT");
    }

    @Test
    void withoutMemoryTheQuestionStaysAPlainQuestion() {
        AgentTurnResponse asked = service.act(service.start().conversationId(), "CONTINUE", "", "开始复诊办理");

        // 没有记忆就不许替老人回忆：既不多说一句话，也不挂一个无中生有的按钮。
        assertThat(asked.stage()).isEqualTo("ASK_HOSPITAL");
        assertThat(asked.reply()).doesNotContain("还是像以前那样去");
        assertThat(asked.quickReplies()).isEmpty();
    }

    @Test
    void aShortYesWithoutMemoryStillAsksForTheName() {
        String id = service.start().conversationId();
        service.act(id, "CONTINUE", "", "开始复诊办理");

        // 手上没有候选时，一句「是的」不能凭空认下一家医院——这里必须老老实实再问一次。
        assertThat(service.chat(id, "是的").reply()).contains("我还没有确认您说的是哪家医院");
    }

    @Test
    void sameAsLastTimeIsClaimedJustLikeAShortYes() {
        bookOnce();
        String id = service.start().conversationId();
        service.act(id, "CONTINUE", "", "开始复诊办理");

        AgentTurnResponse same = service.chat(id, "和上次一样");

        // 「和上次一样」是老人被问「去哪家医院」时最自然的答法。Java 手上已经有候选（记忆里那家），
        // 就不能因为这句话不是「是的」而当成没听懂——真实演示里翻车的正是这一句。
        assertThat(same.reply()).doesNotContain("我还没有确认您说的是哪家医院");
        assertThat(same.stage()).as(same.reply()).isEqualTo("ASK_DEPARTMENT");
    }

    @Test
    void sameAsLastTimeWithoutMemoryStillAsksForTheName() {
        String id = service.start().conversationId();
        service.act(id, "CONTINUE", "", "开始复诊办理");

        // 没有候选时「照旧」也无处可落：必须回落到问名字，不能凭空认一家。
        assertThat(service.chat(id, "和上次一样").reply()).contains("我还没有确认您说的是哪家医院");
    }

    @Test
    void aNegatedReminiscentPhraseIsNotTreatedAsConfirmation() {
        bookOnce();
        String id = service.start().conversationId();
        service.act(id, "CONTINUE", "", "开始复诊办理");

        // 带否定的说法（「上次那家不好」）不许被当成「就选记忆里那家」——那等于替老人把医院定了。
        assertThat(service.chat(id, "上次那家不好").stage()).isEqualTo("ASK_HOSPITAL");
    }

    @Test
    void departmentChoicesCoverTheWholeCatalogWithoutASelfInputButton() {
        String id = service.start().conversationId();
        service.act(id, "SET_HOSPITAL", "h001", "选择市第一医院（模拟）");

        AgentTurnResponse dept = service.act(id, "CHANGE_DEPARTMENT", "", "换科室");

        assertThat(dept.stage()).as(dept.reply()).isEqualTo("ASK_DEPARTMENT");
        // 目录里这家医院 6 个科室就摆 6 个（前端一屏 3 个、翻页看后 3 个）。
        // 「我自己说科室」不再占位：老人本来就能直接打字或说科室名，位置让给真科室更划算。
        assertThat(labels(dept))
                .containsExactly("心内科", "神经内科", "内分泌科", "骨科", "呼吸内科", "消化内科");
        assertThat(labels(dept)).doesNotContain("我自己说科室");
    }
}
