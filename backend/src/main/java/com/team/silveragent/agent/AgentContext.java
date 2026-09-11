package com.team.silveragent.agent;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 模型层统一消费的会话上下文（业务层组装，模型实现层只读）。
 *
 * 这里只放"规范化的业务信息"，不放 dataUrl / 文件路径等存储细节：
 * 图片附件的存储形态将来从 dataUrl 换成文件存储时，本对象和所有模型实现都不用改。
 *
 * vision 是本次会话内最近若干张图片的识别结果。它让"刚才那张图片""提取图片上的文字"
 * 这类后续追问能命中之前的图片，而不必让老人重新上传。
 */
public record AgentContext(
        String stage,
        String knownFacts,
        LocalDate currentDate,
        List<Message> recentMessages,
        List<VisionNote> vision
) {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** 兼容不带图片上下文的调用方。 */
    public AgentContext(String stage, String knownFacts, LocalDate currentDate, List<Message> recentMessages) {
        this(stage, knownFacts, currentDate, recentMessages, List.of());
    }

    public record Message(String role, String content) { }

    /**
     * 一次图片识别结果。hint 是老人上传时的补充说明，description 是"这图片是什么"的摘要，
     * ocr 是图片上的完整文字（可能为 null，表示视觉模型没提取到完整文字）。
     */
    public record VisionNote(String hint, String description, String ocr, LocalDateTime createdAt) { }

    /** 把图片识别结果渲染成给模型看的文字；没有图片时返回空串，调用方自行判断。 */
    public String visionSummary() {
        if (vision == null || vision.isEmpty()) return "";
        StringBuilder text = new StringBuilder();
        for (VisionNote note : vision) {
            text.append("- ");
            if (note.createdAt() != null) text.append(note.createdAt().format(TIME)).append(" ");
            text.append("用户上传过一张图片");
            if (note.hint() != null && !note.hint().isBlank()) {
                text.append("，当时说：「").append(note.hint().trim()).append("」");
            }
            text.append("，识别结果是：").append(note.description() == null ? "" : note.description().trim());
            text.append('\n');
        }
        return text.toString();
    }

    /**
     * 当前图片（最近一批上传，一次最多 3 张合并成一条记录）的完整 OCR 原文。
     * vision 列表是"新的在前"，取第 0 条即最新那一批；没有 OCR 就返回空串，
     * 绝不回退到更早一批图片，避免把上一批的内容串到这一批。
     */
    public String latestVisionOcr() {
        if (vision == null || vision.isEmpty()) return "";
        String ocr = vision.get(0).ocr();
        return ocr == null ? "" : ocr.trim();
    }
}
