package com.team.silveragent.application;

import com.team.silveragent.agent.AgentRole;
import com.team.silveragent.agent.planning.PlannerTool;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 模型可见的只读工具白名单。写工具不会注册到这里。
 *
 * <p>本表是“系统一共能做哪些事”的能力清单。谁能看见哪几条由每条工具自己声明的角色决定，
 * 实际判定在 {@link ToolPolicy}，按角色取清单在 {@link AgentRuntime}：
 * 注册表只负责“有哪些”，不负责“谁能用”。
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
                List.of("keyword"), AgentOrchestrator.Route.RESOLVE_HOSPITAL);
        register("department.search", "在已选医院内按全名、简称或片段查询真实科室目录",
                List.of("hospital", "keyword"), AgentOrchestrator.Route.RESOLVE_DEPARTMENT);
        register("careGuide.search", "查询复诊办理流程、到院步骤、咨询渠道和安全边界；只读，不提供诊断",
                List.of("query", "hospital", "department"), AgentOrchestrator.Route.QUERY_CARE_GUIDE);
        register("hospital.list", "查询模拟医院资料；只读，不推荐医疗结论", List.of("hospital"),
                AgentOrchestrator.Route.QUERY_HOSPITALS);
        register("department.list", "查询医院科室资料；只读", List.of("hospital"),
                AgentOrchestrator.Route.QUERY_DEPARTMENTS);
        register("appointment.querySlots", "查询真实模拟号源；只读，不创建预约",
                List.of("hospital", "department", "date"), AgentOrchestrator.Route.QUERY_AVAILABLE_SLOTS);
        register("appointment.queryNearbySlots", "指定日期无号时查询前后日期的真实模拟号源；只读",
                List.of("hospital", "department", "date"), AgentOrchestrator.Route.QUERY_NEARBY_SLOTS);
        register("appointment.checkDuplicate", "提交前查询同一用户是否已有相同医院、科室、日期和时间的预约；只读",
                List.of("hospital", "department", "date", "time"), AgentOrchestrator.Route.CHECK_DUPLICATE);
        register("schedule.checkConflict", "查询当前预约草稿选择的时间是否与用户已有日程冲突；只读",
                List.of("date", "time"), AgentOrchestrator.Route.CHECK_CONFLICT);
        register("appointment.queryMine", "查询当前服务对象已确认预约（本人自办时即本人；代他人办理时是被协同的长辈）；只读，用户身份由 Java 注入",
                List.of("date", "hospital", "department"), AgentOrchestrator.Route.QUERY_MY_APPOINTMENTS);
        register("material.checklist", "查询医院和科室的复诊材料清单；只读",
                List.of("hospital", "department"), AgentOrchestrator.Route.ASK_MATERIALS);
        register("travel.routePlan", "查询到医院的路线、距离、预计用时和建议出发时间；只读",
                List.of("appointmentId", "transport"), AgentOrchestrator.Route.QUERY_TRAVEL_GUIDE);
        register("hospital.locationGuide", "查询门诊楼入口、楼层、诊室、报到点和无障碍指引；只读",
                List.of("appointmentId", "hospital", "department"), AgentOrchestrator.Route.QUERY_LOCATION_GUIDE);
        // 药品知识：只能查知识库里有的药，查不到就如实说没查到。老人本人和照护端都可能问，
        // 所以不加角色限制。
        register("drug.queryKnowledge", "查询药品的名称、规格、类别、用途和用药提醒；只读，不提供诊断、不判断该不该吃、不建议换药加量",
                List.of("drugName", "specification"), AgentOrchestrator.Route.QUERY_DRUG_KNOWLEDGE);
        // 下面两条只对被协同的长辈有意义，老人本人查不到也不需要：注册表按角色限定可见性。
        register("care.timeline", "查询当前协助长辈的复诊动态时间线；只读，仅家属/志愿者可用",
                List.of("elderUserId"), AgentOrchestrator.Route.QUERY_CARE_TIMELINE,
                AgentRole.FAMILY, AgentRole.VOLUNTEER);
        register("care.notifications", "查询发给当前操作者的协同通知；只读，仅家属/志愿者可用",
                List.of(), AgentOrchestrator.Route.QUERY_CARE_NOTIFICATIONS,
                AgentRole.FAMILY, AgentRole.VOLUNTEER);
    }

    private void register(String name, String description, List<String> arguments,
                          AgentOrchestrator.Route route) {
        register(name, description, arguments, route, AgentRole.values());
    }

    private void register(String name, String description, List<String> arguments,
                          AgentOrchestrator.Route route, AgentRole... roles) {
        Set<AgentRole> allowed = roles.length == 0
                ? EnumSet.allOf(AgentRole.class) : EnumSet.copyOf(List.of(roles));
        tools.put(name, new RegisteredTool(new PlannerTool(name, description, "READ_ONLY", arguments),
                route, allowed));
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
