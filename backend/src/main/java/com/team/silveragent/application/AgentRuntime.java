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

/**
 * 一轮智能体运行时：调用规划模型，执行工具白名单和回答权限检查，再交给工作流。
 * 写操作从不在这里执行，只会被转换为原有 Java 确认流程。
 */
@Component
final class AgentRuntime {
    record Outcome(AgentOrchestrator.Route route, ExtractedFacts facts, String replyDraft,
                   String dialogueMode, String plannerSource, String proposedTool,
                   List<PlannerToolCall> proposedTools, PlannerActionType actionType,
                   String intent, boolean modelDriven) { }

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
            PlannerDecision proposal = planner.plan(message, context, registry.plannerTools());
            if (proposal.source().startsWith("MODEL")) return modelProposal(proposal, state);
            // 模型本轮失败时才退回旧规则路径；不能把旧关键词规则叠加在成功的模型结论之上。
            return legacyProposal(message, context, state, proposal);
        }
        return legacyProposal(message, context, state, null);
    }

    private Outcome legacyProposal(String message, AgentContext context, ConversationState state,
                                   PlannerDecision existingProposal) {
        AgentOrchestrator.Route fastRoute = orchestrator.deterministicOverride(message);
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
                ? planner.plan(message, context, registry.plannerTools()) : existingProposal;
        ExtractedFacts facts = proposal.facts();

        AgentOrchestrator.Route deterministic = orchestrator.deterministicOverride(message);
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
                if (toolPolicy.evaluate(proposal, tool) != ToolPolicy.Decision.ALLOW) {
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
        if (proposal.actionType() == PlannerActionType.CALL_READ_TOOL
                || proposal.actionType() == PlannerActionType.CALL_READ_TOOLS) {
            List<PlannerToolCall> approved = new ArrayList<>();
            for (PlannerToolCall call : proposal.toolCalls()) {
                ToolRegistry.RegisteredTool tool = registry.find(call.toolName()).orElse(null);
                if (toolPolicy.evaluate(proposal, tool) == ToolPolicy.Decision.ALLOW) approved.add(call);
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
            return outcome(AgentOrchestrator.Route.DIRECT_ANSWER, proposal, null, List.of(), true);
        }

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
            case "PROVIDE_INFORMATION" -> AgentOrchestrator.Route.CURRENT_FLOW;
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

    String mode() { return planner.mode(); }

    boolean modelAvailable() {
        String mode = planner.mode();
        return mode != null && mode.startsWith("MODEL_");
    }

    boolean isPageCommand(String message) { return orchestrator.isPageCommand(message); }
}
