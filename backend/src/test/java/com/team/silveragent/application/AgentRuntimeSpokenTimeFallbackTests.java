package com.team.silveragent.application;

import com.team.silveragent.agent.AgentContext;
import com.team.silveragent.agent.ExtractedFacts;
import com.team.silveragent.agent.RuleFactExtractor;
import com.team.silveragent.agent.planning.ConversationPlanner;
import com.team.silveragent.agent.planning.PlannerActionType;
import com.team.silveragent.agent.planning.PlannerDecision;
import com.team.silveragent.agent.planning.PlannerTool;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型链路的口语时间兜底（`docs/proposals/模型链路与规则链路对齐方案.md` 的 B）。
 *
 * <p>要修的现象：模型嘴上说「已经帮您选好了下午3点半」，JSON 里却没填 {@code selectedTime}，
 * 于是 Java 手上还是空的——下一轮又把「您想上午还是下午」问一遍，老人刚答过的话被退回给他。
 *
 * <p>为什么必须有这层，而不指望模型：{@code facts} 是模型链路唯一的事实源，模型漏一个字段
 * 没有任何东西去补。这层只补空、不覆盖模型结论，所以模型不变差、只是不再凭运气。
 *
 * <p>为什么不走 Spring：现有测试全是 {@code agent.model.enabled=false} 的规则链路，
 * 没有一条开模型模式；这条直接把假规划器塞进 {@link AgentRuntime}，把「模型回了什么」变成
 * 完全可控的输入，才钉得住「补了 / 没覆盖 / 抽不到」三种情形。
 */
class AgentRuntimeSpokenTimeFallbackTests {

    /** 假规划器：永远回一条「模型成功」的结论，facts 由用例给定。 */
    private static final class FakeModelPlanner implements ConversationPlanner {
        private final ExtractedFacts facts;

        FakeModelPlanner(ExtractedFacts facts) {
            this.facts = facts;
        }

        @Override
        public PlannerDecision plan(String message, AgentContext context, List<PlannerTool> allowedTools) {
            // source 必须以 MODEL 开头，才是「这一轮模型成功了」——否则 {@link AgentRuntime} 会走规则回退。
            return new PlannerDecision(PlannerActionType.ASK_USER, "UNKNOWN", null, Map.of(),
                    "好的，我记下了。", "SUPPORT", facts, "MODEL_TEST");
        }

        @Override
        public String mode() {
            return "MODEL_TEST";
        }
    }

    private static AgentRuntime runtime(ExtractedFacts modelFacts) {
        // toolPolicy / orchestrator 只为工具白名单与关键词路由服务，这条路径（模型回一句普通追问）
        // 走不到它们；传 null 是刻意的，避免为一次单元测试拼一整套 Spring 依赖。
        return new AgentRuntime(new FakeModelPlanner(modelFacts), new ToolRegistry(), new ToolPolicy(),
                new ActionValidator(), null, new RuleFactExtractor(null));
    }

    private static AgentContext context() {
        return new AgentContext("SELECT_PERIOD", "", LocalDate.now(), List.of());
    }

    private static ConversationState stateAtPeriodSelection() {
        ConversationState state = new ConversationState("c-spoken-time", "u-elder");
        state.taskStatus = ConversationState.TaskStatus.ACTIVE;
        state.stage = ConversationState.Stage.SELECT_PERIOD;
        return state;
    }

    @Test
    void theTimeTheModelOnlySaidOutLoudStillGetsRecorded() {
        // 模型一句时间线索都没给 —— 正是演示里那一轮。
        AgentRuntime.Outcome outcome = runtime(ExtractedFacts.empty())
                .plan("我要下午的三点半的", context(), stateAtPeriodSelection());

        assertThat(outcome.facts().selectedTime())
                .as("模型没说出口的时间，Java 从原话里补上，流程才不会又退回上一问")
                .isEqualTo(LocalTime.of(15, 30));
        assertThat(outcome.modelDriven()).as("补槽不改路线：这仍是模型驱动的一轮").isTrue();
    }

    @Test
    void aTimeTheModelAlreadyGaveIsNeverOverwritten() {
        ExtractedFacts modelSaidTenThirty = ExtractedFacts.empty().withSelectedTime(LocalTime.of(10, 30));

        AgentRuntime.Outcome outcome = runtime(modelSaidTenThirty)
                .plan("我要下午三点", context(), stateAtPeriodSelection());

        assertThat(outcome.facts().selectedTime())
                .as("只补空：模型已经给了结论，Java 不许覆盖它")
                .isEqualTo(LocalTime.of(10, 30));
    }

    @Test
    void aTimePreferenceFromTheModelAlsoCountsAsAClue() {
        ExtractedFacts afternoon = new ExtractedFacts("UNKNOWN", null, null, null, null, null, null,
                null, null, null, "AFTERNOON", null, null, null, null, null, null);

        AgentRuntime.Outcome outcome = runtime(afternoon)
                .plan("下午三点", context(), stateAtPeriodSelection());

        assertThat(outcome.facts().selectedTime())
                .as("模型给了「下午」这一档，就不再替它补一个更细的时刻")
                .isNull();
        assertThat(outcome.facts().timePreference()).isEqualTo("AFTERNOON");
    }

    @Test
    void noClockInTheSentenceLeavesTheFactsAlone() {
        AgentRuntime.Outcome outcome = runtime(ExtractedFacts.empty())
                .plan("一会儿再说吧", context(), stateAtPeriodSelection());

        assertThat(outcome.facts().selectedTime()).as("抽不到就原样返回，不猜").isNull();
    }
}
