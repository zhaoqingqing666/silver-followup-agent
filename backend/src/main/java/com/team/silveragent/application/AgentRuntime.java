package com.team.silveragent.application;

import com.team.silveragent.agent.AgentContext;
import com.team.silveragent.agent.ExtractedFacts;
import com.team.silveragent.agent.RuleFactExtractor;
import com.team.silveragent.agent.planning.ConversationPlanner;
import com.team.silveragent.agent.planning.PlannerActionType;
import com.team.silveragent.agent.planning.PlannerDecision;
import com.team.silveragent.agent.planning.PlannerToolCall;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一轮智能体运行时：调用规划模型，执行工具白名单和回答权限检查，再交给工作流。
 * 写操作从不在这里执行，只会被转换为原有 Java 确认流程。
 */
@Component
final class AgentRuntime {
    record Outcome(AgentOrchestrator.Route route, ExtractedFacts facts, String replyDraft,
                   String dialogueMode, String plannerSource, String proposedTool,
                   List<PlannerToolCall> proposedTools, PlannerActionType actionType,
                   String intent, boolean modelDriven) {
        /**
         * 这一轮有一个<b>过了契约校验</b>的工具调用，可以照它的意思办。
         *
         * <p>名字说的是「契约查过了」，不只是「有东西」。差别很要紧：调用方拿它决定要不要再跑一遍
         * 关键词兜底，而兜底会按原句里的「都取消」「最近」重猜一次范围——所以只有在
         * 「参数已经判过一遍、这次调用确实能用」的时候才允许跳过兜底。
         *
         * <p>「非空 ⇒ 已校验」是产品路径给的性质，不是这个 record 自己保证的：只有
         * {@link #modelProposal} 会往 {@code proposedTools} 里放东西，而它放之前必须过
         * {@link ToolContract}——三条交互通道（只读 / 确认 / 澄清）各查一次，写错标签的
         * 那条也一样查。所以这里读「非空」就是在读「已校验」。
         */
        boolean hasContractCheckedToolCall() {
            return modelDriven && proposedTools != null && !proposedTools.isEmpty();
        }
    }

    private final ConversationPlanner planner;
    private final ToolRegistry registry;
    private final ToolPolicy toolPolicy;
    private final ActionValidator validator;
    private final AgentOrchestrator orchestrator;
    private final RuleFactExtractor ruleExtractor;

    AgentRuntime(ConversationPlanner planner, ToolRegistry registry, ToolPolicy toolPolicy,
                 ActionValidator validator, AgentOrchestrator orchestrator, RuleFactExtractor ruleExtractor) {
        this.planner = planner;
        this.registry = registry;
        this.toolPolicy = toolPolicy;
        this.validator = validator;
        this.orchestrator = orchestrator;
        this.ruleExtractor = ruleExtractor;
    }

    Outcome plan(String message, AgentContext context, ConversationState state) {
        if (modelAvailable()) {
            PlannerDecision proposal = planner.plan(message, context, registry.plannerTools(state.actorRole));
            if (proposal.source().startsWith("MODEL")) return modelProposal(proposal, state);
            // 模型本轮失败时才退回旧规则路径；不能把旧关键词规则叠加在成功的模型结论之上。
            return legacyProposal(message, context, state, proposal);
        }
        return legacyProposal(message, context, state, null);
    }

    Outcome continueAfterTools(String originalMessage, AgentContext context, ConversationState state,
                               String toolResults) {
        if (!modelAvailable()) {
            return new Outcome(AgentOrchestrator.Route.CURRENT_FLOW, ExtractedFacts.empty(), null,
                    "FOLLOWUP_FLOW", "TOOL_RESULT_FALLBACK", null, List.of(),
                    PlannerActionType.ANSWER, "UNKNOWN", false);
        }
        PlannerDecision proposal = planner.continueAfterTools(
                originalMessage, context, registry.plannerTools(state.actorRole), toolResults);
        if (proposal.source().startsWith("MODEL")) return modelProposal(proposal, state);
        return new Outcome(AgentOrchestrator.Route.CURRENT_FLOW, ExtractedFacts.empty(), null,
                "FOLLOWUP_FLOW", proposal.source(), null, List.of(),
                PlannerActionType.ANSWER, "UNKNOWN", false);
    }

