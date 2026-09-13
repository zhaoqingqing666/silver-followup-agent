package com.team.silveragent.application;

import com.team.silveragent.agent.AgentRole;
import com.team.silveragent.agent.planning.PlannerTool;
import com.team.silveragent.agent.planning.ToolArgument;
import com.team.silveragent.agent.planning.ToolConstraint;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 模型可见的受控工具清单。查询工具可以自动执行；确认交互工具只能提出确认卡；澄清交互工具
 * 只提问、不生成任何执行授权。三类都写不出业务数据。
 *
 * <p>本表是“系统一共能做哪些事”的能力清单。谁能看见哪几条由每条工具自己声明的角色决定，
 * 实际判定在 {@link ToolPolicy}，参数判罚在 {@link ToolContract}，按角色取清单在 {@link AgentRuntime}：
 * 注册表只负责“有哪些”，不负责“谁能用、参数对不对”。
 *
 * <p>每条参数的<b>类型、必填、枚举和字段组合约束</b>都在这里声明一次，既进提示词给模型看，
 * 也是 Java 判罚的唯一依据。别在别处再写一套「这个参数应该是日期」的检查。
 */
@Component
final class ToolRegistry {
    record RegisteredTool(PlannerTool definition, AgentOrchestrator.Route route, Set<AgentRole> roles) {
        /** 全部角色可见（老人端自办和家属/志愿者代办的查询语义相同，共用一条工具）。 */
        boolean visibleTo(AgentRole role) {
            return roles.contains(role == null ? AgentRole.ELDER : role);
        }
    }

    private final Map<String, RegisteredTool> tools = new LinkedHashMap<>();

