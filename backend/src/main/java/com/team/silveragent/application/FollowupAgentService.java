package com.team.silveragent.application;

import com.team.silveragent.agent.AgentContext;
import com.team.silveragent.agent.DeepSeekFactExtractor;
import com.team.silveragent.agent.ExtractedFacts;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.model.AgentTurnResponse.ConfirmationCard;
import com.team.silveragent.domain.model.AgentTurnResponse.PlanCard;
import com.team.silveragent.domain.model.AgentTurnResponse.QuickReply;
import com.team.silveragent.domain.model.AgentTurnResponse.ResultCard;
import com.team.silveragent.domain.model.ConversationHistoryResponse;
import com.team.silveragent.domain.model.ToolModels.AppointmentSummary;
import com.team.silveragent.domain.model.ToolModels.Conflict;
import com.team.silveragent.domain.model.ToolModels.DepartmentProfile;
import com.team.silveragent.domain.model.ToolModels.HospitalProfile;
import com.team.silveragent.domain.model.ToolModels.Slot;
import com.team.silveragent.domain.tool.AppointmentTool;
import com.team.silveragent.domain.tool.DepartmentCatalogTool;
import com.team.silveragent.domain.tool.FamilyNotificationTool;
import com.team.silveragent.domain.tool.HospitalCatalogTool;
import com.team.silveragent.domain.tool.MaterialChecklistTool;
import com.team.silveragent.domain.tool.MaterialPreparationTool;
import com.team.silveragent.domain.tool.MyAppointmentTool;
import com.team.silveragent.domain.tool.ScheduleTool;
import com.team.silveragent.domain.tool.TravelTool;
import com.team.silveragent.infrastructure.mock.ToolTraceStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FollowupAgentService {
    private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("M月d日");
    private static final DateTimeFormatter TIME_LABEL = DateTimeFormatter.ofPattern("HH:mm");

    private final AppointmentTool appointmentTool;
    private final HospitalCatalogTool hospitalCatalogTool;
    private final DepartmentCatalogTool departmentCatalogTool;
    private final ScheduleTool scheduleTool;
    private final TravelTool travelTool;
    private final FamilyNotificationTool familyTool;
    private final MaterialChecklistTool materialTool;
    private final MaterialPreparationTool materialPreparationTool;
    private final MyAppointmentTool myAppointmentTool;
    private final ToolTraceStore traces;
    private final DeepSeekFactExtractor extractor;
    private final ConversationStore conversations;
    private final AppointmentRecordStore appointmentRecords;
    private final CareCatalogRepository catalog;
    private final AgentOrchestrator orchestrator;
    private final String defaultUserId;
    private final Map<String, ConversationState> sessions = new ConcurrentHashMap<>();

    public FollowupAgentService(
            AppointmentTool appointmentTool,
            HospitalCatalogTool hospitalCatalogTool,
            DepartmentCatalogTool departmentCatalogTool,
            ScheduleTool scheduleTool,
            TravelTool travelTool,
            FamilyNotificationTool familyTool,
            MaterialChecklistTool materialTool,
            MaterialPreparationTool materialPreparationTool,
            MyAppointmentTool myAppointmentTool,
            ToolTraceStore traces,
            DeepSeekFactExtractor extractor,
            ConversationStore conversations,
            AppointmentRecordStore appointmentRecords,
            CareCatalogRepository catalog,
            AgentOrchestrator orchestrator,
            @Value("${demo.user-id:user-001}") String defaultUserId) {
        this.appointmentTool = appointmentTool;
        this.hospitalCatalogTool = hospitalCatalogTool;
        this.departmentCatalogTool = departmentCatalogTool;
        this.scheduleTool = scheduleTool;
        this.travelTool = travelTool;
        this.familyTool = familyTool;
        this.materialTool = materialTool;
        this.materialPreparationTool = materialPreparationTool;
        this.myAppointmentTool = myAppointmentTool;
        this.traces = traces;
        this.extractor = extractor;
        this.conversations = conversations;
        this.appointmentRecords = appointmentRecords;
        this.catalog = catalog;
        this.orchestrator = orchestrator;
        this.defaultUserId = defaultUserId;
    }

    public AgentTurnResponse start() {
        return start(defaultUserId);
    }

    public AgentTurnResponse start(String requestedUserId) {
        String userId = requestedUserId == null || requestedUserId.isBlank() ? defaultUserId : requestedUserId;
        CareCatalogRepository.UserProfile user = catalog.user(userId)
                .orElseThrow(() -> new IllegalArgumentException("没有找到当前用户，请检查模拟用户数据"));
        String id = UUID.randomUUID().toString();
        ConversationState state = new ConversationState(id, user.id());
        sessions.put(id, state);
        return askHospital(state, "您好，" + user.name() +
                "。我是复诊助手。您可以直接说完整需求，也可以跟着我一步一步办理。请问想去哪家医院复诊？");
    }

    public ConversationHistoryResponse resume(String conversationId) {
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
    public AgentTurnResponse chat(String conversationId, String message) {
        ConversationState state = requireSession(conversationId);
        String value = message == null ? "" : message.trim();
        if (value.isEmpty()) return respond(state, "我没有听清，请再说一次。", List.of(q("重新说一遍", "ASK_HUMAN_INPUT", "")));

        AgentContext context = new AgentContext(state.stage.name(), knownFacts(state),
                LocalDate.now(), conversations.recentMessages(state.id));
        ExtractedFacts facts = extractor.extract(value, context);
        conversations.addMessage(state.id, "user", value);
        AgentOrchestrator.Route route = orchestrator.decide(value, facts, state);
        return switch (route) {
            case EMERGENCY -> respond(state,
                    "这可能是紧急情况。请立即联系身边家属，并拨打120或前往最近的急诊。现在不继续普通预约流程。",
                    List.of(q("联系人工帮助", "CONTACT_HUMAN", "")));
            case MEDICAL_BOUNDARY -> respond(state,
                    "我只能协助办理复诊，不能诊断疾病、解释检查结果或调整用药。请咨询医生或专业医疗机构。",
                    resumeReplies(state, q("咨询人工", "CONTACT_HUMAN", "")));
            case CONFIRM_PENDING -> confirm(conversationId, true);
            case DENY_PENDING -> confirm(conversationId, false);
            case CANCEL_CURRENT_TASK -> cancelTask(state);
            case CANCEL_EXISTING_APPOINTMENT -> beginCancelExistingAppointment(state, facts);
            case QUERY_MY_APPOINTMENTS -> queryMyAppointments(state, facts);
            case RESTART_TASK -> restartInCurrentConversation(state);
            case RESUME_TASK -> resumeInterruptedTask(state);
            case QUERY_HOSPITALS -> showHospitals(state, facts.hospital());
            case QUERY_DEPARTMENTS -> showDepartments(state, facts.hospital());
            case RECOMMEND_HOSPITAL -> recommendHospitals(state, facts.department());
            case QUERY_AVAILABLE_SLOTS -> {
                if (facts.date() != null) applyFacts(state, facts);
                else clearSlotSelection(state, true);
                yield showAvailableSlots(state);
            }
            case ASK_MATERIALS -> showMaterials(state, facts);
            case CHANGE_HOSPITAL -> changeHospital(state, facts);
            case CHANGE_DEPARTMENT -> changeDepartment(state, facts);
            case CHANGE_DATE -> changeDate(state, facts);
            case CHANGE_TIME -> changeTime(state, facts);
            case CURRENT_FLOW -> continueCurrentFlow(state, facts);
        };
    }

    private AgentTurnResponse continueCurrentFlow(ConversationState state, ExtractedFacts facts) {
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
    public AgentTurnResponse act(String conversationId, String action, String value, String label) {
        ConversationState state = requireSession(conversationId);
        String safeValue = value == null ? "" : value.trim();
        String displayLabel = label == null || label.isBlank() ? (safeValue.isBlank() ? "继续办理" : safeValue) : label.trim();
        conversations.addMessage(state.id, "user", "[按钮] " + action + "：" + displayLabel);

        switch (action) {
            case "SET_HOSPITAL" -> chooseHospital(state, safeValue);
            case "SET_DEPARTMENT" -> chooseDepartment(state, safeValue);
            case "SET_DATE" -> { state.date = LocalDate.parse(safeValue); return querySlots(state); }
            case "SET_PERIOD" -> { return recommendPeriod(state, safeValue); }
            case "SHOW_PERIOD_SLOTS" -> { return showPeriodSlots(state, safeValue); }
            case "SET_ALTERNATIVE" -> state.acceptAlternative = Boolean.parseBoolean(safeValue);
            case "SET_COMPANION" -> state.needCompanion = Boolean.parseBoolean(safeValue);
            case "SET_TRAVEL" -> state.needTravel = Boolean.parseBoolean(safeValue);
            case "SET_TRANSPORT" -> state.transport = safeValue;
            case "SET_NOTIFY" -> state.notifyFamily = Boolean.parseBoolean(safeValue);
            case "START_PLAN" -> { return state.selectedSlot == null ? querySlots(state) : checkSchedule(state); }
            case "RETRY_QUERY" -> { return querySlots(state); }
            case "SHOW_AVAILABLE_DATES" -> { return showAvailableSlots(state); }
            case "NEW_BOOKING" -> { return restartInCurrentConversation(state); }
            case "SELECT_SLOT" -> { return selectSlot(state, safeValue); }
            case "KEEP_CONFLICT" -> { return buildConfirmation(state); }
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
            case "SELECT_APPOINTMENT_TO_CANCEL" -> { return prepareExistingCancellation(state, safeValue); }
            case "CANCEL_TASK" -> { return cancelTask(state); }
            case "RESUME_INTERRUPTED" -> { return resumeInterruptedTask(state); }
            case "CONTINUE" -> {
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

    @Transactional
    public AgentTurnResponse confirm(String conversationId, boolean approved) {
        ConversationState state = requireSession(conversationId);
        conversations.addMessage(state.id, "user", approved ? "[确认] 执行操作" : "[确认] 暂不执行");
        if (state.stage != ConversationState.Stage.AWAITING_CONFIRMATION) {
            return respond(state, "当前没有等待确认的操作，请先完成信息填写。", List.of(q("继续办理", "CONTINUE", "")));
        }
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
                return respond(state, "好的，原预约已经保留，没有执行取消操作。",
                        List.of(q("查询我的预约", "QUERY_APPOINTMENTS", ""), q("重新办理", "NEW_BOOKING", "")));
            }
            state.stage = ConversationState.Stage.READY_TO_PLAN;
            return respondWithPlan(state, "好的，没有执行任何写入操作。您可以修改计划后再确认。",
                    List.of(q("修改日期", "CHANGE_DATE", ""), q("修改医院", "CHANGE_HOSPITAL", ""),
                            q("取消整个办理", "CANCEL_TASK", "")));
        }

        try {
            if ("CANCEL".equals(state.pendingAction) || "CANCEL_EXISTING".equals(state.pendingAction)) {
                String targetId = state.pendingAppointmentId != null ? state.pendingAppointmentId : state.appointmentId;
                appointmentTool.cancel(state.id, targetId, state.userId);
                state.pendingAction = "CREATE";
                state.pendingAppointmentId = null;
                if (state.interruptedStage != null) {
                    state.stage = state.interruptedStage;
                    return respond(state, "这条已确认预约已经取消，原模拟号源已释放。要继续刚才未完成的办理吗？",
                            resumeReplies(state));
                }
                state.stage = ConversationState.Stage.CANCELLED;
                return respond(state, "预约已取消，原模拟号源已经释放。",
                        List.of(q("查询我的预约", "QUERY_APPOINTMENTS", ""), q("重新办理", "NEW_BOOKING", "")));
            }

            String appointmentId = appointmentTool.submit(state.id, state.selectedSlot.id(), state.userId);
            state.appointmentId = appointmentId;
            LocalDateTime appointmentAt = LocalDateTime.of(state.selectedSlot.date(), state.selectedSlot.time());

            scheduleTool.createReminder(state.id, state.userId, "复诊材料准备提醒", appointmentAt.minusDays(1));
            String reminderStatus = "已创建复诊提醒";
            if (Boolean.TRUE.equals(state.needTravel) && state.travelPlan != null) {
                scheduleTool.createReminder(state.id, state.userId, "复诊出发提醒", state.travelPlan.departureAt().minusMinutes(10));
                reminderStatus = "已创建复诊及出发提醒";
            }

            String familyStatus = "无需通知家属";
            if (Boolean.TRUE.equals(state.notifyFamily) && state.contact != null) {
                familyTool.notify(state.id, state.contact.id(), "复诊安排：" + state.hospital + state.department + "，" +
                        state.date.format(DATE_LABEL) + " " + state.selectedSlot.time().format(TIME_LABEL) +
                        (Boolean.TRUE.equals(state.needCompanion) ? "，需要陪同。" : "。"));
                familyStatus = "已通知" + state.contact.relationship() + state.contact.name();
            }

            appointmentRecords.complete(appointmentId, state, reminderStatus, familyStatus);
            materialPreparationTool.initialize(state.id, appointmentId, state.department, state.materials);
            state.stage = ConversationState.Stage.COMPLETED;
            ResultCard card = new ResultCard(appointmentId, state.hospital, state.department,
                    state.date.format(DATE_LABEL), state.selectedSlot.time().format(TIME_LABEL), state.materials,
                    state.travelPlan == null ? "无需出行提醒" : state.travelPlan.departureAt().format(TIME_LABEL),
                    reminderStatus, familyStatus);
            return finish(state, new AgentTurnResponse(state.id, state.stage.name(),
                    "已经办理完成。事项页面现在会读取这条真实预约记录。",
                    List.of(q("查看复诊事项", "OPEN_TASKS", ""), q("取消预约", "CANCEL_APPOINTMENT", "")),
                    plan(state), null, card, traces.findByConversation(state.id)));
        } catch (RuntimeException error) {
            return respond(state, "提交时出现问题：" + error.getMessage() + "。没有重复提交，您可以重新查询号源。",
                    List.of(q("重新查询", "RETRY_QUERY", ""), q("咨询人工", "CONTACT_HUMAN", "")));
        }
    }

    public Map<String, Object> modelStatus() {
        return Map.of("mode", extractor.mode(), "model", extractor.model(), "secretStored", false);
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
        state.stage = ConversationState.Stage.AWAITING_CONFIRMATION;
        ConfirmationCard card = new ConfirmationCard("确认取消已经预约的复诊吗？",
                List.of("取消预约：" + appointmentSummary(target), "释放对应模拟号源"),
                "取消后原预约失效；如果仍需复诊，需要重新预约。",
                "确认取消预约", "保留预约");
        return finish(state, new AgentTurnResponse(state.id, state.stage.name(),
                "取消预约属于重要操作，需要您明确确认。", List.of(), null, card,
                resultCard(target), traces.findByConversation(state.id)));
    }

    private AgentTurnResponse restartInCurrentConversation(ConversationState state) {
        clearDraft(state);
        return askHospital(state, "好的，我们重新开始办理复诊。请告诉我就诊医院。");
    }

    private AgentTurnResponse resumeInterruptedTask(ConversationState state) {
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

    private AgentTurnResponse changeHospital(ConversationState state, ExtractedFacts facts) {
        discardInterruption(state);
        resetAfterHospital(state);
        applyFacts(state, facts);
        return state.hospital == null
                ? askHospital(state, "好的，请重新选择医院。原来的预约不会被直接修改。")
                : advance(state, facts);
    }

    private AgentTurnResponse changeDepartment(ConversationState state, ExtractedFacts facts) {
        discardInterruption(state);
        if (state.hospitalId == null) return askHospital(state, "修改科室前，请先选择医院。");
        resetAfterDepartment(state);
        applyFacts(state, facts);
        return state.department == null
                ? askDepartment(state, "好的，请重新选择复诊科室。")
                : advance(state, facts);
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

    private AgentTurnResponse showMaterials(ConversationState state, ExtractedFacts facts) {
        if (state.hospital == null || state.department == null) {
            return respond(state, "材料清单需要根据医院和科室生成。请先完成医院和科室选择。",
                    resumeReplies(state));
        }
        if (state.materials.isEmpty()) {
            state.materials = materialTool.checklist(state.id, state.hospital, state.department);
        }
        return respondWithPlan(state, acknowledgement(facts,
                        "这是根据模拟数据库中的医院和科室规则生成的材料清单。"),
                resumeReplies(state));
    }

    private void rememberInterruptedTask(ConversationState state, String sideTask) {
        if (state.interruptedStage == null && state.stage != ConversationState.Stage.COMPLETED
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
        else if (state.stage != ConversationState.Stage.COMPLETED
                && state.stage != ConversationState.Stage.CANCELLED) replies.add(q("继续办理", "CONTINUE", ""));
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
        if (Boolean.TRUE.equals(state.needTravel) && state.transport == null) {
            state.stage = ConversationState.Stage.ASK_TRANSPORT;
            return respond(state, acknowledgement(facts, "您准备怎样去医院？"), List.of(
                    q("家属开车", "SET_TRANSPORT", "家属开车"),
                    q("打车", "SET_TRANSPORT", "打车"),
                    q("公交", "SET_TRANSPORT", "公交")));
        }
        if (state.notifyFamily == null) {
            state.stage = ConversationState.Stage.ASK_NOTIFY;
            loadPrimaryContact(state);
            String target = state.contact == null ? "家属" : state.contact.relationship() + state.contact.name();
            return respond(state, acknowledgement(facts, "预约完成后，需要通知" + target + "吗？"), List.of(
                    q("通知" + target, "SET_NOTIFY", "true"),
                    q("不用通知", "SET_NOTIFY", "false")));
        }

        if (state.materials.isEmpty()) {
            state.materials = materialTool.checklist(state.id, state.hospital, state.department);
        }
        state.stage = ConversationState.Stage.READY_TO_PLAN;
        return respondWithPlan(state, acknowledgement(facts,
                "我已经把需求拆成办理计划。请先检查计划，确认后再开始查询。"), List.of(
                q("开始办理", "START_PLAN", ""),
                q("修改日期", "CHANGE_DATE", ""),
                q("取消整个办理", "CANCEL_TASK", "")));
    }

    private AgentTurnResponse querySlots(ConversationState state) {
        List<Slot> slots = appointmentTool.queryAvailableSlots(
                state.id, state.hospitalId, state.department, state.date);
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
        state.alternatives = appointmentTool.queryAlternatives(
                state.id, state.hospitalId, state.department, state.date);
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
        Slot recommendation = exact != null ? exact : state.alternatives.stream()
                .min((left, right) -> Long.compare(
                        Math.abs(java.time.Duration.between(requestedTime, left.time()).toMinutes()),
                        Math.abs(java.time.Duration.between(requestedTime, right.time()).toMinutes())))
                .orElse(null);
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
        return advance(state, ExtractedFacts.empty());
    }

    private AgentTurnResponse checkSchedule(ConversationState state) {
        LocalDateTime start = LocalDateTime.of(state.selectedSlot.date(), state.selectedSlot.time());
        List<Conflict> conflicts = scheduleTool.findConflicts(state.id, state.userId, start, start.plusMinutes(60));
        if (!conflicts.isEmpty()) {
            state.stage = ConversationState.Stage.CONFLICT;
            List<Slot> sameDay = appointmentTool.queryAvailableSlots(state.id, state.hospitalId, state.department, state.date)
                    .stream().filter(item -> !item.id().equals(state.selectedSlot.id())).toList();
            state.alternatives = sameDay;
            List<QuickReply> choices = new ArrayList<>(slotReplies(sameDay.stream().limit(2).toList()));
            choices.add(q("重新选择日期", "CHANGE_DATE", ""));
            choices.add(q("仍保留这个时间", "KEEP_CONFLICT", ""));
            return respondWithPlan(state, "这个时间与您的“" + conflicts.get(0).title() +
                    "”冲突。您可以选择其他号源，也可以明确保留当前时间。", choices);
        }
        return buildConfirmation(state);
    }

    private AgentTurnResponse buildConfirmation(ConversationState state) {
        LocalDateTime appointmentAt = LocalDateTime.of(state.selectedSlot.date(), state.selectedSlot.time());
        if (Boolean.TRUE.equals(state.needTravel)) {
            state.travelPlan = travelTool.plan(state.id, state.userId, state.hospital,
                    appointmentAt, state.transport == null ? "打车" : state.transport);
        }
        if (Boolean.TRUE.equals(state.notifyFamily) && state.contact == null) loadPrimaryContact(state);
        state.stage = ConversationState.Stage.AWAITING_CONFIRMATION;

        List<String> operations = new ArrayList<>();
        operations.add("提交模拟预约：" + slotLabel(state.selectedSlot));
        operations.add("保存材料清单：" + String.join("、", state.materials));
        operations.add("创建复诊材料准备提醒");
        if (state.travelPlan != null) operations.add("创建出发提醒：" + state.travelPlan.departureAt().format(TIME_LABEL));
        if (state.contact != null) operations.add("通知" + state.contact.relationship() + "：" + state.contact.name());

        ConfirmationCard card = new ConfirmationCard("请确认复诊办理计划", operations,
                "确认后会占用该模拟号源，并把预约、提醒和通知结果写入数据库。",
                "确认办理", "返回修改");
        return finish(state, new AgentTurnResponse(state.id, state.stage.name(),
                "查询和检查已经完成。请核对材料及全部操作，确认前不会写入预约。",
                List.of(), plan(state), card, null, traces.findByConversation(state.id)));
    }

    private AgentTurnResponse cancelTask(ConversationState state) {
        clearDraft(state);
        state.stage = ConversationState.Stage.CANCELLED;
        return respond(state, "好的，整个办理任务已取消，没有提交预约或发送通知。",
                List.of(q("重新开始", "NEW_BOOKING", ""), q("查询我的预约", "QUERY_APPOINTMENTS", "")));
    }

    private AgentTurnResponse respondWithCancelCard(ConversationState state) {
        ConfirmationCard card = new ConfirmationCard("确认取消已经预约的复诊吗？",
                List.of("取消预约：" + slotLabel(state.selectedSlot), "释放该模拟号源"),
                "取消后原预约失效；如果仍需复诊，需要重新预约。",
                "确认取消预约", "保留预约");
        return finish(state, new AgentTurnResponse(state.id, state.stage.name(),
                "取消预约属于重要操作，需要您明确确认。", List.of(), plan(state), card, null,
                traces.findByConversation(state.id)));
    }

    private AgentTurnResponse askHospital(ConversationState state, String message) {
        state.stage = ConversationState.Stage.ASK_HOSPITAL;
        List<QuickReply> choices = new ArrayList<>(catalog.hospitals().stream()
                .limit(3).map(item -> q(item.name(), "SET_HOSPITAL", item.id())).toList());
        choices.add(q("我还没想好", "ASK_HUMAN_INPUT", ""));
        return respond(state, message, choices);
    }

    private AgentTurnResponse askDepartment(ConversationState state, String message) {
        state.stage = ConversationState.Stage.ASK_DEPARTMENT;
        List<QuickReply> choices = new ArrayList<>(catalog.departments(state.hospitalId).stream()
                .limit(3).map(item -> q(item.name(), "SET_DEPARTMENT", item.id())).toList());
        choices.add(q("我自己说科室", "ASK_HUMAN_INPUT", ""));
        return respond(state, message, choices);
    }

    private AgentTurnResponse askDate(ConversationState state, String message) {
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
                null, null, null, traces.findByConversation(state.id)));
    }

    private AgentTurnResponse respondWithPlan(ConversationState state, String reply, List<QuickReply> quickReplies) {
        return finish(state, new AgentTurnResponse(state.id, state.stage.name(), reply, quickReplies,
                plan(state), null, null, traces.findByConversation(state.id)));
    }

    private AgentTurnResponse finish(ConversationState state, AgentTurnResponse response) {
        conversations.save(state, response);
        conversations.addMessage(state.id, "assistant", response.reply());
        return response;
    }

    private PlanCard plan(ConversationState state) {
        List<String> tasks = List.of(
                "查询可预约日期", "选择复诊时间", "生成复诊材料清单",
                "检查用户日程是否冲突", "生成出发时间建议", "创建复诊提醒", "通知指定家属");
        return new PlanCard(
                valueOrPending(state.hospital), valueOrPending(state.department),
                state.date == null ? "待确认" : state.date.format(DATE_LABEL),
                state.selectedSlot == null ? "待选择" : state.selectedSlot.time().format(TIME_LABEL),
                tasks, state.materials,
                state.travelPlan == null ? (Boolean.TRUE.equals(state.needTravel) ? "待计算" : "无需提醒")
                        : state.travelPlan.departureAt().format(TIME_LABEL),
                state.contact == null ? (Boolean.TRUE.equals(state.notifyFamily) ? "待确认联系人" : "无需通知")
                        : state.contact.relationship() + state.contact.name());
    }

    private void applyFacts(ConversationState state, ExtractedFacts facts) {
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
        if (facts.transport() != null) state.transport = facts.transport();
        if (facts.timePreference() != null) state.timePreference = facts.timePreference();
        if (facts.selectedTime() != null) state.requestedTime = facts.selectedTime();
    }

    private void resetAfterDate(ConversationState state) {
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
                "；医院ID=" + valueOrPending(state.hospitalId) +
                "；医院=" + valueOrPending(state.hospital) +
                "；科室=" + valueOrPending(state.department) +
                "；日期=" + (state.date == null ? "待确认" : state.date) +
                "；接受附近日期=" + state.acceptAlternative +
                "；需要陪同=" + state.needCompanion +
                "；需要出行提醒=" + state.needTravel +
                "；交通方式=" + valueOrPending(state.transport) +
                "；通知家属=" + state.notifyFamily +
                "；时段偏好=" + valueOrPending(state.timePreference) +
                "；数据库可用号源=" + state.alternatives.stream().map(this::slotLabel).toList() +
                "；当前推荐号源=" + slotLabel(state.recommendedSlot);
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

    private void loadPrimaryContact(ConversationState state) {
        try {
            state.contact = familyTool.findPrimaryContact(state.id, state.userId);
        } catch (RuntimeException ignored) {
            state.contact = null;
        }
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
