package com.team.silveragent.application;

import com.team.silveragent.application.care.CareBookingService;
import com.team.silveragent.application.care.CareCatalogRepository;
import com.team.silveragent.application.care.CareService;
import com.team.silveragent.application.health.HealthRecordParser;
import com.team.silveragent.application.health.HealthRecordStore;
import com.team.silveragent.application.health.HealthReportParser;
import com.team.silveragent.application.health.HealthReportService;
import com.team.silveragent.application.memo.MemoCommandParser;
import com.team.silveragent.application.memo.MemoParser;
import com.team.silveragent.application.memo.MemoStore;
import com.team.silveragent.application.longterm.MemoryStore;
import com.team.silveragent.application.profile.ProfileQueryService;
import com.team.silveragent.application.time.BusinessClock;
import com.team.silveragent.application.travel.TravelGuideService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.silveragent.agent.AgentContext;
import com.team.silveragent.agent.AgentRole;
import com.team.silveragent.agent.AnswerGenerator;
import com.team.silveragent.agent.ExtractedFacts;
import com.team.silveragent.agent.ReplyContext;
import com.team.silveragent.agent.planning.HospitalRecommendation;
import com.team.silveragent.agent.planning.PlannerActionType;
import com.team.silveragent.agent.planning.PlannerToolCall;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.model.AgentTurnResponse.ConfirmationCard;
import com.team.silveragent.domain.model.AgentTurnResponse.PlanCard;
import com.team.silveragent.domain.model.AgentTurnResponse.QuickReply;
import com.team.silveragent.domain.model.AgentTurnResponse.ResultCard;
import com.team.silveragent.domain.model.AgentTurnResponse.TaskProgress;
import com.team.silveragent.domain.model.AgentTurnResponse.ToolTrace;
import com.team.silveragent.domain.model.AgentTurnResponse.UiDirective;
import com.team.silveragent.domain.model.ConversationHistoryResponse;
import com.team.silveragent.domain.model.ConversationSummary;
import com.team.silveragent.domain.model.ToolModels.AppointmentSummary;
import com.team.silveragent.domain.model.ToolModels.Conflict;
import com.team.silveragent.domain.model.ToolModels.Contact;
import com.team.silveragent.domain.model.ToolModels.DepartmentProfile;
import com.team.silveragent.domain.model.ToolModels.DrugKnowledge;
import com.team.silveragent.domain.model.ToolModels.HospitalProfile;
import com.team.silveragent.domain.model.ToolModels.AppointmentTravelGuide;
import com.team.silveragent.domain.model.ToolModels.FacilityGuide;
import com.team.silveragent.domain.model.ToolModels.RouteGuide;
import com.team.silveragent.domain.model.ToolModels.Slot;
import com.team.silveragent.domain.tool.AppointmentTool;
import com.team.silveragent.domain.tool.CareGuideTool;
import com.team.silveragent.domain.tool.CareGuideTool.GuideArticle;
import com.team.silveragent.domain.tool.DepartmentCatalogTool;
import com.team.silveragent.domain.tool.DrugKnowledgeTool;
import com.team.silveragent.domain.tool.FamilyNotificationTool;
import com.team.silveragent.domain.tool.HealthRecordTool;
import com.team.silveragent.domain.tool.HospitalCatalogTool;
import com.team.silveragent.domain.tool.FacilityGuideTool;
import com.team.silveragent.domain.tool.MaterialChecklistTool;
import com.team.silveragent.domain.tool.MaterialPreparationTool;
import com.team.silveragent.domain.tool.MemoTool;
import com.team.silveragent.domain.tool.MyAppointmentTool;
import com.team.silveragent.domain.tool.ScheduleTool;
import com.team.silveragent.domain.tool.TravelTool;
import com.team.silveragent.domain.tool.RouteGuideTool;
import com.team.silveragent.infrastructure.mock.ToolTraceStore;
import com.team.silveragent.service.VlService;
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
public class FollowupAgentService extends ConfirmationSupport {
    /**
     * 模型对取消对象的结构化选择；valid=false 表示工具参数不完整，绝不回退猜测用户原话。
     *
     * <p>{@code rejection} 是那份参数被契约判罚时的第一条判罚，带过来是为了说清「缺的是什么」：
     * 缺参数（{@code MISSING_INFO}）和不唯一（{@code NEEDS_CLARIFICATION}）都是让老人再选一次，
     * 但对老人说的话和系统里的下一步不是一回事。
     */
    private record CancellationSelection(boolean valid, String scope, LocalDate date, String direction,
                                         LocalTime time, String period, String position,
                                         String hospital, String department,
                                         ToolContract.Rejection rejection) {
        boolean batch() { return "ALL".equals(scope) || "DATE_RANGE".equals(scope); }
    }

