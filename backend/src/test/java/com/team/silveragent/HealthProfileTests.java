package com.team.silveragent;

import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.application.HealthProfileStore;
import com.team.silveragent.application.memo.MemoParser;
import com.team.silveragent.domain.model.AgentTurnResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 健康档案（过敏史 / 既往病史 / 身高 / 体重）。
 *
 * <p>这里钉住四件事：两端读写的是同一份、null 与空串的区别、校验不通过时一个字都不写、
 * 以及最要紧的那条——助手看不到这块数据。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-health-profile;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=false"})
@AutoConfigureMockMvc
class HealthProfileTests {

    @Autowired HealthProfileStore profiles;
    @Autowired FollowupAgentService agent;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @BeforeEach
    void resetProfile() {
        jdbc.update("""
                UPDATE users SET allergies=NULL,medical_history=NULL,height_cm=NULL,weight_kg=NULL,
                       health_profile_updated_by=NULL,health_profile_updated_at=NULL
                WHERE id='user-001'
                """);
    }

    @Test
    void whatOneSideWritesIsWhatTheOtherSideReads() {
        // 家属（小丽）在长辈信息里填的
        profiles.save("user-001", "小丽", "158", "61.5", "青霉素过敏", "高血压（2019年确诊）");

        HealthProfileStore.HealthProfile filled = profiles.get("user-001");
        assertThat(filled.heightCm()).isEqualTo(158);
        assertThat(filled.weightKg()).isEqualTo(61.5);
        assertThat(filled.allergies()).isEqualTo("青霉素过敏");
        assertThat(filled.updatedBy()).isEqualTo("小丽");
        assertThat(filled.updatedAt()).isNotNull();

        // 老人自己在“我的”里改了体重：读到的就是最新那份，没有第二份数据要对齐
        profiles.save("user-001", "王阿姨", null, "60", null, null);

        HealthProfileStore.HealthProfile changed = profiles.get("user-001");
        assertThat(changed.weightKg()).isEqualTo(60.0);
        assertThat(changed.allergies()).isEqualTo("青霉素过敏"); // 没传的字段原样不动
        assertThat(changed.updatedBy()).isEqualTo("王阿姨");
    }

    @Test
    void nullKeepsAFieldAndEmptyStringClearsIt() {
        profiles.save("user-001", "小丽", "158", "61.5", "青霉素过敏", "高血压");

        // null = 这一项没动；空串 = 清掉。身高体重的“清掉”也走空串，
        // 不然想删掉一个填错的身高，只能把它改成 0 这种明显是错的数。
        profiles.save("user-001", "小丽", "", null, "", null);

        HealthProfileStore.HealthProfile after = profiles.get("user-001");
        assertThat(after.allergies()).isNull();
        assertThat(after.heightCm()).isNull();
        assertThat(after.medicalHistory()).isEqualTo("高血压");
    }

    @Test
    void aRejectedNumberDoesNotTouchTheOtherFields() {
        profiles.save("user-001", "小丽", "158", "61.5", "青霉素过敏", "高血压");

        // 身高填错（180 写成 18）：这一整次都不该落库，不能出现"身高被拒了、过敏史却已经改了"
        assertThatThrownBy(() -> profiles.save("user-001", "小丽", "18", "61.5", "改过的过敏史", "改过的病史"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("身高");

        HealthProfileStore.HealthProfile untouched = profiles.get("user-001");
        assertThat(untouched.allergies()).isEqualTo("青霉素过敏");
        assertThat(untouched.medicalHistory()).isEqualTo("高血压");
        assertThat(untouched.heightCm()).isEqualTo(158);
    }

    @Test
    void notANumberGetsAPlainHintInsteadOfACrash() {
        assertThatThrownBy(() -> profiles.save("user-001", "小丽", "一米五八", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("身高请填数字");
        assertThatThrownBy(() -> profiles.save("user-001", "小丽", null, "六十", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("体重请填数字");
    }

    @Test
    void changingOneFieldOnAnEmptyArchiveDoesNotBlowUp() {
        // 从来没人填过的档案（六列全空），老人上来只改了体重：其余三项传 null = "没动"，
        // 而库里本来也是 null——两边都是 null 是正常情况，不能当成出错（更不该 500）。
        profiles.save("user-001", "王阿姨", null, "60", null, null);

        HealthProfileStore.HealthProfile only = profiles.get("user-001");
        assertThat(only.weightKg()).isEqualTo(60.0);
        assertThat(only.heightCm()).isNull();
        assertThat(only.allergies()).isNull();
        assertThat(only.medicalHistory()).isNull();
        assertThat(only.updatedBy()).isEqualTo("王阿姨");
    }

    @Test
    void theArchiveClockIsTheSameOneTheMemosAndRecordsUse() {
        profiles.save("user-001", "小丽", null, null, "青霉素过敏", null);

        // 容器本身是 UTC，而界面上显示的时间一律是北京时间（备忘到点、健康记录的测量时间都走
        // MemoParser 那个钟）。这里要是用了 LocalDateTime.now()，档案卡片上的"最近填写时间"
        // 就会比同一屏里那条健康记录早 8 小时。
        long minutesOff = java.time.Duration
                .between(profiles.get("user-001").updatedAt(), MemoParser.nowInDemoZone()).abs().toMinutes();
        assertThat(minutesOff).isLessThan(2L);
    }

    @Test
    void bothEndsGoThroughTheSameEndpoint() throws Exception {
        mvc.perform(put("/api/users/user-001/health-profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"heightCm\":\"158\",\"weightKg\":\"61.5\",\"allergies\":\"青霉素过敏\","
                                + "\"medicalHistory\":\"高血压\",\"editorName\":\"小丽\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allergies").value("青霉素过敏"))
                .andExpect(jsonPath("$.updatedBy").value("小丽"));

        // 老人端读的是同一个地址，拿到的就是家属刚填的
        mvc.perform(get("/api/users/user-001/health-profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heightCm").value(158))
                .andExpect(jsonPath("$.updatedBy").value("小丽"));
    }

    @Test
    void aBadHeightComesBackAsAPlainMessage() throws Exception {
        mvc.perform(put("/api/users/user-001/health-profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"heightCm\":\"1800\",\"editorName\":\"小丽\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("身高")));
    }

    @Test
    void theAssistantCannotSeeAnyOfIt() {
        profiles.save("user-001", "小丽", "158", "61.5", "青霉素过敏", "高血压（2019年确诊）");

        AgentTurnResponse start = agent.start("user-001");
        AgentTurnResponse asked = agent.chat(start.conversationId(), "我有什么过敏吗");
        AgentTurnResponse general = agent.chat(start.conversationId(), "我最近要注意什么");

        // 这是一根绊线，不是"助手永远不知道"的证明：眼下它读不到，是因为没有任何一条路
        // 把这块数据递给它。谁哪天把它接进提示词、或者给它加了个查询工具，这里就会红。
        for (AgentTurnResponse reply : java.util.List.of(asked, general)) {
            assertThat(reply.reply()).doesNotContain("青霉素").doesNotContain("高血压");
            assertThat(String.valueOf(reply.speechText())).doesNotContain("青霉素");
            assertThat(reply.toolTraces().toString()).doesNotContain("青霉素").doesNotContain("过敏");
        }
    }
}
