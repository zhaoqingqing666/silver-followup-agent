package com.team.silveragent.application;

import com.team.silveragent.agent.planning.PlannerTool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 模型可见的只读工具白名单。写工具不会注册到这里。 */
@Component
final class ToolRegistry {
    record RegisteredTool(PlannerTool definition, AgentOrchestrator.Route route) { }

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
        register("appointment.queryMine", "查询当前用户已确认预约；只读，用户身份由 Java 注入",
                List.of("date", "hospital", "department"), AgentOrchestrator.Route.QUERY_MY_APPOINTMENTS);
        register("material.checklist", "查询医院和科室的复诊材料清单；只读",
                List.of("hospital", "department"), AgentOrchestrator.Route.ASK_MATERIALS);
        register("travel.routePlan", "查询到医院的路线、距离、预计用时和建议出发时间；只读",
                List.of("appointmentId", "transport"), AgentOrchestrator.Route.QUERY_TRAVEL_GUIDE);
        register("hospital.locationGuide", "查询门诊楼入口、楼层、诊室、报到点和无障碍指引；只读",
                List.of("appointmentId", "hospital", "department"), AgentOrchestrator.Route.QUERY_LOCATION_GUIDE);
    }

    private void register(String name, String description, List<String> arguments, AgentOrchestrator.Route route) {
        tools.put(name, new RegisteredTool(new PlannerTool(name, description, "READ_ONLY", arguments), route));
    }

    List<PlannerTool> plannerTools() {
        return tools.values().stream().map(RegisteredTool::definition).toList();
    }

    Optional<RegisteredTool> find(String name) {
        return Optional.ofNullable(name == null ? null : tools.get(name));
    }
}