    boolean isModelReadToolOutcome(Outcome outcome) {
        return outcome != null && outcome.modelDriven()
                && (outcome.actionType() == PlannerActionType.CALL_READ_TOOL
                || outcome.actionType() == PlannerActionType.CALL_READ_TOOLS)
                && outcome.proposedTools() != null && !outcome.proposedTools().isEmpty();
    }

    private Outcome legacyProposal(String message, AgentContext context, ConversationState state,
                                   PlannerDecision existingProposal) {
        // 有确认卡时先让“同意/拒绝当前卡”按上下文解释；不能让“取消预约”关键词把它
        // 抢回候选查询。模型不可用时仍由 orchestrator 的确认阶段规则安全兜底。
        AgentOrchestrator.Route fastRoute = state.stage == ConversationState.Stage.AWAITING_CONFIRMATION
                ? null : orchestrator.deterministicOverride(message);
        if (fastRoute == AgentOrchestrator.Route.QUERY_CARE_GUIDE) {
            List<PlannerToolCall> calls = new ArrayList<>();
            calls.add(new PlannerToolCall("careGuide.search", java.util.Map.of("query", message)));
            if (containsAny(message, "材料", "带什么", "准备什么")) {
                calls.add(new PlannerToolCall("material.checklist", java.util.Map.of()));
                return new Outcome(AgentOrchestrator.Route.MULTI_READ_TOOLS, ExtractedFacts.empty(), null,
                        "FOLLOWUP_FLOW", "RULE_FAST_PATH", null, calls,
                        PlannerActionType.CALL_READ_TOOLS, "EXPLAIN_PROCESS", false);
            }
            return new Outcome(fastRoute, ExtractedFacts.empty(), null, "FOLLOWUP_FLOW",
                    "RULE_FAST_PATH", "careGuide.search", calls,
                    PlannerActionType.CALL_READ_TOOL, "EXPLAIN_PROCESS", false);
        }
        if (fastRoute == AgentOrchestrator.Route.CANCEL_EXISTING_APPOINTMENT) {
            ExtractedFacts guardedFacts = ruleExtractor.extract(message, context);
            return new Outcome(fastRoute, guardedFacts, null, "FOLLOWUP_FLOW",
                    "RULE_GUARD", null, List.of(), PlannerActionType.PROPOSE_WORKFLOW_ACTION,
                    guardedFacts.intent(), false);
        }
        // 页面指令（看地图、院内怎么走）是纯只读跳转，直接走确定性规则，不调用规划模型：
        // 既省一次模型调用，也避免模型漂移把老人带到别的页面。安全判定仍在调用方之后照常执行。
        if (orchestrator.isPageCommand(message)) {
            ExtractedFacts pageFacts = ruleExtractor.extract(message, context);
            return new Outcome(fastRoute, pageFacts, null, "FOLLOWUP_FLOW",
                    "RULE_FAST_PATH", null, List.of(), PlannerActionType.CALL_READ_TOOL,
                    pageFacts.intent(), false);
        }
        PlannerDecision proposal = existingProposal == null
                ? planner.plan(message, context, registry.plannerTools(state.actorRole)) : existingProposal;
        ExtractedFacts facts = proposal.facts();

        AgentOrchestrator.Route deterministic = state.stage == ConversationState.Stage.AWAITING_CONFIRMATION
                ? null : orchestrator.deterministicOverride(message);
        if (deterministic != null
                && !(deterministic == AgentOrchestrator.Route.QUERY_CARE_GUIDE
                && proposal.toolCalls().size() > 1)) {
            return new Outcome(deterministic, facts, null, "FOLLOWUP_FLOW",
                    proposal.source(), proposal.toolName(), proposal.toolCalls(), proposal.actionType(),
                    proposal.intent(), false);
        }

        if (proposal.actionType() == PlannerActionType.CALL_READ_TOOL
                || proposal.actionType() == PlannerActionType.CALL_READ_TOOLS) {
            List<PlannerToolCall> approved = new ArrayList<>();
            for (PlannerToolCall call : proposal.toolCalls()) {
                ToolRegistry.RegisteredTool tool = registry.find(call.toolName()).orElse(null);
                if (toolPolicy.evaluate(state.actorRole, tool) != ToolPolicy.Decision.ALLOW) {
                    return workflow(message, proposal, state);
                }
                approved.add(call);
            }
            if (approved.size() == 1) {
                ToolRegistry.RegisteredTool tool = registry.find(approved.get(0).toolName()).orElseThrow();
                return new Outcome(tool.route(), facts, null, "FOLLOWUP_FLOW",
                        proposal.source(), approved.get(0).toolName(), approved, proposal.actionType(),
                        proposal.intent(), false);
            }
            if (approved.size() > 1) {
                boolean supportedBatch = approved.stream().allMatch(call ->
                        "careGuide.search".equals(call.toolName()) || "material.checklist".equals(call.toolName()));
                if (!supportedBatch) return workflow(message, proposal, state);
                return new Outcome(AgentOrchestrator.Route.MULTI_READ_TOOLS, facts, null,
                        "FOLLOWUP_FLOW", proposal.source(), null, approved, proposal.actionType(),
                        proposal.intent(), false);
            }
            // 非白名单或写工具不能由模型直接执行，退回 Java 业务路由。
            return workflow(message, proposal, state);
        }

        boolean safeClarification = proposal.actionType() == PlannerActionType.ASK_USER
                && ("UNKNOWN".equals(proposal.intent())
                || "SUPPORT".equals(proposal.dialogueMode())
                || "SMALL_TALK".equals(proposal.dialogueMode()));
        // ANSWER 只允许用于真正的会话型意图。模型即使误把 CREATE_FOLLOWUP / RESUME_TASK
        // 标成 ANSWER，也必须进入 Java 状态审核，不能用一段自然回复绕过工作流。
        boolean conversationalAnswer = proposal.actionType() == PlannerActionType.ANSWER
                && isConversationalIntent(proposal.intent());
        if ((conversationalAnswer || safeClarification)
                && validator.safeDirectReply(proposal.replyDraft())) {
            return new Outcome(AgentOrchestrator.Route.DIRECT_ANSWER, facts, proposal.replyDraft(),
                    normalizeDialogueMode(proposal.dialogueMode()), proposal.source(), null, List.of(),
                    proposal.actionType(), proposal.intent(), false);
        }
        return workflow(message, proposal, state);
    }

