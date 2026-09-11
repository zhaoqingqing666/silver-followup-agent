package com.team.silveragent.application;

import com.team.silveragent.agent.AgentContext;
import com.team.silveragent.agent.QwenFactExtractor;
import com.team.silveragent.agent.OpenReplyGenerator;
import com.team.silveragent.agent.ToolCallingAgent;
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
import com.team.silveragent.service.VlService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class FollowupAgentService {
    private static final String USER_ID = "user-001";
    /** 跨轮累积的多任务/约束上限，防止长对话里这些列表无限膨胀。 */
    private static final int MAX_REMEMBERED_ITEMS = 12;
    private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("M月d日");
    private static final DateTimeFormatter TIME_LABEL = DateTimeFormatter.ofPattern("HH:mm");

    /** 药品/材料图片上常见的字段标签，用于从 OCR 原文里按标签截取某一段（如"批准文号"）。 */
    private static final List<String> OCR_FIELD_LABELS = List.of(
            "成份", "成分", "性状", "适应症", "用法用量", "用法", "不良反应", "禁忌", "注意事项",
            "贮藏", "储存", "批准文号", "国药准字", "有效期", "生产日期", "生产厂家",
            "规格", "包装", "功能主治", "主治");

    /** 剂量写法（如"0.5克/片""10mg""100毫升"），配合药名关键词判断图上是不是药品。 */
    private static final Pattern DOSAGE = Pattern.compile(
            "\\d+(\\.\\d+)?\\s*(mg|MG|g|G|毫克|克|微克|μg|ml|mL|毫升|片|粒|袋|支|丸)");

    private final AppointmentTool appointmentTool;
    private final ScheduleTool scheduleTool;
    private final TravelTool travelTool;
    private final FamilyNotificationTool familyTool;
    private final MaterialChecklistTool materialTool;
    private final ToolTraceStore traces;
    private final QwenFactExtractor extractor;
    private final OpenReplyGenerator openChat;
    private final ToolCallingAgent toolAgent;
    private final VlService vlService;
    private final ConversationStore conversations;
    private final ConversationLifecycle lifecycle;
    private final AppointmentRecordStore appointmentRecords;
    private final MemoryStore memories;
    private final Map<String, ConversationState> sessions = new ConcurrentHashMap<>();


    public FollowupAgentService(
            AppointmentTool appointmentTool,
            ScheduleTool scheduleTool,
            TravelTool travelTool,
            FamilyNotificationTool familyTool,
            MaterialChecklistTool materialTool,
            ToolTraceStore traces,
            QwenFactExtractor extractor,
            OpenReplyGenerator openChat,
            ToolCallingAgent toolAgent,
            VlService vlService,
            ConversationStore conversations,
            ConversationLifecycle lifecycle,
            AppointmentRecordStore appointmentRecords,
            MemoryStore memories) {
        this.appointmentTool = appointmentTool;
        this.scheduleTool = scheduleTool;
        this.travelTool = travelTool;
        this.familyTool = familyTool;
        this.materialTool = materialTool;
        this.traces = traces;
        this.extractor = extractor;
        this.openChat = openChat;
        this.toolAgent = toolAgent;
        this.vlService = vlService;
        this.conversations = conversations;
        this.lifecycle = lifecycle;
        this.appointmentRecords = appointmentRecords;
        this.memories = memories;
    }

    /**
     * 「＋新对话」：立即结束当前会话，另开一段新会话。
     *
     * 唯一的例外是当前会话还是完全空的（用户一句话都没说过）——那就直接沿用，
     * 免得连点「新对话」在历史里堆出一串空会话。
     */
    public AgentTurnResponse start() {
        String active = conversations.activeSessionId().orElse(null);
        if (active != null) {
            if (conversations.isEmptyConversation(active)) {
                ConversationState existing = requireSession(active);
                return conversations.lastResponse(active).orElseGet(() -> greeting(existing));
            }
            conversations.close(active, "NEW_CONVERSATION");
            sessions.remove(active);
        }
        return greeting(newConversation());
    }

    public ConversationHistoryResponse resume(String conversationId) {
        ConversationState state = requireSession(conversationId);
        AgentTurnResponse current = conversations.lastResponse(conversationId)
                .orElseGet(() -> AgentTurnResponse.message(conversationId, state.stage.name(),
                        "已恢复上次办理进度。", List.of(q("继续办理", "CONTINUE", ""))));
        return new ConversationHistoryResponse(conversationId, state.stage.name(),
                conversations.status(conversationId), conversations.messages(conversationId), current);
    }

    /** 历史对话列表（按更新时间倒序） */
    public List<com.team.silveragent.domain.model.ConversationSummary> listConversations() {
        return conversations.summaries();
    }

    /**
     * 自由语言入口：每一轮都会携带当前状态和最近对话调用语言理解模型。
     */
    public AgentTurnResponse chat(String conversationId, String message, Boolean isVoice) {
        ConversationState state = resolveActive(conversationId);
        String value = message == null ? "" : message.trim();
        if (value.isEmpty()) return respond(state, "我没有听清，请再说一次。", List.of(q("重新说一遍", "ASK_HUMAN_INPUT", "")));
        boolean voice = Boolean.TRUE.equals(isVoice);

        AgentContext context = new AgentContext(state.stage.name(), knownFacts(state),
                LocalDate.now(), conversations.recentMessages(state.id), conversations.recentVision(state.id));
        ExtractedFacts facts = extractor.extract(value, context, voice);
        // 先记下这轮理解到的多任务与动态约束，再走下面的分支。
        // 放在这里是为了让查询预约、紧急拦截、医疗拒答、图片追问等所有提前 return 的路径
        // 都不会把用户说过的诉求丢掉——只记录，不改变任何流程走向。
        rememberDemand(state, facts);
        conversations.addMessage(state.id, "user", value);

        // 跨页面/查询预约：无论语音或文字都直接执行，不走语音确认
        if ("QUERY_APPOINTMENTS".equals(facts.intent())) return queryAppointments(state);
        if ("QUERY_AVAILABLE_DATES".equals(facts.intent())) return queryAvailableDates(state);
        if ("VIEW_TASKS".equals(facts.intent())) return viewTasks(state);

        // 语音输入：如果提取到了关键事实，先复述确认，让用户确认后再继续流程
        if (voice && hasKeyFacts(facts)) {
            String ack = facts.acknowledgement() != null && !facts.acknowledgement().isBlank()
                    ? facts.acknowledgement()
                    : "我听到您说：「" + value + "」。请问我理解得对吗？";
            // 先把事实应用到状态（这样用户确认后可以直接继续），但 reply 只展示确认
            ConversationState.Stage previousStage = state.stage;
            applyFacts(state, facts);
            return respond(state, ack, List.of(
                    q("对的，继续办理", "CONTINUE", ""),
                    q("不对，我重新说", "ASK_HUMAN_INPUT", "")));
        }

        if ("EMERGENCY".equals(facts.intent()) || containsAny(value, "胸痛", "呼吸困难", "昏迷", "大出血", "喘不上气")) {
            return respond(state, "这可能是紧急情况。请立即联系身边家属，并拨打120或前往最近的急诊。现在不继续普通预约流程。",
                    List.of(q("联系人工帮助", "CONTACT_HUMAN", "")));
        }

        // ---- 明确要听"AI 自己刚才说的那句话"（"把你刚才说的话念出来""把上一条回答读一下"）。
        //      必须放在图片通道之前：这些话里也带"念/读"，但它指的是上一轮 AI 的回答，
        //      不是图片原文，不能走下面的 OCR 朗读通道。 ----
        if (isReadAssistantAnswerRequest(value)) {
            String previous = lastAssistantReply(context);
            if (previous != null) {
                // 连续两次"把上一条回答读一下"时，上一条本身就是这句回声，不能再套一层前缀，
                // 否则念出来是"我刚才说的是：我刚才说的是：……"，老人听着莫名其妙。
                if (previous.startsWith("我刚才说的是：")) {
                    previous = previous.substring("我刚才说的是：".length()).trim();
                }
                return respond(state, "我刚才说的是：\n\n" + previous,
                        List.of(q("继续办理复诊", "CONTINUE", ""), q("查看复诊事项", "OPEN_TASKS", "")));
            }
        }

        // ---- 图片追问：这段对话里之前上传过图片，本轮又问起它（"提取文字""刚才那张图片"）。
        //      识别结果已经存在库里，直接复用，既不再调一次视觉模型，也绝不说"没有收到图片"。 ----
        if (!context.visionSummary().isBlank() && (refersToPreviousImage(value) || isImageTextQuestion(value))) {
            // 优先从完整 OCR 原文直接回答（"全部念出来/规格/批准文号"等）；答不上来再交给模型。
            AgentTurnResponse ocrReply = imageTextAnswer(state, context, value);
            if (ocrReply != null) return ocrReply;
            AgentTurnResponse modelReply = openChatAnswer(state, context, value, facts.intent());
            if (modelReply != null) return modelReply;
            // 模型通道不可用：用存下来的识别结果如实回答，不让老人听到"答不上来"
            return respond(state, previousImageReply(context),
                    List.of(q("继续办理复诊", "CONTINUE", ""), q("查看复诊事项", "OPEN_TASKS", "")));
        }

        // ---- 双轨通道 1：就医分诊问题（"我该挂什么科"）。规则先行，分诊结论必须稳妥，不交给大模型自由发挥 ----
        if (containsAny(value, "去哪个科室", "去哪个科", "挂什么科", "挂哪个科", "该挂什么科", "该挂哪个科",
                "看什么科", "看哪个科", "什么科室", "哪个科室", "挂什么号", "挂哪个号", "去哪个诊室", "挂什么门诊", "什么门诊")) {
            String dept = referralDepartment(value);
            String reply = dept != null
                    ? "按您说的情况，初步看应该挂：" + dept + "。这只是一般性参考，具体以医院导诊台和接诊医生的判断为准；拿不准的话可以带病历先到导诊台问一下。"
                    : "先别急。帮您判断挂哪个科室前，我需要知道您主要哪里不舒服，或者医生给过什么诊断。您也可以先按现有的复诊科室继续办理预约。";
            return respond(state, reply, List.of(
                    q("帮我预约复诊", "CONTINUE", ""),
                    q("查看复诊事项", "OPEN_TASKS", "")));
        }

        // ---- 双轨通道 2：真·模型回话。只在"还没开始办预约、且是问答类问题"时启用，
        //      避免打断预约办理流程；也不处理语音确认（上面已拦截）。 ----
        boolean coldStart = state.hospital == null;
        // MEDICAL_ADVICE 也放进来：老人问"药盒上写了几片""说明书上还写了什么"这类
        // 照着图片读文字的问题，模型能依据识别结果如实回答。真正的安全边界交给系统提示词
        // 和下面第 197 行的兜底把关，不再用一句"不能诊断"把所有沾药的问题一律堵死。
        boolean openQuestion = "PROVIDE_INFORMATION".equals(facts.intent())
                || "HEALTH_ADVICE".equals(facts.intent())
                || "MEDICAL_ADVICE".equals(facts.intent());
        if (coldStart && openQuestion && !hasBookingSlots(facts)) {
            AgentTurnResponse modelReply = openChatAnswer(state, context, value, facts.intent());
            if (modelReply != null) return modelReply;
            // 模型未配置/调用失败：冷启动诚实兜底，不再答非所问地追问"请告诉我就诊医院"
            if ("PROVIDE_INFORMATION".equals(facts.intent())) {
                return respond(state, "抱歉，这个问题我暂时还答不上来。我是专门帮您办理复诊预约的助手，可以直接帮您预约复诊或查看复诊事项。",
                        List.of(q("帮我预约复诊", "CONTINUE", ""), q("查看复诊事项", "OPEN_TASKS", "")));
            }
        }

        if ("MEDICAL_ADVICE".equals(facts.intent()) || containsAny(value, "怎么用药", "怎么吃药", "药怎么吃", "药量", "停药", "加药", "减药", "副作用", "剂量", "诊断", "检查结果", "是不是得了")) {
            return respond(state, "我只能协助办理复诊，不能诊断疾病、解释检查结果或调整用药。请咨询医生或专业医疗机构。",
                    List.of(q("继续办理复诊", "CONTINUE", ""), q("咨询人工", "CONTACT_HUMAN", "")));
        }
        if ("HEALTH_ADVICE".equals(facts.intent()) || containsAny(value, "科普", "平时要注意", "平时注意", "注意些什么", "注意啥", "日常注意", "日常要注意", "怎么注意", "怎么预防", "如何预防", "预防", "养生", "保健", "吃什么好", "吃点什么好", "饮食上", "饮食注意", "高血压", "血压高", "血压低", "低血压", "血糖高", "血糖低", "高血糖", "低血糖", "血脂高", "高血脂", "尿酸高", "糖尿病", "生活方式", "注意事项")) {
            return respond(state,
                    "这属于健康科普问题。给您几点一般性提醒：保持规律作息、均衡饮食、适当运动，遵医嘱规律用药、定期复诊，不要自行停药或调整剂量。" +
                    "需要说明的是，以上仅为通用科普内容，不能替代医生的专业诊断与用药建议；具体到您个人的病情，请务必咨询专业医生。",
                    List.of(q("好的，知道了", "CONTINUE", ""), q("查看复诊事项", "OPEN_TASKS", "")));
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

        // 只有用户明确提到"复诊/办理/预约"等办理字眼（或带办理类意图/事实），才进入办理流程；
        // 否则不硬把用户拽去"请告诉我就诊医院"，避免把问答/闲聊误带进预约办理。
        if (state.hospital == null && !explicitBookingRequest(value, facts)) {
            return respond(state, "我是帮您办理复诊预约的助手。您可以直接说「帮我预约复诊」，也可以继续问我图片、药品或别的问题。",
                    List.of(q("帮我预约复诊", "CONTINUE", ""), q("查看复诊事项", "OPEN_TASKS", "")));
        }
        return advance(state, facts);
    }

    /**
     * 图片入口（复诊材料走对话问答）：一次最多 3 张图，作为一轮用户消息进入对话流。
     *
     * 每张图只调一次视觉模型（{@link VlService#recognizeAll}，多张并发），一次拿回
     * 「描述 + OCR 原文 + 关键信息」，合并成一份当前会话的视觉上下文
     * （描述合并、OCR 原文合并），后续"全部念出来/规格/批准文号"等追问直接从这份 OCR 读取。
     * 再上传新一批图片时，旧的视觉上下文会被裁剪替换，避免上一批内容串到这一批。
     */
    public AgentTurnResponse handleImages(String conversationId, List<String> imageDataUrls, String hint) {
        ConversationState state = resolveActive(conversationId);
        String note = hint == null ? "" : hint.trim();

        // 最多 3 张，过滤空值；同一张图重复上传（老人连点、同图多拍）先按图片内容去重：
        // 重复识别同样的图既多等一轮模型，回复里还会把一模一样的描述重复念好几遍。
        // 前端选图时也做了一层同样的去重，这里是兜底（直接调接口的场景同样受保护）。
        List<String> images = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int received = 0;
        if (imageDataUrls != null) {
            for (String url : imageDataUrls) {
                if (url == null || url.isBlank()) continue;
                received++;
                // 同一次里最多认 3 张，且同一张图只留第一份（重复的图不占名额）
                if (images.size() < 3 && seen.add(imagePayload(url))) images.add(url);
            }
        }
        if (images.isEmpty()) {
            return respond(state, "没有收到图片，请重新选择图片上传。",
                    List.of(q("继续办理复诊", "CONTINUE", ""), q("查看复诊事项", "OPEN_TASKS", "")));
        }
        for (String url : images) {
            if (lifecycle.imageTooLarge(url)) {
                return respond(state, lifecycle.imageTooLargeText(),
                        List.of(q("继续办理复诊", "CONTINUE", ""), q("查看复诊事项", "OPEN_TASKS", "")));
            }
        }

        // 图片先落库（一条消息 + 每张图一个附件），这样后续轮次说"刚才那张图片"时还找得回来。
        long messageId = conversations.addMessage(state.id, "user",
                "[图片]" + (note.isBlank() ? "" : " " + note), "IMAGE", null);
        long firstAttachmentId = 0L;
        for (String url : images) {
            long id = conversations.addAttachment(state.id, messageId, "IMAGE", url);
            if (firstAttachmentId == 0L) firstAttachmentId = id;
        }

        AgentContext context = new AgentContext(state.stage.name(), knownFacts(state),
                LocalDate.now(), conversations.recentMessages(state.id), conversations.recentVision(state.id));

        if (!vlService.isEnabled()) {
            return respond(state, "图片识别功能暂时没有开启，我暂时看不清图片内容。您可以直接告诉我这是什么材料，比如“病历”“出院小结”“检查单”，我帮您看复诊需要带什么。",
                    List.of(q("继续办理复诊", "CONTINUE", ""), q("查看复诊事项", "OPEN_TASKS", "")));
        }

        // 每张图只调一次视觉模型（多张并发），一次拿回「描述 + OCR 原文 + 关键信息」。
        List<VlService.VisionResult> recognized = vlService.recognizeAll(images, note);
        StringBuilder description = new StringBuilder();
        StringBuilder ocr = new StringBuilder();
        StringBuilder keyFacts = new StringBuilder();
        for (VlService.VisionResult result : recognized) {
            if (result == null) continue; // 这一张失败了，其余几张照样能用
            if (result.description() != null && !result.description().isBlank()) {
                if (description.length() > 0) description.append('\n');
                description.append(result.description().trim());
            }
            if (result.ocr() != null && !result.ocr().isBlank()) {
                if (ocr.length() > 0) ocr.append('\n').append('\n');
                ocr.append(result.ocr().trim());
            }
            if (result.keyFacts() != null && !result.keyFacts().isBlank()) {
                if (keyFacts.length() > 0) keyFacts.append('\n');
                keyFacts.append(result.keyFacts().trim());
            }
        }
        String mergedDescription = description.toString();
        String mergedOcr = ocr.toString();
        String mergedKeyFacts = keyFacts.toString();
        if (mergedDescription.isBlank() && !mergedOcr.isBlank()) {
            // 模型只吐了文字没给描述：拿 OCR 原文兜底，不白丢已经识别出来的内容。
            mergedDescription = imageReadPrefix(mergedOcr) + mergedOcr;
        }
        if (mergedDescription.isBlank()) {
            return respond(state, "这些图片我看得不太清楚，麻烦重新拍得清晰些，或者直接打字告诉我它是什么材料。",
                    List.of(q("继续办理复诊", "CONTINUE", ""), q("查看复诊事项", "OPEN_TASKS", "")));
        }

        // 合并成一份视觉上下文（挂在第一张附件上），后续轮次直接复用，不必重新调用视觉模型。
        // 关键信息一并折进描述字段落库，既不改表结构，也不让后续"刚才那张图"丢掉这部分内容。
        String storedDescription = mergedKeyFacts.isBlank()
                ? mergedDescription
                : mergedDescription + "\n\n关键信息：\n" + mergedKeyFacts;
        conversations.saveVisionResult(firstAttachmentId, state.id, note, storedDescription, mergedOcr);

        // 视觉识别是一次真实执行，照实记一笔（参数=用户的补充说明+这张图的字段分类，结果=真实识别文本）。
        // images=实际识别的张数，received=收到的张数，两者不等就说明有重复图被合并了。
        String kind = imageKind(mergedOcr);
        traces.record(state.id, "vision.recognize",
                Map.of("hint", note, "images", images.size(), "received", received,
                        "kind", kind == null ? "未识别" : kind),
                Map.of("description", mergedDescription), true);

        // 交给回答模型时把 OCR 原文一起带上：问"规格/批准文号/副作用"时能照原文如实念出。
        String grounding = mergedOcr.isBlank()
                ? mergedDescription
                : mergedDescription + "\n\n图片原文文字：\n" + mergedOcr;
        String question = note.isBlank()
                ? "请帮我看看这些图片里的药品或材料是什么、有什么用，复诊要带什么、要注意什么。"
                : note;

        // 只有确实要查真实数据（材料清单、药品知识、号源）才跑工具智能体——它最多要 3 轮模型循环，
        // 图片问答多数用不上，默认走一次普通问答即可，省掉这几轮等待。
        String reply = needsTool(note, mergedDescription, mergedOcr)
                ? toolAgent.answer(state.id, USER_ID, context, question, grounding)
                : null;
        if (reply == null) reply = openChat.answerAboutImage(context, grounding, note);
        if (reply == null) reply = mergedDescription; // 模型通道都不可用 → 直接采用视觉模型的描述，不编造
        return respond(state, reply,
                List.of(q("继续办理复诊", "CONTINUE", ""), q("查看复诊事项", "OPEN_TASKS", "")));
    }

    /**
     * 图片去重指纹：取 data URL 里的 base64 正文。
     * 同一张图哪怕 MIME 声明写成 image/jpg 或 image/jpeg，正文也是一样的，都算重复。
     */
    private static String imagePayload(String dataUrl) {
        int comma = dataUrl.indexOf(',');
        return comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
    }

    /**
     * 明确按钮入口：不调用大模型，直接按action和value更新状态。
     */
    public AgentTurnResponse act(String conversationId, String action, String value) {
        ConversationState state = resolveActive(conversationId);
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
        ConversationState state = resolveActive(conversationId);
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
                memories.save(USER_ID, state.hospital, state.department,
                        state.date, "CANCELLED", state.appointmentId, state.id);
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
            memories.save(USER_ID, state.hospital, state.department,
                    state.selectedSlot.date(), "CONFIRMED", appointmentId, state.id);
            ResultCard card = new ResultCard(appointmentId, state.hospital, state.department,
                    state.date.format(DATE_LABEL), state.selectedSlot.time().format(TIME_LABEL), state.materials,
                    state.travelPlan == null ? "无需出行提醒" : state.travelPlan.departureAt().format(TIME_LABEL),
                    reminderStatus, familyStatus);
            return finish(state, new AgentTurnResponse(state.id, state.stage.name(),
                    "已经办理完成。事项页面现在会读取这条真实预约记录。",
                    List.of(q("查看复诊事项", "OPEN_TASKS", ""), q("取消预约", "CANCEL_APPOINTMENT", "")),
                    plan(state), null, card, traces.findByConversation(state.id), null));
        } catch (RuntimeException error) {
            return respond(state, "提交时出现问题：" + error.getMessage() + "。没有重复提交，您可以重新查询号源。",
                    List.of(q("重新查询", "RETRY_QUERY", ""), q("咨询人工", "CONTACT_HUMAN", "")));
        }
    }

    public Map<String, Object> modelStatus() {
        return Map.of("mode", extractor.mode(), "model", extractor.model(), "secretStored", extractor.hasApiKey());
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
            return respond(state, state.date.format(DATE_LABEL) +
                    "暂时没有可预约时段。我查到了附近日期的可预约时段，请选择一个：", choices);
        }
        return respond(state, state.date.format(DATE_LABEL) +
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
                List.of(), plan(state), card, null, traces.findByConversation(state.id), null));
    }

    private AgentTurnResponse cancelTask(ConversationState state) {
        state.stage = ConversationState.Stage.CANCELLED;
        return respond(state, "好的，整个办理任务已取消，没有提交预约或发送通知。",
                List.of(q("重新开始", "CHANGE_HOSPITAL", "")));
    }

    /** 查询用户已有的复诊预约：复用 AppointmentRecordStore.allFor 工具能力。 */
    private AgentTurnResponse queryAppointments(ConversationState state) {
        List<AppointmentRecordStore.AppointmentView> list = appointmentRecords.allFor(USER_ID);
        if (list.isEmpty()) {
            return respond(state, "您目前没有未完成的复诊预约。",
                    List.of(q("开始预约", "CONTINUE", ""), q("查看事项", "OPEN_TASKS", "")));
        }
        List<String> lines = list.stream().map(a -> "· " + a.hospital() + a.department() + " " +
                a.date().format(DATE_LABEL) + " " + a.time().format(TIME_LABEL) +
                "（" + ("CONFIRMED".equals(a.status()) ? "已确认" : a.status()) + "）").toList();
        String summary = "您有 " + list.size() + " 条预约：\n" + String.join("\n", lines);
        return respond(state, summary,
                List.of(q("查看复诊事项", "OPEN_TASKS", ""), q("继续办理", "CONTINUE", "")));
    }

    /** "最近哪天能约/有哪些可预约日期"：跨医院/科室的最近可预约日期概览。 */
    private AgentTurnResponse queryAvailableDates(ConversationState state) {
        List<Slot> upcoming = appointmentTool.queryUpcomingAvailable(state.id);
        if (upcoming.isEmpty()) {
            return respond(state, "近期没有查到可预约的模拟号源，您可以稍后再来查询。",
                    List.of(q("开始预约", "CONTINUE", ""), q("查看复诊事项", "OPEN_TASKS", "")));
        }
        // 按日期升序分组，同一天可能有多家医院/科室有号源
        Map<LocalDate, List<Slot>> byDate = new LinkedHashMap<>();
        for (Slot slot : upcoming) {
            byDate.computeIfAbsent(slot.date(), key -> new ArrayList<>()).add(slot);
        }
        List<String> lines = new ArrayList<>();
        int shown = 0;
        for (Map.Entry<LocalDate, List<Slot>> entry : byDate.entrySet()) {
            if (shown == 6) {
                lines.add("· 之后还有可约日期，如需请告诉我。");
                break;
            }
            List<Slot> daySlots = entry.getValue();
            Slot first = daySlots.get(0);
            boolean singleHospital = daySlots.stream().map(Slot::hospitalId).distinct().count() == 1;
            String where = singleHospital
                    ? "（" + cleanName(first.hospitalName()) + "·" + first.department() + "）"
                    : "（多家医院均有号）";
            lines.add("· " + entry.getKey().format(DATE_LABEL) + " " + first.time().format(TIME_LABEL)
                    + "起共" + daySlots.size() + "个时段" + where);
            shown++;
        }
        String summary = "最近可预约日期如下：\n" + String.join("\n", lines);
        return respond(state, summary,
                List.of(q("我要预约", "CONTINUE", ""), q("查看复诊事项", "OPEN_TASKS", "")));
    }

    /** 打开复诊事项页：通过 autoAction=OPEN_TASKS 让前端自动跳转。 */
    private AgentTurnResponse viewTasks(ConversationState state) {
        return respondWithAuto(state, "好的，已为您打开复诊事项页面。",
                List.of(q("查看复诊事项", "OPEN_TASKS", "")), "OPEN_TASKS");
    }

    private AgentTurnResponse respondWithCancelCard(ConversationState state) {
        ConfirmationCard card = new ConfirmationCard("确认取消已经预约的复诊吗？",
                List.of("取消预约：" + slotLabel(state.selectedSlot), "释放该模拟号源"),
                "取消后原预约失效；如果仍需复诊，需要重新预约。",
                "确认取消预约", "保留预约");
        return finish(state, new AgentTurnResponse(state.id, state.stage.name(),
                "取消预约属于重要操作，需要您明确确认。", List.of(), plan(state), card, null,
                traces.findByConversation(state.id), null));
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
        return respondWithAuto(state, reply, quickReplies, null);
    }

    /** 带 autoAction 的回复：前端收到 autoAction=OPEN_TASKS 会自动跳转事项页。 */
    private AgentTurnResponse respondWithAuto(ConversationState state, String reply,
                                               List<QuickReply> quickReplies, String autoAction) {
        return finish(state, new AgentTurnResponse(state.id, state.stage.name(), reply, quickReplies,
                null, null, null, traces.findByConversation(state.id), autoAction));
    }

    private AgentTurnResponse respondWithPlan(ConversationState state, String reply, List<QuickReply> quickReplies) {
        return finish(state, new AgentTurnResponse(state.id, state.stage.name(), reply, quickReplies,
                plan(state), null, null, traces.findByConversation(state.id), null));
    }

    private AgentTurnResponse finish(ConversationState state, AgentTurnResponse response) {
        String reply = consumeNotice(state, response.reply());
        conversations.addMessage(state.id, "assistant", reply);

        ConversationStore.SessionMeta before = conversations.meta(state.id).orElse(null);
        conversations.touch(state.id);
        ConversationStore.SessionMeta after = conversations.meta(state.id).orElse(null);
        if (before == null || after == null) {
            AgentTurnResponse persisted = withReply(response, state.id, reply);
            conversations.save(state, persisted);
            return persisted;
        }

        ConversationLifecycle.Verdict verdict = lifecycle.evaluate(
                before.turnCount(), before.estimatedTokens(), after.turnCount(), after.estimatedTokens());

        // 到了轮次或 Token 上限：先给这一段收个尾，再自动切到一段新会话。
        if (verdict.close()) {
            String closing = reply + lifecycle.closeText(verdict);
            conversations.replaceLastAssistantMessage(state.id, closing);
            conversations.save(state, withReply(response, state.id, closing));
            conversations.close(state.id, verdict.reason());
            sessions.remove(state.id);

            ConversationState fresh = newConversation();
            conversations.save(fresh, null);
            return withReply(response, fresh.id, closing);
        }

        // 只是接近上限：提示一下，不关闭会话。
        if (verdict.warn()) {
            reply = reply + lifecycle.warningText();
            conversations.replaceLastAssistantMessage(state.id, reply);
        }
        AgentTurnResponse persisted = withReply(response, state.id, reply);
        conversations.save(state, persisted);
        return persisted;
    }

    /** 固定执行步骤已经覆盖的任务类型，不必在计划里重复展示一遍。 */
    private static final Set<String> COVERED_TASK_KINDS = Set.copyOf(ExtractedFacts.TASK_KINDS);

    private PlanCard plan(ConversationState state) {
        List<String> tasks = new ArrayList<>(List.of(
                "查询可预约日期", "选择复诊时间", "生成复诊材料清单",
                "检查用户日程是否冲突", "生成出发时间建议", "创建复诊提醒", "通知指定家属"));
        // 用户提出的、固定步骤装不下的部分补在计划后面：
        // 归类不到固定事项的任务、硬性要求、偏好、补充诉求。
        // 不这么做的话，用户说的话只有一部分会出现在计划里，剩下的就静默消失了。
        for (ExtractedFacts.TaskItem item : state.tasks) {
            if (!COVERED_TASK_KINDS.contains(item.kind())) addOnce(tasks, item.label());
        }
        for (String item : state.constraints) addOnce(tasks, "按您的要求：" + item);
        for (String item : state.preferences) addOnce(tasks, "尽量满足：" + item);
        for (String item : state.additionalRequests) addOnce(tasks, "另外记下：" + item);
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

    /**
     * 累积记录这轮理解到的多任务与动态约束。
     *
     * <p>与 {@link #applyFacts} 的区别：槽位是"最新一次覆盖"，而这些诉求是"说过就留着"——
     * 用户后面补一句"改成9月20日"，之前说的"出发前提醒我""告诉女儿"不应该跟着消失。
     * 因此 {@link #resetAfterDate}/{@link #resetAfterHospital} 也不会清空它们。
     *
     * <p>这里<b>只写状态，不做任何分支判断</b>，写操作的权限边界仍在 {@code confirm()}。
     */
    private void rememberDemand(ConversationState state, ExtractedFacts facts) {
        if (!facts.hasStructuredDemand() && facts.missingInformation().isEmpty()) return;
        if (facts.tasks() != null && !facts.tasks().isEmpty()) {
            List<ExtractedFacts.TaskItem> tasks = new ArrayList<>(state.tasks);
            for (ExtractedFacts.TaskItem item : facts.tasks()) {
                boolean seen = tasks.stream().anyMatch(existing -> existing.label().equals(item.label()));
                if (!seen && tasks.size() < MAX_REMEMBERED_ITEMS) tasks.add(item);
            }
            state.tasks = List.copyOf(tasks);
        }
        state.constraints = mergeItems(state.constraints, facts.constraints());
        state.preferences = mergeItems(state.preferences, facts.preferences());
        state.additionalRequests = mergeItems(state.additionalRequests, facts.additionalRequests());
        // 缺什么信息是"当前这一轮"的判断，整体替换，不做累积。
        state.missingInformation = pruneMissingInformation(state, facts.missingInformation());
    }

    /**
     * 去掉状态里其实已经填好的"缺失项"。
     *
     * <p>规则兜底只看当前这一句话，用户回一句"不需要陪同"，它就会把医院/科室/日期又报成"还缺"；
     * 这份清单是要喂给模型的，不筛一遍模型就会反过来追问已经知道的信息。
     */
    private List<String> pruneMissingInformation(ConversationState state, List<String> missing) {
        if (missing.isEmpty()) return missing;
        List<String> result = new ArrayList<>();
        for (String item : missing) {
            if (item.contains("医院") && state.hospital != null) continue;
            if (item.contains("科室") && state.department != null) continue;
            if (item.contains("哪一天") && state.date != null) continue;
            result.add(item);
        }
        return List.copyOf(result);
    }

    private List<String> mergeItems(List<String> existing, List<String> incoming) {
        List<String> merged = new ArrayList<>(existing);
        for (String item : incoming) {
            if (!merged.contains(item) && merged.size() < MAX_REMEMBERED_ITEMS) merged.add(item);
        }
        return List.copyOf(merged);
    }

    /** 计划里同一件事只出现一行，重复说法不再叠加。 */
    private void addOnce(List<String> target, String item) {
        if (item != null && !item.isBlank() && !target.contains(item)) target.add(item);
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

    // ---------- 会话生命周期 ----------

    private ConversationState newConversation() {
        ConversationState state = new ConversationState(UUID.randomUUID().toString());
        sessions.put(state.id, state);
        return state;
    }

    private AgentTurnResponse greeting(ConversationState state) {
        String text = "您好，我是复诊助手。您可以直接告诉我完整需求，也可以跟着我一步一步办理。请问想去哪家医院复诊？";
        AgentTurnResponse response = AgentTurnResponse.message(state.id, state.stage.name(), text, List.of(
                q("市第一医院", "SET_HOSPITAL", "市第一医院"),
                q("市人民医院", "SET_HOSPITAL", "市人民医院"),
                q("我还没想好", "ASK_HUMAN_INPUT", "")));
        conversations.save(state, response);
        conversations.addMessage(state.id, "assistant", text);
        conversations.touch(state.id);
        return response;
    }

    /**
     * 取当前会话用于继续对话。如果它已经结束（点了「新对话」/ 空闲太久 / 到了上限），
     * 就先关掉它，再自动开一段新会话，把用户这句话放进新会话里。
     *
     * 空闲超时完全由后端按 last_message_at 判断，前端不需要任何定时器。
     */
    private ConversationState resolveActive(String conversationId) {
        ConversationState state = requireSession(conversationId);
        boolean closed = "CLOSED".equals(conversations.status(conversationId));
        if (!closed) {
            LocalDateTime lastMessageAt = conversations.meta(conversationId)
                    .map(ConversationStore.SessionMeta::lastMessageAt).orElse(null);
            if (!lifecycle.isIdleExpired(lastMessageAt)) return state;
            conversations.close(conversationId, "IDLE_TIMEOUT");
        }
        sessions.remove(conversationId);
        ConversationState fresh = newConversation();
        fresh.pendingNotice = "（上一段对话已经结束了，我为您开启了一段新对话。您接着说就行。）";
        return fresh;
    }

    /** 把只在这一轮使用的一次性提示并进回复，并清掉标记。 */
    private String consumeNotice(ConversationState state, String reply) {
        if (state.pendingNotice == null) return reply;
        String notice = state.pendingNotice;
        state.pendingNotice = null;
        return notice + "\n\n" + reply;
    }

    private AgentTurnResponse withReply(AgentTurnResponse source, String conversationId, String reply) {
        return new AgentTurnResponse(conversationId, source.stage(), reply, source.quickReplies(),
                source.plan(), source.confirmation(), source.result(), source.toolTraces(), source.autoAction());
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
        List<MemoryStore.UserMemory> recent = memories.recentByUser(USER_ID, 3);
        String history = recent.isEmpty() ? "无"
                : recent.stream()
                .map(m -> valueOrPending(m.hospital()) + " "
                        + (m.appointmentDate() == null ? "无日期" : m.appointmentDate())
                        + "(" + m.result() + ")")
                .collect(Collectors.joining("；"));
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
                "；当前推荐号源=" + slotLabel(state.recommendedSlot) +
                "；最近复诊历史=" + history +
                // 用户在这场对话里提出过的多任务、硬约束、偏好与补充诉求。
                // 交给模型是为了让它下一轮能自己判断"还缺什么、该先办哪件"，
                // 而不是把这些话只留在用户那一侧的聊天记录里。
                "；用户待办任务=" + joined(state.tasks.stream().map(ExtractedFacts.TaskItem::label).toList()) +
                "；硬性要求=" + joined(state.constraints) +
                "；软性偏好=" + joined(state.preferences) +
                "；其他诉求=" + joined(state.additionalRequests) +
                "；还缺信息=" + joined(state.missingInformation);
    }

    /** 拼成给模型看的一行；没有内容时明确写"无"，避免留空让模型以为字段缺失。 */
    private String joined(List<String> items) {
        return items.isEmpty() ? "无" : String.join("、", items);
    }

    private String valueOrPending(String value) {
        return value == null || value.isBlank() ? "待确认" : value;
    }

    private boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }

    /** 语音输入时判断是否提取到了关键事实（需要确认）。
     *  只对"预约办理类"的意图/事实做复述确认；像"是治疗什么的"这类追问图片的问答意图
     *  绝不能走语音确认，否则会误把问题当成预约信息，把用户带偏到复诊流程去。 */
    private boolean hasKeyFacts(ExtractedFacts facts) {
        return facts.hospital() != null || facts.department() != null || facts.date() != null
                || facts.selectedTime() != null || facts.timePreference() != null
                || "CREATE_FOLLOWUP".equals(facts.intent())
                || "CHANGE_HOSPITAL".equals(facts.intent())
                || "CHANGE_DATE".equals(facts.intent());
    }

    /** 本句话里是否带有真实的预约办理信息（医院/科室/日期/时间等）。有的话绝不走开放式问答，防止把预约流程带偏。 */
    private boolean hasBookingSlots(ExtractedFacts facts) {
        return facts.hospital() != null || facts.department() != null || facts.date() != null
                || facts.selectedTime() != null || facts.timePreference() != null
                || facts.acceptAlternative() != null || facts.needCompanion() != null
                || facts.needTravel() != null || facts.notifyFamily() != null
                || facts.transport() != null || facts.acceptRecommendedTime() != null;
    }

    /** 是否明确在说要办理复诊预约：要么话里出现了"复诊/办理/预约"等字眼，要么带了办理类意图或事实。
     *  只有满足其一才进入办理流程，避免把问答/闲聊误拽进"请告诉我就诊医院"。 */
    private boolean explicitBookingRequest(String value, ExtractedFacts facts) {
        if (containsAny(value, "预约", "复诊", "办理", "挂号", "就诊", "复检", "复查", "门诊", "看诊", "挂个号", "复个诊")) {
            return true;
        }
        return "CREATE_FOLLOWUP".equals(facts.intent())
                || "CHANGE_HOSPITAL".equals(facts.intent())
                || "CHANGE_DATE".equals(facts.intent())
                || "ASK_MATERIALS".equals(facts.intent())
                || facts.hospital() != null || facts.department() != null || facts.date() != null
                || facts.selectedTime() != null || facts.timePreference() != null;
    }

    /** 双轨通道2：让大模型现写回复；模型没配置/调用失败时返回 null（上层走规则兜底）。健康类问题强制补"咨询专业医生"。 */
    private AgentTurnResponse openChatAnswer(ConversationState state, AgentContext context, String question, String intent) {
        // 先交给工具调用智能体：由模型自己决定要不要查真实工具（药品知识库、号源、复诊记录……），
        // 查到真实结果后再作答。模型通道不可用或未收敛时，回落到原有的理解模型通道。
        String answer = toolAgent.answer(state.id, USER_ID, context, question, null);
        if (answer == null) answer = openChat.answer(context, question, intent);
        if (answer == null) return null;
        if (("HEALTH_ADVICE".equals(intent) || "MEDICAL_ADVICE".equals(intent))
                && !answer.contains("咨询专业医生") && !answer.contains("咨询医生")) {
            answer = answer + "\n\n以上为一般性参考，不能替代医生的专业诊断与建议；具体请咨询专业医生。";
        }
        return respond(state, answer, List.of(
                q("帮我预约复诊", "CONTINUE", ""),
                q("查看复诊事项", "OPEN_TASKS", "")));
    }

    /**
     * 本轮这句话是不是在追问这张对话里之前上传过的图片（"提取文字""刚才那张图片"）。
     * 识别结果已经存在库里，只要命中就直接复用，不必也不该让老人重新上传。
     */
    private boolean refersToPreviousImage(String value) {
        if (value == null) return false;
        return containsAny(value, "图片", "照片", "图上", "图中的", "拍的图", "拍的那张", "这张图", "那张图",
                "刚才那张", "刚才的图", "上面那张", "上传的图", "提取文字", "提取图中", "图里的字", "上面的字",
                "扫一下", "识别一下",
                // 药盒/说明书：老人让"读出上面写了什么"也是在看这张图，不是要医疗决策
                "说明书", "药盒", "包装盒", "盒子上", "上面写", "写的什么", "写了什么", "写了啥", "标的什么",
                // 追问图片里这个药/材料本身（"是治疗什么的""干什么用"），也属于在问这张图，不是医疗决策
                "治疗什么", "治什么", "主治什么", "干什么", "干嘛", "有什么作用", "有什么功效",
                "什么药", "这药", "这个药", "这说明书", "管什么");
    }

    /**
     * 老人是不是在要"把图片上的字读出声"。
     *
     * 单字"念"太宽（挂念/想念/纪念），所以只收两字以上的完整说法；这里同时覆盖老人
     * 不太标准的说法（"念一下""念一遍""朗读"）——否则这些说法会漏到模型通道，
     * 结果念出来的是 AI 生成的总结，而不是图片原文。
     */
    private boolean isReadAloudRequest(String value) {
        return containsAny(value, "全部念", "都念", "念出来", "念给我听", "读给我听", "读出来",
                "念一下", "念一遍", "念来听", "念给", "念一次", "念念",
                "朗读", "读一下", "读一遍", "读给", "读来听", "读一次", "读一读",
                "上面的字", "盒子上写", "上面写", "写的什么", "写了什么", "写了啥", "标的什么", "有什么字");
    }

    /** 图片文字追问："全部念出来/规格/批准文号"等。这些可能在 refersToPreviousImage 之外，单独判定。 */
    private boolean isImageTextQuestion(String value) {
        if (isReadAloudRequest(value)) return true;
        return containsAny(value, "规格", "批准文号", "成份", "成分", "性状", "适应症", "用法", "用量",
                "怎么吃", "怎么用", "贮藏", "储存", "保存", "禁忌", "注意事项", "不良反应",
                "有效期", "生产日期", "生产厂家", "上面的字");
    }

    /**
     * 老人是不是明确要听"你（AI）刚才说的那句话"：把你刚才说的话念出来 / 把上一条回答读一下 /
     * 你刚才的回答帮我念一遍。要求同时出现"指向上一条回答"和"念/读/重复"两层意思，
     * 避免把"你刚才说的那个药怎么吃"这类正常提问误判成朗读请求。
     */
    private boolean isReadAssistantAnswerRequest(String value) {
        if (value == null) return false;
        boolean aboutAssistant = containsAny(value, "你刚才说的", "你刚才讲", "你刚才回答", "你刚才的回复", "你刚才的话",
                "你刚刚说的", "你刚说的", "你的回答", "你的回复", "上一条回答", "上一条回复", "上一条消息",
                "上一条", "上一句", "刚才的回答", "刚才的回复", "刚才说的话", "刚才那段");
        if (!aboutAssistant) return false;
        return containsAny(value, "念", "读", "说一遍", "讲一遍", "重复", "复述", "朗读", "听一遍");
    }

    /** 上下文里最近一条助手回复的原文（"把你刚才说的话念出来"要念的就是它）。没有则返回 null。 */
    private String lastAssistantReply(AgentContext context) {
        List<AgentContext.Message> messages = context.recentMessages();
        if (messages == null || messages.isEmpty()) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            AgentContext.Message message = messages.get(i);
            if ("assistant".equals(message.role()) && message.content() != null && !message.content().isBlank()) {
                return message.content().trim();
            }
        }
        return null;
    }

    /**
     * 优先从完整 OCR 原文直接回答图片文字问题，不调模型。
     * 这是"视觉模型被调用后"的专属通道：OCR 有就直接念/截取；OCR 没有该内容时，
     * 直接转"咨询医生"，不再去查药品库（本项目没有药品库）。
     * 返回 null 仅表示"不是图片文字类问题"，交给上层走普通模型通道（如"这药是干什么的"）。
     */
    private AgentTurnResponse imageTextAnswer(ConversationState state, AgentContext context, String value) {
        String ocr = context.latestVisionOcr();

        List<QuickReply> replies = List.of(
                q("继续办理复诊", "CONTINUE", ""), q("查看复诊事项", "OPEN_TASKS", ""));

        // "全部念出来/读给我听"：照 vision_results.ocr 的原文输出，不总结、不改写、不再过一次大模型。
        boolean readAll = isReadAloudRequest(value);
        if (readAll) {
            String spoken = cleanOcrForReading(ocr);
            if (!spoken.isBlank()) {
                return respond(state, imageReadPrefix(ocr) + spoken, replies);
            }
            // 完整 OCR 没提取到：不查药品库，直接请老人咨询医生
            return respond(state, "这张图上的完整文字我没能提取到。药品信息请以说明书和医嘱为准，具体请咨询医生或专业医疗机构。", replies);
        }

        // 定向字段：规格 / 批准文号 / 成份 / 性状 / 适应症 / 用法用量 / 贮藏 等
        if (fieldLabelFor(value) != null) {
            if (ocr != null && !ocr.isBlank()) {
                String fieldAnswer = readOcrField(ocr, value);
                if (fieldAnswer != null) return respond(state, fieldAnswer, replies);
            }
            // OCR 里没有该字段：不查药品库，直接咨询医生
            return respond(state, "图片上没写清楚这个内容。药品相关的信息请以说明书和医嘱为准，具体请咨询医生或专业医疗机构。", replies);
        }

        return null; // 不是图片文字问题 → 交给上层普通模型通道
    }

    /** 根据用户问的是哪个字段，从 OCR 原文里按标签截取；OCR 里没有该字段时返回 null。 */
    private String readOcrField(String ocr, String value) {
        String label = fieldLabelFor(value);
        if (label == null) return null;
        String content = valueAfterLabel(ocr, label);
        if (content == null || content.isBlank()) return null;
        return label + "：" + cleanOcrForReading(content);
    }

    private String fieldLabelFor(String value) {
        if (containsAny(value, "批准文号")) return "批准文号";
        if (containsAny(value, "成份", "成分")) return "成份";
        if (containsAny(value, "性状")) return "性状";
        if (containsAny(value, "适应症", "主治")) return "适应症";
        if (containsAny(value, "用法", "用量", "怎么吃", "怎么用")) return "用法用量";
        if (containsAny(value, "贮藏", "储存", "保存")) return "贮藏";
        if (containsAny(value, "禁忌", "不良反应", "注意事项")) return "不良反应";
        if (containsAny(value, "有效期", "生产日期", "生产批号")) return "有效期";
        if (containsAny(value, "规格", "包装")) return "规格";
        return null;
    }

    /** 从 OCR 原文里截取某标签后面的内容，取到下一个字段标签或文末为止；找不到返回 null。 */
    private String valueAfterLabel(String ocr, String label) {
        int idx = ocr.indexOf(label);
        if (idx < 0) return null;
        int start = idx + label.length();
        while (start < ocr.length()) {
            char c = ocr.charAt(start);
            if (c == '：' || c == ':' || c == ' ' || c == '\t' || c == '\n' || c == '\r') start++;
            else break;
        }
        int end = ocr.length();
        for (String next : OCR_FIELD_LABELS) {
            int p = ocr.indexOf(next, start);
            if (p >= 0 && p < end) end = p;
        }
        String content = ocr.substring(start, end).trim();
        if (content.isEmpty()) return null;
        return content.replaceAll("\\s+", " ").trim();
    }

    /**
     * 朗读前的轻量清洗：只去掉 Markdown 标记和明显的 OCR 排版噪声，方便 TTS 念得顺。
     * 严格不总结、不压缩、不改写——念出来的必须还是图片上的原文，一个字都不能少。
     */
    private String cleanOcrForReading(String ocr) {
        if (ocr == null || ocr.isBlank()) return "";
        String text = ocr.replace("\r\n", "\n").replace('\r', '\n');
        // Markdown 标记：不处理的话 TTS 会把星号、井号一起念出来
        text = text.replaceAll("(?m)^\\s{0,3}#{1,6}\\s+", "");
        text = text.replaceAll("(?m)^\\s{0,3}>\\s?", "");
        text = text.replace("```", "");
        text = text.replaceAll("`([^`]*)`", "$1");
        text = text.replaceAll("\\*\\*([^*]*)\\*\\*", "$1");
        text = text.replaceAll("__([^_]*)__", "$1");
        text = text.replaceAll("~~([^~]*)~~", "$1");
        // 行首项目符号：图片上本来没有，多半是 OCR 排版噪声（"-"只在后面跟空格时才当符号，避免动到数字）
        text = text.replaceAll("(?m)^\\s*[*·•●▪◆○]\\s+", "");
        text = text.replaceAll("(?m)^\\s*-\\s+", "");
        // 纯分隔线（---、===、___）念不出任何内容，去掉
        text = text.replaceAll("(?m)^\\s*[-=*_~—·]{3,}\\s*$", "");
        // 排版噪声：行内多余空格合并、去掉行尾空格、连续空行压缩，原有分段保留
        text = text.replaceAll("[ \\t]{2,}", " ");
        text = text.replaceAll("(?m)[ \\t]+$", "");
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.trim();
    }

    /**
     * 图片问答要不要跑工具智能体。工具智能体最多 3 轮模型循环，只有确实要查真实数据才值得等：
     * 复诊材料清单、药品知识、号源/预约。问不出这些就只需一次普通问答。
     */
    private boolean needsTool(String note, String description, String ocr) {
        String text = note == null ? "" : note;
        if (containsAny(text, "要带", "带什么", "材料", "清单", "东西", "准备什么")) return true;
        if (containsAny(text, "预约", "挂号", "号源", "医院", "科室")) return true;
        boolean asksDrug = containsAny(text, "什么药", "怎么吃", "怎么用", "用法", "用量", "剂量",
                "副作用", "不良反应", "适应症", "主治", "禁忌", "治什么", "能吃", "规格", "批准文号", "注意什么");
        // 只有图上确实是药才去查药品知识库；问错了对象，查了也是白等。
        return asksDrug && looksLikeDrug((ocr == null ? "" : ocr) + "\n" + (description == null ? "" : description));
    }

    /** 视觉内容里有没有"这是一份药品信息"的确凿线索：说明书特征字段，或"药名 + 剂量"。 */
    private boolean looksLikeDrug(String content) {
        if (content == null || content.isBlank()) return false;
        if (containsAny(content, "国药准字", "批准文号", "说明书", "适应症", "功能主治",
                "不良反应", "禁忌", "用法用量", "成份", "成分")) {
            return true;
        }
        return DOSAGE.matcher(content).find()
                && containsAny(content, "药", "胶囊", "颗粒", "片", "口服液", "滴眼液", "注射液", "软膏", "喷雾");
    }

    /**
     * 图片内容分类（这张图上的字段/材料属于哪一类），用于朗读时先说清"这是什么图"。
     * 只按 OCR 原文里真实出现的特征字段名判断，判断不出来返回 null，绝不猜。
     */
    private String imageKind(String ocr) {
        if (ocr == null || ocr.isBlank()) return null;
        if (containsAny(ocr, "国药准字", "批准文号", "说明书", "功能主治", "适应症", "不良反应", "注意事项",
                "成份", "成分", "性状", "贮藏", "用法用量")) {
            return "药品说明书";
        }
        if (containsAny(ocr, "检验报告", "化验单", "检验结果", "参考范围", "检测项目", "标本")) {
            return "化验单/检验报告";
        }
        if (containsAny(ocr, "门诊病历", "主诉", "现病史", "诊断", "处方", "医师", "医嘱")) {
            return "病历/处方";
        }
        if (containsAny(ocr, "发票", "收费", "金额", "费用", "结算")) {
            return "费用票据";
        }
        return null;
    }

    /** 朗读图片原文时的开场语：能判断出图片类别就点明，判断不出就不说，绝不编。 */
    private String imageReadPrefix(String ocr) {
        String kind = imageKind(ocr);
        return kind == null ? "图片上写的文字如下：\n\n" : "这张图是" + kind + "，上面的文字如下：\n\n";
    }

    /** 模型通道不可用时，用存下来的视觉识别结果如实回答，不让老人听到"答不上来"。 */
    private String previousImageReply(AgentContext context) {
        String latest = "";
        for (AgentContext.VisionNote note : context.vision()) {
            if (note.description() != null && !note.description().isBlank()) {
                latest = note.description().trim();
                break;
            }
        }
        if (latest.isBlank()) latest = context.visionSummary().trim();
        return "您刚才上传的那张图片，我看到的是：\n\n" + latest
                + "\n\n复诊的时候把原图一起带上，方便医生核对。";
    }

    /** 就医分诊：把症状/疾病关键词映射到建议挂的科室（只给一般性导诊参考，不做诊断）。匹配不到返回 null。 */
    private String referralDepartment(String value) {
        if (containsAny(value, "高血压", "血压高", "血压偏高")) return "心血管内科（有的医院还设高血压专病门诊）";
        if (containsAny(value, "血糖高", "高血糖", "糖尿病", "血糖低", "低血糖", "甲状腺", "甲亢", "甲减")) return "内分泌科";
        if (containsAny(value, "心慌", "心跳", "心悸", "心脏", "心绞", "冠心病", "房颤", "心律", "胸闷")) return "心血管内科";
        if (containsAny(value, "头晕", "头痛", "头疼", "眩晕", "手麻", "脚麻", "嘴歪", "口齿不清", "中风", "脑梗", "帕金森", "癫痫")) return "神经内科";
        if (containsAny(value, "胃疼", "胃痛", "肚子疼", "肚子痛", "胃胀", "反酸", "烧心", "便秘", "腹泻", "拉肚子", "消化", "胃肠")) return "消化内科";
        if (containsAny(value, "咳嗽", "咳痰", "感冒", "哮喘", "气管", "肺炎", "支气管", "喘不上气", "气喘")) return "呼吸内科";
        if (containsAny(value, "关节", "膝盖", "腰椎", "颈椎", "腰疼", "腰痛", "腰酸", "腿疼", "腿痛", "骨头", "骨刺", "骨折", "骨质疏松", "肩周")) return "骨科";
        if (containsAny(value, "皮肤", "皮疹", "红疹", "湿疹", "瘙痒", "过敏")) return "皮肤科";
        if (containsAny(value, "眼", "视力", "看不清", "白内障", "青光眼", "干眼", "飞蚊")) return "眼科";
        if (containsAny(value, "耳鸣", "耳聋", "听力", "耳朵", "中耳炎")) return "耳鼻喉科";
        if (containsAny(value, "尿频", "尿痛", "肾结石", "泌尿", "前列腺")) return "泌尿外科（或肾内科，请以医院导诊为准）";
        return null;
    }

    private String slotLabel(Slot slot) {
        return slot == null ? "待选择" : slot.date().format(DATE_LABEL) + " " + slot.time().format(TIME_LABEL);
    }

    /** 展示给用户时去掉种子数据的"（模拟）"后缀。 */
    private String cleanName(String name) {
        return name.replace("（模拟）", "").replace("(模拟)", "");
    }
}
