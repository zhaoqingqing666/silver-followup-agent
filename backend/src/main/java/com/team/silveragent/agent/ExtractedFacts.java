package com.team.silveragent.agent;

import java.time.LocalDate;
import java.time.LocalTime;

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
        /** 老人本轮提到的医生：全名 / 姓氏+职称 / “专家”这类号别诉求，由 Java 再解析。 */
        String doctor
) {
    public static ExtractedFacts empty() {
        return new ExtractedFacts("UNKNOWN", null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * 只换 {@code selectedTime} 一个分量，其余原样拷贝。
     *
     * <p>模型链路补口语时间用（{@code AgentRuntime}）：那里只有「补一个空槽」这一件事，
     * 在调用点摆一串 17 参构造，加字段时就得回来数第几个参数。
     */
    public ExtractedFacts withSelectedTime(LocalTime time) {
        return new ExtractedFacts(intent, hospital, department, date, acceptAlternative,
                needCompanion, needTravel, notifyFamily, transport, time, timePreference,
                acceptRecommendedTime, acknowledgement, emotion, concern, familyContact, doctor);
    }

    /**
     * 兼容构造：加 {@code doctor} 字段之前的旧 16 参调用点（含两处测试）不牵动。
     */
    public ExtractedFacts(String intent, String hospital, String department, LocalDate date,
                          Boolean acceptAlternative, Boolean needCompanion, Boolean needTravel,
                          Boolean notifyFamily, String transport, LocalTime selectedTime,
                          String timePreference, Boolean acceptRecommendedTime, String acknowledgement,
                          String emotion, String concern, String familyContact) {
        this(intent, hospital, department, date, acceptAlternative, needCompanion, needTravel,
                notifyFamily, transport, selectedTime, timePreference, acceptRecommendedTime,
                acknowledgement, emotion, concern, familyContact, null);
    }
}