    /**
     * 模型可用时由模型动作直接决定本轮路线。这里不再运行关键词快速路由，也不再让
     * AgentOrchestrator 根据当前 Stage 覆盖模型已经给出的自然语言判断。
     */
    private Outcome modelProposal(PlannerDecision proposal, ConversationState state) {
        ExtractedFacts facts = proposal.facts();
        if (proposal.actionType() == PlannerActionType.CALL_CONFIRMATION_TOOL) {
            if (proposal.toolCalls().size() != 1) {
                return refuse(proposal);
            }
            return interactionProposal(proposal, state, proposal.toolCalls().get(0));
        }
        // 确认回答优先于普通 ANSWER/ASK_USER：模型已经结合确认卡理解为同意或拒绝时，
        // 不能只把 replyDraft 说给用户听而不推进 confirmationId。
        if (state.stage == ConversationState.Stage.AWAITING_CONFIRMATION) {
            if ("CONFIRM_ACTION".equals(proposal.intent())) {
                return outcome(AgentOrchestrator.Route.CONFIRM_PENDING, proposal, null, List.of(), true);
            }
            if ("DENY_ACTION".equals(proposal.intent())) {
                return outcome(AgentOrchestrator.Route.DENY_PENDING, proposal, null, List.of(), true);
            }
        }
        if (proposal.actionType() == PlannerActionType.CALL_READ_TOOL
                || proposal.actionType() == PlannerActionType.CALL_READ_TOOLS) {
            List<PlannerToolCall> approved = new ArrayList<>();
            boolean rejectedTool = false;
            for (PlannerToolCall call : proposal.toolCalls()) {
                ToolRegistry.RegisteredTool tool = registry.find(call.toolName()).orElse(null);
                ToolPolicy.Decision decision = toolPolicy.evaluate(state.actorRole, tool);
                if (decision == ToolPolicy.Decision.ALLOW) {
                    // 类型、必填、枚举、字段组合在这里统一判一次。参数缺项或取值非法就不执行这次调用——
                    // 拿一份缺字段的参数去查库，只会得到一份看起来像「没查到」的假结果。
                    // 也绝不替它猜补：按它自己给的 intent 回到既有 Java 工作流。
                    if (ToolContract.check(tool, call).isPresent()) return rejectedByIntent(proposal, state);
                    approved.add(call);
                } else if (decision == ToolPolicy.Decision.DENY_SIDE_EFFECT && tool != null) {
                    // 模型偶尔会把确认 / 澄清交互误写成 CALL_READ_TOOL。它仍然不能自动执行——上面那次
                    // evaluate 只认 READ_ONLY，这一步没变；但也不能静默变成一段没有卡片的回答，
                    // 更不能因为动作类型写错就把这次调用丢掉：丢掉了，取消链路就只能退回 Java
                    // 中文词表按原句关键词重猜范围——那是一条没走过任何契约校验的路，正好绕开
                    // 这一轮本该判的东西。按它真正点名的工具走同一条交互通道，参数原样带上。
                    return interactionProposal(proposal, state, call);
                } else {
                    rejectedTool = true;
                }
            }
            if (approved.size() == 1) {
                ToolRegistry.RegisteredTool tool = registry.find(approved.get(0).toolName()).orElseThrow();
                return outcome(tool.route(), proposal, approved.get(0).toolName(), approved, true);
            }
            if (approved.size() > 1) {
                List<PlannerToolCall> limited = approved.stream().limit(3).toList();
                boolean batchSafe = limited.stream().allMatch(call -> containsAny(call.toolName(),
                        "careGuide.search", "material.checklist", "hospital.list",
                        "department.list", "appointment.queryMine"));
                if (batchSafe) {
                    return outcome(AgentOrchestrator.Route.MULTI_READ_TOOLS, proposal, null, limited, true);
                }
                // 有依赖的查询不能并行：先执行模型列出的第一个工具，下一轮模型可根据结果继续。
                PlannerToolCall first = limited.get(0);
                return outcome(registry.find(first.toolName()).orElseThrow().route(), proposal,
                        first.toolName(), List.of(first), true);
            }
            if (rejectedTool) return rejectedByIntent(proposal, state);
            return outcome(AgentOrchestrator.Route.DIRECT_ANSWER, proposal, null, List.of(), true);
        }

        // 备忘 / 健康数值 / 发周报要先于下面的「直接回答」判定：这三件事都要落库或对外发消息，
        // 不能让模型用一句 ANSWER 就带过去——实测过，那样它会回“我给您记一个提醒”而库里一条都没有。
        // 模型只需要认出“这句话属于这三件事”，填槽仍由 Java 做。
        AgentOrchestrator.Route dailyRoute = dailyRoute(proposal.intent());
        if (dailyRoute != null) return outcome(dailyRoute, proposal, null, List.of(), true);

        // 办理进行中时，模型追问下一句（“您想去哪家医院？”）用的是 ASK_USER，这不是插话闲聊。
        // 走 DIRECT_ANSWER 会把正在办的预约暂停掉，草稿、阶段和按钮也会停在原地，
        // 所以流程内的追问必须回到工作流，由 Java 记录这轮问到了什么。冷启动的追问不受影响。
        if (proposal.actionType() == PlannerActionType.ASK_USER
                && "FOLLOWUP_FLOW".equals(proposal.dialogueMode())
                && taskInProgress(state)
                && validator.safeDirectReply(proposal.replyDraft())) {
            return outcome(AgentOrchestrator.Route.CURRENT_FLOW, proposal, null, List.of(), true);
        }
        if ((proposal.actionType() == PlannerActionType.ANSWER
                || proposal.actionType() == PlannerActionType.ASK_USER)
                && validator.safeDirectReply(proposal.replyDraft())) {
            return outcome(AgentOrchestrator.Route.DIRECT_ANSWER, proposal, null, List.of(), true);
        }
        AgentOrchestrator.Route workflowRoute = modelRoute(proposal.intent(), state);
        if (workflowRoute != null) return outcome(workflowRoute, proposal, null, List.of(), true);
        // 模型未给可用回答时只进入草稿更新，不再运行第二套关键词意图判断。
        return outcome(AgentOrchestrator.Route.CURRENT_FLOW, proposal, null, List.of(), true);
    }