    ToolRegistry() {
        register("appointment.validateDraft", "检查当前预约草稿已收集字段和缺失字段；只读，不决定提问顺序",
                List.of(), AgentOrchestrator.Route.VALIDATE_DRAFT);
        register("hospital.search", "按老人说出的全名、简称或片段查询真实医院目录；返回精确、唯一近似、多候选或无匹配",
                List.of(ToolArgument.text("keyword", true, "老人说的医院名或其中一段")),
                AgentOrchestrator.Route.RESOLVE_HOSPITAL);
        register("department.search", "在已选医院内按全名、简称或片段查询真实科室目录",
                List.of(ToolArgument.text("keyword", true, "老人说的科室名或其中一段"),
                        ToolArgument.text("hospital", false, "限定在哪家医院里找")),
                AgentOrchestrator.Route.RESOLVE_DEPARTMENT);
        register("careGuide.search", "查询复诊办理流程、到院步骤、咨询渠道和安全边界；只读，不提供诊断",
                List.of(ToolArgument.text("query", true, "老人问的流程问题原话"),
                        ToolArgument.text("hospital", false, "限定医院"),
                        ToolArgument.text("department", false, "限定科室")),
                AgentOrchestrator.Route.QUERY_CARE_GUIDE);
        register("hospital.list", "查询模拟医院资料；只读，不推荐医疗结论",
                List.of(ToolArgument.text("hospital", false, "只看这一家医院")),
                AgentOrchestrator.Route.QUERY_HOSPITALS);
        register("department.list", "查询医院科室资料；只读",
                List.of(ToolArgument.text("hospital", false, "查这家医院的科室")),
                AgentOrchestrator.Route.QUERY_DEPARTMENTS);
        // 日期可选：老人还没说日期时，Java 自己会提示先选日期（clearSlotSelection），
        // 不能因为模型没带日期就把这次查询判成缺参数。
        register("appointment.querySlots", "查询真实模拟号源；只读，不创建预约",
                List.of(ToolArgument.text("hospital", false, "医院名"),
                        ToolArgument.text("department", false, "科室名"),
                        ToolArgument.date("date", false, "查哪一天的号，ISO 日期")),
                AgentOrchestrator.Route.QUERY_AVAILABLE_SLOTS);
        register("appointment.queryNearbySlots", "指定日期无号时查询前后日期的真实模拟号源；只读",
                List.of(ToolArgument.text("hospital", false, "医院名"),
                        ToolArgument.text("department", false, "科室名"),
                        ToolArgument.date("date", false, "以哪一天为基准前后找，ISO 日期")),
                AgentOrchestrator.Route.QUERY_NEARBY_SLOTS);
        register("appointment.checkDuplicate", "提交前查询同一用户是否已有相同医院、科室、日期和时间的预约；只读",
                List.of(ToolArgument.text("hospital", false, "医院名"),
                        ToolArgument.text("department", false, "科室名"),
                        ToolArgument.date("date", false, "ISO 日期"),
                        ToolArgument.time("time", false, "HH:mm")),
                AgentOrchestrator.Route.CHECK_DUPLICATE);
        register("schedule.checkConflict", "查询当前预约草稿选择的时间是否与用户已有日程冲突；只读",
                List.of(ToolArgument.date("date", false, "ISO 日期"),
                        ToolArgument.time("time", false, "HH:mm")),
                AgentOrchestrator.Route.CHECK_CONFLICT);
        register("appointment.queryMine", "查询当前服务对象已确认预约（本人自办时即本人；代他人办理时是被协同的长辈）；只读，用户身份由 Java 注入",
                List.of(ToolArgument.date("date", false, "只看这一天的预约，ISO 日期"),
                        ToolArgument.text("hospital", false, "只看这家医院的预约"),
                        ToolArgument.text("department", false, "只看这个科室的预约")),
                AgentOrchestrator.Route.QUERY_MY_APPOINTMENTS);
        // 取消范围由模型给结构化参数，Java 只认这一组声明好的字段：枚举之外的取值判非法，
        // 字段组合说不通的（DATE_RANGE 没给方向、SINGLE_FILTER 一个条件都没给）同样判非法，
        // 两种情况都不执行、转成澄清。appointmentId 故意<b>不</b>在声明里：模型不得指定取消哪一条，
        // 它给了也会被当作未声明参数忽略。
        registerInteraction("interaction.requestConfirmation",
                "申请或修改取消预约确认卡；只描述筛选范围，不提供预约ID，也不执行取消。"
                        + "scope=ALL|DATE_RANGE|SINGLE_FILTER|AMBIGUOUS；日期用ISO格式；范围方向用BEFORE|ON_OR_BEFORE|AFTER|ON_OR_AFTER。"
                        + "范围本来就说不清时不要硬填，改用 interaction.askClarification 让老人从真实候选里选。",
                List.of(ToolArgument.enumeration("scope", true,
                                List.of("ALL", "DATE_RANGE", "SINGLE_FILTER", "AMBIGUOUS"),
                                "取消范围：全部 / 按日期范围 / 按单个条件筛 / 自己也说不清"),
                        ToolArgument.date("date", false, "范围边界日期，ISO 日期"),
                        ToolArgument.enumeration("direction", false,
                                List.of("BEFORE", "ON_OR_BEFORE", "AFTER", "ON_OR_AFTER"),
                                "边界日期算不算在内：之前 / 及之前 / 之后 / 及之后"),
                        ToolArgument.time("time", false, "只看这个时刻的预约，HH:mm"),
                        ToolArgument.enumeration("period", false, List.of("MORNING", "AFTERNOON"),
                                "只看上午或下午"),
                        ToolArgument.enumeration("position", false, List.of("NEAREST", "EARLIEST"),
                                "只看最近一次或最早一次"),
                        ToolArgument.text("hospital", false, "只看这家医院的预约"),
                        ToolArgument.text("department", false, "只看这个科室的预约")),
                List.of(ToolConstraint.requiresAll("scope", List.of("DATE_RANGE"),
                                List.of("date", "direction"),
                                "按日期范围取消必须同时给出边界日期和方向，否则筛不出范围"),
                        ToolConstraint.atLeastOne("scope", List.of("SINGLE_FILTER"),
                                List.of("date", "time", "period", "position", "hospital", "department"),
                                "按单个条件筛时至少要给一个筛选条件，一个都不给等于没说取消哪些")),
                AgentOrchestrator.Route.CANCEL_EXISTING_APPOINTMENT);
        registerInteraction("interaction.respondConfirmation",
                "处理当前确认卡；decision只能是CONFIRM或DENY。confirmationId由Java从当前会话注入，模型不得提供",
                List.of(ToolArgument.enumeration("decision", true, List.of("CONFIRM", "DENY"),
                        "同意或拒绝当前确认卡")),
                AgentOrchestrator.Route.CURRENT_FLOW);
        // 澄清交互：只提问 + 把 Java 从真实工具取回的候选摆出来，不建卡、不发 confirmationId，
        // 所以它不构成任何执行授权（老人点选之后仍然要走 requestConfirmation 那张卡）。
        registerClarification("interaction.askClarification",
                "老人说的对象不唯一、范围说不清时，提出一个自然问题并让他在真实候选里选一个；"
                        + "只提问，不执行任何操作，也不生成确认卡。候选由 Java 从数据库补上，模型不要自己列预约。",
                // question 是可选的：它是模型的润色，不是这次澄清的必要条件。给了但说不过结构闸门
                // （带数字、像在宣布完成）就整句丢掉，Java 用自己的固定问法接着问——
                // 让缺一句好话变成「不澄清了」才是真的糟糕。
                List.of(ToolArgument.text("question", false, "一句自然的追问，不超过80字，不要出现数字"),
                        ToolArgument.enumeration("candidateTool", true, List.of("appointment.queryMine"),
                                "候选从哪条只读工具取，必须点名，目前只支持 appointment.queryMine")),
                AgentOrchestrator.Route.ASK_CLARIFICATION);
        register("material.checklist", "查询医院和科室的复诊材料清单；只读",
                List.of(ToolArgument.text("hospital", false, "医院名"),
                        ToolArgument.text("department", false, "科室名")),
                AgentOrchestrator.Route.ASK_MATERIALS);
        register("travel.routePlan", "查询到医院的路线、距离、预计用时和建议出发时间；只读",
                List.of(ToolArgument.text("appointmentId", false, "针对哪条预约规划路线"),
                        ToolArgument.text("transport", false, "交通方式，如打车、公交")),
                AgentOrchestrator.Route.QUERY_TRAVEL_GUIDE);
        register("hospital.locationGuide", "查询门诊楼入口、楼层、诊室、报到点和无障碍指引；只读",
                List.of(ToolArgument.text("appointmentId", false, "针对哪条预约"),
                        ToolArgument.text("hospital", false, "医院名"),
                        ToolArgument.text("department", false, "科室名")),
                AgentOrchestrator.Route.QUERY_LOCATION_GUIDE);
        // 药品知识：只能查知识库里有的药，查不到就如实说没查到。老人本人和照护端都可能问，
        // 所以不加角色限制。
        register("drug.queryKnowledge", "查询药品的名称、规格、类别、用途和用药提醒；只读，不提供诊断、不判断该不该吃、不建议换药加量",
                List.of(ToolArgument.text("drugName", true, "老人说的药名"),
                        ToolArgument.text("specification", false, "规格，用来在多条同名记录里挑一条")),
                AgentOrchestrator.Route.QUERY_DRUG_KNOWLEDGE);
        // 下面两条只对被协同的长辈有意义，老人本人查不到也不需要：注册表按角色限定可见性。
        register("care.timeline", "查询当前协助长辈的复诊动态时间线；只读，仅家属/志愿者可用",
                List.of(ToolArgument.text("elderUserId", false, "被协助长辈的 id")),
                AgentOrchestrator.Route.QUERY_CARE_TIMELINE,
                AgentRole.FAMILY, AgentRole.VOLUNTEER);
        register("care.notifications", "查询发给当前操作者的协同通知；只读，仅家属/志愿者可用",
                List.of(), AgentOrchestrator.Route.QUERY_CARE_NOTIFICATIONS,
                AgentRole.FAMILY, AgentRole.VOLUNTEER);
    }

