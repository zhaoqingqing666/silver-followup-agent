package com.team.silveragent.agent;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 一轮用户输入的结构化理解结果。
 *
 * <p>前 13 个字段是历史就有的「固定槽位」，预约办理流程（ConversationState / advance）继续依赖它们，
 * 行为不变。后 5 个字段是本次升级新增的「多任务 + 动态约束」结构化输出：
 *
 * <ul>
 *   <li>{@code tasks} —— 把一句话拆成需要分别办理的子任务，支持「帮我安排复诊，出发前提醒我，
 *       并告诉女儿」这类一句话多件事的需求；</li>
 *   <li>{@code constraints} —— 用户明确说出的硬性限制（「必须上午」「不能周三」「国庆前」），
 *       原话保留，不做归一化改写；</li>
 *   <li>{@code preferences} —— 非硬性偏好（「人少一点」「想要女医生」）；</li>
 *   <li>{@code additionalRequests} —— 落不进任何固定槽位、但用户确实提出的诉求，绝不能静默丢弃；</li>
 *   <li>{@code missingInformation} —— 要把上面这些任务办完还缺的关键信息。</li>
 * </ul>
 *
 * <p>这些新字段只参与「理解、记录与展示」，<b>不改变写操作权限</b>：所有写操作仍然只由
 * {@code FollowupAgentService.confirm()} 在用户确认之后执行。
 */
public record ExtractedFacts(
        String intent,
        String hospital,
        String department,
        LocalDate date,
        Boolean acceptAlternative,
        Boolean needCompanion,
        Boolean needTravel,
        Boolean notifyFamily,
        String transport,
        LocalTime selectedTime,
        String timePreference,
        Boolean acceptRecommendedTime,
        String acknowledgement,
        List<TaskItem> tasks,
        List<String> constraints,
        List<String> preferences,
        List<String> additionalRequests,
        List<String> missingInformation
) {
    /** 粗粒度任务类型之一，用于 tasks 项归纳不出更具体类型时兜底。 */
    public static final String OTHER_TASK = "其他";

    /**
     * 能归纳出的任务类型，顺序即拆解顺序。
     * 模型通道、规则兜底通道与计划展示共用这一份，避免三处各写一遍对不上。
     */
    public static final List<String> TASK_KINDS = List.of(
            "预约查询", "时间安排", "材料准备", "出行规划", "家属通知", "日程提醒");

    /**
     * 一个子任务。kind 是粗粒度任务类型（预约查询/时间安排/材料准备/出行规划/家属通知/日程提醒），
     * summary 是给用户看的一句话说明。
     */
    public record TaskItem(String kind, String summary) {
        public TaskItem {
            kind = kind == null || kind.isBlank() ? OTHER_TASK : kind.trim();
            summary = summary == null ? "" : summary.trim();
        }

        /** 渲染成给用户看的一行文字，例如「家属通知：把复诊安排告诉女儿」。 */
        public String label() {
            return summary.isEmpty() || summary.equals(kind) ? kind : kind + "：" + summary;
        }
    }

    /** 列表字段一律不为 null，调用方不必再判空。 */
    public ExtractedFacts {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        preferences = preferences == null ? List.of() : List.copyOf(preferences);
        additionalRequests = additionalRequests == null ? List.of() : List.copyOf(additionalRequests);
        missingInformation = missingInformation == null ? List.of() : List.copyOf(missingInformation);
    }

    /** 什么都没理解出来。 */
    public static ExtractedFacts empty() {
        return slotsOnly("UNKNOWN", null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    /**
     * 只有固定槽位、没有多任务结构的理解结果。
     * 让只关心槽位的调用方（旧流程、按钮动作）不必一路传 5 个空列表。
     */
    public static ExtractedFacts slotsOnly(String intent, String hospital, String department, LocalDate date,
                                           Boolean acceptAlternative, Boolean needCompanion, Boolean needTravel,
                                           Boolean notifyFamily, String transport, LocalTime selectedTime,
                                           String timePreference, Boolean acceptRecommendedTime, String acknowledgement) {
        return new ExtractedFacts(intent, hospital, department, date, acceptAlternative, needCompanion, needTravel,
                notifyFamily, transport, selectedTime, timePreference, acceptRecommendedTime, acknowledgement,
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /**
     * 用户提出的、固定槽位装不下的诉求，按「约束 → 偏好 → 补充诉求」的顺序合并去重。
     * 用于展示计划、注入下一轮模型上下文——保证用户说过的话不会因为槽位装不下就消失。
     */
    public List<String> extraRequirements() {
        List<String> all = new ArrayList<>(constraints);
        for (String item : preferences) if (!all.contains(item)) all.add(item);
        for (String item : additionalRequests) if (!all.contains(item)) all.add(item);
        return List.copyOf(all);
    }

    /** 本轮是否携带了固定槽位以外的多任务/动态约束信息。 */
    public boolean hasStructuredDemand() {
        return !tasks.isEmpty() || !constraints.isEmpty() || !preferences.isEmpty() || !additionalRequests.isEmpty();
    }
}