    /**
     * 一次交互工具调用（确认卡 / 澄清）该怎么走。两条通道各有自己的判罚入口，不能互相借。
     *
     * <p>模型把这类工具误写成 {@code CALL_READ_TOOL} 时走的也是这里：动作类型是它写错的标签，
     * 工具名和参数才是真的，所以两边的后果必须一致——路由、参数、判罚都不因为写错而改变。
     *
     * <p>{@code requestConfirmation} 的参数够不够用<b>不在这里判</b>：取消链路拿的是同一份
     * {@link ToolContract} 判罚（{@link #rejection}），它要靠那条判罚决定「转为澄清」还是
     * 「照范围出卡」。判两次就会有两套口径，而「范围说不清」正好是这两套口径最容易分叉的地方。
     *
     * <p>{@code respondConfirmation} 反过来，<b>必须在这里判</b>：它没有下游可依赖——它直接翻成
     * 确认或拒绝，再往下就是执行。所以三条通道里只有它在这里查参数，而且查完只回绝、不回退。
     */
    private Outcome interactionProposal(PlannerDecision proposal, ConversationState state,
                                        PlannerToolCall call) {
        ToolRegistry.RegisteredTool tool = registry.find(call.toolName()).orElse(null);
        if ("interaction.askClarification".equals(call.toolName())) {
            // 澄清走最窄的第三条通道：不自动执行，也拿不到确认凭据。判罚和确认通道分开，
            // 免得「问一句」顺手借到一张卡和 confirmationId——那是全部写操作的唯一钥匙。
            if (toolPolicy.evaluateClarification(state.actorRole, tool) != ToolPolicy.Decision.ALLOW) {
                return refuse(proposal);
            }
            if (ToolContract.check(tool, call).isPresent()) {
                // 参数不成立就不算一次澄清，按 intent 回既有工作流，不猜补、不执行。
                return rejectedByIntent(proposal, state);
            }
            return asInteraction(tool.route(), proposal, call.toolName(), List.of(call));
        }
        if (toolPolicy.evaluateConfirmation(state.actorRole, tool) != ToolPolicy.Decision.ALLOW) {
            return refuse(proposal);
        }
        if ("interaction.requestConfirmation".equals(call.toolName())) {
            return asInteraction(tool.route(), proposal, call.toolName(), List.of(call));
        }
        if ("interaction.respondConfirmation".equals(call.toolName())) {
            // 和另外两条通道同一份判罚：decision 只能是 CONFIRM 或 DENY，缺了、写成别的值都是
            // 「这次调用用不了」。归一化也走同一处（模型写 confirm 也认），不再手写 toUpperCase。
            if (ToolContract.check(tool, call).isPresent()) {
                // 但这里**不能**像澄清那样按 intent 回退。intent 是 CONFIRM_ACTION 时，
                // 回退会被 modelRoute 接成 CONFIRM_PENDING ——那等于「一次参数写错的调用，
                // 换来一次真的执行」。参数不成立就明确回绝，凭据还在，他按原来那个按钮重来一次
                // 就行；一次写坏的调用不该产生任何执行后果。
                return refuse(proposal);
            }
            // 上面那次校验已经保证 decision 只会是这两个值之一，这里只是把它翻成路由。
            String decision = ToolContract.normalize(tool.definition().arguments(), call.arguments())
                    .getOrDefault("decision", "");
            AgentOrchestrator.Route route = "CONFIRM".equals(decision)
                    ? AgentOrchestrator.Route.CONFIRM_PENDING
                    : AgentOrchestrator.Route.DENY_PENDING;
            return asInteraction(route, proposal, call.toolName(), List.of(call));
        }
        return refuse(proposal);
    }

