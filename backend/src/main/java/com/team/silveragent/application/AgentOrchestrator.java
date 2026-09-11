package com.team.silveragent.application;

import com.team.silveragent.agent.ExtractedFacts;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 中控只决定“本轮该走哪条业务路由”。
 * 大模型负责理解意图，Java 中控负责安全优先级和流程状态，工具负责真实数据。
 */
@Component
final class AgentOrchestrator {
    /** “9月8号”这类只写了月日的说法：查询已有预约时要按月份和日期匹配，不能武断地补年份。 */
    private static final Pattern SPOKEN_MONTH_DAY = Pattern.compile("(\\d{1,2})\\s*月\\s*(\\d{1,2})");
    private static final Pattern SHORT_ISO_DATE = Pattern.compile("20\\d{2}\\s*[-/.]\\s*\\d{1,2}\\s*[-/.]\\s*\\d{1,2}");
    /** 短口令长度上限：更长的句子说明用户还带了条件，必须交给规划模型理解。 */
    private static final int SHORT_COMMAND_LIMIT = 10;
    /** 指向“某一次预约”的说法。出现这些词就不能走默认地图快速通道。 */
    private static final List<String> APPOINTMENT_REFERENCE =
            List.of("上次", "上一次", "之前", "以前", "原来", "最近", "那次", "刚才", "早先", "历史");

    private final CareCatalogRepository catalog;

    AgentOrchestrator(CareCatalogRepository catalog) {
        this.catalog = catalog;
    }

    enum Route {
        DIRECT_ANSWER, CONFIRM_PENDING, DENY_PENDING, KEEP_CONFLICT,
        EMOTIONAL_SUPPORT, SMALL_TALK, CLARIFY_DISCOMFORT,
        CANCEL_CURRENT_TASK, CANCEL_EXISTING_APPOINTMENT, QUERY_MY_APPOINTMENTS,
        RESTART_TASK, RESUME_TASK,
        QUERY_CARE_GUIDE, MULTI_READ_TOOLS, QUERY_HOSPITALS, QUERY_DEPARTMENTS, RECOMMEND_HOSPITAL,
        RESOLVE_HOSPITAL, RESOLVE_DEPARTMENT, VALIDATE_DRAFT,
        QUERY_AVAILABLE_SLOTS, QUERY_NEARBY_SLOTS, CHECK_CONFLICT, CHECK_DUPLICATE,
        ASK_MATERIALS, QUERY_TRAVEL_GUIDE, QUERY_LOCATION_GUIDE,
        // 药品知识查询：命中的是知识库里的真实条目，查不到就如实说没查到。
        QUERY_DRUG_KNOWLEDGE,
        CHANGE_HOSPITAL, CHANGE_DEPARTMENT, CHANGE_DATE, CHANGE_TIME,
        // 健康备忘与健康数值不属于复诊预约流程：写操作仍由 Java 的解析器填槽、
        // 仍走各自的门禁（备忘先确认、数值异常先反问），这里只负责把它们从模型那边接过来。
        MANAGE_MEMO, RECORD_HEALTH_VALUE, SEND_HEALTH_REPORT,
        // 协同照护端（家属/志愿者）专用只读查询：老人本人没有这两个入口，
        // 工具可见性由 ToolRegistry 按角色限定，这里只负责把模型选中的工具接到执行。
        QUERY_CARE_TIMELINE, QUERY_CARE_NOTIFICATIONS,
        // 家属/志愿者给长辈留一条提醒：落到长辈自己的备忘里，反向通知。
        REMIND_ELDER,
        CURRENT_FLOW
    }

    Route decide(String message, ExtractedFacts facts, ConversationState state) {
        String intent = facts.intent() == null ? "UNKNOWN" : facts.intent();

        Route deterministic = deterministicOverride(message);
        if (deterministic != null) return deterministic;

        if (state.stage == ConversationState.Stage.AWAITING_CONFIRMATION) {
            if ("CONFIRM_ACTION".equals(intent) || contains(message, "确认取消", "确认办理", "执行操作")) {
                return Route.CONFIRM_PENDING;
            }
            if ("DENY_ACTION".equals(intent) || contains(message, "保留预约", "不执行", "返回修改")) {
                return Route.DENY_PENDING;
            }
        }
        if (state.stage == ConversationState.Stage.CONFLICT
                && ("CONFIRM_ACTION".equals(intent)
                || contains(message, "还是这个时间", "仍然这个时间", "保留这个时间", "就按这个时间", "时间不改"))) {
            // 这里只表示用户明确选择保留冲突时间，仍需进入最终确认卡，不能直接提交预约。
            return Route.KEEP_CONFLICT;
        }

        if ("CREATE_FOLLOWUP".equals(intent)) {
            // 模型负责理解“我要办理”这类自然表达；Java只根据权威任务状态决定是新建还是恢复，
            // 不能因为已经存在任务就把模型结论丢进 CURRENT_FLOW。
            return taskInProgress(state) ? Route.RESUME_TASK : Route.RESTART_TASK;
        }
        if ("RESUME_TASK".equals(intent) && !taskInProgress(state)) {
            // 没有可恢复任务时不凭空创建，交给当前流程生成明确澄清与“开始办理”入口。
            return Route.CURRENT_FLOW;
        }

        return switch (intent) {
            case "EMOTIONAL_SUPPORT" -> Route.EMOTIONAL_SUPPORT;
            case "SMALL_TALK" -> Route.SMALL_TALK;
            case "CLARIFY_DISCOMFORT" -> Route.CLARIFY_DISCOMFORT;
            case "EXPLAIN_PROCESS" -> Route.QUERY_CARE_GUIDE;
            case "CANCEL_TASK" -> Route.CANCEL_CURRENT_TASK;
            case "CANCEL_APPOINTMENT" -> Route.CANCEL_EXISTING_APPOINTMENT;
            case "QUERY_APPOINTMENTS" -> Route.QUERY_MY_APPOINTMENTS;
            case "RESTART_TASK" -> Route.RESTART_TASK;
            case "RESUME_TASK" -> Route.RESUME_TASK;
            case "QUERY_HOSPITALS", "QUERY_HOSPITAL_INFO" -> Route.QUERY_HOSPITALS;
            case "QUERY_DEPARTMENTS" -> Route.QUERY_DEPARTMENTS;
            case "REQUEST_RECOMMENDATION" -> Route.RECOMMEND_HOSPITAL;
            case "QUERY_AVAILABLE_SLOTS" -> Route.QUERY_AVAILABLE_SLOTS;
            case "QUERY_NEARBY_SLOTS" -> Route.QUERY_NEARBY_SLOTS;
            case "CHECK_CONFLICT" -> Route.CHECK_CONFLICT;
            case "CHECK_DUPLICATE" -> Route.CHECK_DUPLICATE;
            case "ASK_MATERIALS" -> Route.ASK_MATERIALS;
            case "ASK_TRAVEL_ROUTE" -> Route.QUERY_TRAVEL_GUIDE;
            case "ASK_LOCATION_GUIDE" -> Route.QUERY_LOCATION_GUIDE;
            case "CHANGE_HOSPITAL" -> Route.CHANGE_HOSPITAL;
            case "CHANGE_DEPARTMENT" -> Route.CHANGE_DEPARTMENT;
            case "CHANGE_DATE" -> Route.CHANGE_DATE;
            case "CHANGE_TIME" -> Route.CHANGE_TIME;
            case "QUERY_DRUG" -> Route.QUERY_DRUG_KNOWLEDGE;
            default -> Route.CURRENT_FLOW;
        };
    }