    private void register(String name, String description, List<ToolArgument> arguments,
                          AgentOrchestrator.Route route) {
        register(name, description, arguments, route, AgentRole.values());
    }

    private void register(String name, String description, List<ToolArgument> arguments,
                          AgentOrchestrator.Route route, AgentRole... roles) {
        Set<AgentRole> allowed = roles.length == 0
                ? EnumSet.allOf(AgentRole.class) : EnumSet.copyOf(List.of(roles));
        tools.put(name, new RegisteredTool(
                new PlannerTool(name, description, "READ_ONLY", arguments, List.of()),
                route, allowed));
    }

    /** 确认交互：只能生成/处理确认卡，不能写业务数据。 */
    private void registerInteraction(String name, String description, List<ToolArgument> arguments,
                                     AgentOrchestrator.Route route) {
        registerInteraction(name, description, arguments, List.of(), route);
    }

    private void registerInteraction(String name, String description, List<ToolArgument> arguments,
                                     List<ToolConstraint> constraints, AgentOrchestrator.Route route) {
        tools.put(name, new RegisteredTool(
                new PlannerTool(name, description, "CONFIRMATION_ONLY", arguments, constraints),
                route, EnumSet.allOf(AgentRole.class)));
    }

    /**
     * 澄清交互：只提问，不建卡、不发凭据。单独一种 risk，是为了让 {@link ToolPolicy} 能用独立入口
     * 判它——它既不能走只读通道自动执行，也不能走确认通道拿到执行授权。
     */
    private void registerClarification(String name, String description, List<ToolArgument> arguments,
                                       AgentOrchestrator.Route route) {
        tools.put(name, new RegisteredTool(
                new PlannerTool(name, description, "CLARIFICATION_ONLY", arguments, List.of()),
                route, EnumSet.allOf(AgentRole.class)));
    }

    /** 交给模型的工具清单：按当前会话的操作者身份过滤，模型看不见的就不会去想它。 */
    List<PlannerTool> plannerTools(AgentRole role) {
        return tools.values().stream()
                .filter(tool -> tool.visibleTo(role))
                .map(RegisteredTool::definition)
                .toList();
    }

    List<PlannerTool> plannerTools() {
        return tools.values().stream().map(RegisteredTool::definition).toList();
    }

    Optional<RegisteredTool> find(String name) {
        return Optional.ofNullable(name == null ? null : tools.get(name));
    }
}
