package com.team.silveragent.agent;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record AgentContext(
        String stage,
        String knownFacts,
        LocalDate currentDate,
        List<Message> recentMessages,
        Identity identity,
        /** 本会话最近识别过的图片，新的在前；没有图时是空列表。 */
        List<VisionNote> vision
) {
    public record Message(String role, String content) { }

    /**
     * 一次图片识别的结果，只保留能放进提示词的文字。
     *
     * <p><b>不含 data URL</b>：图片 base64 动辄几十万字符，放进每一轮提示词会直接压垮上下文。
     * 图片本体存在附件表里，这里只带识别结论。
     */
    public record VisionNote(String hint, String description, String ocr, LocalDateTime createdAt) { }

    /**
     * 关系上下文：谁在操作、替谁办、二人是什么关系。
     * 由 Java 从已验证的会话身份生成，模型既不能填也不能改。
     */
    public record Identity(String actorName, String subjectName, String relationLabel, AgentRole role) {
        public static final Identity SELF = new Identity(null, null, null, AgentRole.ELDER);

        public static Identity self() { return SELF; }

        public boolean isCaregiver() { return role != null && role.isCaregiver(); }
    }

    public AgentContext {
        vision = vision == null ? List.of() : List.copyOf(vision);
    }

    /** 带身份、没有识图记录：代他人办理的既有调用点。 */
    public AgentContext(String stage, String knownFacts, LocalDate currentDate,
                        List<Message> recentMessages, Identity identity) {
        this(stage, knownFacts, currentDate, recentMessages, identity, List.of());
    }

    /** 老人端自办：没有关系上下文、没有识图记录。保留给既有调用点与测试。 */
    public AgentContext(String stage, String knownFacts, LocalDate currentDate, List<Message> recentMessages) {
        this(stage, knownFacts, currentDate, recentMessages, Identity.SELF, List.of());
    }

    public boolean hasVision() {
        return !vision.isEmpty();
    }

    /**
     * 最近一张（或一批）图的描述，供提示词做 grounding。
     * 只取最新的一批，避免把几轮之前的图混进本轮判断。
     */
    public String visionSummary() {
        if (vision.isEmpty()) return "";
        VisionNote latest = vision.get(0);
        String value = latest.description();
        return value == null ? "" : value.trim();
    }

    /**
     * 最近一张图的 OCR 原文。
     * 只取 index 0：多张图的文字拼在一起会串味，用户问“上面写了什么”指的通常是刚拍的那张。
     */
    public String latestVisionOcr() {
        if (vision.isEmpty()) return "";
        String value = vision.get(0).ocr();
        return value == null ? "" : value.trim();
    }
}
