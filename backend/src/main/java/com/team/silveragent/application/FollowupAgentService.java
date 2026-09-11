package com.team.silveragent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.silveragent.agent.AgentContext;
import com.team.silveragent.agent.AnswerGenerator;
import com.team.silveragent.agent.ExtractedFacts;
import com.team.silveragent.agent.ReplyContext;
import com.team.silveragent.agent.planning.PlannerToolCall;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.model.AgentTurnResponse.ConfirmationCard;
import com.team.silveragent.domain.model.AgentTurnResponse.PlanCard;
import com.team.silveragent.domain.model.AgentTurnResponse.QuickReply;
import com.team.silveragent.domain.model.AgentTurnResponse.ResultCard;
import com.team.silveragent.domain.model.AgentTurnResponse.TaskProgress;
import com.team.silveragent.domain.model.AgentTurnResponse.UiDirective;
import com.team.silveragent.domain.model.ConversationHistoryResponse;
import com.team.silveragent.domain.model.ToolModels.AppointmentSummary;
import com.team.silveragent.domain.model.ToolModels.Conflict;
import com.team.silveragent.domain.model.ToolModels.Contact;
import com.team.silveragent.domain.model.ToolModels.DepartmentProfile;
import com.team.silveragent.domain.model.ToolModels.HospitalProfile;
import com.team.silveragent.domain.model.ToolModels.AppointmentTravelGuide;
import com.team.silveragent.domain.model.ToolModels.FacilityGuide;
import com.team.silveragent.domain.model.ToolModels.RouteGuide;
import com.team.silveragent.domain.model.ToolModels.Slot;
import com.team.silveragent.domain.tool.AppointmentTool;
import com.team.silveragent.domain.tool.CareGuideTool;
import com.team.silveragent.domain.tool.CareGuideTool.GuideArticle;
import com.team.silveragent.domain.tool.DepartmentCatalogTool;
import com.team.silveragent.domain.tool.FamilyNotificationTool;
import com.team.silveragent.domain.tool.HospitalCatalogTool;
import com.team.silveragent.domain.tool.FacilityGuideTool;
import com.team.silveragent.domain.tool.MaterialChecklistTool;
import com.team.silveragent.domain.tool.MaterialPreparationTool;
import com.team.silveragent.domain.tool.MyAppointmentTool;
import com.team.silveragent.domain.tool.ScheduleTool;
import com.team.silveragent.domain.tool.TravelTool;
import com.team.silveragent.domain.tool.RouteGuideTool;
import com.team.silveragent.infrastructure.mock.ToolTraceStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FollowupAgentService {
    private static final int MAX_MODEL_TOOL_ROUNDS = 3;
    private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("yyyy年M月d日");
    private static final DateTimeFormatter TIME_LABEL = DateTimeFormatter.ofPattern("HH:mm");
    private static final Pattern SPOKEN_DATE = Pattern.compile("(\\d{1,2})月(\\d{1,2})[日号]?");
    /** “2026年9月8号”：用户明确说了年份时才按完整日期查历史预约。 */
    private static final Pattern SPOKEN_FULL_DATE =
            Pattern.compile("(20\\d{2})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})");
    /** 完成播报后只回一个“要/好的”时的整句肯定句式，见 isShortAffirmative。 */
    private static final Pattern AFFIRMATIVE =
            Pattern.compile("(是|要|好|行|可以|需要|看看|看一下|听听|嗯|对)(的|了|吧|啊|呀|看|一下)*");

    private final AppointmentTool appointmentTool;
    private final CareGuideTool careGuideTool;
    private final HospitalCatalogTool hospitalCatalogTool;
    private final DepartmentCatalogTool departmentCatalogTool;
    private final ScheduleTool scheduleTool;
    private final TravelTool travelTool;
    private final RouteGuideTool routeGuideTool;
    private final FacilityGuideTool facilityGuideTool;
    private final TravelGuideService travelGuides;
    private final FamilyNotificationTool familyTool;
    private final MaterialChecklistTool materialTool;
    private final MaterialPreparationTool materialPreparationTool;
    private final MyAppointmentTool myAppointmentTool;
    private final ToolTraceStore traces;
    private final AgentRuntime agentRuntime;
    private final AnswerGenerator answerGenerator;
    private final ConversationStore conversations;
    private final AppointmentRecordStore appointmentRecords;
    private final CareCatalogRepository catalog;
    private final SafetyGuard safetyGuard;
    private final DialogueService dialogueService;
    private final ReplyContextBuilder replyContextBuilder;
    private final CatalogEntityResolver entityResolver;
    private final ObjectMapper json;
    private final String defaultUserId;
    private final Map<String, ConversationState> sessions = new ConcurrentHashMap<>();
    /** 工具循环中的中间响应不能写入对话，也不能提前再调用一次回答模型。 */
    private final ThreadLocal<Boolean> deferFinalization = ThreadLocal.withInitial(() -> false);

    public FollowupAgentService(
            AppointmentTool appointmentTool,
            CareGuideTool careGuideTool,
            HospitalCatalogTool hospitalCatalogTool,
            DepartmentCatalogTool departmentCatalogTool,
            ScheduleTool scheduleTool,
            TravelTool travelTool,
            RouteGuideTool routeGuideTool,
            FacilityGuideTool facilityGuideTool,
            TravelGuideService travelGuides,
            FamilyNotificationTool familyTool,
            MaterialChecklistTool materialTool,
            MaterialPreparationTool materialPreparationTool,
            MyAppointmentTool myAppointmentTool,
            ToolTraceStore traces,
            AgentRuntime agentRuntime,
            AnswerGenerator answerGenerator,
            ConversationStore conversations,
            AppointmentRecordStore appointmentRecords,
            CareCatalogRepository catalog,
            SafetyGuard safetyGuard,
            DialogueService dialogueService,
            ReplyContextBuilder replyContextBuilder,
            CatalogEntityResolver entityResolver,
            ObjectMapper json,
            @Value("${demo.user-id:user-001}") String defaultUserId) {
        this.appointmentTool = appointmentTool;
        this.careGuideTool = careGuideTool;
        this.hospitalCatalogTool = hospitalCatalogTool;
        this.departmentCatalogTool = departmentCatalogTool;
        this.scheduleTool = scheduleTool;
        this.travelTool = travelTool;
        this.routeGuideTool = routeGuideTool;
        this.facilityGuideTool = facilityGuideTool;
        this.travelGuides = travelGuides;
        this.familyTool = familyTool;
        this.materialTool = materialTool;
        this.materialPreparationTool = materialPreparationTool;
        this.myAppointmentTool = myAppointmentTool;
        this.traces = traces;
        this.agentRuntime = agentRuntime;
        this.answerGenerator = answerGenerator;
        this.conversations = conversations;
        this.appointmentRecords = appointmentRecords;
        this.catalog = catalog;
        this.safetyGuard = safetyGuard;
        this.dialogueService = dialogueService;
        this.replyContextBuilder = replyContextBuilder;
        this.entityResolver = entityResolver;
        this.json = json;
        this.defaultUserId = defaultUserId;
    }

    public synchronized AgentTurnResponse start() {
        return start(defaultUserId);
    }

    public synchronized AgentTurnResponse start(String requestedUserId) {
        String userId = requestedUserId == null || requestedUserId.isBlank() ? defaultUserId : requestedUserId;
        CareCatalogRepository.UserProfile user = catalog.user(userId)
                .orElseThrow(() -> new IllegalArgumentException("没有找到当前用户，请检查模拟用户数据"));
        String id = UUID.randomUUID().toString();
        ConversationState state = new ConversationState(id, user.id());
        sessions.put(id, state);
        return respondWithoutModel(state, "您好，" + user.name()
                        + "。我是您的复诊事务助手。您可以和我聊聊，也可以问复诊流程、材料和到院指引；准备办理时，直接说“我要预约复诊”即可。",
                List.of(q("开始复诊办理", "CONTINUE", ""),
                        q("了解复诊流程", "QUERY_CARE_GUIDE", ""),
                        q("查询我的预约", "QUERY_APPOINTMENTS", "")));
    }

    public synchronized ConversationHistoryResponse resume(String conversationId) {
        ConversationState state = requireSession(conversationId);
        AgentTurnResponse current = conversations.lastResponse(conversationId)
                .orElseGet(() -> AgentTurnResponse.message(conversationId, state.stage.name(),
                        "已恢复上次办理进度。", List.of(q("继续办理", "CONTINUE", ""))));
        return new ConversationHistoryResponse(conversationId, state.stage.name(),
                conversations.messages(conversationId), current);
    }

    /**
     * 自由语言入口：每一轮都会携带当前状态和最近对话调用语言理解模型。
     */
    public synchronized AgentTurnResponse chat(String conversationId, String message) {
        try { return chatInternal(conversationId, message); }
        catch (RuntimeException error) { return toolError(requireSession(conversationId), error); }
    }

    private AgentTurnResponse chatInternal(String conversationId, String message) {
        ConversationState state = requireSession(conversationId);
        String value = message == null ? "" : message.trim();
        if (value.isEmpty()) return respond(state, "我没有听清，请再说一次。", List.of(q("重新说一遍", "ASK_HUMAN_INPUT", "")));

        // 模型可用时医疗语义、普通意图和页面意图都由同一个主模型判断；
        // 只有模型不可用的回退模式才运行旧的 Java 关键词预检。
        if (!agentRuntime.modelAvailable()) {
            SafetyGuard.Decision immediate = safetyGuard.precheck(value);
            if (immediate == SafetyGuard.Decision.EMERGENCY) {
                conversations.addMessage(state.id, "user", value);
                return emergency(state);
            }
            if (immediate == SafetyGuard.Decision.MEDICAL_BOUNDARY) {
                conversations.addMessage(state.id, "user", value);
                return medicalBoundary(state);
            }
        }
        AgentContext context = new AgentContext(state.stage.name(), knownFacts(state),
                LocalDate.now(), conversations.recentMessages(state.id));
        AgentRuntime.Outcome outcome = agentRuntime.plan(value, context, state);
        ExtractedFacts facts = outcome.facts();
        conversations.addMessage(state.id, "user", value);
        SafetyGuard.Decision safety = outcome.modelDriven()
                ? safetyGuard.evaluateModel(facts) : safetyGuard.evaluate(value, facts);
        if (safety == SafetyGuard.Decision.EMERGENCY) return emergency(state);
        if (safety == SafetyGuard.Decision.MEDICAL_BOUNDARY) return medicalBoundary(state);
        AgentOrchestrator.Route route = outcome.route();
        if (outcome.modelDriven()) prepareModelIntentState(state, outcome.intent());
        if (stopped(state) && !allowedWhenStopped(route)) return stoppedResponse(state);
        AgentTurnResponse entityConfirmation = handlePendingEntityConfirmation(state, value, facts);
        if (entityConfirmation != null) return entityConfirmation;
        if ("CANCEL_EXISTING_APPOINTMENT".equals(state.sideTask)) {
            AgentTurnResponse selection = resolveCancellationCandidate(state, value);
            if (selection != null) return selection;
        }
        // 地图候选支线与取消支线分开：这里只挑“看哪一次预约的地图”，不会进入任何取消确认。
        if ("SELECT_TRAVEL_APPOINTMENT".equals(state.sideTask)) {
            AgentTurnResponse selection = resolveTravelCandidate(state, value);
            if (selection != null) return selection;
        }
        // 有确认卡等着处理时，“打开地图”这类页面指令不得把确认/返回修改挤掉；先让老人处理完这次重要操作。
        // 判定按“本轮落在哪条路由”而不是“是不是短口令”：带日期的“我想看9月8号的地图”同样只是只读跳转，
        // 但它不走快速通道，只看 isPageCommand 会把确认卡冲掉。
        if (state.stage == ConversationState.Stage.AWAITING_CONFIRMATION
                && (agentRuntime.isPageCommand(value)
                    || route == AgentOrchestrator.Route.QUERY_TRAVEL_GUIDE
                    || route == AgentOrchestrator.Route.QUERY_LOCATION_GUIDE)) {
            if ("CANCEL_EXISTING".equals(state.pendingAction) && state.pendingAppointmentId != null) {
                return prepareExistingCancellation(state, state.pendingAppointmentId);
            }
            if (state.selectedSlot != null) return buildConfirmation(state);
            return respond(state, "当前有一项重要操作等待确认，请先选择“确认”或“返回修改”，之后我再帮您打开地图。", List.of());
        }
        if (state.stage == ConversationState.Stage.COMPLETED && isShortAffirmative(value)) {
            AgentTurnResponse opened = showTravelGuide(state, ExtractedFacts.empty(), value, false);
            if (opened.uiDirective() != null) return opened;
        }
        // 在“等待医院/科室”节点，模型只需保留用户原话；Java调用真实目录工具做匹配。
        // 精确项直接采用，近似项先自然追问，多候选或无结果都明确说明，不能静默丢弃后重复原问题。
        if ((route == AgentOrchestrator.Route.CURRENT_FLOW || route == AgentOrchestrator.Route.RESUME_TASK
                || route == AgentOrchestrator.Route.RESTART_TASK)
                && state.taskStatus == ConversationState.TaskStatus.ACTIVE) {
            if (state.stage == ConversationState.Stage.ASK_HOSPITAL && state.hospital == null) {
                if (facts.hospital() != null || looksLikeHospitalMention(value)) {
                    // 先保存同一句话里的日期、时间、陪同、交通等其他字段；医院本身仍由目录查询确认。
                    applyFacts(state, facts);
                    return resolveHospitalInput(state, firstNonBlank(facts.hospital(), value));
                }
            }
            if (state.stage == ConversationState.Stage.ASK_DEPARTMENT && state.department == null) {
                if (facts.department() != null || looksLikeDepartmentMention(value)) {
                    applyFacts(state, facts);
                    return resolveDepartmentInput(state, firstNonBlank(facts.department(), value));
                }
            }
            // Java 手上举着一个待确认的推荐号源时，老人回一句“可以/行/好的”就是对这件事的最短肯定。
            // 模型偶尔不把 acceptRecommendedTime 记上，文字却说“那就先记下 09:00”，界面按钮于是还停在
            // 选时段上，和文字对不上。这一步 Java 自己就能认定，不必看模型当轮心情；
            // 模型明确给了接受/拒绝、或老人说了别的时间（selectedTime）时都不抢。
            if (state.recommendedSlot != null && facts.acceptRecommendedTime() == null
                    && facts.selectedTime() == null && isShortAffirmative(value)) {
                return selectSlot(state, state.recommendedSlot.id());
            }
            // 同一件事在陪同、出行提醒、通知家属上也一样：Java 问出口的是一道是非题，
            // 老人回一句“要/不用/好的”，模型却常常不填对应字段，于是同一句话被反复问，
            // 老人只能去点按钮。模型这轮一个业务字段都没给时，这一项由 Java 自己记下，
            // 顺序与 pendingQuestion 一致；交通方式是开放题，短肯定接不上，跳过交给模型。
            if (!hasTaskFacts(facts) && state.selectedSlot != null && state.acceptAlternative != null
                    && (isShortAffirmative(value) || isShortNegative(value))) {
                boolean yes = isShortAffirmative(value);
                if (state.needCompanion == null) {
                    state.needCompanion = yes;
                    return advance(state, ExtractedFacts.empty());
                }
                if (state.needTravel == null) {
                    state.needTravel = yes;
                    return advance(state, ExtractedFacts.empty());
                }
                if (state.transport == null) {
                    // 开放题，短肯定回答不了，交给后面的正常流程。
                } else if (state.notifyFamily == null) {
                    state.notifyFamily = yes;
                    if (!yes) state.contact = null;
                    return advance(state, ExtractedFacts.empty());
                }
            }
        }
        if (!outcome.modelDriven() && route == AgentOrchestrator.Route.CURRENT_FLOW && !hasTaskFacts(facts)
                && ("UNKNOWN".equals(facts.intent()) || "PROVIDE_INFORMATION".equals(facts.intent()))) {
            // 办理正停在一个待答问题上时，一句没被接住的话不该把整个办理挂起：任务一旦 PAUSED，
            // 阶段就不再跟着同步，按钮只剩“继续办理”，老人越答越回到原地。这种只把当前这一步再问一遍。
            List<QuickReply> waitingReplies = state.taskStatus == ConversationState.TaskStatus.ACTIVE
                    ? modelSuggestedReplies(state) : List.of();
            if (!waitingReplies.isEmpty()) {
                return respond(state, clarificationDraft(state), waitingReplies);
            }
            if (state.taskStatus == ConversationState.TaskStatus.ACTIVE) {
                state.taskStatus = ConversationState.TaskStatus.PAUSED;
            }
            state.dialogueMode = ConversationState.DialogueMode.GENERAL_CHAT;
            return respond(state, clarificationDraft(state), resumeReplies(state));
        }
        if (advancesTask(route)) {
            state.dialogueMode = ConversationState.DialogueMode.FOLLOWUP_FLOW;
            if (state.taskStatus == ConversationState.TaskStatus.PAUSED) {
                state.taskStatus = ConversationState.TaskStatus.ACTIVE;
            }
        }
        if (agentRuntime.isModelReadToolOutcome(outcome)) {
            return runModelToolLoop(state, value, outcome);
        }
        return dispatchOutcome(state, value, outcome);
    }

    private AgentTurnResponse dispatchOutcome(ConversationState state, String value,
                                              AgentRuntime.Outcome outcome) {
        ExtractedFacts facts = outcome.facts();
        AgentOrchestrator.Route route = outcome.route();
        return switch (route) {
            case DIRECT_ANSWER -> {
                if (outcome.modelDriven() && hasTaskFacts(facts)
                        && state.taskStatus == ConversationState.TaskStatus.ACTIVE) {
                    applyFacts(state, facts);
                    syncPresentationStage(state);
                }
                DialogueService.DialogueReply reply = dialogueService.modelReply(
                        state, outcome.replyDraft(), outcome.dialogueMode());
                // 规划模型已经生成并通过安全校验，不再进行第二次模型润色。
                yield respondWithoutModel(state, reply.draft(), reply.quickReplies());
            }
            case EMOTIONAL_SUPPORT -> {
                DialogueService.DialogueReply reply = dialogueService.support(state, value, facts);
                yield respond(state, reply.draft(), reply.quickReplies());
            }
            case SMALL_TALK -> {
                DialogueService.DialogueReply reply = dialogueService.smallTalk(state, value);
                yield respond(state, reply.draft(), reply.quickReplies());
            }
            case CLARIFY_DISCOMFORT -> {
                DialogueService.DialogueReply reply = dialogueService.clarifyDiscomfort(state);
                yield respond(state, reply.draft(), reply.quickReplies());
            }
            case KEEP_CONFLICT -> keepConflict(state);
            case CONFIRM_PENDING -> confirm(state.id, true, state.confirmationId);
            case DENY_PENDING -> confirm(state.id, false, state.confirmationId);
            case CANCEL_CURRENT_TASK -> cancelTask(state);
            case CANCEL_EXISTING_APPOINTMENT -> beginCancelExistingAppointment(state, facts);
            case QUERY_MY_APPOINTMENTS -> queryMyAppointments(state, facts);
            case RESTART_TASK -> outcome.modelDriven()
                    ? modelWorkflowReply(state, facts, outcome.replyDraft(), true)
                    : restartInCurrentConversation(state);
            case RESUME_TASK -> outcome.modelDriven()
                    ? modelWorkflowReply(state, facts, outcome.replyDraft(), false)
                    : resumeInterruptedTask(state);
            case QUERY_CARE_GUIDE -> showCareGuide(state, value);
            case MULTI_READ_TOOLS -> executeReadTools(state, value, outcome.proposedTools());
            case RESOLVE_HOSPITAL -> {
                applyFacts(state, facts);
                yield resolveHospitalInput(state,
                        firstToolArgument(outcome, "keyword", firstNonBlank(facts.hospital(), value)));
            }
            case RESOLVE_DEPARTMENT -> {
                applyFacts(state, facts);
                yield resolveDepartmentInput(state,
                        firstToolArgument(outcome, "keyword", firstNonBlank(facts.department(), value)));
            }
            case VALIDATE_DRAFT -> validateDraftForModel(state, facts);
            case QUERY_HOSPITALS -> showHospitals(state, facts.hospital());
            case QUERY_DEPARTMENTS -> showDepartments(state, facts.hospital());
            case RECOMMEND_HOSPITAL -> recommendHospitals(state, facts.department());
            case QUERY_AVAILABLE_SLOTS -> {
                if (facts.date() != null) applyFacts(state, facts);
                else clearSlotSelection(state, true);
                yield showAvailableSlots(state);
            }
            case QUERY_NEARBY_SLOTS -> queryNearbySlots(state);
            case CHECK_CONFLICT -> checkSchedule(state);
            case CHECK_DUPLICATE -> checkDuplicate(state);
            case ASK_MATERIALS -> showMaterials(state, facts);
            case QUERY_TRAVEL_GUIDE -> showTravelGuide(state, facts, value, false);
            case QUERY_LOCATION_GUIDE -> showLocationGuide(state, facts, value, true);
            case CHANGE_HOSPITAL -> changeHospital(state, facts, value);
            case CHANGE_DEPARTMENT -> changeDepartment(state, facts, value);
            case CHANGE_DATE -> changeDate(state, facts);
            case CHANGE_TIME -> changeTime(state, facts);
            case CURRENT_FLOW -> outcome.modelDriven()
                    ? modelWorkflowReply(state, facts, outcome.replyDraft(), false)
                    : continueCurrentFlow(state, facts);
        };
    }

    /**
     * 模型主导的有界只读工具循环。现有异常处理方法仍负责查询真实数据、更新权威草稿并
     * 生成合法按钮；中间结果不会直接发给用户，而是作为结构化证据回到同一个主模型。
     */
    private AgentTurnResponse runModelToolLoop(ConversationState state, String originalMessage,
                                               AgentRuntime.Outcome initial) {
        AgentRuntime.Outcome current = initial;
        AgentTurnResponse latestToolResponse = null;
        Set<String> executed = new LinkedHashSet<>();

        for (int round = 1; round <= MAX_MODEL_TOOL_ROUNDS; round++) {
            if (!agentRuntime.isModelReadToolOutcome(current)) {
                return finishToolLoopDecision(state, originalMessage, current, latestToolResponse);
            }

            List<PlannerToolCall> freshCalls = current.proposedTools().stream()
                    .filter(call -> executed.add(toolSignature(call)))
                    .toList();
            if (freshCalls.isEmpty()) {
                return finalizeToolEvidence(state, latestToolResponse,
                        "我已经完成了这项查询。您可以根据上面的真实结果继续选择。");
            }

            if (hasTaskFacts(current.facts()) && taskInProgress(state)) {
                applyFacts(state, current.facts());
                syncPresentationStage(state);
            }
            AgentRuntime.Outcome executable = withToolCalls(current, freshCalls);
            deferFinalization.set(true);
            try {
                latestToolResponse = dispatchOutcome(state, originalMessage, executable);
            } finally {
                deferFinalization.remove();
            }

            // 确认卡、完成卡和安全暂停都是业务终点，不能为了措辞再让模型改变动作。
            if (latestToolResponse.confirmation() != null
                    || state.stage == ConversationState.Stage.EMERGENCY_PAUSED
                    || state.stage == ConversationState.Stage.COMPLETED
                    || state.stage == ConversationState.Stage.PARTIAL) {
                return finalizeToolEvidence(state, latestToolResponse, latestToolResponse.reply());
            }

            String evidence = toolLoopEvidence(round, freshCalls, latestToolResponse);
            AgentContext nextContext = new AgentContext(state.stage.name(), knownFacts(state),
                    LocalDate.now(), conversations.recentMessages(state.id));
            current = agentRuntime.continueAfterTools(originalMessage, nextContext, state, evidence);

            SafetyGuard.Decision safety = current.modelDriven()
                    ? safetyGuard.evaluateModel(current.facts())
                    : SafetyGuard.Decision.NONE;
            if (safety == SafetyGuard.Decision.EMERGENCY) return emergency(state);
            if (safety == SafetyGuard.Decision.MEDICAL_BOUNDARY) return medicalBoundary(state);
            if (current.modelDriven()) prepareModelIntentState(state, current.intent());
        }

        return finalizeToolEvidence(state, latestToolResponse,
                "我已经完成当前查询。为避免重复查询，请从现有结果中选择，或告诉我想修改哪项条件。");
    }

    private AgentTurnResponse finishToolLoopDecision(ConversationState state, String originalMessage,
                                                     AgentRuntime.Outcome decision,
                                                     AgentTurnResponse latestToolResponse) {
        if (!decision.modelDriven() && latestToolResponse != null) {
            // 工具已经返回真实结果，但模型续写超时或解析失败时，直接交付权威工具结果，
            // 不再重新进入旧流程路由，避免丢失本轮查询结果或重复调用工具。
            return finalizeToolEvidence(state, latestToolResponse, latestToolResponse.reply());
        }
        if (decision.route() == AgentOrchestrator.Route.CONFIRM_PENDING) {
            // 工具结果不能替用户确认现实操作；确认只能由用户直接面对当前确认卡明确表达。
            return finalizeToolEvidence(state, latestToolResponse,
                    "查询已经完成。涉及预约、取消、提醒或通知的操作，还需要您查看确认内容后明确确认。");
        }
        if (decision.route() == AgentOrchestrator.Route.DIRECT_ANSWER
                && decision.replyDraft() != null && !decision.replyDraft().isBlank()) {
            if (hasTaskFacts(decision.facts()) && taskInProgress(state)) {
                applyFacts(state, decision.facts());
                syncPresentationStage(state);
            }
            return finalizeToolEvidence(state, latestToolResponse, decision.replyDraft());
        }
        return dispatchOutcome(state, originalMessage, decision);
    }

    private AgentTurnResponse finalizeToolEvidence(ConversationState state,
                                                   AgentTurnResponse evidenceResponse,
                                                   String reply) {
        if (evidenceResponse == null) {
            return respondWithoutModel(state, reply, modelSuggestedReplies(state));
        }
        String finalReply = reply == null || reply.isBlank() ? evidenceResponse.reply() : reply.trim();
        AgentTurnResponse finalDraft = new AgentTurnResponse(state.id, state.stage.name(), finalReply,
                evidenceResponse.quickReplies(), evidenceResponse.plan(), evidenceResponse.confirmation(),
                evidenceResponse.result(), traces.findByConversation(state.id), null, finalReply,
                evidenceResponse.uiDirective());
        return finishWithoutModel(state, finalDraft);
    }

    private AgentRuntime.Outcome withToolCalls(AgentRuntime.Outcome outcome, List<PlannerToolCall> calls) {
        AgentOrchestrator.Route route = calls.size() > 1
                ? AgentOrchestrator.Route.MULTI_READ_TOOLS
                : toolRoute(calls.get(0), outcome.route());
        return new AgentRuntime.Outcome(route, outcome.facts(), outcome.replyDraft(),
                outcome.dialogueMode(), outcome.plannerSource(),
                calls.size() == 1 ? calls.get(0).toolName() : null, calls,
                calls.size() == 1 ? com.team.silveragent.agent.planning.PlannerActionType.CALL_READ_TOOL
                        : com.team.silveragent.agent.planning.PlannerActionType.CALL_READ_TOOLS,
                outcome.intent(), outcome.modelDriven());
    }

    private AgentOrchestrator.Route toolRoute(PlannerToolCall call, AgentOrchestrator.Route fallback) {
        return switch (call.toolName()) {
            case "appointment.validateDraft" -> AgentOrchestrator.Route.VALIDATE_DRAFT;
            case "hospital.search" -> AgentOrchestrator.Route.RESOLVE_HOSPITAL;
            case "department.search" -> AgentOrchestrator.Route.RESOLVE_DEPARTMENT;
            case "careGuide.search" -> AgentOrchestrator.Route.QUERY_CARE_GUIDE;
            case "hospital.list" -> AgentOrchestrator.Route.QUERY_HOSPITALS;
            case "department.list" -> AgentOrchestrator.Route.QUERY_DEPARTMENTS;
            case "appointment.querySlots" -> AgentOrchestrator.Route.QUERY_AVAILABLE_SLOTS;
            case "appointment.queryNearbySlots" -> AgentOrchestrator.Route.QUERY_NEARBY_SLOTS;
            case "appointment.checkDuplicate" -> AgentOrchestrator.Route.CHECK_DUPLICATE;
            case "schedule.checkConflict" -> AgentOrchestrator.Route.CHECK_CONFLICT;
            case "appointment.queryMine" -> AgentOrchestrator.Route.QUERY_MY_APPOINTMENTS;
            case "material.checklist" -> AgentOrchestrator.Route.ASK_MATERIALS;
            case "travel.routePlan" -> AgentOrchestrator.Route.QUERY_TRAVEL_GUIDE;
            case "hospital.locationGuide" -> AgentOrchestrator.Route.QUERY_LOCATION_GUIDE;
            default -> fallback;
        };
    }

    private String toolSignature(PlannerToolCall call) {
        return call.toolName() + ":" + new TreeMap<>(call.arguments());
    }

    private String toolLoopEvidence(int round, List<PlannerToolCall> calls,
                                    AgentTurnResponse response) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("round", round);
        value.put("status", response.stage());
        value.put("executedTools", calls.stream().map(call -> Map.of(
                "name", call.toolName(), "arguments", call.arguments())).toList());
        value.put("authoritativeResult", response.reply());
        value.put("allowedNextActions", response.quickReplies() == null ? List.of()
                : response.quickReplies().stream().map(item -> Map.of(
                "label", item.label(), "action", item.action(), "value", item.value())).toList());
        value.put("appointmentDraft", knownFacts(requireSession(response.conversationId())));
        List<AgentTurnResponse.ToolTrace> recent = response.toolTraces() == null ? List.of()
                : response.toolTraces().stream()
                .skip(Math.max(0, response.toolTraces().size() - 4L)).toList();
        value.put("toolEvidence", recent);
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            return value.toString();
        }
    }

    private void prepareModelIntentState(ConversationState state, String intent) {
        if ("RESTART_TASK".equals(intent)
                || ("CREATE_FOLLOWUP".equals(intent) && !taskInProgress(state))) {
            clearDraft(state);
            state.taskStatus = ConversationState.TaskStatus.ACTIVE;
            state.dialogueMode = ConversationState.DialogueMode.FOLLOWUP_FLOW;
            return;
        }
        if ("CREATE_FOLLOWUP".equals(intent) || "RESUME_TASK".equals(intent)
                || "PROVIDE_INFORMATION".equals(intent)) {
            if (state.taskStatus == ConversationState.TaskStatus.PAUSED) {
                state.taskStatus = ConversationState.TaskStatus.ACTIVE;
            }
            state.dialogueMode = ConversationState.DialogueMode.FOLLOWUP_FLOW;
        }
    }

    /** 模型负责本轮问法和信息顺序；Java只合并草稿并维护兼容界面的展示阶段。 */
    private AgentTurnResponse modelWorkflowReply(ConversationState state, ExtractedFacts facts,
                                                 String replyDraft, boolean restart) {
        if (restart && state.taskStatus != ConversationState.TaskStatus.ACTIVE) {
            clearDraft(state);
            state.taskStatus = ConversationState.TaskStatus.ACTIVE;
        }
        state.dialogueMode = ConversationState.DialogueMode.FOLLOWUP_FLOW;
        applyFacts(state, facts);
        // 老人说了家属但目录里对不上（“我闺女”“老张”）：不能当作没听见再问一遍“要通知谁”，
        // 那样同一句话会被反复问。把数据库里真实登记的人摆出来让他挑。
        if (Boolean.TRUE.equals(state.notifyFamily) && state.contact == null
                && facts.familyContact() != null && !facts.familyContact().isBlank()) {
            return askContactFromCatalog(state, facts.familyContact());
        }
        // 界面阶段不能拿来决定认不认账：模型模式下阶段可能还停在 SELECT_PERIOD，
        // 但草稿里已经有真实号源和老人刚说出口的时间，这时一句“可以”必须被认领。
        if (state.recommendedSlot == null && state.selectedSlot == null && state.requestedTime != null) {
            state.recommendedSlot = nearestSlot(state.alternatives, state.requestedTime);
        }
        if (state.recommendedSlot != null) {
            if (Boolean.TRUE.equals(facts.acceptRecommendedTime())) {
                return selectSlot(state, state.recommendedSlot.id());
            }
            if (Boolean.FALSE.equals(facts.acceptRecommendedTime())) {
                return showPeriodSlots(state, state.timePreference);
            }
        }
        // 号源已经选好之后，模型再复述同一个时间不能把流程推回“选时间”，
        // 否则之后每一轮都会被拉回 CONFIRM_SLOT，永远走不到确认卡。
        // 用户真要改时间时 applyFacts 会先清空 selectedSlot，这里才需要重新推荐。
        if (facts.selectedTime() != null && state.selectedSlot == null && !state.alternatives.isEmpty()) {
            return recommendSpecificTime(state, facts.selectedTime());
        }
        if (facts.timePreference() != null && state.selectedSlot == null && !state.alternatives.isEmpty()) {
            return recommendPeriod(state, facts.timePreference());
        }
        if (facts.date() != null && state.hospitalId != null && state.department != null
                && state.selectedSlot == null && state.alternatives.isEmpty()) {
            return querySlots(state);
        }
        if (readyForMaterialLookup(state)) {
            state.materials = callTool(state, "materials.checklist",
                    Map.of("hospital", state.hospital, "department", state.department),
                    () -> materialTool.checklist(state.id, state.hospital, state.department));
            return checkSchedule(state);
        }
        syncPresentationStage(state);
        if (replyDraft != null && !replyDraft.isBlank()) {
            return respondWithoutModel(state, replyDraft, modelSuggestedReplies(state));
        }
        // 模型没有给出可用问句才使用最低限度模板回退，不再调用 Java 意图中控。
        return advance(state, facts);
    }

    private boolean readyForMaterialLookup(ConversationState state) {
        return state.hospitalId != null && state.department != null && state.date != null
                && state.selectedSlot != null && state.acceptAlternative != null
                && state.needCompanion != null && state.needTravel != null
                && state.transport != null && state.notifyFamily != null
                && (!state.notifyFamily || state.contact != null) && state.materials.isEmpty();
    }

    private List<QuickReply> modelSuggestedReplies(ConversationState state) {
        if (state.stage == ConversationState.Stage.AWAITING_CONFIRMATION) return List.of();
        if (state.selectedSlot == null && !state.alternatives.isEmpty()) {
            return slotReplies(state.alternatives.stream().limit(3).toList());
        }
        // 按钮要跟着这一轮真正问的问题走。以前这里只认号源，模型问“需要人陪您去吗”时
        // 底下却还挂着三个时间段，老人只能照着错的按钮点。
        return switch (state.stage) {
            case ASK_ALTERNATIVE -> List.of(q("可以换日期", "SET_ALTERNATIVE", "true"),
                    q("只要这一天", "SET_ALTERNATIVE", "false"));
            case ASK_COMPANION -> List.of(q("需要陪同", "SET_COMPANION", "true"),
                    q("不需要陪同", "SET_COMPANION", "false"));
            case ASK_TRAVEL -> List.of(q("需要出行提醒", "SET_TRAVEL", "true"),
                    q("不需要", "SET_TRAVEL", "false"));
            case ASK_TRANSPORT -> List.of(q("家属开车", "SET_TRANSPORT", "家属开车"),
                    q("打车", "SET_TRANSPORT", "打车"), q("公交", "SET_TRANSPORT", "公交"));
            case ASK_NOTIFY -> List.of(q("需要通知", "SET_NOTIFY", "true"),
                    q("不用通知", "SET_NOTIFY", "false"));
            case READY_TO_PLAN -> resumeReplies(state);
            default -> List.of();
        };
    }

    /** Stage 只为旧前端进度展示服务，不参与模型模式的意图决定。 */
    private void syncPresentationStage(ConversationState state) {
        if (state.taskStatus != ConversationState.TaskStatus.ACTIVE) return;
        if (state.hospital == null) state.stage = ConversationState.Stage.ASK_HOSPITAL;
        else if (state.department == null) state.stage = ConversationState.Stage.ASK_DEPARTMENT;
        else if (state.date == null) state.stage = ConversationState.Stage.ASK_DATE;
        else if (state.selectedSlot == null) state.stage = state.alternatives.isEmpty()
                ? ConversationState.Stage.SELECT_SLOT : ConversationState.Stage.SELECT_PERIOD;
        else if (state.acceptAlternative == null) state.stage = ConversationState.Stage.ASK_ALTERNATIVE;
        else if (state.needCompanion == null) state.stage = ConversationState.Stage.ASK_COMPANION;
        else if (state.needTravel == null) state.stage = ConversationState.Stage.ASK_TRAVEL;
        else if (state.transport == null) state.stage = ConversationState.Stage.ASK_TRANSPORT;
        else if (state.notifyFamily == null || (state.notifyFamily && state.contact == null)) {
            state.stage = ConversationState.Stage.ASK_NOTIFY;
        } else state.stage = ConversationState.Stage.READY_TO_PLAN;
    }

    private String firstToolArgument(AgentRuntime.Outcome outcome, String name, String fallback) {
        if (outcome.proposedTools() == null || outcome.proposedTools().isEmpty()) return fallback;
        return firstNonBlank(outcome.proposedTools().get(0).arguments().get(name), fallback);
    }

    private AgentTurnResponse medicalBoundary(ConversationState state) {
        return respondWithoutModel(state,
                "我听到您身体不舒服了，但我不能诊断疾病、判断原因、解释检查结果或调整用药。请及时咨询医生或专业医疗机构；如果症状突然加重，请尽快寻求线下帮助。",
                resumeReplies(state, q("咨询人工", "CONTACT_HUMAN", "")));
    }

    private boolean allowedWhenStopped(AgentOrchestrator.Route route) {
        return switch (route) {
            case DIRECT_ANSWER, EMOTIONAL_SUPPORT, SMALL_TALK, CLARIFY_DISCOMFORT, RESTART_TASK,
                    QUERY_CARE_GUIDE, MULTI_READ_TOOLS,
                    QUERY_MY_APPOINTMENTS, CANCEL_EXISTING_APPOINTMENT,
                    QUERY_TRAVEL_GUIDE, QUERY_LOCATION_GUIDE -> true;
            default -> false;
        };
    }

    private AgentTurnResponse continueCurrentFlow(ConversationState state, ExtractedFacts facts) {
        if (state.taskStatus == ConversationState.TaskStatus.NONE) {
            if (!hasTaskFacts(facts)) {
                return respondWithoutModel(state,
                        "我还不确定您是想开始办理复诊，还是想先了解相关信息。您可以直接问我，也可以点击“开始复诊办理”。",
                        List.of(q("开始复诊办理", "CONTINUE", ""),
                                q("了解复诊流程", "ASK_HUMAN_INPUT", "")));
            }
            state.taskStatus = ConversationState.TaskStatus.ACTIVE;
        }
        if (state.stage == ConversationState.Stage.AWAITING_CONFIRMATION) {
            if ("CANCEL_EXISTING".equals(state.pendingAction) && state.pendingAppointmentId != null) {
                return prepareExistingCancellation(state, state.pendingAppointmentId);
            }
            if (state.selectedSlot != null) return buildConfirmation(state);
            return respond(state, "当前有一项重要操作等待确认。请明确选择确认或返回修改。", List.of());
        }
        discardInterruption(state);
        if (facts.date() != null && (facts.date().isBefore(LocalDate.now())
                || facts.date().isAfter(LocalDate.now().plusMonths(1)))) {
            return respond(state, "目前可以查询今天到一个月后的模拟号源，请在这个日期范围内重新选择。",
                    List.of(q("查看可预约日期", "SHOW_AVAILABLE_DATES", ""),
                            q("我自己说日期", "ASK_HUMAN_INPUT", "")));
        }

        ConversationState.Stage previousStage = state.stage;
        applyFacts(state, facts);
        if (previousStage == ConversationState.Stage.CONFIRM_SLOT && state.recommendedSlot != null) {
            if (Boolean.TRUE.equals(facts.acceptRecommendedTime())) return selectSlot(state, state.recommendedSlot.id());
            if (Boolean.FALSE.equals(facts.acceptRecommendedTime())) return showPeriodSlots(state, state.timePreference);
        }
        if (facts.selectedTime() != null && isSelectingTime(previousStage)) {
            return recommendSpecificTime(state, facts.selectedTime());
        }
        if ((previousStage == ConversationState.Stage.SELECT_PERIOD
                || previousStage == ConversationState.Stage.CONFIRM_SLOT)
                && facts.timePreference() != null) {
            return recommendPeriod(state, facts.timePreference());
        }
        if (previousStage == ConversationState.Stage.NO_SLOT && facts.date() != null) {
            clearSlotSelection(state, false);
            return querySlots(state);
        }
        return advance(state, facts);
    }

    /**
     * 明确按钮入口：不调用大模型，直接按action和value更新状态。
     */
    public synchronized AgentTurnResponse act(String conversationId, String action, String value, String label) {
        try { return actInternal(conversationId, action, value, label); }
        catch (RuntimeException error) { return toolError(requireSession(conversationId), error); }
    }

    private AgentTurnResponse actInternal(String conversationId, String action, String value, String label) {
        ConversationState state = requireSession(conversationId);
        String safeValue = value == null ? "" : value.trim();
        String displayLabel = label == null || label.isBlank() ? (safeValue.isBlank() ? "继续办理" : safeValue) : label.trim();
        conversations.addMessage(state.id, "user", "[按钮] " + action + "：" + displayLabel);

        if ("NEW_BOOKING".equals(action)) return restartInCurrentConversation(state);
        if ("CONTACT_HUMAN".equals(action)) return respond(state,
                "这里是模拟人工帮助入口，尚未接通真人。当前记录已保留。", List.of());
        if ("CONFIRM_ENTITY".equals(action)) return confirmPendingEntity(state, true);
        if ("REJECT_ENTITY".equals(action)) return confirmPendingEntity(state, false);
        if (("CONTINUE".equals(action) || "RETURN_TO_FLOW".equals(action))
                && (state.taskStatus == ConversationState.TaskStatus.NONE
                || state.taskStatus == ConversationState.TaskStatus.CANCELLED
                || state.taskStatus == ConversationState.TaskStatus.COMPLETED)) {
            return restartInCurrentConversation(state);
        }
        if (stopped(state)) return stoppedResponse(state);
        if ("RETURN_TO_FLOW".equals(action)) {
            state.dialogueMode = ConversationState.DialogueMode.FOLLOWUP_FLOW;
            state.taskStatus = ConversationState.TaskStatus.ACTIVE;
            return continueCurrentFlow(state, ExtractedFacts.empty());
        }
        state.dialogueMode = ConversationState.DialogueMode.FOLLOWUP_FLOW;
        if (action.startsWith("SET_") || action.startsWith("CHANGE_")
                || List.of("START_PLAN", "SELECT_SLOT", "CONTINUE").contains(action)) {
            state.taskStatus = ConversationState.TaskStatus.ACTIVE;
        }
        if ("CANCEL_TASK".equals(action)) return cancelTask(state);
        if ("RETRY_EXECUTION".equals(action) && state.stage == ConversationState.Stage.PARTIAL) {
            return buildConfirmation(state);
        }
        if ("EDIT_BOOKING".equals(action) && state.appointmentId != null) {
            if (state.stage != ConversationState.Stage.COMPLETED) return respond(state, "请先补办未完成事项，或取消预约。", bookedActions(state));
            state.originalAppointmentId = state.appointmentId;
            state.appointmentId = null;
            state.materialReminderDone = false;
            state.departureReminderDone = false;
            state.notificationDone = false;
            state.pendingAction = "CREATE";
            invalidate(state);
            return respondWithPlan(state, "原预约仍然有效。请修改草稿，确认新方案后才会变更原预约。",
                    List.of(q("修改日期", "CHANGE_DATE", ""), q("修改医院", "CHANGE_HOSPITAL", "")));
        }
        if (state.appointmentId != null && !List.of("CANCEL_APPOINTMENT", "QUERY_APPOINTMENTS",
                "OPEN_TASKS", "NEW_BOOKING", "CONTACT_HUMAN", "CANCEL_TASK",
                "RETRY_EXECUTION", "EDIT_BOOKING").contains(action))
            return respondWithPlan(state, "已有预约已保留，请选择下一步。", bookedActions(state));
        if ("KEEP_CONFLICT".equals(action) && state.stage != ConversationState.Stage.CONFLICT)
            return respond(state, "请先检查当前日程。", List.of(q("检查计划", "START_PLAN", "")));
        if (action.startsWith("SET_") || action.startsWith("CHANGE_") || "SELECT_SLOT".equals(action)) invalidate(state);
        switch (action) {
            case "SET_HOSPITAL" -> { resetAfterHospital(state); chooseHospital(state, safeValue); }
            case "SET_DEPARTMENT" -> { resetAfterDate(state); chooseDepartment(state, safeValue); }
            case "SET_DATE" -> { resetAfterDate(state); state.date = LocalDate.parse(safeValue); return querySlots(state); }
            case "SET_PERIOD" -> { return recommendPeriod(state, safeValue); }
            case "SHOW_PERIOD_SLOTS" -> { return showPeriodSlots(state, safeValue); }
            case "SET_ALTERNATIVE" -> { state.acceptAlternative = Boolean.parseBoolean(safeValue); if (state.selectedSlot == null && state.date != null) return querySlots(state); }
            case "SET_COMPANION" -> state.needCompanion = Boolean.parseBoolean(safeValue);
            case "SET_TRAVEL" -> state.needTravel = Boolean.parseBoolean(safeValue);
            case "SET_TRANSPORT" -> state.transport = safeValue;
            case "SET_NOTIFY" -> { state.notifyFamily = Boolean.parseBoolean(safeValue); if (!state.notifyFamily) state.contact = null; }
            case "SET_CONTACT" -> { state.contact = catalog.contacts(state.userId).stream().filter(c -> c.id().equals(safeValue)).findFirst().orElseThrow(() -> new IllegalArgumentException("请选择当前用户的家属")); }
            case "EDIT_PREFERENCES" -> { state.needCompanion = null; state.needTravel = null; state.notifyFamily = null; state.contact = null; invalidate(state); }
            case "START_PLAN" -> { return ready(state) ? checkSchedule(state) : advance(state, ExtractedFacts.empty()); }
            case "RETRY_QUERY" -> { return querySlots(state); }
            case "SHOW_AVAILABLE_DATES" -> { return showAvailableSlots(state); }
            case "NEW_BOOKING" -> { return restartInCurrentConversation(state); }
            case "SELECT_SLOT" -> { return selectSlot(state, safeValue); }
            case "KEEP_CONFLICT" -> { state.scheduleChecked = true; return buildConfirmation(state); }
            case "CHANGE_DATE" -> {
                discardInterruption(state);
                resetAfterDate(state);
                return askDate(state, "好的，尚未提交预约。请告诉我新的复诊日期。");
            }
            case "CHANGE_HOSPITAL" -> {
                discardInterruption(state);
                resetAfterHospital(state);
                return askHospital(state, "好的，尚未提交预约。请重新选择医院。");
            }
            case "CHANGE_DEPARTMENT" -> {
                discardInterruption(state);
                resetAfterDepartment(state);
                return askDepartment(state, "好的，尚未提交预约。请重新选择复诊科室。");
            }
            case "CHANGE_TIME" -> { return changeTime(state, ExtractedFacts.empty()); }
            case "CANCEL_APPOINTMENT" -> {
                return beginCancelExistingAppointment(state, ExtractedFacts.empty());
            }
            case "QUERY_APPOINTMENTS" -> { return queryMyAppointments(state, ExtractedFacts.empty()); }
            case "QUERY_CARE_GUIDE" -> { return showCareGuide(state, "复诊办理流程"); }
            case "SELECT_APPOINTMENT_TO_CANCEL" -> { return prepareExistingCancellation(state, safeValue); }
            case "SELECT_TRAVEL_APPOINTMENT" -> { return selectTravelAppointment(state, safeValue); }
            case "CANCEL_TASK" -> { return cancelTask(state); }
            case "RESUME_INTERRUPTED" -> { return resumeInterruptedTask(state); }
            case "CONTINUE" -> {
                state.taskStatus = ConversationState.TaskStatus.ACTIVE;
                return state.interruptedStage == null
                        ? advance(state, ExtractedFacts.empty())
                        : resumeInterruptedTask(state);
            }
            case "CONTACT_HUMAN" -> {
                return respond(state, "已为您保留当前办理进度。这里是模拟人工帮助入口，工作人员可以接着处理。",
                        List.of(q("继续办理", "CONTINUE", "")));
            }
            case "ASK_HUMAN_INPUT" -> {
                return respond(state, "没关系，您可以直接说出知道的信息，我会一次只问一个问题。",
                        List.of());
            }
            default -> {
                return respond(state, "这个操作暂时无法识别，请重新选择。", List.of(q("继续办理", "CONTINUE", "")));
            }
        }
        return advance(state, ExtractedFacts.empty());
    }

    public synchronized AgentTurnResponse confirm(String conversationId, boolean approved, String confirmationId) {
        ConversationState state = requireSession(conversationId);
        if (stopped(state)) return stoppedResponse(state);
        if (state.stage != ConversationState.Stage.AWAITING_CONFIRMATION || confirmationId == null
                || !confirmationId.equals(state.confirmationId)) {
            return respondWithPlan(state, "这份确认已经失效或已办理，请查看当前计划后重新确认。",
                    state.appointmentId == null ? List.of(q("检查计划", "START_PLAN", "")) : bookedActions(state));
        }
        state.confirmationId = null;
        conversations.addMessage(state.id, "user", approved ? "[确认] 执行操作" : "[确认] 暂不执行");
        if (!approved) {
            if ("CANCEL".equals(state.pendingAction) || "CANCEL_EXISTING".equals(state.pendingAction)) {
                state.pendingAction = "CREATE";
                state.pendingAppointmentId = null;
                if (state.interruptedStage != null) {
                    state.stage = state.interruptedStage;
                    return respond(state, "好的，原预约已经保留。您要继续刚才的复诊办理吗？",
                            resumeReplies(state));
                }
                state.stage = ConversationState.Stage.COMPLETED;
                state.taskStatus = ConversationState.TaskStatus.COMPLETED;
                return respond(state, "好的，原预约已经保留，没有执行取消操作。",
                        List.of(q("查询我的预约", "QUERY_APPOINTMENTS", ""), q("重新办理", "NEW_BOOKING", "")));
            }
            state.pendingAction = "CREATE";
            state.stage = state.appointmentId == null ? ConversationState.Stage.READY_TO_PLAN
                    : (state.materialReminderDone && (!Boolean.TRUE.equals(state.needTravel) || state.departureReminderDone)
                    && (!Boolean.TRUE.equals(state.notifyFamily) || state.notificationDone)
                    ? ConversationState.Stage.COMPLETED : ConversationState.Stage.PARTIAL);
            state.taskStatus = state.appointmentId == null
                    ? ConversationState.TaskStatus.ACTIVE : ConversationState.TaskStatus.COMPLETED;
            return respondWithPlan(state, "没有执行本次操作，已有预约保留。您可以返回修改。",
                    state.appointmentId == null ? List.of(q("修改日期", "CHANGE_DATE", ""), q("修改偏好", "EDIT_PREFERENCES", ""), q("检查计划", "START_PLAN", "")) : bookedActions(state));
        }
        try {
            if ("CANCEL".equals(state.pendingAction) || "CANCEL_EXISTING".equals(state.pendingAction)) {
                String targetId = state.pendingAppointmentId != null ? state.pendingAppointmentId : state.appointmentId;
                appointmentTool.cancel(state.id, targetId, state.userId);
                state.pendingAction = "CREATE";
                state.pendingAppointmentId = null;
                if (targetId != null && targetId.equals(state.appointmentId)) state.appointmentId = null;
                if (state.interruptedStage != null) {
                    state.stage = state.interruptedStage;
                    return respond(state, "这条已确认预约已经取消，原模拟号源已释放。要继续刚才未完成的办理吗？",
                            resumeReplies(state));
                }
                state.stage = ConversationState.Stage.CANCELLED;
                state.taskStatus = ConversationState.TaskStatus.CANCELLED;
                return respond(state, "预约已取消，原模拟号源已经释放。",
                        List.of(q("查询我的预约", "QUERY_APPOINTMENTS", ""), q("重新办理", "NEW_BOOKING", "")));
            }
            if (!ready(state) || !state.scheduleChecked || state.travelPlan == null) {
                state.stage = ConversationState.Stage.READY_TO_PLAN;
                return advance(state, ExtractedFacts.empty());
            }
            if (state.appointmentId == null) {
                state.appointmentId = state.originalAppointmentId == null
                        ? appointmentTool.submit(state.id, state.selectedSlot.id(), state.userId)
                        : appointmentTool.reschedule(state.id, state.originalAppointmentId, state.selectedSlot.id(), state.userId);
                state.originalAppointmentId = null;
                conversations.save(state, null);
            }
            LocalDateTime at = LocalDateTime.of(state.selectedSlot.date(), state.selectedSlot.time());
            if (!state.materialReminderDone) {
                callTool(state, "schedule.createReminder", Map.of("title", "复诊材料准备提醒", "remindAt", at.minusDays(1)),
                        () -> scheduleTool.createReminder(state.id, state.userId, "复诊材料准备提醒", at.minusDays(1)));
                state.materialReminderDone = true;
                conversations.save(state, null);
            }
            if (Boolean.TRUE.equals(state.needTravel) && !state.departureReminderDone) {
                callTool(state, "schedule.createReminder", Map.of("title", "复诊出发提醒", "remindAt", state.travelPlan.departureAt().minusMinutes(10)),
                        () -> scheduleTool.createReminder(state.id, state.userId, "复诊出发提醒", state.travelPlan.departureAt().minusMinutes(10)));
                state.departureReminderDone = true;
                conversations.save(state, null);
            }
            if (Boolean.TRUE.equals(state.notifyFamily) && !state.notificationDone) {
                callTool(state, "family.notify", Map.of("contactId", state.contact.id(), "message", notificationMessage(state)),
                        () -> familyTool.notify(state.id, state.contact.id(), notificationMessage(state)));
                state.notificationDone = true;
                conversations.save(state, null);
            }
            materialPreparationTool.initialize(state.id, state.appointmentId, state.department, state.materials);
            state.stage = ConversationState.Stage.COMPLETED;
            state.taskStatus = ConversationState.TaskStatus.COMPLETED;
            return completionResult(state);
        } catch (RuntimeException error) {
            return toolError(state, error);
        }
    }

    private AgentTurnResponse executionResult(ConversationState state, String message) {
        ResultCard card = recordResult(state);
        return finish(state, new AgentTurnResponse(state.id, state.stage.name(), message, bookedActions(state),
                plan(state), null, card, traces.findByConversation(state.id)));
    }

    /**
     * 预约完成路径：口播与文字全部由权威 ResultCard 字段确定性拼接，不经过回答模型改写，避免关键事实被压缩或变形。
     */
    private AgentTurnResponse completionResult(ConversationState state) {
        ResultCard card = recordResult(state);
        String narration = completionNarration(card);
        List<QuickReply> actions = new ArrayList<>();
        actions.add(q("查看地图和院内指引", "OPEN_TRAVEL", card.appointmentId()));
        actions.addAll(bookedActions(state));
        AgentTurnResponse response = new AgentTurnResponse(state.id, state.stage.name(), narration, actions,
                plan(state), null, card, traces.findByConversation(state.id), null, narration, null);
        return finishWithoutModel(state, response);
    }

    private ResultCard recordResult(ConversationState state) {
        String reminders = reminderStatusLabel(state);
        String family = familyStatusLabel(state);
        appointmentRecords.complete(state.appointmentId, state, reminders, family);
        return new ResultCard(state.appointmentId, state.hospital, state.department,
                state.date.format(DATE_LABEL), state.selectedSlot.time().format(TIME_LABEL), state.materials,
                state.travelPlan == null ? "未提供出发建议（路线或时间信息不足）" : state.travelPlan.departureAt().format(TIME_LABEL), reminders, family);
    }

    private String reminderStatusLabel(ConversationState state) {
        return !state.materialReminderDone ? "复诊提醒未完成"
                : Boolean.TRUE.equals(state.needTravel) && !state.departureReminderDone ? "复诊提醒已创建，出发提醒未完成"
                : Boolean.TRUE.equals(state.needTravel) ? "复诊及出发提醒已创建" : "复诊提醒已创建，不创建出发提醒";
    }

    private String familyStatusLabel(ConversationState state) {
        return !Boolean.TRUE.equals(state.notifyFamily) ? "无需通知家属"
                : !state.notificationDone ? "家属通知未完成" : "已通知" + state.contact.relationship() + state.contact.name();
    }

    /**
     * 只用 ResultCard 中已有的权威字段拼接：不得虚构交通方式、耗时、距离、楼层或诊室。
     */
    private String completionNarration(ResultCard card) {
        StringBuilder text = new StringBuilder("已经为您预约成功。");
        text.append("复诊时间是").append(card.date()).append(spokenTime(card.time())).append("，");
        text.append("医院是").append(card.hospital()).append("，科室是").append(card.department()).append("。");
        text.append(card.materials().isEmpty() ? "具体携带材料请以医院通知为准。"
                : "请携带" + String.join("、", card.materials()) + "。");
        text.append(card.departureTime().startsWith("未提供") ? "暂时没有计算出发时间。"
                : "建议" + spokenTime(card.departureTime()) + "出发。");
        text.append(card.reminderStatus()).append("。").append(card.familyStatus()).append("。");
        text.append("需要我现在打开地图，告诉您怎么走吗？");
        return text.toString();
    }

    private String spokenTime(String value) {
        try {
            LocalTime time = LocalTime.parse(value.length() > 5 ? value.substring(11, 16) : value);
            String period = time.isBefore(LocalTime.NOON) ? "上午" : "下午";
            int hour = time.getHour() > 12 ? time.getHour() - 12 : time.getHour();
            return minuteLabel(period + hour + "点", time.getMinute());
        } catch (RuntimeException ignored) {
            return value == null || value.isBlank() ? "时间待确认" : value;
        }
    }

    private String minuteLabel(String prefix, int minute) {
        return minute == 0 ? prefix : prefix + minute + "分";
    }

    private List<QuickReply> bookedActions(ConversationState state) {
        return List.of(q("查看事项", "OPEN_TASKS", ""),
                state.stage == ConversationState.Stage.PARTIAL ? q("补办未完成事项", "RETRY_EXECUTION", "") : q("修改预约", "EDIT_BOOKING", ""),
                q("取消预约", "CANCEL_APPOINTMENT", ""), q("新建办理", "NEW_BOOKING", ""));
    }

    private <T> T callTool(ConversationState state, String name, Map<String, ?> parameters, java.util.function.Supplier<T> operation) {
        try { return operation.get(); }
        catch (RuntimeException error) {
            traces.record(state.id, name, parameters, Map.of("error", String.valueOf(error.getMessage())), false);
            throw error;
        }
    }

    private AgentTurnResponse toolError(ConversationState state, RuntimeException error) {
        state.confirmationId = null;
        traces.record(state.id, "workflow.error", Map.of("stage", state.stage.name()), Map.of("error", String.valueOf(error.getMessage())), false);
        if (state.appointmentId != null) {
            if ("CANCEL".equals(state.pendingAction)) {
                state.pendingAction = "CREATE";
                state.stage = ConversationState.Stage.COMPLETED;
                state.taskStatus = ConversationState.TaskStatus.COMPLETED;
                return respondWithPlan(state, "取消未完成，原预约保留：" + error.getMessage(), bookedActions(state));
            }
            state.stage = ConversationState.Stage.PARTIAL;
            state.taskStatus = ConversationState.TaskStatus.COMPLETED;
            return executionResult(state, "预约已保留，后续事项未全部完成：" + error.getMessage() + "。可补办未完成事项，不会重复预约。");
        }
        state.stage = ConversationState.Stage.TOOL_ERROR;
        state.taskStatus = ConversationState.TaskStatus.ACTIVE;
        return respondWithPlan(state, "本步骤未完成：" + error.getMessage() + "。已保留信息，请重试或修改。",
                List.of(q("重试检查", "START_PLAN", ""), q("修改日期", "CHANGE_DATE", ""), q("人工帮助", "CONTACT_HUMAN", "")));
    }

    private boolean stopped(ConversationState state) {
        return state.stage == ConversationState.Stage.CANCELLED || state.stage == ConversationState.Stage.EMERGENCY_PAUSED;
    }

    private AgentTurnResponse stoppedResponse(ConversationState state) {
        return respond(state, state.stage == ConversationState.Stage.EMERGENCY_PAUSED
                ? "普通办理已暂停，请及时联系急救服务或身边人员。旧操作不会继续执行。"
                : "本次办理已经停止。已有预约记录仍可在事项页查看。", List.of(q("查看事项", "OPEN_TASKS", ""), q("新建办理", "NEW_BOOKING", "")));
    }

    private AgentTurnResponse emergency(ConversationState state) {
        state.confirmationId = null;
        if (state.taskStatus == ConversationState.TaskStatus.ACTIVE) {
            state.taskStatus = ConversationState.TaskStatus.PAUSED;
        }
        state.stage = ConversationState.Stage.EMERGENCY_PAUSED;
        return emergencyNotice(state);
    }

    /** 紧急暂停期间的统一安全提示：只给文字和按钮，绝不自动跳转页面。 */
    private AgentTurnResponse emergencyNotice(ConversationState state) {
        return respondWithoutModel(state, "这可能是紧急情况。请立即联系身边人员，拨打120或寻求线下急救帮助。普通办理已暂停，已有记录保留。",
                List.of(q("人工帮助", "CONTACT_HUMAN", "")));
    }

    private void invalidate(ConversationState state) {
        state.confirmationId = null;
        state.scheduleChecked = false;
        state.travelPlan = null;
        if (state.stage == ConversationState.Stage.AWAITING_CONFIRMATION) state.stage = ConversationState.Stage.READY_TO_PLAN;
    }

    private boolean ready(ConversationState state) {
        return state.hospitalId != null && state.department != null && state.selectedSlot != null
                && state.date != null && state.date.equals(state.selectedSlot.date())
                && state.hospitalId.equals(state.selectedSlot.hospitalId()) && state.department.equals(state.selectedSlot.department())
                && state.acceptAlternative != null && state.needCompanion != null && state.needTravel != null
                && state.transport != null && state.notifyFamily != null
                && (!state.notifyFamily || state.contact != null) && !state.materials.isEmpty();
    }

    private AgentTurnResponse validateDraftForModel(ConversationState state) {
        return validateDraftForModel(state, ExtractedFacts.empty());
    }

    private AgentTurnResponse validateDraftForModel(ConversationState state, ExtractedFacts facts) {
        // 模型把意图判成“检查草稿”时会绕开工作流，这一轮说出的家属姓名也就没人落字段，
        // 于是老人刚说完“小丽”，系统回头又问“要通知哪一位”。这里先把本轮字段补上再往下走。
        applyFacts(state, facts);
        syncPresentationStage(state);
        List<String> missing = draftMissingFields(state);
        if (missing.isEmpty()) {
            return respondWithPlan(state, "当前预约草稿的信息已经齐全，可以检查重复预约和日程冲突，然后进入最终确认。",
                    List.of(q("检查并确认", "START_PLAN", "")));
        }
        // “还缺少：家属联系人”对老人是句没法执行的话：他刚说了要通知谁，系统却说缺联系人。
        // 这里也要兜一道，把老人这一轮说的称呼一并带过去认。
        if (Boolean.TRUE.equals(state.notifyFamily) && state.contact == null) {
            return askContactFromCatalog(state, facts.familyContact());
        }
        return respondWithPlan(state, "当前预约草稿已经保留。还缺少：" + String.join("、", missing)
                        + "。请根据我们的对话继续补充其中最合适的一项。",
                modelSuggestedReplies(state));
    }

    private List<String> draftMissingFields(ConversationState state) {
        List<String> missing = new ArrayList<>();
        if (state.hospital == null) missing.add("医院");
        if (state.department == null) missing.add("科室");
        if (state.date == null) missing.add("日期");
        if (state.selectedSlot == null) missing.add("具体时间");
        if (state.acceptAlternative == null) missing.add("是否接受附近日期");
        if (state.needCompanion == null) missing.add("是否需要陪同");
        if (state.needTravel == null) missing.add("是否需要出行提醒");
        if (state.transport == null) missing.add("交通方式");
        if (state.notifyFamily == null) missing.add("是否通知家属");
        if (Boolean.TRUE.equals(state.notifyFamily) && state.contact == null) missing.add("家属联系人");
        return missing;
    }

    private String notificationMessage(ConversationState state) {
        return "复诊安排：" + state.hospital + " " + state.department + "，" + state.date.format(DATE_LABEL)
                + " " + state.selectedSlot.time().format(TIME_LABEL) + (Boolean.TRUE.equals(state.needCompanion) ? "，需要陪同。" : "，不需要陪同。");
    }

    public Map<String, Object> modelStatus() {
        return Map.of("understandingMode", agentRuntime.mode(), "planningMode", agentRuntime.mode(),
                "architecture", agentRuntime.modelAvailable()
                        ? "MODEL_ORCHESTRATED_TOOL_AGENT" : "RULE_WORKFLOW_FALLBACK",
                "promptMode", "SINGLE_MAIN_AGENT_PROMPT",
                "answerMode", answerGenerator.mode(),
                "provider", answerGenerator.providerName(), "model", answerGenerator.modelName(),
                "secretStored", false);
    }

    private AgentTurnResponse queryMyAppointments(ConversationState state, ExtractedFacts facts) {
        rememberInterruptedTask(state, "QUERY_MY_APPOINTMENTS");
        List<AppointmentSummary> rows = myAppointmentTool.search(
                state.id, state.userId, facts.date(), facts.hospital(), facts.department());
        state.sideTask = null;
        if (rows.isEmpty()) {
            return respond(state, "没有找到符合条件的已确认复诊预约。",
                    resumeReplies(state, q("办理新的复诊", "NEW_BOOKING", "")));
        }

        String summary = rows.stream().limit(4).map(this::appointmentSummary)
                .collect(java.util.stream.Collectors.joining("；"));
        List<QuickReply> choices = new ArrayList<>();
        if (state.interruptedStage != null) choices.add(q("继续刚才办理", "RESUME_INTERRUPTED", ""));
        for (AppointmentSummary item : rows.stream().limit(3).toList()) {
            choices.add(q("取消" + item.date().format(DATE_LABEL) + "预约",
                    "SELECT_APPOINTMENT_TO_CANCEL", item.appointmentId()));
        }
        ResultCard result = rows.size() == 1 ? resultCard(rows.get(0)) : null;
        return finish(state, new AgentTurnResponse(state.id, state.stage.name(),
                "我从数据库查到" + rows.size() + "条已确认预约：" + summary + "。",
                choices, null, null, result, traces.findByConversation(state.id)));
    }

    private AgentTurnResponse beginCancelExistingAppointment(ConversationState state, ExtractedFacts facts) {
        rememberInterruptedTask(state, "CANCEL_EXISTING_APPOINTMENT");
        List<AppointmentSummary> rows = myAppointmentTool.search(
                state.id, state.userId, facts.date(), facts.hospital(), facts.department());
        if (rows.isEmpty()) {
            state.sideTask = null;
            return respond(state, "没有找到符合条件的已确认预约，所以没有执行取消。",
                    resumeReplies(state, q("查询我的预约", "QUERY_APPOINTMENTS", "")));
        }
        if (rows.size() == 1) return prepareExistingCancellation(state, rows.get(0));

        List<QuickReply> choices = rows.stream().limit(4)
                .map(item -> q(item.date().format(DATE_LABEL) + " " + item.time().format(TIME_LABEL)
                                + " " + item.hospital(),
                        "SELECT_APPOINTMENT_TO_CANCEL", item.appointmentId()))
                .toList();
        return respond(state, "我查到多条已确认预约。为防止取消错，请选择要取消的那一条。", choices);
    }

    /**
     * 取消支线中把“最近的一次、最早的一次、上午那个、某日期的、某医院那条”解析成唯一一条候选。
     * 只生成确认卡，绝不直接取消；“好的”“继续”这类没有指向的说法不会被当成选择。
     * 无法唯一确定时明确询问，不猜测。
     */
    private AgentTurnResponse resolveCancellationCandidate(ConversationState state, String message) {
        boolean latest = containsAny(message, "最近", "最新");
        // 不能写成“第一”：那会把“市第一医院那条”误判成取最早的一条。
        boolean earliest = containsAny(message, "最早");
        boolean morning = containsAny(message, "上午", "早上");
        boolean afternoon = containsAny(message, "下午", "午后");
        LocalDate wantedDate = mentionedDate(message);
        String wantedHospital = catalog.hospitalNames().stream().filter(message::contains).findFirst().orElse(null);
        if (!latest && !earliest && !morning && !afternoon && wantedDate == null && wantedHospital == null) {
            return null;
        }

        List<AppointmentSummary> candidates = myAppointmentTool.search(state.id, state.userId, null, null, null);
        if (wantedDate != null) candidates = candidates.stream().filter(item -> item.date().equals(wantedDate)).toList();
        if (wantedHospital != null) {
            String hospital = wantedHospital;
            candidates = candidates.stream().filter(item -> item.hospital().contains(hospital)).toList();
        }
        if (morning) candidates = candidates.stream().filter(item -> item.time().isBefore(LocalTime.NOON)).toList();
        if (afternoon) candidates = candidates.stream().filter(item -> !item.time().isBefore(LocalTime.NOON)).toList();
        if (candidates.isEmpty()) {
            return respond(state, "没有找到符合这个条件的已确认预约，所以没有执行任何取消操作。",
                    resumeReplies(state, q("查询我的预约", "QUERY_APPOINTMENTS", "")));
        }
        if (candidates.size() > 1 && latest) {
            LocalDateTime now = LocalDateTime.now();
            candidates = List.of(candidates.stream().min(Comparator.comparingLong(item ->
                    Math.abs(java.time.Duration.between(now, appointmentAt(item)).toMinutes()))).orElseThrow());
        } else if (candidates.size() > 1 && earliest) {
            candidates = List.of(candidates.stream().min(Comparator.comparing(this::appointmentAt)).orElseThrow());
        }
        if (candidates.size() > 1) {
            return respond(state, "有多条已确认预约符合这个说法。为防止取消错，请选择要取消的那一条。",
                    candidates.stream().limit(4).map(item -> q(item.date().format(DATE_LABEL) + " "
                                    + item.time().format(TIME_LABEL) + " " + item.hospital(),
                            "SELECT_APPOINTMENT_TO_CANCEL", item.appointmentId())).toList());
        }
        return prepareExistingCancellation(state, candidates.get(0));
    }

    private LocalDate mentionedDate(String message) {
        Matcher matcher = SPOKEN_DATE.matcher(message);
        if (!matcher.find()) return null;
        try {
            LocalDate today = LocalDate.now();
            LocalDate candidate = LocalDate.of(today.getYear(), Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)));
            return candidate.isBefore(today.minusDays(1)) ? candidate.plusYears(1) : candidate;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private AgentTurnResponse prepareExistingCancellation(ConversationState state, String appointmentId) {
        AppointmentSummary target = myAppointmentTool.search(state.id, state.userId, null, null, null)
                .stream().filter(item -> item.appointmentId().equals(appointmentId)).findFirst().orElse(null);
        if (target == null) {
            return respond(state, "这条预约已经不存在或已被取消，我没有执行任何操作。",
                    resumeReplies(state, q("查询我的预约", "QUERY_APPOINTMENTS", "")));
        }
        return prepareExistingCancellation(state, target);
    }

    private AgentTurnResponse prepareExistingCancellation(ConversationState state, AppointmentSummary target) {
        state.pendingAction = "CANCEL_EXISTING";
        state.pendingAppointmentId = target.appointmentId();
        state.sideTask = "CANCEL_EXISTING_APPOINTMENT";
        state.confirmationId = UUID.randomUUID().toString();
        state.stage = ConversationState.Stage.AWAITING_CONFIRMATION;
        state.taskStatus = ConversationState.TaskStatus.AWAITING_CONFIRMATION;
        ConfirmationCard card = new ConfirmationCard("确认取消已经预约的复诊吗？",
                List.of("取消预约：" + appointmentSummary(target), "释放对应模拟号源"),
                "取消后原预约失效；如果仍需复诊，需要重新预约。",
                "确认取消预约", "保留预约", state.confirmationId);
        return finish(state, new AgentTurnResponse(state.id, state.stage.name(),
                "取消预约属于重要操作，需要您明确确认。", List.of(), null, card,
                resultCard(target), traces.findByConversation(state.id)));
    }

    private AgentTurnResponse restartInCurrentConversation(ConversationState state) {
        boolean firstBooking = state.taskStatus == ConversationState.TaskStatus.NONE;
        clearDraft(state);
        state.taskStatus = ConversationState.TaskStatus.ACTIVE;
        state.dialogueMode = ConversationState.DialogueMode.FOLLOWUP_FLOW;
        String opening = firstBooking
                ? "好的，我们开始办理复诊。请告诉我就诊医院。"
                : "好的，我们重新开始办理复诊。请告诉我就诊医院。";
        return askHospital(state, opening);
    }

    private AgentTurnResponse resumeInterruptedTask(ConversationState state) {
        state.taskStatus = ConversationState.TaskStatus.ACTIVE;
        state.dialogueMode = ConversationState.DialogueMode.FOLLOWUP_FLOW;
        if (state.interruptedStage == null) return advance(state, ExtractedFacts.empty());
        ConversationState.Stage target = state.interruptedStage;
        String previousAction = state.interruptedPendingAction;
        String previousTarget = state.interruptedPendingAppointmentId;
        clearInterruption(state);
        state.stage = target;
        state.pendingAction = previousAction == null ? "CREATE" : previousAction;
        state.pendingAppointmentId = previousTarget;
        if (target == ConversationState.Stage.AWAITING_CONFIRMATION
                && "CREATE".equals(state.pendingAction) && state.selectedSlot != null) {
            return buildConfirmation(state);
        }
        return advance(state, ExtractedFacts.empty());
    }

    private AgentTurnResponse changeHospital(ConversationState state, ExtractedFacts facts, String originalMessage) {
        discardInterruption(state);
        resetAfterHospital(state);
        if (facts.hospital() == null && containsAny(originalMessage,
                "换医院", "换个医院", "换家医院", "换一个医院", "其他医院", "修改医院")) {
            return askHospital(state, "好的，当前选择已清除。请告诉我新的医院名称，我会查询目录并请您确认。原来的预约不会被直接修改。");
        }
        String mention = firstNonBlank(facts.hospital(), originalMessage);
        return resolveHospitalInput(state, mention);
    }

    private AgentTurnResponse changeDepartment(ConversationState state, ExtractedFacts facts, String originalMessage) {
        discardInterruption(state);
        if (state.hospitalId == null) return askHospital(state, "修改科室前，请先选择医院。");
        resetAfterDepartment(state);
        if (facts.department() == null && containsAny(originalMessage,
                "换科室", "换个科室", "换一个科室", "其他科室", "修改科室", "改科室")) {
            return askDepartment(state, "好的，请重新说医生安排的复诊科室。原来的选择已经清除。");
        }
        String mention = firstNonBlank(facts.department(), originalMessage);
        return resolveDepartmentInput(state, mention);
    }

    private AgentTurnResponse changeDate(ConversationState state, ExtractedFacts facts) {
        discardInterruption(state);
        resetAfterDate(state);
        if (facts.date() == null) return askDate(state, "好的，请告诉我新的复诊日期。");
        if (facts.date().isBefore(LocalDate.now()) || facts.date().isAfter(LocalDate.now().plusMonths(1))) {
            return respond(state, "目前可以查询今天到一个月后的模拟号源，请重新选择日期。",
                    List.of(q("查看可预约日期", "SHOW_AVAILABLE_DATES", "")));
        }
        state.date = facts.date();
        return state.hospitalId != null && state.department != null ? querySlots(state) : advance(state, facts);
    }

    private AgentTurnResponse changeTime(ConversationState state, ExtractedFacts facts) {
        discardInterruption(state);
        state.selectedSlot = null;
        state.recommendedSlot = null;
        state.requestedTime = null;
        state.travelPlan = null;
        if (state.date == null) return askDate(state, "修改时间前，请先选择复诊日期。");
        if (state.alternatives.isEmpty()) return querySlots(state);
        if (facts.selectedTime() != null) return recommendSpecificTime(state, facts.selectedTime());
        if (facts.timePreference() != null) return recommendPeriod(state, facts.timePreference());
        state.stage = ConversationState.Stage.SELECT_PERIOD;
        return respond(state, "好的，请重新选择上午、下午或具体时间。", periodReplies(state.alternatives));
    }

    /** 医院口语匹配：目录工具给事实，大模型回答节点只负责把追问说自然。 */
    private AgentTurnResponse resolveHospitalInput(ConversationState state, String utterance) {
        List<HospitalProfile> rows = hospitalCatalogTool.listHospitals(state.id);
        CatalogEntityResolver.Match match = entityResolver.hospital(utterance, rows);
        if (match.type() == CatalogEntityResolver.MatchType.EXACT) {
            chooseHospital(state, match.only().id());
            clearPendingEntity(state);
            return advance(state, ExtractedFacts.empty());
        }
        if (match.type() == CatalogEntityResolver.MatchType.UNIQUE_APPROXIMATE) {
            rememberEntityCandidate(state, "HOSPITAL", match);
            return respond(state, "我把您说的“" + match.raw() + "”理解为“" + match.only().name()
                            + "”。请确认是不是这家医院；如果不是，请重新说医院全名或院区。",
                    List.of(q("是这家医院", "CONFIRM_ENTITY", ""), q("不是，重新说", "REJECT_ENTITY", "")));
        }
        if (match.type() == CatalogEntityResolver.MatchType.AMBIGUOUS) {
            String names = match.candidates().stream().map(CatalogEntityResolver.Candidate::name)
                    .collect(java.util.stream.Collectors.joining("、"));
            return respond(state, "我听到您说“" + match.raw() + "”，但查到了多个相近医院：" + names
                            + "。请再说完整医院名称或院区，我确认后再继续。",
                    List.of(q("重新说医院", "ASK_HUMAN_INPUT", "")));
        }
        if (match.type() == CatalogEntityResolver.MatchType.UNCLEAR) {
            return respond(state, "没关系，如果暂时不确定医院，可以看看就诊记录、预约通知，或者问家属和医院服务台。确认后直接告诉我医院名称。",
                    List.of(q("查询可办理医院", "QUERY_HOSPITALS", "")));
        }
        String supported = rows.stream().map(HospitalProfile::name)
                .collect(java.util.stream.Collectors.joining("、"));
        return respond(state, "我听到您想去“" + match.raw() + "”，但查询医院目录后没有找到完全对应的医院，"
                        + "所以没有替您随便选择。目前系统可办理：" + supported
                        + "。请换一个医院名称，或联系人工确认。",
                List.of(q("联系人工", "CONTACT_HUMAN", "")));
    }

    /** 科室口语匹配：简称可形成候选；症状描述不在这里推断科室。 */
    private AgentTurnResponse resolveDepartmentInput(ConversationState state, String utterance) {
        List<DepartmentProfile> rows = departmentCatalogTool.listDepartments(state.id, state.hospitalId);
        CatalogEntityResolver.Match match = entityResolver.department(utterance, rows);
        if (match.type() == CatalogEntityResolver.MatchType.EXACT) {
            chooseDepartment(state, match.only().id());
            clearPendingEntity(state);
            return advance(state, ExtractedFacts.empty());
        }
        if (match.type() == CatalogEntityResolver.MatchType.UNIQUE_APPROXIMATE) {
            rememberEntityCandidate(state, "DEPARTMENT", match);
            return respond(state, "我把您说的“" + match.raw() + "”理解为“" + match.only().name()
                            + "”。请确认是不是这个复诊科室；如果不是，请重新说医生安排的科室。",
                    List.of(q("是这个科室", "CONFIRM_ENTITY", ""), q("不是，重新说", "REJECT_ENTITY", "")));
        }
        if (match.type() == CatalogEntityResolver.MatchType.AMBIGUOUS) {
            String names = match.candidates().stream().map(CatalogEntityResolver.Candidate::name)
                    .collect(java.util.stream.Collectors.joining("、"));
            return respond(state, "“" + match.raw() + "”可能对应多个科室：" + names
                            + "。我不能替您猜，请说完整科室名称，或按照医生安排和就诊单确认。",
                    List.of(q("重新说科室", "ASK_HUMAN_INPUT", "")));
        }
        if (match.type() == CatalogEntityResolver.MatchType.UNCLEAR) {
            return respond(state, "没关系，请查看上次病历或医生交代的复诊科室。仍不确定时可以咨询医院导诊台，我不会根据症状替您判断科室。",
                    List.of(q("查询这家医院的科室", "QUERY_DEPARTMENTS", "")));
        }
        String supported = rows.stream().map(DepartmentProfile::name)
                .collect(java.util.stream.Collectors.joining("、"));
        return respond(state, "我查询了" + state.hospital + "的科室目录，没有找到与“" + match.raw()
                        + "”对应的科室。当前可办理：" + supported
                        + "。请按医生安排重新说科室名称，或咨询医院导诊台。",
                List.of(q("查询科室说明", "QUERY_DEPARTMENTS", ""), q("联系人工", "CONTACT_HUMAN", "")));
    }

    private void rememberEntityCandidate(ConversationState state, String type, CatalogEntityResolver.Match match) {
        state.pendingEntityType = type;
        state.pendingEntityId = match.only().id();
        state.pendingEntityName = match.only().name();
        state.pendingEntityRaw = match.raw();
    }

    private AgentTurnResponse handlePendingEntityConfirmation(ConversationState state, String message,
                                                               ExtractedFacts facts) {
        if (state.pendingEntityType == null || state.pendingEntityId == null) return null;
        boolean approved = isShortAffirmative(message) || "CONFIRM_ACTION".equals(facts.intent());
        boolean rejected = isShortNegative(message) || "DENY_ACTION".equals(facts.intent());
        if (!approved && !rejected) {
            // 用户没有回答“是/不是”，而是直接给了另一个名称：放弃旧候选，让本轮重新解析。
            clearPendingEntity(state);
            return null;
        }
        String type = state.pendingEntityType;
        String id = state.pendingEntityId;
        String name = state.pendingEntityName;
        clearPendingEntity(state);
        if (rejected) {
            return "HOSPITAL".equals(type)
                    ? askHospital(state, "好的，不选择“" + name + "”。请重新说医院全名或院区。")
                    : askDepartment(state, "好的，不选择“" + name + "”。请重新说医生安排的复诊科室。");
        }
        if ("HOSPITAL".equals(type)) chooseHospital(state, id);
        else chooseDepartment(state, id);
        return advance(state, ExtractedFacts.empty());
    }

    private AgentTurnResponse confirmPendingEntity(ConversationState state, boolean approved) {
        if (state.pendingEntityType == null) {
            return respond(state, "当前没有需要确认的医院或科室，请继续说您的需求。", resumeReplies(state));
        }
        ExtractedFacts facts = new ExtractedFacts(approved ? "CONFIRM_ACTION" : "DENY_ACTION",
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
        return handlePendingEntityConfirmation(state, approved ? "是的" : "不是", facts);
    }

    private void clearPendingEntity(ConversationState state) {
        state.pendingEntityType = null;
        state.pendingEntityId = null;
        state.pendingEntityName = null;
        state.pendingEntityRaw = null;
    }

    private boolean isShortNegative(String message) {
        String value = message == null ? "" : message.replaceAll("[\\s，。、！？,.!?]", "");
        return value.length() <= 8 && containsAny(value, "不是", "不对", "选错了", "换一个", "重新说");
    }

    private boolean looksLikeHospitalMention(String value) {
        if (value == null) return false;
        if (containsAny(value, "医院", "院区", "市一", "一院", "同济", "协和")) return true;
        return catalog.hospitalNames().stream().anyMatch(name -> name.contains(value.trim()) || value.contains(name));
    }

    private boolean looksLikeDepartmentMention(String value) {
        if (value == null) return false;
        if (containsAny(value, "科", "神内", "心内", "内分泌", "骨科")) return true;
        return catalog.departmentNames().stream().anyMatch(name -> name.contains(value.trim()) || value.contains(name));
    }

    /** 异常节点的兜底也要说明当前问题和可恢复方向，而不是统一说“没听懂”。 */
    private String clarificationDraft(ConversationState state) {
        return switch (state.stage) {
            case ASK_HOSPITAL -> "我还没有确认您说的是哪家医院。请说医院全名或常用简称；我会查询目录，有歧义时再请您确认。";
            case ASK_DEPARTMENT -> "我还没有确认复诊科室。可以说完整名称或简称，例如神经内科、神内；不确定时请按病历或医生安排确认。";
            case ASK_DATE -> "我还没有确认复诊日期。您可以说“9月18日”或“下周三”，我再为您查询号源。";
            case SELECT_PERIOD, SELECT_SLOT, CONFIRM_SLOT -> "我还没有确认您想要的时间。可以说上午、下午或具体几点，也可以说换日期。";
            case NO_SLOT -> "原日期暂时没有号。您可以说查前后几天、换日期、换医院，或者稍后再查。";
            case CONFLICT -> "当前复诊时间与已有日程冲突。您可以说换时间、换日期、换医院，或者明确说仍保留这个时间。";
            // 这几个阶段（陪同、出行提醒、交通、通知家属）以前没有自己的兜底，一句“要”没被模型
            // 接住就直接跳到默认的“我没太听明白”，等于把老人从正在办的事里踢出去。这里按当前
            // 真正在问的那一件事再说一遍，并配同一个问题的按钮。
            case ASK_COMPANION -> "我还没听准这一步：这次复诊需要家属陪同吗？回答“需要”或“不需要”都可以。";
            case ASK_TRAVEL -> "我还没听准这一步：出门前需要我提醒您出发吗？回答“需要”或“不需要”都可以。";
            case ASK_TRANSPORT -> "我还没听准这一步：您打算怎么去医院？可以说家属开车、打车或者坐公交。";
            case ASK_NOTIFY -> "我还没听准这一步：需要把这次复诊通知家里谁吗？说“需要”或“不用”都可以。";
            default -> taskInProgress(state)
                    ? "我还没有完全理解这一步想怎么处理，办理进度已经保留。请换一种说法，或者说“继续办理”。"
                    : "我还没有完全理解您的意思。可以换一种说法；想预约复诊时可以说“我要办理复诊”。";
        };
    }

    private AgentTurnResponse showMaterials(ConversationState state, ExtractedFacts facts) {
        if (state.hospital == null || state.department == null) {
            List<String> common = materialTool.checklist(state.id, "未指定（通用清单）", "通用");
            String missing = state.hospital == null ? "医院" : "科室";
            return respond(state, "通用复诊材料包括：" + String.join("、", common)
                            + "。不同医院和科室可能有额外要求；为了查询准确清单，请告诉我复诊" + missing + "。",
                    resumeReplies(state));
        }
        if (state.materials.isEmpty()) {
            state.materials = materialTool.checklist(state.id, state.hospital, state.department);
        }
        return respondWithPlan(state, acknowledgement(facts,
                        "这是根据模拟数据库中的医院和科室规则生成的材料清单。"),
                resumeReplies(state));
    }

    private AgentTurnResponse showCareGuide(ConversationState state, String query) {
        List<GuideArticle> rows = careGuideTool.search(state.id, query, state.hospital, state.department);
        if (rows.isEmpty()) {
            return respond(state, "模拟知识库暂时没有找到这项流程说明。您可以咨询医院门诊服务台、预约服务或护士站。",
                    resumeReplies(state, q("人工帮助", "CONTACT_HUMAN", "")));
        }
        String summary = rows.stream().map(item -> item.title() + "：" + item.content())
                .collect(java.util.stream.Collectors.joining(" "));
        return respond(state, "我查询了复诊办事知识库。" + summary,
                resumeReplies(state));
    }

    /**
     * 出行路线。本轮事实里的日期、医院、科室必须真正参与定位预约，
     * 不能再无条件退回“最近一条有效预约”，否则用户说出的日期会在 Java 阶段被丢掉。
     */
    private AgentTurnResponse showTravelGuide(ConversationState state, ExtractedFacts facts,
                                              String originalMessage, boolean inside) {
        // 紧急处置期间安全提示优先：只给文字和按钮，绝不自动跳转页面。
        if (state.stage == ConversationState.Stage.EMERGENCY_PAUSED) return emergencyNotice(state);
        TravelTarget target = resolveTravelAppointment(state, facts, originalMessage);
        if (target.needsChoice()) return askTravelTarget(state, target.candidates(), inside);
        if (target.appointmentId() != null) return renderTravelGuide(state, target.appointmentId(), inside);
        // 没有可用预约时，正在办理中的草稿仍然可以给出路线预估。
        if (state.selectedSlot != null && state.hospitalId != null && state.transport != null) {
            RouteGuide route = routeGuideTool.plan(state.id, state.userId, state.hospitalId,
                    LocalDateTime.of(state.selectedSlot.date(), state.selectedSlot.time()), state.transport);
            return respondWithoutModel(state,
                    "按当前草稿，选择" + route.transport() + "预计约" + route.durationMinutes()
                            + "分钟，建议" + route.departureAt().format(TIME_LABEL) + "出发。预约时间变化后会重新计算。",
                    resumeReplies(state));
        }
        return respondWithoutModel(state, target.reason(),
                resumeReplies(state, q("查询我的预约", "QUERY_APPOINTMENTS", "")));
    }

    /** 院内指引。与出行路线共用同一套预约定位，楼栋、楼层、诊室一律来自数据库。 */
    private AgentTurnResponse showLocationGuide(ConversationState state, ExtractedFacts facts,
                                                String originalMessage, boolean inside) {
        if (state.stage == ConversationState.Stage.EMERGENCY_PAUSED) return emergencyNotice(state);
        TravelTarget target = resolveTravelAppointment(state, facts, originalMessage);
        if (target.needsChoice()) return askTravelTarget(state, target.candidates(), inside);
        if (target.appointmentId() != null) return renderLocationGuide(state, target.appointmentId(), inside);
        if (state.hospitalId != null && state.departmentId != null) {
            FacilityGuide facility = facilityGuideTool.find(state.id, null, state.hospitalId, state.departmentId);
            return respondWithoutModel(state, facilityReply(null, facility), resumeReplies(state));
        }
        return respondWithoutModel(state, target.reason(),
                resumeReplies(state, q("查询我的预约", "QUERY_APPOINTMENTS", "")));
    }

    private AgentTurnResponse renderTravelGuide(ConversationState state, String appointmentId, boolean inside) {
        AppointmentTravelGuide guide = travelGuides.forAppointment(state.userId, appointmentId, state.id);
        RouteGuide route = guide.route();
        return respondWithoutModel(state,
                "好的，我为您打开" + DATE_LABEL.format(guide.appointmentAt().toLocalDate()) + guide.hospital()
                        + guide.department() + "的出行地图。从模拟住址出发，选择" + route.transport()
                        + "预计约" + route.durationMinutes() + "分钟、" + distanceLabel(route.distanceMeters())
                        + "。建议" + route.departureAt().format(TIME_LABEL) + "出发，并预留20分钟报到和寻找诊室。",
                resumeReplies(state, q("查看地图和院内指引", "OPEN_TRAVEL", appointmentId)),
                pageDirective(state, appointmentId, inside));
    }

    private AgentTurnResponse renderLocationGuide(ConversationState state, String appointmentId, boolean inside) {
        AppointmentTravelGuide guide = travelGuides.forAppointment(state.userId, appointmentId, state.id);
        return respondWithoutModel(state, facilityReply(guide.appointmentAt().toLocalDate(), guide.facility()),
                resumeReplies(state, q("查看地图和院内指引", "OPEN_TRAVEL", appointmentId)),
                pageDirective(state, appointmentId, inside));
    }

    /** 院内指引的口播文案。事实全部来自工具结果，不在这里编造楼栋、楼层或诊室。 */
    private String facilityReply(LocalDate date, FacilityGuide facility) {
        String when = date == null ? "这次复诊" : DATE_LABEL.format(date) + "这次复诊";
        return "您已经到医院了，我为您打开" + when + "的院内指引。请从" + facility.entrance()
                + "进入" + facility.building() + "，先到" + facility.checkInPoint() + "报到，再前往"
                + facility.floor() + facility.room() + "。找不到时可以咨询"
                + (facility.helpDesk() == null ? "门诊服务台" : facility.helpDesk()) + "。";
    }

    /**
     * 页面跳转不是工具调用，但同样受安全优先级约束：紧急暂停期间只给文字和按钮，不自动跳转页面。
     * 注意 CANCELLED 只是“本次未提交的办理”停止，不代表不能查看数据库里已有的预约地图。
     */
    private UiDirective pageDirective(ConversationState state, String appointmentId, boolean inside) {
        if (state.stage == ConversationState.Stage.EMERGENCY_PAUSED) return null;
        return UiDirective.travel(appointmentId, inside);
    }

    /** 地图目标解析结果：唯一预约、需要用户在候选里选一条，或者说明为什么没有。 */
    private record TravelTarget(String appointmentId, List<AppointmentSummary> candidates, String reason) {
        static TravelTarget of(String appointmentId) { return new TravelTarget(appointmentId, List.of(), null); }
        static TravelTarget choose(List<AppointmentSummary> candidates) {
            return new TravelTarget(null, candidates, null);
        }
        static TravelTarget none(String reason) { return new TravelTarget(null, List.of(), reason); }
        boolean needsChoice() { return !candidates.isEmpty(); }
    }

    /** 没有任何筛选条件、也找不出可用预约时的统一说明。 */
    private static final String NO_TRAVEL_TARGET =
            "生成准确路线还需要确定医院、复诊时间和交通方式。您可以继续办理；预约确认后，事项页会保留完整地图和院内指引。";

    /**
     * 把“用户这一轮到底想看哪一次预约”解析成唯一的 appointmentId。
     * 顺序：原句里的日期 / 医院 / 科室 / “上次”这类指代 → 真查数据库；查不到就明确说查不到，不倒向别的预约；
     * 只有完全没有筛选条件时，才允许落到当前会话预约或最近的有效预约。
     */
    private TravelTarget resolveTravelAppointment(ConversationState state, ExtractedFacts facts,
                                                  String originalMessage) {
        String message = originalMessage == null ? "" : originalMessage;
        String hospital = facts == null ? null : facts.hospital();
        String department = facts == null ? null : facts.department();
        LocalDate spokenFull = mentionedFullDate(message);
        int[] monthDay = spokenFull == null ? mentionedMonthDay(message) : null;
        LocalDate exactDate = spokenFull != null ? spokenFull
                : (monthDay == null && facts != null ? facts.date() : null);
        boolean referenced = mentionsAppointmentReference(message);
        if (monthDay == null && exactDate == null && hospital == null && department == null && !referenced) {
            String fallback = latestEffectiveAppointmentId(state);
            return fallback == null ? TravelTarget.none(NO_TRAVEL_TARGET) : TravelTarget.of(fallback);
        }
        List<AppointmentSummary> rows =
                myAppointmentTool.search(state.id, state.userId, exactDate, hospital, department);
        if (monthDay != null) {
            // 只说“9月8号”时按月和日匹配，年份由数据库里的真实记录决定，绝不补成下一年。
            rows = rows.stream()
                    .filter(item -> item.date().getMonthValue() == monthDay[0]
                            && item.date().getDayOfMonth() == monthDay[1])
                    .toList();
        }
        if (rows.isEmpty()) {
            return TravelTarget.none("没有查到" + travelTargetLabel(monthDay, exactDate, hospital, department)
                    + "的已确认预约，所以没有打开地图。您可以换一个日期，或者查询我的预约。");
        }
        if (referenced && rows.size() > 1) {
            // “上次 / 最近”本身就是筛选条件：按离现在最近的一条收敛，仍然只认数据库里的真实记录。
            LocalDateTime now = LocalDateTime.now();
            rows = List.of(rows.stream().min(Comparator.comparingLong(
                    item -> Math.abs(Duration.between(now, appointmentAt(item)).toMinutes()))).orElseThrow());
        }
        return rows.size() > 1 ? TravelTarget.choose(rows) : TravelTarget.of(rows.get(0).appointmentId());
    }

    /** 用户对“哪一次预约”的口头描述，用于在“没查到”时复述清楚。 */
    private String travelTargetLabel(int[] monthDay, LocalDate date, String hospital, String department) {
        StringBuilder label = new StringBuilder();
        if (monthDay != null) label.append(monthDay[0]).append("月").append(monthDay[1]).append("日");
        else if (date != null) label.append(DATE_LABEL.format(date));
        if (hospital != null) label.append(hospital);
        if (department != null) label.append(department);
        return label.isEmpty() ? "符合这个条件" : label.toString();
    }

    /** 只取出“几月几号”，不补年份：查询历史预约时年份由数据库里的真实记录决定。 */
    private int[] mentionedMonthDay(String message) {
        Matcher matcher = SPOKEN_DATE.matcher(message);
        if (!matcher.find()) return null;
        try {
            int month = Integer.parseInt(matcher.group(1));
            int day = Integer.parseInt(matcher.group(2));
            if (month < 1 || month > 12 || day < 1 || day > 31) return null;
            return new int[]{month, day};
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** 用户明确说了年份时（“2026年9月8号”），按完整日期查，同样不补年份。 */
    private LocalDate mentionedFullDate(String message) {
        Matcher matcher = SPOKEN_FULL_DATE.matcher(message);
        if (!matcher.find()) return null;
        try {
            return LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean mentionsAppointmentReference(String message) {
        return containsAny(message, "上次", "上一次", "之前", "以前", "原来", "最近", "那次",
                "刚才", "早先", "历史");
    }

    /**
     * 同一天有多条预约时不替用户选：先问清楚，再打开地图。
     * 候选使用地图专用支线，绝不会落进取消预约的确认流程。
     */
    private AgentTurnResponse askTravelTarget(ConversationState state, List<AppointmentSummary> candidates,
                                              boolean inside) {
        state.sideTask = "SELECT_TRAVEL_APPOINTMENT";
        state.travelInside = inside;
        List<AppointmentSummary> limited = candidates.stream().limit(3).toList();
        // 同一天的多条写清楚是哪一天；跨年份时按钮上带完整年月日，老人照着选就不会选错。
        boolean sameDay = limited.stream().map(AppointmentSummary::date).distinct().count() == 1;
        String scope = sameDay ? DATE_LABEL.format(limited.get(0).date()) : "您说的这些日期里";
        // 同一家医院同一个科室的多条预约必须带上时间，否则复述出来是两句一模一样的话，老人没法选。
        boolean distinguishable = limited.stream().map(item -> item.hospital() + item.department())
                .distinct().count() == limited.size();
        String detail = limited.stream()
                .map(item -> item.hospital() + item.department()
                        + (distinguishable ? "" : TIME_LABEL.format(item.time())))
                .collect(java.util.stream.Collectors.joining("，还是"));
        List<QuickReply> choices = limited.stream()
                .map(item -> q(item.date().format(DATE_LABEL) + " " + item.time().format(TIME_LABEL) + " "
                        + item.hospital() + item.department(), "SELECT_TRAVEL_APPOINTMENT", item.appointmentId()))
                .toList();
        return respond(state, "我查到" + scope + "有" + candidates.size()
                + "条已确认预约，请问您想看" + detail + "？", choices);
    }

    /** 地图候选支线的选择：只打开地图，不会进入取消确认。 */
    private AgentTurnResponse selectTravelAppointment(ConversationState state, String appointmentId) {
        state.sideTask = null;
        return state.travelInside
                ? renderLocationGuide(state, appointmentId, true)
                : renderTravelGuide(state, appointmentId, false);
    }

    /**
     * 地图候选支线中把“市第一医院那个 / 下午那条”收敛成唯一候选。
     * 无法唯一确定时继续问，不猜；绝不进入取消流程。
     */
    private AgentTurnResponse resolveTravelCandidate(ConversationState state, String message) {
        String hospital = catalog.hospitalNames().stream().filter(message::contains).findFirst().orElse(null);
        int[] monthDay = mentionedMonthDay(message);
        boolean morning = containsAny(message, "上午", "早上");
        boolean afternoon = containsAny(message, "下午", "午后");
        boolean earliest = containsAny(message, "最早");
        if (hospital == null && monthDay == null && !morning && !afternoon && !earliest) return null;
        List<AppointmentSummary> rows = myAppointmentTool.search(state.id, state.userId, null, null, null);
        if (monthDay != null) {
            rows = rows.stream().filter(item -> item.date().getMonthValue() == monthDay[0]
                    && item.date().getDayOfMonth() == monthDay[1]).toList();
        }
        if (hospital != null) {
            String wanted = hospital;
            rows = rows.stream().filter(item -> item.hospital().contains(wanted)).toList();
        }
        if (morning) rows = rows.stream().filter(item -> item.time().isBefore(LocalTime.NOON)).toList();
        if (afternoon) rows = rows.stream().filter(item -> !item.time().isBefore(LocalTime.NOON)).toList();
        if (rows.isEmpty()) {
            // 带日期或医院的说法说明用户不是在选择候选，而是换了条件重新问：
            // 清掉支线交回主路径，由 resolveTravelAppointment 复述清楚日期后给统一的“没查到”。
            if (monthDay != null || hospital != null) {
                state.sideTask = null;
                return null;
            }
            return respond(state, "没有找到符合这个条件的已确认预约，所以没有打开地图。", resumeReplies(state));
        }
        if (earliest && rows.size() > 1) {
            rows = List.of(rows.stream().min(Comparator.comparing(this::appointmentAt)).orElseThrow());
        }
        return rows.size() > 1
                ? askTravelTarget(state, rows, state.travelInside)
                : selectTravelAppointment(state, rows.get(0).appointmentId());
    }

    /**
     * 预约完成播报最后会问“需要我现在打开地图吗”，老人往往只回一个“要”或“好的”。
     * 这类简短肯定只用来打开只读的路线页，绝不触达任何写操作：调用方限定在 COMPLETED 阶段，
     * “不要”“先不用”这类否定和带其他内容的句子都不会命中。
     */
    private boolean isShortAffirmative(String message) {
        String value = message.replaceAll("[\\s，。、！？,.!?]", "");
        if (value.isEmpty() || value.length() > 4) return false;
        if (containsAny(value, "不", "别", "算了", "没有", "不用")) return false;
        // 整句必须只由肯定词加语气助词构成，“好的谢谢”“行，那我先忙”这类都不会命中。
        return AFFIRMATIVE.matcher(value).matches();
    }

    /**
     * “当前或最近的未过期有效预约”的确定性规则：
     * 1) 优先本会话已确认且尚未过期的 appointmentId；
     * 2) 否则取预约时间距当前最近且尚未过期的记录；
     * 3) 全部已过期时退回最近的一条历史记录，没有有效预约则返回 null（由调用方给出无指令的兜底说明）。
     */
    private String latestEffectiveAppointmentId(ConversationState state) {
        List<AppointmentSummary> rows = myAppointmentTool.search(state.id, state.userId, null, null, null);
        if (rows.isEmpty()) return null;
        LocalDateTime now = LocalDateTime.now();
        List<AppointmentSummary> upcoming = rows.stream()
                .filter(item -> !appointmentAt(item).isBefore(now))
                .sorted(Comparator.comparing(this::appointmentAt)).toList();
        if (!upcoming.isEmpty()) {
            boolean sessionStillValid = state.appointmentId != null && upcoming.stream()
                    .anyMatch(item -> item.appointmentId().equals(state.appointmentId));
            return sessionStillValid ? state.appointmentId : upcoming.get(0).appointmentId();
        }
        return rows.get(rows.size() - 1).appointmentId();
    }

    private LocalDateTime appointmentAt(AppointmentSummary item) {
        return LocalDateTime.of(item.date(), item.time());
    }

    private String distanceLabel(int meters) {
        return meters >= 1000 ? String.format(java.util.Locale.ROOT, "%.1f公里", meters / 1000.0)
                : meters + "米";
    }

    /** 一次规划后批量执行互不依赖的知识库与材料只读查询。 */
    private AgentTurnResponse executeReadTools(ConversationState state, String message,
                                               List<PlannerToolCall> calls) {
        List<String> sections = new ArrayList<>();
        boolean needsExactMaterials = false;
        for (PlannerToolCall call : calls) {
            if ("careGuide.search".equals(call.toolName())) {
                String query = call.arguments().getOrDefault("query", message);
                List<GuideArticle> guides = careGuideTool.search(state.id, query, state.hospital, state.department);
                if (!guides.isEmpty()) {
                    sections.add(guides.stream().map(item -> item.title() + "：" + item.content())
                            .collect(java.util.stream.Collectors.joining(" ")));
                }
            } else if ("material.checklist".equals(call.toolName())) {
                String hospital = firstNonBlank(call.arguments().get("hospital"), state.hospital);
                String department = firstNonBlank(call.arguments().get("department"), state.department);
                boolean exact = hospital != null && department != null;
                List<String> materials = materialTool.checklist(state.id,
                        exact ? hospital : "未指定（通用清单）", exact ? department : "通用");
                if (exact && state.materials.isEmpty()) state.materials = materials;
                sections.add((exact ? hospital + department + "材料" : "通用复诊材料")
                        + "：" + String.join("、", materials) + "。");
                needsExactMaterials = !exact;
            } else if ("hospital.list".equals(call.toolName())) {
                List<HospitalProfile> hospitals = hospitalCatalogTool.listHospitals(state.id);
                sections.add("当前可办理医院：" + hospitals.stream().limit(5)
                        .map(HospitalProfile::name).collect(java.util.stream.Collectors.joining("、")) + "。");
            } else if ("department.list".equals(call.toolName()) && state.hospitalId != null) {
                List<DepartmentProfile> departments = departmentCatalogTool.listDepartments(state.id, state.hospitalId);
                sections.add(state.hospital + "当前可办理科室：" + departments.stream().limit(8)
                        .map(DepartmentProfile::name).collect(java.util.stream.Collectors.joining("、")) + "。");
            } else if ("appointment.queryMine".equals(call.toolName())) {
                LocalDate date = parseIsoDate(call.arguments().get("date"));
                List<AppointmentSummary> appointments = myAppointmentTool.search(state.id, state.userId,
                        date, call.arguments().get("hospital"), call.arguments().get("department"));
                sections.add(appointments.isEmpty() ? "没有查到符合条件的已确认预约。"
                        : "已确认预约：" + appointments.stream().limit(5).map(this::appointmentSummary)
                        .collect(java.util.stream.Collectors.joining("；")) + "。");
            }
        }
        if (sections.isEmpty()) {
            return respond(state, "这次只读查询没有获得可用结果。您可以换一种说法，或请求人工帮助。",
                    resumeReplies(state, q("人工帮助", "CONTACT_HUMAN", "")));
        }
        String question = needsExactMaterials
                ? "不同医院和科室可能有额外要求。请告诉我您准备去哪家医院复诊。"
                : "以上来自模拟办事知识库和材料数据库，具体要求请以医院通知为准。";
        return respond(state, String.join(" ", sections) + " " + question, resumeReplies(state));
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private LocalDate parseIsoDate(String value) {
        try { return value == null || value.isBlank() ? null : LocalDate.parse(value); }
        catch (RuntimeException ignored) { return null; }
    }

    private void rememberInterruptedTask(ConversationState state, String sideTask) {
        if (taskInProgress(state) && state.interruptedStage == null
                && state.stage != ConversationState.Stage.COMPLETED
                && state.stage != ConversationState.Stage.CANCELLED) {
            state.interruptedStage = state.stage;
            state.interruptedPendingAction = state.pendingAction;
            state.interruptedPendingAppointmentId = state.pendingAppointmentId;
            state.returnPolicy = "ASK_TO_RESUME";
        }
        state.sideTask = sideTask;
    }

    private List<QuickReply> resumeReplies(ConversationState state, QuickReply... extras) {
        List<QuickReply> replies = new ArrayList<>();
        if (state.interruptedStage != null) replies.add(q("继续刚才办理", "RESUME_INTERRUPTED", ""));
        else if (taskInProgress(state)) replies.add(q("继续办理", "CONTINUE", ""));
        else replies.add(q("开始复诊办理", "CONTINUE", ""));
        for (QuickReply extra : extras) replies.add(extra);
        return replies;
    }

    private void clearInterruption(ConversationState state) {
        state.interruptedStage = null;
        state.interruptedPendingAction = null;
        state.interruptedPendingAppointmentId = null;
        state.sideTask = null;
        state.returnPolicy = null;
    }

    private void discardInterruption(ConversationState state) { clearInterruption(state); }

    private boolean isSelectingTime(ConversationState.Stage stage) {
        return stage == ConversationState.Stage.SELECT_PERIOD
                || stage == ConversationState.Stage.CONFIRM_SLOT
                || stage == ConversationState.Stage.SELECT_SLOT
                || stage == ConversationState.Stage.NO_SLOT;
    }

    private void clearSlotSelection(ConversationState state, boolean clearDate) {
        if (clearDate) state.date = null;
        state.selectedSlot = null;
        state.recommendedSlot = null;
        state.requestedTime = null;
        state.alternatives = List.of();
    }

    private String appointmentSummary(AppointmentSummary item) {
        return item.date().format(DATE_LABEL) + " " + item.time().format(TIME_LABEL) + "，"
                + item.hospital() + "·" + item.department();
    }

    private ResultCard resultCard(AppointmentSummary item) {
        return new ResultCard(item.appointmentId(), item.hospital(), item.department(),
                item.date().format(DATE_LABEL), item.time().format(TIME_LABEL), item.materials(),
                item.departureAt() == null ? "未设置" : item.departureAt().format(TIME_LABEL),
                valueOrPending(item.reminderStatus()), valueOrPending(item.familyStatus()));
    }

    private AgentTurnResponse advance(ConversationState state, ExtractedFacts facts) {
        if (state.hospital == null) return askHospital(state, acknowledgement(facts, "请告诉我就诊医院。"));
        if (state.department == null) {
            return askDepartment(state, acknowledgement(facts, "好的。请问复诊哪个科室？"));
        }
        if (state.date == null) return askDate(state, acknowledgement(facts, "请问希望哪一天复诊？"));
        if (state.selectedSlot == null) {
            if (state.alternatives.isEmpty()) return querySlots(state);
            if (state.requestedTime != null) return recommendSpecificTime(state, state.requestedTime);
            if (state.timePreference != null) return recommendPeriod(state, state.timePreference);
            return respondWithPlan(state, "请先告诉我想上午还是下午，也可以直接说具体时间。", periodReplies(state.alternatives));
        }
        if (state.acceptAlternative == null) {
            state.stage = ConversationState.Stage.ASK_ALTERNATIVE;
            return respond(state, acknowledgement(facts, "如果这一天没有号，您接受前后几天的其他时间吗？"), List.of(
                    q("可以换日期", "SET_ALTERNATIVE", "true"),
                    q("只要这一天", "SET_ALTERNATIVE", "false")));
        }
        if (state.needCompanion == null) {
            state.stage = ConversationState.Stage.ASK_COMPANION;
            return respond(state, acknowledgement(facts, "这次复诊需要家属陪同吗？"), List.of(
                    q("需要陪同", "SET_COMPANION", "true"),
                    q("不需要陪同", "SET_COMPANION", "false")));
        }
        if (state.needTravel == null) {
            state.stage = ConversationState.Stage.ASK_TRAVEL;
            return respond(state, acknowledgement(facts, "需要我计算出发时间并在出发前提醒吗？"), List.of(
                    q("需要出行提醒", "SET_TRAVEL", "true"),
                    q("不需要", "SET_TRAVEL", "false")));
        }
        if (state.transport == null) {
            state.stage = ConversationState.Stage.ASK_TRANSPORT;
            return respond(state, acknowledgement(facts, "您准备怎样去医院？"), List.of(
                    q("家属开车", "SET_TRANSPORT", "家属开车"),
                    q("打车", "SET_TRANSPORT", "打车"),
                    q("公交", "SET_TRANSPORT", "公交")));
        }
        if (state.notifyFamily == null) {
            state.stage = ConversationState.Stage.ASK_NOTIFY;
            return respond(state, "需要通知家属吗？", List.of(q("需要通知", "SET_NOTIFY", "true"), q("不用通知", "SET_NOTIFY", "false")));
        }
        if (Boolean.TRUE.equals(state.notifyFamily) && state.contact == null) {
            List<QuickReply> contacts = catalog.contacts(state.userId).stream()
                    .map(c -> q(c.relationship() + " " + c.name(), "SET_CONTACT", c.id())).toList();
            if (contacts.isEmpty()) return respond(state, "尚未配置家属联系人。请选择暂不通知，或请求人工帮助。",
                    List.of(q("暂不通知", "SET_NOTIFY", "false"), q("人工帮助", "CONTACT_HUMAN", "")));
            return respond(state, "请确认要通知哪位家属。", contacts);
        }

        if (state.materials.isEmpty()) {
            state.materials = callTool(state, "materials.checklist", Map.of("hospital", state.hospital, "department", state.department),
                    () -> materialTool.checklist(state.id, state.hospital, state.department));
        }
        state.stage = ConversationState.Stage.READY_TO_PLAN;
        // 所有必要信息已经收集完成，直接执行只读的日程检查并生成确认卡。
        // “检查计划”本身不会提交预约，不需要老人再额外说一次“开始办理”；
        // 真正的写操作仍然必须经过随后生成的 confirmationId 确认门禁。
        return checkSchedule(state);
    }

    private AgentTurnResponse querySlots(ConversationState state) {
        if (state.hospitalId == null || state.department == null || state.date == null) return advance(state, ExtractedFacts.empty());
        if (state.date.isBefore(LocalDate.now())) { resetAfterDate(state); return askDate(state, "这个日期已经过去，请重新选择复诊日期。"); }
        List<Slot> slots = callTool(state, "appointment.querySlots", Map.of("hospitalId", state.hospitalId, "department", state.department, "date", state.date),
                () -> appointmentTool.queryAvailableSlots(state.id, state.hospitalId, state.department, state.date));
        state.selectedSlot = null;
        state.recommendedSlot = null;
        if (!slots.isEmpty()) {
            state.alternatives = slots;
            if (state.requestedTime != null) return recommendSpecificTime(state, state.requestedTime);
            if (state.timePreference != null) return recommendPeriod(state, state.timePreference);
            state.stage = ConversationState.Stage.SELECT_PERIOD;
            return respondWithPlan(state, periodSummary(state.date, slots), periodReplies(slots));
        }

        state.stage = ConversationState.Stage.NO_SLOT;
        state.alternatives = List.of();
        if (state.acceptAlternative == null) return respondWithPlan(state, "这一天暂无号源。您接受附近的其他日期吗？",
                List.of(q("接受其他日期", "SET_ALTERNATIVE", "true"), q("只要这一天", "SET_ALTERNATIVE", "false")));
        if (!state.acceptAlternative) return respondWithPlan(state, "这一天暂无号源，已保留只选这一天的意愿。",
                List.of(q("稍后再查", "RETRY_QUERY", ""), q("主动修改日期", "CHANGE_DATE", ""), q("换医院", "CHANGE_HOSPITAL", "")));
        state.alternatives = callTool(state, "appointment.queryAlternatives", Map.of("hospitalId", state.hospitalId, "date", state.date),
                () -> appointmentTool.queryAlternatives(state.id, state.hospitalId, state.department, state.date));
        if (!state.alternatives.isEmpty()) {
            List<QuickReply> choices = new ArrayList<>(
                    slotReplies(state.alternatives.stream().limit(3).toList()));
            choices.add(q("重新选择日期", "CHANGE_DATE", ""));
            return respondWithPlan(state, state.date.format(DATE_LABEL) +
                    "暂时没有可预约时段。我查到了附近日期的真实模拟号源，请选择一个，或重新选日期。", choices);
        }
        return respondWithPlan(state, state.date.format(DATE_LABEL) +
                "暂时没有可预约时段。我保留了其他信息，您可以修改日期或医院。",
                List.of(q("重新选择日期", "CHANGE_DATE", ""),
                        q("查看其他医院", "CHANGE_HOSPITAL", ""),
                        q("稍后再查", "RETRY_QUERY", "")));
    }

    private AgentTurnResponse queryNearbySlots(ConversationState state) {
        if (state.hospitalId == null || state.department == null || state.date == null) {
            return validateDraftForModel(state);
        }
        state.acceptAlternative = true;
        state.stage = ConversationState.Stage.NO_SLOT;
        state.alternatives = callTool(state, "appointment.queryAlternatives",
                Map.of("hospitalId", state.hospitalId, "department", state.department, "date", state.date),
                () -> appointmentTool.queryAlternatives(state.id, state.hospitalId, state.department, state.date));
        if (state.alternatives.isEmpty()) {
            return respondWithPlan(state, state.date.format(DATE_LABEL)
                            + "没有号，前后三天也没有查到可预约时段。您可以换日期、换医院或稍后再查。",
                    List.of(q("换日期", "CHANGE_DATE", ""), q("换医院", "CHANGE_HOSPITAL", ""),
                            q("稍后再查", "RETRY_QUERY", "")));
        }
        return respondWithPlan(state, state.date.format(DATE_LABEL)
                        + "没有号。我查询了附近日期，下面这些时间目前可以预约，请选择一个。",
                slotReplies(state.alternatives.stream().limit(4).toList()));
    }

    private AgentTurnResponse showAvailableSlots(ConversationState state) {
        if (state.hospitalId == null) {
            return askHospital(state, "要查询号源，请先告诉我想去哪家医院。");
        }
        if (state.department == null) {
            return askDepartment(state, "要查询号源，还需要先选择复诊科室。");
        }
        if (state.date != null && !state.date.isBefore(LocalDate.now())
                && !state.date.isAfter(LocalDate.now().plusMonths(1))) {
            return querySlots(state);
        }

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusMonths(1);
        List<Slot> slots = appointmentTool.queryUpcomingSlots(
                state.id, state.hospitalId, state.department, from, to);
        if (slots.isEmpty()) {
            return respond(state, state.hospital + state.department +
                    "在今天到一个月后暂时没有可预约号源。您可以改选医院或联系人工帮助。",
                    List.of(q("修改医院", "CHANGE_HOSPITAL", ""),
                            q("联系人工", "CONTACT_HUMAN", "")));
        }

        List<LocalDate> dates = slots.stream().map(Slot::date).distinct().limit(6).toList();
        List<QuickReply> choices = dates.stream()
                .map(date -> q(date.format(DATE_LABEL), "SET_DATE", date.toString())).toList();
        String labels = dates.stream().map(date -> date.format(DATE_LABEL))
                .collect(java.util.stream.Collectors.joining("、"));
        state.date = null;
        state.selectedSlot = null;
        state.recommendedSlot = null;
        state.alternatives = List.of();
        state.stage = ConversationState.Stage.ASK_DATE;
        return respond(state, "我查询了数据库。今天到一个月后，较早有号的日期是：" + labels
                + "。请选择一天，选好后我再为您介绍上午和下午的具体时间。", choices);
    }

    private AgentTurnResponse recommendPeriod(ConversationState state, String preference) {
        String normalized = "AFTERNOON".equalsIgnoreCase(preference) ? "AFTERNOON" : "MORNING";
        List<Slot> candidates = filterByPeriod(state.alternatives, normalized);
        if (candidates.isEmpty()) {
            String other = "MORNING".equals(normalized) ? "AFTERNOON" : "MORNING";
            List<Slot> otherSlots = filterByPeriod(state.alternatives, other);
            return respondWithPlan(state, periodLabel(normalized) + "暂时没有号。" +
                    (otherSlots.isEmpty() ? "请重新选择日期。" :
                            periodLabel(other) + "最早" + otherSlots.get(0).time().format(TIME_LABEL) + "还有号。"),
                    otherSlots.isEmpty()
                            ? List.of(q("重新选择日期", "CHANGE_DATE", ""))
                            : List.of(q("选择" + periodLabel(other), "SET_PERIOD", other),
                                    q("重新选择日期", "CHANGE_DATE", "")));
        }
        state.timePreference = normalized;
        state.recommendedSlot = candidates.get(0);
        state.stage = ConversationState.Stage.CONFIRM_SLOT;
        return respondWithPlan(state, periodLabel(normalized) + "最早" +
                        state.recommendedSlot.time().format(TIME_LABEL) +
                        "还有预约号，这个时间可以吗？",
                List.of(q("这个时间可以", "SELECT_SLOT", state.recommendedSlot.id()),
                        q("看看其他" + periodLabel(normalized) + "时间", "SHOW_PERIOD_SLOTS", normalized),
                        q("改选" + periodLabel("MORNING".equals(normalized) ? "AFTERNOON" : "MORNING"),
                                "SET_PERIOD", "MORNING".equals(normalized) ? "AFTERNOON" : "MORNING")));
    }

    private AgentTurnResponse recommendSpecificTime(ConversationState state, LocalTime requestedTime) {
        if (state.alternatives.isEmpty()) return querySlots(state);
        Slot exact = state.alternatives.stream()
                .filter(item -> item.time().equals(requestedTime)).findFirst().orElse(null);
        Slot recommendation = nearestSlot(state.alternatives, requestedTime);
        if (recommendation == null) {
            return respondWithPlan(state, "当前没有可推荐的号源，请重新选择日期。",
                    List.of(q("重新选择日期", "CHANGE_DATE", "")));
        }
        state.requestedTime = requestedTime;
        state.timePreference = requestedTime.isBefore(LocalTime.NOON) ? "MORNING" : "AFTERNOON";
        state.recommendedSlot = recommendation;
        state.stage = ConversationState.Stage.CONFIRM_SLOT;
        String reply = exact != null
                ? requestedTime.format(TIME_LABEL) + "还有预约号，这个时间可以吗？"
                : "您想要的" + requestedTime.format(TIME_LABEL) + "暂时没有号。最接近的" +
                recommendation.time().format(TIME_LABEL) + "还有号，这个时间可以吗？";
        return respondWithPlan(state, reply, List.of(
                q("这个时间可以", "SELECT_SLOT", recommendation.id()),
                q("看看其他" + periodLabel(state.timePreference) + "时间",
                        "SHOW_PERIOD_SLOTS", state.timePreference),
                q("重新选择日期", "CHANGE_DATE", "")));
    }

    private AgentTurnResponse showPeriodSlots(ConversationState state, String preference) {
        String normalized = "AFTERNOON".equalsIgnoreCase(preference) ? "AFTERNOON" : "MORNING";
        List<Slot> candidates = filterByPeriod(state.alternatives, normalized);
        state.stage = ConversationState.Stage.SELECT_SLOT;
        List<QuickReply> choices = new ArrayList<>(slotReplies(candidates));
        String other = "MORNING".equals(normalized) ? "AFTERNOON" : "MORNING";
        choices.add(q("改选" + periodLabel(other), "SET_PERIOD", other));
        return respondWithPlan(state, "以下是" + periodLabel(normalized) +
                "仍可预约的时间，请选择一个。", choices);
    }

    /** 在真实号源里找等于 wanted 的时段，没有就取时间上最接近的一个；绝不凭空造号。 */
    private Slot nearestSlot(List<Slot> slots, LocalTime wanted) {
        if (wanted == null || slots.isEmpty()) return null;
        Slot exact = slots.stream().filter(item -> item.time().equals(wanted)).findFirst().orElse(null);
        if (exact != null) return exact;
        return slots.stream().min((left, right) -> Long.compare(
                Math.abs(java.time.Duration.between(wanted, left.time()).toMinutes()),
                Math.abs(java.time.Duration.between(wanted, right.time()).toMinutes()))).orElse(null);
    }

    private List<Slot> filterByPeriod(List<Slot> slots, String preference) {
        return slots.stream().filter(slot -> "MORNING".equals(preference)
                ? slot.time().isBefore(LocalTime.NOON)
                : !slot.time().isBefore(LocalTime.NOON)).toList();
    }

    private List<QuickReply> periodReplies(List<Slot> slots) {
        List<QuickReply> replies = new ArrayList<>();
        if (!filterByPeriod(slots, "MORNING").isEmpty()) {
            replies.add(q("上午", "SET_PERIOD", "MORNING"));
        }
        if (!filterByPeriod(slots, "AFTERNOON").isEmpty()) {
            replies.add(q("下午", "SET_PERIOD", "AFTERNOON"));
        }
        replies.add(q("直接选择具体时间", "SHOW_PERIOD_SLOTS",
                !filterByPeriod(slots, "MORNING").isEmpty() ? "MORNING" : "AFTERNOON"));
        return replies;
    }

    private String periodSummary(LocalDate date, List<Slot> slots) {
        List<Slot> morning = filterByPeriod(slots, "MORNING");
        List<Slot> afternoon = filterByPeriod(slots, "AFTERNOON");
        List<String> descriptions = new ArrayList<>();
        if (!morning.isEmpty()) descriptions.add("上午最早" + morning.get(0).time().format(TIME_LABEL));
        if (!afternoon.isEmpty()) descriptions.add("下午最早" + afternoon.get(0).time().format(TIME_LABEL));
        return date.format(DATE_LABEL) + "共查到" + slots.size() + "个可预约时段，" +
                String.join("，", descriptions) + "。您想上午去还是下午去？";
    }

    private String periodLabel(String preference) {
        return "AFTERNOON".equals(preference) ? "下午" : "上午";
    }

    private AgentTurnResponse selectSlot(ConversationState state, String slotId) {
        Slot selected = state.alternatives.stream()
                .filter(item -> item.id().equals(slotId)).findFirst().orElse(null);
        if (selected == null) {
            return respond(state, "这个号源已经不在当前候选列表中，请重新查询。",
                    List.of(q("重新查询", "RETRY_QUERY", "")));
        }
        state.selectedSlot = selected;
        state.recommendedSlot = null;
        state.requestedTime = null;
        state.date = selected.date();
        // 已经锁定一个真实可约的号源，“如果这天没号要不要看前后几天”就成了假设问题：
        // 每轮都问一遍，老人答了别的也会被同一句话再拦一次。这里直接记成“只要这一天”。
        // 真正需要问的场景（选的日期根本没号）在 querySlots 的 NO_SLOT 分支里照样会问。
        if (state.acceptAlternative == null) state.acceptAlternative = false;
        return advance(state, ExtractedFacts.empty());
    }

    private AgentTurnResponse checkSchedule(ConversationState state) {
        if (!ready(state)) return advance(state, ExtractedFacts.empty());
        LocalDateTime start = LocalDateTime.of(state.selectedSlot.date(), state.selectedSlot.time());
        List<Conflict> conflicts = callTool(state, "schedule.checkConflict", Map.of("userId", state.userId, "start", start),
                () -> scheduleTool.findConflicts(state.id, state.userId, start, start.plusMinutes(60)));
        if (!conflicts.isEmpty()) {
            state.stage = ConversationState.Stage.CONFLICT;
            List<Slot> sameDay = appointmentTool.queryAvailableSlots(state.id, state.hospitalId, state.department, state.date)
                    .stream().filter(item -> !item.id().equals(state.selectedSlot.id())).toList();
            state.alternatives = sameDay;
            List<QuickReply> choices = new ArrayList<>(slotReplies(sameDay.stream().limit(2).toList()));
            choices.add(q("重新选择日期", "CHANGE_DATE", ""));
            choices.add(q("仍保留这个时间", "KEEP_CONFLICT", ""));
            return respondWithPlan(state, "这个时间与您的“" + conflicts.get(0).title() + "（" + conflicts.get(0).startAt() + " 至 " + conflicts.get(0).endAt() + "）" +
                    "”冲突。您可以选择其他号源，也可以明确保留当前时间。", choices);
        }
        state.scheduleChecked = true;
        return checkDuplicate(state);
    }

    private AgentTurnResponse keepConflict(ConversationState state) {
        if (state.stage != ConversationState.Stage.CONFLICT) {
            return respond(state, "当前没有需要保留的冲突时间，请继续办理。", resumeReplies(state));
        }
        state.scheduleChecked = true;
        return checkDuplicate(state);
    }

    private AgentTurnResponse checkDuplicate(ConversationState state) {
        if (!ready(state)) return validateDraftForModel(state);
        List<AppointmentSummary> matches = myAppointmentTool.search(
                        state.id, state.userId, state.selectedSlot.date(), state.hospital, state.department)
                .stream()
                .filter(item -> item.time().equals(state.selectedSlot.time()))
                .filter(item -> state.originalAppointmentId == null
                        || !item.appointmentId().equals(state.originalAppointmentId))
                .toList();
        if (matches.isEmpty()) return buildConfirmation(state);
        AppointmentSummary duplicate = matches.get(0);
        state.stage = ConversationState.Stage.READY_TO_PLAN;
        return respondWithPlan(state, "您已经有一条相同的复诊预约：" + appointmentSummary(duplicate)
                        + "。我没有重复提交。您想保留已有预约，还是重新选择时间？",
                List.of(q("保留已有预约", "CANCEL_TASK", ""),
                        q("重新选择时间", "CHANGE_TIME", ""),
                        q("查看我的预约", "QUERY_APPOINTMENTS", "")));
    }

    private AgentTurnResponse buildConfirmation(ConversationState state) {
        if (!ready(state)) return advance(state, ExtractedFacts.empty());
        LocalDateTime at = LocalDateTime.of(state.selectedSlot.date(), state.selectedSlot.time());
        if (state.travelPlan == null) {
            state.travelPlan = callTool(state, "travel.plan", Map.of("hospital", state.hospital, "appointmentAt", at, "transport", state.transport),
                    () -> travelTool.plan(state.id, state.userId, state.hospital, at, state.transport));
        }
        // 重述待确认内容（例如用户在确认卡前说“打开地图”）时保留原确认凭据，
        // 避免确认卡看似被刷新、旧按钮突然失效。
        if (state.confirmationId == null) state.confirmationId = UUID.randomUUID().toString();
        state.stage = ConversationState.Stage.AWAITING_CONFIRMATION;
        state.taskStatus = ConversationState.TaskStatus.AWAITING_CONFIRMATION;
        List<String> operations = new ArrayList<>();
        operations.add("医院科室：" + state.hospital + " · " + state.department);
        operations.add("复诊时间：" + slotLabel(state.selectedSlot));
        operations.add("陪同需求：" + (state.needCompanion ? "需要家属陪同" : "不需要陪同"));
        operations.add("建议出发：" + state.travelPlan.departureAt() + "（" + state.transport + "）");
        if (state.originalAppointmentId != null) {
            appointmentRecords.allFor(state.userId).stream().filter(row -> row.appointmentId().equals(state.originalAppointmentId)).findFirst()
                    .ifPresent(row -> operations.add("原预约：" + row.hospital() + " · " + row.department() + " " + row.date() + " " + row.time()));
        }
        if (state.appointmentId == null) operations.add(state.originalAppointmentId == null ? "提交模拟预约" : "变更原预约 " + state.originalAppointmentId + "，停用原提醒");
        else operations.add("保留已成功预约：" + state.appointmentId + "，仅补办未完成事项");
        operations.add("材料：" + String.join("、", state.materials));
        if (!state.materialReminderDone) operations.add("创建材料准备提醒：" + at.minusDays(1));
        if (Boolean.TRUE.equals(state.needTravel) && !state.departureReminderDone)
            operations.add("创建出发提醒：" + state.travelPlan.departureAt().minusMinutes(10));
        if (!Boolean.TRUE.equals(state.needTravel)) operations.add("不创建出发提醒");
        if (Boolean.TRUE.equals(state.notifyFamily) && !state.notificationDone)
            operations.add("通知" + state.contact.relationship() + " " + state.contact.name() + "：" + notificationMessage(state));
        else operations.add(state.notificationDone ? "家属已通知，不重复发送" : "不通知家属");
        ConfirmationCard card = new ConfirmationCard("请确认复诊办理计划", operations,
                "确认后按以上内容更新模拟预约、提醒及通知。原已发送消息不能撤回。", "确认办理", "返回修改", state.confirmationId);
        String narration = confirmationNarration(state);
        // 确认摘要中的时间、地点、陪同、材料和通知对象均来自 Java 权威状态，
        // 不再交给回答模型压缩或改写；reply 与 speechText 使用同一份内容。
        return finishWithoutModel(state, new AgentTurnResponse(state.id, state.stage.name(), narration,
                List.of(), plan(state), card, null, traces.findByConversation(state.id), null, narration, null));
    }

    private String confirmationNarration(ConversationState state) {
        StringBuilder text = new StringBuilder("请确认本次复诊安排。");
        text.append("复诊时间是")
                .append(state.selectedSlot.date().format(DATE_LABEL))
                .append(spokenTime(state.selectedSlot.time().format(TIME_LABEL)))
                .append("，医院是").append(state.hospital)
                .append("，科室是").append(state.department).append("。");
        text.append(Boolean.TRUE.equals(state.needCompanion)
                ? "这次需要家属陪同。" : "这次不需要家属陪同。");
        if (state.travelPlan != null) {
            text.append("建议").append(spokenTime(state.travelPlan.departureAt().toString()))
                    .append("出发，交通方式是").append(state.transport).append("。");
        }
        if (!state.materials.isEmpty()) {
            text.append("请携带").append(String.join("、", state.materials)).append("。");
        }
        if (Boolean.TRUE.equals(state.needTravel)) {
            text.append("确认后会创建材料准备提醒和出发提醒。");
        } else {
            text.append("确认后会创建材料准备提醒，不创建出发提醒。");
        }
        if (Boolean.TRUE.equals(state.notifyFamily) && state.contact != null) {
            text.append("并通知").append(state.contact.relationship()).append(state.contact.name()).append("。");
        } else {
            text.append("不会通知家属。");
        }
        text.append("如果这些信息正确，请说“确认办理”；需要修改，请说“返回修改”。");
        return text.toString();
    }

    private AgentTurnResponse cancelTask(ConversationState state) {
        state.confirmationId = null;
        if (state.appointmentId != null) {
            state.stage = ConversationState.Stage.CANCELLED;
            state.taskStatus = ConversationState.TaskStatus.CANCELLED;
            return respond(state, "好的，已停止继续办理。已经确认的预约仍然保留，可在事项页面查看。",
                    List.of(q("查看事项", "OPEN_TASKS", ""), q("查询我的预约", "QUERY_APPOINTMENTS", ""),
                            q("新建办理", "NEW_BOOKING", "")));
        }
        boolean editingExisting = state.originalAppointmentId != null;
        clearDraft(state);
        state.stage = ConversationState.Stage.CANCELLED;
        state.taskStatus = ConversationState.TaskStatus.CANCELLED;
        return respond(state, editingExisting ? "变更草稿已取消，原预约和提醒保留。" : "本次办理已停止，未提交预约。",
                List.of(q("新建办理", "NEW_BOOKING", ""), q("查看事项", "OPEN_TASKS", ""),
                        q("查询我的预约", "QUERY_APPOINTMENTS", "")));
    }

    private AgentTurnResponse respondWithCancelCard(ConversationState state) {
        state.confirmationId = UUID.randomUUID().toString();
        ConfirmationCard card = new ConfirmationCard("确认取消已预约的复诊吗？",
                List.of("医院科室：" + state.hospital + " · " + state.department,
                        "取消预约：" + slotLabel(state.selectedSlot), "释放号源并停用关联提醒", "不另发家属消息；已发送消息保留，请告知家属取消安排"),
                "确认后原预约及关联提醒失效；返回则保留预约。", "确认取消预约", "保留预约", state.confirmationId);
        return finish(state, new AgentTurnResponse(state.id, state.stage.name(), "已有预约，取消需要明确确认。", List.of(), plan(state), card, null, traces.findByConversation(state.id)));
    }

    private AgentTurnResponse askHospital(ConversationState state, String message) {
        state.taskStatus = ConversationState.TaskStatus.ACTIVE;
        state.stage = ConversationState.Stage.ASK_HOSPITAL;
        // 正常询问时不替老人预选医院，优先让其直接语音或文字回答；匹配失败时再做有依据的引导。
        return respond(state, message, List.of());
    }

    private AgentTurnResponse askDepartment(ConversationState state, String message) {
        state.taskStatus = ConversationState.TaskStatus.ACTIVE;
        state.stage = ConversationState.Stage.ASK_DEPARTMENT;
        List<QuickReply> choices = new ArrayList<>(catalog.departments(state.hospitalId).stream()
                .limit(3).map(item -> q(item.name(), "SET_DEPARTMENT", item.id())).toList());
        choices.add(q("我自己说科室", "ASK_HUMAN_INPUT", ""));
        return respond(state, message, choices);
    }

    private AgentTurnResponse askDate(ConversationState state, String message) {
        state.taskStatus = ConversationState.TaskStatus.ACTIVE;
        state.stage = ConversationState.Stage.ASK_DATE;
        List<QuickReply> choices = new ArrayList<>(catalog.availableDates(
                        state.hospitalId, state.department, LocalDate.now(), 3).stream()
                .map(date -> q(date.format(DATE_LABEL), "SET_DATE", date.toString())).toList());
        choices.add(q("我自己说日期", "ASK_HUMAN_INPUT", ""));
        return respond(state, message, choices);
    }

    private AgentTurnResponse showHospitals(ConversationState state, String requestedHospital) {
        List<HospitalProfile> rows = hospitalCatalogTool.listHospitals(state.id);
        if (requestedHospital != null) {
            var matched = catalog.hospital(requestedHospital);
            if (matched.isPresent()) {
                rows = rows.stream().filter(item -> item.id().equals(matched.get().id())).toList();
            }
        }
        if (rows.isEmpty()) {
            return respond(state, "暂时没有找到这家医院的模拟资料。" + resumeHint(state), List.of());
        }
        String summary = rows.stream().limit(3).map(this::hospitalSummary)
                .collect(java.util.stream.Collectors.joining("；"));
        List<QuickReply> choices = rows.stream().limit(3)
                .map(item -> q("选择" + item.name(), "SET_HOSPITAL", item.id())).toList();
        return respond(state, "目前的模拟医院资料如下：" + summary + "。" + resumeHint(state), choices);
    }

    private AgentTurnResponse showDepartments(ConversationState state, String requestedHospital) {
        if (requestedHospital != null) {
            catalog.hospital(requestedHospital).ifPresent(item -> {
                if (!item.id().equals(state.hospitalId)) resetAfterHospital(state);
                state.hospitalId = item.id();
                state.hospital = item.name();
                state.departmentId = null;
                state.department = null;
            });
        }
        if (state.hospitalId == null) {
            return showHospitals(state, null);
        }
        List<DepartmentProfile> rows = departmentCatalogTool.listDepartments(state.id, state.hospitalId);
        if (rows.isEmpty()) {
            return respond(state, state.hospital + "暂时没有配置可预约科室，请选择其他医院。",
                    List.of(q("查看其他医院", "CHANGE_HOSPITAL", "")));
        }
        String names = rows.stream().map(item -> item.name() + "（" + joinOrDefault(item.specialtyTags(), "常规复诊") + "）")
                .collect(java.util.stream.Collectors.joining("、"));
        List<QuickReply> choices = rows.stream().limit(3)
                .map(item -> q(item.name(), "SET_DEPARTMENT", item.id())).toList();
        return respond(state, state.hospital + "目前可办理：" + names + "。请选择医生要求您复诊的科室。", choices);
    }

    private AgentTurnResponse recommendHospitals(ConversationState state, String requestedDepartment) {
        String department = requestedDepartment != null ? requestedDepartment : state.department;
        if (department == null) {
            List<HospitalProfile> rows = hospitalCatalogTool.listHospitals(state.id);
            String summary = rows.stream().limit(3).map(this::hospitalSummary)
                    .collect(java.util.stream.Collectors.joining("；"));
            List<QuickReply> choices = rows.stream().limit(3)
                    .map(item -> q("了解" + item.name(), "SET_HOSPITAL", item.id())).toList();
            return respond(state, "我不能判断哪家医院‘最好’，但可以根据数据库中的科室特色、适老服务和号源帮您筛选。"
                    + summary + "。请先告诉我医生要求复诊的科室。", choices);
        }
        List<HospitalProfile> rows = hospitalCatalogTool.findHospitalsForDepartment(state.id, department);
        if (rows.isEmpty()) {
            return respond(state, "模拟数据库中暂时没有开设" + department + "的医院。您可以换一个科室，或联系人工帮助。",
                    List.of(q("查看医院", "CHANGE_HOSPITAL", ""), q("联系人工", "CONTACT_HUMAN", "")));
        }
        if (!java.util.Objects.equals(state.department, department)) resetAfterDate(state);
        state.department = department;
        state.departmentId = null;
        List<String> reasons = new ArrayList<>();
        for (HospitalProfile item : rows.stream().limit(3).toList()) {
            DepartmentProfile departmentProfile = departmentCatalogTool
                    .listDepartments(state.id, item.id()).stream()
                    .filter(candidate -> candidate.name().equals(department))
                    .findFirst().orElse(null);
            String departmentStrength = departmentProfile == null
                    ? "提供该科室的常规复诊服务"
                    : "该科室主要覆盖" + joinOrDefault(departmentProfile.specialtyTags(), departmentProfile.followupScope());
            reasons.add(item.name() + "：" + departmentStrength + "；适老服务有" +
                    joinOrDefault(item.elderlyServices(), "人工服务"));
        }
        String summary = String.join("。", reasons);
        List<QuickReply> choices = rows.stream().limit(3)
                .map(item -> q("选择" + item.name(), "SET_HOSPITAL", item.id())).toList();
        return respond(state, "根据“" + department + "”和模拟医院资料，我找到了以下选择。" + summary
                + "。这是办理信息筛选，不是医疗诊断，请选择您原就诊医院或医生建议的医院。", choices);
    }

    private String hospitalSummary(HospitalProfile item) {
        return item.name() + (item.level() == null ? "" : "（" + item.level() + "）") +
                "，特色为" + joinOrDefault(item.specialtyTags(), "常规复诊服务") +
                "，提供" + joinOrDefault(item.elderlyServices(), "人工服务");
    }

    private String joinOrDefault(List<String> values, String fallback) {
        return values == null || values.isEmpty() ? fallback : String.join("、", values);
    }

    private String resumeHint(ConversationState state) {
        return switch (state.stage) {
            case ASK_HOSPITAL -> "您可以从中选择一家医院。";
            case ASK_DEPARTMENT -> "您还需要选择复诊科室。";
            case ASK_DATE -> "医院和科室已经保留，接下来仍需确认日期。";
            default -> "原来的办理进度已经保留。";
        };
    }

    private AgentTurnResponse respond(ConversationState state, String reply, List<QuickReply> quickReplies) {
        return finish(state, new AgentTurnResponse(state.id, state.stage.name(), reply, quickReplies,
                plan(state), null, null, traces.findByConversation(state.id)));
    }

    private AgentTurnResponse respondWithPlan(ConversationState state, String reply, List<QuickReply> quickReplies) {
        return finish(state, new AgentTurnResponse(state.id, state.stage.name(), reply, quickReplies,
                plan(state), null, null, traces.findByConversation(state.id)));
    }

    private AgentTurnResponse respondWithoutModel(ConversationState state, String reply,
                                                  List<QuickReply> quickReplies) {
        return respondWithoutModel(state, reply, quickReplies, null);
    }

    private AgentTurnResponse respondWithoutModel(ConversationState state, String reply,
                                                  List<QuickReply> quickReplies, UiDirective directive) {
        return finishWithoutModel(state, new AgentTurnResponse(state.id, state.stage.name(), reply, quickReplies,
                plan(state), null, null, traces.findByConversation(state.id), null, reply, directive));
    }

    private AgentTurnResponse finish(ConversationState state, AgentTurnResponse response) {
        if (Boolean.TRUE.equals(deferFinalization.get())) return response;
        ReplyContext context = replyContextBuilder.build(state, response, knownFacts(state),
                conversations.recentMessages(state.id));
        String reply = answerGenerator.generate(context);
        AgentTurnResponse finalized = new AgentTurnResponse(response.conversationId(), response.stage(), reply,
                response.quickReplies(), response.plan(), response.confirmation(), response.result(),
                response.toolTraces(), taskProgress(state), authoritativeSpeech(response, reply), response.uiDirective());
        conversations.save(state, finalized);
        conversations.addMessage(state.id, "assistant", finalized.reply());
        return finalized;
    }

    /**
     * 口播文本只有在明确区别于草稿时才保留原样（确定性生成），否则跟随最终回复，避免朗读被改写过的旧草稿。
     */
    private String authoritativeSpeech(AgentTurnResponse response, String finalizedReply) {
        String speech = response.speechText();
        if (speech == null || speech.isBlank() || speech.equals(response.reply())) return finalizedReply;
        return speech;
    }

    private AgentTurnResponse finishWithoutModel(ConversationState state, AgentTurnResponse response) {
        if (Boolean.TRUE.equals(deferFinalization.get())) return response;
        AgentTurnResponse finalized = new AgentTurnResponse(response.conversationId(), response.stage(), response.reply(),
                response.quickReplies(), response.plan(), response.confirmation(), response.result(),
                response.toolTraces(), taskProgress(state), authoritativeSpeech(response, response.reply()), response.uiDirective());
        conversations.save(state, finalized);
        conversations.addMessage(state.id, "assistant", finalized.reply());
        return finalized;
    }

    private PlanCard plan(ConversationState state) {
        if (state.taskStatus == ConversationState.TaskStatus.NONE
                || state.taskStatus == ConversationState.TaskStatus.CANCELLED) return null;
        List<String> tasks = List.of(
                "查询可预约日期", "选择复诊时间", "生成复诊材料清单",
                "检查用户日程是否冲突", "生成出发时间建议", "创建复诊提醒", "通知指定家属");
        return new PlanCard(
                valueOrPending(state.hospital), valueOrPending(state.department),
                state.date == null ? "待确认" : state.date.format(DATE_LABEL),
                state.selectedSlot == null ? "待选择" : state.selectedSlot.time().format(TIME_LABEL),
                tasks, state.materials,
                state.travelPlan == null ? "待计算"
                        : state.travelPlan.departureAt().format(TIME_LABEL),
                !Boolean.TRUE.equals(state.notifyFamily) ? "无需通知" : state.contact == null ? "待确认联系人"
                        : state.contact.relationship() + state.contact.name(),
                List.of(state.alternatives.isEmpty() && state.selectedSlot == null ? "待查询" : "已查询",
                        state.selectedSlot == null ? "待选择" : "已选择",
                        state.materials.isEmpty() ? "待生成" : "已生成",
                        state.scheduleChecked ? "已检查" : state.stage == ConversationState.Stage.CONFLICT ? "存在冲突" : "待检查",
                        state.travelPlan == null ? "待计算" : "已计算",
                        state.materialReminderDone ? (Boolean.TRUE.equals(state.needTravel) && !state.departureReminderDone ? "出发提醒待补办" : "已创建") : "待创建",
                        state.notifyFamily == null ? "待确认" : !state.notifyFamily ? "已跳过" : state.notificationDone ? "已通知" : "待通知"));
    }

    private void applyFacts(ConversationState state, ExtractedFacts facts) {
        String oldHospital = state.hospitalId;
        String oldDepartment = state.department;
        LocalDate oldDate = state.date;
        if (facts.hospital() != null) catalog.hospital(facts.hospital()).ifPresent(item -> {
            if (!item.id().equals(state.hospitalId)) {
                state.departmentId = null;
                state.department = null;
            }
            state.hospitalId = item.id();
            state.hospital = item.name();
        });
        if (facts.department() != null && state.hospitalId != null) {
            catalog.department(state.hospitalId, facts.department()).ifPresent(item -> {
                state.departmentId = item.id();
                state.department = item.name();
            });
        }
        if (facts.date() != null) state.date = facts.date();
        if (facts.acceptAlternative() != null) state.acceptAlternative = facts.acceptAlternative();
        if (facts.needCompanion() != null) state.needCompanion = facts.needCompanion();
        if (facts.needTravel() != null) state.needTravel = facts.needTravel();
        if (facts.notifyFamily() != null) state.notifyFamily = facts.notifyFamily();
        if (facts.familyContact() != null) {
            String wanted = facts.familyContact();
            catalog.contacts(state.userId).stream()
                    .filter(item -> contactMatches(item, wanted))
                    .findFirst().ifPresent(item -> state.contact = item);
        }
        if (facts.transport() != null) state.transport = facts.transport();
        if (facts.timePreference() != null) state.timePreference = facts.timePreference();
        if (facts.selectedTime() != null) {
            state.requestedTime = facts.selectedTime();
            if (state.selectedSlot != null && !state.selectedSlot.time().equals(facts.selectedTime())) state.selectedSlot = null;
        }
        if (facts.timePreference() != null && state.selectedSlot != null
                && ("MORNING".equals(facts.timePreference()) != state.selectedSlot.time().isBefore(LocalTime.NOON))) state.selectedSlot = null;
        if (!java.util.Objects.equals(oldHospital, state.hospitalId) || !java.util.Objects.equals(oldDepartment, state.department)
                || !java.util.Objects.equals(oldDate, state.date)) {
            state.selectedSlot = null;
            state.recommendedSlot = null;
            state.alternatives = List.of();
            state.materials = List.of();
            invalidate(state);
        }
        if (Boolean.FALSE.equals(state.notifyFamily)) state.contact = null;
    }

    private void resetAfterDate(ConversationState state) {
        invalidate(state);
        state.date = null;
        state.selectedSlot = null;
        state.recommendedSlot = null;
        state.timePreference = null;
        state.requestedTime = null;
        state.alternatives = List.of();
        state.travelPlan = null;
        state.materials = List.of();
        state.stage = ConversationState.Stage.ASK_DATE;
    }

    private void resetAfterHospital(ConversationState state) {
        invalidate(state);
        clearPendingEntity(state);
        state.hospitalId = null;
        state.hospital = null;
        state.departmentId = null;
        state.department = null;
        state.date = null;
        state.selectedSlot = null;
        state.recommendedSlot = null;
        state.timePreference = null;
        state.requestedTime = null;
        state.alternatives = List.of();
        state.travelPlan = null;
        state.materials = List.of();
        state.stage = ConversationState.Stage.ASK_HOSPITAL;
    }

    private void resetAfterDepartment(ConversationState state) {
        clearPendingEntity(state);
        state.departmentId = null;
        state.department = null;
        state.date = null;
        state.selectedSlot = null;
        state.recommendedSlot = null;
        state.timePreference = null;
        state.requestedTime = null;
        state.alternatives = List.of();
        state.travelPlan = null;
        state.materials = List.of();
        state.acceptAlternative = null;
        state.needCompanion = null;
        state.needTravel = null;
        state.notifyFamily = null;
        state.transport = null;
        state.stage = ConversationState.Stage.ASK_DEPARTMENT;
    }

    private void clearDraft(ConversationState state) {
        clearPendingEntity(state);
        state.hospitalId = null;
        state.hospital = null;
        state.departmentId = null;
        state.department = null;
        state.date = null;
        state.acceptAlternative = null;
        state.needCompanion = null;
        state.needTravel = null;
        state.notifyFamily = null;
        state.transport = null;
        state.selectedSlot = null;
        state.recommendedSlot = null;
        state.timePreference = null;
        state.requestedTime = null;
        state.alternatives = List.of();
        state.travelPlan = null;
        state.contact = null;
        state.materials = List.of();
        state.pendingAction = "CREATE";
        state.appointmentId = null;
        state.pendingAppointmentId = null;
        state.confirmationId = null;
        state.originalAppointmentId = null;
        state.materialReminderDone = false;
        state.departureReminderDone = false;
        state.notificationDone = false;
        state.scheduleChecked = false;
        clearInterruption(state);
    }

    private ConversationState requireSession(String id) {
        ConversationState state = sessions.get(id);
        if (state != null) return state;
        state = conversations.find(id).orElseThrow(() ->
                new IllegalArgumentException("会话不存在或已过期，请重新开始"));
        normalizeCatalogSelections(state);
        sessions.put(id, state);
        return state;
    }

    private List<QuickReply> slotReplies(List<Slot> slots) {
        return slots.stream().map(slot -> q(slotLabel(slot), "SELECT_SLOT", slot.id())).toList();
    }

    private QuickReply q(String label, String action, String value) {
        return new QuickReply(label, action, value);
    }

    private String acknowledgement(ExtractedFacts facts, String fallback) {
        String value = facts.acknowledgement();
        return value == null || value.isBlank() ? fallback : value + " " + fallback;
    }

    private String knownFacts(ConversationState state) {
        return "用户=" + state.userId +
                "；复诊任务状态=" + state.taskStatus +
                "；对话模式=" + state.dialogueMode +
                "；被打断前阶段=" + (state.interruptedStage == null ? "无" : state.interruptedStage) +
                "；当前支线任务=" + valueOrPending(state.sideTask) +
                "；待确认动作=" + valueOrPending(state.pendingAction) +
                "；存在有效确认卡=" + (state.confirmationId != null) +
                "；待确认目录候选=" + (state.pendingEntityName == null ? "无"
                        : state.pendingEntityType + ":" + state.pendingEntityRaw + "→" + state.pendingEntityName) +
                "；医院ID=" + valueOrPending(state.hospitalId) +
                "；医院=" + valueOrPending(state.hospital) +
                "；科室=" + valueOrPending(state.department) +
                "；日期=" + (state.date == null ? "待确认" : state.date) +
                "；接受附近日期=" + state.acceptAlternative +
                "；需要陪同=" + state.needCompanion +
                "；需要出行提醒=" + state.needTravel +
                "；交通方式=" + valueOrPending(state.transport) +
                "；通知家属=" + state.notifyFamily +
                "；家属联系人=" + (state.contact == null ? "待确认"
                        : state.contact.relationship() + state.contact.name()) +
                "；数据库里的家属联系人候选=" + contactCandidates(state) +
                "；当前正等着老人回答的一件事=" + pendingQuestion(state) +
                "；预约草稿缺失字段=" + draftMissingFields(state) +
                "；时段偏好=" + valueOrPending(state.timePreference) +
                "；数据库可用号源=" + state.alternatives.stream().map(this::slotLabel).toList() +
                "；当前推荐号源=" + slotLabel(state.recommendedSlot);
    }

    /**
     * 把目录里真实登记的家属交给模型，由模型做语义落位：“我闺女”“老伴”“我儿子”都要落到具体的人。
     * 这里只提供候选，不做关键词匹配；模型也不允许落到名单以外的人身上。
     */
    private String contactCandidates(ConversationState state) {
        var rows = catalog.contacts(state.userId);
        if (rows.isEmpty()) return "无（没有登记家属，需要通知时要如实说明并建议人工帮助）";
        return rows.stream().map(item -> item.name() + "（" + item.relationship() + "，id=" + item.id() + "）")
                .collect(java.util.stream.Collectors.joining("、"));
    }

    /**
     * 明确写出 Java 手上正等着老人回答的那一件事。阶段名（SELECT_PERIOD 之类）太含糊，
     * 模型据此常常认不出“上午”“可以”这类短回答该落到哪个字段，这一项就是给它的靶子。
     * 判断顺序必须和 syncPresentationStage 保持一致，否则模型会照着错的问题去填。
     */
    private String pendingQuestion(ConversationState state) {
        if (state.taskStatus != ConversationState.TaskStatus.ACTIVE) return "无（当前没有正在办理的事）";
        if (state.hospital == null) return "去哪家医院复诊";
        if (state.department == null) return "复诊看哪个科室";
        if (state.date == null) return "哪天复诊";
        if (state.selectedSlot == null) {
            if (state.recommendedSlot != null) return "“" + slotLabel(state.recommendedSlot) + "”这个时间可不可以";
            if (state.alternatives.isEmpty()) return "哪一天复诊";
            return state.timePreference == null ? "想上午去还是下午去" : "选哪一个具体时段";
        }
        if (state.acceptAlternative == null) return "如果这天没号，接不接受前后几天";
        if (state.needCompanion == null) return "这次复诊需不需要家属陪同";
        if (state.needTravel == null) return "需不需要出行提醒";
        if (state.transport == null) return "打算怎么去医院";
        if (state.notifyFamily == null) return "要不要通知家属";
        if (Boolean.TRUE.equals(state.notifyFamily) && state.contact == null) return "要通知哪一位家属";
        return "没有待答问题，可以进入确认";
    }

    /**
     * 老人说的家属在目录里落不上时，把真实登记的联系人摆出来让他挑，而不是把同一个问题再问一遍。
     * 目录里一个人都没有就如实说明并给人工帮助，不能凭空编一个可以通知的人。
     */
    private AgentTurnResponse askContactFromCatalog(ConversationState state, String raw) {
        var rows = catalog.contacts(state.userId);
        // 老人说的正好是名单上的人（“小丽”），就没必要再让他从名单里挑一遍。
        // 这条路径可能从“检查草稿”绕进来，那一条不落字段，所以这里自己再认一次。
        if (raw != null && !raw.isBlank()) {
            var hit = rows.stream().filter(item -> contactMatches(item, raw)).findFirst();
            if (hit.isPresent()) {
                state.contact = hit.get();
                return advance(state, ExtractedFacts.empty());
            }
        }
        if (rows.isEmpty()) {
            // 前端只有家属的只读展示，没有添加入口，所以不能说“您先添加家属”，那是句办不到的话。
            return respond(state, "您这边还没有登记过家属联系人，我没法直接发通知。"
                            + "需要的话可以找人工帮您登记。",
                    List.of(q("人工帮助", "CONTACT_HUMAN", "")));
        }
        String names = rows.stream().map(item -> item.name() + "（" + item.relationship() + "）")
                .collect(java.util.stream.Collectors.joining("、"));
        String prefix = raw == null || raw.isBlank()
                ? "这次要通知的家属我还没对上。"
                : "您说的“" + raw + "”我没有在您的家属名单里找到。";
        return respond(state, prefix + "目前登记的是：" + names
                        + "。您想通知其中哪一位？说名字或者点下面的按钮都行。",
                rows.stream().map(item -> q("通知" + item.name(), "SET_CONTACT", item.id())).toList());
    }

    /** 家属联系人的落位判断，只做姓名、关系、编号的直接比对，不猜名单以外的任何人。 */
    private boolean contactMatches(Contact item, String wanted) {
        if (wanted == null || wanted.isBlank()) return false;
        return item.id().equalsIgnoreCase(wanted)
                || item.name().equalsIgnoreCase(wanted)
                || item.relationship().equalsIgnoreCase(wanted)
                || wanted.contains(item.name())
                || wanted.contains(item.relationship());
    }

    private TaskProgress taskProgress(ConversationState state) {
        boolean active = taskInProgress(state);
        String summary = state.hospital == null ? "复诊办理尚未选择医院"
                : state.department == null ? state.hospital + " · 待选择科室"
                : state.date == null ? state.hospital + " · " + state.department + " · 待选择日期"
                : state.hospital + " · " + state.department + " · " + state.date;
        return new TaskProgress(active, state.taskStatus.name(), state.stage.name(), summary, missingField(state));
    }

    private String missingField(ConversationState state) {
        if (!taskInProgress(state)) return null;
        if (state.hospital == null) return "医院";
        if (state.department == null) return "科室";
        if (state.date == null) return "日期";
        if (state.selectedSlot == null) return "复诊时间";
        if (state.acceptAlternative == null) return "是否接受附近日期";
        if (state.needCompanion == null) return "是否需要陪同";
        if (state.needTravel == null) return "是否需要出行提醒";
        if (state.transport == null) return "交通方式";
        if (state.notifyFamily == null) return "是否通知家属";
        return null;
    }

    private boolean taskInProgress(ConversationState state) {
        return state.taskStatus == ConversationState.TaskStatus.ACTIVE
                || state.taskStatus == ConversationState.TaskStatus.PAUSED
                || state.taskStatus == ConversationState.TaskStatus.AWAITING_CONFIRMATION;
    }

    private boolean hasTaskFacts(ExtractedFacts facts) {
        return facts.hospital() != null || facts.department() != null || facts.date() != null
                || facts.selectedTime() != null || facts.acceptAlternative() != null
                || facts.needCompanion() != null || facts.needTravel() != null
                || facts.notifyFamily() != null || facts.transport() != null
                || facts.familyContact() != null;
    }

    private boolean advancesTask(AgentOrchestrator.Route route) {
        return switch (route) {
            case CONFIRM_PENDING, DENY_PENDING, KEEP_CONFLICT, CANCEL_CURRENT_TASK, RESTART_TASK, RESUME_TASK,
                    CHANGE_HOSPITAL, CHANGE_DEPARTMENT, CHANGE_DATE, CHANGE_TIME,
                    QUERY_AVAILABLE_SLOTS, CURRENT_FLOW -> true;
            default -> false;
        };
    }

    private void chooseHospital(ConversationState state, String idOrName) {
        CareCatalogRepository.Hospital hospital = catalog.hospital(idOrName)
                .orElseThrow(() -> new IllegalArgumentException("没有找到这个模拟医院，请重新选择"));
        String requestedDepartment = state.department;
        state.hospitalId = hospital.id();
        state.hospital = hospital.name();
        state.departmentId = null;
        state.department = null;
        if (requestedDepartment != null) {
            catalog.department(hospital.id(), requestedDepartment).ifPresent(item -> {
                state.departmentId = item.id();
                state.department = item.name();
            });
        }
    }

    private void chooseDepartment(ConversationState state, String idOrName) {
        CareCatalogRepository.Department department = catalog.department(state.hospitalId, idOrName)
                .orElseThrow(() -> new IllegalArgumentException("这家医院没有配置该科室，请重新选择"));
        state.departmentId = department.id();
        state.department = department.name();
    }

    private void normalizeCatalogSelections(ConversationState state) {
        if (state.hospitalId == null && state.hospital != null) {
            catalog.hospital(state.hospital).ifPresent(item -> {
                state.hospitalId = item.id();
                state.hospital = item.name();
            });
        }
        if (state.departmentId == null && state.department != null && state.hospitalId != null) {
            catalog.department(state.hospitalId, state.department).ifPresent(item -> {
                state.departmentId = item.id();
                state.department = item.name();
            });
        }
    }

    private String valueOrPending(String value) {
        return value == null || value.isBlank() ? "待确认" : value;
    }

    private boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }

    private String slotLabel(Slot slot) {
        return slot == null ? "待选择" : slot.date().format(DATE_LABEL) + " " + slot.time().format(TIME_LABEL);
    }
}
