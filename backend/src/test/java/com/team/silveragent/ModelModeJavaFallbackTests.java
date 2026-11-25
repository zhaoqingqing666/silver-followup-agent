package com.team.silveragent;

import com.team.silveragent.agent.model.ModelGateway;
import com.team.silveragent.agent.model.ModelRequest;
import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.application.memo.MemoStore;
import com.team.silveragent.domain.model.AgentTurnResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型主导（{@code agent.model.enabled=true}）时，「健康备忘」这件事仍然是 Java 填槽、Java 落库。
 *
 * <p>{@link AgentRuntimeRoutingTests} 只证明到「模型说了 MANAGE_MEMO，路由就落在 MANAGE_MEMO」，
 * 那是装配好一根 {@code AgentRuntime} 直接调的，没经过 Spring 上下文，也到不了写入。这个测试补的是
 * 后半截：{@link ScriptedModelGateway} 这一轮只负责说「这句话是记一条备忘」，真正解析时间、
 * 决定写不写、写什么的是 {@link FollowupAgentService} 里那批解析器。
 *
 * <p>所以断言刻意落在<b>库里的行</b>和<b>工具轨迹</b>上，不落在措辞上：模型把回复润色成什么样都行，
 * 老人被答应「记下了」而库里一条都没有，才是这条判据要挡的事。每个用例都会先确认这一轮真的
 * 问过模型（{@link ScriptedModelGateway#plannerCalls}）——模型没接上时测试不能照样绿。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-model-memo;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=true"})
class ModelModeJavaFallbackTests {

    /** 备忘按中国时区起算（与 MemoParser.DEMO_ZONE 一致），断言要用同一时区才不受运行时刻影响。 */
    private static final ZoneId DEMO_ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired FollowupAgentService service;
    @Autowired MemoStore memos;
    @Autowired JdbcTemplate jdbc;
    @Autowired ScriptedModelGateway gateway;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM memos");
        // 健康记录也要清：下面有一条判“这一轮没写记录”，别人留下的行会让它假红。
        jdbc.update("DELETE FROM health_records");
        gateway.intent = "MANAGE_MEMO";
    }

    private int memoCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM memos WHERE status='ACTIVE'", Integer.class);
        return count == null ? 0 : count;
    }

    private List<MemoStore.MemoView> recorded() {
        return memos.activeFor("user-001");
    }

    private AgentTurnResponse send(String message) {
        return service.chat(service.start("user-001").conversationId(), message);
    }

    /** 这一轮确实问过模型：不查这个的话，模型没接上（配置写错、装配被换掉）测试照样绿。 */
    private void assertModelWasConsulted() {
        assertThat(gateway.plannerCalls.get()).isPositive();
    }

    @Test
    void modelRecognizesTheMemoButJavaParsesTheTimeAndWritesTheRow() {
        AgentTurnResponse reply = send("提醒我明天早上八点吃药");

        assertModelWasConsulted();
        // 这一轮的凭据是库里那一行：正文和时间都由 Java 的解析器算出来，不是模型那句话说了算
        assertThat(recorded()).hasSize(1);
        assertThat(recorded().get(0).text()).isEqualTo("吃药");
        assertThat(recorded().get(0).remindAt())
                .isEqualTo(LocalDate.now(DEMO_ZONE).plusDays(1).atTime(8, 0));
        assertThat(reply.toolTraces()).anyMatch(trace ->
                "memo.create".equals(trace.toolName()) && trace.success());
    }

    /**
     * 模型认错了：把一句闲聊说成备忘。Java 的备忘解析器才是闸门——它说这不是备忘，
     * 就一条都不写，退回原链路照常回答，不许顺着模型那句「好的，我给您记下了」写一行。
     */
    @Test
    void aSentenceTheJavaMemoParserRejectsIsNotWrittenEvenWhenTheModelCallsItAMemo() {
        AgentTurnResponse reply = send("今天天气不错");

        assertModelWasConsulted();
        assertThat(memoCount()).isZero();
        assertThat(reply.toolTraces()).noneMatch(trace -> "memo.create".equals(trace.toolName()));
    }

    /**
     * 一句话里两个时间点：模型说是备忘，Java 的「一件一件说」判据仍然先拦下来——
     * 这条判据不在 {@code if (!modelAvailable())} 分支里，开关模型都要跑。
     */
    @Test
    void theOneThingAtATimeGuardStillRunsWhenTheModelIsAvailable() {
        AgentTurnResponse reply = send("提醒我周一早上八点吃药，周三下午三点复查");

        assertModelWasConsulted();
        assertThat(memoCount()).isZero();
        assertThat(reply.toolTraces()).noneMatch(trace -> "memo.create".equals(trace.toolName()));
        // 这句是 Java 自己写的出口（respondWithoutModel），模型润不到它
        assertThat(reply.reply()).contains("一件一件说");
    }

    /**
     * 用户报的那条 bug 的第二种形态，也是最难堵的一种：模型把「帮我记一下我对青霉素过敏」
     * 当成闲聊（intent=UNKNOWN），自己写一句「好的，我记下了」，而这一轮一个工具都没跑。
     *
     * <p>断言落在<b>库里的行</b>上而不是措辞上：改完之后这一轮不该只是「换一句话糊过去」，
     * 而应该退回模型离线时那条 Java 链路，把老人托付的这件事<b>真的记成一条长期备忘</b>；
     * 也不该把「我记下了」原样念给他听——那句话曾经在库里一条都没有的情况下被说出去过。
     */
    @Test
    void anAnswerClaimingAWriteThatNeverHappenedGoesBackToTheJavaChain() {
        gateway.intent = "UNKNOWN";
        gateway.replyDraft = "好的，我记下了：您对青霉素过敏。";

        AgentTurnResponse reply = send("帮我记一下我对青霉素过敏");

        assertModelWasConsulted();
        assertThat(recorded()).hasSize(1);
        assertThat(recorded().get(0).text()).contains("青霉素");
        assertThat(reply.reply()).doesNotContain("我记下了");
        assertThat(reply.toolTraces()).anyMatch(trace ->
                "memo.create".equals(trace.toolName()) && trace.success());
    }

    /**
     * 一句话里两件事（报个数 + 让提醒）：这条路上只有数值那半会办，另一件必须当面交回给老人。
     *
     * <p>老人说完就去等提醒了，而这条链路一个备忘都不写——他顺着卡片那句“需要我记下吗”点头，
     * 只会把数值记下来，提醒那半<b>从头到尾没人提</b>。所以照“一件一件办”的口径：数值照常出卡，
     * 另一件说出来，让他单独再说一遍。断言落在“这一轮有没有写备忘”上——只看措辞分不出
     * “说出来了”和“真的记下来了”这两件事（后者才是他不知道的那个）。
     *
     * <p>判据是“这句里还有一件<b>带时间的让提醒</b>”，不是“能解析出备忘”：{@code MemoParser}
     * 对显式托付本来就宽松，「记一下我血压130」也能解析成一条备忘，可那是同一件事、不是第二件。
     * 这里说的“带时间”包含只说了时段的那种（“明天早上吃药”）——那半句这一轮本来就该反问他几点，
     * 更得说出来：他要等的是那个提醒，而这条链路一个备忘都不写。
     */
    @Test
    void aNumberAndAReminderInOneSentenceHandlesTheNumberAndSaysTheOtherOneOutLoud() {
        gateway.intent = "RECORD_HEALTH_VALUE";

        AgentTurnResponse reply = send("我血压130，顺便提醒我明天早上吃药");

        assertModelWasConsulted();
        assertThat(reply.confirmation()).isNotNull();
        assertThat(reply.reply()).contains("没有一起办");
        assertThat(memoCount()).isZero();
        assertThat(reply.toolTraces()).noneMatch(trace -> "memo.create".equals(trace.toolName()));
    }

    /** 只说了一个数的句子不带那句补话：判据是“还有第二件带时间的提醒”，不是“这句话像不像托付”。 */
    @Test
    void anOrdinaryReadingGetsNoSecondThingNote() {
        gateway.intent = "RECORD_HEALTH_VALUE";

        AgentTurnResponse reply = send("我的血压是138");

        assertModelWasConsulted();
        assertThat(reply.confirmation()).isNotNull();
        assertThat(reply.reply()).doesNotContain("没有一起办");
    }

    /**
     * 反过来的一面：这句话先被备忘认领（模型判成记备忘），于是数那半没人接。
     *
     * <p>跟 {@link #aNumberAndAReminderInOneSentenceHandlesTheNumberAndSaysTheOtherOneOutLoud}
     * 是同一件事的两个出口，看的是“哪半被办、哪半只剩一句话”。这里备忘照记（提醒真落库了），
     * 但血压那半不许静默消失——老人在 8198 上真遇到过：备忘存成“我血压130，顺便提醒我早上吃药”，
     * 数不在记录里，他也从没被告知。所以断言两处：库里备忘有一条，健康记录一条都没有但有交代。
     */
    @Test
    void aMemoSentenceThatAlsoCarriesAReadingSaysTheReadingWasNotRecorded() {
        gateway.intent = "MANAGE_MEMO";

        AgentTurnResponse reply = send("我血压130，顺便提醒我明天早上八点吃药");

        assertModelWasConsulted();
        // 提醒那半照办：这条是模型认出来的，时间和正文仍旧由 Java 解析器算
        assertThat(recorded()).hasSize(1);
        assertThat(recorded().get(0).remindAt())
                .isEqualTo(LocalDate.now(DEMO_ZONE).plusDays(1).atTime(8, 0));
        // 数那半不办，但当面说清：没写进记录、让他单独再说一遍
        assertThat(reply.reply()).contains("没有一起记到健康记录").contains("血压 130");
        Integer records = jdbc.queryForObject("SELECT COUNT(*) FROM health_records", Integer.class);
        assertThat(records).isZero();
    }

    /** 备忘正文里没有数值时，那句“还报了一个数”不许冒出来。 */
    @Test
    void anOrdinaryMemoSentenceGetsNoReadingNote() {
        gateway.intent = "MANAGE_MEMO";

        AgentTurnResponse reply = send("提醒我明天早上八点吃药");

        assertModelWasConsulted();
        assertThat(recorded()).hasSize(1);
        assertThat(reply.reply()).doesNotContain("没有一起记");
    }

    /** 脚本化模型：规划阶段只说「这句话是 MANAGE_MEMO」，其余一概不认。 */
    static class ScriptedModelGateway implements ModelGateway {
        volatile String intent = "MANAGE_MEMO";
        /** 规划阶段那句草稿：模型打算直接说给老人的话。 */
        volatile String replyDraft = "好的，我给您记下了。";
        /** 真正被当成规划器问过几次；回答阶段的改写不算。 */
        final AtomicInteger plannerCalls = new AtomicInteger();

        @Override public String complete(ModelRequest request) {
            String system = request.messages().isEmpty() ? "" : request.messages().get(0).content();
            // 回答阶段返回空：LlmAnswerGenerator 校验不过会退回权威草稿，这一轮要断言的
            // 正是 Java 那句草稿和它背后的写入，不让措辞把结论搅浑。
            if (system.contains("工具结果后的回答阶段")) return "";
            plannerCalls.incrementAndGet();
            return """
                    {"actionType":"ANSWER","intent":"%s","toolName":null,"arguments":{},
                     "replyDraft":"%s","dialogueMode":"FOLLOWUP_FLOW","facts":{}}
                    """.formatted(intent, replyDraft);
        }

        @Override public boolean available() { return true; }

        @Override public String providerName() { return "scripted"; }

        @Override public String modelName() { return "scripted"; }
    }

    @TestConfiguration
    static class ScriptedModelConfig {
        @Bean @Primary
        ScriptedModelGateway scriptedModelGateway() { return new ScriptedModelGateway(); }
    }
}
