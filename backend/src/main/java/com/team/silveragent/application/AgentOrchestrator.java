package com.team.silveragent.application;

import com.team.silveragent.agent.ExtractedFacts;
import com.team.silveragent.agent.MedicalBoundaryRules;
import org.springframework.stereotype.Component;

/**
 * 中控只决定“本轮该走哪条业务路由”。
 * 大模型负责理解意图，Java 中控负责安全优先级和流程状态，工具负责真实数据。
 */
@Component
final class AgentOrchestrator {
    enum Route {
        EMERGENCY, MEDICAL_BOUNDARY, CONFIRM_PENDING, DENY_PENDING,
        CANCEL_CURRENT_TASK, CANCEL_EXISTING_APPOINTMENT, QUERY_MY_APPOINTMENTS,
        RESTART_TASK, RESUME_TASK, START_PLAN,
        QUERY_HOSPITALS, QUERY_DEPARTMENTS, RECOMMEND_HOSPITAL,
        QUERY_AVAILABLE_SLOTS, ASK_MATERIALS,
        CHANGE_HOSPITAL, CHANGE_DEPARTMENT, CHANGE_DATE, CHANGE_TIME,
        CURRENT_FLOW
    }

    Route decide(String message, ExtractedFacts facts, ConversationState state) {
        String intent = facts.intent() == null ? "UNKNOWN" : facts.intent();

        if ("EMERGENCY".equals(intent) || contains(message, "胸痛", "呼吸困难", "昏迷", "大出血", "喘不上气")) {
            return Route.EMERGENCY;
        }
        // 安全边界先于业务意图：越界漏判会表现为“被静默忽略”，代价高于多提示一次。
        if ("MEDICAL_ADVICE".equals(intent) || MedicalBoundaryRules.looksLikeMedicalAdvice(message)) {
            return Route.MEDICAL_BOUNDARY;
        }
        if (state.stage == ConversationState.Stage.AWAITING_CONFIRMATION) {
            if ("CONFIRM_ACTION".equals(intent) || contains(message, "确认取消", "确认办理", "执行操作")) {
                return Route.CONFIRM_PENDING;
            }
            if ("DENY_ACTION".equals(intent) || contains(message, "保留预约", "不执行", "返回修改")) {
                return Route.DENY_PENDING;
            }
        }

        if ("CREATE_FOLLOWUP".equals(intent)
                && (state.stage == ConversationState.Stage.CANCELLED
                || state.stage == ConversationState.Stage.COMPLETED)) {
            return Route.RESTART_TASK;
        }

        return switch (intent) {
            case "CANCEL_TASK" -> Route.CANCEL_CURRENT_TASK;
            case "CANCEL_APPOINTMENT" -> Route.CANCEL_EXISTING_APPOINTMENT;
            case "QUERY_APPOINTMENTS" -> Route.QUERY_MY_APPOINTMENTS;
            case "RESTART_TASK" -> Route.RESTART_TASK;
            case "RESUME_TASK" -> Route.RESUME_TASK;
            case "START_EXECUTION" -> Route.START_PLAN;
            case "QUERY_HOSPITALS", "QUERY_HOSPITAL_INFO" -> Route.QUERY_HOSPITALS;
            case "QUERY_DEPARTMENTS" -> Route.QUERY_DEPARTMENTS;
            case "REQUEST_RECOMMENDATION" -> Route.RECOMMEND_HOSPITAL;
            case "QUERY_AVAILABLE_SLOTS" -> Route.QUERY_AVAILABLE_SLOTS;
            case "ASK_MATERIALS" -> Route.ASK_MATERIALS;
            case "CHANGE_HOSPITAL" -> Route.CHANGE_HOSPITAL;
            case "CHANGE_DEPARTMENT" -> Route.CHANGE_DEPARTMENT;
            case "CHANGE_DATE" -> Route.CHANGE_DATE;
            case "CHANGE_TIME" -> Route.CHANGE_TIME;
            default -> Route.CURRENT_FLOW;
        };
    }

    private boolean contains(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }
}