    private Outcome refuse(PlannerDecision proposal) {
        return outcome(AgentOrchestrator.Route.REFUSE_UNSUPPORTED_TOOL, proposal, null, List.of(), true);
    }

    /**
     * 交互工具调用的统一出口。
     *
     * <p>这里把 {@code actionType} 归成 {@link PlannerActionType#CALL_CONFIRMATION_TOOL}：
     * 模型若把它写成了 {@code CALL_READ_TOOL}，那是标签写错了——这一轮该怎么走由**它点名的工具**
     * 决定，不由标签决定。归位之后，「这一轮是不是一次交互调用」在下游只有一处判据
     * （取消链路与只读工具循环各看一次），不会出现「同一个调用在两处被判成两种东西」。
     */
    private Outcome asInteraction(AgentOrchestrator.Route route, PlannerDecision proposal,
                                  String tool, List<PlannerToolCall> tools) {
        return new Outcome(route, proposal.facts(), proposal.replyDraft(),
                normalizeDialogueMode(proposal.dialogueMode()), proposal.source(), tool, tools,
                PlannerActionType.CALL_CONFIRMATION_TOOL, proposal.intent(), true);
    }

    /**
     * 模型这个工具调用用不了（没注册、角色没权限、是个写工具，或者参数过不了契约校验）时的统一出口。
     *
     * <p>绝不执行它，也绝不静默降成一段没有卡片的回答：先忽略工具名，按它自己给的 intent 回到既有
     * Java 工作流——写操作在那里仍会被翻译成确认卡，而不是被这里直接执行。intent 也不可信
     * （UNKNOWN 或没映射）时明确回绝，不复用模型话术、不动任务状态。
     */
    private Outcome rejectedByIntent(PlannerDecision proposal, ConversationState state) {
        AgentOrchestrator.Route byIntent = dailyRoute(proposal.intent());
        if (byIntent == null) byIntent = modelRoute(proposal.intent(), state);
        if (byIntent != null) return outcome(byIntent, proposal, null, List.of(), true);
        return outcome(AgentOrchestrator.Route.REFUSE_UNSUPPORTED_TOOL, proposal, null, List.of(), true);
    }

