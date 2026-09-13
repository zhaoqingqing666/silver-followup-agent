package com.team.silveragent.application;

import com.team.silveragent.application.care.CareCatalogRepository;

import com.team.silveragent.agent.AgentContext;
import com.team.silveragent.agent.AgentRole;
import com.team.silveragent.agent.ExtractedFacts;
import com.team.silveragent.agent.RuleFactExtractor;
import com.team.silveragent.agent.planning.ConversationPlanner;
import com.team.silveragent.agent.planning.PlannerActionType;
import com.team.silveragent.agent.planning.PlannerDecision;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRuntimeRoutingTests {
    @Test
    void workflowIntentCannotBeHiddenInsideAnAnswerAction() {
        ConversationPlanner planner = mock(ConversationPlanner.class);
        ExtractedFacts facts = facts("CREATE_FOLLOWUP");
        when(planner.plan(any(), any(), any())).thenReturn(new PlannerDecision(
                PlannerActionType.ANSWER, "CREATE_FOLLOWUP", null, Map.of(),
                "好的，我来办理。", "FOLLOWUP_FLOW", facts, "MODEL_PLANNER"));

        AgentRuntime runtime = new AgentRuntime(planner, new ToolRegistry(), new ToolPolicy(),
                new ActionValidator(), new AgentOrchestrator(emptyCatalog()), mock(RuleFactExtractor.class));
        ConversationState state = new ConversationState("conversation", "user-001");
        state.taskStatus = ConversationState.TaskStatus.ACTIVE;

        AgentRuntime.Outcome outcome = runtime.plan("我要办理", new AgentContext(
                "ASK_HOSPITAL", "复诊任务状态=ACTIVE", LocalDate.of(2026, 9, 10), List.of()), state);

        assertThat(outcome.route()).isEqualTo(AgentOrchestrator.Route.RESUME_TASK);
        assertThat(outcome.route()).isNotEqualTo(AgentOrchestrator.Route.DIRECT_ANSWER);
    }

    @Test
    void successfulModelDecisionIsNotOverwrittenByJavaKeywordRouting() {
        ConversationPlanner planner = mock(ConversationPlanner.class);
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(new PlannerDecision(
                PlannerActionType.ANSWER, "UNKNOWN", null, Map.of(),
                "您是在问整个复诊流程，还是到医院后的报到流程？",
                "FOLLOWUP_FLOW", facts("UNKNOWN"), "MODEL_PLANNER"));
        AgentRuntime runtime = runtime(planner);

        AgentRuntime.Outcome outcome = runtime.plan("办理流程是什么", context(),
                new ConversationState("conversation", "user-001"));

        assertThat(outcome.modelDriven()).isTrue();
        assertThat(outcome.route()).isEqualTo(AgentOrchestrator.Route.DIRECT_ANSWER);
        assertThat(outcome.replyDraft()).contains("整个复诊流程");
    }

    @Test
    void modelCanAskNaturallyWhileCollectingInformation() {
        ConversationPlanner planner = mock(ConversationPlanner.class);
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(new PlannerDecision(
                PlannerActionType.ASK_USER, "PROVIDE_INFORMATION", null, Map.of(),
                "您说的同济医院是在武汉还是上海？", "FOLLOWUP_FLOW",
                facts("PROVIDE_INFORMATION"), "MODEL_PLANNER"));

        AgentRuntime.Outcome outcome = runtime(planner).plan("我想去同济", context(),
                new ConversationState("conversation", "user-001"));

        assertThat(outcome.route()).isEqualTo(AgentOrchestrator.Route.DIRECT_ANSWER);
        assertThat(outcome.replyDraft()).contains("武汉还是上海");
    }

    @Test
    void modelChoosesCatalogSearchToolDirectly() {
        ConversationPlanner planner = mock(ConversationPlanner.class);
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(new PlannerDecision(
                PlannerActionType.CALL_READ_TOOL, "PROVIDE_INFORMATION", "hospital.search",
                Map.of("keyword", "市一"), null, "FOLLOWUP_FLOW",
                facts("PROVIDE_INFORMATION"), "MODEL_PLANNER"));

        AgentRuntime.Outcome outcome = runtime(planner).plan("去市一", context(),
                new ConversationState("conversation", "user-001"));

        assertThat(outcome.route()).isEqualTo(AgentOrchestrator.Route.RESOLVE_HOSPITAL);
        assertThat(outcome.proposedTool()).isEqualTo("hospital.search");
    }

    /**
     * 模型把确认交互误写成只读调用时，后果必须和写对了完全一样：一样的路由，
     * 而且**参数要原样带到取消链路上**。
     *
     * <p>这里曾经把 {@code proposedTools} 清空、只留下一个路由——那是本阶段要堵的第二个洞：
     * 参数一丢，取消链路就只能退回 Java 中文词表按原句关键词重猜范围，而那条路没走过任何
     * 契约校验。模型说了 {@code scope=DATE_RANGE} 却因为标签写错被当成「都取消」，就是这么来的。
     */
    @Test
    void confirmationOnlyToolMistakenForAReadCallKeepsItsStructuredArguments() {
        ConversationPlanner planner = mock(ConversationPlanner.class);
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(new PlannerDecision(
                PlannerActionType.CALL_READ_TOOL, "CANCEL_APPOINTMENT", "interaction.requestConfirmation",
                Map.of("scope", "DATE_RANGE", "date", "2026-09-12", "direction", "BEFORE"),
                "请确认是否取消这次预约？", "FOLLOWUP_FLOW",
                facts("CANCEL_APPOINTMENT"), "MODEL_PLANNER"));

        AgentRuntime.Outcome outcome = runtime(planner).plan("12号之前的都取消", context(),
                new ConversationState("conversation", "user-001"));

        assertThat(outcome.route()).isEqualTo(AgentOrchestrator.Route.CANCEL_EXISTING_APPOINTMENT);
        assertThat(outcome.route()).isNotEqualTo(AgentOrchestrator.Route.DIRECT_ANSWER);
        assertThat(outcome.proposedTool()).isEqualTo("interaction.requestConfirmation");
        // 动作类型归位成 CALL_CONFIRMATION_TOOL：这一轮该怎么走由它点名的工具决定，不由标签决定。
        assertThat(outcome.actionType()).isEqualTo(PlannerActionType.CALL_CONFIRMATION_TOOL);
        assertThat(outcome.hasContractCheckedToolCall()).isTrue();
        assertThat(outcome.proposedTools()).singleElement().satisfies(call -> {
            assertThat(call.toolName()).isEqualTo("interaction.requestConfirmation");
            assertThat(call.arguments()).containsEntry("scope", "DATE_RANGE")
                    .containsEntry("direction", "BEFORE");
        });
    }

    /** 误写的澄清调用同样走澄清通道，不因为动作类型写错就变成只读查询或一段没有按钮的回答。 */
    @Test
    void clarificationMistakenForAReadCallStillEntersTheClarificationRoute() {
        ConversationPlanner planner = mock(ConversationPlanner.class);
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(new PlannerDecision(
                PlannerActionType.CALL_READ_TOOL, "CANCEL_APPOINTMENT", "interaction.askClarification",
                Map.of("candidateTool", "appointment.queryMine", "question", "您想取消的是哪一条？"),
                null, "FOLLOWUP_FLOW", facts("CANCEL_APPOINTMENT"), "MODEL_PLANNER"));

        AgentRuntime.Outcome outcome = runtime(planner).plan("那些都不要了", context(),
                new ConversationState("conversation", "user-001"));

        assertThat(outcome.route()).isEqualTo(AgentOrchestrator.Route.ASK_CLARIFICATION);
        assertThat(outcome.actionType()).isEqualTo(PlannerActionType.CALL_CONFIRMATION_TOOL);
        // 澄清仍然不发凭据：候选要靠 Java 现查，这一轮不建卡。
        assertThat(outcome.proposedTools()).hasSize(1);
    }

    @Test
    void modelCallsTheStructuredCancellationConfirmationTool() {
        ConversationPlanner planner = mock(ConversationPlanner.class);
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(new PlannerDecision(
                PlannerActionType.CALL_CONFIRMATION_TOOL, "CANCEL_APPOINTMENT",
                "interaction.requestConfirmation",
                Map.of("scope", "DATE_RANGE", "date", "2026-09-12", "direction", "BEFORE"),
                "我按您刚说的范围重新确认。", "FOLLOWUP_FLOW",
                facts("CANCEL_APPOINTMENT"), "MODEL_PLANNER"));

        ConversationState state = new ConversationState("conversation", "user-001");
        state.stage = ConversationState.Stage.AWAITING_CONFIRMATION;
        state.pendingAction = "CANCEL_EXISTING";
        AgentRuntime.Outcome outcome = runtime(planner).plan("还是只取消12号之前的", context(), state);

        assertThat(outcome.route()).isEqualTo(AgentOrchestrator.Route.CANCEL_EXISTING_APPOINTMENT);
        assertThat(outcome.proposedTools()).singleElement().satisfies(call -> {
            assertThat(call.toolName()).isEqualTo("interaction.requestConfirmation");
            assertThat(call.arguments()).containsEntry("scope", "DATE_RANGE")
                    .containsEntry("direction", "BEFORE");
        });
    }

    /**
     * 确认回答的参数也要过同一份契约：{@code decision} 只能是 CONFIRM 或 DENY。
     *
     * <p>这条钉的是<b>结果</b>：不合法的确认回答一律回绝，永远不许落到 CONFIRM_PENDING 上。
     * 回绝本来就是这个结果，所以测试守的不是一次行为变更，而是这个不变量——尤其是挡住那个看着
     * 很自然、其实很危险的「统一」改法：这条路上「判罚之后回哪去」和澄清<b>必须不一样</b>。
     * 澄清参数不成立时是按 intent 回既有工作流的；这里照搬就会出事——intent 是
     * {@code CONFIRM_ACTION}、会话又停在等待确认上，{@code modelRoute} 会把它接成
     * CONFIRM_PENDING，那等于「一次参数写错的调用换来一次真的执行」。凭据原地不动才对，
     * 他按原来那个按钮重来一次就行。
     */
    @Test
    void aConfirmationResponseWithAnUnknownDecisionIsRefusedInsteadOfExecuting() {
        ConversationPlanner planner = mock(ConversationPlanner.class);
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(new PlannerDecision(
                PlannerActionType.CALL_CONFIRMATION_TOOL, "CONFIRM_ACTION",
                "interaction.respondConfirmation", Map.of("decision", "MAYBE"),
                null, "FOLLOWUP_FLOW", facts("CONFIRM_ACTION"), "MODEL_PLANNER"));

        ConversationState state = new ConversationState("conversation", "user-001");
        state.stage = ConversationState.Stage.AWAITING_CONFIRMATION;
        state.confirmationId = "java-owned-id";

        AgentRuntime.Outcome outcome = runtime(planner).plan("嗯……", context(), state);

        assertThat(outcome.route()).isEqualTo(AgentOrchestrator.Route.REFUSE_UNSUPPORTED_TOOL);
        assertThat(outcome.route()).isNotEqualTo(AgentOrchestrator.Route.CONFIRM_PENDING);
        assertThat(outcome.proposedTools()).isEmpty();
    }

    /** 连 decision 都没给：同上，明确回绝，绝不当成一次确认。 */
    @Test
    void aConfirmationResponseWithoutADecisionIsRefused() {
        ConversationPlanner planner = mock(ConversationPlanner.class);
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(new PlannerDecision(
                PlannerActionType.CALL_CONFIRMATION_TOOL, "CONFIRM_ACTION",
                "interaction.respondConfirmation", Map.of(),
                null, "FOLLOWUP_FLOW", facts("CONFIRM_ACTION"), "MODEL_PLANNER"));

        ConversationState state = new ConversationState("conversation", "user-001");
        state.stage = ConversationState.Stage.AWAITING_CONFIRMATION;
        state.confirmationId = "java-owned-id";

        AgentRuntime.Outcome outcome = runtime(planner).plan("那个", context(), state);

        assertThat(outcome.route()).isEqualTo(AgentOrchestrator.Route.REFUSE_UNSUPPORTED_TOOL);
        assertThat(outcome.proposedTools()).isEmpty();
    }

    /** 大小写由契约那一处统一归一化，模型写 confirm / deny 一样认。 */
    @Test
    void aConfirmationDecisionIsNormalizedBeforeItIsTurnedIntoARoute() {
        for (String decision : List.of("confirm", "Confirm", "CONFIRM")) {
            ConversationPlanner planner = mock(ConversationPlanner.class);
            when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
            when(planner.plan(any(), any(), any())).thenReturn(new PlannerDecision(
                    PlannerActionType.CALL_CONFIRMATION_TOOL, "CONFIRM_ACTION",
                    "interaction.respondConfirmation", Map.of("decision", decision),
                    null, "FOLLOWUP_FLOW", facts("CONFIRM_ACTION"), "MODEL_PLANNER"));

            ConversationState state = new ConversationState("conversation", "user-001");
            state.stage = ConversationState.Stage.AWAITING_CONFIRMATION;
            state.confirmationId = "java-owned-id";

            assertThat(runtime(planner).plan("确认", context(), state).route())
                    .as(decision).isEqualTo(AgentOrchestrator.Route.CONFIRM_PENDING);
        }
        for (String decision : List.of("deny", "DENY")) {
            ConversationPlanner planner = mock(ConversationPlanner.class);
            when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
            when(planner.plan(any(), any(), any())).thenReturn(new PlannerDecision(
                    PlannerActionType.CALL_CONFIRMATION_TOOL, "DENY_ACTION",
                    "interaction.respondConfirmation", Map.of("decision", decision),
                    null, "FOLLOWUP_FLOW", facts("DENY_ACTION"), "MODEL_PLANNER"));

            ConversationState state = new ConversationState("conversation", "user-001");
            state.stage = ConversationState.Stage.AWAITING_CONFIRMATION;
            state.confirmationId = "java-owned-id";

            assertThat(runtime(planner).plan("先不要", context(), state).route())
                    .as(decision).isEqualTo(AgentOrchestrator.Route.DENY_PENDING);
        }
    }

    @Test
    void modelConfirmationResponseUsesTheCurrentCardWithoutSupplyingItsId() {
        ConversationPlanner planner = mock(ConversationPlanner.class);
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(new PlannerDecision(
                PlannerActionType.CALL_CONFIRMATION_TOOL, "CONFIRM_ACTION",
                "interaction.respondConfirmation", Map.of("decision", "CONFIRM"),
                null, "FOLLOWUP_FLOW", facts("CONFIRM_ACTION"), "MODEL_PLANNER"));

        ConversationState state = new ConversationState("conversation", "user-001");
        state.stage = ConversationState.Stage.AWAITING_CONFIRMATION;
        state.confirmationId = "java-owned-id";
        AgentRuntime.Outcome outcome = runtime(planner).plan("确认", context(), state);

        assertThat(outcome.route()).isEqualTo(AgentOrchestrator.Route.CONFIRM_PENDING);
        assertThat(outcome.proposedTools().get(0).arguments()).doesNotContainKey("confirmationId");
    }

    /**
     * 模型编了一个根本没注册的写工具，但 intent 是我们支持的写业务：工具绝不能被执行，
     * 也绝不能静默变成一句没有卡片的回答。忽略工具名，按 intent 回到既有 Java 工作流——
     * 取消在那里仍要被翻译成确认卡，而不是在这里被直接执行。
     */
    @Test
    void fabricatedWriteToolWithARealWriteIntentFallsBackToTheJavaWorkflow() {
        ConversationPlanner planner = mock(ConversationPlanner.class);
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(new PlannerDecision(
                PlannerActionType.CALL_READ_TOOL, "CANCEL_APPOINTMENT", "appointment.cancelDirectly",
                Map.of("appointmentId", "a-001"), "已经帮您取消了。", "FOLLOWUP_FLOW",
                facts("CANCEL_APPOINTMENT"), "MODEL_PLANNER"));

        AgentRuntime.Outcome outcome = runtime(planner).plan("把之前那个取消掉", context(),
                new ConversationState("conversation", "user-001"));

        assertThat(outcome.route()).isEqualTo(AgentOrchestrator.Route.CANCEL_EXISTING_APPOINTMENT);
        assertThat(outcome.route()).isNotEqualTo(AgentOrchestrator.Route.DIRECT_ANSWER);
        assertThat(outcome.proposedTool()).isNull();
        assertThat(outcome.proposedTools()).isEmpty();
    }

    /**
     * 模型编的工具执行不了，它给的 intent 也归不到任何业务链路（UNKNOWN）：由 Java 明确回绝。
     * 关键是既不能执行那个工具，也不能掉进 DIRECT_ANSWER——那条路会 pauseActiveTask，
     * 把正在办理的预约流程停掉。这里只表示“这条工具我不认”，任务状态原地不动。
     */
    @Test
    void fabricatedToolWithAnUnmappableIntentIsRefusedInsteadOfAnsweredSilently() {
        ConversationPlanner planner = mock(ConversationPlanner.class);
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(new PlannerDecision(
                PlannerActionType.CALL_READ_TOOL, "UNKNOWN", "appointment.cancelDirectly",
                Map.of("appointmentId", "a-001"), "已经帮您取消了。", "FOLLOWUP_FLOW",
                facts("UNKNOWN"), "MODEL_PLANNER"));

        AgentRuntime.Outcome outcome = runtime(planner).plan("随便弄一下那个东西", context(),
                new ConversationState("conversation", "user-001"));

        assertThat(outcome.route()).isEqualTo(AgentOrchestrator.Route.REFUSE_UNSUPPORTED_TOOL);
        assertThat(outcome.route()).isNotEqualTo(AgentOrchestrator.Route.DIRECT_ANSWER);
        assertThat(outcome.proposedTool()).isNull();
        assertThat(outcome.proposedTools()).isEmpty();
    }

    /**
     * 工具真实存在，但当前角色没权限用它（老人端没有 care.timeline）。同样不执行、不静默，
     * 按 intent 回到老人自己的只读查询——权限判定不能被“回退到工作流”绕过。
     */
    @Test
    void toolOutsideTheActorRoleNeverReachesTheExecutionList() {
        ConversationPlanner planner = mock(ConversationPlanner.class);
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(new PlannerDecision(
                PlannerActionType.CALL_READ_TOOL, "QUERY_APPOINTMENTS", "care.timeline",
                Map.of(), null, "FOLLOWUP_FLOW", facts("QUERY_APPOINTMENTS"), "MODEL_PLANNER"));

        ConversationState state = new ConversationState("conversation", "user-001");
        state.actorRole = AgentRole.ELDER;

        AgentRuntime.Outcome outcome = runtime(planner).plan("看看最近的动态", context(), state);

        assertThat(outcome.route()).isEqualTo(AgentOrchestrator.Route.QUERY_MY_APPOINTMENTS);
        assertThat(outcome.proposedTool()).isNull();
        assertThat(outcome.proposedTools()).isEmpty();
    }

    /**
     * 备忘 / 健康数值 / 发周报这三件事必须落库或对外发消息，不能被模型的一句 ANSWER 带过去。
     * 实测过：不挡住的话，模型会对老人说“我给您记一个提醒”，而库里一条记录都没有。
     */
    @Test
    void memoryAndHealthIntentsAreNotSwallowedByAnAnswerAction() {
        for (String intent : List.of("MANAGE_MEMO", "RECORD_HEALTH_VALUE", "SEND_HEALTH_REPORT")) {
            ConversationPlanner planner = mock(ConversationPlanner.class);
            when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
            when(planner.plan(any(), any(), any())).thenReturn(new PlannerDecision(
                    PlannerActionType.ANSWER, intent, null, Map.of(),
                    "好的，我给您记下了。", "SMALL_TALK", facts(intent), "MODEL_PLANNER"));

            AgentRuntime.Outcome outcome = runtime(planner).plan("明早八点提醒我吃药", context(),
                    new ConversationState("conversation", "user-001"));

            assertThat(outcome.route()).as(intent)
                    .isEqualTo(AgentOrchestrator.Route.valueOf(intent));
            assertThat(outcome.route()).as(intent).isNotEqualTo(AgentOrchestrator.Route.DIRECT_ANSWER);
        }
    }

    /** 不相干的 intent 仍旧走原来的“直接回答”，别把日常三类扩成万能兜底。 */
    @Test
    void unrelatedIntentStillReachesDirectAnswer() {
        ConversationPlanner planner = mock(ConversationPlanner.class);
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(new PlannerDecision(
                PlannerActionType.ANSWER, "SMALL_TALK", null, Map.of(),
                "今天天气不错。", "SMALL_TALK", facts("SMALL_TALK"), "MODEL_PLANNER"));

        AgentRuntime.Outcome outcome = runtime(planner).plan("今天天气真好", context(),
                new ConversationState("conversation", "user-001"));

        assertThat(outcome.route()).isEqualTo(AgentOrchestrator.Route.DIRECT_ANSWER);
    }

    /**
     * 问药走真实知识库查询，不能由模型凭记忆回答——它的记忆里没有我们的药品库，
     * 说出来的用法用量也没人核对过。这里断言它被接到 drug.queryKnowledge 上。
     */
    @Test
    void drugQuestionIsRoutedToTheDrugKnowledgeTool() {
        ConversationPlanner planner = mock(ConversationPlanner.class);
        when(planner.mode()).thenReturn("MODEL_PLANNER_WITH_RULE_FALLBACK");
        when(planner.plan(any(), any(), any())).thenReturn(new PlannerDecision(
                PlannerActionType.CALL_READ_TOOL, "QUERY_DRUG", "drug.queryKnowledge",
                Map.of("drugName", "阿司匹林"), null, "FOLLOWUP_FLOW",
                facts("QUERY_DRUG"), "MODEL_PLANNER"));

        AgentRuntime.Outcome outcome = runtime(planner).plan("阿司匹林是干嘛的", context(),
                new ConversationState("conversation", "user-001"));

        assertThat(outcome.route()).isEqualTo(AgentOrchestrator.Route.QUERY_DRUG_KNOWLEDGE);
        assertThat(outcome.proposedTool()).isEqualTo("drug.queryKnowledge");
    }

    private AgentRuntime runtime(ConversationPlanner planner) {
        return new AgentRuntime(planner, new ToolRegistry(), new ToolPolicy(),
                new ActionValidator(), new AgentOrchestrator(emptyCatalog()), mock(RuleFactExtractor.class));
    }

    private AgentContext context() {
        return new AgentContext("ASK_HOSPITAL", "预约草稿缺失字段=[医院, 科室]",
                LocalDate.of(2026, 9, 10), List.of());
    }

    /** 页面指令快速通道只用到医院/科室名做筛选条件判断，这个用例不涉及任何筛选。 */
    private CareCatalogRepository emptyCatalog() {
        CareCatalogRepository catalog = mock(CareCatalogRepository.class);
        when(catalog.hospitalNames()).thenReturn(List.of());
        when(catalog.departmentNames()).thenReturn(List.of());
        return catalog;
    }

    private ExtractedFacts facts(String intent) {
        return new ExtractedFacts(intent, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }
}
