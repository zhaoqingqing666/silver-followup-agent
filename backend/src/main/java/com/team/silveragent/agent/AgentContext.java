package com.team.silveragent.agent;

import java.time.LocalDate;
import java.util.List;

public record AgentContext(
        String stage,
        String knownFacts,
        LocalDate currentDate,
        List<Message> recentMessages,
        Identity identity
) {
    public record Message(String role, String content) { }

    /**
     * 关系上下文：谁在操作、替谁办、二人是什么关系。
     * 由 Java 从已验证的会话身份生成，模型既不能填也不能改。
     */
    public record Identity(String actorName, String subjectName, String relationLabel, AgentRole role) {
        public static final Identity SELF = new Identity(null, null, null, AgentRole.ELDER);

        public static Identity self() { return SELF; }

        public boolean isCaregiver() { return role != null && role.isCaregiver(); }
    }

    /** 老人端自办：没有关系上下文。保留给既有调用点与测试。 */
    public AgentContext(String stage, String knownFacts, LocalDate currentDate, List<Message> recentMessages) {
        this(stage, knownFacts, currentDate, recentMessages, Identity.SELF);
    }
}
