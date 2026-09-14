package com.team.silveragent.agent;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 一轮对话里、Java 愿意认下来的事实。
 *
 * <p>{@code excludedHospitals} 是老人这一轮<b>明确说不要</b>的医院目标，可能不止一家。
 * 它不是草稿字段：只决定这一轮推荐时过滤掉谁，不落 {@code ConversationState}、不进长期偏好，
 * 下一轮改口就按新条件重新推荐。
 *
 * <p>为什么单独占一个组件、而不是塞进工具参数那一层：参数是 {@code Map<String,String>}，
 * 表达多个目标只能靠分隔符切分，而医院名本身就可能带顿号、逗号、括号（例如
 * 「市第一医院（门诊部）」），切错了等于 Java 替模型猜它排除了谁。规划 JSON 的 {@code facts}
 * 节点本来就是 {@code JsonNode}，可以原样读一个字符串数组，这是假设最少的表达。
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
        String emotion,
        String concern,
        String familyContact,
        List<String> excludedHospitals
) {
    /**
     * 16 参兼容构造：不涉及排除条件的调用点（规则抽取、确认动作、测试脚手架）不必跟着改。
     * <p>只读工具轮那条「拿掉会落到草稿上的字段」的路径要显式传下去——排除不是草稿字段。
     */
    public ExtractedFacts(String intent, String hospital, String department, LocalDate date,
                          Boolean acceptAlternative, Boolean needCompanion, Boolean needTravel,
                          Boolean notifyFamily, String transport, LocalTime selectedTime,
                          String timePreference, Boolean acceptRecommendedTime, String acknowledgement,
                          String emotion, String concern, String familyContact) {
        this(intent, hospital, department, date, acceptAlternative, needCompanion, needTravel,
                notifyFamily, transport, selectedTime, timePreference, acceptRecommendedTime,
                acknowledgement, emotion, concern, familyContact, List.of());
    }

    public ExtractedFacts {
        excludedHospitals = excludedHospitals == null ? List.of() : List.copyOf(excludedHospitals);
    }

    public static ExtractedFacts empty() {
        return new ExtractedFacts("UNKNOWN", null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }
}
