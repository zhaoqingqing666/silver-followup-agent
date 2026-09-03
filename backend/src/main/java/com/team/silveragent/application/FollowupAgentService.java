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
import com.team.silveragent.domain.model.ToolModels.Conflict;
import com.team.silveragent.domain.model.ToolModels.Slot;
import com.team.silveragent.domain.tool.AppointmentTool;
import com.team.silveragent.domain.tool.FamilyNotificationTool;
import com.team.silveragent.domain.tool.MaterialChecklistTool;
import com.team.silveragent.domain.tool.ScheduleTool;
import com.team.silveragent.domain.tool.TravelTool;
import com.team.silveragent.infrastructure.mock.ToolTraceStore;
import org.springframework.stereotype.Service;

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
    private static final String USER_ID = "user-001";
    private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("M月d日");
    private static final DateTimeFormatter TIME_LABEL = DateTimeFormatter.ofPattern("HH:mm");

    private final AppointmentTool appointmentTool;
    private final ScheduleTool scheduleTool;
    private final TravelTool travelTool;
    private final FamilyNotificationTool familyTool;
    private final MaterialChecklistTool materialTool;
    private final ToolTraceStore traces;
    private final DeepSeekFactExtractor extractor;
    private final ConversationStore conversations;
    private final AppointmentRecordStore appointmentRecords;
    private final Map<String, ConversationState> sessions = new ConcurrentHashMap<>();

    public FollowupAgentService(
            AppointmentTool appointmentTool,
            ScheduleTool scheduleTool,
            TravelTool travelTool,
            FamilyNotificationTool familyTool,
            MaterialChecklistTool materialTool,
            ToolTraceStore traces,
            DeepSeekFactExtractor extractor,
            ConversationStore conversations,
            AppointmentRecordStore appointmentRecords) {
        this.appointmentTool = appointmentTool;
        this.scheduleTool = scheduleTool;
        this.travelTool = travelTool;
        this.familyTool = familyTool;
        this.materialTool = materialTool;
        this.traces = traces;
        this.extractor = extractor;
        this.conversations = conversations;
        this.appointmentRecords = appointmentRecords;
    }

    public AgentTurnResponse start() {
        String id = UUID.randomUUID().toString();
        ConversationState state = new ConversationState(id);
        sessions.put(id, state);
        String greeting = "您好，我是复诊助手。您可以直接告诉我完整需求，也可以跟着我一步一步办理。请问想去哪家医院复诊？";
        AgentTurnResponse response = AgentTurnResponse.message(id, state.stage.name(), greeting, List.of(
                q("市第一医院", "SET_HOSPITAL", "市第一医院"),
                q("市人民医院", "SET_HOSPITAL", "市人民医院"),
                q("我还没想好", "ASK_HUMAN_INPUT", "")));
        conversations.save(state, response);
        conversations.addMessage(id, "assistant", greeting);
        return response;
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

        if ("EMERGENCY".equals(facts.intent()) || containsAny(value, "胸痛", "呼吸困难", "昏迷", "大出血", "喘不上气")) {
            return respond(state, "这可能是紧急情况。请立即联系身边家属，并拨打120或前往最近的急诊。现在不继续普通预约流程。",
                    List.of(q("联系人工帮助", "CONTACT_HUMAN", "")));
        }
        if ("MEDICAL_ADVICE".equals(facts.intent()) || containsAny(value, "怎么用药", "药量", "诊断", "检查结果", "是不是得了")) {
            return respond(state, "我只能协助办理复诊，不能诊断疾病、解释检查结果或调整用药。请咨询医生或专业医疗机构。",
                    List.of(q("继续办理复诊", "CONTINUE", ""), q("咨询人工", "CONTACT_HUMAN", "")));
        }
        if ("CANCEL_TASK".equals(facts.intent())) return cancelTask(state);
        if ("CANCEL_APPOINTMENT".equals(facts.intent()) && state.stage == ConversationState.Stage.COMPLETED) {
            state.pendingAction = "CANCEL";
            state.stage = ConversationState.Stage.AWAITING_CONFIRMATION;
            return respondWithCancelCard(state);
        }

        ConversationState.Stage previousStage = state.stage;
        applyFacts(state, facts);
        if (previousStage == ConversationState.Stage.COMPLETED
                && "CREATE_FOLLOWUP".equals(facts.intent())) {
            return start();
        }
        if (facts.selectedTime() != null
                && (previousStage == ConversationState.Stage.SELECT_PERIOD
                || previousStage == ConversationState.Stage.CONFIRM_SLOT
                || previousStage == ConversationState.Stage.SELECT_SLOT
                || previousStage == ConversationState.Stage.NO_SLOT)) {
            Slot spokenSlot = state.alternatives.stream()
                    .filter(item -> (facts.date() == null || item.date().equals(facts.date()))
                            && item.time().equals(facts.selectedTime()))
                    .findFirst().orElse(null);
            if (spokenSlot != null) return selectSlot(state, spokenSlot.id());
        }
        if ((previousStage == ConversationState.Stage.SELECT_PERIOD
                || previousStage == ConversationState.Stage.CONFIRM_SLOT)
                && facts.timePreference() != null) {
            return recommendPeriod(state, facts.timePreference());
        }
        if (previousStage == ConversationState.Stage.CONFIRM_SLOT && state.recommendedSlot != null) {
            if (Boolean.TRUE.equals(facts.acceptRecommendedTime())) {
                return selectSlot(state, state.recommendedSlot.id());
            }
            if (Boolean.FALSE.equals(facts.acceptRecommendedTime())) {
                return showPeriodSlots(state, state.timePreference);
            }
        }
        if (previousStage == ConversationState.Stage.NO_SLOT && facts.date() != null) {
            state.selectedSlot = null;
            state.recommendedSlot = null;
            state.alternatives = List.of();
            return querySlots(state);
        }
        if ("CHANGE_HOSPITAL".equals(facts.intent())) {
            resetAfterHospital(state);
            if (facts.hospital() != null) state.hospital = facts.hospital();
        }
        if ("CHANGE_DATE".equals(facts.intent())) {
            resetAfterDate(state);
            if (facts.date() != null) state.date = facts.date();
        }
        if ("ASK_MATERIALS".equals(facts.intent()) && !state.materials.isEmpty()) {
            return respondWithPlan(state, acknowledgement(facts,
                    "这是根据您当前医院和科室生成的材料清单。您可以继续修改计划。"),
                    List.of(q("继续办理", "CONTINUE", ""), q("修改日期", "CHANGE_DATE", "")));
        }
        return advance(state, facts);
    }

    /**
     * 明确按钮入口：不调用大模型，直接按action和value更新状态。
     */
    public AgentTurnResponse act(String conversationId, String action, String value) {
        ConversationState state = requireSession(conversationId);
        String safeValue = value == null ? "" : value.trim();
        conversations.addMessage(state.id, "user", "[按钮] " + action + (safeValue.isBlank() ? "" : "：" + safeValue));

        switch (action) {
            case "SET_HOSPITAL" -> state.hospital = safeValue;
            case "SET_DEPARTMENT" -> state.department = safeValue;
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
            case "NEW_BOOKING" -> { return start(); }
            case "SELECT_SLOT" -> { return selectSlot(state, safeValue); }
            case "KEEP_CONFLICT" -> { return buildConfirmation(state); }
            case "CHANGE_DATE" -> {
                resetAfterDate(state);
                return askDate(state, "好的，尚未提交预约。请告诉我新的复诊日期。");
            }
            case "CHANGE_HOSPITAL" -> {
                resetAfterHospital(state);
                return askHospital(state, "好的，尚未提交预约。请重新选择医院。");
            }
            case "CANCEL_APPOINTMENT" -> {
                if (state.stage != ConversationState.Stage.COMPLETED) {
                    return respond(state, "当前没有可以取消的已确认预约。", List.of(q("继续办理", "CONTINUE", "")));
                }
                state.pendingAction = "CANCEL";
                state.stage = ConversationState.Stage.AWAITING_CONFIRMATION;
                return respondWithCancelCard(state);
            }
            case "CANCEL_TASK" -> { return cancelTask(state); }
            case "CONTINUE" -> { return advance(state, ExtractedFacts.empty()); }
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

    public AgentTurnResponse confirm(String conversationId, boolean approved) {
        ConversationState state = requireSession(conversationId);
        conversations.addMessage(state.id, "user", approved ? "[确认] 执行操作" : "[确认] 暂不执行");
        if (state.stage != ConversationState.Stage.AWAITING_CONFIRMATION) {
            return respond(state, "当前没有等待确认的操作，请先完成信息填写。", List.of(q("继续办理", "CONTINUE", "")));
        }
        if (!approved) {
            if ("CANCEL".equals(state.pendingAction)) {
                state.pendingAction = "CREATE";
                state.stage = ConversationState.Stage.COMPLETED;
                return respond(state, "好的，原预约已经保留，没有执行取消操作。",
                        List.of(q("取消预约", "CANCEL_APPOINTMENT", ""), q("返回事项", "CONTINUE", "")));
            }
            state.stage = ConversationState.Stage.READY_TO_PLAN;
            return respondWithPlan(state, "好的，没有执行任何写入操作。您可以修改计划后再确认。",
                    List.of(q("修改日期", "CHANGE_DATE", ""), q("修改医院", "CHANGE_HOSPITAL", ""),
                            q("取消整个办理", "CANCEL_TASK", "")));
        }

        try {
            if ("CANCEL".equals(state.pendingAction)) {
                appointmentTool.cancel(state.id, state.appointmentId, USER_ID);
                state.stage = ConversationState.Stage.CANCELLED;
                state.pendingAction = "CREATE";
                return respond(state, "预约已取消，原模拟号源已经释放。", List.of(q("重新开始", "CHANGE_HOSPITAL", "")));
            }

            String appointmentId = appointmentTool.submit(state.id, state.selectedSlot.id(), USER_ID);
            state.appointmentId = appointmentId;
            LocalDateTime appointmentAt = LocalDateTime.of(state.selectedSlot.date(), state.selectedSlot.time());

            scheduleTool.createReminder(state.id, USER_ID, "复诊材料准备提醒", appointmentAt.minusDays(1));
            String reminderStatus = "已创建复诊提醒";
            if (Boolean.TRUE.equals(state.needTravel) && state.travelPlan != null) {
                scheduleTool.createReminder(state.id, USER_ID, "复诊出发提醒", state.travelPlan.departureAt().minusMinutes(10));
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

    private AgentTurnResponse advance(ConversationState state, ExtractedFacts facts) {
        if (state.hospital == null) return askHospital(state, acknowledgement(facts, "请告诉我就诊医院。"));
        if (state.department == null) {
            state.stage = ConversationState.Stage.ASK_DEPARTMENT;
            return respond(state, acknowledgement(facts, "好的。请问复诊哪个科室？"), List.of(
                    q("心内科", "SET_DEPARTMENT", "心内科"),
                    q("内分泌科", "SET_DEPARTMENT", "内分泌科"),
                    q("神经内科", "SET_DEPARTMENT", "神经内科")));
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
            return respond(state, acknowledgement(facts, "预约完成后，需要通知您的女儿吗？"), List.of(
                    q("通知女儿", "SET_NOTIFY", "true"),
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
                state.id, state.hospital, state.department, state.date);
        state.selectedSlot = null;
        state.recommendedSlot = null;
        if (!slots.isEmpty()) {
            state.alternatives = slots;
            state.stage = ConversationState.Stage.SELECT_PERIOD;
            return respondWithPlan(state, periodSummary(state.date, slots), periodReplies(slots));
        }

        state.stage = ConversationState.Stage.NO_SLOT;
        state.alternatives = appointmentTool.queryAlternatives(
                state.id, state.hospital, state.department, state.date);
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
        state.date = selected.date();
        return advance(state, ExtractedFacts.empty());
    }

    private AgentTurnResponse checkSchedule(ConversationState state) {
        LocalDateTime start = LocalDateTime.of(state.selectedSlot.date(), state.selectedSlot.time());
        List<Conflict> conflicts = scheduleTool.findConflicts(state.id, USER_ID, start, start.plusMinutes(60));
        if (!conflicts.isEmpty()) {
            state.stage = ConversationState.Stage.CONFLICT;
            List<Slot> sameDay = appointmentTool.queryAvailableSlots(state.id, state.hospital, state.department, state.date)
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
            state.travelPlan = travelTool.plan(state.id, USER_ID, state.hospital,
                    appointmentAt, state.transport == null ? "打车" : state.transport);
        }
        if (Boolean.TRUE.equals(state.notifyFamily)) state.contact = familyTool.findPrimaryContact(state.id, USER_ID);
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
        state.stage = ConversationState.Stage.CANCELLED;
        return respond(state, "好的，整个办理任务已取消，没有提交预约或发送通知。",
                List.of(q("重新开始", "CHANGE_HOSPITAL", "")));
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
        return respond(state, message, List.of(
                q("市第一医院", "SET_HOSPITAL", "市第一医院"),
                q("市人民医院", "SET_HOSPITAL", "市人民医院"),
                q("我还没想好", "ASK_HUMAN_INPUT", "")));
    }

    private AgentTurnResponse askDate(ConversationState state, String message) {
        state.stage = ConversationState.Stage.ASK_DATE;
        int year = LocalDate.now().getYear();
        return respond(state, message, List.of(
                q("9月18日", "SET_DATE", year + "-09-18"),
                q("9月19日", "SET_DATE", year + "-09-19"),
                q("9月20日", "SET_DATE", year + "-09-20")));
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
        if (facts.hospital() != null) state.hospital = facts.hospital();
        if (facts.department() != null) state.department = facts.department();
        if (facts.date() != null) state.date = facts.date();
        if (facts.acceptAlternative() != null) state.acceptAlternative = facts.acceptAlternative();
        if (facts.needCompanion() != null) state.needCompanion = facts.needCompanion();
        if (facts.needTravel() != null) state.needTravel = facts.needTravel();
        if (facts.notifyFamily() != null) state.notifyFamily = facts.notifyFamily();
        if (facts.transport() != null) state.transport = facts.transport();
        if (facts.timePreference() != null) state.timePreference = facts.timePreference();
    }

    private void resetAfterDate(ConversationState state) {
        state.date = null;
        state.selectedSlot = null;
        state.recommendedSlot = null;
        state.timePreference = null;
        state.alternatives = List.of();
        state.travelPlan = null;
        state.materials = List.of();
        state.stage = ConversationState.Stage.ASK_DATE;
    }

    private void resetAfterHospital(ConversationState state) {
        state.hospital = null;
        state.department = null;
        state.date = null;
        state.selectedSlot = null;
        state.recommendedSlot = null;
        state.timePreference = null;
        state.alternatives = List.of();
        state.travelPlan = null;
        state.materials = List.of();
        state.stage = ConversationState.Stage.ASK_HOSPITAL;
    }

    private ConversationState requireSession(String id) {
        ConversationState state = sessions.get(id);
        if (state != null) return state;
        state = conversations.find(id).orElseThrow(() ->
                new IllegalArgumentException("会话不存在或已过期，请重新开始"));
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
        return "医院=" + valueOrPending(state.hospital) +
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