    /**
     * 取消支线走完一步之后：给老人看的那份响应，加上这一步到底是什么结果。
     *
     * <p>结果类型不能靠读中文反推——「没有符合条件的预约」「我还不能确定您想取消哪些」
     * 「请从下面的真实预约里选一条」「确认卡已生成」在文字上都是几句话，在系统里却是四种不同的
     * 下一步。这里顺手把类型带出来，随工具证据一起回给模型（{@code outcomeKind}）。
     */
    private record CancellationStep(ToolOutcome.Kind kind, AgentTurnResponse response, String detail) { }
    private static final int MAX_MODEL_TOOL_ROUNDS = 3;
    /**
     * 长期记忆摘要里每一摞最多摆几条。
     *
     * <p>比 {@code MemoryStore.digest} 的 8 条松一点，因为这里分了两摞、还带来源和时间；
     * 但也只是松一点——摘要段的用途是让模型知道「有这么回事」，不是把整张表搬进上下文。
     */
    private static final int MEMORY_LIST_LIMIT = 10;
    /** 一轮最多接受几张图：再多会超过并发识别池的批次，也会让这一轮明显变慢。 */
    private static final int MAX_IMAGES_PER_TURN = 3;
    /**
     * 单张 data URL 的字符上限（约 3MB 原图）。前端已经压到长边 2048，
     * 超过这个数说明没走压缩，直接拒绝比让它撑爆内存和数据库好。
     */
    private static final int MAX_IMAGE_DATA_URL_CHARS = 4_000_000;
    /** 老人只传图、没写文字时的默认问法。 */
    private static final String DEFAULT_IMAGE_QUESTION = "请帮我看看这些图片里的药品或材料是什么、有什么要注意的。";
    /** 执行器回读同一份日期/时间格式，不能各写一份（差一个字老人看到的就是两套说法）。 */
    static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("yyyy年M月d日");
    static final DateTimeFormatter TIME_LABEL = DateTimeFormatter.ofPattern("HH:mm");
    private static final Pattern SPOKEN_DATE = Pattern.compile("(\\d{1,2})月(\\d{1,2})[日号]?");
    /** 取消筛选同时接受“9月12日”和老人常说/常输的“9.12号”。 */
    private static final Pattern CANCELLATION_DATE =
            Pattern.compile("(?<!\\d)(\\d{1,2})\\s*(?:月|[./-])\\s*(\\d{1,2})\\s*[日号]?");
    /** 相对星期说法，与 RuleFactExtractor.parseDate 同一口径：“下周三”“本周日”“星期一”。 */
    private static final Pattern RELATIVE_WEEKDAY =
            Pattern.compile("(下周|本周|这周|周|星期)[一二三四五六日天]");
    /** “2026年9月8号”：用户明确说了年份时才按完整日期查历史预约。 */
    private static final Pattern SPOKEN_FULL_DATE =
            Pattern.compile("(20\\d{2})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})");
    /** 完成播报后只回一个“要/好的”时的整句肯定句式，见 isShortAffirmative。 */
    private static final Pattern AFFIRMATIVE =
            Pattern.compile("(是|要|好|行|可以|需要|看看|看一下|听听|嗯|对)(的|了|吧|啊|呀|看|一下)*");
    private static final DateTimeFormatter MEMO_LABEL = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private static final DateTimeFormatter MEMO_DAY_ONLY = DateTimeFormatter.ofPattern("M月d日");
    /** 回答“几点”时表示“不想到点提醒、只记下”的说法，命中则存长期备忘。 */
    private static final String[] MEMO_NO_TIME = {
            "不用", "不提醒", "不需要提醒", "不设", "先不用", "只记下", "不用了", "算了", "长期", "随便"
    };
    /** 回查实测数值时最多说几条（说太多老人记不住）。 */
    private static final int HEALTH_QUERY_LIMIT = 3;
    /**
     * 一次复诊按 60 分钟圈日程，和号源档位的间隔一致（09:00 / 10:30 / 14:00 / 15:30）。
     * 真实科室时长还没有进模拟数据（`departments` 里只有 `followup_scope` 文本），
     * 所以这里是一个常量而不是查表：至少别让它继续做散在代码里的魔法数字。
     */
    private static final int APPOINTMENT_DURATION = 60;
    /**
     * 备忘清单里最多给前几条配“改/删”按钮。前端一次只显示 3 个快捷回复，
     * 所以按钮给全了反而要点很多次“查看更多选项”；正文里每条都带序号，
     * 更靠后的说“改第7条”一样能办。
     */
    private static final int MEMO_BUTTON_LIMIT = 5;
    /**
     * 只读日程查询里最多点名几条冲突。
     *
     * <p>推荐一轮只说 2—3 个选择，冲突本身只是这些选择的背景，说多了就盖过了正事；
     * 而且老人要判断的是「这个时间行不行」，不是「那天排了几件事」。
     */
    private static final int CONFLICT_DISPLAY_LIMIT = 3;
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
    private final MemoTool memoTool;
    private final HealthRecordTool healthRecordTool;
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
    private final CareBookingService careBooking;
    private final CareService careService;
    private final HealthReportService healthReportService;
    private final DrugKnowledgeTool drugKnowledgeTool;
    private final VlService vlService;
    private final TurnProgress turnProgress;
    private final MemoryStore memories;
    /** 画像与预约历史的受控只读读取；本类只负责把结果摆成话，权限与 SQL 都在它那边。 */
    private final ProfileQueryService profileQuery;
    private final ConfirmationInteractionTool confirmationInteraction;
    private final ClarificationInteractionTool clarificationInteraction;
    /** 确认凭据与待确认操作登记的唯一出处；本类只负责「什么时候问老人要凭据」。 */
    private final ConfirmationService confirmations;
    /**
     * 确认通过之后的业务执行：按签发时定下的 {@code Kind} 交给对应执行器。
     * 本类只负责「什么时候问老人要凭据」，具体写库与各自的事务都在执行器与业务工具里。
     */
    private final ConfirmationDispatcher dispatcher;
    private final BusinessClock clock;
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
            MemoTool memoTool,
            HealthRecordTool healthRecordTool,
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
            CareBookingService careBooking,
            CareService careService,
            HealthReportService healthReportService,
            DrugKnowledgeTool drugKnowledgeTool,
            VlService vlService,
            TurnProgress turnProgress,
            MemoryStore memories,
            ProfileQueryService profileQuery,
            ConfirmationInteractionTool confirmationInteraction,
            ClarificationInteractionTool clarificationInteraction,
            ConfirmationService confirmations,
            ConfirmationDispatcher dispatcher,
            BusinessClock clock,
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
        this.memoTool = memoTool;
        this.healthRecordTool = healthRecordTool;
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
        this.careBooking = careBooking;
        this.careService = careService;
        this.healthReportService = healthReportService;
        this.drugKnowledgeTool = drugKnowledgeTool;
        this.vlService = vlService;
        this.turnProgress = turnProgress;
        this.memories = memories;
        this.profileQuery = profileQuery;
        this.confirmationInteraction = confirmationInteraction;
        this.clarificationInteraction = clarificationInteraction;
        this.confirmations = confirmations;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.defaultUserId = defaultUserId;
    }

    public synchronized AgentTurnResponse start() {
        return start(defaultUserId);
    }

    public synchronized AgentTurnResponse start(String requestedUserId) {
        return start(requestedUserId, null);
    }

    /**
     * 建立一次会话。
     *
     * @param requestedUserId 本次要服务的<b>就诊人</b>：本人自办时就是说话的人，家属/志愿者办理时是被协同的长辈。
     * @param actorId         真正在操作的人；为空表示本人自办。
     *                        <p>两者不同时必须能在 care_relations 里查到关系，否则拒绝建会话。
     *                        身份一律由后端按关系表判定，前端传来的角色字段不作数。
     */
    public synchronized AgentTurnResponse start(String requestedUserId, String actorId) {
        String userId = requestedUserId == null || requestedUserId.isBlank() ? defaultUserId : requestedUserId;
        CareCatalogRepository.UserProfile user = catalog.user(userId)
                .orElseThrow(() -> new IllegalArgumentException("没有找到当前用户，请检查模拟用户数据"));
        String id = UUID.randomUUID().toString();
        ConversationState state = new ConversationState(id, user.id());

        String actor = actorId == null || actorId.isBlank() ? user.id() : actorId.trim();
        if (!actor.equals(user.id())) {
            // 代他人办理：这是唯一一处把“别人”的身份带进会话的地方，所以校验必须在这里做死。
            // 不查关系就建会话，等于把任意长辈的预约、材料和动态开放给任何知道 id 的人。
            CareService.CareRelation relation = careService.relation(actor, user.id())
                    .orElseThrow(() -> new IllegalArgumentException("没有权限查看这位就诊人的信息"));
            AgentRole role = AgentRole.fromRelationRole(relation.role());
            if (role == null || !role.isCaregiver()) {
                throw new IllegalArgumentException("没有权限查看这位就诊人的信息");
            }
            CareCatalogRepository.UserProfile actorUser = catalog.user(actor)
                    .orElseThrow(() -> new IllegalArgumentException("没有找到当前操作者账号"));
            state.actorUserId = actor;
            state.actorRole = role;
            state.relationLabel = relation.relationship() == null || relation.relationship().isBlank()
                    ? (role == AgentRole.VOLUNTEER ? "社区志愿者" : "家属")
                    : relation.relationship();
            sessions.put(id, state);
            return caregiverGreeting(state, actorUser.name(), user.name());
        }

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
        return new ConversationHistoryResponse(conversationId, state.status.name(), state.stage.name(),
                conversations.messages(conversationId), current);
    }

    /**
     * 自由语言入口：每一轮都会携带当前状态和最近对话调用语言理解模型。
     */
    public synchronized AgentTurnResponse chat(String conversationId, String message) {
        try { return chatInternal(conversationId, message); }
        catch (RuntimeException error) { return toolError(requireSession(conversationId), error); }
    }

    /**
     * 只读地看一眼这轮办到哪了。不落库、不改状态，评审页面每几百毫秒调一次，
     * 所以这里绝不能有副作用——真正的调用记录另由 {@code tool_call_logs} 保存。
     */
    public TurnProgress.Snapshot progressSnapshot(String conversationId, long afterSeq) {
        return turnProgress.snapshot(conversationId, afterSeq);
    }

    /**
     * 历史记录：这个人最近聊过的会话，新的在前。
     *
     * <p>只按就诊人查，不带操作者身份——家属代办的会话同样记在被服务的长辈名下，
     * 所以长辈在自己手机上看得到「女儿帮我约的那次」，这是想要的结果。
     */
    public List<ConversationSummary> conversations(String requestedUserId, int limit) {
        String userId = requestedUserId == null || requestedUserId.isBlank() ? defaultUserId : requestedUserId;
        return conversations.list(userId, limit <= 0 ? 20 : limit);
    }

    /**
     * 助手记住的关于这位老人的事。给「我的」页面看的——记了什么必须能看见，
     * 看不见的记忆就是黑箱，老人没有理由信任它。
     */
    public List<MemoryStore.Memory> memories(String requestedUserId) {
        return memories.list(resolveUserId(requestedUserId));
    }

    /** 忘掉一条。用户自己按的按钮，直接生效，不需要再确认一遍——「忘掉」本来就是他的意思。 */
    public boolean forgetMemory(String requestedUserId, String key) {
        return key != null && !key.isBlank() && memories.forget(resolveUserId(requestedUserId), key);
    }

    private String resolveUserId(String requested) {
        return requested == null || requested.isBlank() ? defaultUserId : requested;
    }

    /**
     * 结束一段对话，用于「新对话」。结束后这个会话只读：还能翻看，但不再接受
     * 新的办理和确认（见 {@link #closedResponse}）。
     *
     * <p>幂等：对已经结束的会话再调一次不会报错，也不会改动任何东西。
     */
    public synchronized void closeConversation(String conversationId) {
        ConversationState state = requireSession(conversationId);
        if (closed(state)) return;
        state.status = ConversationState.Status.CLOSED;
        // 先把内存里的状态改掉再落库，这样紧跟其后的 /actions、/confirmations
        // 即使命中了缓存的同一个对象，也一样会被拦下来。
        conversations.close(state.id);
    }

    /**
     * 一轮真实办理的开始与结束。进度打点必须包在最外层：识图会把结论交给 {@link #chatInternalBody}
     * 继续走同一条主链路，无论中途从哪个分支返回，收尾都要执行一次，否则前端会一直以为「还在处理」。
     */
    private AgentTurnResponse chatInternal(String conversationId, String message) {
        ConversationState state = requireSession(conversationId);
        if (closed(state)) return closedResponse(state);
        reactivateIfExpired(state);
        turnProgress.begin(state.id);
        try {
            return chatInternalBody(state, conversationId, message);
        } finally {
            turnProgress.end(state.id);
        }
    }

    private AgentTurnResponse chatInternalBody(ConversationState state, String conversationId, String message) {
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
        // 暂停态的统一出口在下面 route 算出来之后：判断要按“本轮落在哪条路由”来，
        // 紧急暂停期间问“到医院怎么走”仍要回带 120 的安全提示，而页面跳转一律不给。
        // 这里不能提前无条件 return，否则安全提示里就只剩“旧操作不会继续执行”，
        // 反倒把最要紧的求助信息盖掉了。
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
                clock.today(), conversations.recentMessages(state.id), identityOf(state),
                conversations.recentVision(state.id));
        // 「把图上的字念一遍」不走模型：这种问题只要求逐字照抄，让语言模型过一手反而可能
        // 把规格、文号、日期改写掉。识别结果本身就是权威的，直接念。
        AgentTurnResponse readAloud = readVisionAloud(state, context, value);
        if (readAloud != null) {
            conversations.addMessage(state.id, "user", value);
            return readAloud;
        }
        AgentRuntime.Outcome outcome = agentRuntime.plan(value, context, state);
        ExtractedFacts facts = outcome.facts();
        conversations.addMessage(state.id, "user", value);
        SafetyGuard.Decision safety = outcome.modelDriven()
                ? safetyGuard.evaluateModel(value, facts) : safetyGuard.evaluate(value, facts);
        if (safety == SafetyGuard.Decision.EMERGENCY) return emergency(state);
        if (safety == SafetyGuard.Decision.MEDICAL_BOUNDARY) return medicalBoundary(state);
        // 模型不可用的回退模式：运行旧的 Java 关键词路由（健康备忘、健康记录、目录查询与推荐）。
        // 与上面的医疗安全预检同属回退链路；模型可用时这些语义统一交给主模型判断。
        //
        // 模型给了话但这句话不能用时（outcome.javaFallback，例如它自称「已经记下了」而一条都没写）
        // 也走这里：那不是「模型没上线」，是「模型这一轮办不了这件事」。这两者的兜底是同一条链路，
        // 所以判据放在一起，免得两个分支各写一半、又成了两套口径。
        if (!agentRuntime.modelAvailable() || outcome.javaFallback()) {
            if ("EMERGENCY".equals(facts.intent()) || containsAny(value, "胸痛", "呼吸困难", "昏迷", "大出血", "喘不上气")) {
                return emergency(state);
            }
            // 代他人办理时“提醒长辈”是独立的一件事，要排在老人那套备忘识别之前，
            // 否则“提醒我妈带身份证”会被当成操作者自己要记一条备忘，写到他自己名下。
            if (state.caregiving() && value.contains("提醒")) {
                return remindElder(state, value);
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
            if (report != null && dailyHealthContext(state)) {
                return healthReportReply(state, report);
            }
            // 实测数值（“我的血压是100”“我最近血压多少”）：备忘是“要做的事”，这是“已经量到的数”，分家存
            HealthRecordParser.RecordIntent record = HealthRecordParser.detect(value);
            if (record != null && (record.kind() == HealthRecordParser.Kind.QUERY || dailyHealthContext(state)
                    || replacesPendingHealthRecord(state, record))) {
                return healthRecordReply(state, record, value);
            }
            // 医疗越界不在这里再判一次：函数开头的 safetyGuard.precheck 已经按
            // MedicalBoundaryRules 拦掉了，走到这里的一定不是越界句。
            // 这里只保留本分支新加的语义（备忘、健康记录、周报）。
            // 「取消本次办理 / 取消预约 / 已保留预约 / 查医院 / 查科室 / 求推荐」不在这份名单里：
            // 它们都已经由下面的规则规划器翻成 AgentOrchestrator.Route，再走 state 机处理，
            // 语义比这里按关键词直接给卡片更细（例如“取消预约”要先问清楚取消哪一次，
            // 只有一次预约时才直接出确认卡）。在这里按 intent 抢先 return 会把那段流程整个盖掉。
        }
        AgentOrchestrator.Route route = outcome.route();
        CancellationSelection structuredCancellation = modelCancellationSelection(outcome);
        if (outcome.modelDriven()) prepareModelIntentState(state, outcome.intent());
        if (stopped(state) && !allowedWhenStopped(route)) return stoppedResponse(state);
        // 模型模式下的备忘 / 健康数值 / 发周报：模型只认出“这句话属于这三件事”，
        // 具体时间、项目、数值仍由 Java 的解析器填槽，和回退模式走的是同一批方法。
        // 放在预约流程之前，否则“明早八点提醒我吃药”会被当成一个没有医院和科室的预约草稿。
        if (outcome.modelDriven()) {
            AgentTurnResponse daily = modelDailyRoute(route, state, value);
            if (daily != null) return daily;
        }
        AgentTurnResponse entityConfirmation = handlePendingEntityConfirmation(state, value, facts);
        if (entityConfirmation != null) return entityConfirmation;
        // 模型这一轮给出的是<b>合法</b>工具调用时，下面的关键词兜底整段不跑：它的意图已经由调用本身
        // 说清楚了，不能再用原句里的「都取消」「最近」把范围改回来。判断依据是「参数过了契约校验」，
        // 不是「动作类型长得像确认」——模型调一个只读工具（比如 appointment.queryMine）同样算数。
        if ("CANCEL_EXISTING_APPOINTMENT".equals(state.sideTask) && !outcome.hasContractCheckedToolCall()) {
            // “都取消 / 9月12日前的都取消”是在承接上一轮预约列表，不应重新掉回意图识别，
            // 也不能被单条候选解析抢先消费；这里只生成整组确认卡，仍不直接写库。
            if (state.stage != ConversationState.Stage.AWAITING_CONFIRMATION
                    && !state.caregiving() && isBatchCancellationRequest(value)) {
                return beginCancelExistingAppointment(state, facts, value,
                        outcome.modelDriven() ? outcome.replyDraft() : null, null);
            }
            AgentTurnResponse selection = resolveCancellationCandidate(state, value);
            if (selection != null) return selection;
            // “之前那个”只有指代、没有日期或时刻，不能把“之前”误当成整组范围。
            // 既然无法唯一定位，就重新展示数据库候选，让用户明确点选一条。
            if (state.stage != ConversationState.Stage.AWAITING_CONFIRMATION
                    && mentionsAppointmentReference(value)) {
                return beginCancelExistingAppointment(state, ExtractedFacts.empty(), "取消预约", null, null);
            }
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
        if (agentRuntime.isModelReadToolOutcome(outcome) || isStructuredRecommendation(outcome)) {
            return runModelToolLoop(state, value, outcome);
        }
        return dispatchOutcome(state, value, outcome);
    }

    /**
     * 图片走对话：拍完照或选完图之后，走的是和文字<b>完全同一条</b>主链路。
     *
     * <p>识别结论作为本轮上下文交给同一个主模型，安全预检、规划、只读工具循环、
     * 完成态装配全部照常 —— 图片只是换了一种「用户说了什么」的输入形式，
     * 不新开一条绕过确认门禁的旁路。本方法不会触碰 confirmationId，也不会调用 act/confirm。
     */
    public synchronized AgentTurnResponse handleImages(String conversationId, List<String> imageDataUrls,
                                                       String hint) {
        try { return handleImagesInternal(conversationId, imageDataUrls, hint); }
        catch (RuntimeException error) { return toolError(requireSession(conversationId), error); }
    }

    private AgentTurnResponse handleImagesInternal(String conversationId, List<String> imageDataUrls, String hint) {
        ConversationState state = requireSession(conversationId);
        // 结束检查要在存附件之前：往一个已经关掉的会话里写图片，等于给只读的东西留了写入路径。
        if (closed(state)) return closedResponse(state);
        reactivateIfExpired(state);
        // 识图这一轮多数时间花在视觉模型上，而且「视觉关掉 / 看不清」会提前返回。
        // 所以进度也要从这一层就开始记，否则老人传完图只会看到一片安静。
        turnProgress.begin(state.id);
        try {
            return handleImagesBody(state, imageDataUrls, hint);
        } finally {
            turnProgress.end(state.id);
        }
    }

    private AgentTurnResponse handleImagesBody(ConversationState state, List<String> imageDataUrls, String hint) {
        String note = hint == null ? "" : hint.trim();
        List<QuickReply> fallbackReplies = List.of(
                q("继续办理复诊", "CONTINUE", ""), q("咨询人工", "CONTACT_HUMAN", ""));

        List<String> images = sanitizeImages(imageDataUrls);
        if (images.isEmpty()) {
            return respond(state, "我没有收到图片，请重新拍一张或从相册里选一张。", fallbackReplies);
        }
        for (String image : images) {
            if (image.length() > MAX_IMAGE_DATA_URL_CHARS) {
                return respond(state, "这张图太大了，我没法处理。请重新拍一张，"
                        + "或者把手机相机的分辨率调低一点再拍。", fallbackReplies);
            }
        }

        // 先落库再识别：识别可能失败或超时，但「老人确实传过这张图」这件事必须留下痕迹。
        List<Long> attachmentIds = new ArrayList<>(images.size());
        for (String image : images) {
            attachmentIds.add(conversations.addAttachment(state.id, null, "IMAGE", image));
        }
        conversations.addMessage(state.id, "user",
                note.isEmpty() ? "[图片]" : "[图片] " + note, "IMAGE", attachmentIds.get(0));

        // 没配视觉模型时第一步就返回。这是「没有百炼 key 也能把整个 Demo 走完」的关键分支：
        // 必须在任何模型调用之前，否则老人要等一次注定失败的请求才知道看不了图。
        if (!vlService.isEnabled()) {
            // 这句话必须原样说出去：它是在教老人「换个办法」，绝不能被回答模型改写掉。
            // 走 respondWithoutModel 还有个好处——主模型开着、只有视觉关掉时（配了别的厂商的 key），
            // 也不必为一句固定说明白等一次模型调用。
            return respondWithoutModel(state, "图片识别功能暂时没有开启，我还没法帮您看这张图。"
                    + "您可以先用文字告诉我这是什么，或者继续办理复诊。", fallbackReplies);
        }

        List<VlService.VisionResult> results = vlService.recognizeAll(images, note);
        StringBuilder description = new StringBuilder();
        int recognized = 0;
        for (int i = 0; i < results.size(); i++) {
            VlService.VisionResult result = results.get(i);
            if (result == null || result.isBlank()) continue;
            recognized++;
            conversations.saveVisionResult(attachmentIds.get(i), state.id, note,
                    joinText(result.description(), result.keyFacts()), result.ocr());
            if (description.length() > 0) description.append('\n');
            description.append(nullToEmpty(result.description()));
        }
        traces.record(state.id, "vision.recognize",
                Map.of("images", images.size(), "hint", note),
                Map.of("recognized", recognized), recognized > 0);

        if (recognized == 0) {
            return respond(state, "这张图我看不太清楚，没法确认上面写的是什么。"
                    + "麻烦靠近一点、对着光线再拍一张，或者直接用文字告诉我。", fallbackReplies);
        }

        // 视觉可用但主模型不可用：只如实复述识别结论，绝不代替模型编造解读。
        if (!agentRuntime.modelAvailable()) {
            return respondWithoutModel(state, description.toString().trim(), fallbackReplies);
        }
        // 交给同一个主模型。识别结论已经作为 vision 上下文挂在会话上，
        // 所以这里只需要把「用户想问什么」递进去。
        return chatInternal(state.id, note.isEmpty() ? DEFAULT_IMAGE_QUESTION : note);
    }

    /**
     * 「把图上的字念一遍」不走模型：这种问题只要求逐字照抄，
     * 让语言模型过一手反而可能把规格、批准文号、日期改写掉。识别结果本身就是权威的。
     *
     * <p>以「本会话有识图记录」为门，纯文字会话完全不受影响；
     * 有待确认的重要操作时也不抢，先让老人把确认卡处理完。
     */
    private AgentTurnResponse readVisionAloud(ConversationState state, AgentContext context, String value) {
        if (!context.hasVision() || value == null || value.isBlank()) return null;
        if (state.confirmationId != null || stopped(state)) return null;
        if (!containsAny(value, "念一遍", "念一下", "念给我听", "读一遍", "读一下",
                "全部念", "都念出来", "原样念", "上面写了什么", "上面写的是什么", "批准文号")) {
            return null;
        }
        String ocr = context.latestVisionOcr();
        String text = ocr.isBlank() ? context.visionSummary() : ocr;
        if (text.isBlank()) return null;
        return respondWithoutModel(state, text,
                List.of(q("继续办理复诊", "CONTINUE", ""), q("咨询人工", "CONTACT_HUMAN", "")));
    }

    /**
     * 同一张图重复上传时只识别一次：指纹取逗号之后的 base64 正文，
     * 前面的 {@code data:image/jpeg;base64,} 前缀不参与比较。
     */
    private List<String> sanitizeImages(List<String> imageDataUrls) {
        if (imageDataUrls == null || imageDataUrls.isEmpty()) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        List<String> images = new ArrayList<>();
        for (String raw : imageDataUrls) {
            if (raw == null || raw.isBlank()) continue;
            String value = raw.trim();
            int comma = value.indexOf(',');
            String fingerprint = comma >= 0 ? value.substring(comma + 1) : value;
            if (!seen.add(fingerprint)) continue;
            images.add(value);
            if (images.size() >= MAX_IMAGES_PER_TURN) break;
        }
        return images;
    }

    private static String joinText(String first, String second) {
        String a = nullToEmpty(first);
        String b = nullToEmpty(second);
        if (a.isEmpty()) return b;
        if (b.isEmpty() || b.equals(a)) return a;
        return a + "\n" + b;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
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
            case CANCEL_EXISTING_APPOINTMENT -> beginCancelExistingAppointment(
                    state, facts, value, outcome.modelDriven() ? outcome.replyDraft() : null,
                    modelCancellationSelection(outcome));
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
            case RECOMMEND_HOSPITAL -> recommendHospitals(state, facts, outcome.modelDriven());
            case QUERY_AVAILABLE_SLOTS -> querySlotsFor(state, facts, outcome);
            case QUERY_NEARBY_SLOTS -> nearbySlotsFor(state, outcome);
            case CHECK_CONFLICT -> checkSchedule(state, outcome);
            case CHECK_DUPLICATE -> checkDuplicate(state);
            case ASK_MATERIALS -> showMaterials(state, facts);
            case QUERY_TRAVEL_GUIDE -> travelGuideFor(state, facts, value, outcome);
            case QUERY_LOCATION_GUIDE -> showLocationGuide(state, facts, value, true);
            case CHANGE_HOSPITAL -> changeHospital(state, facts, value);
            case CHANGE_DEPARTMENT -> changeDepartment(state, facts, value);
            case CHANGE_DATE -> changeDate(state, facts);
            case CHANGE_TIME -> changeTime(state, facts);
            // 模型说是备忘/数值/发周报，但 Java 的解析器没认出来（上下文不对，或者话里没有可用的时间、
            // 项目或数值）。这里绝不复用模型自己的 replyDraft——那正是它会说“我给您记一个提醒”
            // 而其实什么都没写的地方。改成一个中性的追问，让老人自己说清楚。
            case MANAGE_MEMO, RECORD_HEALTH_VALUE, SEND_HEALTH_REPORT ->
                    respond(state, "这句话我还没听准。您是想记一条提醒、看看以前记过的数值，还是把它发给家里人？可以再说一遍。",
                            List.of(q("记一条提醒", "CONTINUE", ""), q("继续办理复诊", "CONTINUE", ""),
                                    q("咨询人工", "CONTACT_HUMAN", "")));
            case QUERY_DRUG_KNOWLEDGE -> showDrugKnowledge(state, outcome);
            case QUERY_CARE_TIMELINE -> showCareTimeline(state);
            case QUERY_CARE_NOTIFICATIONS -> showCareNotifications(state);
            case QUERY_APPOINTMENT_HISTORY -> showAppointmentHistory(state, outcome);
            case QUERY_PROFILE_MEMORY -> showProfileMemory(state);
            case REMIND_ELDER -> remindElder(state, value);
            // 模型点名了一个执行不了的工具，intent 也归不到任何业务链路。绝不复用它的 replyDraft，
            // 也不走 DIRECT_ANSWER（那条路会暂停正在办理的任务）：由 Java 明确回绝，任务状态原地不动。
            case ASK_CLARIFICATION -> askClarification(state, outcome);
            case REFUSE_UNSUPPORTED_TOOL -> respondWithoutModel(state,
                    "抱歉，这个操作我没办法直接执行。您可以换一种说法，或者让我先帮您查一下已确认的预约。",
                    modelSuggestedReplies(state));
            case CURRENT_FLOW -> outcome.modelDriven()
                    ? modelWorkflowReply(state, facts, outcome.replyDraft(), false)
                    : continueCurrentFlow(state, facts);
        };
    }

    /**
     * 澄清交互的出口：模型问的那句话 + Java 从真实工具取回的候选。
     *
     * <p><b>澄清不是确认</b>：这里不建卡、不发 {@code confirmationId}、不改任务状态，只把问题和一个
     * 能干下去的分支摆出来。凭据是全部写操作的唯一钥匙，澄清碰不到它，所以「模型问了一句」
     * 变不出任何执行授权——老人下一步仍然要在真实候选里选一条，再由 Java 生成确认卡。
     */
    private AgentTurnResponse askClarification(ConversationState state, AgentRuntime.Outcome outcome) {
        String question = outcome.proposedTools() == null || outcome.proposedTools().isEmpty()
                ? null : outcome.proposedTools().get(0).arguments().get("question");
        String candidateTool = outcome.proposedTools() == null || outcome.proposedTools().isEmpty()
                ? "appointment.queryMine"
                : outcome.proposedTools().get(0).arguments()
                        .getOrDefault("candidateTool", "appointment.queryMine");
        // 候选永远来自真实工具。枚举目前只有一条取值，将来扩别的对象（比如备忘、材料）时在这里加分支，
        // 加不到的就是空候选，落成「没查到」而不是拿模型的话顶上。
        List<AppointmentSummary> candidates = switch (candidateTool) {
            case "appointment.queryMine" -> myAppointmentTool.search(state.id, state.userId, null, null, null);
            default -> List.of();
        };
        // 承接取消支线：老人接下来无论是点按钮还是直接说“9月10日那条”，都还在同一件事上。
        rememberInterruptedTask(state, "CANCEL_EXISTING_APPOINTMENT");
        ClarificationInteractionTool.Prompt prompt = clarificationInteraction.askWhichAppointmentToCancel(
                state.id, candidates, question, "您想取消的是哪一条预约？可以从下面的真实预约里直接选一条。");
        return respondWithoutModel(state, prompt.reply(), prompt.choices());
    }

    /**
     * 模型主导的有界只读工具循环。现有异常处理方法仍负责查询真实数据、更新权威草稿并
     * 生成合法按钮；中间结果不会直接发给用户，而是作为结构化证据回到同一个主模型。
     */
    private AgentTurnResponse runModelToolLoop(ConversationState state, String originalMessage,
                                               AgentRuntime.Outcome initial) {
        AgentRuntime.Outcome current = initial;
        AgentTurnResponse latestToolResponse = null;
        ToolOutcome.Step latestOutcome = null;
        Set<String> executed = new LinkedHashSet<>();
        // 老人这一轮明确排除的医院、以及他说定的科室，是本轮的读条件，不是草稿字段。
        // 模型很可能先查历史或画像、下一轮才给推荐，而那一轮未必把排除项再说一遍——
        // 所以约束由整个循环带着走，不能只看最后那一轮的 facts。
        TurnReadConstraint constraint = TurnReadConstraint.none().merge(initial.facts());
        // 本轮证据的分界线：进循环前记下最大追踪 id，校验推荐理由时只认它之后的真实工具调用。
        // 必须在任何一次目录查询之前取——目录工具自己也会写追踪，取晚了会把 Java 自己的查询
        // 当成「模型查过的证据」。
        long evidenceMarker = traces.latestId(state.id);
        // 结构化推荐校验不过时只给一次修正机会，且这次修正会消耗一轮（它可能先去点只读工具）。
        int recommendationRepairs = 0;

        for (int round = 1; round <= MAX_MODEL_TOOL_ROUNDS; round++) {
            if (!agentRuntime.isModelReadToolOutcome(current)) {
                if (isStructuredRecommendation(current)) {
                    RecommendationCheck check = checkRecommendations(state, current, constraint, evidenceMarker);
                    if (check.ok()) return deliverRecommendation(state, current.answering(), check);
                    // 认不准的排除、排除之后没有候选：这是 Java 的终结句，不该交给模型再改一遍。
                    if (check.stopped() != null) return check.stopped();
                    if (recommendationRepairs++ == 0) {
                        turnProgress.mark(state.id, TurnProgress.Kind.PLANNING);
                        current = agentRuntime.continueAfterTools(originalMessage, planContext(state),
                                state, check.evidence());
                        constraint = constraint.merge(current.facts());
                        AgentTurnResponse guarded = guardContinuation(state, originalMessage, current);
                        if (guarded != null) return guarded;
                        continue;
                    }
                    return recommendationUnavailable(state);
                }
                return finishToolLoopDecision(state, current, latestToolResponse);
            }

            List<PlannerToolCall> freshCalls = current.proposedTools().stream()
                    .filter(call -> executed.add(toolSignature(call)))
                    .toList();
            if (freshCalls.isEmpty()) {
                return finalizeToolEvidence(state, latestToolResponse,
                        "我已经完成了这项查询。您可以根据上面的真实结果继续选择。");
            }

            // 模型发起的号源查询（appointment.querySlots / appointment.queryNearbySlots）只查不改：
            // 参数只决定这次查什么，这一轮的 facts 也不落到草稿上，交给业务流程的是「不写草稿的那一份」。
            // 草稿只由两处改——模型明确提出业务动作（PROPOSE_WORKFLOW_ACTION）的那一轮，
            // 以及完全不经过模型的既有办理流程。（其余只读工具尚未逐个按同一口径核对，
            // 比如 hospital.search 仍可能更新会话里的解析状态，所以这里不宣称所有 READ_ONLY 工具都已收口。）
            AgentRuntime.Outcome executable = withToolCalls(withoutDraftWrites(current), freshCalls);
            // 参数是模型这一步真实生成的，先原样记下来再执行——
            // 「参数由智能体生成」这件事只有在执行之前才看得见。
            turnProgress.toolProposed(state.id, freshCalls);
            deferFinalization.set(true);
            AgentToolStep step;
            try {
                step = dispatchToolStep(state, originalMessage, executable);
            } finally {
                deferFinalization.remove();
            }
            latestToolResponse = step.response();
            latestOutcome = step.outcome();

            // 确认卡、澄清问题、完成卡和安全暂停都是业务终点，不能为了措辞再让模型改变动作。
            // 澄清尤其不能：候选按钮是按 Java 那份回复生成的，让模型换个说法问，文字就和按钮对不上了。
            if (latestToolResponse.confirmation() != null
                    || latestOutcome.kind() == ToolOutcome.Kind.NEEDS_CLARIFICATION
                    || state.stage == ConversationState.Stage.EMERGENCY_PAUSED
                    || state.stage == ConversationState.Stage.COMPLETED
                    || state.stage == ConversationState.Stage.PARTIAL) {
                return finalizeToolEvidence(state, latestToolResponse, latestToolResponse.reply());
            }

            String evidence = toolLoopEvidence(round, freshCalls, latestToolResponse, latestOutcome);
            turnProgress.mark(state.id, TurnProgress.Kind.PLANNING);
            current = agentRuntime.continueAfterTools(originalMessage, planContext(state), state, evidence);
            // 续跑轮里模型可能会补一个新的排除目标，或者第一次说出科室：并进去，只增不减。
            // 它写错名字的代价由 Java 承担——认不准的那一轮不给推荐，绝不悄悄忽略。
            constraint = constraint.merge(current.facts());

            AgentTurnResponse guarded = guardContinuation(state, originalMessage, current);
            if (guarded != null) return guarded;
        }

        // 轮数用尽：最后再看一眼收口，别把「三轮都在查工具、最后一次才给推荐」丢掉。
        // 这里不再给修正机会——它已经是最后一轮了。
        if (isStructuredRecommendation(current)) {
            RecommendationCheck check = checkRecommendations(state, current, constraint, evidenceMarker);
            if (check.ok()) return deliverRecommendation(state, current.answering(), check);
            if (check.stopped() != null) return check.stopped();
            return recommendationUnavailable(state);
        }
        return finalizeToolEvidence(state, latestToolResponse,
                "我已经完成当前查询。为避免重复查询，请从现有结果中选择，或告诉我想修改哪项条件。");
    }

    /**
     * 进入规划模型的那一份上下文：阶段、已知事实、今天、最近消息、身份、最近的识图。
     *
     * <p>工具循环的每一轮各建一份，内容随时在变；抽出来是为了让「续跑」和「推荐修正」用的是
     * 同一份口径——修正轮也是同一轮对话里的续跑，不该看到不一样的上下文。
     */
    private AgentContext planContext(ConversationState state) {
        return new AgentContext(state.stage.name(), knownFacts(state),
                clock.today(), conversations.recentMessages(state.id), identityOf(state),
                conversations.recentVision(state.id));
    }

    /**
     * 续跑结果的安全预检与意图准备。返回非空表示这一轮到此为止（急症 / 医疗边界），
     * 返回 null 表示照常往下走。
     *
     * <p>工具循环的常规续跑和推荐校验失败后的那一次修正共用它：修正轮也是模型的一次续跑，
     * 不能因为「它是在改一份不合规的推荐」就跳过安全预检。
     */
    private AgentTurnResponse guardContinuation(ConversationState state, String originalMessage,
                                                AgentRuntime.Outcome current) {
        SafetyGuard.Decision safety = current.modelDriven()
                ? safetyGuard.evaluateModel(originalMessage, current.facts())
                : SafetyGuard.Decision.NONE;
        if (safety == SafetyGuard.Decision.EMERGENCY) return emergency(state);
        if (safety == SafetyGuard.Decision.MEDICAL_BOUNDARY) return medicalBoundary(state);
        if (current.modelDriven()) prepareModelIntentState(state, current.intent());
        return null;
    }

    // ---------------------------------------------------------------- 结构化推荐的校验与交付
    //
    // 推荐这一轮不再由 Java 重装、也不再交给回答模型润色：模型在最后一次调用里同时给出结构化
    // recommendations 和最终话语 answering，Java 只校验结构化那一半，通过了就把 answering
    // 原样交付。Java 因此不排序、不替补、不重写理由——它只回答一个问题：
    // 「这几家医院，是不是这一轮真查过、真没被排除、理由真有出处？」

    /** 这一轮是在推荐：模型给了结构化清单，或者 intent 就是它。 */
    private static boolean isStructuredRecommendation(AgentRuntime.Outcome outcome) {
        return outcome != null && outcome.modelDriven()
                && outcome.route() == AgentOrchestrator.Route.RECOMMEND_HOSPITAL;
    }

    /** 一次推荐最多给几家。超过就整轮不展示，让模型自己删——Java 不替它挑前三条。 */
    private static final int MAX_RECOMMENDATIONS = 3;

    /**
     * 模型口中的工具名 → {@code tool_call_logs} 里真实记下的工具名。
     *
     * <p>目录工具的追踪名和规划器工具名不是一套：{@code hospital.list} 落库时叫
     * {@code catalog.queryHospitals}，号源的「附近日期」查询落库时叫 {@code appointment.queryAlternatives}。
     * 不显式对上，模型引用了真实查过的证据也会被判成「没查过」。这张表是<b>工具名</b>的对应，
     * 不是中文词表。
     */
    private static final Map<String, String> TRACE_TOOL_NAMES = Map.of(
            "hospital.list", "catalog.queryHospitals",
            "hospital.search", "catalog.queryHospitals",
            "department.list", "catalog.queryDepartments",
            "department.search", "catalog.queryDepartments",
            "appointment.queryNearbySlots", "appointment.queryAlternatives",
            "material.checklist", "material.generateChecklist");

    /**
     * 这些证据「只对某一家医院成立」：号源、路线、科室清单、院内指引，查的时候就是奔着某一家去的。
     * 引它们当理由时，那一次调用的参数或结果里必须真的出现这家医院；否则「有证据」只是有证据，
     * 证明不了什么。{@code hospital.list}、{@code appointment.history}、{@code profile.memorySummary}
     * 这类是不分医院的，只要真跑过且有结果就算数。
     */
    private static final Set<String> HOSPITAL_SCOPED_TOOLS = Set.of(
            "appointment.querySlots", "appointment.queryNearbySlots", "travel.routePlan",
            "department.list", "department.search", "hospital.search", "hospital.locationGuide");

    /** 工具自己报条数时用的字段名；用来分辨「查了但一条没有」和「查到了」。 */
    private static final Set<String> TRACE_COUNT_FIELDS = Set.of("total", "returned", "count", "size");

    /**
     * 结构化推荐的校验结果。
     *
     * @param ok        这一份清单能不能直接交付
     * @param validated 过了校验的医院，<b>顺序就是模型给的顺序</b>（Java 不重排）
     * @param note      Java 得在 {@code answering} 前面说的一句理解说明（近似排除时才有）
     * @param evidence  校验不过时回给模型的结构化问题；{@code ok} 时为空
     * @param stopped   这一轮不能靠模型修正：Java 已经写好了终结回复（排除认不准 / 没有候选了）
     */
    private record RecommendationCheck(boolean ok, List<HospitalProfile> validated, String note,
                                       String evidence, AgentTurnResponse stopped) {
        static RecommendationCheck ok(List<HospitalProfile> validated, String note) {
            return new RecommendationCheck(true, validated, note, null, null);
        }

        static RecommendationCheck retry(String evidence) {
            return new RecommendationCheck(false, List.of(), "", evidence, null);
        }

        static RecommendationCheck stopped(AgentTurnResponse response) {
            return new RecommendationCheck(false, List.of(), "", null, response);
        }
    }

    /**
     * 逐条校验模型给的结构化推荐。
     *
     * <p>顺序很要紧：<b>先读证据、再算候选</b>。候选是 Java 自己查目录算出来的，那次查询也会落追踪；
     * 反过来写的话，模型只要引一句 {@code hospital.list}，Java 自己刚查的那一趟就成了它的「证据」。
     * 所以证据快照取自 {@code prepareCandidates} 之前。
     *
     * <p>校验只回答「推荐对象是不是本轮真实候选、有没有被排除、科室在不在、引用的证据是不是本轮真查过
     * 且是关于这一家的、条数够不够少、answering 里有没有夹带别家」。它<b>不</b>判断理由说得好不好、
     * 也不排序——那是模型的事。
     */
    private RecommendationCheck checkRecommendations(ConversationState state, AgentRuntime.Outcome decision,
                                                     TurnReadConstraint constraint, long evidenceMarker) {
        List<ToolTrace> evidence = traces.since(state.id, evidenceMarker);
        RecommendationPlan plan = prepareCandidates(state, constraint.facts(), true);
        if (plan.blocked() != null) return RecommendationCheck.stopped(plan.blocked());

        List<HospitalRecommendation> items = decision.recommendations();
        List<String> errors = new ArrayList<>();
        if (evidence.isEmpty()) {
            // 最要紧的一条先说：一条工具都没查就给推荐，理由不可能有出处。
            errors.add("NO_EVIDENCE_AT_ALL: 你这一轮还没有调用过任何只读工具，也就没有任何真实数据可以支撑推荐。"
                    + "请先查（例如 hospital.list、department.list、appointment.querySlots），拿到结果再给 recommendations。");
        }
        if (items.isEmpty()) {
            errors.add("NO_RECOMMENDATIONS: 这一轮没有给出 recommendations。");
        }
        if (items.size() > MAX_RECOMMENDATIONS) {
            errors.add("TOO_MANY: 一次最多推荐 " + MAX_RECOMMENDATIONS + " 家，你给了 " + items.size() + " 家，请自己删到 3 家以内。");
        }

        Map<String, HospitalProfile> candidates = new LinkedHashMap<>();
        for (HospitalProfile item : plan.kept()) candidates.put(item.id(), item);
        Map<String, HospitalProfile> catalog = new LinkedHashMap<>();
        for (HospitalProfile item : hospitalCatalogTool.listHospitals(state.id)) catalog.put(item.id(), item);

        Set<String> seen = new LinkedHashSet<>();
        List<HospitalProfile> validated = new ArrayList<>();
        for (HospitalRecommendation item : items) {
            if (item.hospitalId().isEmpty()) {
                errors.add("MISSING_HOSPITAL_ID: 有一条推荐没有填 hospitalId。");
                continue;
            }
            if (!seen.add(item.hospitalId())) {
                errors.add("DUPLICATE_HOSPITAL: " + item.hospitalId() + " 出现了不止一次。");
                continue;
            }
            HospitalProfile hospital = candidates.get(item.hospitalId());
            if (hospital == null) {
                HospitalProfile known = catalog.get(item.hospitalId());
                errors.add(plan.excludedIds().contains(item.hospitalId())
                        ? "HOSPITAL_EXCLUDED: " + describe(known, item.hospitalId())
                        + " 是老人这一轮明确排除的医院，不能再推荐。"
                        : "HOSPITAL_NOT_A_CANDIDATE: " + describe(known, item.hospitalId())
                        + " 不在本轮的真实候选里，请只从 allowedCandidates 里选。");
                continue;
            }
            if (plan.department() != null && !hasDepartment(state, hospital.id(), plan.department())) {
                errors.add("DEPARTMENT_NOT_FOUND: " + hospital.name() + " 的目录里没有“" + plan.department()
                        + "”这个科室。");
                continue;
            }
            if (item.evidenceRefs().isEmpty()) {
                errors.add("EVIDENCE_MISSING: " + hospital.name() + " 这条没有 evidenceRefs，"
                        + "推荐理由必须说明是从哪一次真实查询里来的。");
                continue;
            }
            for (String ref : item.evidenceRefs()) {
                String problem = evidenceProblem(ref, hospital, evidence);
                if (problem != null) errors.add(problem);
            }
            validated.add(hospital);
        }

        String answering = decision.answering();
        if (answering == null || answering.isBlank()) {
            errors.add("NO_ANSWERING: 这一轮没有给出 answering，老人看不到话。");
        } else {
            String smuggled = hospitalNamedIn(answering, catalog.values(), validated);
            if (smuggled != null) {
                errors.add("ANSWER_MENTIONS_HOSPITAL: answering 里提到了“" + smuggled
                        + "”，它不在这次要推荐的医院里，整句不能给老人看。");
            }
        }

        if (!errors.isEmpty()) return RecommendationCheck.retry(recommendationRepairEvidence(errors, plan));
        return RecommendationCheck.ok(validated, plan.note());
    }

    /**
     * 展示这一轮校验通过的推荐：<b>就用模型给的那句话</b>，不再润色，也不再重写理由。
     *
     * <p>按钮由 Java 用已校验的候选按模型给的顺序生成（label / action / value 与既有页面约定一致，
     * 前端零改动）。文字是 {@code note + answering}——{@code note} 只在近似排除时有内容，
     * 那是 Java 必须说的话（「您说的市一我理解成市第一医院」），其余情况就是逐字的 {@code answering}。
     *
     * <p>走 {@code respondWithoutModel}：<b>不调用回答模型</b>，也不再经过「权威回复草稿」那一层。
     * 这句话已经过结构校验，交给润色层再改一遍，就等于把刚验过的东西重新变成没人校验的自由文本。
     */
    private AgentTurnResponse deliverRecommendation(ConversationState state, String answering,
                                                    RecommendationCheck check) {
        List<QuickReply> choices = check.validated().stream()
                .map(item -> q("选择" + item.name(), "SET_HOSPITAL", item.id())).toList();
        String note = check.note() == null ? "" : check.note();
        String text = answering == null ? "" : answering.trim();
        return respondWithoutModel(state, note + text, choices);
    }

    /**
     * 两次都没能给出合法推荐：只回一句中性的话。
     *
     * <p><b>不复述模型那句，也不出现任何医院名、任何按钮。</b>校验没通过的那句话里到底提了谁、
     * 说得对不对，Java 判断不了（中文自由措辞不可解析），所以整句都不能给老人看。
     */
    private AgentTurnResponse recommendationUnavailable(ConversationState state) {
        return respondWithoutModel(state,
                "这一轮我还没能给您一份靠得住的推荐，所以先不列医院了。"
                        + "您可以告诉我医生要求复诊的科室，或换个说法再说一次，我重新查。",
                resumeReplies(state));
    }

    /**
     * 证据引用能不能算数：那一次调用这一轮真的发生过吗？真查到东西了吗？是关于这一家医院的吗？
     *
     * <p>判「查到没有」读的是落库的 {@code response_json}，不是会话里那个 {@code outcomeKind}——
     * 后者在工具循环里几乎恒为 SUCCESS（只看响应形状），拿它当「有证据」等于没查过。
     */
    private String evidenceProblem(String ref, HospitalProfile hospital, List<ToolTrace> evidence) {
        if (ref == null || ref.isBlank()) return "EVIDENCE_MISSING: 有一条 evidenceRefs 是空的。";
        String traceName = TRACE_TOOL_NAMES.getOrDefault(ref, ref);
        List<ToolTrace> hits = evidence.stream()
                .filter(row -> traceName.equals(row.toolName())).toList();
        if (hits.isEmpty()) {
            return "EVIDENCE_NOT_GATHERED: 这一轮没有真的调用过“" + ref + "”，它不能当推荐理由的出处。";
        }
        if (hits.stream().noneMatch(row -> row.success() && traceHasResult(row.result()))) {
            return "EVIDENCE_EMPTY: “" + ref + "”这一轮查过，但没有查到内容，不能当推荐理由的出处。";
        }
        if (!HOSPITAL_SCOPED_TOOLS.contains(ref)) return null;
        boolean aboutThisOne = hits.stream()
                .anyMatch(row -> row.success() && traceHasResult(row.result()) && traceMentions(row, hospital));
        return aboutThisOne ? null
                : "EVIDENCE_NOT_ABOUT_HOSPITAL: “" + ref + "”这一轮查的不是" + hospital.name()
                + "（那一次的参数和结果里都没有这家医院），不能当它的推荐理由。";
    }

    /**
     * 这次调用到底查到东西没有。
     *
     * <p>光看「JSON 是不是空的」会漏：工具在「查了但一条也没有」时返回的往往是
     * {@code {"total":0,...}} 这种非空对象，当成证据就等于拿空结果去支撑推荐。所以还要看
     * 工具自己报出来的条数——报了条数且全是 0，就是没查到。
     */
    private boolean traceHasResult(String responseJson) {
        if (responseJson == null || responseJson.isBlank()) return false;
        JsonNode node;
        try {
            node = json.readTree(responseJson);
        } catch (Exception error) {
            // 没按 JSON 落库的痕迹（比如错误文本）不当空处理，交给 success 那位去判断。
            return true;
        }
        if (node == null || node.isNull()) return false;
        if (node.isArray()) return !node.isEmpty();
        if (node.isObject()) {
            boolean reportedCount = false;
            for (String field : TRACE_COUNT_FIELDS) {
                JsonNode value = node.get(field);
                if (value != null && value.isNumber()) {
                    reportedCount = true;
                    if (value.asInt() > 0) return true;
                }
            }
            return !reportedCount && !node.isEmpty();
        }
        return !node.asText("").isBlank();
    }

    /** 这一次调用的参数或结果里，有没有出现这家医院的 id 或名字。 */
    private static boolean traceMentions(ToolTrace trace, HospitalProfile hospital) {
        String text = (trace.parameters() == null ? "" : trace.parameters())
                + (trace.result() == null ? "" : trace.result());
        return (!hospital.id().isBlank() && text.contains(hospital.id()))
                || (!hospital.name().isBlank() && text.contains(hospital.name()));
    }

    /**
     * {@code answering} 里有没有夹带真实医院名。
     *
     * <p>查的是<b>真实目录里的名字</b>，不是新增一份中文词表：凡是目录里认得出来的医院名出现在这句话里、
     * 又不属于这次要推荐的那几家，整句就不展示。被排除的那家和「目录里真有、但没验过」的那家都在此列——
     * 前者是绕着排除走，后者是夹带了一个没人验过的名字。
     */
    private static String hospitalNamedIn(String text, java.util.Collection<HospitalProfile> catalog,
                                          List<HospitalProfile> allowed) {
        String haystack = CatalogEntityResolver.canonicalName(text);
        Set<String> allowedIds = allowed.stream().map(HospitalProfile::id).collect(java.util.stream.Collectors.toSet());
        for (HospitalProfile item : catalog) {
            String name = CatalogEntityResolver.canonicalName(item.name());
            if (name.isEmpty() || !haystack.contains(name)) continue;
            if (allowedIds.contains(item.id())) continue;
            return item.name();
        }
        return null;
    }

    private static String describe(HospitalProfile known, String hospitalId) {
        return known == null ? "“" + hospitalId + "”" : "“" + known.name() + "”";
    }

    private boolean hasDepartment(ConversationState state, String hospitalId, String department) {
        return departmentCatalogTool.listDepartments(state.id, hospitalId).stream()
                .anyMatch(row -> CatalogEntityResolver.canonicalName(row.name())
                        .equals(CatalogEntityResolver.canonicalName(department)));
    }

    /**
     * 校验不过时回给模型的结构化问题清单。
     *
     * <p>形状沿用 {@link #toolLoopEvidence} 那一套（{@code outcomeKind} + 结构化字段），
     * 让模型不必从中文里猜「到底哪一条不合规」。{@code recommendation.validate} 只是个记号，
     * <b>不注册成工具</b>，模型看不见它、也调不到它。
     */
    private String recommendationRepairEvidence(List<String> errors, RecommendationPlan plan) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("outcomeKind", "MISSING_INFO");
        value.put("toolName", "recommendation.validate");
        value.put("problems", errors);
        value.put("requestedDepartment", plan.department());
        value.put("excludedHospitals", plan.excluded());
        value.put("allowedCandidates", plan.kept().stream().map(item -> Map.of(
                "hospitalId", item.id(), "name", item.name())).toList());
        value.put("recommendationContract", List.of(
                "每条推荐填 hospitalId / reason / evidenceRefs；一次最多 3 条，hospitalId 不能重复",
                "hospitalId 只能取 allowedCandidates 里的真实 id",
                "evidenceRefs 填你这一轮真正调用过、并且真的查到了内容的工具名；每条推荐至少一个",
                "号源、路线、科室、院内指引这类证据必须是关于那一家医院的（参数里带上那家医院）",
                "answering 里只准出现本次要推荐的医院，别的真实医院名一个都不能有"));
        value.put("requiredShape", "{\"recommendations\":[{\"hospitalId\":\"...\",\"reason\":\"...\","
                + "\"evidenceRefs\":[\"appointment.querySlots\"]}],\"answering\":\"...\"}");
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            return value.toString();
        }
    }

    /**
     * 工具循环里的一步：响应本身，加上「这一步是哪类结果」。
     *
     * <p>分类只在两个地方产生，不靠读中文反推：取消支线自己知道（{@link CancellationStep}），
     * 其余的看响应形状——带确认卡就是等确认，阶段真的走到 COMPLETED 就是状态变了，剩下算成功。
     * 判不出来时说成功，比编一个错的结果类型要好。
     */
    private record AgentToolStep(AgentTurnResponse response, ToolOutcome.Step outcome) { }

    private AgentToolStep dispatchToolStep(ConversationState state, String value,
                                           AgentRuntime.Outcome outcome) {
        String tool = outcome.proposedTool() == null ? "" : outcome.proposedTool();
        // 取消支线的结果类型只有它自己知道（摆的是卡、是候选、还是没查到），所以单独接一遍。
        // 目前只读工具的映射落不到这条路由，这一段是分类口径的兜底，不是某条必经之路。
        if (outcome.route() == AgentOrchestrator.Route.CANCEL_EXISTING_APPOINTMENT) {
            CancellationStep step = beginCancellationStep(state, outcome.facts(), value,
                    outcome.modelDriven() ? outcome.replyDraft() : null,
                    modelCancellationSelection(outcome));
            return new AgentToolStep(step.response(),
                    new ToolOutcome.Step(step.kind(), tool, step.detail()));
        }
        AgentTurnResponse response = dispatchOutcome(state, value, outcome);
        ToolOutcome.Kind kind;
        if (response.confirmation() != null) {
            kind = ToolOutcome.Kind.NEEDS_CONFIRMATION;
        } else if (ConversationState.Stage.COMPLETED.name().equals(response.stage())) {
            kind = ToolOutcome.Kind.STATE_CHANGED;
        } else {
            kind = ToolOutcome.Kind.SUCCESS;
        }
        return new AgentToolStep(response, new ToolOutcome.Step(kind, tool, response.stage()));
    }

    /**
     * 工具循环的收口：模型这一轮没有再提工具，接着说什么。
     *
     * <p>工具循环里的每一轮都是「模型看着真实工具结果作答」，包括第一轮——它是由老人的话触发的，
     * 但模型选的是只读查询，不是业务动作。<b>所以这里不写草稿、也不重新进预约业务流</b>，
     * 交付的只有两样：真实查询结果、模型那句回答（拿不到就一句兜底说明）。
     * 要改草稿，得由老人的下一句话走业务动作（{@code PROPOSE_WORKFLOW_ACTION}），那一轮不经过这里。
     *
     * <p>推荐路由<b>不走这里</b>：它在循环顶就被 {@link #checkRecommendations} 拦下来了，
     * 模型那句自由措辞不能直接交付。
     *
     * <p>本轮的范围只到号源查询：{@code appointment.querySlots} 与 {@code appointment.queryNearbySlots}
     * 的模型调用只查不改。其余只读工具（如 {@code hospital.search}）仍可能更新会话里的解析状态，
     * 尚未逐个按同一口径核对。
     */
    private AgentTurnResponse finishToolLoopDecision(ConversationState state,
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
        String reply = decision.replyDraft() == null || decision.replyDraft().isBlank()
                ? "我已经完成了这项查询。您可以根据上面的真实结果继续选择。"
                : decision.replyDraft();
        return finalizeToolEvidence(state, latestToolResponse, reply);
    }

    /**
     * 一次用户消息内的只读约束：这一轮 Java 拿来算推荐的两样条件——科室，以及老人明确排除的医院。
     *
     * <p>它存在的理由是<b>工具循环跨轮</b>：老人说「推荐心内科的医院，别给我市一」之后，模型常常
     * 先查 {@code appointment.history} 或 {@code profile.memorySummary}，下一轮才给推荐。那一轮
     * 它未必把排除项重说一遍；只看最后那一轮的 {@code facts.excludedHospitals}，排除就会在续跑轮里
     * 悄悄失效，被排除的医院从推荐文字里回来。约束跟着整个循环走，这个缺口才不存在。
     *
     * <p><b>它只活在这一次用户消息的这一轮里</b>：一个局部变量，不落 {@code ConversationState}、
     * 不写 {@code MemoryStore}，方法返回就没了。老人下一轮改口说「那家也可以」，那是新的一轮、
     * 新的空约束——既不会被记住，也不会被上一轮的话挡住。
     *
     * <p>科室取第一次说定的那个，排除目标取并集（只增不减）：中途忘掉一个排除目标，等于把它放回候选。
     */
    private record TurnReadConstraint(String department, List<String> excludedHospitals) {
        static TurnReadConstraint none() { return new TurnReadConstraint(null, List.of()); }

        TurnReadConstraint merge(ExtractedFacts facts) {
            if (facts == null) return this;
            // 科室：第一次说定的那个就是这一轮的条件，后面几轮不重复说也不该丢。
            String mergedDepartment = department != null ? department : facts.department();
            if (facts.excludedHospitals().isEmpty()) {
                if (mergedDepartment == department) return this;
                return new TurnReadConstraint(mergedDepartment, excludedHospitals);
            }
            List<String> merged = new ArrayList<>(excludedHospitals);
            for (String raw : facts.excludedHospitals()) {
                if (!merged.contains(raw)) merged.add(raw);
            }
            return new TurnReadConstraint(mergedDepartment, List.copyOf(merged));
        }

        /** 交给 {@link #recommendHospitals} 的那一份事实：只带读条件，草稿字段一个都没有。 */
        ExtractedFacts facts() {
            return new ExtractedFacts("UNKNOWN", null, department, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, excludedHospitals);
        }
    }

    /**
     * 拿掉会落到草稿上的那几项，只留不属于「办理条件」的事实。
     *
     * <p>模型发起这一轮的 facts 不是老人的新说法：模型常把「这次要查哪家医院、哪一天」同时写进
     * 工具参数和 facts 节点，续跑轮还会原样带一遍。带着它进业务流程，就会出现「老人问一句
     * 市二院下周三有号吗，手头正办的那笔预约跟着改了日期」。所以医院、科室、日期、时间、陪同、
     * 通知这些一概拿掉；致谢、情绪和关切照旧。
     */
    private static AgentRuntime.Outcome withoutDraftWrites(AgentRuntime.Outcome outcome) {
        ExtractedFacts facts = outcome.facts();
        if (facts == null) return outcome;
        return new AgentRuntime.Outcome(outcome.route(),
                new ExtractedFacts(facts.intent(), null, null, null, null, null, null, null, null,
                        null, null, null, facts.acknowledgement(), facts.emotion(), facts.concern(), null,
                        // 排除目标是「这一轮查什么」，不是草稿字段，原样留着。
                        facts.excludedHospitals()),
                outcome.replyDraft(), outcome.dialogueMode(), outcome.plannerSource(), outcome.proposedTool(),
                outcome.proposedTools(), outcome.actionType(), outcome.intent(), outcome.modelDriven(),
                // 结构化推荐与最终话语不是「办理条件」，不落草稿，原样带走。
                outcome.recommendations(), outcome.answering(), outcome.javaFallback());
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
                outcome.intent(), outcome.modelDriven(),
                outcome.recommendations(), outcome.answering(), outcome.javaFallback());
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
            case "drug.queryKnowledge" -> AgentOrchestrator.Route.QUERY_DRUG_KNOWLEDGE;
            case "appointment.history" -> AgentOrchestrator.Route.QUERY_APPOINTMENT_HISTORY;
            case "profile.memorySummary" -> AgentOrchestrator.Route.QUERY_PROFILE_MEMORY;
            default -> fallback;
        };
    }

    private String toolSignature(PlannerToolCall call) {
        return call.toolName() + ":" + new TreeMap<>(call.arguments());
    }

    private String toolLoopEvidence(int round, List<PlannerToolCall> calls,
                                    AgentTurnResponse response, ToolOutcome.Step outcome) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("round", round);
        value.put("status", response.stage());
        // 这一步是哪类结果，说给模型听：SUCCESS/NO_RESULT/MISSING_INFO/NEEDS_CLARIFICATION/
        // NEEDS_CONFIRMATION/STATE_CHANGED/FAILURE。有了它，模型不必从中文里猜「是没查到还是参数不够」。
        if (outcome != null) {
            value.put("outcomeKind", outcome.kind().name());
            value.put("outcomeDetail", outcome.detail());
        }
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
        else if (!notifySettled(state)) state.stage = ConversationState.Stage.ASK_NOTIFY;
        else state.stage = ConversationState.Stage.READY_TO_PLAN;
    }

    private String firstToolArgument(AgentRuntime.Outcome outcome, String name, String fallback) {
        if (outcome.proposedTools() == null || outcome.proposedTools().isEmpty()) return fallback;
        return firstNonBlank(outcome.proposedTools().get(0).arguments().get(name), fallback);
    }

    /**
     * 医疗越界回复。
     *
     * <p>除了一句回复，还要给前端一个提示块：这句话在视觉上必须和普通聊天不一样，
     * 否则评审看不出它是「服务边界的拒绝」，还以为只是助手随口回了一句。
     *
     * <p>刻意不动 stage，也不清 {@code confirmationId}：老人问一句用药不等于想中断办理，
     * 手里还开着的确认卡要照常能用（取舍见 DECISIONS）。
     */
    private AgentTurnResponse medicalBoundary(ConversationState state) {
        String message = "我听到您身体不舒服了，但我不能诊断疾病、判断原因、解释检查结果或调整用药。"
                + "请及时咨询医生或专业医疗机构；如果症状突然加重，请尽快寻求线下帮助。";
        // 提示块的正文不是 reply 的复制：回复照常进对话记录，这块只补一句说明，
        // 让老人明白「这条为什么长得不一样」以及「想办的事没被打断」。
        String framing = "这类问题我不能回答，所以用这张提示卡单独说明。"
                + "您的复诊办理没有中断，接着往下办就行。";
        // 越界回答不能把等着按的确认卡挤掉：老人问到一半药，正要按的「确认办理」
        // 若跟着消失，他会以为办不成，而服务端那张卡其实一直是有效的。
        return respondWithoutModel(state, message, resumeReplies(state, q("咨询人工", "CONTACT_HUMAN", "")), null,
                new AgentTurnResponse.Notice(AgentTurnResponse.Notice.MEDICAL_BOUNDARY, "超出我的服务范围", framing),
                pendingConfirmation(state));
    }

    /**
     * 会话停在确认卡上时，把那张卡连同原来的 {@code confirmationId} 一起交回去。
     *
     * <p>只有复诊办理这张卡走这条路：取消类确认卡各自带着不同的状态支线
     * （{@code CANCEL_EXISTING} / {@code CANCEL_MANAGED} 等），这里不做重建，
     * 与加提示块之前的行为保持一致。
     */
    private ConfirmationCard pendingConfirmation(ConversationState state) {
        if (state.stage != ConversationState.Stage.AWAITING_CONFIRMATION) return null;
        // 卡片内容来自整套预约草稿，草稿不完整时宁可不显示，也不能凭空拼一张出来。
        if (state.selectedSlot == null || state.travelPlan == null) return null;
        if ("CANCEL_EXISTING".equals(state.pendingAction) || "CANCEL_MANAGED".equals(state.pendingAction)) return null;
        return confirmationCard(state, LocalDateTime.of(state.selectedSlot.date(), state.selectedSlot.time()));
    }

    /**
     * 清空内存里的会话表，只给演示场景重置用。
     *
     * <p>会话状态平时存在 {@code conversation_sessions} 里，内存这份是热副本；重置清了库之后，
     * 这个副本必须一起清——{@link #requireSession} 命中内存就不会回查数据库，
     * 不清的话旧会话 id 还能继续说话，而它对应的库记录已经没了。
     */
    public synchronized void forgetAllSessions() {
        sessions.clear();
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
        if (facts.date() != null && (facts.date().isBefore(clock.today())
                || facts.date().isAfter(clock.today().plusMonths(1)))) {
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
        if (closed(state)) return closedResponse(state);
        reactivateIfExpired(state);
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
        // 选一条候选、往后翻一页，都是「接着处理这次取消」，不能因为这个人名下已经有一份预约
        // 就被拦成「已有预约已保留，请选择下一步」——那正好是取消入口，拦住它等于没有出口。
        if (state.appointmentId != null && !List.of("CANCEL_APPOINTMENT", "QUERY_APPOINTMENTS",
                "SELECT_APPOINTMENT_TO_CANCEL", "MORE_CANCEL_CANDIDATES",
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
                return beginCancelExistingAppointment(state, ExtractedFacts.empty(), displayLabel, null, null);
            }
            case "QUERY_APPOINTMENTS" -> { return queryMyAppointments(state, ExtractedFacts.empty()); }
            case "QUERY_CARE_GUIDE" -> { return showCareGuide(state, "复诊办理流程"); }
            case "QUERY_CARE_TIMELINE" -> { return showCareTimeline(state); }
            case "QUERY_CARE_NOTIFICATIONS" -> { return showCareNotifications(state); }
            case "SELECT_APPOINTMENT_TO_CANCEL" -> { return prepareExistingCancellation(state, safeValue); }
            case "MORE_CANCEL_CANDIDATES" -> { return showCancellationCandidatesPage(state, safeValue); }
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
        // 会话结束之后，之前发出去的确认卡一律失效：确认门禁靠 confirmationId 绑定「当时那份
        // 操作快照」，而结束会话意味着那份快照不再作数。少了这一条，一个已经关掉的会话仍然能把
        // 预约提交或取消写进库，「结束后只读」就成了空话——这是所有写入路径里最要紧的一道。
        if (closed(state)) return closedResponse(state);
        reactivateIfExpired(state);
        if (stopped(state)) return stoppedResponse(state);
        // 凭据的校验与消费都在 ConfirmationService 里，这里只认它给的裁决：不通过就什么都不动，
        // 他还能按原来那个按钮重来一次；通过则凭据当场作废，同一把钥匙用不了第二回。
        ConfirmationService.Decision decision = confirmations.consume(state, confirmationId);
        if (!decision.accepted()) {
            return respondWithPlan(state, "这份确认已经失效或已办理，请查看当前计划后重新确认。",
                    state.appointmentId == null ? List.of(q("检查计划", "START_PLAN", "")) : bookedActions(state));
        }
        conversations.addMessage(state.id, "user", approved ? "[确认] 执行操作" : "[确认] 暂不执行");
        // 这一轮到底要执行哪件事，由凭据上的登记说了算：动作类型与完整目标集合在签发时就冻在
        // PendingOperation 里，此后任何会话字段怎么变都影响不了它。分派见 ConfirmationDispatcher。
        return dispatcher.dispatch(state, decision.operation(), approved, this);
    }

    private AgentTurnResponse executionResult(ConversationState state, String message) {
        ResultCard card = recordResult(state);
        return finish(state, new AgentTurnResponse(state.id, state.stage.name(), message, bookedActions(state),
                plan(state), null, card, traces.findByConversation(state.id)));
    }

    /**
     * 预约完成路径：口播与文字全部由权威 ResultCard 字段确定性拼接，不经过回答模型改写，避免关键事实被压缩或变形。
     */
    @Override
    AgentTurnResponse completionResult(ConversationState state) {
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

    @Override
    List<QuickReply> bookedActions(ConversationState state) {
        return List.of(q("查看事项", "OPEN_TASKS", ""),
                state.stage == ConversationState.Stage.PARTIAL ? q("补办未完成事项", "RETRY_EXECUTION", "") : q("修改预约", "EDIT_BOOKING", ""),
                q("取消预约", "CANCEL_APPOINTMENT", ""), q("新建办理", "NEW_BOOKING", ""));
    }

    @Override
    <T> T callTool(ConversationState state, String name, Map<String, ?> parameters, java.util.function.Supplier<T> operation) {
        try { return operation.get(); }
        catch (RuntimeException error) {
            traces.record(state.id, name, parameters, Map.of("error", String.valueOf(error.getMessage())), false);
            throw error;
        }
    }

    @Override
    AgentTurnResponse toolError(ConversationState state, RuntimeException error) {
        confirmations.clear(state);
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

    // ---------------------------------------------------------------------------------------
    // ConfirmationSupport：确认执行器执行完之后，交回编排层做收尾的那几个口子。
    //
    // 这里全是转接，不新增任何判断——每个方法都对应本类里一个已有的出口。之所以要开这个口子、
    // 以及为什么是包级私有的抽象类而不是接口，见 ConfirmationSupport 的类注释。
    // ---------------------------------------------------------------------------------------

    /** 草稿信息是否齐全（执行器读它决定「能不能照这份草稿写下去」）。 */
    @Override
    boolean draftComplete(ConversationState state) {
        return ready(state);
    }

    /**
     * 这张卡是不是「照草稿开新预约」、而且草稿里那个时段已经过去了。
     *
     * <p>只挡「照着草稿开新预约」这一条路（pendingAction 为 CREATE 或未设）。完成过的会话仍然
     * 留着当初那份 selectedSlot，取消的是老预约、跟这个时段没关系，拿它去拦会让老人越等越
     * 取消不了，只能重新选日期。
     */
    @Override
    boolean draftSlotExpired(ConversationState state) {
        return booksFromDraft(state) && ready(state) && slotAlreadyPassed(state.selectedSlot);
    }

    /** 被打断过的那件事的「继续办理」按钮。 */
    @Override
    List<QuickReply> resumeInterruptedReplies(ConversationState state) {
        return resumeReplies(state);
    }

    /** 本会话到目前为止的工具调用记录。 */
    @Override
    List<AgentTurnResponse.ToolTrace> traceList(ConversationState state) {
        return traces.findByConversation(state.id);
    }

    /** 备忘落库成功后的回读话术（一句话说了几天就逐条念）。 */
    @Override
    String memoRecordedReply(List<MemoStore.MemoView> created, String repeatRule) {
        return memoSavedReply(created, repeatRule);
    }

    /** 多天备忘只写成了其中几条时的回读话术：写成的念，没写成的说清。 */
    @Override
    String memoPartlyRecordedReply(List<MemoStore.MemoView> created, List<LocalDateTime> missed,
                                   String repeatRule) {
        return memoPartlySavedReply(created, missed, repeatRule);
    }

    /** 业务执行器自己接住异常时，把这次失败记下来。 */
    @Override
    void recordToolFailure(ConversationState state, RuntimeException error) {
        traces.record(state.id, "workflow.error", Map.of("stage", state.stage.name()),
                Map.of("error", String.valueOf(error.getMessage())), false);
    }

    /** 交回漏斗继续下一步（信息不齐时）。 */
    @Override
    AgentTurnResponse advance(ConversationState state) {
        return advance(state, ExtractedFacts.empty());
    }

    private boolean stopped(ConversationState state) {
        return state.stage == ConversationState.Stage.CANCELLED || state.stage == ConversationState.Stage.EMERGENCY_PAUSED;
    }

    /** 用户主动结束过的会话。之后只读：还能翻看，但不再接受新的办理与确认。 */
    private boolean closed(ConversationState state) {
        return state.status == ConversationState.Status.CLOSED;
    }

    /**
     * 已经结束的会话收到新指令时的回复。
     *
     * <p>这里给不出「新对话」按钮：新建会话必须换一个 conversationId，而按钮走的是
     * 同一个会话的 /actions，前端只会把这条回复贴进旧会话。所以换新对话由前端直接调
     * 建会话接口完成，这条回复只负责说清楚「为什么这里办不了了」。
     *
     * <p>刻意不落库：这是一句拒绝，不是一轮对话。落库的话，一个还开着确认卡的老页面
     * 每重试一次就多一条一模一样的「已经结束」，老人回头翻这段历史，真正聊过的内容
     * 反倒被一串复读淹掉。回复照常返回，界面上照常看得见。
     */
    private AgentTurnResponse closedResponse(ConversationState state) {
        String reply = "这段对话已经结束了。之前聊过的都保存在历史记录里，"
                + "随时可以翻看；要办新的事情，请点上面的「新对话」重新开始。";
        return new AgentTurnResponse(state.id, state.stage.name(), reply, List.of(),
                null, null, null, List.of(), null, reply, null);
    }

    /**
     * 太久没说话只是显示口径，人回来了就接着办：不因为离开过一会儿就要求老人重说一遍。
     * 真正的结束只有用户主动点「新对话」这一条路径。
     */
    private void reactivateIfExpired(ConversationState state) {
        if (state.status == ConversationState.Status.EXPIRED) state.status = ConversationState.Status.ACTIVE;
    }

    private AgentTurnResponse stoppedResponse(ConversationState state) {
        return respond(state, state.stage == ConversationState.Stage.EMERGENCY_PAUSED
                ? "普通办理已暂停，请及时联系急救服务或身边人员。旧操作不会继续执行。"
                : "本次办理已经停止。已有预约记录仍可在事项页查看。", List.of(q("查看事项", "OPEN_TASKS", ""), q("新建办理", "NEW_BOOKING", "")));
    }

    private AgentTurnResponse emergency(ConversationState state) {
        confirmations.clear(state);
        if (state.taskStatus == ConversationState.TaskStatus.ACTIVE) {
            state.taskStatus = ConversationState.TaskStatus.PAUSED;
        }
        state.stage = ConversationState.Stage.EMERGENCY_PAUSED;
        notifyArranger(state, "emergency",
                "紧急通知：" + elderName(state.userId) + "触发紧急求助，助手已引导拨打120或联系身边人。请尽快联系老人确认情况。");
        return emergencyNotice(state);
    }

    /** 紧急暂停期间的统一安全提示：只给文字和按钮，绝不自动跳转页面。 */
    private AgentTurnResponse emergencyNotice(ConversationState state) {
        return respondWithoutModel(state, "这可能是紧急情况。请立即联系身边人员，拨打120或寻求线下急救帮助。普通办理已暂停，已有记录保留。",
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
        confirmations.clear(state);
        state.pendingAppointmentIds = List.of();
        state.scheduleChecked = false;
        state.travelPlan = null;
        if (state.stage == ConversationState.Stage.AWAITING_CONFIRMATION) state.stage = ConversationState.Stage.READY_TO_PLAN;
    }

    private boolean ready(ConversationState state) {
        return state.hospitalId != null && state.department != null && state.selectedSlot != null
                && state.date != null && state.date.equals(state.selectedSlot.date())
                && state.hospitalId.equals(state.selectedSlot.hospitalId()) && state.department.equals(state.selectedSlot.department())
                && state.acceptAlternative != null && state.needCompanion != null && state.needTravel != null
                && state.transport != null && notifySettled(state) && !state.materials.isEmpty();
    }

    /**
     * “通知哪位家属”这一步是否已经了结。
     *
     * <p>本人自办时要问：老人得从自己登记的家属里选一个能收到通知的人。
     * 代他人办理时不问——操作者本人就是家属或志愿者，让他再选一遍“通知家里谁”是多余的，
     * 而且这次代约由 {@link CareBookingService} 自己通知其他照护者，选的这个联系人根本不会被用到。
     */
    private boolean notifySettled(ConversationState state) {
        if (state.caregiving()) return true;
        return state.notifyFamily != null && (!state.notifyFamily || state.contact != null);
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

    @Override
    String notificationMessage(ConversationState state) {
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

    private AgentTurnResponse beginCancelExistingAppointment(ConversationState state, ExtractedFacts facts,
                                                             String originalMessage, String modelDraft,
                                                             CancellationSelection structuredSelection) {
        return beginCancellationStep(state, facts, originalMessage, modelDraft, structuredSelection).response();
    }

    private CancellationStep beginCancellationStep(ConversationState state, ExtractedFacts facts,
                                                   String originalMessage, String modelDraft,
                                                   CancellationSelection structuredSelection) {
        if (structuredSelection != null && state.stage == ConversationState.Stage.AWAITING_CONFIRMATION
                && ("CANCEL_EXISTING".equals(state.pendingAction) || "CANCEL".equals(state.pendingAction))) {
            confirmations.retire(state);
        }
        rememberInterruptedTask(state, "CANCEL_EXISTING_APPOINTMENT");
        if (structuredSelection != null && !structuredSelection.valid()) {
            // 参数缺项或取值不合法：这次调用根本不够用，绝不能顺手当成「都取消」。
            // 仍然把真实候选摆出来让老人点，但类型是「缺少信息」，和「对象不唯一」分得开。
            return clarifyCancellationTargets(state, structuredSelection.rejection(), null);
        }
        List<AppointmentSummary> rows = structuredSelection == null
                ? cancellationCandidates(state, facts, originalMessage)
                : cancellationCandidates(state, structuredSelection);
        if (rows.isEmpty()) {
            state.sideTask = null;
            // 没查到既不是出错也不是要澄清：把事实说清楚，再给一个能往下走的入口。
            return new CancellationStep(ToolOutcome.Kind.NO_RESULT,
                    respond(state, "没有找到符合条件的已确认预约，所以没有执行取消。",
                            resumeReplies(state, q("查询我的预约", "QUERY_APPOINTMENTS", ""))),
                    "库里没有符合条件的已确认预约");
        }
        if (rows.size() == 1) {
            return new CancellationStep(ToolOutcome.Kind.NEEDS_CONFIRMATION,
                    prepareExistingCancellation(state, List.of(rows.get(0)), modelDraft),
                    "已生成确认卡，等待老人确认");
        }

        // “都取消 / 某日前的”已经明确圈定了一组对象：展示整组确认卡，一次确认后原子取消。
        // 普通“取消预约”没有圈定范围，仍只展示候选，不替老人猜。
        boolean batch = structuredSelection == null
                ? isBatchCancellationRequest(originalMessage) : structuredSelection.batch();
        if (!state.caregiving() && batch) {
            return new CancellationStep(ToolOutcome.Kind.NEEDS_CONFIRMATION,
                    prepareExistingCancellation(state, rows, modelDraft),
                    "已生成整组确认卡，等待老人确认");
        }
        return clarifyCancellationTargets(state, null, rows);
    }

    /**
     * 取消对象不唯一（或参数不够用）时问「您说的是哪一条」。
     *
     * <p>问题可以来自模型，候选永远来自数据库：{@code rows} 是调用方刚用真实工具筛出来的，
     * 传空则在 {@link ClarificationInteractionTool} 里从 {@code appointment.queryMine} 现取。
     * 走到这里不会建卡、不会发 {@code confirmationId}——老人点选之后仍然要过那张确认卡。
     */
    private CancellationStep clarifyCancellationTargets(ConversationState state,
                                                        ToolContract.Rejection rejection,
                                                        List<AppointmentSummary> rows) {
        List<AppointmentSummary> candidates = rows != null ? rows
                : myAppointmentTool.search(state.id, state.userId, null, null, null);
        boolean missingInfo = rejection != null;
        ClarificationInteractionTool.Prompt prompt = clarificationInteraction.askWhichAppointmentToCancel(
                state.id, candidates, null,
                missingInfo
                        ? "我还需要知道取消的范围，才能确定要取消哪几条预约。您可以从下面的真实预约里直接选一条。"
                        : "我查到多条已确认预约。为防止取消错，请选择要取消的那一条。");
        // 「一条都没有」盖过「缺参数」：没对象可选时，说清没查到比追问范围更有用。
        ToolOutcome.Kind kind = missingInfo && prompt.kind() == ToolOutcome.Kind.NEEDS_CLARIFICATION
                ? ToolOutcome.Kind.MISSING_INFO : prompt.kind();
        String detail = missingInfo ? rejection.detail()
                : prompt.kind() == ToolOutcome.Kind.NO_RESULT ? "库里没有可取消的已确认预约"
                : "候选 " + candidates.size() + " 条，等老人点选或说出是哪一条";
        // 候选列表同时是要念出来的话，所以这份回复不再交给模型润色：按钮是按它生成的那几条，
        // 文字被改写就会出现「问的和按钮对不上」。
        return new CancellationStep(kind,
                respondWithoutModel(state, prompt.reply(), prompt.choices()), detail);
    }

    /**
     * 澄清候选翻页：只是「再看几条」，不动任何业务状态。
     *
     * <p>候选每一页都从数据库现取，所以翻页看到的永远是真的；页码由按钮自己带过来（越界由工具夹回
     * 有效范围），往前往后都到得了，候选不会再因为「只摆得下四条」而有一条谁都点不到。
     */
    private AgentTurnResponse showCancellationCandidatesPage(ConversationState state, String pageValue) {
        int page = 0;
        try {
            page = Integer.parseInt(pageValue == null ? "" : pageValue.trim());
        } catch (NumberFormatException ignored) {
            // 页码读不出来就回到第一页：翻页是展示动作，不该因为一个脏参数把人卡住。
        }
        rememberInterruptedTask(state, "CANCEL_EXISTING_APPOINTMENT");
        ClarificationInteractionTool.Prompt prompt = clarificationInteraction.askWhichAppointmentToCancel(
                state.id, myAppointmentTool.search(state.id, state.userId, null, null, null), null,
                "您想取消的是哪一条？可以从下面的真实预约里直接选一条。", page);
        return respondWithoutModel(state, prompt.reply(), prompt.choices());
    }

    /** 模型模式只消费结构化工具参数，不再从中文原句的关键词推断取消范围。 */
    private List<AppointmentSummary> cancellationCandidates(ConversationState state,
                                                            CancellationSelection selection) {
        List<AppointmentSummary> rows = myAppointmentTool.search(state.id, state.userId, null, null, null);
        if (selection.hospital() != null) {
            rows = rows.stream().filter(item -> item.hospital().contains(selection.hospital())).toList();
        }
        if (selection.department() != null) {
            rows = rows.stream().filter(item -> item.department().contains(selection.department())).toList();
        }
        if ("DATE_RANGE".equals(selection.scope())) {
            LocalDate boundary = selection.date();
            rows = switch (selection.direction()) {
                case "BEFORE" -> rows.stream().filter(item -> item.date().isBefore(boundary)).toList();
                case "ON_OR_BEFORE" -> rows.stream().filter(item -> !item.date().isAfter(boundary)).toList();
                case "AFTER" -> rows.stream().filter(item -> item.date().isAfter(boundary)).toList();
                case "ON_OR_AFTER" -> rows.stream().filter(item -> !item.date().isBefore(boundary)).toList();
                default -> List.of();
            };
        } else if ("SINGLE_FILTER".equals(selection.scope()) && selection.date() != null) {
            rows = rows.stream().filter(item -> item.date().equals(selection.date())).toList();
        }
        if (selection.time() != null) {
            rows = rows.stream().filter(item -> item.time().equals(selection.time())).toList();
        } else if ("MORNING".equals(selection.period())) {
            rows = rows.stream().filter(item -> item.time().isBefore(LocalTime.NOON)).toList();
        } else if ("AFTERNOON".equals(selection.period())) {
            rows = rows.stream().filter(item -> !item.time().isBefore(LocalTime.NOON)).toList();
        }
        if (rows.size() > 1 && "EARLIEST".equals(selection.position())) {
            return List.of(rows.stream().min(Comparator.comparing(this::appointmentAt)).orElseThrow());
        }
        if (rows.size() > 1 && "NEAREST".equals(selection.position())) {
            LocalDateTime now = clock.now();
            return List.of(rows.stream().min(Comparator.comparingLong(item ->
                    Math.abs(java.time.Duration.between(now, appointmentAt(item)).toMinutes()))).orElseThrow());
        }
        return rows;
    }

    /**
     * 把模型给的结构化参数读成取消范围。
     *
     * <p>参数读的是 {@link AgentRuntime#acceptedArguments} 归一化之后的那份：只留工具声明过的字段，
     * 枚举补大写，空串当没给。模型硬塞的 {@code appointmentId} 不在声明里，读到这一步就已经没了——
     * 「模型不得指定取消哪一条」这条约束靠的就是它，不是靠提示词里的一句叮嘱。
     *
     * <p>「这次调用够不够用」由 {@link ToolContract} 判，这里不另写一套范围检查。
     */
    private CancellationSelection modelCancellationSelection(AgentRuntime.Outcome outcome) {
        if (!outcome.modelDriven() || outcome.actionType() != PlannerActionType.CALL_CONFIRMATION_TOOL
                || outcome.proposedTools() == null || outcome.proposedTools().size() != 1
                || !"interaction.requestConfirmation".equals(outcome.proposedTools().get(0).toolName())) {
            return null;
        }
        PlannerToolCall call = outcome.proposedTools().get(0);
        ToolContract.Rejection rejection = agentRuntime.rejection(call.toolName(), call);
        Map<String, String> args = agentRuntime.acceptedArguments(call.toolName(), call);
        String scope = upper(args.get("scope"));
        return new CancellationSelection(rejection == null, scope,
                ToolContract.parseDate(args.get("date")), upper(args.get("direction")),
                ToolContract.parseTime(args.get("time")), upper(args.get("period")),
                upper(args.get("position")), blankToNull(args.get("hospital")),
                blankToNull(args.get("department")), rejection);
    }

    private List<AppointmentSummary> cancellationCandidates(ConversationState state, ExtractedFacts facts,
                                                            String originalMessage) {
        String message = originalMessage == null ? "" : originalMessage;
        List<AppointmentSummary> rows = myAppointmentTool.search(
                state.id, state.userId, null, facts.hospital(), facts.department());
        // 只有用户这一句真的提到日期，才允许拿模型抽取的日期去缩小取消范围。模型给出的 date 可能
        // 来自上一轮闲聊或它自己的补全；“都取消”被它悄悄缩成一天，就会取消错对象。
        LocalDate modelDate = mentionsCancellationDate(message) ? facts.date() : null;
        LocalDate mentioned = cancellationDate(message, modelDate);
        boolean before = containsAny(message, "之前", "以前", "日前", "号前", "日之前", "号之前");
        boolean inclusiveBefore = containsAny(message, "及以前", "及之前", "当天也算", "包括当天");
        boolean after = containsAny(message, "之后", "以后", "日后", "号后");
        boolean inclusiveAfter = containsAny(message, "及以后", "及之后", "当天也算", "包括当天");
        if (mentioned != null) {
            if (before) {
                rows = rows.stream().filter(item -> inclusiveBefore
                        ? !item.date().isAfter(mentioned) : item.date().isBefore(mentioned)).toList();
            } else if (after) {
                rows = rows.stream().filter(item -> inclusiveAfter
                        ? !item.date().isBefore(mentioned) : item.date().isAfter(mentioned)).toList();
            } else {
                rows = rows.stream().filter(item -> item.date().equals(mentioned)).toList();
            }
        }
        if (facts.selectedTime() != null) {
            rows = rows.stream().filter(item -> item.time().equals(facts.selectedTime())).toList();
        } else if (containsAny(message, "上午", "早上")) {
            rows = rows.stream().filter(item -> item.time().isBefore(LocalTime.NOON)).toList();
        } else if (containsAny(message, "下午", "午后")) {
            rows = rows.stream().filter(item -> !item.time().isBefore(LocalTime.NOON)).toList();
        }
        return rows;
    }

    /**
     * 这一句用户话里到底有没有出现日期，决定能不能用模型抽取的日期去缩小取消范围。
     *
     * <p>相对日期词表与 {@code RuleFactExtractor.parseDate} 保持同一口径：那里认「今天/明天/后天」
     * 和「下周/本周/这周/周X/星期X」，这里就必须认同一批，否则用户说「下周的都取消」会被当成没有日期。
     */
    private boolean mentionsCancellationDate(String message) {
        String value = message == null ? "" : message;
        if (mentionedFullDate(value) != null) return true;
        if (CANCELLATION_DATE.matcher(value).find()) return true;
        if (containsAny(value, "今天", "明天", "后天")) return true;
        return RELATIVE_WEEKDAY.matcher(value).find();
    }

    private LocalDate cancellationDate(String message, LocalDate modelDate) {
        LocalDate fullDate = mentionedFullDate(message == null ? "" : message);
        if (fullDate != null) return fullDate;
        Matcher matcher = CANCELLATION_DATE.matcher(message == null ? "" : message);
        if (!matcher.find()) return modelDate;
        try {
            // 这里筛的是数据库里已经存在的预约，过去两天也可能仍是 CONFIRMED，
            // 所以“9月10日”一律按今年理解，只按业务时区的今天取年份。
            return LocalDate.of(clock.today().getYear(), Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)));
        } catch (RuntimeException ignored) {
            return modelDate;
        }
    }

    private boolean isBatchCancellationRequest(String message) {
        String value = message == null ? "" : message;
        if (containsAny(value, "都取消", "全部取消", "全取消", "所有预约", "这些预约", "这几条")) {
            return true;
        }
        boolean hasDate = CANCELLATION_DATE.matcher(value).find() || SPOKEN_FULL_DATE.matcher(value).find();
        return hasDate && containsAny(value, "之前", "以前", "日前", "号前", "日之前", "号之前",
                "之后", "以后", "日后", "号后", "日之后", "号之后");
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
            LocalDateTime now = clock.now();
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
        LocalDate fullDate = mentionedFullDate(message);
        if (fullDate != null) return fullDate;
        Matcher matcher = SPOKEN_DATE.matcher(message);
        if (!matcher.find()) return null;
        try {
            return LocalDate.of(clock.today().getYear(), Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)));
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
        return prepareExistingCancellation(state, List.of(target), null);
    }

    private AgentTurnResponse prepareExistingCancellation(ConversationState state, AppointmentSummary target) {
        return prepareExistingCancellation(state, List.of(target), null);
    }

    private AgentTurnResponse prepareExistingCancellation(ConversationState state,
                                                          List<AppointmentSummary> targets,
                                                          String modelDraft) {
        AppointmentSummary target = targets.get(0);
        state.pendingAction = "CANCEL_EXISTING";
        state.pendingAppointmentId = target.appointmentId();
        state.pendingAppointmentIds = targets.stream().map(AppointmentSummary::appointmentId).distinct().toList();
        state.sideTask = "CANCEL_EXISTING_APPOINTMENT";
        // 授权范围与凭据一起交给 ConfirmationService：这一步之后，「这张卡对哪几条生效」就不再是
        // 一个谁也说不准的问题——它和凭据绑在一起（一起进快照、一起作废），重启也不会缩水。
        // 类型由这里给死：本方法建的就是取消预约的卡，不看 pendingAction 现算。
        //
        // 「谁的业务链路来取消」也一起给死：本人自办走老人端那批原子取消，代他人办理走
        // CareBookingService（它会一并通知其他照护者）。这两个取值是两类动作，不是同一个类型
        // 加一个执行时再看一眼的开关——那一眼用的又是可变状态，等于把已经冻在凭据里的事重新交还回去。
        String confirmationId = confirmations.issue(state,
                state.caregiving()
                        ? ConfirmationService.PendingOperation.Kind.CANCEL_APPOINTMENTS_CAREGIVER
                        : ConfirmationService.PendingOperation.Kind.CANCEL_APPOINTMENTS,
                state.pendingAppointmentIds);
        state.taskStatus = ConversationState.TaskStatus.AWAITING_CONFIRMATION;
        ConfirmationInteractionTool.Prompt prompt = confirmationInteraction.requestCancellation(
                state.id, confirmationId, targets, modelDraft);
        return finishWithoutModel(state, new AgentTurnResponse(state.id, state.stage.name(),
                prompt.reply(), List.of(), null, prompt.card(), targets.size() == 1 ? resultCard(target) : null,
                traces.findByConversation(state.id), null, prompt.speechText(), null));
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
        if (facts.date().isBefore(clock.today()) || facts.date().isAfter(clock.today().plusMonths(1))) {
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
            // 举例不带具体日子：写死某一天，那天一过，举例本身就先过期了。
            case ASK_DATE -> "我还没有确认复诊日期。您可以说“下周三”，也可以直接说几月几号，我再为您查询号源。";
            case SELECT_PERIOD, SELECT_SLOT, CONFIRM_SLOT -> "我还没有确认您想要的时间。可以说上午、下午或具体几点，也可以说换日期。";
            case NO_SLOT -> "原日期暂时没有号。您可以说查附近几天、换日期、换医院，或者稍后再查。";
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

    /**
     * 药品知识查询。命中就照知识库的原文说，<b>没命中就如实说没查到</b>，
     * 绝不凭模型记忆补一条出来 —— 药品名称、规格、用途写错比说「不知道」危险得多。
     */
    private AgentTurnResponse showDrugKnowledge(ConversationState state, AgentRuntime.Outcome outcome) {
        String drugName = firstToolArgument(outcome, "drugName", "");
        if (drugName.isBlank()) {
            return respond(state, "您想查哪种药？把药盒上的名字说给我听，我帮您查它的用途和注意事项。",
                    resumeReplies(state));
        }
        String specification = firstToolArgument(outcome, "specification", "");
        List<DrugKnowledge> hits = callTool(state, "drug.queryKnowledge",
                Map.of("drugName", drugName, "specification", specification),
                () -> drugKnowledgeTool.search(state.id, drugName, specification));
        if (hits.isEmpty()) {
            return respond(state, "我在药品知识库里没有查到「" + drugName + "」这条。"
                            + "您可以照着药盒把名字再说一遍，或者拿着药盒直接问药师、问开药的医生。",
                    resumeReplies(state));
        }
        StringBuilder text = new StringBuilder();
        for (DrugKnowledge drug : hits) {
            if (text.length() > 0) text.append("\n\n");
            text.append(drugKnowledgeText(drug));
        }
        return respond(state, text.toString(), resumeReplies(state));
    }

    private String drugKnowledgeText(DrugKnowledge drug) {
        StringBuilder line = new StringBuilder("【" + drug.name() + "】");
        if (drug.specification() != null && !drug.specification().isBlank()) {
            line.append(" 规格 ").append(drug.specification());
        }
        if (drug.category() != null && !drug.category().isBlank()) {
            line.append("（").append(drug.category()).append("）");
        }
        line.append('\n').append(drug.purpose());
        if (drug.reminder() != null && !drug.reminder().isBlank()) {
            line.append('\n').append("注意：").append(drug.reminder());
        }
        if (drug.followupTip() != null && !drug.followupTip().isBlank()) {
            line.append('\n').append("复诊提示：").append(drug.followupTip());
        }
        return line.toString();
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

    /** 已确认预约的路线：真正打开地图的那一种。 */
    private static final String MODE_APPOINTMENT = "APPOINTMENT";
    /** 候选条件的估算：只回用时和距离，不打开地图。 */
    private static final String MODE_CANDIDATE = "CANDIDATE";

    /**
     * {@code travel.routePlan} 的入口。两种语义靠结构化 {@code mode} 分开，不靠「带了哪些参数」猜：
     * APPOINTMENT 看某一次已确认预约的路线（Java 校验预约归属，照旧打开地图、发页面指令），
     * CANDIDATE 只估算候选条件的用时（不打开地图、不改草稿）。
     *
     * <p>mode 缺省时才按参数唯一推断：只给 appointmentId → APPOINTMENT，只给候选参数 → CANDIDATE。
     * 两边都给了、或者 mode 和参数对不上，一律不猜、转成澄清——猜错的代价不对称：
     * 把「打开地图」猜成估算，老人被留在比较的半路上；把估算猜成「打开地图」，
     * 则会在他还没定下来的时候跳走页面。
     *
     * <p><b>transport 只属于 CANDIDATE</b>（用于候选路线比较）。APPOINTMENT 用的是这条预约和
     * 用户资料里已经存着的交通方式，<b>不接受这一次的 transport 覆盖</b>，所以带上它不算混用、
     * 也不用报错，忽略即可。三种没分清的情形各给一句话，老人接下来要做的事不一样。
     *
     * <p>一个参数都不给的老调用（例如老人点了「查看地图」）原样走 {@link #showTravelGuide}，
     * 那条路本来就会去数据库里定位到底要看哪一次预约。
     */
    private AgentTurnResponse travelGuideFor(ConversationState state, ExtractedFacts facts,
                                            String originalMessage, AgentRuntime.Outcome outcome) {
        if (!outcome.modelDriven()) {
            return showTravelGuide(state, facts, originalMessage, false);
        }
        Map<String, String> arguments = toolArguments(outcome, "travel.routePlan");
        String mode = blankToNull(arguments.get("mode"));
        String appointmentId = blankToNull(arguments.get("appointmentId"));
        // 「混着给」只看预约编号和「哪一家、哪一天、几点」。transport 不算：它只属于 CANDIDATE，
        // APPOINTMENT 忽略它（预约与资料里存着什么就按什么走），带上它只是多了一个用不上的字段。
        if (appointmentId != null && hasRouteCandidateLocation(arguments)) {
            return routeModeMixedArguments(state);
        }
        boolean candidateArguments = hasRouteCandidateArguments(arguments);
        if (mode == null) {
            if (appointmentId == null && !candidateArguments) {
                return showTravelGuide(state, facts, originalMessage, false);
            }
            mode = appointmentId != null ? MODE_APPOINTMENT : MODE_CANDIDATE;
        }
        if (MODE_APPOINTMENT.equals(mode)) {
            if (appointmentId == null) return routeModeMissingAppointment(state);
            return routeForAppointment(state, appointmentId);
        }
        // CANDIDATE：给药了 appointmentId 是模式说不通，什么都没给是条件不够，两句话不一样。
        if (appointmentId != null) return routeModeMixedArguments(state);
        if (!candidateArguments) return routeModeMissingConditions(state);
        return routeEstimateReadOnly(state, arguments);
    }

    /**
     * 三种「没分清」分开说，因为老人接下来要做的事不一样：缺预约编号要去挑一条预约，
     * 缺条件要把医院或时间说出来，模式矛盾要说明白他到底想看哪一种。
     *
     * <p>都不摆候选：老人这一轮问的是路线，Java 手上并没有一个「正确的那一次预约」，
     * 随便摆几条出来等于把猜错的责任推给他。
     */
    private AgentTurnResponse routeModeMissingAppointment(ConversationState state) {
        return answerReadOnly(state, "您想看的是某一次复诊预约的路线，但没告诉我是哪一次。"
                + "请从您的预约里选一条，我再打开它的地图。");
    }

    private AgentTurnResponse routeModeMissingConditions(ConversationState state) {
        return answerReadOnly(state, "要估算路上要多久，我还需要知道去哪家医院、哪一天几点。"
                + "请告诉我要去的医院或者时间。");
    }

    private AgentTurnResponse routeModeMixedArguments(ConversationState state) {
        return answerReadOnly(state, "这一次的路线请求我没分清：是要看某一次已确认预约的路线，"
                + "还是只估算某个候选条件的用时。请说明白是看已有预约的路线，还是估算候选的路线。");
    }

    /**
     * 已确认预约的路线：这一条才是真正打开地图、发页面跳转指令的那条路。
     *
     * <p><b>预约归属由 SQL 把关</b>：{@link TravelGuideService#forAppointment} 的
     * {@code WHERE a.user_id=? AND a.id=?} 就是这次调用的鉴权——模型给的 appointmentId 必须
     * 真属于这位就诊人，否则查不到、抛异常，这里转成一句明确的回绝。
     * 「不存在」和「不是您的」回同一句话，不给出可比较的差异，避免越权调用靠回话探测预约是否存在。
     * 越权时不发 UiDirective，页面不会因为一个模型给的编号被带走。
     */
    private AgentTurnResponse routeForAppointment(ConversationState state, String appointmentId) {
        // 紧急处置期间安全提示优先：这一条和 showTravelGuide 一样，不自动跳页。
        if (state.stage == ConversationState.Stage.EMERGENCY_PAUSED) return emergencyNotice(state);
        try {
            return renderTravelGuide(state, appointmentId, false);
        } catch (IllegalArgumentException missing) {
            return answerReadOnly(state, "没有找到这条复诊预约，所以没有打开地图。"
                    + "您可以先查询自己的预约，再从里面选一条看路线。");
        }
    }

    /**
     * 这次调用有没有给 CANDIDATE 要的东西。{@code appointmentId} 不算——它是另一种模式的参数。
     *
     * <p>只要给了 {@code transport} 就算：医院、日期、时刻可以回退到草稿里已经明确的条件，
     * 「坐公交去要多久」本身就是一个完整的候选估算请求。
     */
    private boolean hasRouteCandidateArguments(Map<String, String> arguments) {
        return hasRouteCandidateLocation(arguments) || blankToNull(arguments.get("transport")) != null;
    }

    /**
     * 「算哪一家、哪一天、几点」——和 {@code appointmentId} 放在同一个请求里说不通的那三项。
     *
     * <p>{@code transport} <b>不算</b>：它只属于 CANDIDATE。APPOINTMENT 走的是这条预约和用户资料里
     * 已经存着的交通方式（{@code travelGuides.forAppointment} 自己取），不接受这一次的
     * {@code transport} 覆盖——所以「APPOINTMENT + appointmentId + transport」不是混用，
     * 是正常的看地图请求多带了一个用不上的字段，忽略它就是了，不该为它把老人拦下来问一句。
     */
    private boolean hasRouteCandidateLocation(Map<String, String> arguments) {
        return blankToNull(arguments.get("hospital")) != null
                || blankToNull(arguments.get("date")) != null
                || blankToNull(arguments.get("time")) != null;
    }

    /**
     * 只估算一次候选路线的用时与距离。
     *
     * <p><b>这一轮只查不改、也不跳页面。</b>不写 {@code state.travelPlan}、不动草稿的任何字段，
     * 不生成 {@code UiDirective}——推荐轮里替老人「打开地图」等于把他从比较的半路上带走，
     * 而且那条指令是给某一次真实预约用的，候选医院根本没有预约可打开。
     *
     * <p>路线来自模拟数据，措辞里继续保留「模拟」二字；查不到就如实说查不到，
     * 不拿别的交通方式或别家医院的用时顶上——「大概半小时吧」是最容易被写进推荐理由的那种编造。
     */
    private AgentTurnResponse routeEstimateReadOnly(ConversationState state, Map<String, String> arguments) {
        String hospitalId = state.hospitalId;
        String hospital = state.hospital;
        String explicitHospital = blankToNull(arguments.get("hospital"));
        if (explicitHospital != null) {
            // 用的还是办理流程那一份解析器，口径一致：对不上目录就直说对不上，不用草稿里的旧医院顶上。
            CatalogEntityResolver.Match match = entityResolver.hospital(explicitHospital,
                    hospitalCatalogTool.listHospitals(state.id));
            if (match.type() != CatalogEntityResolver.MatchType.EXACT) {
                return unresolvedHospitalReply(state, match);
            }
            hospitalId = match.only().id();
            hospital = match.only().name();
        }
        if (hospitalId == null) {
            return answerReadOnly(state, "要预估路上要多久，得先知道去哪家医院。请告诉我是哪一家。");
        }
        String target = hospital;

        String explicitDate = blankToNull(arguments.get("date"));
        LocalDate date = explicitDate == null ? state.date : ToolContract.parseDate(explicitDate);
        if (explicitDate != null && date == null) {
            return answerReadOnly(state, "我没看准您说的是哪一天（“" + explicitDate
                    + "”）。请再说一次，例如“9月19号”，或写成 2026-09-19 这样。");
        }
        String explicitTime = blankToNull(arguments.get("time"));
        LocalTime time = explicitTime == null ? draftTime(state) : ToolContract.parseTime(explicitTime);
        if (explicitTime != null && time == null) {
            return answerReadOnly(state, "我没看准您说的是几点（“" + explicitTime
                    + "”）。请按 09:30 这样的写法再说一次。");
        }
        if (date == null || time == null) {
            return answerReadOnly(state, "要预估到" + target + "路上要多久，还得知道是哪一天的几点。"
                    + "请告诉我复诊的日期和时间。");
        }
        String explicitTransport = blankToNull(arguments.get("transport"));
        String transport = explicitTransport != null ? explicitTransport : state.transport;
        if (transport == null) {
            return answerReadOnly(state, "要预估到" + target + "路上要多久，还得知道您打算怎么去"
                    + "（打车、公交还是步行）。");
        }
        String chosenTransport = transport;
        String chosenHospitalId = hospitalId;
        LocalDateTime at = LocalDateTime.of(date, time);
        RouteGuide route;
        try {
            route = callTool(state, "travel.routePlan",
                    Map.of("hospitalId", chosenHospitalId, "transport", chosenTransport, "appointmentAt", at),
                    () -> routeGuideTool.plan(state.id, state.userId, chosenHospitalId, at, chosenTransport));
        } catch (RuntimeException error) {
            // 没有这条路线就说没有，绝不按距离或经验编一个用时——推荐理由里的「大概半小时」
            // 一旦不是工具给的，老人据此选的时间就建立在假数据上。
            return answerReadOnly(state, "我没有查到从您登记的住址到" + target + "选择" + chosenTransport
                    + "的路线数据，所以没法给出预计用时。您可以换一种交通方式再问我。");
        }
        return answerReadOnly(state, "从您登记的住址到" + target + "，选择" + route.transport() + "预计约"
                + route.durationMinutes() + "分钟、" + distanceLabel(route.distanceMeters())
                + "。建议" + route.departureAt().format(TIME_LABEL) + "出发"
                + simulatedSuffix(route) + "。这一轮只是在帮您比较，没有改动手上的预约。");
    }

    /** 模拟路线必须自己说出来：推荐理由里的距离和时间是模拟数据，不能读成真实路况。 */
    private String simulatedSuffix(RouteGuide route) {
        return "SIMULATED".equalsIgnoreCase(String.valueOf(route.source())) ? "（模拟路线，供参考）" : "";
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
            LocalDateTime now = clock.now();
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
        LocalDateTime now = clock.now();
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

    private LocalTime parseIsoTime(String value) {
        try { return value == null || value.isBlank() ? null : LocalTime.parse(value); }
        catch (RuntimeException ignored) { return null; }
    }

    private String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private boolean notBlank(String value) { return value != null && !value.isBlank(); }

    private String blankToNull(String value) { return notBlank(value) ? value.trim() : null; }

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
            return respond(state, acknowledgement(facts, "如果这一天没有号，您接受附近几天的其他时间吗？"), List.of(
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
        // 代他人办理时这一步整段跳过，理由见 notifySettled：操作者本人就是家属，
        // 再问“通知家里谁”不仅多余，长辈名下没有登记联系人时还会把人卡死在这里。
        if (!notifySettled(state)) {
            if (state.notifyFamily == null) {
                state.stage = ConversationState.Stage.ASK_NOTIFY;
                return respond(state, "需要通知家属吗？", List.of(q("需要通知", "SET_NOTIFY", "true"), q("不用通知", "SET_NOTIFY", "false")));
            }
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
        if (state.date.isBefore(clock.today())) { resetAfterDate(state); return askDate(state, "这个日期已经过去，请重新选择复诊日期。"); }
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

    // ---------------------------------------------------------------- 号源查询的条件解析
    //
    // 号源查询与办理草稿是两件事。老人可以随口问一句「市二院下周三有号吗」，心里并不打算
    // 把手头正在办的那笔预约改掉。所以下面先把「这次到底要查哪家医院、哪个科室、哪一天」
    // 解析清楚：模型显式给的参数说了算，参数省略时才沿用会话里已经明确的条件；解析不出来
    // 就停下说清楚，绝不悄悄退回旧条件再装作回答了这次的问题。

    /**
     * 一次号源查询的实际条件。
     *
     * <p>刻意不是直接读 {@code state} 里那几个字段：它可能指向草稿之外的医院、科室或日期。
     */
    private record SlotQuery(String hospitalId, String hospital, String departmentId, String department,
                             LocalDate date) {
        /** 说给老人（和模型）听的那套条件。回复正文与工具留痕都用它，免得两边说的不是同一件事。 */
        String label() {
            return hospital + "·" + department + (date == null ? "" : "·" + date.format(DATE_LABEL));
        }
    }

    /**
     * 条件解析的结果：要么给出一套能拿去查的条件，要么给出一条「这次查不了、要说清楚」的回复。
     * 两者必有其一——不会既查不成又不说话，也不会悄悄退回旧条件接着查。
     */
    private record SlotQueryResolution(SlotQuery query, AgentTurnResponse stop) {
        static SlotQueryResolution of(SlotQuery query) { return new SlotQueryResolution(query, null); }

        static SlotQueryResolution stop(AgentTurnResponse response) {
            return new SlotQueryResolution(null, response);
        }
    }

    /**
     * 取这次规划里点名 {@code toolName} 那次调用的参数。
     *
     * <p>按名字找，不按位置取：单工具路径只有一次调用，批量路径里可能夹着别的工具，
     * 两条路必须解析出同一份参数，否则同一句话在两条路上会查出不同结果。
     */
    private Map<String, String> toolArguments(AgentRuntime.Outcome outcome, String toolName) {
        if (outcome.proposedTools() == null) return Map.of();
        return outcome.proposedTools().stream()
                .filter(call -> toolName.equals(call.toolName()))
                .map(PlannerToolCall::arguments)
                .findFirst().orElse(Map.of());
    }

    /**
     * 把这次号源查询的条件定下来。
     *
     * <p>口径只有一条：<b>模型显式给的参数说了算，参数省略时才沿用会话里已经明确的条件</b>。
     * 显式参数解析不出来（日期没看准、医院或科室对不上、不唯一）时就地停下说清楚，
     * 绝不退回草稿的旧条件再装作回答了这次问题——那会答非所问，而且从回复里看不出来。
     *
     * <p>显式参数只决定「这次查什么」，不写回草稿。要落到草稿上，得由老人下一步明确表达。
     */
    private SlotQueryResolution resolveSlotQuery(ConversationState state, Map<String, String> arguments,
                                                 boolean requireDate) {
        String hospitalId = state.hospitalId;
        String hospital = state.hospital;
        String explicitHospital = blankToNull(arguments.get("hospital"));
        if (explicitHospital != null) {
            CatalogEntityResolver.Match match = entityResolver.hospital(explicitHospital,
                    hospitalCatalogTool.listHospitals(state.id));
            if (match.type() != CatalogEntityResolver.MatchType.EXACT) {
                return SlotQueryResolution.stop(unresolvedHospitalReply(state, match));
            }
            hospitalId = match.only().id();
            hospital = match.only().name();
        }

        String departmentId = state.departmentId;
        String department = state.department;
        String explicitDepartment = blankToNull(arguments.get("department"));
        if (explicitDepartment != null) {
            if (hospitalId == null) {
                return SlotQueryResolution.stop(respond(state,
                        "要查科室的号源，得先知道是哪家医院。请告诉我要去哪家医院。", resumeReplies(state)));
            }
            CatalogEntityResolver.Match match = entityResolver.department(explicitDepartment,
                    departmentCatalogTool.listDepartments(state.id, hospitalId));
            if (match.type() != CatalogEntityResolver.MatchType.EXACT) {
                return SlotQueryResolution.stop(unresolvedDepartmentReply(state, hospital, match));
            }
            departmentId = match.only().id();
            department = match.only().name();
        }

        LocalDate date = state.date;
        String explicitDate = blankToNull(arguments.get("date"));
        if (explicitDate != null) {
            // 用契约那一份解析器：模型写 2026-9-12 这种没补零的写法，契约认，这里就得认，
            // 不能因为两条路各有一套宽松度而把一次合规的调用判成说不清。
            date = ToolContract.parseDate(explicitDate);
            if (date == null) {
                // 「给了但看不懂」和「没给」是两件事：前者不能退回去用旧日期，那等于把
                // 老人问的那天换成了另一天还照答不误。
                return SlotQueryResolution.stop(respond(state,
                        "我没看准您说的是哪一天（“" + explicitDate + "”）。请再说一次，"
                                + "例如“9月19号”，或写成 2026-09-19 这样。", resumeReplies(state)));
            }
        }
        if (date != null && date.isBefore(clock.today())) {
            return SlotQueryResolution.stop(respond(state,
                    date.format(DATE_LABEL) + "已经过去了，我没有拿它去查号。请告诉我要查哪一天。",
                    resumeReplies(state)));
        }
        if (hospitalId == null) {
            return SlotQueryResolution.stop(respond(state, "要查询号源，请先告诉我想去哪家医院。",
                    resumeReplies(state)));
        }
        if (department == null) {
            return SlotQueryResolution.stop(respond(state, "要查询号源，还需要先选择复诊科室。",
                    resumeReplies(state)));
        }
        if (date == null && requireDate) {
            return SlotQueryResolution.stop(respond(state,
                    "要找附近日期的号源，得先知道以哪一天为准。请先告诉我想查哪一天。", resumeReplies(state)));
        }
        return SlotQueryResolution.of(new SlotQuery(hospitalId, hospital, departmentId, department, date));
    }

    /**
     * 显式医院对不上目录时的回话。
     *
     * <p>用的还是办理流程那一份 {@link CatalogEntityResolver}，口径一致；区别在于这里是查询，
     * 所以只解释清楚，不改草稿、也不摆「是这家医院吗」的确认按钮——那不是在问这次的事。
     */
    private AgentTurnResponse unresolvedHospitalReply(ConversationState state,
                                                      CatalogEntityResolver.Match match) {
        if (match.type() == CatalogEntityResolver.MatchType.AMBIGUOUS) {
            String names = match.candidates().stream().map(CatalogEntityResolver.Candidate::name)
                    .collect(java.util.stream.Collectors.joining("、"));
            return respond(state, "您说的“" + match.raw() + "”对上了不止一家医院：" + names
                    + "。我没替您挑，请说全名或院区，我再查号源。", resumeReplies(state));
        }
        if (match.type() == CatalogEntityResolver.MatchType.UNIQUE_APPROXIMATE) {
            return respond(state, "您说的“" + match.raw() + "”我理解成“" + match.only().name()
                    + "”，但没敢直接拿它去查号。请说全名或院区确认一下，我再查。", resumeReplies(state));
        }
        return respond(state, "我没在可办理的医院里找到“" + match.raw() + "”，所以没有拿别的医院替它去查。"
                + "请换一个医院名称说，或联系人工确认。", resumeReplies(state));
    }

    /** 显式科室对不上目录时的回话；口径同上。 */
    private AgentTurnResponse unresolvedDepartmentReply(ConversationState state, String hospital,
                                                        CatalogEntityResolver.Match match) {
        if (match.type() == CatalogEntityResolver.MatchType.AMBIGUOUS) {
            String names = match.candidates().stream().map(CatalogEntityResolver.Candidate::name)
                    .collect(java.util.stream.Collectors.joining("、"));
            return respond(state, hospital + "的“" + match.raw() + "”对上了不止一个科室：" + names
                    + "。我不能替您猜，请说完整科室名称，我再查号源。", resumeReplies(state));
        }
        if (match.type() == CatalogEntityResolver.MatchType.UNIQUE_APPROXIMATE) {
            return respond(state, hospital + "的“" + match.raw() + "”我理解成“" + match.only().name()
                    + "”，但没敢直接拿它去查号。请说完整科室名称确认一下，我再查。", resumeReplies(state));
        }
        return respond(state, "我没在" + hospital + "找到与“" + match.raw() + "”对应的科室，"
                + "所以没有拿别的科室替它去查。请按医生安排重新说科室名称。", resumeReplies(state));
    }

    /**
     * {@code appointment.querySlots} 的入口。
     *
     * <p>分界只有一条——<b>这一轮是不是模型发起的</b>，与参数给没给无关：
     * <ul>
     *   <li>模型发起的调用：一律按只读查询处理，<b>参数为空也一样</b>。缺的项回草稿里取
     *       （见 {@link #resolveSlotQuery}），够用就查、不够就问；不推进阶段、不动确认卡。
     *       要按这次结果改预约，等老人下一句明确说。</li>
     *   <li>不经过模型的按钮/规则流程：走既有办理流程，行为与加这一层之前逐字相同。</li>
     * </ul>
     */
    private AgentTurnResponse querySlotsFor(ConversationState state, ExtractedFacts facts,
                                            AgentRuntime.Outcome outcome) {
        if (!outcome.modelDriven()) {
            // 老人按按钮或规则规划器走到这一步：他明确说了什么就落什么，照旧进办理流程。
            if (facts.date() != null) applyFacts(state, facts);
            else clearSlotSelection(state, true);
            return showAvailableSlots(state);
        }
        // arguments 为空也走只读出口：缺的条件从草稿里取，取不到就追问，草稿本身不动。
        Map<String, String> arguments = toolArguments(outcome, "appointment.querySlots");
        SlotQueryResolution resolution = resolveSlotQuery(state, arguments, false);
        if (resolution.stop() != null) return resolution.stop();
        return answerSlotQueryReadOnly(state, resolution.query(), false);
    }

    /** {@code appointment.queryNearbySlots} 的入口；分界规则与 {@link #querySlotsFor} 相同。 */
    private AgentTurnResponse nearbySlotsFor(ConversationState state, AgentRuntime.Outcome outcome) {
        if (!outcome.modelDriven()) return queryNearbySlots(state);
        Map<String, String> arguments = toolArguments(outcome, "appointment.queryNearbySlots");
        SlotQueryResolution resolution = resolveSlotQuery(state, arguments, true);
        if (resolution.stop() != null) return resolution.stop();
        return answerSlotQueryReadOnly(state, resolution.query(), true);
    }

    /**
     * 只回答这一次的号源查询。
     *
     * <p>不推进阶段、不写号源候选、不动「接不接受附近日期」的意愿，更不碰确认卡——老人问的是
     * 另一家医院或另一天的号，不构成改动手头那笔预约的授权。回复里点名这次实际查的是哪家医院、
     * 哪个科室、哪一天，模型据此知道究竟查了什么，不会把别处的号源当成他问的那一处；查到之后
     * 只问一句要不要按它来，改不改由老人的下一句话决定。
     */
    private AgentTurnResponse answerSlotQueryReadOnly(ConversationState state, SlotQuery query,
                                                      boolean nearby) {
        if (query.date() == null) {
            // 要查的是草稿之外的医院或科室，可这一天还没定下来——不能拿旧日期替它查。
            return answerReadOnly(state, "我还没听清您想问哪一天的号源。请告诉我想查哪一天，我再查。");
        }
        if (nearby) {
            List<Slot> slots = callTool(state, "appointment.queryAlternatives",
                    Map.of("hospitalId", query.hospitalId(), "department", query.department(), "date", query.date()),
                    () -> appointmentTool.queryAlternatives(
                            state.id, query.hospitalId(), query.department(), query.date()));
            if (slots.isEmpty()) {
                return answerReadOnly(state, "我查了" + query.label() + "，" + query.date().format(DATE_LABEL)
                        + "没有号，之后三天也没有可预约时段。您可以换日期、换医院，或稍后再查。");
            }
            return answerReadOnly(state, "我查了" + query.label() + "，" + query.date().format(DATE_LABEL)
                    + "没有号。附近日期目前可以预约的时间：" + slotText(slots, 4) + "。要按这个来吗？");
        }
        List<Slot> slots = callTool(state, "appointment.querySlots",
                Map.of("hospitalId", query.hospitalId(), "department", query.department(), "date", query.date()),
                () -> appointmentTool.queryAvailableSlots(
                        state.id, query.hospitalId(), query.department(), query.date()));
        if (slots.isEmpty()) {
            return answerReadOnly(state, "我查了" + query.label() + "，" + query.date().format(DATE_LABEL)
                    + "没有可预约时段。您可以换一天、换医院，或稍后再查。");
        }
        // 查到只是查到：这一轮不替老人把预约改过去，只问一句要不要按它来。
        return answerReadOnly(state, "我查了" + query.label() + "，可以预约的时间：" + slotText(slots, 4)
                + "。要按这个来吗？");
    }

    /**
     * 只读答案的出口：正等确认时，把老人手上那张卡原样带上。
     *
     * <p>带上卡不只是为了不丢按钮——确认卡是业务终点，这一轮的措辞就此固定，不再交给模型润色。
     * 否则模型可能对着「正在等确认」的状态说出一句「好的，我这就帮您约上」，而屏幕上并没有发生
     * 任何确认；这句话既不是真实动作，也和他手上的卡对不上。
     */
    private AgentTurnResponse answerReadOnly(ConversationState state, String reply) {
        List<QuickReply> replies = resumeReplies(state);
        ConfirmationCard pending = confirmations.stillValidCard(state);
        return pending == null
                ? respond(state, reply, replies)
                : respondWithoutModel(state, reply, replies, null, null, pending);
    }

    /** 把真实号源摆成一句话。查询与办理两条路共用，免得同一批号源在两边说得不一样。 */
    private String slotText(List<Slot> slots, int limit) {
        return slots.stream().limit(limit).map(this::slotLabel)
                .collect(java.util.stream.Collectors.joining("；"));
    }

    private AgentTurnResponse queryNearbySlots(ConversationState state) {
        if (state.hospitalId == null || state.department == null || state.date == null) {
            return validateDraftForModel(state);
        }
        // 这里刻意不写 state.acceptAlternative：查一次附近日期不等于老人答应了「可以换日期」。
        // 意愿只有他本人说了（或点了「可以换日期」）才算数，办理流程问到这一步时照旧会问。
        state.stage = ConversationState.Stage.NO_SLOT;
        state.alternatives = callTool(state, "appointment.queryAlternatives",
                Map.of("hospitalId", state.hospitalId, "department", state.department, "date", state.date),
                () -> appointmentTool.queryAlternatives(state.id, state.hospitalId, state.department, state.date));
        if (state.alternatives.isEmpty()) {
            return respondWithPlan(state, state.date.format(DATE_LABEL)
                            + "没有号，接下来三天也没有查到可预约时段。您可以换日期、换医院或稍后再查。",
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
        if (state.date != null && !state.date.isBefore(clock.today())
                && !state.date.isAfter(clock.today().plusMonths(1))) {
            return querySlots(state);
        }

        LocalDate from = clock.today();
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

    /**
     * {@code schedule.checkConflict} 的入口。
     *
     * <p>分界与号源查询是同一把尺子——<b>这一轮是不是模型发起的</b>：
     * <ul>
     *   <li>模型发起的调用：按只读查询处理（见 {@link #checkConflictReadOnly}）。模型问的是
     *       「这个时间冲不冲突」，不是「把草稿推进到冲突这一步」。</li>
     *   <li>不经过模型的按钮/规则流程：走既有办理流程，行为与加这一层之前逐字相同——
     *       它本来就有权推进阶段、记下冲突、摆候选号源。</li>
     * </ul>
     */
    private AgentTurnResponse checkSchedule(ConversationState state, AgentRuntime.Outcome outcome) {
        return outcome.modelDriven() ? checkConflictReadOnly(state, outcome) : checkSchedule(state);
    }

    /**
     * 只回答这一次的日程冲突查询。
     *
     * <p>推荐轮里模型要拿「哪个时间不冲突」当理由，就得能单独问一次时间，而这一问不能顺手
     * 把办理推着往前走。所以这里和号源只读出口（{@link #answerSlotQueryReadOnly}）同口径：
     * 不写 {@code stage}、不写 {@code conflicts}、不写 {@code alternatives}、不置
     * {@code scheduleChecked}，<b>也不经由 {@code checkDuplicate} 生成确认卡</b>。
     * 中间那几项看着像「顺便记一下」，但工具循环收口时的 {@code finalizeToolEvidence} 真的会
     * 落盘，于是「查一下时间」就变成了「替老人把预约往前推了一步」。
     *
     * <p>条件只用这一次调用给的 {@code date}/{@code time}，缺了才回草稿取；取不到就直说要哪一项，
     * 不拿别的时段的结论顶上。冲突一律点名说不冲突/有冲突——不能默默把冲突时段混在候选里，
     * 那正是「老人以为这个时间空着」的来源。
     */
    private AgentTurnResponse checkConflictReadOnly(ConversationState state, AgentRuntime.Outcome outcome) {
        Map<String, String> arguments = toolArguments(outcome, "schedule.checkConflict");
        String explicitDate = blankToNull(arguments.get("date"));
        LocalDate date = explicitDate == null ? state.date : ToolContract.parseDate(explicitDate);
        if (explicitDate != null && date == null) {
            // 「给了但看不懂」不退回去用旧日期：那是把老人问的那天换成另一天还照答不误。
            return answerReadOnly(state, "我没看准您说的是哪一天（“" + explicitDate
                    + "”）。请再说一次，例如“9月19号”，或写成 2026-09-19 这样。");
        }
        String explicitTime = blankToNull(arguments.get("time"));
        LocalTime time = explicitTime == null ? draftTime(state) : ToolContract.parseTime(explicitTime);
        if (explicitTime != null && time == null) {
            return answerReadOnly(state, "我没看准您说的是几点（“" + explicitTime
                    + "”）。请按 09:30 这样的写法再说一次。");
        }
        if (date == null || time == null) {
            return answerReadOnly(state, "要帮您看这个时间冲不冲突，得先知道是哪一天的几点。请告诉我日期和具体时间。");
        }
        LocalDateTime start = LocalDateTime.of(date, time);
        // 「正好等于此刻」也算已经过去，口径与号源查询（appointment_time > 当前时间）和
        // 确认闸门（{@link #slotAlreadyPassed}）一致：同一个整点，一边说还能约、另一边说过去了，
        // 老人就会拿到一个永远确认不成的时段。
        if (!start.isAfter(clock.now())) {
            return answerReadOnly(state, date.format(DATE_LABEL) + time.format(TIME_LABEL)
                    + "已经过去了，我没有拿它去查日程。请告诉我另一个时间。");
        }
        List<Conflict> conflicts = callTool(state, "schedule.checkConflict",
                Map.of("userId", state.userId, "start", start),
                () -> scheduleTool.findConflicts(state.id, state.userId, start, start.plusMinutes(APPOINTMENT_DURATION)));
        String when = date.format(DATE_LABEL) + " " + time.format(TIME_LABEL);
        if (conflicts.isEmpty()) {
            return answerReadOnly(state, when + "这个时间与您已有的日程不冲突。"
                    + "这一轮只是在帮您比较，没有改动手上的预约。");
        }
        // 冲突一定要点名说：它必须以「有冲突」的样子出现在候选里，不能被混进去当一个普通时段。
        String items = conflicts.stream().limit(CONFLICT_DISPLAY_LIMIT)
                .map(item -> item.title() + "（" + item.startAt() + " 至 " + item.endAt() + "）")
                .collect(java.util.stream.Collectors.joining("；"));
        return answerReadOnly(state, when + "与您已有的日程有冲突：" + items
                + "。换一个时间能避开它。要不要改由您说了算，我没有改动手上的预约。");
    }

    /** 草稿里已经定下来的时刻；没有就返回 null。只读查询拿它当兜底条件，不写回任何字段。 */
    private LocalTime draftTime(ConversationState state) {
        if (state.selectedSlot != null && state.selectedSlot.time() != null) return state.selectedSlot.time();
        return state.requestedTime;
    }

    private AgentTurnResponse checkSchedule(ConversationState state) {
        if (!ready(state)) return advance(state, ExtractedFacts.empty());
        LocalDateTime start = LocalDateTime.of(state.selectedSlot.date(), state.selectedSlot.time());
        List<Conflict> conflicts = callTool(state, "schedule.checkConflict", Map.of("userId", state.userId, "start", start),
                () -> scheduleTool.findConflicts(state.id, state.userId, start, start.plusMinutes(APPOINTMENT_DURATION)));
        if (!conflicts.isEmpty()) {
            state.stage = ConversationState.Stage.CONFLICT;
            // 记住冲突本身：用户选「仍保留这个时间」后，确认卡要把它列出来，这是最后一道防线。
            state.conflicts = conflicts;
            List<Slot> sameDay = appointmentTool.queryAvailableSlots(state.id, state.hospitalId, state.department, state.date)
                    .stream().filter(item -> !item.id().equals(state.selectedSlot.id())).toList();
            state.alternatives = sameDay;
            // 当天候选只给一个：加上「重新选择日期」「仍保留这个时间」正好三个，一屏放得下。
            // 给两个的话「仍保留这个时间」会被挤到第二页，老人根本翻不到。
            List<QuickReply> choices = new ArrayList<>(slotReplies(sameDay.stream().limit(1).toList()));
            choices.add(q("重新选择日期", "CHANGE_DATE", ""));
            choices.add(q("仍保留这个时间", "KEEP_CONFLICT", ""));
            return respondWithPlan(state, "这个时间与您的“" + conflicts.get(0).title() + "（" + conflicts.get(0).startAt() + " 至 " + conflicts.get(0).endAt() + "）" +
                    "”冲突。您可以选择其他号源，也可以明确保留当前时间。", choices);
        }
        // 检查通过了就把上一次的冲突清掉：改完时间再回来，确认卡上不能再挂着已经解决的冲突。
        state.conflicts = List.of();
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
        // 选号那一刻可能就在今天早上，走到这一步已经是下午：先把已经过去的时段挡在外面，
        // 不能让一张写着「今天 09:00」的确认卡发到老人手上。
        if (slotAlreadyPassed(state.selectedSlot)) return reaskAfterPassedSlot(state);
        LocalDateTime at = LocalDateTime.of(state.selectedSlot.date(), state.selectedSlot.time());
        if (state.travelPlan == null) {
            state.travelPlan = callTool(state, "travel.plan", Map.of("hospital", state.hospital, "appointmentAt", at, "transport", state.transport),
                    () -> travelTool.plan(state.id, state.userId, state.hospital, at, state.transport));
        }
        // 重述待确认内容（例如用户在确认卡前说“打开地图”）时保留原确认凭据，
        // 避免确认卡看似被刷新、旧按钮突然失效。
        // 但保留的前提是「手上那份就是这张卡」：类型得是开新预约、目标得是同一批。这里是
        // booking 卡，若手上那份是上一轮那张取消卡的凭据（类型不同），这里会换一把新钥匙，
        // 旧凭据当场作废——否则老人对着这张「确认办理」点头，执行出来的是上一轮那次取消。
        // 手上那份读不出来（旧快照）时同样换新的，否则它会一路带到确认那一步才作废。
        //
        // 「谁在办」也进类型：本人自办与代他人办理是两条不同的业务链路（后者要写代约归属、
        // 通知其他照护者），签发时就得定下来是哪一个。合并成一个类型、执行时再看 caregiving()，
        // 就等于让一个可变状态决定这次确认要动谁的业务。
        confirmations.ensureIssued(state,
                state.caregiving()
                        ? ConfirmationService.PendingOperation.Kind.BOOKING_CAREGIVER
                        : ConfirmationService.PendingOperation.Kind.BOOKING,
                List.of());
        state.stage = ConversationState.Stage.AWAITING_CONFIRMATION;
        state.taskStatus = ConversationState.TaskStatus.AWAITING_CONFIRMATION;
        ConfirmationCard card = confirmationCard(state, at);
        String narration = confirmationNarration(state);
        // 确认摘要中的时间、地点、陪同、材料和通知对象均来自 Java 权威状态，
        // 不再交给回答模型压缩或改写；reply 与 speechText 使用同一份内容。
        return finishWithoutModel(state, new AgentTurnResponse(state.id, state.stage.name(), narration,
                List.of(), plan(state), card, null, traces.findByConversation(state.id), null, narration, null));
    }

    /**
     * 复诊确认卡的逐条内容，全部由权威状态算出。
     *
     * <p>抽出来是为了让「越界回答」也能把同一张卡原样带回去（见 {@link #pendingConfirmation}）：
     * 卡片内容必须只有一处来源，否则两边一旦不一致，老人看到的和真正会执行的就是两回事。
     */
    private ConfirmationCard confirmationCard(ConversationState state, LocalDateTime at) {
        List<String> operations = new ArrayList<>();
        // 代他人办理时第一行必须说清“替谁办”：这是按确认之前唯一的关口，
        // 说错对象就等于替错人动了别人的预约和提醒。
        if (state.caregiving()) {
            operations.add("服务对象：" + elderName(state.userId) + "（您以"
                    + (state.relationLabel == null ? "照护者" : state.relationLabel) + "身份代为办理）");
        }
        operations.add("医院科室：" + state.hospital + " · " + state.department);
        operations.add("复诊时间：" + slotLabel(state.selectedSlot));
        // 用户选择保留冲突时把冲突本身写进确认卡：这是提交前最后一次提醒，
        // 也是家属通知和审计回头能看到的证据。
        for (Conflict conflict : state.conflicts) {
            operations.add("已知冲突：与“" + conflict.title() + "”（" + conflict.startAt() + " 至 "
                    + conflict.endAt() + "）时间重叠，您已选择保留");
        }
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
        if (state.caregiving()) {
            // 代办的提醒与通知由 CareBookingService 统一落地，不再列一遍老人端那套“通知家属”，
            // 否则确认卡上会写着一件不会按那个方式发生的事。
            operations.add("代约归属：这份预约会记为由您代约，并通知其他照护者");
            operations.add("为" + elderName(state.userId) + "创建复诊提醒，其助手开场时会主动告知");
        } else if (Boolean.TRUE.equals(state.notifyFamily) && !state.notificationDone) {
            operations.add("通知" + state.contact.relationship() + " " + state.contact.name() + "：" + notificationMessage(state));
        } else {
            operations.add(state.notificationDone ? "家属已通知，不重复发送" : "不通知家属");
        }
        return new ConfirmationCard("请确认复诊办理计划", operations,
                "确认后按以上内容更新模拟预约、提醒及通知。原已发送消息不能撤回。", "确认办理", "返回修改", state.confirmationId);
    }

    private String confirmationNarration(ConversationState state) {
        StringBuilder text = new StringBuilder(state.caregiving()
                ? "请确认本次为" + elderName(state.userId) + "办理的复诊安排。" : "请确认本次复诊安排。");
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
        // 这一段必须和确认卡上的口径一致：卡片写“会通知其他照护者”，口播就不能说“不会通知家属”。
        if (state.caregiving()) {
            text.append("这份预约会记为由您代约，并通知其他照护者。");
        } else if (Boolean.TRUE.equals(state.notifyFamily) && state.contact != null) {
            text.append("并通知").append(state.contact.relationship()).append(state.contact.name()).append("。");
        } else {
            text.append("不会通知家属。");
        }
        text.append("如果这些信息正确，请说“确认办理”；需要修改，请说“返回修改”。");
        return text.toString();
    }

    /** 不带计划卡的纯文本回复（用于“家属代约计划”等已有预约的开场与管理话术）。 */
    @Override
    AgentTurnResponse respondSimple(ConversationState state, String reply, List<QuickReply> quickReplies) {
        return finish(state, AgentTurnResponse.message(state.id, state.stage.name(), reply, quickReplies));
    }

    /** 当前用户由家属/志愿者代约且仍在进行中的复诊计划。 */
    private java.util.Optional<AppointmentRecordStore.AppointmentView> upcomingArranged(ConversationState state) {
        return appointmentRecords.allFor(state.userId).stream()
                .filter(row -> "CONFIRMED".equals(row.status()))
                .filter(row -> !row.date().isBefore(clock.today()))
                .filter(row -> row.arrangedBy() != null)
                .findFirst();
    }

    /** 长辈名下进行中、但不是照护者代约的那一份（老人自己约的）。 */
    private java.util.Optional<AppointmentRecordStore.AppointmentView> upcomingOwn(ConversationState state) {
        return appointmentRecords.allFor(state.userId).stream()
                .filter(row -> "CONFIRMED".equals(row.status()))
                .filter(row -> !row.date().isBefore(clock.today()))
                .filter(row -> row.arrangedBy() == null)
                .findFirst();
    }

    @Override
    String managedShort(AppointmentRecordStore.AppointmentView plan) {
        return plan.date().format(DATE_LABEL) + " " + plan.time().format(TIME_LABEL)
                + "，" + plan.hospital() + " " + plan.department();
    }

    private String managedGreeting(AppointmentRecordStore.AppointmentView plan, String userName) {
        return "您好，" + userName + "。您目前有一份由" + plan.arrangedLabel() + "帮您约好的复诊安排："
                + managedShort(plan) + "。需要查看详情、临时改期或取消时，直接告诉我就可以；"
                + "遇到紧急情况也请告诉我，我会暂停普通办理并给出求助提示。";
    }

    /**
     * 家属/志愿者端的开场。不复用老人端那条“我要预约复诊”的漏斗：那个漏斗的主语是老人自己，
     * 直接套到代办的会话上，家属会以为是在给自己办。
     */
    private AgentTurnResponse caregiverGreeting(ConversationState state, String actorName, String subjectName) {
        String prefix = "您好，" + actorName + "。我是" + subjectName + "的复诊事务助手。"
                + "您可以问我" + subjectName + "的复诊安排，也可以查看要带的材料和最近的复诊动态。";
        AppointmentRecordStore.AppointmentView arranged = upcomingArranged(state).orElse(null);
        if (arranged != null) {
            return respondWithoutModel(state, prefix + "目前" + subjectName + "有一份进行中的安排："
                            + managedShort(arranged) + "。",
                    caregiverActions(subjectName));
        }
        // 本人自己约的那份也要说：一次只能有一份进行中的预约，不说清楚，
        // 操作者会一路填到确认卡才被拦下，白填一遍。
        AppointmentRecordStore.AppointmentView own = upcomingOwn(state).orElse(null);
        if (own != null) {
            return respondWithoutModel(state, prefix + "目前" + subjectName + "名下有一份进行中的复诊预约："
                            + managedShort(own) + "。要再安排一次的话，需要先调整或取消这一份。",
                    caregiverActions(subjectName));
        }
        return respondWithoutModel(state, prefix + "目前" + subjectName + "还没有进行中的复诊预约。",
                caregiverActions(subjectName));
    }

    /** 照护者端开场的按钮。这里只放已经真正接上的入口，避免出现点了没反应的选择。 */
    private List<QuickReply> caregiverActions(String subjectName) {
        return List.of(
                q("帮" + subjectName + "预约复诊", "NEW_BOOKING", ""),
                q("查看" + subjectName + "的复诊安排", "QUERY_APPOINTMENTS", ""),
                q("最近的复诊动态", "QUERY_CARE_TIMELINE", ""),
                q("发给我的通知", "QUERY_CARE_NOTIFICATIONS", ""));
    }

    /** 代约完成后的按钮。 */
    @Override
    List<QuickReply> caregiverBookedActions(String subjectName) {
        return List.of(
                q("查看" + subjectName + "的复诊安排", "QUERY_APPOINTMENTS", ""),
                q("最近的复诊动态", "QUERY_CARE_TIMELINE", ""),
                q("发给我的通知", "QUERY_CARE_NOTIFICATIONS", ""));
    }

    /**
     * 家属/志愿者查看长辈的复诊动态。归属校验在 {@link CareService#timeline} 里，
     * 越权会直接抛错——这里兜成一句人话，不把异常抛给前端。
     */
    private AgentTurnResponse showCareTimeline(ConversationState state) {
        if (!state.caregiving()) {
            return respondWithoutModel(state, "查看复诊动态需要先指定一位长辈。",
                    List.of(q("查询我的预约", "QUERY_APPOINTMENTS", "")));
        }
        List<CareService.TimelineEvent> events;
        try {
            events = careService.timeline(state.actorUserId, state.userId);
        } catch (IllegalArgumentException error) {
            // 权限类消息一律不走模型润色：措辞被改软就等于把边界说模糊了。
            return respondWithoutModel(state, "现在看不了这位长辈的动态：" + error.getMessage(),
                    List.of(q("联系人工帮助", "CONTACT_HUMAN", "")));
        }
        String subject = elderName(state.userId);
        if (events.isEmpty()) {
            return respondWithoutModel(state, subject + "最近还没有复诊动态。", caregiverActions(subject));
        }
        String lines = events.stream().limit(6)
                .map(event -> "· " + event.title()
                        + (event.detail() == null || event.detail().isBlank() ? "" : "：" + event.detail())
                        + "（" + event.at().toLocalDate() + "）")
                .collect(java.util.stream.Collectors.joining("\n"));
        return respondWithoutModel(state, subject + "最近的复诊动态：\n" + lines, caregiverActions(subject));
    }

    /**
     * 家属/志愿者给长辈留一条提醒。落到长辈自己的备忘里，长辈打开助手就能看到。
     *
     * <p>文本开头必须写明是谁留的：不然长辈收到一条凭空出现的备忘，不知道是谁让做的，
     * 这正是“协同”最容易丢的一环。同时给操作者自己回一条协同通知，便于事后核对。
     */
    private AgentTurnResponse remindElder(ConversationState state, String value) {
        if (!state.caregiving()) {
            return respondWithoutModel(state, "想记提醒的话，直接说“提醒我几点做什么”就行。",
                    List.of(q("记一条提醒", "CONTINUE", "")));
        }
        String subject = elderName(state.userId);
        MemoParser.MemoIntent intent = MemoParser.detect(value, clock.now());
        String text = intent == null || intent.text() == null || intent.text().isBlank()
                ? value.trim() : intent.text().trim();
        if (text.isBlank()) {
            return respondWithoutModel(state,
                    "您想提醒" + subject + "做什么？比如“提醒" + subject + "明天上午带身份证”。",
                    caregiverActions(subject));
        }
        // 一句话里说了不止一个时间点：跟老人端同一套判据、同一个理由（见 askOneThingAtATime）。
        // 这里更要说清“还没留”——家属听到“好的”会以为已经留好了，长辈那边其实什么都没有。
        if (MemoParser.severalMomentsInOneSentence(value)) {
            return respondWithoutModel(state, "您这句话里像是说了不止一件事（或不止一个时间），我怕记串了，"
                            + "先没有给" + subject + "留。请您一件一件说，"
                            + "比如“提醒" + subject + "明天上午带身份证”。",
                    caregiverActions(subject));
        }
        // 缺日子/缺钟点/缺重复锚点：先问，不许写。
        // 以前这几种是直接落库的：说“每周提醒我妈量血压”，存的是一条没有提醒时间的长期备忘——
        // 家属听到的是“已经给王阿姨留好提醒”，长辈那边到哪天都不响，还说不出哪里错了。
        // 追问共用老人端那套话术（同一套“您想哪天/几点”），家属端不出确认卡这条在 finishMemoAnswer 里兜住。
        if (intent != null && intent.repeatDayGap() != null) return askMemoRepeatDay(state, intent);
        if (intent != null && intent.needsDay()) return askMemoDay(state, intent);
        if (intent != null && intent.needsTime()) return askMemoTime(state, intent);
        return commitElderRemind(state, text, remindAts(intent), intent == null ? null : intent.repeatRule());
    }

    /** 备忘里那几个到点时间（去掉空值、去重、按时间排）。 */
    private List<LocalDateTime> remindAts(MemoParser.MemoIntent intent) {
        return intent == null ? List.of()
                : intent.remindAts().stream().filter(at -> at != null).distinct().sorted().toList();
    }

    /**
     * 家属/志愿者给长辈留提醒的<b>唯一落库口</b>——直写、补答日子后、补答钟点后都走这里。
     *
     * <p>四件事只在这一处做，分开写迟早走散：
     * <ul>
     *   <li><b>称呼摘掉</b>：“提醒我<b>妈</b>带身份证”里的“我妈”是他在叫谁，不是事项
     *       （见 {@link MemoParser#stripElderAddress}）——留着的话长辈看到的备忘是“妈带身份证”。</li>
     *   <li><b>抬头</b>：正文写成“「小丽」提醒：带身份证”。长辈收到一条凭空出现的备忘，不知道是谁让做的，
     *       这正是“协同”最容易丢的一环。</li>
     *   <li><b>一条一天、每条只说自己那天</b>：只留第一天，长辈会以为后面几天也设好了，到点却不响；
     *       而多天原句整段抄进每条（“下周周一周二周三量血压”）会让周三那条说自己周一。
     *       逐天改写与老人端同一条路（{@code textForDay} + {@code stripSchedule}）。</li>
     *   <li><b>通知 + 回读</b>：回读用“9月22日（周二）08:00”这种中文写法，不贴 {@code LocalDate.toString()}
     *       出来的生日期——家属要照着这句话核对，念不出来就等于没回读。</li>
     * </ul>
     */
    private AgentTurnResponse commitElderRemind(ConversationState state, String text, List<LocalDateTime> ats,
                                               String repeatRule) {
        String subject = elderName(state.userId);
        String repeat = MemoStore.normalizeRepeat(repeatRule);
        String prefix = "「" + (state.relationLabel == null ? "照护者" : state.relationLabel)
                + " " + elderName(state.actorUserId) + "」提醒：";
        // “提醒我妈…”里的“我妈”是他在叫谁，不是事项：留在正文里，长辈看到的是“妈带身份证”。
        // 摘掉之后再落库、再回读，家属听到的这句话才和长辈在首页看到的是同一句
        String memoText = MemoParser.stripElderAddress(text);
        List<LocalDateTime> times = ats == null ? List.of() : ats;
        if (times.isEmpty()) {
            // 没有到点时间的长期备忘不能摘正文：“下周家人来接我”里的时间是内容本身，不是提醒
            memoTool.create(state.id, state.userId, prefix + memoText, null, repeat);
        } else {
            for (LocalDateTime at : times) {
                memoTool.create(state.id, state.userId,
                        prefix + MemoParser.stripSchedule(
                                MemoParser.textForDay(memoText, at.toLocalDate(), clock.today())),
                        at, repeat);
            }
        }
        // 回读用老人端那一套（“9月22日（周二） 08:00”／重复的念“每周三 08:00”），
        // 不贴 LocalDate.toString() 出来的生日期：家属要照着这句话核对
        String when = times.isEmpty() ? "（长期备忘，不到点提醒）"
                : "，到" + String.join("、",
                        times.stream().map(at -> memoMomentLabel(at, repeat)).toList()) + "会提醒";
        careBooking.notifyCaregiver(state.actorUserId, state.userId,
                "已给" + subject + "留提醒：" + memoText + when, "info");
        return respondWithoutModel(state, "已经给" + subject + "留好提醒：" + memoText + when
                        + "。" + subject + "打开助手就能在备忘里看到，上面写着是您留的。",
                caregiverBookedActions(subject));
    }

    /** 发给当前照护者本人的协同通知（代约结果、老人改动、求助等）。 */
    private AgentTurnResponse showCareNotifications(ConversationState state) {
        if (!state.caregiving()) {
            return respondWithoutModel(state, "协同通知只在协助长辈时才有。",
                    List.of(q("查询我的预约", "QUERY_APPOINTMENTS", "")));
        }
        List<CareService.NotificationView> items = careService.notifications(state.actorUserId);
        if (items.isEmpty()) {
            return respondWithoutModel(state, "目前没有发给您的协同通知。",
                    caregiverActions(elderName(state.userId)));
        }
        String lines = items.stream().limit(6)
                .map(item -> "· " + item.content() + "（" + item.sentAt().toLocalDate() + "）")
                .collect(java.util.stream.Collectors.joining("\n"));
        return respondWithoutModel(state, "发给您的协同通知：\n" + lines,
                caregiverActions(elderName(state.userId)));
    }

    /**
     * 预约历史事实：把库里真实发生过的记录摆给模型看。
     *
     * <p><b>只说「预约过」。</b>Java 这边产出的是纯事实标签——「已预约（还没到日子）」
     * 「已预约（日子已经过了）」「已取消」——没有一个字说人去过医院。项目里没有可靠的
     * 到院/就诊完成事实，而「预约成功」和「人真的去了」是两件事：把前者说成后者，
     * 是这套功能最容易犯、后果也最重的错。措辞规则在提示词里再写一遍，这里保证没有可被
     * 误读成到院的词。
     *
     * <p><b>这一轮只查不改。</b>查询条件只决定这次查什么，不落到草稿上；出口走
     * {@link #answerReadOnly}，正等确认时把老人手上那张卡原样带回。老人真想改成这家，
     * 下一轮由他自己说了算，走正常业务动作。
     */
    private AgentTurnResponse showAppointmentHistory(ConversationState state, AgentRuntime.Outcome outcome) {
        Map<String, String> arguments = toolArguments(outcome, "appointment.history");
        ProfileQueryService.HistoryQuery query = new ProfileQueryService.HistoryQuery(
                arguments.get("hospital"), arguments.get("department"), arguments.get("status"),
                // 日期用的是契约那一份宽松解析（认 2026-9-12 这种写法），解析不出来就当没给这个条件——
                // 不另写一套格式判断，免得同一个参数在两处有两种解释。
                ToolContract.parseDate(arguments.get("from")), ToolContract.parseDate(arguments.get("to")));
        if (query.rangeReversed()) {
            // 反过来的范围是「你这两句话对不上」，不是「那段时间没有记录」。
            // 当成空结果回答，老人会以为自己那段真的没有预约——实际上他要的那段根本没被查过。
            // 所以：一条 SQL 都不跑、一条留痕都不落，也说清楚是这个范围本身不成立。
            return answerReadOnly(state, "您说的开始日期 " + query.from() + " 晚于结束日期 " + query.to()
                    + "，这两个日期是反的，我没法按这个范围查。请重新说一下要查哪一段时间。");
        }
        ProfileQueryService.History history;
        try {
            history = profileQuery.history(state.actorUserId, state.actorRole, state.userId, query);
        } catch (IllegalArgumentException error) {
            // 权限类消息一律不走模型润色：措辞被改软就等于把边界说模糊了。
            // 也不清确认卡——查不了是权限问题，和他手上那张卡没有任何关系。
            return answerReadOnly(state, "现在查不了这位老人的预约记录：" + error.getMessage() + "。");
        }
        traces.record(state.id, "appointment.history",
                Map.of("userId", state.userId, "arguments", arguments), historyTrace(history), true);
        return answerReadOnly(state, appointmentHistoryText(state, history));
    }

    /**
     * 长期记忆摘要：明确偏好与「系统从预约里总结出来的」分开说。
     *
     * <p>把两者混成一摞「用户偏好」，模型就会拿一条系统自己归纳的「常去的医院是市一院」
     * 当成老人亲口说过的要求去劝他，而老人从没这么说过。来源和更新时间原样带出来，
     * 让读的人能自己判断这条还算不算数。
     */
    private AgentTurnResponse showProfileMemory(ConversationState state) {
        ProfileQueryService.MemorySummary summary;
        try {
            summary = profileQuery.memorySummary(state.actorUserId, state.actorRole, state.userId);
        } catch (IllegalArgumentException error) {
            return answerReadOnly(state, "现在查不了这位老人的长期记忆：" + error.getMessage() + "。");
        }
        traces.record(state.id, "profile.memorySummary", Map.of("userId", state.userId),
                Map.of("explicitPreferences", summary.explicitPreferences().size(),
                        "bookingHistory", summary.bookingHistory().size(),
                        "unverifiableCount", summary.unverifiableCount()), true);
        return answerReadOnly(state, profileMemoryText(state, summary));
    }

    /** 预约历史摆成给模型看的一段话。事实、统计、当轮要求三段各自标明来历，不揉成一句「您的偏好」。 */
    private String appointmentHistoryText(ConversationState state, ProfileQueryService.History history) {
        StringBuilder text = new StringBuilder(elderName(state.userId)).append("的预约记录：");
        if (history.total() == 0) {
            text.append("没有查到符合条件的记录。");
        } else {
            String facts = history.facts().stream()
                    .map(fact -> fact.date().format(DATE_LABEL) + " " + fact.time().format(TIME_LABEL)
                            + " " + fact.hospital() + " " + fact.department() + "（" + factLabel(fact.status()) + "）")
                    .collect(java.util.stream.Collectors.joining("；"));
            // 这里刻意不写「不代表已经到院」这类免责句：说了「到院」两个字，就等于把那个词
            // 放回了提示词。Java 只给纯事实标签，能不能说到院由提示词那条硬红线去管。
            text.append("共 ").append(history.total()).append(" 条，按日期从晚到早列出 ")
                    .append(history.facts().size()).append(" 条：").append(facts).append("。");
        }
        if (!history.tendencies().isEmpty()) {
            String items = history.tendencies().stream()
                    .map(item -> itemLabel(item.dimension()) + "是" + tendencyLabel(item)
                            + "（已确认预约中出现 " + item.count() + " 次）")
                    .collect(java.util.stream.Collectors.joining("；"));
            text.append(" 历史统计（只统计已确认的预约，是多次记录形成的统计，不是老人明确说过的偏好）：")
                    .append(items).append("。");
        } else if (history.confirmedTotal() == 1) {
            text.append(" 历史统计：只查到 1 次已确认的预约，还谈不上习惯。");
        }
        return text.append(currentDraftNote(state)).toString();
    }

    private String profileMemoryText(ConversationState state, ProfileQueryService.MemorySummary summary) {
        StringBuilder text = new StringBuilder(elderName(state.userId)).append("的长期记忆：");
        if (summary.isEmpty()) {
            text.append("没有查到记录。");
        } else {
            text.append("共 ").append(summary.explicitPreferences().size() + summary.bookingHistory().size())
                    .append(" 条。");
            // 明确偏好也不能说成「就是现在的偏好」：它是老人在**某个时间点**说过的话，
            // 说过之后可能改主意。带出更新时间、并要求时间较久或与当轮要求不一致时回头问一句，
            // 是这条记录能被安全使用的唯一前提——本项目没有任何「偏好自动过期」的可靠阈值，
            // 所以不发明天数，只把判断交回给这一轮的真实对话。
            appendMemories(text, "老人曾明确表达的偏好",
                    summary.explicitPreferences(),
                    "这些是老人曾明确表达的偏好，附带更新时间；如果时间较久或与当轮要求不一致，"
                            + "需要询问现在是否仍适用。");
            appendMemories(text, "系统根据已确认预约沉淀的历史事实",
                    summary.bookingHistory(),
                    "这些是系统自己归纳出来的历史事实，不是老人明确说过的偏好：只说明最近一次"
                            + "确认预约的是什么，不代表他以后还想这样，同样要按更新时间复核。");
            if (summary.unverifiableCount() > 0) {
                text.append("另有 ").append(summary.unverifiableCount())
                        .append(" 条来源不明的记录，我没有采用。");
            }
        }
        return text.append(currentDraftNote(state)).toString();
    }

    private void appendMemories(StringBuilder text, String title, List<MemoryStore.Memory> items,
                                String note) {
        if (items.isEmpty()) return;
        String lines = items.stream().limit(MEMORY_LIST_LIMIT)
                .map(memory -> "（" + memory.kind() + "）" + memory.content()
                        + "（来源 " + memory.source() + "，更新于 " + memory.updatedAt() + "）")
                .collect(java.util.stream.Collectors.joining("；"));
        text.append(" ").append(title).append("：").append(lines).append("。").append(note);
    }

    /**
     * 当轮要求：当前办理草稿里已经定下来的条件。
     *
     * <p>它不在库里，也不在记忆里——是这一轮对话里老人自己说的。摆出来的唯一目的是让模型
     * 明白优先级：当前要求压过明确偏好，明确偏好压过统计倾向。少了这一段，
     * 「上次去的那家还考虑吗」就可能在老人已经改口说「这次去市二院」之后还往回劝。
     */
    private String currentDraftNote(ConversationState state) {
        List<String> parts = new ArrayList<>();
        if (notBlank(state.hospital)) parts.add("医院=" + state.hospital);
        if (notBlank(state.department)) parts.add("科室=" + state.department);
        if (state.date != null) parts.add("日期=" + state.date.format(DATE_LABEL));
        if (state.selectedSlot != null && state.selectedSlot.time() != null) {
            parts.add("时间=" + state.selectedSlot.time().format(TIME_LABEL));
        } else if (state.requestedTime != null) {
            parts.add("时间=" + state.requestedTime.format(TIME_LABEL));
        } else if (notBlank(state.timePreference)) {
            parts.add("时段=" + ("MORNING".equals(state.timePreference) ? "上午" : "下午"));
        }
        if (parts.isEmpty()) {
            return " 本轮办理中还没有确定医院、科室、日期或时间；这些历史与统计只是候选，"
                    + "要不要用由老人自己决定。";
        }
        return " 本轮办理中已经明确的条件（优先级最高，历史与统计都不能覆盖它）："
                + String.join("、", parts) + "。";
    }

    /** 事实标签里没有一个字能读成「人已经去过了」。 */
    private String factLabel(ProfileQueryService.FactStatus status) {
        return switch (status) {
            case CONFIRMED_UPCOMING -> "已预约，还没到日子";
            case CONFIRMED_PAST -> "已预约，日子已经过了";
            case CANCELLED -> "已取消";
            case UNKNOWN -> "状态未知";
        };
    }

    private String itemLabel(ProfileQueryService.Tendency.Dimension dimension) {
        return switch (dimension) {
            case HOSPITAL -> "最常预约的医院";
            case DEPARTMENT -> "最常预约的科室";
            case PERIOD -> "预约时段偏";
        };
    }

    private String tendencyLabel(ProfileQueryService.Tendency tendency) {
        return switch (tendency.dimension()) {
            case HOSPITAL, DEPARTMENT -> tendency.value();
            case PERIOD -> "MORNING".equals(tendency.value()) ? "上午" : "下午";
        };
    }

    /** 工具留痕里只落结构化字段，不落整段话术：留痕是给排查看的，不该成为第二条返回通道。 */
    private Map<String, Object> historyTrace(ProfileQueryService.History history) {
        return Map.of("total", history.total(),
                "confirmedTotal", history.confirmedTotal(),
                "returned", history.facts().size(),
                "facts", history.facts().stream().map(fact -> Map.of(
                        "date", fact.date().toString(),
                        "time", fact.time().toString(),
                        "hospital", fact.hospital(),
                        "department", fact.department(),
                        "status", fact.status().name())).toList(),
                "tendencies", history.tendencies().stream().map(item -> Map.of(
                        "dimension", item.dimension().name(),
                        "value", item.value(),
                        "count", item.count())).toList());
    }

    @Override
    String elderName(String userId) {
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
        // 要取消的是哪一条，就在这一刻<b>写死</b>进凭据：`plan` 是查出来的那一条，卡片上写的也是它。
        // 以前这里传的是空列表，执行时再按「当前那份代约安排」现找一遍——多一套口径就多一次
        // 「卡上写 A、实际取消 B」的机会（这中间别人又代约了一条更早的，现找就会找错）。
        String confirmationId = confirmations.issue(state,
                ConfirmationService.PendingOperation.Kind.CANCEL_MANAGED,
                List.of(plan.appointmentId()));
        ConfirmationCard card = new ConfirmationCard(
                "确认取消这次由" + plan.arrangedLabel() + "约好的复诊吗？",
                List.of("医院科室：" + plan.hospital() + " · " + plan.department(),
                        "复诊时间：" + plan.date().format(DATE_LABEL) + " " + plan.time().format(TIME_LABEL),
                        "释放号源并停用关联提醒",
                        "取消后会自动通知安排者" + plan.arrangedLabel()),
                "确认后取消预约；返回则保留原安排。", "确认取消预约", "保留预约", confirmationId);
        return finish(state, new AgentTurnResponse(state.id, state.stage.name(),
                "取消这次安排需要明确确认。", List.of(), null, card, null,
                traces.findByConversation(state.id)));
    }

    /**
     * 模型判定为「健康备忘 / 健康数值 / 发周报」时，把这一轮交回 Java 的解析器填槽。
     *
     * <p>为什么不让模型直接给答复：这三件事都要落库或者对外发消息，写成什么样必须由确定性代码决定。
     * 模型给的是“这句话是这件事”这个判断，不是“已经办好了”这个结论——所以在模型说是、而 Java 却
     * 解析不出具体的项目/数值/时间时，这里返回 null 退回原链路，宁可多问一句，也不能回一句
     * 模棱两可的“好的”。（实测过：不接这条路由时，模型会对老人说“我给您记一个提醒”，而库里一条都没有。）
     *
     * <p>每一条分支的上下文闸门与回退模式里那一段关键词预检保持完全一致（备忘看 {@link #memoContext}，
     * 实测数值与发周报看 {@link #dailyHealthContext}），这样开关模型前后老人的得到的行为是一样的。
     */
    private AgentTurnResponse modelDailyRoute(AgentOrchestrator.Route route, ConversationState state, String value) {
        switch (route) {
            case MANAGE_MEMO -> {
                if (!memoContext(state)) return null;
                AgentTurnResponse command = memoCommandReply(state, value);
                return command != null ? command : memoReply(state, value);
            }
            case SEND_HEALTH_REPORT -> {
                if (!dailyHealthContext(state)) return null;
                HealthReportParser.ReportIntent report = HealthReportParser.detect(value);
                return report == null ? null : healthReportReply(state, report);
            }
            case RECORD_HEALTH_VALUE -> {
                // “把这个月的血压发给女儿”里也有“记录”字样，先让发周报认走，否则永远发不出去
                HealthReportParser.ReportIntent report = HealthReportParser.detect(value);
                if (report != null && dailyHealthContext(state)) return healthReportReply(state, report);
                HealthRecordParser.RecordIntent record = HealthRecordParser.detect(value);
                if (record == null) return null;
                // 回查历史数值不受上下文限制；要落一条新记录的仍然只在日常上下文里进行——
                // 例外是他手上那张健康记录卡还没点头时又报了一条新数（见 replacesPendingHealthRecord）
                if (record.kind() != HealthRecordParser.Kind.QUERY && !dailyHealthContext(state)
                        && !replacesPendingHealthRecord(state, record)) return null;
                return healthRecordReply(state, record, value);
            }
            default -> {
                return null;
            }
        }
    }

    /** 只有在能安全插入一条备忘的对话上下文中才识别备忘：约好待办/空闲开场/等待开始办理。 */
    private boolean memoContext(ConversationState state) {
        if (state.stage == ConversationState.Stage.AWAITING_CONFIRMATION) return false;
        return state.appointmentId != null
                || state.hospital == null
                || state.stage == ConversationState.Stage.READY_TO_PLAN;
    }

    /**
     * 能安全落地“记一条实测数值 / 把记录发给家属”的上下文。
     *
     * <p>这里刻意比 {@link #memoContext} 宽松。备忘是“要做的事”，得有上下文才谈得上时间；
     * 而实测数值是已经发生过的事实，写的是 health_records 这张跟复诊草稿毫无关系的表，
     * 唯一必须让路的只有那张正等老人点头的确认卡（那时“90”可能是对卡片的回答，不是一次测量）。
     *
     * <p>回归：原来这里借用了 {@code memoContext}，而它会在草稿有医院、没有 appointmentId 时为假。
     * 模型把上一句闲聊里的东西写进草稿（例如凭空落一个就诊医院）之后，“我今天走了6000步”
     * 和“我低压95”就被静默吞掉，只有一句“请告诉我就诊医院”——老人以为记上了，库里一条没有。
     * 确定性的数值不该被一条模型自己填的草稿挡住。
     *
     * <p>急救暂停时也不抢话：那时先让老人处理紧急情况，和备忘同一个道理。
     */
    private boolean dailyHealthContext(ConversationState state) {
        if (state.stage == ConversationState.Stage.EMERGENCY_PAUSED) return false;
        return state.stage != ConversationState.Stage.AWAITING_CONFIRMATION;
    }

    /**
     * 手上那张健康记录卡还没点头，他这一句又报出了一条实测数值。
     *
     * <p>为什么要认这一句：不认就等于他报的这个数被静默吞掉——回复变成一句“这句话我还没听准”，
     * 而他以为已经说过了。数值是已经量到的事实，要么落进一张卡，要么当面告诉他没记。
     * 认下来之后走 {@link #healthRecordConfirmCard} 里那条“换掉手上这张卡”的分支。
     *
     * <p><b>只放行“平静地报出一个能记住的数”</b>这一种句子，两道都卡着：
     * <ul>
     *   <li>句子里要有一个明确的项目词（{@code Kind.RECORD}）。“卡还悬着时他说 90”更可能是对卡片上
     *       那个数的回答，而不是新量的一次；而“90”本来也解析不成一条测量——{@link HealthRecordParser}
     *       要求句子里有项目词（“血糖6.4”认得出，“90”认不出）。</li>
     *   <li>这个数要量得出来（{@code !needsConfirm()}）。“血压800”那类先要反问他一句“是重新量一个
     *       还是照记”，走的是另一条路（见 {@link #askRecordConfirm}），而那条路<b>不换卡</b>：
     *       它只会改手上的草稿，屏幕上那张卡却还是上一条。放它进来，就会出现“卡上写着 138、
     *       点下去写的是 800”。所以宁可让这一句走原来的链路，卡原样留着。</li>
     * </ul>
     *
     * <p>判据是<b>签发时冻下来的类型</b>（{@code confirmationKind}，与执行时看的是同一处），
     * 不是 {@code pendingAction}：后者是各业务分支随手改的会话字段，而“手上这张卡是哪一类”
     * 只有签发那一刻说了算。认不出来的类型名一律当“不是我这一类”。
     */
    private boolean replacesPendingHealthRecord(ConversationState state, HealthRecordParser.RecordIntent intent) {
        return intent != null && intent.kind() == HealthRecordParser.Kind.RECORD && !intent.needsConfirm()
                && healthRecordCardPending(state);
    }

    /** 手上是不是正悬着一张还没点头的健康记录卡：凭据、类型、等待确认，三者齐备才算。 */
    private boolean healthRecordCardPending(ConversationState state) {
        if (state.confirmationId == null) return false;
        if (state.stage != ConversationState.Stage.AWAITING_CONFIRMATION) return false;
        return ConfirmationService.PendingOperation.Kind.stored(state.confirmationKind)
                == ConfirmationService.PendingOperation.Kind.HEALTH_RECORD;
    }

    /** 备忘识别：识别不到返回 null 走原链路；时间不明确先追问钟点；显式托付直接记，隐式先给确认卡。 */
    /**
     * 备忘这条路（模型开关都要走）：办之前先看看这句话里是不是还夹着一个<b>健康数值</b>。
     *
     * <p>这是「一句话两件事」的另一面。健康数值那条链路里，夹着的提醒会被当面交回老人
     * （见 {@link #alsoATimedMemoNote}）；反过来，这句话也可能先被备忘认领
     * （“我血压 130，顺便提醒我明天早上吃药”——模型离线时按关键词路由，模型在线时也可能是模型
     * 判成了记备忘），于是一整句进备忘、<b>数没人接</b>。老人在 8198 上真遇到过：备忘存成
     * “我血压130，顺便提醒我早上吃药”，血压那半从此不在他的记录里，他也从没被告知。
     *
     * <p>所以：数值那半不跟着办，但要说出来，让他单独再说一遍（跟另一面同一口径）。
     * 补话同样拼在<b>草稿</b>上——定稿那一步已经把回复写进会话历史，贴上去的话只落在屏幕，
     * 下一轮上下文里没有，等于没说过（做法见 {@link #deferFinalization}，工具循环里同款）。
     *
     * <p>补话这一轮走 {@link #respondWithoutModel}：这句话唯一的产出就是“那个数我还没记”这个交代，
     * 让模型重写措辞就有可能被润掉，润掉了老人就当数已经报过了。
     */
    private AgentTurnResponse memoReply(ConversationState state, String value) {
        String also = alsoAHealthValueNote(value);
        if (also.isEmpty()) return memoReplyDraft(state, value);
        deferFinalization.set(true);
        AgentTurnResponse draft;
        try {
            draft = memoReplyDraft(state, value);
        } finally {
            deferFinalization.remove();
        }
        if (draft == null) return null;
        return respondWithoutModel(state, draft.reply() + also, draft.quickReplies(),
                draft.uiDirective(), draft.notice(), draft.confirmation());
    }

    /**
     * “这句话里还夹着一个数”的补话，判据是<b>认出了一个量得出来的实测值</b>：
     * 项目明确、数值得在范围内（血压 130）、不是离谱值。
     *
     * <p>不认离谱值（血压 800 / “早上8点量血压”里被读成数值的那个 8）：那类句子本身就有歧义，
     * 说一句“您还报了一个血压 8”只会让他更糊涂；它们各有各的去处（前者健康链路会反问他重测还是照记）。
     */
    private String alsoAHealthValueNote(String raw) {
        HealthRecordParser.RecordIntent also = raw == null ? null : HealthRecordParser.detect(raw);
        if (also == null || also.kind() != HealthRecordParser.Kind.RECORD) return "";
        if (also.item() == null || also.valueNum() == null || also.issue() != null) return "";
        return "另外，您这句话里还报了一个" + also.item() + " " + also.valueText()
                + "，我这一轮没有一起记到健康记录里——请单独再说一遍，我帮您记上。";
    }

    private AgentTurnResponse memoReplyDraft(ConversationState state, String value) {
        MemoParser.MemoIntent memo = MemoParser.detect(value, clock.now());
        if (memo == null) return null;
        state.pendingMemoDays = null;
        // 一句话说了不止一个时间点（“周一八点吃药，周三下午三点复查”）：解析器只认一套“钟点+日子”，
        // 硬记就是两件事共用一个钟点、第二件的时间被顶掉（“9月15号和9月20号”还会丢一天）。
        // 这里不猜，也不留半截草稿，请他一件一件说。
        if (MemoParser.severalMomentsInOneSentence(value)) return askOneThingAtATime(state);
        // “每周提醒我量血压”这种没说周几/几号的，趁早问清楚：照原样存下来只会是一条永远不响的备忘
        if (memo.repeatDayGap() != null) return askMemoRepeatDay(state, memo);
        if (memo.needsDay()) return askMemoDay(state, memo);
        if (memo.needsTime()) return askMemoTime(state, memo);
        if (memo.explicit()) return recordMemo(state, memo);
        state.pendingMemoText = memo.text();
        state.pendingMemoAts = memo.remindAts();
        state.pendingMemoRepeat = memo.repeatRule();
        state.memoNeedsApproval = true;
        state.memoReturnStage = state.stage;
        return memoConfirmCard(state);
    }

    /**
     * 一句话里说了不止一个时间点（“周一八点吃药，周三下午三点复查”）：不猜，请他一件一件说。
     *
     * <p>解析器一条备忘只认一套“钟点 + 日子”，两个时间点挤在一句里，第二处会把第一处顶掉，
     * 于是<b>两件事共用一个时间</b>——老人到点听到的是张冠李戴的提醒，而且听不出来错在哪。
     * 不是不能记，是这一句里“哪半句配哪个时间”机器判不准，所以宁可不记。
     *
     * <p><b>不落库、也不留草稿</b>：没有可靠的依据挑出“头一件”，替他挑就是替他做决定；
     * 留个半截草稿更糟——下一句会被接着当成补充答案。这一轮只回一句话，状态原地不动，
     * 他重说的那一句从零解析，干干净净。
     */
    private AgentTurnResponse askOneThingAtATime(ConversationState state) {
        return respondWithoutModel(state,
                "您这句话里像是说了不止一件事（或不止一个时间），我怕记串了，"
                        + "先没有记。请您一件一件说，我先记头一件——比如“提醒我周一早上八点吃药”。",
                List.of());
    }

    /**
     * 补答的那一句里带了不止一个钟点：一次只认一个，请他分开说。
     *
     * <p>这里和 {@link #askOneThingAtATime} 有一处关键差别：<b>草稿留着</b>。追问到这一步，
     * 要记的内容已经问清楚了（{@code pendingMemoText}），作废的是“钟点”这个答案、不是整条备忘。
     * 清掉草稿等于让他为了一个钟点把内容重讲一遍——比不记还烦人。
     * 所以 {@code pendingMemoText/pendingMemoDays/pendingAction} 一个都不动，问题还是原来那个问题。
     *
     * <p>话术要把话说全：我一次只记一个到点时间（为什么），另一件事等这轮说完再跟我说一遍（怎么补救）。
     * 少了后半句，老人以为两件都交代过了，第二件就变成静默丢掉。
     *
     * <p>走 {@link #respondWithoutModel} 而不是 {@code respond}：这句话唯一的产出就是“那件事我还没记”
     * 这个交代，模型改写措辞时完全可能把它润掉（它能看到的只有一句“权威草稿”，
     * 校验也只管紧急/医疗/确认卡那几类），真润掉了，这条判据就白做了。
     * 按钮的 action 沿用当前 {@code pendingAction}：回到原来那个追问处理器，答完日子/钟点仍然接得上。
     */
    private AgentTurnResponse askOneClockAtATime(ConversationState state) {
        return respondWithoutModel(state,
                "您这句里有两个时间，我一次只能记一个到点时间，怕记串了——"
                        + "先说我该按哪个时间来提醒？比如“早上8点”。"
                        + "另外那件事，等这条记好了您再跟我说一遍，我另给您记上。",
                memoNoTimeReply(state.pendingAction == null ? "MEMO_TIME" : state.pendingAction));
    }

    /**
     * 改期那一句里带了不止一个到点时间（“改成明天早上八点，还有周三下午三点”“改成周三和周五”）：
     * 一次只认一个，请他挑。
     *
     * <p>和补答路径同源（见 {@link #askOneClockAtATime}），差别有两处：那边是「记」这边是「改」；
     * 那边多天答复要拆成几条备忘（所以只拦钟点），而改期只动这一条备忘、装不下两天，所以这里
     * 连日子一起拦。
     *
     * <p>要改的那条备忘由 {@code pendingMemoId} 认着，{@code pendingAction} 仍是
     * {@code MEMO_EDIT_TIME}——他重新说一句就回到 {@code editMemoTo}，不必从头再走一遍“改第几条”。
     */
    private AgentTurnResponse askOneTimeForEditAtATime(ConversationState state) {
        return respondWithoutModel(state,
                "您这句里有两个时间，我一次只能把这条备忘改成其中一个，怕改错——"
                        + "先说我该按哪个时间来提醒？比如“明天早上8点”或“周三下午3点”。"
                        + "另外那个时间，您单独跟我说一遍，我另给您记一条。",
                memoStopRemindReply());
    }

    /**
     * 日期没定下来时先问清楚是哪一天。两种情形：
     * 老人说的那天已经过去了（周四说“这周三”），或者只说了范围没说哪天（“我这周要吃药”）。
     * 两种都不替他猜——猜出来的日期一旦错了，老人到点没被提醒还查不出原因。
     */
    private AgentTurnResponse askMemoDay(ConversationState state, MemoParser.MemoIntent memo) {
        state.pendingMemoText = memo.text();
        state.pendingMemoAts = null;
        state.pendingMemoRepeat = memo.repeatRule();
        state.pendingMemoDays = null;
        state.memoNeedsApproval = !memo.explicit();
        state.memoReturnStage = state.stage;
        state.pendingAction = "MEMO_DAY";
        state.stage = ConversationState.Stage.MEMO_TIME;
        confirmations.clear(state);
        List<LocalDate> past = MemoParser.pastWeekdays(memo.text(), clock.today());
        // “这周/下周”只说范围没说哪天，说“那天已经过去了”是无中生有——那天还没定呢
        String scope = MemoParser.bareWeekWord(memo.text());
        String opening = !past.isEmpty()
                ? "您说的“" + memoDaysLabel(past) + "”已经过去了。"
                : "您说的是“" + (scope == null ? "那天" : scope) + "”，还没说具体哪一天。";
        String suggest = MemoParser.weekDaySuggestion(memo.text(), clock.today());
        return respond(state, opening
                        + "您是指哪一天呢？可以告诉我“" + suggest + "”，或者直接说个日期，比如“9月16号”。"
                        + "不需要提醒就说“不用提醒，只记下”。",
                memoDayReplies(suggest));
    }

    private List<QuickReply> memoDayReplies(String suggest) {
        return List.of(
                q(suggest, "MEMO_DAY", suggest),
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

    /** 好几天时逐天列出来：只回读第一天，老人没法发现后面哪天听错了。 */
    private String memoDaysLabel(List<LocalDate> days) {
        return String.join("、", days.stream().map(this::memoDayLabel).toList());
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
        state.pendingMemoAts = null;
        state.pendingMemoRepeat = memo.repeatRule();
        state.pendingMemoDays = null;
        state.memoNeedsApproval = !memo.explicit();
        state.memoReturnStage = state.stage;
        state.pendingAction = "MEMO_REPEAT_DAY";
        state.stage = ConversationState.Stage.MEMO_TIME;
        confirmations.clear(state);
        boolean weekly = !"MONTHLY".equals(memo.repeatRule());
        String ask = weekly
                ? "您想每周几提醒呢？比如说“每周三”"
                : "您想每月几号提醒呢？比如说“每月15号”";
        return respond(state, "好的，这条我帮您记成重复提醒。" + ask + "；不需要提醒就说“不用提醒，只记下”。",
                memoNoTimeReply("MEMO_REPEAT_DAY"));
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
            state.pendingMemoDays = null;
            state.memoNeedsApproval = false;
            state.memoReturnStage = null;
            state.pendingAction = "CREATE";
            if (state.stage == ConversationState.Stage.MEMO_TIME) state.stage = ConversationState.Stage.READY_TO_PLAN;
            return writeMemo(state, text, List.of(), null);
        }
        // 与 applyMemoAnswer 同一处判据、同一个理由：答句里的第二个钟点会被静默丢掉。
        // 放在 resolveRepeatAnchor 之前——锚点听懂了也不该顺着往下写，那句里还有半句我们没接住。
        if (MemoParser.severalClocksInOneSentence(value)) return askOneClockAtATime(state);
        LocalDate day = MemoParser.resolveRepeatAnchor(state.pendingMemoRepeat, value, clock.today());
        if (day == null) {
            return respond(state, "没听清是哪一天。请再说一次，比如“每周三”或“每月15号”；不需要提醒就说“不用提醒，只记下”。",
                    memoNoTimeReply("MEMO_REPEAT_DAY"));
        }
        List<LocalDateTime> ats = MemoParser.resolveRemindAt(state.pendingMemoText, value,
                List.of(day), clock.now());
        if (ats.isEmpty()) {
            // 锚点听懂了、还差钟点：带着这一天接着问几点
            state.pendingMemoDays = List.of(day);
            state.pendingAction = "MEMO_TIME";
            return respond(state, "好的，" + repeatAnchorLabel(state.pendingMemoRepeat, day)
                            + "。还差具体几点，请告诉我几点提醒，比如“早上8点”或“下午3点”；"
                            + "不需要到点提醒就说“不用提醒，只记下”。",
                    memoNoTimeReply("MEMO_TIME"));
        }
        return finishMemoAnswer(state, ats);
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
        confirmations.clear(state);
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
        // 「改成明天早上八点，还有周三下午三点」「改成周三和周五」：这条备忘只装得下一个到点时间，
        // 而下面 resolveRemindAt 的结果只取第一条——第二处会被静默丢掉，老人以为改到了下午三点，
        // 到点响的却是早上八点，或者周五那次根本没改上，还听不出错在哪。不猜，请他挑一个再说。
        // 两个判据都只看他这一句：备忘正文里本来就写着几天的那种老条目不算，那不是这次要改的东西。
        if (MemoParser.severalMomentsInOneSentence(value)
                || MemoParser.resolveDays(value, clock.today()).size() > 1) {
            return askOneTimeForEditAtATime(state);
        }
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
            anchor = MemoParser.resolveRepeatAnchor(repeat, value, clock.today());
            // 只说“每周/每月”没说周几/几号：接着问，不能存一条永远不响的重复提醒
            if (anchor == null) {
                return respond(state, "没听清是每周几还是每月几号。请再说一次，比如“改成每周三下午三点”。",
                        List.of());
            }
        }
        // 算时间仍用原话：老人只说“改成每周三”时，钟点要沿用正文里那个（“每天八点”的八点），
        // 否则会说“没听清”再问一遍，而且第二轮答钟点时把每周三这个周期丢掉。
        // 改期只动这一条备忘，所以这里说的是“哪天”就取第一条，不给它拆出新条目。
        List<LocalDateTime> resolved = MemoParser.resolveRemindAt(memo.text(), value,
                anchor == null ? List.of() : List.of(anchor), clock.now());
        LocalDateTime at = resolved.isEmpty() ? null : resolved.get(0);
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
        confirmations.clear(state);
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
        state.pendingMemoAts = null;
        state.pendingMemoRepeat = memo.repeatRule();
        state.pendingMemoDays = null;
        state.memoNeedsApproval = !memo.explicit();
        state.memoReturnStage = state.stage;
        state.pendingAction = "MEMO_TIME";
        state.stage = ConversationState.Stage.MEMO_TIME;
        confirmations.clear(state);
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
        String confirmationId = confirmations.issue(state,
                ConfirmationService.PendingOperation.Kind.MEMO, List.of());
        String text = state.pendingMemoText;
        // 走到这里 ats 一定不是 null：上面 issue 那一步已经把"草稿不齐"的卡拦在门外了（见 payloadIntact）。
        // 留着兜底是因为这里的退化方向是安全的那一边（卡片说"暂不设置时间"，执行时会被判凭据不可信），
        // 而不是拿一个 null 去拼卡片。
        List<LocalDateTime> ats = state.pendingMemoAts == null ? List.of() : state.pendingMemoAts;
        List<String> operations = new ArrayList<>();
        operations.add("备忘内容：" + text);
        if (ats.isEmpty()) {
            operations.add("提醒：暂不设置时间（作为长期备忘）");
        } else {
            // 一句话说几天就列几行：老人点头之前得能核对每一天的日期听没听错，
            // 只写一句“共 3 条”等于没给他核对的机会
            for (LocalDateTime at : ats) {
                operations.add("提醒：" + memoRepeatLabel(at, state.pendingMemoRepeat) + "，到点打开应用会提醒您");
            }
        }
        ConfirmationCard card = new ConfirmationCard("帮您记下这条健康备忘吗？", operations,
                "只记录健康/复诊相关事项；确认后写入首页“健康备忘”，可随时查看、标记完成或删除。",
                "确认记下", "先不用", confirmationId);
        return finish(state, new AgentTurnResponse(state.id, state.stage.name(),
                "您说的这件健康事项，我可以帮您记下来并按时提醒。需要我记下吗？", List.of(),
                null, card, null, traces.findByConversation(state.id)));
    }

    /** 显式托付：调备忘录工具落库，回复后回到原办理上下文。 */
    private AgentTurnResponse recordMemo(ConversationState state, MemoParser.MemoIntent memo) {
        return writeMemo(state, memo.text(), memo.remindAts(), memo.repeatRule());
    }

    /**
     * 备忘的落库入口（显式托付直写、追问补齐之后落库）。
     *
     * <p>它和确认卡那条路落到同一个执行器：三条路写的是同一条库、回读的是同一套话术，
     * 在服务里再留一份，两边迟早在「正文要不要摘掉时间」这类细节上走散。
     */
    private AgentTurnResponse writeMemo(ConversationState state, String text, List<LocalDateTime> ats,
                                        String repeatRule) {
        return dispatcher.writeMemo(state, text, ats, repeatRule, this);
    }

    /** 老人回答“几点”的入口：可打字（聊天）也可点快捷回复（动作 MEMO_TIME）。 */
    private AgentTurnResponse applyMemoTime(ConversationState state, String answer) {
        return applyMemoAnswer(state, answer, memoNoTimeReply("MEMO_TIME"),
                "没听清具体钟点。请再说一个时间，比如“早上8点”或“下午3点”；不需要到点提醒就说“不用提醒，只记下”。");
    }

    /** 老人回答“哪一天”的入口（那天已过去、或只说了“这周/下周”时追问用）。 */
    private AgentTurnResponse applyMemoDay(ConversationState state, String answer) {
        String pending = state.pendingMemoText == null ? "" : state.pendingMemoText;
        return applyMemoAnswer(state, answer, memoDayReplies(MemoParser.weekDaySuggestion(pending, clock.today())),
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
        // 补答里带了两个钟点（“早上八点吃药，下午三点量血压”）：下面 resolveRemindAt 只取一个钟点，
        // 第二个会被静默丢掉、两个日子还会共用第一个钟点。草稿留着，只让他把时间一个一个说
        // （判据只看钟点：答句说“下周三，下周五”是同一件事的两天，本来就该拆条，不能拦）。
        if (MemoParser.severalClocksInOneSentence(value)) return askOneClockAtATime(state);
        boolean keepStanding = containsAny(value, MEMO_NO_TIME);
        List<LocalDate> answeredDays = keepStanding ? List.of() : MemoParser.resolveDays(value, clock.today());
        List<LocalDateTime> ats = keepStanding ? List.of()
                : MemoParser.resolveRemindAt(state.pendingMemoText, value, state.pendingMemoDays, clock.now());
        if (!keepStanding && ats.isEmpty()) {
            // 日子听懂了（“下周三”）但还差钟点：接着问几点，别把刚听懂的日子又丢掉，
            // 也不能回一句“没听清是哪一天”——老人明明已经说清楚了
            if (!answeredDays.isEmpty()) {
                state.pendingMemoDays = answeredDays;
                state.pendingAction = "MEMO_TIME";
                return respond(state, "好的，记成" + memoDaysLabel(answeredDays)
                                + "。还差具体几点，请告诉我几点提醒，比如“早上8点”或“下午3点”；"
                                + "不需要到点提醒就说“不用提醒，只记下”。",
                        memoNoTimeReply("MEMO_TIME"));
            }
            return respond(state, retryNote, retryReplies);
        }
        return finishMemoAnswer(state, ats);
    }

    /**
     * 追问齐了（内容+到点时间）之后的共同落地：隐式备忘走确认卡，显式直写。
     *
     * <p><b>代长辈留提醒走另一支。</b>家属端的提醒按设计不出确认卡（家属是自己人，问一声再点头只会多一屏），
     * 所以 caregiving 这一支排在卡片判断<b>之前</b>；落库和回读也换成家属那一套
     * （{@link #commitElderRemind}）——抬头写明是谁留的、逐天改写正文、回一条协同通知。
     * 不这么分的话，家属补答完钟点之后走的是老人端的口吻（“已记下 1 条”），
     * 通知也不会发，等于替长辈留了提醒却没人知道。
     */
    private AgentTurnResponse finishMemoAnswer(ConversationState state, List<LocalDateTime> ats) {
        state.pendingMemoDays = null;
        state.pendingMemoAts = ats;
        boolean caregiving = state.caregiving();
        if (!caregiving && state.memoNeedsApproval) return memoConfirmCard(state);
        String text = state.pendingMemoText;
        String repeat = state.pendingMemoRepeat;
        state.pendingMemoText = null;
        state.pendingMemoRepeat = null;
        state.pendingMemoDays = null;
        state.pendingAction = "CREATE";
        state.stage = state.memoReturnStage == null ? ConversationState.Stage.READY_TO_PLAN : state.memoReturnStage;
        state.memoReturnStage = null;
        state.memoNeedsApproval = false;
        if (caregiving) return commitElderRemind(state, text, ats, repeat);
        return writeMemo(state, text, ats, repeat);
    }

    /** 丢弃暂存中的备忘草稿（追问被打断/内容失效时）。 */
    private void clearMemoDraft(ConversationState state) {
        state.pendingMemoText = null;
        state.pendingMemoAts = null;
        state.pendingMemoRepeat = null;
        state.pendingMemoDays = null;
        state.memoNeedsApproval = false;
        state.memoReturnStage = null;
        confirmations.clear(state);
        state.pendingAction = "CREATE";
        if (state.stage == ConversationState.Stage.MEMO_TIME) {
            state.stage = ConversationState.Stage.READY_TO_PLAN;
        }
    }

    /**
     * 回读记下了什么。一句话说了好几天时会写成好几条，所以按条列出来——
     * 老人只有逐条听到日期，才能发现其中哪天听错了，不然“已记下”三个字掩盖了三条里的错。
     */
    private String memoSavedReply(List<MemoStore.MemoView> created, String repeatRule) {
        String repeat = MemoStore.normalizeRepeat(repeatRule);
        if (created.size() > 1) {
            StringBuilder lines = new StringBuilder("好的，已记下 " + created.size() + " 条。");
            for (MemoStore.MemoView memo : created) {
                lines.append("\n· ").append(memoMomentLabel(memo.remindAt(), repeat));
            }
            return lines.append("\n您可以在首页“健康备忘”中查看、标记完成或删除。").toString();
        }
        MemoStore.MemoView memo = created.get(0);
        String when;
        if (memo.remindAt() == null) {
            when = "这条作为长期备忘保留。";
        } else if (repeat == null) {
            when = "到" + memoTimeLabel(memo.remindAt()) + "打开应用会提醒您。";
        } else {
            // 重复提醒把周期锚点一起回读（“以后每周三 15:00”）：
            // 只说“每周”老人听不出是哪天，也就没法发现听错了
            when = "以后" + memoRepeatLabel(memo.remindAt(), repeat) + "到点打开应用会提醒您。";
        }
        return "好的，已记下：“" + memo.text() + "”。" + when
                + "您可以在首页“健康备忘”中查看、标记完成或删除。";
    }

    /**
     * 多天备忘写到一半失败时的回读：写成的那几条逐条念，没写成的如实说清是哪几天。
     *
     * <p>两句话都不能省。只说“已经记下 2 条”，老人以为三天都设好了，第三天永远不响；
     * 只说“没记上”，他又会以为一条都没有、回头重复说一遍——两条路都让他照着话去首页核对时
     * 对不上号。
     */
    private String memoPartlySavedReply(List<MemoStore.MemoView> created, List<LocalDateTime> missed,
                                        String repeatRule) {
        String repeat = MemoStore.normalizeRepeat(repeatRule);
        StringBuilder lines = new StringBuilder();
        if (created.isEmpty()) {
            lines.append("好的，这条我没能记上，现在一条都没有。");
        } else {
            lines.append("好的，已记下 ").append(created.size()).append(" 条。");
            for (MemoStore.MemoView memo : created) {
                lines.append("\n· ").append(memoMomentLabel(memo.remindAt(), repeat));
            }
        }
        lines.append("\n还有 ").append(missed.size()).append(" 条没记上：");
        lines.append(String.join("、", missed.stream().map(at -> memoMomentLabel(at, repeat)).toList()));
        return lines.append("。您把这几条再说一遍，我接着记。").toString();
    }

    /**
     * 回读某一条提醒时刻的文案：一次性的连日期一起念（“9月16日（周三） 08:00”），
     * 重复的念周期锚点（“每周三 08:00”）。
     *
     * <p>带上星期几是有意的：老人说的是“这周周一周二周三”，回读里只有“9月16日”他得自己数
     * 日子才知道那是周几，听不出听没听错。
     */
    private String memoMomentLabel(LocalDateTime at, String repeat) {
        if (at == null) return "长期备忘";
        return repeat == null ? memoDayLabel(at.toLocalDate()) + " " + at.format(TIME_LABEL)
                : memoRepeatLabel(at, repeat);
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
    @Override
    AgentTurnResponse memoHandoff(ConversationState state, String note) {
        if (state.appointmentId != null) {
            return respondWithPlan(state, note, bookedActions(state));
        }
        // 办理已经停止：备忘记完就停在这儿。原来会落到最后的 askHospital，等于把老人刚取消掉的
        // 办理又推回去（那句“我们继续办理复诊，请问您想去哪家医院”还会把 stage 从 CANCELLED
        // 改成 ASK_HOSPITAL）。要重新办，得由老人自己点“新建办理”。
        if (state.stage == ConversationState.Stage.CANCELLED) {
            return respondWithoutModel(state, note + " 这次办理已经停止，想重新办理时告诉我。",
                    List.of(q("新建办理", "NEW_BOOKING", ""), q("查看事项", "OPEN_TASKS", "")));
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

    /**
     * 实测数值：记录或回查。两条路都不落到预约链路（“我的血压是100”不是要办复诊）。
     *
     * <p>{@code also} 是“这句话里还夹着另一件事”的补话（见 {@link #alsoATimedMemoNote}），
     * 由三个出口拼在<b>草稿</b>里——不是等出口把回复定稿之后再往上一贴：定稿那一步已经写进会话
     * 历史了（见 {@code finish}），补话只落在屏幕上、没落进他这一轮的记录，下一轮的上下文就对不上。
     */
    private AgentTurnResponse healthRecordReply(ConversationState state, HealthRecordParser.RecordIntent intent,
                                                String raw) {
        String also = alsoATimedMemoNote(raw);
        if (intent.kind() == HealthRecordParser.Kind.QUERY) return healthRecordQuery(state, intent.item(), also);
        // 血压 800、体温 60 这种量不出来的数：先问一句是重测还是照记。既不静默丢（老人报了数却
        // 什么都没发生，还会顺着链路被问“去哪家医院”），也不闷头记成一条不可能的数据。
        // 这一问本身就是一次照面：老人当场答“照记”或重报一个数，都算他为这个数表过态，
        // 那之后直写（见 applyRecordConfirm），不再叠一张确认卡问第二遍。
        if (intent.needsConfirm()) return askRecordConfirm(state, intent, raw, also);
        return healthRecordConfirmCard(state, intent, raw, also);
    }

    /**
     * 一句话里夹着两件事时的补话：数值那半进健康记录，另半句<b>带时间的提醒</b>不跟着办——
     * 这条路上没有任何一处会写备忘。
     *
     * <p>为什么非得说出来：老人说完「我血压 130，顺便提醒我明天早上吃药」就去等提醒了，
     * 而库里的备忘一条都没有。这不是“少办一件”的措辞问题，是他说出口的话被静默丢掉。
     * 按“一件一件办”的口径，这里只办数值，并把另一件明确交回给他，让他再说一遍
     * （其余“一句话多个时间”的场景见 {@code severalMomentsInOneSentence} 那套回问）。
     *
     * <p>判据是<b>“这句里还有一件带时间的让提醒”</b>，不是“能解析出备忘”：{@code MemoParser}
     * 对显式托付本来就宽松，「记一下我血压130」也能解析成一条备忘，可那是同一件事、不是第二件，
     * 照那句判会说出一句误导他的话。所以两句都要：句子里有让提醒的话（见
     * {@link MemoParser#asksForAReminder}），且那半句带时间。
     *
     * <p>时间<b>认得出钟点</b>（“明天早上八点提醒我吃药”）和<b>只说了时段</b>（“明天早上提醒我吃药”）
     * 都算第二件——后者这一轮本来就该反问他几点（见 {@code MemoParser#clockTimeOfUnsaid}），
     * 更得说出来：他要等的是那个提醒，而这条链路一个备忘都不写。
     */
    private String alsoATimedMemoNote(String raw) {
        if (!MemoParser.asksForAReminder(raw)) return "";
        MemoParser.MemoIntent also = MemoParser.detect(raw, clock.now());
        if (also == null) return "";
        if (also.remindAts().isEmpty() && !also.needsTime()) return "";
        return "另外，您这句话里还有一件要提醒的事，我这一轮没有一起办——请单独再说一遍，我这就给您记上。";
    }

    /**
     * 健康记录确认卡：老人平静地报了一个数值（“我的血压是100”），先让他点头再落库。
     *
     * <p>为什么这条要一道门：健康记录是他自己拿给医生看的数据，而“听错了数”在这条路上没有任何
     * 别的护栏——“136”听成“160”既不离谱也不自相矛盾，机器判不出来，只有他本人看一眼才知道。
     * 所以卡上把三样一起复述清楚：记哪一项、记成什么数、什么时候量的；这三样也正是执行时
     * 要用的草稿，随凭据一起进快照（见 {@code ConfirmationService.payloadIntact}）。
     *
     * <p>记录时间取<b>此刻</b>并冻在草稿里，而不是等确认时再取一次：他在晚上九点半量的血压，
     * 隔了十分钟才点确认，记录里该是九点半。卡上写哪一刻，写进库的就是哪一刻。
     *
     * <p>{@code pendingAction} 用 {@code RECORD_CARD} 而不是反问那条的 {@code RECORD_CONFIRM}：
     * 后者是被反问之后老人答话的入口，两种状态混用一个名字，他随手打一句“150”就会被当成
     * 在回答一个他从没见过的问题。
     *
     * <p><b>手上已经有一张健康卡时，这一张换掉那一张</b>（同一会话手上只该有一张卡）：
     * 他上一条还没点头，这一句又报了一个新数，两个数不能各占一张卡等他挑。换掉的是那张卡，
     * 不是他正在办的事——原来办到哪一步沿用最早那一次记下的，确认之后照样接着办。
     */
    private AgentTurnResponse healthRecordConfirmCard(ConversationState state,
                                                      HealthRecordParser.RecordIntent intent, String raw,
                                                      String also) {
        // 先清旧凭据、再改草稿，顺序不能反：只改草稿不清凭据，屏幕上那张旧卡（写着上一条）就配上了
        // 这一条新数值——他对着卡上的「血压 138」点头，写进去的是「血糖 6.4」。清掉之后那张卡上的
        // 按钮当场失效（凭据对不上了），他按下去只会得到一句“这份确认已经失效”。
        boolean replacing = healthRecordCardPending(state);
        String replaced = replacing ? replacedRecordLabel(state) : null;
        if (replacing) confirmations.clear(state);
        state.pendingRecordItem = intent.item();
        state.pendingRecordValueNum = intent.valueNum();
        state.pendingRecordValueText = intent.valueText();
        state.pendingRecordUnit = intent.unit();
        state.pendingRecordRaw = raw;
        state.pendingRecordAt = clock.now();
        if (!"RECORD_CARD".equals(state.pendingAction)) {
            state.recordReturnAction = state.pendingAction;
            state.recordReturnStage = state.stage;
        }
        state.pendingAction = "RECORD_CARD";
        String confirmationId = confirmations.issue(state,
                ConfirmationService.PendingOperation.Kind.HEALTH_RECORD, List.of());
        String value = state.pendingRecordValueText + " " + state.pendingRecordUnit;
        ConfirmationCard card = new ConfirmationCard("帮您记下这条健康数值吗？",
                List.of("记录内容：" + state.pendingRecordItem + " " + value,
                        "记录时间：" + state.pendingRecordAt.format(MEMO_LABEL)),
                "确认后写入首页“健康记录”，随时可以查看；记错了也能删掉重记。",
                "确认记下", "先不用", confirmationId);
        // 换卡时把被撤下那条说出来：否则他以为两个数都记了，或者以为上一条已经记进去了
        String question = (replaced == null
                ? "您说的这个数，我帮您记到“健康记录”里。需要我记下吗？"
                : "您说的这个数，我帮您记到“健康记录”里。" + replaced + "还没确认，我撤下来了，"
                        + "没有记进去。需要我记下现在这一条吗？") + also;
        return finish(state, new AgentTurnResponse(state.id, state.stage.name(),
                question, List.of(),
                null, card, null, traces.findByConversation(state.id)));
    }

    /** 手上那张卡上记的是哪一条（“刚才那条「血压 138 mmHg」”），只说给老人听，不作任何判据。 */
    private String replacedRecordLabel(ConversationState state) {
        if (state.pendingRecordItem == null || state.pendingRecordValueText == null) return "刚才那条数值";
        String unit = state.pendingRecordUnit;
        String reading = unit == null || unit.isBlank()
                ? state.pendingRecordValueText : state.pendingRecordValueText + " " + unit;
        return "刚才那条「" + state.pendingRecordItem + " " + reading + "」";
    }

    /** 健康数值落库成功后的回读话术（确认卡与反问后直写两条路共用）。 */
    @Override
    String healthRecordedReply(String item, String valueText, String unit, LocalDateTime at) {
        return "好的，已记下：" + at.format(MEMO_LABEL) + " " + item + " " + valueText + " " + unit
                + "。以后想回看，问我“我最近" + item + "多少”就行，首页“健康记录”里也留着。";
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

    /**
     * 真往库里写一条实测值；回读记下了什么，老人才能发现听错了数。
     *
     * <p>它和确认卡那条路落到同一个执行器：两条路写的是同一张表、回读的是同一套话术，
     * 在服务里再留一份，两边迟早在「记录时间取哪一刻」这类细节上走散。
     *
     * <p>这里只留反问之后那一条入口（老人刚为这个数表过态，不必再点一次头）；平静报数那条
     * 走 {@link #healthRecordConfirmCard}。
     */
    private AgentTurnResponse recordValue(ConversationState state, HealthRecordParser.RecordIntent intent,
                                          String raw) {
        return recordValue(state, intent, raw, clock.now());
    }

    /** 记录时间由调用方给死的入口：反问前暂存的那条要把“量到的时刻”一起带过来。 */
    private AgentTurnResponse recordValue(ConversationState state, HealthRecordParser.RecordIntent intent,
                                          String raw, LocalDateTime at) {
        return dispatcher.writeHealthRecord(state, intent.item(), intent.valueNum(), intent.valueText(),
                intent.unit(), raw, at, this);
    }

    /**
     * 数值看起来不对时先反问，暂存这一条等老人表态。
     * 这里只判断“量不出这个数”，不判断“这个数好不好”——后者是医学判断，助手不做。
     */
    private AgentTurnResponse askRecordConfirm(ConversationState state, HealthRecordParser.RecordIntent intent,
                                               String raw, String also) {
        state.pendingRecordItem = intent.item();
        state.pendingRecordValueNum = intent.valueNum();
        state.pendingRecordValueText = intent.valueText();
        state.pendingRecordUnit = intent.unit();
        // 原话与量到的时刻一起暂存：他答“照记”时落库的仍是这一条（同一个数、同一刻），
        // 不是“反问之后我们又重新理解了一遍”的另一条
        state.pendingRecordRaw = raw;
        state.pendingRecordAt = clock.now();
        // 反问前正在办的流程（复诊办理、改期草稿）要记下来，答完还回去
        if (!"RECORD_CONFIRM".equals(state.pendingAction)) state.recordReturnAction = state.pendingAction;
        state.pendingAction = "RECORD_CONFIRM";
        String value = intent.valueText() + " " + intent.unit();
        if (intent.issue() == HealthRecordParser.Issue.SWAPPED) {
            return respond(state, "您说的是「" + intent.item() + " " + value + "」，这两个数是不是说反了？"
                            + "血压的前一个数要比后一个大。您重新说一遍，还是就按 " + intent.valueText() + " 记下来？"
                            + also,
                    recordConfirmReplies());
        }
        return respond(state, "您说的是「" + intent.item() + " " + value + "」，这个数好像不太对："
                        + intent.item() + "一般量不出这个数来，可能是听错了或者看错了。"
                        + "您重新量一个，还是就按 " + intent.valueText() + " 记下来？" + also,
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
            // 补话传空串：这是他在回答上一轮的反问（“150”），不是在说第二件事
            if (retry.needsConfirm()) return askRecordConfirm(state, retry, value, "");  // 换了个数还是量不出来
            clearPendingRecord(state);
            // 重报的这个数记“他说这句话的时刻”：这是新量的一次，不是刚才那条被反问的数
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
        // 落库用的仍是他刚才那句话和那一刻：反问只是问了一句，没有把这条变成另一次测量。
        // 原来这里现编一句“老人确认按原数记录：…”当原话，等于把他真正说的那句从记录里抹掉了。
        String raw = state.pendingRecordRaw == null
                ? intent.item() + " " + intent.valueText() + " " + intent.unit() : state.pendingRecordRaw;
        LocalDateTime at = state.pendingRecordAt == null ? clock.now() : state.pendingRecordAt;
        clearPendingRecord(state);
        return recordValue(state, intent, raw, at);
    }

    /** 老人要重测：暂存的这条留着，他接下来直接说个数就能对上项目。 */
    private AgentTurnResponse askRetryRecord(ConversationState state, String item) {
        return respond(state, "好，您重新量一下，量好直接把数告诉我就行，比如说“" + item + "是120”。",
                recordConfirmReplies());
    }

    /**
     * 丢弃待记的那条数值（改口重说、跑题、或状态不完整时）。
     *
     * <p>清哪几个字段、阶段还回哪一页，都由执行器那一份 {@code clearDraft} 说了算：
     * 确认卡那条路也要清同一批字段，两处各写一遍，早晚会漏掉其中一个——
     * 而漏掉的偏偏就是「缺了凭据就不作数」的那几个。
     */
    private void clearPendingRecord(ConversationState state) {
        dispatcher.clearHealthRecordDraft(state);
    }

    private List<QuickReply> recordConfirmReplies() {
        return List.of(q("重新说一个", "RECORD_RETRY", ""), q("就按这个记下来", "RECORD_KEEP", ""));
    }

    /** 回查最近的实测数值；一条都没有时教老人怎么上报。 */
    private AgentTurnResponse healthRecordQuery(ConversationState state, String item, String also) {
        List<HealthRecordStore.RecordView> rows = callTool(state, "healthRecord.query",
                Map.of("item", item == null ? "全部" : item, "limit", HEALTH_QUERY_LIMIT),
                () -> healthRecordTool.recent(state.id, state.userId, item, HEALTH_QUERY_LIMIT));
        String what = item == null ? "健康数值" : item;
        List<QuickReply> replies = List.of(q("继续办理复诊", "CONTINUE", ""));
        if (rows.isEmpty()) {
            return respond(state, "还没有" + what + "的记录。量完直接告诉我就行，比如说“我的"
                    + (item == null ? "血压是100" : item + "是100") + "”，我会帮您记下来。" + also, replies);
        }
        StringBuilder text = new StringBuilder("您最近的" + what + "记录：");
        for (int index = 0; index < rows.size(); index++) {
            HealthRecordStore.RecordView row = rows.get(index);
            if (index > 0) text.append("；");
            text.append(row.recordedAt().format(MEMO_LABEL)).append(" ");
            if (item == null) text.append(row.item()).append(" ");
            text.append(row.valueText()).append(" ").append(row.unit());
        }
        return respond(state, text.append("。").append(also).toString(), replies);
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

    private AgentTurnResponse cancelTask(ConversationState state) {
        confirmations.clear(state);
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

    private AgentTurnResponse askHospital(ConversationState state, String message) {
        state.taskStatus = ConversationState.TaskStatus.ACTIVE;
        state.managedMode = false; // 问到“哪家医院”=已转入本人新预约，离开代约开场
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
                        state.hospitalId, state.department, clock.today(), 3).stream()
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

    /**
     * 推荐医院。
     *
     * <p>{@code modelDriven} 为真时这条路是<b>只读</b>的：一个字段都不写。推荐只是建议，
     * 老人点了「选择市第一医院」之后才由那一次的业务动作把医院写进草稿。不给模型留一条
     * 「说是推荐、其实顺手把科室定了」的缝，规则才有可验证的边界。
     *
     * <p>不经过模型的按钮/规则流程（老人自己点了「推荐」）保持原样：那种调用里
     * {@code facts.department()} 就是老人刚说的条件，按既有口径记进草稿。
     */
    private AgentTurnResponse recommendHospitals(ConversationState state, ExtractedFacts facts,
                                                 boolean modelDriven) {
        RecommendationPlan plan = prepareCandidates(state, facts, modelDriven);
        if (plan.blocked() != null) return plan.blocked();
        String department = plan.department();
        List<HospitalProfile> rows = plan.kept();
        if (department == null) {
            String summary = rows.stream().limit(3).map(this::hospitalSummary)
                    .collect(java.util.stream.Collectors.joining("；"));
            List<QuickReply> choices = rows.stream().limit(3)
                    .map(item -> q("了解" + item.name(), "SET_HOSPITAL", item.id())).toList();
            return respond(state, plan.note()
                    + "我不能判断哪家医院‘最好’，但可以根据数据库中的科室特色、适老服务和号源帮您筛选。"
                    + summary + "。请先告诉我医生要求复诊的科室。", choices);
        }
        // 模型发起的推荐轮到此为止：往下一行都不许写草稿。不经过模型的那条路里，科室是老人
        // 自己说的条件，才按既有口径记下来——清掉日期、时段和候选号源，因为它们都是照着旧科室定的。
        if (!modelDriven && !java.util.Objects.equals(state.department, department)) {
            resetAfterDate(state);
            state.department = department;
            state.departmentId = null;
        }
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
        return respond(state, plan.note() + "根据“" + department + "”和模拟医院资料，我找到了以下选择。" + summary
                + "。这是办理信息筛选，不是医疗诊断，请选择您原就诊医院或医生建议的医院。", choices);
    }

    /**
     * 本轮推荐的真实候选，以及算它的过程中必须当场说的两句话。
     *
     * @param kept          排除之后剩下的真实候选。模型给的结构化推荐只能从这里选——
     *                      校验层和展示层用的是<b>同一份</b>，否则「模型推荐的」和「Java 会摆出来的」就是两批人。
     * @param department    这一轮按哪个科室算的候选；为空表示还没说科室，按全院算
     * @param excludedIds   被排除掉的医院 id：用来把「夹带被排除的那家」和「压根不在候选里」分开说
     * @param excluded      老人原话里说要排除的目标，原样带回去给模型看它错在哪
     * @param note          Java 必须说的一句理解说明（近似排除时才有），交付时要接在模型那句话前面
     * @param blocked       非空表示这一轮到此为止，直接把这句话交给老人（认不准 / 没有候选了）
     */
    private record RecommendationPlan(List<HospitalProfile> kept, String department,
                                      Set<String> excludedIds, List<String> excluded, String note,
                                      AgentTurnResponse blocked) {
        static RecommendationPlan blocked(AgentTurnResponse response) {
            return new RecommendationPlan(List.of(), null, Set.of(), List.of(), "", response);
        }
    }

    /**
     * 算本轮推荐的真实候选：先定科室，再查目录，最后拿掉老人明确排除的。
     *
     * <p>规则路径（老人自己点了「推荐」）和模型路径（模型给结构化推荐）走的是<b>同一个方法</b>。
     * 两条路各算各的候选，校验就成了一句空话——模型只需要推荐一批「另一套口径下的候选」，
     * 逐条检查照样能过。
     *
     * <p>{@code modelDriven} 只决定一件事：Java 自己写的那些终结句（科室查不到、排除认不准、
     * 没有候选了）走 {@code respond} 还是 {@code respondWithoutModel}。模型那条路上不能调用回答模型
     * 润色——那些是安全措辞，改软了就等于把边界说模糊了。
     */
    private RecommendationPlan prepareCandidates(ConversationState state, ExtractedFacts facts,
                                                 boolean modelDriven) {
        String requestedDepartment = facts == null ? null : facts.department();
        String department = requestedDepartment != null ? requestedDepartment : state.department;
        // 老人这一轮明确说不要的医院：在生成推荐文字和快捷按钮之前就把它们拿掉。
        List<String> excluded = facts == null ? List.of() : facts.excludedHospitals();
        if (department == null) {
            return finishCandidates(state, hospitalCatalogTool.listHospitals(state.id), null, excluded, modelDriven);
        }
        List<HospitalProfile> rows = hospitalCatalogTool.findHospitalsForDepartment(state.id, department);
        if (rows.isEmpty()) {
            List<QuickReply> choices = List.of(q("查看医院", "CHANGE_HOSPITAL", ""),
                    q("联系人工", "CONTACT_HUMAN", ""));
            String text = "模拟数据库中暂时没有开设" + department + "的医院。您可以换一个科室，或联系人工帮助。";
            return RecommendationPlan.blocked(modelDriven
                    ? respondWithoutModel(state, text, choices) : respond(state, text, choices));
        }
        return finishCandidates(state, rows, department, excluded, modelDriven);
    }

    private RecommendationPlan finishCandidates(ConversationState state, List<HospitalProfile> rows,
                                                String department, List<String> excluded,
                                                boolean modelDriven) {
        ExclusionFilter filter = withExclusionsRemoved(state, rows, excluded, modelDriven);
        if (filter.unclear() != null) {
            return new RecommendationPlan(List.of(), department, Set.of(), List.copyOf(excluded), "",
                    filter.unclear());
        }
        if (filter.kept().isEmpty()) {
            return new RecommendationPlan(List.of(), department, filter.excludedIds(),
                    List.copyOf(excluded), filter.note(),
                    noCandidateLeftAfterExclusion(state, department, filter.note(), modelDriven));
        }
        return new RecommendationPlan(filter.kept(), department, filter.excludedIds(),
                List.copyOf(excluded), filter.note(), null);
    }

    /**
     * 过滤掉老人这一轮明确排除的医院：{@code note} 是一句要在回复里说的话（近似匹配时必须
     * 说出来，让老人有机会纠正），{@code excludedIds} 是真正被拿掉的 id，
     * {@code unclear} 非空表示有一个排除目标 Java 认不准，这一轮就不给推荐了。
     */
    private record ExclusionFilter(List<HospitalProfile> kept, Set<String> excludedIds, String note,
                                   AgentTurnResponse unclear) {
        static ExclusionFilter none(List<HospitalProfile> rows) {
            return new ExclusionFilter(rows, Set.of(), "", null);
        }
    }

    /**
     * 把老人排除的医院从候选里拿掉 <b>它们和真实医院目录比对之后</b>。
     *
     * <p>这里是「当前明确排除」真正落地的地方：<b>在生成推荐文字和快捷按钮之前</b>过滤，
     * 所以被排除的医院不可能从哪一句话或哪一个按钮里漏回来。
     *
     * <p>三种匹配结果三种处理，都不猜：
     * <ul>
     *   <li>精确命中：直接排除，不用多解释。</li>
     *   <li>唯一近似（老人说「市一」）：仍然排除，但<b>必须把理解的结果说出来</b>，
     *       他才有机会发现认错了。</li>
     *   <li>对不上目录，或者对上了不止一家：这一轮不给推荐，直接说清是哪一步没认准。
     *       不拿别的医院替它，也不假装已经排除了。</li>
     * </ul>
     */
    private ExclusionFilter withExclusionsRemoved(ConversationState state, List<HospitalProfile> candidates,
                                                  List<String> excluded, boolean modelDriven) {
        if (excluded == null || excluded.isEmpty()) return ExclusionFilter.none(candidates);
        List<HospitalProfile> catalog = hospitalCatalogTool.listHospitals(state.id);
        Set<String> excludedIds = new LinkedHashSet<>();
        StringBuilder note = new StringBuilder();
        for (String raw : excluded) {
            CatalogEntityResolver.Match match = entityResolver.hospital(raw, catalog);
            if (match.type() == CatalogEntityResolver.MatchType.EXACT
                    || match.type() == CatalogEntityResolver.MatchType.UNIQUE_APPROXIMATE) {
                excludedIds.add(match.only().id());
                if (match.type() == CatalogEntityResolver.MatchType.UNIQUE_APPROXIMATE) {
                    note.append("您说的“").append(match.raw()).append("”我理解成“")
                            .append(match.only().name()).append("”，已经不算在候选里。");
                }
            } else {
                String reply = exclusionUnclearReply(match);
                return new ExclusionFilter(candidates, Set.of(), "",
                        modelDriven ? respondWithoutModel(state, reply, resumeReplies(state))
                                : respond(state, reply, resumeReplies(state)));
            }
        }
        List<HospitalProfile> kept = candidates.stream()
                .filter(item -> !excludedIds.contains(item.id())).toList();
        return new ExclusionFilter(kept, Set.copyOf(excludedIds), note.toString(), null);
    }

    /** 排除目标认不准时的回话：说清是哪一步没认准，不替老人挑一家来排除。 */
    private String exclusionUnclearReply(CatalogEntityResolver.Match match) {
        if (match.type() == CatalogEntityResolver.MatchType.AMBIGUOUS) {
            String names = match.candidates().stream().map(CatalogEntityResolver.Candidate::name)
                    .collect(java.util.stream.Collectors.joining("、"));
            return "您说的“" + match.raw() + "”对上了不止一家医院：" + names
                    + "。我没替您猜是哪一家，所以这一轮先不给推荐。请说全名或院区，我再按您排除的那家重新看。";
        }
        return "您说不要“" + match.raw() + "”，但我在可办理的医院里没找到这一家，"
                + "所以不知道要排除的到底是哪一家。这一轮我先不给推荐，也不拿别的医院替它。"
                + "请说完整的医院名称，或换个说法。";
    }

    /**
     * 排除之后没有候选了：如实说明，请他换条件。
     *
     * <p><b>绝不把被排除的医院悄悄放回来。</b>「都被您排除了」和「您选一家吧」这两句话
     * 只能出现一句，出现后者就等于推荐里又有了他刚说不要的那家。
     */
    private AgentTurnResponse noCandidateLeftAfterExclusion(ConversationState state, String department,
                                                            String note, boolean modelDriven) {
        String scope = department == null ? "可办理的医院" : "开设" + department + "的医院";
        List<QuickReply> replies = resumeReplies(state);
        String reply = note + "把您明确排除的医院去掉之后，" + scope + "里没有别家了。"
                + "您可以说一家不排除的医院，或者换一个科室，我再看看。这一轮没有改动手上的预约。";
        return modelDriven ? respondWithoutModel(state, reply, replies)
                : respond(state, reply, replies);
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

    @Override
    AgentTurnResponse respondWithPlan(ConversationState state, String reply, List<QuickReply> quickReplies) {
        return finish(state, new AgentTurnResponse(state.id, state.stage.name(), reply, quickReplies,
                plan(state), null, null, traces.findByConversation(state.id)));
    }

    @Override
    AgentTurnResponse respondWithoutModel(ConversationState state, String reply,
                                          List<QuickReply> quickReplies) {
        return respondWithoutModel(state, reply, quickReplies, null);
    }

    private AgentTurnResponse respondWithoutModel(ConversationState state, String reply,
                                                  List<QuickReply> quickReplies, UiDirective directive) {
        return respondWithoutModel(state, reply, quickReplies, directive, null);
    }

    private AgentTurnResponse respondWithoutModel(ConversationState state, String reply,
                                                  List<QuickReply> quickReplies, UiDirective directive,
                                                  AgentTurnResponse.Notice notice) {
        return respondWithoutModel(state, reply, quickReplies, directive, notice, null);
    }

    /**
     * 携带提示块与「仍然有效的确认卡」的出口。
     *
     * <p>卡片是原样带回来的那张（同一个 {@code confirmationId}），不是新生成的——
     * 提示块只多占一屏，不新建待办，也不让老人已经看到的确认按钮失效。
     */
    private AgentTurnResponse respondWithoutModel(ConversationState state, String reply,
                                                  List<QuickReply> quickReplies, UiDirective directive,
                                                  AgentTurnResponse.Notice notice, ConfirmationCard confirmation) {
        return finishWithoutModel(state, new AgentTurnResponse(state.id, state.stage.name(), reply, quickReplies,
                plan(state), confirmation, null, traces.findByConversation(state.id), null, reply, directive, notice));
    }

    private AgentTurnResponse finish(ConversationState state, AgentTurnResponse response) {
        if (Boolean.TRUE.equals(deferFinalization.get())) return response;
        response = confirmations.reconcile(state, response);
        turnProgress.mark(state.id, TurnProgress.Kind.ANSWERING);
        ReplyContext context = replyContextBuilder.build(state, response, knownFacts(state),
                conversations.recentMessages(state.id));
        String reply = answerGenerator.generate(context);
        AgentTurnResponse finalized = new AgentTurnResponse(response.conversationId(), response.stage(), reply,
                response.quickReplies(), response.plan(), response.confirmation(), response.result(),
                response.toolTraces(), taskProgress(state), authoritativeSpeech(response, reply), response.uiDirective(),
                response.notice());
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

    @Override
    AgentTurnResponse finishWithoutModel(ConversationState state, AgentTurnResponse response) {
        if (Boolean.TRUE.equals(deferFinalization.get())) return response;
        response = confirmations.reconcile(state, response);
        AgentTurnResponse finalized = new AgentTurnResponse(response.conversationId(), response.stage(), response.reply(),
                response.quickReplies(), response.plan(), response.confirmation(), response.result(),
                response.toolTraces(), taskProgress(state), authoritativeSpeech(response, response.reply()),
                response.uiDirective(), response.notice());
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
        state.pendingAppointmentIds = List.of();
        confirmations.clear(state);
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

    private String acknowledgement(ExtractedFacts facts, String fallback) {
        String value = facts.acknowledgement();
        return value == null || value.isBlank() ? fallback : value + " " + fallback;
    }

    /**
     * 关系上下文交给提示词。老人端返回“本人自办”，此时提示词与合并前逐字相同。
     * 名字和关系都由 Java 从已验证的会话身份取，模型无从填写。
     */
    private AgentContext.Identity identityOf(ConversationState state) {
        if (!state.caregiving()) return AgentContext.Identity.SELF;
        return new AgentContext.Identity(elderName(state.actorUserId), elderName(state.userId),
                state.relationLabel, state.actorRole);
    }

    /** 记忆的 key。改这些名字等于让旧记忆失效，所以它们是对外可见的「事实名」。 */
    private static final String MEMORY_HOSPITAL = "habit.hospital";
    private static final String MEMORY_DEPARTMENT = "habit.department";
    private static final String MEMORY_PERIOD = "habit.period";

    /**
     * 把这次办成的事记下来，下一段对话里模型按需查得到。
     *
     * <p>只在确认门禁放行、预约真的写进库之后调用——记的是「实际发生的事」，
     * 不是老人在某一轮随口提过的想法。模型不参与、前端也不能调，它只是已确认动作的副产品。
     *
     * <p><b>措辞必须是中性的历史事实，不能写成「常去的医院是……」「习惯上午复诊」。</b>
     * 这里记的是**一次**已确认的预约，一次不等于习惯；写成「常去」，模型下一轮就会拿它
     * 去劝老人「您常去这家，还约这儿吧」——那句话没有任何事实支撑，而且它已经绕开了
     * 「一次预约不能自动成为长期习惯」这条要求。要谈「常去」，只能由
     * {@link ProfileQueryService} 在真的统计出足够多次数之后说出来。
     *
     * <p>时段按号源的实际时间归纳成上午/下午，而不是记具体时刻：号源是时刻，
     * 把「9:30」当成结论下次会去推一个并不合适的具体时间。
     *
     * <p>{@code kind} 一律用 {@link MemoryStore#KIND_HISTORY}：这三条都是同一类东西——
     * 系统从已确认预约里沉淀的历史信息，没有一条是老人明确要求的偏好。原来给时段标的
     * {@code PREFERENCE} 与事实不符，会让读取侧的同一条记录有两个互相矛盾的标签。
     */
    @Override
    void rememberBookingPreferences(ConversationState state) {
        Slot slot = state.selectedSlot;
        if (slot == null) return;
        memories.remember(state.userId, MEMORY_HOSPITAL, MemoryStore.KIND_HISTORY,
                "最近一次确认预约的医院是" + slot.hospitalName(),
                MemoryStore.SOURCE_CONFIRMED_BOOKING, state.id);
        memories.remember(state.userId, MEMORY_DEPARTMENT, MemoryStore.KIND_HISTORY,
                "最近一次确认预约的科室是" + slot.department(),
                MemoryStore.SOURCE_CONFIRMED_BOOKING, state.id);
        memories.remember(state.userId, MEMORY_PERIOD, MemoryStore.KIND_HISTORY,
                "最近一次确认预约的时段是"
                        + (slot.time() != null && slot.time().getHour() < 12 ? "上午" : "下午"),
                MemoryStore.SOURCE_CONFIRMED_BOOKING, state.id);
    }

    private String knownFacts(ConversationState state) {
        return "用户=" + state.userId +
                (state.caregiving()
                        ? "；本次服务对象=" + elderName(state.userId)
                        + "；操作者=" + elderName(state.actorUserId) + "（" + state.relationLabel + "）"
                        + "；本会话是代他人办理，上面所有预约、材料与路线都属于服务对象，不属于操作者"
                        : "") +
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
        // 这里<b>不</b>再拼长期记忆。原来这里挂过一句 memories.digest(state.userId)，
        // 它把全部 active 记忆（不分来源）原样塞进每一轮提示词：一条系统自己归纳的
        // 「常去的医院是市一院」到了模型眼里和老人亲口说的话没有区别，来源分类在上游
        // 分得再干净也没有用。画像现在统一走 profile.memorySummary 按需查——
        // 查不到就没有，查到了也带着来源与更新时间。默认上下文里一个字的记忆都没有。
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
        // 日期用 DATE_LABEL（2026年9月15日），与 plan.date / result.date 及全站其他地方一致。
        // 老人端助手页把这一行原样当「当前复诊办理」的摘要显示，ISO 写法（2026-09-15）太像编号。
        String summary = state.hospital == null ? "复诊办理尚未选择医院"
                : state.department == null ? state.hospital + " · 待选择科室"
                : state.date == null ? state.hospital + " · " + state.department + " · 待选择日期"
                : state.hospital + " · " + state.department + " · " + state.date.format(DATE_LABEL);
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

    /**
     * 这个号源是不是已经过去了。
     *
     * <p>按业务时区判断，不用服务器默认时区：开发容器是 UTC，北京时间凌晨那八小时
     * 服务器眼里的「现在」还停在昨天，会把刚过去的时段算成还没到。
     *
     * <p>号源查询本身就只返回此刻之后的时段，所以这里只兜「草稿或确认卡生成之后时间又过去了」
     * 这一种情况——它恰恰是最容易漏的：确认卡上没有时间闸门，老人停多久都行。
     *
     * <p>「正好等于此刻」算已过去：号源查询的口径是 {@code appointment_time > 当前时间}，
     * 差一毫秒都算过点。这里跟它对齐，否则 09:00 整点确认时能约到一条查询永远查不出来的时段。
     */
    private boolean slotAlreadyPassed(Slot slot) {
        return slot != null && !LocalDateTime.of(slot.date(), slot.time()).isAfter(clock.now());
    }

    /**
     * 这次确认是不是「照草稿开新预约」（建新预约或改期）。
     *
     * <p>取消类 pendingAction 一律不算——取消针对的是库里已有的预约，草稿里那份 selectedSlot
     * 只是上次办理留下的残留，过期与否都不该拦取消。
     */
    private boolean booksFromDraft(ConversationState state) {
        return state.pendingAction == null || "CREATE".equals(state.pendingAction);
    }

    /**
     * 时间已经过去的时段一律作废，退回重选日期。
     *
     * <p>只清「时间」这一格不够用：真正过去的是那一天里的时段，重新问一次日期最省事，
     * 也跟「日期已经过去」那条既有处理保持一致（{@code querySlots} 就是这么做的）。
     * 医院、科室、陪同、出行这些已经问过的信息都留着，老人不用重说一遍。
     */
    @Override
    AgentTurnResponse reaskAfterPassedSlot(ConversationState state) {
        String passed = slotLabel(state.selectedSlot);
        resetAfterDate(state);
        return askDate(state, "您选的" + passed + "已经过去了，这个时间不能再约，请重新选择复诊日期。");
    }
}
