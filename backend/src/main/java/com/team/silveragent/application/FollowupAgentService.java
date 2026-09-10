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
import com.team.silveragent.domain.model.ToolModels.DepartmentProfile;
import com.team.silveragent.domain.model.ToolModels.HospitalProfile;
import com.team.silveragent.domain.model.ToolModels.Slot;
import com.team.silveragent.domain.tool.AppointmentTool;
import com.team.silveragent.domain.tool.DepartmentCatalogTool;
import com.team.silveragent.domain.tool.FamilyNotificationTool;
import com.team.silveragent.domain.tool.HealthRecordTool;
import com.team.silveragent.domain.tool.HospitalCatalogTool;
import com.team.silveragent.domain.tool.MaterialChecklistTool;
import com.team.silveragent.domain.tool.MemoTool;
import com.team.silveragent.domain.tool.ScheduleTool;
import com.team.silveragent.domain.tool.TravelTool;
import com.team.silveragent.infrastructure.mock.ToolTraceStore;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FollowupAgentService {
    private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("yyyy年M月d日");
    private static final DateTimeFormatter TIME_LABEL = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter MEMO_LABEL = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private static final DateTimeFormatter MEMO_DAY_ONLY = DateTimeFormatter.ofPattern("M月d日");
    /** 回答“几点”时表示“不想到点提醒、只记下”的说法，命中则存长期备忘。 */
    private static final String[] MEMO_NO_TIME = {
            "不用", "不提醒", "不需要提醒", "不设", "先不用", "只记下", "不用了", "算了", "长期", "随便"
    };
    /** 回查实测数值时最多说几条（说太多老人记不住）。 */
    private static final int HEALTH_QUERY_LIMIT = 3;
    /**
     * 备忘清单里最多给前几条配“改/删”按钮。前端一次只显示 3 个快捷回复，
     * 所以按钮给全了反而要点很多次“查看更多选项”；正文里每条都带序号，
     * 更靠后的说“改第7条”一样能办。
     */
    private static final int MEMO_BUTTON_LIMIT = 5;
    /** 反问“这个数不太对”之后，老人表示“就按这个记”的说法。 */
    private static final String[] RECORD_KEEP_WORDS = {
            "就按这个", "就按它", "按这个记", "照记", "记下来", "记下", "记上",
            "没错", "就是这个", "对，就是", "就是这样", "是我看错"
    };
    /** 老人表示要重新量的说法。 */
    private static final String[] RECORD_RETRY_WORDS = {
            "重新量", "再量", "重量", "重新测", "重测", "重新说", "再说一遍", "重说", "我说错"
    };
    /** 老人表示不记了。注意判定要排在“记”之前，否则“别记下来”会被当成“记下来”。 */
    private static final String[] RECORD_DROP_WORDS = {"不记", "不用记", "别记", "不要记", "算了", "取消", "不存"};
    /** 助手指认备忘用的“第2条 / 第二条”。 */
    private static final Pattern MEMO_ORDINAL = Pattern.compile("第\\s*([0-9]{1,2}|[一二两三四五六七八九十]{1,2})\\s*条");

    private final AppointmentTool appointmentTool;
    private final HospitalCatalogTool hospitalCatalogTool;
    private final DepartmentCatalogTool departmentCatalogTool;
    private final ScheduleTool scheduleTool;
    private final TravelTool travelTool;
    private final FamilyNotificationTool familyTool;
    private final MaterialChecklistTool materialTool;
    private final MemoTool memoTool;
    private final HealthRecordTool healthRecordTool;
    private final ToolTraceStore traces;
    private final DeepSeekFactExtractor extractor;
    private final ConversationStore conversations;
    private final AppointmentRecordStore appointmentRecords;
    private final CareCatalogRepository catalog;
    private final CareBookingService careBooking;
    private final HealthReportService healthReportService;
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
            MemoTool memoTool,
            HealthRecordTool healthRecordTool,
            ToolTraceStore traces,
            DeepSeekFactExtractor extractor,
            ConversationStore conversations,
            AppointmentRecordStore appointmentRecords,
            CareCatalogRepository catalog,
            CareBookingService careBooking,
            HealthReportService healthReportService,
            @Value("${demo.user-id:user-001}") String defaultUserId) {
        this.appointmentTool = appointmentTool;
        this.hospitalCatalogTool = hospitalCatalogTool;
        this.departmentCatalogTool = departmentCatalogTool;
        this.scheduleTool = scheduleTool;
        this.travelTool = travelTool;
        this.familyTool = familyTool;
        this.materialTool = materialTool;
        this.memoTool = memoTool;
        this.healthRecordTool = healthRecordTool;
        this.traces = traces;
        this.extractor = extractor;
        this.conversations = conversations;
        this.appointmentRecords = appointmentRecords;
        this.catalog = catalog;
        this.careBooking = careBooking;
        this.healthReportService = healthReportService;
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
        // 开场主动告知：若家属/志愿者已帮老人约好复诊，先把计划亮出来，供老人随时查看/改期/取消/求助
        AppointmentRecordStore.AppointmentView arranged = upcomingArranged(state).orElse(null);
        if (arranged != null) {
            state.managedMode = true;
            return respondSimple(state, managedGreeting(arranged, user.name()), List.of(
                    q("查看这次安排", "VIEW_MANAGED", ""),
                    q("临时改期或取消", "MANAGE_MANAGED", ""),
                    q("途中需要帮助", "REQUEST_HELP", ""),
                    q("真紧急，需要帮助", "EMERGENCY_NOW", ""),
                    q("我另外想预约复诊", "CONTINUE", "")));
        }
        return askHospital(state, "您好，" + user.name() +
                "。我是复诊助手。您可以直接说完整需求，也可以跟着我一步一步办理。"
                + "想让我记下健康事项或按时提醒（比如“明早八点提醒我吃药”），直接告诉我就行；"
                + "量了血压、血糖也报给我（比如“我的血压是100”），我会记在首页“健康记录”里，"
                + "想看看有哪些提醒就说“我都有哪些备忘”。请问想去哪家医院复诊？");
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

        if (containsAny(value, "胸痛", "呼吸困难", "昏迷", "大出血", "喘不上气")) {
            conversations.addMessage(state.id, "user", value);
            return emergency(state);
        }
        if (stopped(state)) return stoppedResponse(state);
        if ("MEMO_TIME".equals(state.pendingAction)) {
            conversations.addMessage(state.id, "user", value);
            return applyMemoTime(state, value);
        }
        if ("MEMO_DAY".equals(state.pendingAction)) {
            conversations.addMessage(state.id, "user", value);
            return applyMemoDay(state, value);
        }
        if ("MEMO_REPEAT_DAY".equals(state.pendingAction)) {
            conversations.addMessage(state.id, "user", value);
            return applyMemoRepeatDay(state, value);
        }
        if ("MEMO_EDIT_TIME".equals(state.pendingAction)) {
            conversations.addMessage(state.id, "user", value);
            return applyMemoEdit(state, value);
        }
        if ("MEMO_DELETE_CONFIRM".equals(state.pendingAction)) {
            conversations.addMessage(state.id, "user", value);
            return applyMemoDelete(state, value);
        }
        // 反问“这个数不太对”之后老人的回答。答不上来/改说了别的事时返回 null：
        // 暂存的那条作废，让这句话照常走下面的链路，不把老人卡在这个问题上。
        if ("RECORD_CONFIRM".equals(state.pendingAction)) {
            AgentTurnResponse resolved = applyRecordConfirm(state, value);
            if (resolved != null) {
                conversations.addMessage(state.id, "user", value);
                return resolved;
            }
        }
        AgentContext context = new AgentContext(state.stage.name(), knownFacts(state),
                LocalDate.now(), conversations.recentMessages(state.id));
        ExtractedFacts facts = extractor.extract(value, context);
        conversations.addMessage(state.id, "user", value);

        if ("EMERGENCY".equals(facts.intent()) || containsAny(value, "胸痛", "呼吸困难", "昏迷", "大出血", "喘不上气")) {
            return emergency(state);
        }
        // 助手侧管理已有备忘（查/改/删）：先于“新记一条”判断，“我有哪些备忘”不能被当成新托付
        if (memoContext(state)) {
            AgentTurnResponse command = memoCommandReply(state, value);
            if (command != null) return command;
            // 健康备忘：老人托付“记下来/提醒我”或含明确时间的健康事项时，先于预约/医疗话术处理
            AgentTurnResponse memoReply = memoReply(state, value);
            if (memoReply != null) return memoReply;
        }
        // 把健康记录发给家属（“把这个月的血压发给女儿”）：要排在下面“回查实测数值”之前，
        // 那句话里也有“记录”，先让回查认走就永远发不出去了
        HealthReportParser.ReportIntent report = HealthReportParser.detect(value);
        if (report != null && memoContext(state)) {
            return healthReportReply(state, report);
        }
        // 实测数值（“我的血压是100”“我最近血压多少”）：备忘是“要做的事”，这是“已经量到的数”，分家存
        HealthRecordParser.RecordIntent record = HealthRecordParser.detect(value);
        if (record != null && (record.kind() == HealthRecordParser.Kind.QUERY || memoContext(state))) {
            return healthRecordReply(state, record, value);
        }
        if ("MEDICAL_ADVICE".equals(facts.intent()) || containsAny(value, "怎么用药", "药量", "诊断", "检查结果", "是不是得了", "吃什么药", "推荐药", "加量", "减量", "治疗方案", "停药")) {
            return respond(state, "我只能协助办理复诊，不能诊断疾病、解释检查结果或调整用药。请咨询医生或专业医疗机构。",
                    List.of(q("继续办理复诊", "CONTINUE", ""), q("咨询人工", "CONTACT_HUMAN", "")));
        }
        if ("CANCEL_TASK".equals(facts.intent())) return cancelTask(state);
        if ("CANCEL_APPOINTMENT".equals(facts.intent()) && state.appointmentId != null) {
            state.pendingAction = "CANCEL";
            state.stage = ConversationState.Stage.AWAITING_CONFIRMATION;
            return respondWithCancelCard(state);
        }
        if (state.appointmentId != null) {
            return respondWithPlan(state, "已有预约已保留。请先选择修改预约、取消预约或补办未完成事项。", bookedActions(state));
        }
        invalidate(state);
        if ("QUERY_HOSPITALS".equals(facts.intent()) || "QUERY_HOSPITAL_INFO".equals(facts.intent())) {
            return showHospitals(state, facts.hospital());
        }
        if ("QUERY_DEPARTMENTS".equals(facts.intent())) {
            return showDepartments(state, facts.hospital());
        }
        if ("REQUEST_RECOMMENDATION".equals(facts.intent())) {
            return recommendHospitals(state, facts.department());
        }

        ConversationState.Stage previousStage = state.stage;
        applyFacts(state, facts);
        if (previousStage == ConversationState.Stage.COMPLETED
                && "CREATE_FOLLOWUP".equals(facts.intent())) {
            return start(state.userId);
        }
        if (previousStage == ConversationState.Stage.CONFIRM_SLOT && state.recommendedSlot != null) {
            if (Boolean.TRUE.equals(facts.acceptRecommendedTime())) {
                return selectSlot(state, state.recommendedSlot.id());
            }
            if (Boolean.FALSE.equals(facts.acceptRecommendedTime())) {
                return showPeriodSlots(state, state.timePreference);
            }
        }
        if (facts.selectedTime() != null
                && (previousStage == ConversationState.Stage.SELECT_PERIOD
                || previousStage == ConversationState.Stage.CONFIRM_SLOT
                || previousStage == ConversationState.Stage.SELECT_SLOT
                || previousStage == ConversationState.Stage.NO_SLOT)) {
            return recommendSpecificTime(state, facts.selectedTime());
        }
        if ((previousStage == ConversationState.Stage.SELECT_PERIOD
                || previousStage == ConversationState.Stage.CONFIRM_SLOT)
                && facts.timePreference() != null) {
            return recommendPeriod(state, facts.timePreference());
        }
        if (previousStage == ConversationState.Stage.NO_SLOT && facts.date() != null) {
            state.selectedSlot = null;
            state.recommendedSlot = null;
            state.alternatives = List.of();
            return querySlots(state);
        }
        if ("CHANGE_HOSPITAL".equals(facts.intent())) {
            resetAfterHospital(state);
            if (facts.hospital() != null) chooseHospital(state, facts.hospital());
            applyFacts(state, facts);
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
    public synchronized AgentTurnResponse act(String conversationId, String action, String value, String label) {
        try { return actInternal(conversationId, action, value, label); }
        catch (RuntimeException error) { return toolError(requireSession(conversationId), error); }
    }

    private AgentTurnResponse actInternal(String conversationId, String action, String value, String label) {
        ConversationState state = requireSession(conversationId);
        String safeValue = value == null ? "" : value.trim();
        String displayLabel = label == null || label.isBlank() ? (safeValue.isBlank() ? "继续办理" : safeValue) : label.trim();
        conversations.addMessage(state.id, "user", "[按钮] " + action + "：" + displayLabel);

        if ("NEW_BOOKING".equals(action)) return start(state.userId);
        if ("CONTACT_HUMAN".equals(action)) return respond(state,
                "这里是模拟人工帮助入口，尚未接通真人。当前记录已保留。", List.of());
        if (stopped(state)) return stoppedResponse(state);
        if ("MEMO_TIME".equals(state.pendingAction)) {
            if ("MEMO_TIME".equals(action)) return applyMemoTime(state, safeValue);
            if ("CANCEL_TASK".equals(action)) {
                state.stage = state.memoReturnStage == null ? ConversationState.Stage.READY_TO_PLAN : state.memoReturnStage;
                clearMemoDraft(state);
                return memoHandoff(state, "好的，这条备忘先不记了。");
            }
            return respond(state, "请先告诉我希望几点提醒（或说“不用提醒，只记下”）。",
                    memoNoTimeReply("MEMO_TIME"));
        }
        if ("MEMO_DAY".equals(state.pendingAction)) {
            if ("MEMO_DAY".equals(action)) return applyMemoDay(state, safeValue);
            if ("CANCEL_TASK".equals(action)) {
                state.stage = state.memoReturnStage == null ? ConversationState.Stage.READY_TO_PLAN : state.memoReturnStage;
                clearMemoDraft(state);
                return memoHandoff(state, "好的，这条备忘先不记了。");
            }
            return respond(state, "请先告诉我希望哪一天提醒（或说“不用提醒，只记下”）。",
                    memoDayReplies(MemoParser.nextWeekdayWord(state.pendingMemoText == null ? "" : state.pendingMemoText)));
        }
        if ("MEMO_REPEAT_DAY".equals(state.pendingAction)) {
            if ("MEMO_REPEAT_DAY".equals(action)) return applyMemoRepeatDay(state, safeValue);
            if ("CANCEL_TASK".equals(action)) {
                state.stage = state.memoReturnStage == null ? ConversationState.Stage.READY_TO_PLAN : state.memoReturnStage;
                clearMemoDraft(state);
                return memoHandoff(state, "好的，这条备忘先不记了。");
            }
            return respond(state, "请先告诉我希望每周几或每月几号提醒（或说“不用提醒，只记下”）。",
                    memoNoTimeReply("MEMO_REPEAT_DAY"));
        }
        // 助手侧管理已有备忘：这两个要排在“已有预约”拦截之前，否则约了复诊就改不了备忘
        if ("MEMO_EDIT".equals(action)) {
            MemoStore.MemoView target = activeMemoById(state, safeValue);
            return target == null ? memoMissingReply(state) : askMemoEditTime(state, target);
        }
        if ("MEMO_DELETE".equals(action)) {
            MemoStore.MemoView target = activeMemoById(state, safeValue);
            return target == null ? memoMissingReply(state) : askMemoDelete(state, target);
        }
        if ("MEMO_EDIT_TIME".equals(state.pendingAction)) {
            if ("MEMO_EDIT_TIME".equals(action)) return applyMemoEdit(state, safeValue);
            if ("CANCEL_TASK".equals(action)) {
                cancelMemoCommand(state);
                return memoHandoff(state, "好的，这条备忘没有改。");
            }
            MemoStore.MemoView target = activeMemoById(state, state.pendingMemoId);
            return target == null ? memoMissingReply(state) : askMemoEditTime(state, target);
        }
        if ("MEMO_DELETE_CONFIRM".equals(state.pendingAction)) {
            if ("MEMO_DELETE_CONFIRM".equals(action)) return applyMemoDelete(state, safeValue);
            if ("CANCEL_TASK".equals(action)) {
                cancelMemoCommand(state);
                return memoHandoff(state, "好的，这条备忘保留。");
            }
            return respond(state, "请告诉我删还是不删。", memoDeleteReplies());
        }
        if ("RECORD_CONFIRM".equals(state.pendingAction)) {
            if ("RECORD_KEEP".equals(action)) return recordPendingValue(state);
            if ("RECORD_RETRY".equals(action)) {
                // 保留暂存的这条：老人接下来直接说个数就能当重测值，不用再把“我的血压是”说一遍
                return respond(state, "好，您重新量一下，量好直接把数告诉我就行，比如说“"
                        + state.pendingRecordItem + "是120”。", recordConfirmReplies());
            }
            return respond(state, "请告诉我这个数是不是要重说，还是就按 " + state.pendingRecordValueText + " 记下来。",
                    recordConfirmReplies());
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
        if (state.appointmentId != null && !"CANCEL_APPOINTMENT".equals(action))
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
            case "NEW_BOOKING" -> { return start(state.userId); }
            case "SELECT_SLOT" -> { return selectSlot(state, safeValue); }
            case "KEEP_CONFLICT" -> { state.scheduleChecked = true; return buildConfirmation(state); }
            case "CHANGE_DATE" -> {
                resetAfterDate(state);
                return askDate(state, "好的，尚未提交预约。请告诉我新的复诊日期。");
            }
            case "CHANGE_HOSPITAL" -> {
                resetAfterHospital(state);
                return askHospital(state, "好的，尚未提交预约。请重新选择医院。");
            }
            case "CANCEL_APPOINTMENT" -> {
                if (state.appointmentId == null) {
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
            case "VIEW_MANAGED" -> { return viewManaged(state); }
            case "MANAGE_MANAGED" -> {
                state.managedMode = true;
                return upcomingArranged(state).map(plan -> respondSimple(state,
                        "这次复诊由" + plan.arrangedLabel() + "帮您约好。我可以帮您查看详情、临时改期或取消，改期/取消后会通知" + plan.arrangedLabel() + "。您想怎么调整？",
                        List.of(q("临时改期", "RESCHEDULE_MANAGED", ""),
                                q("取消这次预约", "CANCEL_MANAGED", ""),
                                q("查看这次安排", "VIEW_MANAGED", ""),
                                q("途中需要帮助", "REQUEST_HELP", ""),
                                q("真紧急，需要帮助", "EMERGENCY_NOW", ""),
                                q("联系人工帮助", "CONTACT_HUMAN", ""))))
                        .orElseGet(() -> respondSimple(state, "目前没有家属或志愿者代约的进行中复诊安排。",
                                List.of(q("我想办理复诊", "CONTINUE", ""), q("查看事项", "OPEN_TASKS", ""))));
            }
            case "CANCEL_MANAGED" -> { return confirmManagedCancelCard(state); }
            case "RESCHEDULE_MANAGED" -> { return beginManagedReschedule(state); }
            case "EMERGENCY_NOW" -> { return emergency(state); }
            case "REQUEST_HELP" -> { return requestHelp(state); }
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

        if ("MEMO".equals(state.pendingAction)) {
            return confirmMemo(state, approved);
        }

        if ("CANCEL_MANAGED".equals(state.pendingAction)) {
            return confirmManagedCancel(state, approved);
        }

        if (!approved) {
            state.pendingAction = "CREATE";
            state.stage = state.appointmentId == null ? ConversationState.Stage.READY_TO_PLAN
                    : (state.materialReminderDone && (!Boolean.TRUE.equals(state.needTravel) || state.departureReminderDone)
                    && (!Boolean.TRUE.equals(state.notifyFamily) || state.notificationDone)
                    ? ConversationState.Stage.COMPLETED : ConversationState.Stage.PARTIAL);
            return respondWithPlan(state, "没有执行本次操作，已有预约保留。您可以返回修改。",
                    state.appointmentId == null ? List.of(q("修改日期", "CHANGE_DATE", ""), q("修改偏好", "EDIT_PREFERENCES", ""), q("检查计划", "START_PLAN", "")) : bookedActions(state));
        }
        try {
            if ("CANCEL".equals(state.pendingAction)) {
                appointmentTool.cancel(state.id, state.appointmentId, state.userId);
                state.appointmentId = null;
                state.pendingAction = "CREATE";
                state.stage = ConversationState.Stage.CANCELLED;
                return respond(state, "预约及关联提醒已取消，号源已释放。已发送的家属消息仍保留，请告知家属安排已取消。", List.of(q("新建办理", "NEW_BOOKING", "")));
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
            // 老人把“由安排者约好的复诊”改期成功 → 通知安排者
            if (state.arrangedArrangerId != null && state.appointmentId != null && state.selectedSlot != null) {
                careBooking.notifyCaregiver(state.arrangedArrangerId, state.userId,
                        arrangerRescheduleMessage(state), "reschedule");
                state.arrangedArrangerId = null;
            }
            state.stage = ConversationState.Stage.COMPLETED;
            return executionResult(state, "办理完成，请查看复诊事项卡。");
        } catch (RuntimeException error) {
            return toolError(state, error);
        }
    }

    private AgentTurnResponse executionResult(ConversationState state, String message) {
        String reminders = !state.materialReminderDone ? "复诊提醒未完成"
                : Boolean.TRUE.equals(state.needTravel) && !state.departureReminderDone ? "复诊提醒已创建，出发提醒未完成"
                : Boolean.TRUE.equals(state.needTravel) ? "复诊及出发提醒已创建" : "复诊提醒已创建，不创建出发提醒";
        String family = !Boolean.TRUE.equals(state.notifyFamily) ? "无需通知家属"
                : !state.notificationDone ? "家属通知未完成" : "已通知" + state.contact.relationship() + state.contact.name();
        appointmentRecords.complete(state.appointmentId, state, reminders, family);
        ResultCard card = new ResultCard(state.appointmentId, state.hospital, state.department,
                state.date.format(DATE_LABEL), state.selectedSlot.time().format(TIME_LABEL), state.materials,
                state.travelPlan == null ? "未提供出发建议（路线或时间信息不足）" : state.travelPlan.departureAt().format(TIME_LABEL), reminders, family);
        return finish(state, new AgentTurnResponse(state.id, state.stage.name(), message, bookedActions(state),
                plan(state), null, card, traces.findByConversation(state.id)));
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
                return respondWithPlan(state, "取消未完成，原预约保留：" + error.getMessage(), bookedActions(state));
            }
            state.stage = ConversationState.Stage.PARTIAL;
            return executionResult(state, "预约已保留，后续事项未全部完成：" + error.getMessage() + "。可补办未完成事项，不会重复预约。");
        }
        state.stage = ConversationState.Stage.TOOL_ERROR;
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
        state.stage = ConversationState.Stage.EMERGENCY_PAUSED;
        notifyArranger(state, "emergency",
                "紧急通知：" + elderName(state.userId) + "触发紧急求助，助手已引导拨打120或联系身边人。请尽快联系老人确认情况。");
        return respond(state, "这可能是紧急情况。请立即联系身边人员，拨打120或寻求线下急救帮助。普通办理已暂停，已有记录保留。",
                List.of(q("人工帮助", "CONTACT_HUMAN", "")));
    }

    /** 途中遇到困难：把当前复诊的安排者请来帮忙（不会暂停办理）。 */
    private AgentTurnResponse requestHelp(ConversationState state) {
        notifyArranger(state, "help",
                "途中求助通知：" + elderName(state.userId) + "在复诊途中遇到困难，需要您协助联系或安排接送。");
        return respondSimple(state, "已收到。我已经把您的情况告诉这次复诊的安排者，请他/她尽快帮您；请先确保在安全处休息。",
                List.of(q("返回查看安排", "VIEW_MANAGED", ""),
                        q("临时改期或取消", "MANAGE_MANAGED", ""),
                        q("联系人工帮助", "CONTACT_HUMAN", "")));
    }

    /** 有“由安排者约好”的复诊时，给安排者发定向协同通知；没有则不打扰。 */
    private void notifyArranger(ConversationState state, String kind, String content) {
        upcomingArranged(state).ifPresent(plan ->
                careBooking.notifyCaregiver(plan.arrangedBy(), state.userId, content, kind));
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

    private String notificationMessage(ConversationState state) {
        return "复诊安排：" + state.hospital + " " + state.department + "，" + state.date.format(DATE_LABEL)
                + " " + state.selectedSlot.time().format(TIME_LABEL) + (Boolean.TRUE.equals(state.needCompanion) ? "，需要陪同。" : "，不需要陪同。");
    }

    public Map<String, Object> modelStatus() {
        return Map.of("mode", extractor.mode(), "model", extractor.model(), "secretStored", false);
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
        return respondWithPlan(state, acknowledgement(facts,
                "号源已选择，材料已整理。请检查当前计划，下一步检查日程和出行。"), List.of(
                q("开始办理", "START_PLAN", ""),
                q("修改日期", "CHANGE_DATE", ""),
                q("修改偏好", "EDIT_PREFERENCES", ""), q("取消整个办理", "CANCEL_TASK", "")));
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
        return buildConfirmation(state);
    }

    private AgentTurnResponse buildConfirmation(ConversationState state) {
        if (!ready(state)) return advance(state, ExtractedFacts.empty());
        LocalDateTime at = LocalDateTime.of(state.selectedSlot.date(), state.selectedSlot.time());
        state.travelPlan = callTool(state, "travel.plan", Map.of("hospital", state.hospital, "appointmentAt", at, "transport", state.transport),
                () -> travelTool.plan(state.id, state.userId, state.hospital, at, state.transport));
        state.confirmationId = UUID.randomUUID().toString();
        state.stage = ConversationState.Stage.AWAITING_CONFIRMATION;
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
        return finish(state, new AgentTurnResponse(state.id, state.stage.name(), "请核对本次实际执行内容。", List.of(), plan(state), card, null, traces.findByConversation(state.id)));
    }

    /** 不带计划卡的纯文本回复（用于“家属代约计划”等已有预约的开场与管理话术）。 */
    private AgentTurnResponse respondSimple(ConversationState state, String reply, List<QuickReply> quickReplies) {
        return finish(state, AgentTurnResponse.message(state.id, state.stage.name(), reply, quickReplies));
    }

    /** 当前用户由家属/志愿者代约且仍在进行中的复诊计划。 */
    private java.util.Optional<AppointmentRecordStore.AppointmentView> upcomingArranged(ConversationState state) {
        return appointmentRecords.allFor(state.userId).stream()
                .filter(row -> "CONFIRMED".equals(row.status()))
                .filter(row -> !row.date().isBefore(LocalDate.now()))
                .filter(row -> row.arrangedBy() != null)
                .findFirst();
    }

    private String managedShort(AppointmentRecordStore.AppointmentView plan) {
        return plan.date().format(DATE_LABEL) + " " + plan.time().format(TIME_LABEL)
                + "，" + plan.hospital() + " " + plan.department();
    }

    private String managedGreeting(AppointmentRecordStore.AppointmentView plan, String userName) {
        return "您好，" + userName + "。您目前有一份由" + plan.arrangedLabel() + "帮您约好的复诊安排："
                + managedShort(plan) + "。需要查看详情、临时改期或取消时，直接告诉我就可以；"
                + "遇到紧急情况也请告诉我，我会暂停普通办理并给出求助提示。";
    }

    private String elderName(String userId) {
        return catalog.user(userId).map(CareCatalogRepository.UserProfile::name).orElse(userId);
    }

    private AgentTurnResponse viewManaged(ConversationState state) {
        AppointmentRecordStore.AppointmentView plan = upcomingArranged(state).orElse(null);
        if (plan == null) {
            return respondSimple(state, "目前没有家属或志愿者代约的进行中复诊安排。",
                    List.of(q("我想办理复诊", "CONTINUE", ""), q("查看事项", "OPEN_TASKS", "")));
        }
        List<String> lines = new ArrayList<>();
        lines.add("这次由" + plan.arrangedLabel() + "帮您约好的复诊安排：");
        lines.add("医院科室：" + plan.hospital() + " · " + plan.department());
        lines.add("复诊时间：" + managedShort(plan).split("，")[0]);
        if (plan.departureAt() != null || plan.transport() != null) {
            lines.add((plan.departureAt() == null ? "" : "建议出发 " + plan.departureAt().toLocalTime().format(TIME_LABEL) + "；")
                    + (plan.transport() == null ? "" : "交通方式 " + plan.transport()));
        }
        if (!plan.materials().isEmpty()) lines.add("就诊材料：" + String.join("、", plan.materials()));
        if (plan.reminderStatus() != null) lines.add("提醒：" + plan.reminderStatus());
        lines.add("想临时改期或取消，直接告诉我即可，改动会自动通知" + plan.arrangedLabel() + "。");
        return respondSimple(state, String.join("\n", lines),
                List.of(q("临时改期或取消", "MANAGE_MANAGED", ""),
                        q("查看事项", "OPEN_TASKS", ""),
                        q("联系人工帮助", "CONTACT_HUMAN", "")));
    }

    /** 老人确认取消“代约计划”前，给出明确的取消确认卡。 */
    private AgentTurnResponse confirmManagedCancelCard(ConversationState state) {
        AppointmentRecordStore.AppointmentView plan = upcomingArranged(state).orElse(null);
        if (plan == null) {
            return respondSimple(state, "没有找到可取消的安排，可能已改期或取消。",
                    List.of(q("查看事项", "OPEN_TASKS", "")));
        }
        state.pendingAction = "CANCEL_MANAGED";
        state.stage = ConversationState.Stage.AWAITING_CONFIRMATION;
        state.confirmationId = UUID.randomUUID().toString();
        ConfirmationCard card = new ConfirmationCard(
                "确认取消这次由" + plan.arrangedLabel() + "约好的复诊吗？",
                List.of("医院科室：" + plan.hospital() + " · " + plan.department(),
                        "复诊时间：" + plan.date().format(DATE_LABEL) + " " + plan.time().format(TIME_LABEL),
                        "释放号源并停用关联提醒",
                        "取消后会自动通知安排者" + plan.arrangedLabel()),
                "确认后取消预约；返回则保留原安排。", "确认取消预约", "保留预约", state.confirmationId);
        return finish(state, new AgentTurnResponse(state.id, state.stage.name(),
                "取消这次安排需要明确确认。", List.of(), null, card, null,
                traces.findByConversation(state.id)));
    }

    private AgentTurnResponse confirmManagedCancel(ConversationState state, boolean approved) {
        AppointmentRecordStore.AppointmentView plan = upcomingArranged(state).orElse(null);
        state.pendingAction = "CREATE";
        if (!approved) {
            state.stage = ConversationState.Stage.COMPLETED;
            return respondSimple(state, "好的，没有取消，这次安排仍然保留。",
                    plan == null ? List.of(q("办理复诊", "CONTINUE", ""))
                            : List.of(q("查看这次安排", "VIEW_MANAGED", ""),
                                    q("临时改期或取消", "MANAGE_MANAGED", ""),
                                    q("查看事项", "OPEN_TASKS", "")));
        }
        if (plan == null) {
            state.stage = ConversationState.Stage.COMPLETED;
            return respondSimple(state, "没有找到可取消的安排，可能已改期或取消。",
                    List.of(q("查看事项", "OPEN_TASKS", "")));
        }
        try {
            appointmentTool.cancel(state.id, plan.appointmentId(), state.userId);
        } catch (RuntimeException error) {
            traces.record(state.id, "workflow.error", Map.of("stage", state.stage.name()),
                    Map.of("error", String.valueOf(error.getMessage())), false);
            state.stage = ConversationState.Stage.COMPLETED;
            return respondSimple(state, "取消没有成功，原预约保留：" + error.getMessage(),
                    List.of(q("联系人工帮助", "CONTACT_HUMAN", ""), q("查看事项", "OPEN_TASKS", "")));
        }
        careBooking.notifyCaregiver(plan.arrangedBy(), state.userId,
                "取消通知：" + elderName(state.userId) + "已取消您代约的复诊：" + managedShort(plan) + "。预约与关联提醒已失效。",
                "cancel");
        state.stage = ConversationState.Stage.CANCELLED;
        return respondSimple(state, "已取消这次由" + plan.arrangedLabel() + "约好的复诊，并已通知" + plan.arrangedLabel() + "。号源已释放。",
                List.of(q("新建办理", "NEW_BOOKING", ""), q("查看事项", "OPEN_TASKS", "")));
    }

    /** 只有在能安全插入一条备忘的对话上下文中才识别备忘：约好待办/空闲开场/等待开始办理。 */
    private boolean memoContext(ConversationState state) {
        if (state.stage == ConversationState.Stage.AWAITING_CONFIRMATION) return false;
        return state.appointmentId != null
                || state.hospital == null
                || state.stage == ConversationState.Stage.READY_TO_PLAN;
    }

    /** 备忘识别：识别不到返回 null 走原链路；时间不明确先追问钟点；显式托付直接记，隐式先给确认卡。 */
    private AgentTurnResponse memoReply(ConversationState state, String value) {
        MemoParser.MemoIntent memo = MemoParser.detect(value);
        if (memo == null) return null;
        state.pendingMemoDay = null;
        // “每周提醒我量血压”这种没说周几/几号的，趁早问清楚：照原样存下来只会是一条永远不响的备忘
        if (memo.repeatDayGap() != null) return askMemoRepeatDay(state, memo);
        if (memo.needsDay()) return askMemoDay(state, memo);
        if (memo.needsTime()) return askMemoTime(state, memo);
        if (memo.explicit()) return recordMemo(state, memo);
        state.pendingMemoText = memo.text();
        state.pendingMemoAt = memo.remindAt();
        state.pendingMemoRepeat = memo.repeatRule();
        state.memoNeedsApproval = true;
        state.memoReturnStage = state.stage;
        return memoConfirmCard(state);
    }

    /** 老人说的那天已经过去了（如周四说“这周三”）：先问清楚是哪一天，不替他猜上周还是下周。 */
    private AgentTurnResponse askMemoDay(ConversationState state, MemoParser.MemoIntent memo) {
        state.pendingMemoText = memo.text();
        state.pendingMemoAt = null;
        state.pendingMemoRepeat = memo.repeatRule();
        state.pendingMemoDay = null;
        state.memoNeedsApproval = !memo.explicit();
        state.memoReturnStage = state.stage;
        state.pendingAction = "MEMO_DAY";
        state.stage = ConversationState.Stage.MEMO_TIME;
        state.confirmationId = null;
        LocalDate past = MemoParser.pastWeekdayDate(memo.text());
        String nextWeek = MemoParser.nextWeekdayWord(memo.text());
        return respond(state, "您说的“" + (past == null ? "那天" : memoDayLabel(past)) + "”已经过去了。"
                        + "您是指哪一天呢？可以告诉我“" + nextWeek + "”，或者直接说个日期，比如“9月16号”。"
                        + "不需要提醒就说“不用提醒，只记下”。",
                memoDayReplies(nextWeek));
    }

    private List<QuickReply> memoDayReplies(String nextWeek) {
        return List.of(
                q(nextWeek, "MEMO_DAY", nextWeek),
                q("明天", "MEMO_DAY", "明天"),
                q("不用提醒，只记下", "MEMO_DAY", "不用提醒，只记下"));
    }

    /**
     * “时间还没说清”这一类追问（几点／哪一天／每周几）的快捷回复，只放“不用提醒，只记下”一个。
     *
     * <p>不给“早上8点/下午3点”那种预设：老人没说时间的时候替他挑一个，不是助手该拿的主意。
     * 但“我压根不想设提醒”必须给得出按钮——光在话里提一句“不需要提醒就说…”，
     * 老人得自己把这七个字敲出来，而这条正是长期备忘唯一的入口。
     *
     * <p>action 要跟当前 pendingAction 对齐，按钮才会回到同一个追问处理器里。
     */
    private List<QuickReply> memoNoTimeReply(String action) {
        return List.of(q("不用提醒，只记下", action, "不用提醒，只记下"));
    }

    /** 改提醒时间时的同一个出口：老人可能想说“别提醒了，这条留着”。 */
    private List<QuickReply> memoStopRemindReply() {
        return List.of(q("不用提醒了", "MEMO_EDIT_TIME", "不用提醒了"));
    }

    /** 判星期几的中文日期文案：“9月16日（周三）”。 */
    private String memoDayLabel(LocalDate date) {
        return date.format(MEMO_DAY_ONLY) + "（" + memoWeekdayLabel(date) + "）";
    }

    private String memoWeekdayLabel(LocalDate date) {
        return "周" + "一二三四五六日".charAt(date.getDayOfWeek().getValue() - 1);
    }

    /**
     * 老人说了“每周/每月”却没说星期几/几号：先追问锚点。
     * 不问的话只能存成一条永远不到点的备忘——老人以为设好了，其实到哪天都不响。
     */
    private AgentTurnResponse askMemoRepeatDay(ConversationState state, MemoParser.MemoIntent memo) {
        state.pendingMemoText = memo.text();
        state.pendingMemoAt = null;
        state.pendingMemoRepeat = memo.repeatRule();
        state.pendingMemoDay = null;
        state.memoNeedsApproval = !memo.explicit();
        state.memoReturnStage = state.stage;
        state.pendingAction = "MEMO_REPEAT_DAY";
        state.stage = ConversationState.Stage.MEMO_TIME;
        state.confirmationId = null;
        boolean weekly = !"MONTHLY".equals(memo.repeatRule());
        String ask = weekly
                ? "您想每周几提醒呢？比如说“每周三”"
                : "您想每月几号提醒呢？比如说“每月15号”";
        return respond(state, "好的，这条我帮您记成重复提醒。" + ask + "；不需要提醒就说“不用提醒，只记下”。",
                memoNoTimeReply("MEMO_REPEAT_DAY"));
    }

    /** 追问“每周几/每月几号”的快捷回复。 */
    private List<QuickReply> memoRepeatDayReplies(String repeatRule) {
        List<QuickReply> replies = new ArrayList<>();
        if ("MONTHLY".equals(repeatRule)) {
            replies.add(q("每月1号", "MEMO_REPEAT_DAY", "每月1号"));
            replies.add(q("每月15号", "MEMO_REPEAT_DAY", "每月15号"));
            replies.add(q("每月25号", "MEMO_REPEAT_DAY", "每月25号"));
        } else {
            replies.add(q("每周一", "MEMO_REPEAT_DAY", "每周一"));
            replies.add(q("每周三", "MEMO_REPEAT_DAY", "每周三"));
            replies.add(q("每周五", "MEMO_REPEAT_DAY", "每周五"));
        }
        replies.add(q("不用提醒，只记下", "MEMO_REPEAT_DAY", "不用提醒，只记下"));
        return List.copyOf(replies);
    }

    /** 老人回答“每周几/每月几号”的入口：补上锚点，再走原来“还差钟点就追问”的老路。 */
    private AgentTurnResponse applyMemoRepeatDay(ConversationState state, String answer) {
        String value = answer == null ? "" : answer.trim();
        if (state.pendingMemoText == null || state.pendingMemoText.isBlank()) {
            clearMemoDraft(state);
            return respond(state, "刚才要记的那条内容已经失效，请把想记的话重新对我说一遍。",
                    List.of(q("重新办理复诊", "CONTINUE", "")));
        }
        if (containsAny(value, MEMO_NO_TIME)) {
            // 老人改主意不要到点提醒了：按长期备忘记下，重复规则一并作废
            String text = state.pendingMemoText;
            state.pendingMemoText = null;
            state.pendingMemoRepeat = null;
            state.pendingMemoDay = null;
            state.memoNeedsApproval = false;
            state.memoReturnStage = null;
            state.pendingAction = "CREATE";
            if (state.stage == ConversationState.Stage.MEMO_TIME) state.stage = ConversationState.Stage.READY_TO_PLAN;
            return writeMemo(state, text, null, null);
        }
        LocalDate day = MemoParser.resolveRepeatAnchor(state.pendingMemoRepeat, value);
        if (day == null) {
            return respond(state, "没听清是哪一天。请再说一次，比如“每周三”或“每月15号”；不需要提醒就说“不用提醒，只记下”。",
                    memoNoTimeReply("MEMO_REPEAT_DAY"));
        }
        LocalDateTime at = MemoParser.resolveRemindAt(state.pendingMemoText, value, day);
        if (at == null) {
            // 锚点听懂了、还差钟点：带着这一天接着问几点
            state.pendingMemoDay = day;
            state.pendingAction = "MEMO_TIME";
            return respond(state, "好的，" + repeatAnchorLabel(state.pendingMemoRepeat, day)
                            + "。还差具体几点，请告诉我几点提醒，比如“早上8点”或“下午3点”；"
                            + "不需要到点提醒就说“不用提醒，只记下”。",
                    memoNoTimeReply("MEMO_TIME"));
        }
        return finishMemoAnswer(state, at);
    }

    /** 助手侧查/改/删已有备忘：识别不出返回 null，走“新记一条”和原链路。 */
    private AgentTurnResponse memoCommandReply(ConversationState state, String value) {
        MemoCommandParser.MemoCommand command = MemoCommandParser.detect(value);
        if (command == null) return null;
        List<MemoStore.MemoView> rows = activeMemos(state);
        if (command.kind() == MemoCommandParser.Kind.LIST) {
            return showMemos(state, rows, "您现在的健康备忘有这些：");
        }
        if (rows.isEmpty()) {
            return respond(state, "您现在没有进行中的健康备忘，所以没有能改或删的。想记一条就说“明早八点提醒我吃药”。",
                    List.of(q("继续办理复诊", "CONTINUE", "")));
        }
        List<MemoStore.MemoView> picked = matchMemos(rows, command.head());
        // 认不出是哪条、或好几条都对得上：把清单摆出来让老人自己点，不替他猜
        if (picked.isEmpty()) return showMemos(state, rows, "没有找到跟您说的一样的那条。您现在的健康备忘有这些：");
        if (picked.size() > 1) return showMemos(state, picked, "这几条都对得上，您想动哪一条？");
        MemoStore.MemoView target = picked.get(0);
        if (command.kind() == MemoCommandParser.Kind.DELETE) return askMemoDelete(state, target);
        if (command.tail() != null && !command.tail().isBlank()) {
            // 一句话里就带了新时间（“把周三那条提醒改到周四下午三点”）：直接改
            askMemoEditTime(state, target);
            return editMemoTo(state, target, command.tail());
        }
        return askMemoEditTime(state, target);
    }

    /** 摆出备忘清单（可带一句前言）；每条给“改/删”的快捷回复，老人不用记序号。 */
    private AgentTurnResponse showMemos(ConversationState state, List<MemoStore.MemoView> rows, String preface) {
        if (rows.isEmpty()) {
            return respond(state, "您还没有健康备忘。跟我说一句“明早八点提醒我吃药”，我就帮您记一条。",
                    List.of(q("继续办理复诊", "CONTINUE", "")));
        }
        StringBuilder text = new StringBuilder(preface);
        for (int index = 0; index < rows.size(); index++) {
            text.append(index + 1).append("．").append(memoLine(rows.get(index))).append("；");
        }
        text.append("要改哪条就说“改第几条”，要删就说“删掉第几条”。");
        List<QuickReply> replies = new ArrayList<>();
        for (int index = 0; index < Integer.min(MEMO_BUTTON_LIMIT, rows.size()); index++) {
            MemoStore.MemoView memo = rows.get(index);
            replies.add(q("改第" + (index + 1) + "条", "MEMO_EDIT", memo.id()));
            replies.add(q("删第" + (index + 1) + "条", "MEMO_DELETE", memo.id()));
        }
        replies.add(q("都不改", "CONTINUE", ""));
        return respond(state, text.toString(), List.copyOf(replies));
    }

    /** 一条备忘的列表文案：“吃药（每天 08:00）”。 */
    private String memoLine(MemoStore.MemoView memo) {
        String when = memo.remindAt() == null ? "长期备忘"
                : memo.repeatRule() == null ? memoTimeLabel(memo.remindAt())
                : memoRepeatLabel(memo.remindAt(), memo.repeatRule());
        return memo.text() + "（" + when + "）";
    }

    private List<MemoStore.MemoView> activeMemos(ConversationState state) {
        return callTool(state, "memo.list", Map.of("userId", state.userId),
                () -> memoTool.active(state.id, state.userId));
    }

    private MemoStore.MemoView activeMemoById(ConversationState state, String memoId) {
        if (memoId == null) return null;
        return activeMemos(state).stream().filter(memo -> memo.id().equals(memoId)).findFirst().orElse(null);
    }

    /** “第2条/第二条”→ 2；认不出返回 -1。 */
    private int memoOrdinalNumber(String token) {
        if (token != null && token.matches("[0-9]{1,2}")) return Integer.parseInt(token);
        return switch (token == null ? "" : token) {
            case "一" -> 1;
            case "二", "两" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            case "十" -> 10;
            default -> -1;
        };
    }

    /**
     * 老人说的是哪一条：先认“第2条”，再拿“周三/15号/9月11日/正文两字词”比对。
     * 返回空表示没认出来、多条表示对得上好几条 —— 两种都交给调用方摆清单，不猜。
     */
    private List<MemoStore.MemoView> matchMemos(List<MemoStore.MemoView> rows, String head) {
        String value = head == null ? "" : head;
        Matcher ordinal = MEMO_ORDINAL.matcher(value);
        if (ordinal.find()) {
            int index = memoOrdinalNumber(ordinal.group(1));
            return index >= 1 && index <= rows.size() ? List.of(rows.get(index - 1)) : List.of();
        }
        List<MemoStore.MemoView> picked = new ArrayList<>();
        for (MemoStore.MemoView memo : rows) {
            for (String cue : memoCues(memo)) {
                if (!cue.isBlank() && value.contains(cue)) { picked.add(memo); break; }
            }
        }
        return picked;
    }

    /** 老人可能用来指认这条备忘的词：周几、几号、月日，以及正文里的两字词（“吃药”“量血压”）。 */
    private List<String> memoCues(MemoStore.MemoView memo) {
        List<String> cues = new ArrayList<>();
        LocalDateTime at = memo.remindAt();
        if (at != null) {
            char weekday = "一二三四五六日".charAt(at.getDayOfWeek().getValue() - 1);
            cues.add(at.getMonthValue() + "月" + at.getDayOfMonth() + "日");
            cues.add(at.getDayOfMonth() + "号");
            cues.add("周" + weekday);
            cues.add("星期" + weekday);
        }
        String text = memo.text().replaceAll("[，。、！？：；,.!?:;\\s]", "");
        for (int index = 0; index + 1 < text.length(); index++) cues.add(text.substring(index, index + 2));
        return cues;
    }

    /** 问“改成什么时候”。顺手把这条记进 pendingMemoId，老人下一句只说时间就够了。 */
    private AgentTurnResponse askMemoEditTime(ConversationState state, MemoStore.MemoView memo) {
        state.pendingMemoId = memo.id();
        state.pendingAction = "MEMO_EDIT_TIME";
        state.confirmationId = null;
        return respond(state, "要把「" + memo.text() + "」的提醒改到什么时候？可以说“明天早上八点”，"
                + "也可以说“改成每周三下午三点”；不想再提醒就说“不用提醒了”。", memoStopRemindReply());
    }

    /** 老人回答“改成几点”的入口；改完回读新时间，老人才能确认没听错。 */
    private AgentTurnResponse applyMemoEdit(ConversationState state, String answer) {
        MemoStore.MemoView memo = activeMemoById(state, state.pendingMemoId);
        if (memo == null) return memoMissingReply(state);
        return editMemoTo(state, memo, answer);
    }

    /** 把某条备忘的提醒改成老人新说的那个时间（也可改成重复的，或改成不再提醒）。 */
    private AgentTurnResponse editMemoTo(ConversationState state, MemoStore.MemoView memo, String answer) {
        String value = answer == null ? "" : answer.trim();
        // 正文原来写着的“每天早上八点”要跟着时间一起走：只改 remind_at 会让首页出现
        // “事项：每天早上八点量血压 / 提醒：每周五 15:00”这种自己跟自己打架的显示。
        // 以后时间只由“提醒”那一行负责，正文只留事项本身。
        String text = MemoParser.stripSchedule(memo.text());
        boolean retitled = !text.equals(memo.text());
        String tail = retitled ? "（这条原来正文里的时间说法已经去掉，时间以提醒为准。）" : "";
        if (containsAny(value, MEMO_NO_TIME)) {
            callTool(state, "memo.update", Map.of("memoId", memo.id(), "remindAt", "长期"),
                    () -> memoTool.update(state.id, state.userId, memo.id(), text, null, null));
            cancelMemoCommand(state);
            return memoHandoff(state, "好的，已把「" + text + "」改成不再提醒，只留在健康备忘里。" + tail);
        }
        String repeat = MemoParser.repeatRuleIn(value);
        LocalDate anchor = null;
        if (repeat != null && !"DAILY".equals(repeat)) {
            anchor = MemoParser.resolveRepeatAnchor(repeat, value);
            // 只说“每周/每月”没说周几/几号：接着问，不能存一条永远不响的重复提醒
            if (anchor == null) {
                return respond(state, "没听清是每周几还是每月几号。请再说一次，比如“改成每周三下午三点”。",
                        List.of());
            }
        }
        // 算时间仍用原话：老人只说“改成每周三”时，钟点要沿用正文里那个（“每天八点”的八点），
        // 否则会说“没听清”再问一遍，而且第二轮答钟点时把每周三这个周期丢掉
        LocalDateTime at = MemoParser.resolveRemindAt(memo.text(), value, anchor);
        if (at == null) {
            return respond(state, "没听清要改成什么时候。请再说一个时间，比如“明天早上八点”；不想再提醒就说“不用提醒了”。",
                    memoStopRemindReply());
        }
        callTool(state, "memo.update",
                Map.of("memoId", memo.id(), "remindAt", at, "repeat", repeat == null ? "仅一次" : repeat),
                () -> memoTool.update(state.id, state.userId, memo.id(), text, at, repeat));
        cancelMemoCommand(state);
        String when = repeat == null ? memoTimeLabel(at) : "以后" + memoRepeatLabel(at, repeat);
        return memoHandoff(state, "好的，已把「" + text + "」的提醒改成" + when + "，到点打开应用会提醒您。" + tail);
    }

    /** 删之前先问一句：删掉就找不回来了。 */
    private AgentTurnResponse askMemoDelete(ConversationState state, MemoStore.MemoView memo) {
        state.pendingMemoId = memo.id();
        state.pendingAction = "MEMO_DELETE_CONFIRM";
        state.confirmationId = null;
        return respond(state, "要删掉「" + memoLine(memo) + "」这条备忘吗？删掉就找不回来了。", memoDeleteReplies());
    }

    /** 老人回答“删还是不删”。先认否定词：老人说“先不删”里也有个“删”字，不能当成同意。 */
    private AgentTurnResponse applyMemoDelete(ConversationState state, String answer) {
        String value = answer == null ? "" : answer.trim();
        MemoStore.MemoView memo = activeMemoById(state, state.pendingMemoId);
        if (memo == null) return memoMissingReply(state);
        if (containsAny(value, "不删", "先不", "不用", "别删", "保留", "取消", "算了")) {
            cancelMemoCommand(state);
            return memoHandoff(state, "好的，「" + memo.text() + "」这条备忘保留。");
        }
        if (!containsAny(value, "删", "确定", "确认", "好", "是")) {
            return respond(state, "请告诉我删还是不删。", memoDeleteReplies());
        }
        callTool(state, "memo.delete", Map.of("memoId", memo.id()),
                () -> memoTool.remove(state.id, state.userId, memo.id()));
        cancelMemoCommand(state);
        return memoHandoff(state, "好的，已删掉「" + memo.text() + "」这条备忘。");
    }


    private List<QuickReply> memoDeleteReplies() {
        return List.of(
                q("确认删掉", "MEMO_DELETE_CONFIRM", "确认删掉"),
                q("先不删", "MEMO_DELETE_CONFIRM", "先不删"));
    }

    /** 要改/删的那条已经不在了（别处删掉了）：别硬改，把话头交回去。 */
    private AgentTurnResponse memoMissingReply(ConversationState state) {
        cancelMemoCommand(state);
        return memoHandoff(state, "这条备忘已经不在了，可能刚才已经删过了。");
    }

    /** 退出“改/删某条备忘”的追问状态。 */
    private void cancelMemoCommand(ConversationState state) {
        state.pendingMemoId = null;
        if ("MEMO_EDIT_TIME".equals(state.pendingAction) || "MEMO_DELETE_CONFIRM".equals(state.pendingAction)) {
            state.pendingAction = "CREATE";
        }
    }

    /** 重复锚点的中文小字：“每周三” / “每月15号”。 */
    private String repeatAnchorLabel(String repeatRule, LocalDate day) {
        if ("MONTHLY".equals(repeatRule)) return "每月" + day.getDayOfMonth() + "号";
        return "每周" + "一二三四五六日".charAt(day.getDayOfWeek().getValue() - 1);
    }

    /** 老人只给了日期/时段没给钟点（如“明早”“每天”）：先追问具体几点，暂不落库。 */
    private AgentTurnResponse askMemoTime(ConversationState state, MemoParser.MemoIntent memo) {
        state.pendingMemoText = memo.text();
        state.pendingMemoAt = null;
        state.pendingMemoRepeat = memo.repeatRule();
        state.pendingMemoDay = null;
        state.memoNeedsApproval = !memo.explicit();
        state.memoReturnStage = state.stage;
        state.pendingAction = "MEMO_TIME";
        state.stage = ConversationState.Stage.MEMO_TIME;
        state.confirmationId = null;
        String hint = MemoParser.timeHintOf(memo.text());
        // “每天提醒我量血压”里没有任何日期词，回显“当天”会让老人莫名其妙；重复的就说重复周期
        if ("当天".equals(hint) && memo.repeatRule() != null) hint = repeatLabel(memo.repeatRule());
        String preface = memo.explicit()
                ? "好的，我帮您记着这件健康事项。您说的是“" + hint + "”，但还差具体几点。"
                : "您说的这件健康事项我可以记下来并到点提醒。您说的是“" + hint + "”，但还差具体几点。";
        return respond(state, preface + "请告诉我几点提醒，比如“早上8点”或“下午3点”；不需要到点提醒就说“不用提醒，只记下”。",
                memoNoTimeReply("MEMO_TIME"));
    }

    /** 备忘确认卡（复用预约确认门）。隐式备忘在老人点头后才落库。 */
    private AgentTurnResponse memoConfirmCard(ConversationState state) {
        state.pendingAction = "MEMO";
        state.stage = ConversationState.Stage.AWAITING_CONFIRMATION;
        state.confirmationId = UUID.randomUUID().toString();
        String text = state.pendingMemoText;
        LocalDateTime at = state.pendingMemoAt;
        List<String> operations = new ArrayList<>();
        operations.add("备忘内容：" + text);
        operations.add(at == null ? "提醒：暂不设置时间（作为长期备忘）"
                : "提醒：" + memoRepeatLabel(at, state.pendingMemoRepeat) + "，到点打开应用会提醒您");
        ConfirmationCard card = new ConfirmationCard("帮您记下这条健康备忘吗？", operations,
                "只记录健康/复诊相关事项；确认后写入首页“健康备忘”，可随时查看、标记完成或删除。",
                "确认记下", "先不用", state.confirmationId);
        return finish(state, new AgentTurnResponse(state.id, state.stage.name(),
                "您说的这件健康事项，我可以帮您记下来并按时提醒。需要我记下吗？", List.of(),
                null, card, null, traces.findByConversation(state.id)));
    }

    /** 显式托付：调备忘录工具落库，回复后回到原办理上下文。 */
    private AgentTurnResponse recordMemo(ConversationState state, MemoParser.MemoIntent memo) {
        return writeMemo(state, memo.text(), memo.remindAt(), memo.repeatRule());
    }

    /** 落库一条备忘并回到原办理上下文（显式直写、以及追问后落库共用）。 */
    private AgentTurnResponse writeMemo(ConversationState state, String text, LocalDateTime at, String repeatRule) {
        String repeat = MemoStore.normalizeRepeat(repeatRule);
        // 正文只留“事项”，时间只由“提醒”那一行负责。不摘的话首页会变成
        // “明早八点提醒我吃药 / 提醒：明天 08:00”，同一件事说两遍，改过时间的旧备忘
        // 又是另一种长相。没有提醒的长期备忘不能摘——“9月20号家人来接我”里的日期
        // 是内容本身，不是提醒，摘掉就把事记残了。
        String saved = at == null ? text : MemoParser.stripSchedule(text);
        MemoStore.MemoView created = callTool(state, "memo.create",
                Map.of("text", saved, "remindAt", at == null ? "长期" : at.toString(),
                        "repeat", repeat == null ? "仅一次" : repeat),
                () -> memoTool.create(state.id, state.userId, saved, at, repeat));
        state.pendingMemoRepeat = null;
        state.pendingMemoDay = null;
        return memoHandoff(state, memoSavedReply(created.text(), at, repeat));
    }

    /** 隐式备忘的确认结果（复用预约确认门）。 */
    private AgentTurnResponse confirmMemo(ConversationState state, boolean approved) {
        state.pendingAction = "CREATE";
        state.stage = state.memoReturnStage == null ? ConversationState.Stage.READY_TO_PLAN : state.memoReturnStage;
        state.memoReturnStage = null;
        state.memoNeedsApproval = false;
        String text = state.pendingMemoText;
        LocalDateTime at = state.pendingMemoAt;
        String repeat = state.pendingMemoRepeat;
        state.pendingMemoText = null;
        state.pendingMemoAt = null;
        state.pendingMemoRepeat = null;
        state.pendingMemoDay = null;
        if (!approved) return memoHandoff(state, "好的，这条没有记下。");
        if (text == null || text.isBlank()) {
            return memoHandoff(state, "这条备忘内容已失效，请重新对我说一遍。");
        }
        return writeMemo(state, text, at, repeat);
    }

    /** 老人回答“几点”的入口：可打字（聊天）也可点快捷回复（动作 MEMO_TIME）。 */
    private AgentTurnResponse applyMemoTime(ConversationState state, String answer) {
        return applyMemoAnswer(state, answer, memoNoTimeReply("MEMO_TIME"),
                "没听清具体钟点。请再说一个时间，比如“早上8点”或“下午3点”；不需要到点提醒就说“不用提醒，只记下”。");
    }

    /** 老人回答“哪一天”的入口（“这个星期三”已经过去时追问用）。 */
    private AgentTurnResponse applyMemoDay(ConversationState state, String answer) {
        return applyMemoAnswer(state, answer, memoDayReplies(MemoParser.nextWeekdayWord(
                        state.pendingMemoText == null ? "" : state.pendingMemoText)),
                "没听清是哪一天。请再说一次日期，比如“下周三”或“9月16号”；不需要提醒就说“不用提醒，只记下”。");
    }

    /** 追问钟点/日期后的共同落地：解析出到点时间 → 隐式走确认卡、显式直写。 */
    private AgentTurnResponse applyMemoAnswer(ConversationState state, String answer,
                                              List<QuickReply> retryReplies, String retryNote) {
        String value = answer == null ? "" : answer.trim();
        if (state.pendingMemoText == null || state.pendingMemoText.isBlank()) {
            clearMemoDraft(state);
            return respond(state, "刚才要记的那条内容已经失效，请把想记的话重新对我说一遍。",
                    List.of(q("重新办理复诊", "CONTINUE", "")));
        }
        boolean keepStanding = containsAny(value, MEMO_NO_TIME);
        LocalDate answeredDay = keepStanding ? null : MemoParser.resolveDay(value);
        LocalDateTime at = keepStanding ? null
                : MemoParser.resolveRemindAt(state.pendingMemoText, value, state.pendingMemoDay);
        if (!keepStanding && at == null) {
            // 日子听懂了（“下周三”）但还差钟点：接着问几点，别把刚听懂的日子又丢掉，
            // 也不能回一句“没听清是哪一天”——老人明明已经说清楚了
            if (answeredDay != null) {
                state.pendingMemoDay = answeredDay;
                state.pendingAction = "MEMO_TIME";
                return respond(state, "好的，记成" + memoDayLabel(answeredDay)
                                + "。还差具体几点，请告诉我几点提醒，比如“早上8点”或“下午3点”；"
                                + "不需要到点提醒就说“不用提醒，只记下”。",
                        memoNoTimeReply("MEMO_TIME"));
            }
            return respond(state, retryNote, retryReplies);
        }
        return finishMemoAnswer(state, at);
    }

    /** 追问齐了（内容+到点时间）之后的共同落地：隐式备忘走确认卡，显式直写。 */
    private AgentTurnResponse finishMemoAnswer(ConversationState state, LocalDateTime at) {
        state.pendingMemoDay = null;
        state.pendingMemoAt = at;
        if (state.memoNeedsApproval) return memoConfirmCard(state);
        String text = state.pendingMemoText;
        String repeat = state.pendingMemoRepeat;
        state.pendingMemoText = null;
        state.pendingMemoRepeat = null;
        state.pendingMemoDay = null;
        state.pendingAction = "CREATE";
        state.stage = state.memoReturnStage == null ? ConversationState.Stage.READY_TO_PLAN : state.memoReturnStage;
        state.memoReturnStage = null;
        state.memoNeedsApproval = false;
        return writeMemo(state, text, at, repeat);
    }

    /** 丢弃暂存中的备忘草稿（追问被打断/内容失效时）。 */
    private void clearMemoDraft(ConversationState state) {
        state.pendingMemoText = null;
        state.pendingMemoAt = null;
        state.pendingMemoRepeat = null;
        state.pendingMemoDay = null;
        state.memoNeedsApproval = false;
        state.memoReturnStage = null;
        state.confirmationId = null;
        state.pendingAction = "CREATE";
        if (state.stage == ConversationState.Stage.MEMO_TIME) {
            state.stage = ConversationState.Stage.READY_TO_PLAN;
        }
    }

    private String memoSavedReply(String text, LocalDateTime at, String repeatRule) {
        String repeat = MemoStore.normalizeRepeat(repeatRule);
        String when;
        if (at == null) {
            when = "这条作为长期备忘保留。";
        } else if (repeat == null) {
            when = "到" + memoTimeLabel(at) + "打开应用会提醒您。";
        } else {
            // 重复提醒把周期锚点一起回读（“以后每周三 15:00”）：
            // 只说“每周”老人听不出是哪天，也就没法发现听错了
            when = "以后" + memoRepeatLabel(at, repeat) + "到点打开应用会提醒您。";
        }
        return "好的，已记下：“" + text + "”。" + when
                + "您可以在首页“健康备忘”中查看、标记完成或删除。";
    }

    /**
     * 回读用文案：一次性就是“9月16日 15:00”；重复的带上周期锚点，
     * 让老人听到的正是首页会显示的那句（“每天 08:00 / 每周三 15:00 / 每月15号 09:00”）。
     */
    private String memoRepeatLabel(LocalDateTime at, String repeatRule) {
        String repeat = MemoStore.normalizeRepeat(repeatRule);
        if (repeat == null) return memoTimeLabel(at);
        return switch (repeat) {
            case "DAILY" -> "每天 " + at.format(TIME_LABEL);
            case "WEEKLY" -> "每周" + "一二三四五六日".charAt(at.getDayOfWeek().getValue() - 1)
                    + " " + at.format(TIME_LABEL);
            default -> "每月" + at.getDayOfMonth() + "号 " + at.format(TIME_LABEL);
        };
    }

    /** 重复提醒的前缀文案：“每天 / 每周 / 每月”；一次性返回空串。 */
    private String repeatLabel(String repeatRule) {
        String repeat = MemoStore.normalizeRepeat(repeatRule);
        if (repeat == null) return "";
        return switch (repeat) {
            case "DAILY" -> "每天";
            case "WEEKLY" -> "每周";
            default -> "每月";
        };
    }

    /** 记完备忘/健康数值后把话头交回原来的办理上下文，不打断复诊办理。 */
    private AgentTurnResponse memoHandoff(ConversationState state, String note) {
        if (state.appointmentId != null) {
            return respondWithPlan(state, note, bookedActions(state));
        }
        if (state.stage == ConversationState.Stage.READY_TO_PLAN) {
            return respondWithPlan(state, note + " 可以继续办理复诊。",
                    List.of(q("开始办理", "START_PLAN", ""), q("取消整个办理", "CANCEL_TASK", "")));
        }
        // 停留在“家属/志愿者代约”开场时：备忘记完交回代约入口（查看/改期/求助），而不是反问去哪家医院
        if (state.managedMode) {
            AppointmentRecordStore.AppointmentView plan = upcomingArranged(state).orElse(null);
            if (plan != null) {
                return respondSimple(state, note + " 这次由" + plan.arrangedLabel() + "代约的复诊（"
                                + managedShort(plan) + "）仍在，需要查看、临时改期或取消时告诉我即可。",
                        List.of(q("查看这次安排", "VIEW_MANAGED", ""),
                                q("临时改期或取消", "MANAGE_MANAGED", ""),
                                q("途中需要帮助", "REQUEST_HELP", ""),
                                q("真紧急，需要帮助", "EMERGENCY_NOW", ""),
                                q("我另外想预约复诊", "CONTINUE", "")));
            }
            state.managedMode = false;
        }
        return askHospital(state, note + " 我们继续办理复诊，请问您想去哪家医院？");
    }

    private String memoTimeLabel(LocalDateTime at) {
        return at.format(MEMO_LABEL);
    }

    /** 实测数值：记录或回查。两条路都不落到预约链路（“我的血压是100”不是要办复诊）。 */
    private AgentTurnResponse healthRecordReply(ConversationState state, HealthRecordParser.RecordIntent intent,
                                                String raw) {
        if (intent.kind() == HealthRecordParser.Kind.QUERY) return healthRecordQuery(state, intent.item());
        // 血压 800、体温 60 这种量不出来的数：先问一句是重测还是照记。既不静默丢（老人报了数却
        // 什么都没发生，还会顺着链路被问“去哪家医院”），也不闷头记成一条不可能的数据。
        if (intent.needsConfirm()) return askRecordConfirm(state, intent);
        return recordValue(state, intent, raw);
    }

    /**
     * “把这个月的血压发给女儿”：汇总一段记录发给主联系人。
     *
     * <p>不走确认卡。老人说的是“发给…”，跟页面上按下那个按钮一样是明说的，不是助手替他拿的主意；
     * 但发出去撤不回来，所以回复里把发过去的那段原样念一遍，听错了当场就能发现。
     *
     * <p>老人没说时间跨度时按最近一周算，并且**把这句话说出来**——不声不响替他挑一个
     * 才是问题，说出来了就是一句话能改的事。
     */
    private AgentTurnResponse healthReportReply(ConversationState state, HealthReportParser.ReportIntent intent) {
        HealthReportService.SendResult result = healthReportService.send(state.id, state.userId,
                intent.window(), intent.item(), "健康记录");
        if (!result.sent()) {
            return respond(state, result.reason(), List.of(q("继续办理复诊", "CONTINUE", "")));
        }
        String what = intent.item() == null ? "健康记录" : intent.item() + "记录";
        String assumption = intent.explicit() ? ""
                : "您没说发多长时间的，我按最近一周算的，要改就说“把这个月的发给她”。";
        return respond(state, "好的，已把" + what + "发给" + result.contactLabel() + "。" + assumption
                        + "发过去的内容是：" + result.message(),
                List.of(q("继续办理复诊", "CONTINUE", "")));
    }

    /** 真往库里写一条实测值；回读记下了什么，老人才能发现听错了数。 */
    private AgentTurnResponse recordValue(ConversationState state, HealthRecordParser.RecordIntent intent,
                                          String raw) {
        LocalDateTime at = MemoParser.nowInDemoZone();
        HealthRecordStore.RecordView created = callTool(state, "healthRecord.create",
                Map.of("item", intent.item(), "value", intent.valueText(), "unit", intent.unit()),
                () -> healthRecordTool.create(state.id, state.userId, intent.item(), intent.valueNum(),
                        intent.valueText(), intent.unit(), raw, at));
        return memoHandoff(state, "好的，已记下：" + created.recordedAt().format(MEMO_LABEL) + " "
                + created.item() + " " + created.valueText() + " " + created.unit()
                + "。以后想回看，问我“我最近" + created.item() + "多少”就行，首页“健康记录”里也留着。");
    }

    /**
     * 数值看起来不对时先反问，暂存这一条等老人表态。
     * 这里只判断“量不出这个数”，不判断“这个数好不好”——后者是医学判断，助手不做。
     */
    private AgentTurnResponse askRecordConfirm(ConversationState state, HealthRecordParser.RecordIntent intent) {
        state.pendingRecordItem = intent.item();
        state.pendingRecordValueNum = intent.valueNum();
        state.pendingRecordValueText = intent.valueText();
        state.pendingRecordUnit = intent.unit();
        // 反问前正在办的流程（复诊办理、改期草稿）要记下来，答完还回去
        if (!"RECORD_CONFIRM".equals(state.pendingAction)) state.recordReturnAction = state.pendingAction;
        state.pendingAction = "RECORD_CONFIRM";
        String value = intent.valueText() + " " + intent.unit();
        if (intent.issue() == HealthRecordParser.Issue.SWAPPED) {
            return respond(state, "您说的是「" + intent.item() + " " + value + "」，这两个数是不是说反了？"
                            + "血压的前一个数要比后一个大。您重新说一遍，还是就按 " + intent.valueText() + " 记下来？",
                    recordConfirmReplies());
        }
        return respond(state, "您说的是「" + intent.item() + " " + value + "」，这个数好像不太对："
                        + intent.item() + "一般量不出这个数来，可能是听错了或者看错了。"
                        + "您重新量一个，还是就按 " + intent.valueText() + " 记下来？",
                recordConfirmReplies());
    }

    /** 老人对“这个数不太对”的回答。 */
    private AgentTurnResponse applyRecordConfirm(ConversationState state, String value) {
        String item = state.pendingRecordItem;
        if (item == null) {           // 状态不完整：清掉，别把老人卡在这个问题上
            clearPendingRecord(state);
            return null;
        }
        // 先看“不记”再看“记”：否则“别记下来”会被当成“记下来”
        if (containsAny(value, RECORD_DROP_WORDS)) {
            clearPendingRecord(state);
            return memoHandoff(state, "好的，这条数值先不记了。");
        }
        if (containsAny(value, RECORD_KEEP_WORDS)) return recordPendingValue(state);
        // 直接回一个数（“150”“5.6”）：当成重测值，不用老人再把“我的血压是”说一遍
        HealthRecordParser.RecordIntent retry = HealthRecordParser.detect(item + "是" + value);
        if (retry != null && retry.kind() == HealthRecordParser.Kind.RECORD) {
            if (retry.needsConfirm()) return askRecordConfirm(state, retry);   // 换了个数还是量不出来
            clearPendingRecord(state);
            return recordValue(state, retry, value);
        }
        if (containsAny(value, RECORD_RETRY_WORDS)) return askRetryRecord(state, item);
        // 短促的应声（“好的”“嗯”）不是别的意思，再问一遍，别当成换了话题
        if (value.length() <= 4 && value.chars().noneMatch(Character::isDigit)) {
            return respond(state, "您是重新量一个，还是就按 " + state.pendingRecordValueText + " 记下来？",
                    recordConfirmReplies());
        }
        // 老人改说了别的事：暂存这条作废，绝不偷偷记进去，让这句话照常走下面的链路
        clearPendingRecord(state);
        return null;
    }

    /** 老人说“照记”：就按他说的数写进去，他的数据他做主。 */
    private AgentTurnResponse recordPendingValue(ConversationState state) {
        HealthRecordParser.RecordIntent intent = new HealthRecordParser.RecordIntent(
                HealthRecordParser.Kind.RECORD, state.pendingRecordItem, state.pendingRecordValueNum,
                state.pendingRecordValueText, state.pendingRecordUnit, null);
        String raw = "老人确认按原数记录：" + intent.item() + " " + intent.valueText() + " " + intent.unit();
        clearPendingRecord(state);
        return recordValue(state, intent, raw);
    }

    /** 老人要重测：暂存的这条留着，他接下来直接说个数就能对上项目。 */
    private AgentTurnResponse askRetryRecord(ConversationState state, String item) {
        return respond(state, "好，您重新量一下，量好直接把数告诉我就行，比如说“" + item + "是120”。",
                recordConfirmReplies());
    }

    private void clearPendingRecord(ConversationState state) {
        state.pendingRecordItem = null;
        state.pendingRecordValueNum = null;
        state.pendingRecordValueText = null;
        state.pendingRecordUnit = null;
        state.pendingAction = state.recordReturnAction == null ? "CREATE" : state.recordReturnAction;
        state.recordReturnAction = null;
    }

    private List<QuickReply> recordConfirmReplies() {
        return List.of(q("重新说一个", "RECORD_RETRY", ""), q("就按这个记下来", "RECORD_KEEP", ""));
    }

    /** 回查最近的实测数值；一条都没有时教老人怎么上报。 */
    private AgentTurnResponse healthRecordQuery(ConversationState state, String item) {
        List<HealthRecordStore.RecordView> rows = callTool(state, "healthRecord.query",
                Map.of("item", item == null ? "全部" : item, "limit", HEALTH_QUERY_LIMIT),
                () -> healthRecordTool.recent(state.id, state.userId, item, HEALTH_QUERY_LIMIT));
        String what = item == null ? "健康数值" : item;
        List<QuickReply> replies = List.of(q("继续办理复诊", "CONTINUE", ""));
        if (rows.isEmpty()) {
            return respond(state, "还没有" + what + "的记录。量完直接告诉我就行，比如说“我的"
                    + (item == null ? "血压是100" : item + "是100") + "”，我会帮您记下来。", replies);
        }
        StringBuilder text = new StringBuilder("您最近的" + what + "记录：");
        for (int index = 0; index < rows.size(); index++) {
            HealthRecordStore.RecordView row = rows.get(index);
            if (index > 0) text.append("；");
            text.append(row.recordedAt().format(MEMO_LABEL)).append(" ");
            if (item == null) text.append(row.item()).append(" ");
            text.append(row.valueText()).append(" ").append(row.unit());
        }
        return respond(state, text.append("。").toString(), replies);
    }

    /** 老人把“代约计划”改到新时间：先套用原安排进入改期草稿，确认后才变更原预约。 */
    private AgentTurnResponse beginManagedReschedule(ConversationState state) {
        AppointmentRecordStore.AppointmentView plan = upcomingArranged(state).orElse(null);
        if (plan == null) {
            return respondSimple(state, "没有找到可改期的安排，可能已改期或取消。",
                    List.of(q("查看事项", "OPEN_TASKS", "")));
        }
        CareCatalogRepository.Hospital hospital = catalog.hospital(plan.hospital()).orElse(null);
        CareCatalogRepository.Department department = hospital == null
                ? null : catalog.department(hospital.id(), plan.department()).orElse(null);
        if (hospital == null || department == null) {
            return respondSimple(state, "暂时无法自动改期，请联系人工帮助。",
                    List.of(q("联系人工帮助", "CONTACT_HUMAN", "")));
        }
        state.arrangedArrangerId = plan.arrangedBy();
        state.originalAppointmentId = plan.appointmentId();
        state.hospitalId = hospital.id();
        state.hospital = hospital.name();
        state.departmentId = department.id();
        state.department = department.name();
        state.materialReminderDone = false;
        state.departureReminderDone = false;
        state.notificationDone = false;
        state.pendingAction = "CREATE";
        state.date = null;
        state.selectedSlot = null;
        state.recommendedSlot = null;
        state.timePreference = null;
        state.requestedTime = null;
        state.alternatives = List.of();
        state.materials = List.of();
        invalidate(state);
        return askDate(state, "原来的安排（" + managedShort(plan) + "）会保留到确认新方案后才变更。请问想改到哪一天复诊？");
    }

    /** 老人改期成功后通知原安排者（kind=reschedule，消息里带新的时间）。 */
    private String arrangerRescheduleMessage(ConversationState state) {
        return "改期通知：" + elderName(state.userId) + "已将复诊改期至"
                + state.selectedSlot.date().format(DATE_LABEL) + " " + state.selectedSlot.time().format(TIME_LABEL)
                + "（" + state.hospital + " " + state.department + "）。相关提醒已按新安排更新。";
    }

    private AgentTurnResponse cancelTask(ConversationState state) {
        state.confirmationId = null;
        if (state.appointmentId != null) {
            state.pendingAction = "CANCEL";
            state.stage = ConversationState.Stage.AWAITING_CONFIRMATION;
            return respondWithCancelCard(state);
        }
        state.stage = ConversationState.Stage.CANCELLED;
        return respond(state, state.originalAppointmentId == null ? "本次办理已停止，未提交预约。" : "变更草稿已取消，原预约和提醒保留。",
                List.of(q("新建办理", "NEW_BOOKING", ""), q("查看事项", "OPEN_TASKS", "")));
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
        state.managedMode = false; // 问到“哪家医院”=已转入本人新预约，离开代约开场
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