    private boolean taskInProgress(ConversationState state) {
        return state.taskStatus == ConversationState.TaskStatus.ACTIVE
                || state.taskStatus == ConversationState.TaskStatus.PAUSED
                || state.taskStatus == ConversationState.TaskStatus.AWAITING_CONFIRMATION;
    }

    /**
     * 只读的页面指令快速通道：只认“打开地图 / 院内指引”这类没有附加筛选条件的短口令。
     * 不含任何写操作，但仍受确认卡与安全优先级约束。
     */
    boolean isPageCommand(String message) {
        Route route = shortPageCommand(message);
        return route == Route.QUERY_TRAVEL_GUIDE || route == Route.QUERY_LOCATION_GUIDE;
    }

    /**
     * 页面指令快速通道的判定。
     * 只要句子里出现日期、医院、科室，或者“上次 / 最近 / 那次”这类预约指代，再或者句子本身较长，
     * 就不走快速通道：这些表达必须交给规划模型理解，再由 Java 查询真实预约。
     * 否则“看9月8号的地图”会被当成默认地图，丢掉用户明确说出的日期。
     */
    Route shortPageCommand(String message) {
        String value = message == null ? "" : message.trim();
        if (value.isEmpty() || value.length() > SHORT_COMMAND_LIMIT) return null;
        if (hasAppointmentQualifier(value)) return null;
        if (contains(value, "院内指引", "院内路线", "院内怎么走", "楼里怎么走", "院里的路线")) {
            return Route.QUERY_LOCATION_GUIDE;
        }
        if (contains(value, "查看地图", "打开地图", "看看地图", "看一下地图", "看地图", "地图",
                "我要看地图", "我想看地图", "出行路线", "查看路线", "院外路线", "外面的路线")) {
            return Route.QUERY_TRAVEL_GUIDE;
        }
        return null;
    }

    /** 句子里是否带有“哪一次预约”的限定信息。有就必须查库，不能拿默认预约顶替。 */
    private boolean hasAppointmentQualifier(String value) {
        if (SPOKEN_MONTH_DAY.matcher(value).find() || SHORT_ISO_DATE.matcher(value).find()) return true;
        for (String word : APPOINTMENT_REFERENCE) if (value.contains(word)) return true;
        for (String name : catalog.hospitalNames()) if (value.contains(name)) return true;
        for (String name : catalog.departmentNames()) if (value.contains(name)) return true;
        return false;
    }

    /** 高风险或高频口语的确定性覆盖；模型仍负责提取日期等业务事实。 */
    Route deterministicOverride(String message) {
        // 高价值口语表达使用确定性护栏，避免模型偶发漂移后误入“新建预约”。
        if (isProcessQuestion(message)) {
            return Route.QUERY_CARE_GUIDE;
        }
        boolean explicitCancel = contains(message, "取消", "退掉", "撤销", "作废");
        boolean discardExisting = message.contains("不要")
                && contains(message, "之前", "原来", "已有", "已经", "那个");
        if (contains(message, "预约", "之前的号", "原来的号") && (explicitCancel || discardExisting)) {
            return Route.CANCEL_EXISTING_APPOINTMENT;
        }
        // 页面指令只认不带筛选条件的短口令。带日期、医院、科室或“上次”这类指代的表达留给规划模型理解，
        // 否则“看看9月8号的地图”会被这条规则提前截成默认地图，用户说出的日期在 Java 阶段就丢了。
        Route pageCommand = shortPageCommand(message);
        if (pageCommand != null) return pageCommand;
        return null;
    }

    private boolean isProcessQuestion(String value) {
        return contains(value, "办理流程", "预约流程", "复诊流程", "怎么办理", "怎么复诊", "到医院怎么办", "到院流程")
                || (value.contains("流程") && contains(value, "办理", "复诊", "预约", "到院", "医院"));
    }

    private boolean contains(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }
}
