package com.team.silveragent.domain.model;

import java.util.List;
import java.util.Map;

public record AgentTurnResponse(
        String conversationId,
        String stage,
        String reply,
        List<QuickReply> quickReplies,
        PlanCard plan,
        ConfirmationCard confirmation,
        ResultCard result,
        List<ToolTrace> toolTraces,
        String autoAction
) {
    public static AgentTurnResponse message(String conversationId, String stage, String reply, List<QuickReply> quickReplies) {
        return new AgentTurnResponse(conversationId, stage, reply, quickReplies, null, null, null, List.of(), null);
    }

    public record QuickReply(String label, String action, String value) { }

    public record PlanCard(String hospital, String department, String date, String selectedTime,
                           List<String> tasks, List<String> materials, String departureTime,
                           String familyContact) { }

    public record ConfirmationCard(String title, List<String> operations, String impact,
                                   String confirmText, String cancelText) { }

    public record ResultCard(String appointmentId, String hospital, String department, String date,
                             String time, List<String> materials, String departureTime,
                             String reminderStatus, String familyStatus) { }

    /**
     * 一次真实的工具执行记录。toolName / parameters / result 全部来自运行时真实结果
     * （参数由模型实时生成、结果由工具真实执行后落库），不是预先写好的展示文案。
     * label / summary 只是给老人看的友好说法，由 toolName 和真实结果推导，不改变事实。
     */
    public record ToolTrace(String toolName, String label, String parameters, String result,
                            String summary, boolean success) {

        /**
         * 工具名 → 面向老人的中文名称。未知工具回落到工具名本身。
         *
         * 放在 ToolTrace 内部而不是外层：嵌套类不会继承外层类的静态方法，
         * 只有定义在自己身上，ToolTrace.friendlyName(...) 才能解析到。
         */
        public static String friendlyName(String toolName) {
            if (toolName == null) return "工具调用";
            return FRIENDLY_NAMES.getOrDefault(toolName, toolName);
        }

        private static final Map<String, String> FRIENDLY_NAMES = Map.ofEntries(
                Map.entry("vision.recognize", "图片识别"),
                Map.entry("drug.queryKnowledge", "药品知识查询"),
                Map.entry("appointment.querySlots", "号源查询"),
                Map.entry("appointment.queryAlternatives", "备选号源查询"),
                Map.entry("appointment.queryUpcomingAvailable", "近期可约号源查询"),
                Map.entry("appointment.queryMine", "我的复诊记录查询"),
                Map.entry("appointment.submit", "提交预约"),
                Map.entry("appointment.cancel", "取消预约"),
                Map.entry("catalog.queryHospitals", "医院查询"),
                Map.entry("catalog.recommendHospitals", "可约医院查询"),
                Map.entry("catalog.queryDepartments", "科室查询"),
                Map.entry("catalog.searchDepartments", "科室检索"),
                Map.entry("material.generateChecklist", "复诊材料清单查询"),
                Map.entry("material.initializePreparation", "材料准备清单生成"),
                Map.entry("schedule.checkConflict", "日程冲突检查"),
                Map.entry("schedule.createReminder", "复诊提醒创建"),
                Map.entry("family.queryContact", "家属联系人查询"),
                Map.entry("family.notify", "家属通知"),
                Map.entry("travel.plan", "出行方案查询"));
    }
}