    /** 复诊预约流程之外的日常三类：备忘、健康数值、把记录发给家属。 */
    private AgentOrchestrator.Route dailyRoute(String intent) {
        if (intent == null) return null;
        return switch (intent) {
            case "MANAGE_MEMO" -> AgentOrchestrator.Route.MANAGE_MEMO;
            case "RECORD_HEALTH_VALUE" -> AgentOrchestrator.Route.RECORD_HEALTH_VALUE;
            case "SEND_HEALTH_REPORT" -> AgentOrchestrator.Route.SEND_HEALTH_REPORT;
            // 代他人办理时给长辈留提醒，和老人“记一条提醒”是两件事：主语和落点都不同。
            case "REMIND_ELDER" -> AgentOrchestrator.Route.REMIND_ELDER;
            default -> null;
        };
    }

    private AgentOrchestrator.Route modelRoute(String intent, ConversationState state) {
        if (intent == null) return null;
        if (state.stage == ConversationState.Stage.AWAITING_CONFIRMATION) {
            if ("CONFIRM_ACTION".equals(intent)) return AgentOrchestrator.Route.CONFIRM_PENDING;
            if ("DENY_ACTION".equals(intent)) return AgentOrchestrator.Route.DENY_PENDING;
        }
        if (state.stage == ConversationState.Stage.CONFLICT && "CONFIRM_ACTION".equals(intent)) {
            return AgentOrchestrator.Route.KEEP_CONFLICT;
        }
        return switch (intent) {
            case "CREATE_FOLLOWUP" -> taskInProgress(state)
                    ? AgentOrchestrator.Route.RESUME_TASK : AgentOrchestrator.Route.RESTART_TASK;
            case "RESTART_TASK" -> AgentOrchestrator.Route.RESTART_TASK;
            case "RESUME_TASK" -> taskInProgress(state)
                    ? AgentOrchestrator.Route.RESUME_TASK : null;
            case "CANCEL_TASK" -> AgentOrchestrator.Route.CANCEL_CURRENT_TASK;
            case "CANCEL_APPOINTMENT" -> AgentOrchestrator.Route.CANCEL_EXISTING_APPOINTMENT;
            case "QUERY_APPOINTMENTS" -> AgentOrchestrator.Route.QUERY_MY_APPOINTMENTS;
            case "EXPLAIN_PROCESS" -> AgentOrchestrator.Route.QUERY_CARE_GUIDE;
            case "QUERY_HOSPITALS", "QUERY_HOSPITAL_INFO" -> AgentOrchestrator.Route.QUERY_HOSPITALS;
            case "QUERY_DEPARTMENTS" -> AgentOrchestrator.Route.QUERY_DEPARTMENTS;
            case "QUERY_AVAILABLE_SLOTS" -> AgentOrchestrator.Route.QUERY_AVAILABLE_SLOTS;
            case "QUERY_NEARBY_SLOTS" -> AgentOrchestrator.Route.QUERY_NEARBY_SLOTS;
            case "CHECK_CONFLICT" -> AgentOrchestrator.Route.CHECK_CONFLICT;
            case "CHECK_DUPLICATE" -> AgentOrchestrator.Route.CHECK_DUPLICATE;
            case "REQUEST_RECOMMENDATION" -> AgentOrchestrator.Route.RECOMMEND_HOSPITAL;
            case "ASK_MATERIALS" -> AgentOrchestrator.Route.ASK_MATERIALS;
            case "ASK_TRAVEL_ROUTE" -> AgentOrchestrator.Route.QUERY_TRAVEL_GUIDE;
            case "ASK_LOCATION_GUIDE" -> AgentOrchestrator.Route.QUERY_LOCATION_GUIDE;
            case "CHANGE_HOSPITAL" -> AgentOrchestrator.Route.CHANGE_HOSPITAL;
            case "CHANGE_DEPARTMENT" -> AgentOrchestrator.Route.CHANGE_DEPARTMENT;
            case "CHANGE_DATE" -> AgentOrchestrator.Route.CHANGE_DATE;
            case "CHANGE_TIME" -> AgentOrchestrator.Route.CHANGE_TIME;
            case "QUERY_DRUG" -> AgentOrchestrator.Route.QUERY_DRUG_KNOWLEDGE;
            case "PROVIDE_INFORMATION" -> AgentOrchestrator.Route.CURRENT_FLOW;
            // 备忘 / 健康数值 / 发周报不在这里：它们在 {@link #dailyRoute} 里先于「直接回答」判定，
            // 免得模型用一句 ANSWER 把要落库的事带过去。
            default -> null;
        };
    }

    private boolean taskInProgress(ConversationState state) {
        return state.taskStatus == ConversationState.TaskStatus.ACTIVE
                || state.taskStatus == ConversationState.TaskStatus.PAUSED
                || state.taskStatus == ConversationState.TaskStatus.AWAITING_CONFIRMATION;
    }

    private Outcome outcome(AgentOrchestrator.Route route, PlannerDecision proposal,
                            String tool, List<PlannerToolCall> tools, boolean modelDriven) {
        return new Outcome(route, proposal.facts(), proposal.replyDraft(),
                normalizeDialogueMode(proposal.dialogueMode()), proposal.source(), tool, tools,
                proposal.actionType(), proposal.intent(), modelDriven);
    }

    private Outcome workflow(String message, PlannerDecision proposal, ConversationState state) {
        AgentOrchestrator.Route route = orchestrator.decide(message, proposal.facts(), state);
        return new Outcome(route, proposal.facts(), null, normalizeDialogueMode(proposal.dialogueMode()),
                proposal.source(), proposal.toolName(), proposal.toolCalls(), proposal.actionType(),
                proposal.intent(), false);
    }

    private String normalizeDialogueMode(String value) {
        if ("SUPPORT".equals(value) || "SMALL_TALK".equals(value)) return value;
        return "FOLLOWUP_FLOW";
    }

    private boolean isConversationalIntent(String intent) {
        return "EMOTIONAL_SUPPORT".equals(intent)
                || "SMALL_TALK".equals(intent)
                || "CLARIFY_DISCOMFORT".equals(intent)
                || "HEALTH_CONCERN".equals(intent)
                || "MEDICAL_ADVICE".equals(intent)
                || "UNKNOWN".equals(intent);
    }

    private boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }

    /**
     * 取消链路复用同一份判据：模型这次调用过不过契约，不过就返回第一条判罚。
     *
     * <p>刻意不在这里另写一套「scope 认不认识」的检查——判据只有一份，取消链路读的是
     * {@link #acceptedArguments} 归一化之后的参数，未声明的字段（比如模型硬塞的
     * {@code appointmentId}）在这里就已经没了。
     */
    ToolContract.Rejection rejection(String toolName, PlannerToolCall call) {
        return ToolContract.check(registry.find(toolName).orElse(null), call).orElse(null);
    }

    /** 契约归一化之后的参数：只留声明过的字段，枚举补大写，空串当没给。 */
    Map<String, String> acceptedArguments(String toolName, PlannerToolCall call) {
        ToolRegistry.RegisteredTool tool = registry.find(toolName).orElse(null);
        if (tool == null || call == null) return Map.of();
        return ToolContract.normalize(tool.definition().arguments(), call.arguments());
    }

    String mode() { return planner.mode(); }

    boolean modelAvailable() {
        String mode = planner.mode();
        return mode != null && mode.startsWith("MODEL_");
    }

    boolean isPageCommand(String message) { return orchestrator.isPageCommand(message); }
}
