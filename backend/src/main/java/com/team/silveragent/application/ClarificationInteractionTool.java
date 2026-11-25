package com.team.silveragent.application;

import com.team.silveragent.domain.model.AgentTurnResponse.QuickReply;
import com.team.silveragent.domain.model.ToolModels.AppointmentSummary;
import com.team.silveragent.infrastructure.mock.ToolTraceStore;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把「需要老人澄清一件事」做成独立的交互工具。
 *
 * <p>分工是刻意的：<b>问题由模型提出</b>（它更会说话），<b>候选由 Java 从真实工具结果里补上</b>
 * （模型看不见数据库，让它列预约就是让它编）。所以这里收进来的 {@code candidates} 必须是调用方
 * 刚从 {@code appointment.queryMine} 一类只读工具拿到的真实行，不接收模型自报的候选。
 *
 * <p>三个通道的口径必须说清楚，否则很容易被合并成一个：
 * <ul>
 *   <li>澄清（本类）：只问一句、摆出候选。<b>不建卡、不发 {@code confirmationId}</b>。</li>
 *   <li>确认（{@link ConfirmationInteractionTool}）：建卡、发凭据，等老人明确确认。</li>
 *   <li>执行：只认有效凭据，在 {@code confirm()} 里发生。</li>
 * </ul>
 * 凭据是全部写操作的唯一钥匙；澄清不碰它，所以「模型问了一句话」这件事本身变不出任何执行授权。
 */
@Component
final class ClarificationInteractionTool {
    private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("yyyy年M月d日");
    private static final DateTimeFormatter TIME_LABEL = DateTimeFormatter.ofPattern("HH:mm");
    /** 一页最多摆几条候选：再多按钮就挤成一团，老人反而点不准。超出的部分翻页，不是丢掉。 */
    static final int PAGE_SIZE = 4;
    private static final int MAX_QUESTION_CHARS = 80;
    /** 翻页按钮的动作名。值就是页码（从 0 开始），翻到第几页由按钮自己带着，不必记在会话里。 */
    static final String MORE_CANDIDATES_ACTION = "MORE_CANCEL_CANDIDATES";

    /**
     * 一次澄清要交给老人的东西。
     *
     * @param kind    这次是「需要澄清」还是「根本没有可澄清的对象」
     * @param reply   说给老人听的话，同时也是口播文本
     * @param choices 真实候选的点选按钮（当前这一页），外加减到别的页的按钮
     */
    record Prompt(ToolOutcome.Kind kind, String reply, List<QuickReply> choices) { }

    private final ToolTraceStore traces;

    ClarificationInteractionTool(ToolTraceStore traces) {
        this.traces = traces;
    }

    /**
     * 取消预约时问「您说的是哪一条」。
     *
     * @param modelQuestion    模型写的自然追问；过不了结构闸门或为空时用 {@code fallbackQuestion}
     * @param fallbackQuestion Java 固定的问法
     */
    Prompt askWhichAppointmentToCancel(String conversationId, List<AppointmentSummary> candidates,
                                       String modelQuestion, String fallbackQuestion) {
        return askWhichAppointmentToCancel(conversationId, candidates, modelQuestion, fallbackQuestion, 0);
    }

    /**
     * 同上，但可以翻到第 {@code page} 页（从 0 开始）。
     *
     * <p>一次只摆 {@link #PAGE_SIZE} 条是为了看得清、点得准，但<b>不能因此把剩下的候选藏掉</b>：
     * 老人可能只记得「上个月那条」，它偏偏排在第五位。所以超出部分用「下一页 / 上一页」接上，
     * 页码由按钮自己带过来，不在会话里记状态——翻页是纯粹的展示动作，不该动任何业务状态。
     * 页码越界一律夹回有效范围（最后一个按钮永远能回到真实的候选上）。
     */
    Prompt askWhichAppointmentToCancel(String conversationId, List<AppointmentSummary> candidates,
                                       String modelQuestion, String fallbackQuestion, int page) {
        List<AppointmentSummary> rows = candidates == null ? List.of() : candidates.stream()
                .filter(item -> item != null && item.appointmentId() != null).toList();
        if (rows.isEmpty()) {
            // 「查不到」和「说不清」是两件事：这里没有候选可摆，绝不摆一个空问题糊弄过去。
            String reply = "我没有查到可以取消的已确认预约，所以没有执行任何取消操作。";
            traces.record(conversationId, "interaction.askClarification",
                    Map.of("candidateTool", "appointment.queryMine"),
                    Map.of("outcome", ToolOutcome.Kind.NO_RESULT.name(), "candidateCount", 0), true);
            return new Prompt(ToolOutcome.Kind.NO_RESULT, reply, List.of());
        }

        String accepted = safeQuestion(modelQuestion);
        String question = accepted == null ? fallbackQuestion : accepted;
        int pageCount = (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int current = Math.max(0, Math.min(page, pageCount - 1));
        List<AppointmentSummary> shown = rows.stream()
                .skip((long) current * PAGE_SIZE).limit(PAGE_SIZE).toList();

        List<QuickReply> choices = new ArrayList<>();
        for (AppointmentSummary item : shown) {
            choices.add(new QuickReply(item.date().format(DATE_LABEL) + " "
                    + item.time().format(TIME_LABEL) + " " + item.hospital(),
                    "SELECT_APPOINTMENT_TO_CANCEL", item.appointmentId()));
        }
        if (current > 0) {
            choices.add(new QuickReply("看上一页", MORE_CANDIDATES_ACTION, String.valueOf(current - 1)));
        }
        if (current < pageCount - 1) {
            choices.add(new QuickReply("再看几条（还有" + (rows.size() - (current + 1) * PAGE_SIZE) + "条）",
                    MORE_CANDIDATES_ACTION, String.valueOf(current + 1)));
        }
        // 候选要念出来，也要摆出来：老人可以直接说“9月10日那条”，也可以点。
        // 这一页是第几页、一共几条、还剩几条没摆，都要说清楚——不然他会以为只查到了这几条。
        String reply = question + "\n我查到" + rows.size() + "条已确认预约"
                + (pageCount > 1 ? "，这是第" + (current + 1) + "页（共" + pageCount + "页）" : "")
                + "：" + shown.stream().map(ClarificationInteractionTool::label)
                .collect(java.util.stream.Collectors.joining("；")) + "。"
                + (current < pageCount - 1 ? "\n还想看后面的，就点“再看几条”。" : "");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("candidateTool", "appointment.queryMine");
        request.put("questionFromModel", !question.equals(fallbackQuestion));
        request.put("page", current);
        traces.record(conversationId, "interaction.askClarification", request,
                Map.of("outcome", ToolOutcome.Kind.NEEDS_CLARIFICATION.name(),
                        "candidateCount", rows.size(),
                        "candidateIds", shown.stream().map(AppointmentSummary::appointmentId).toList()), true);
        return new Prompt(ToolOutcome.Kind.NEEDS_CLARIFICATION, reply, choices);
    }

    /**
     * 模型写的追问只能是一句「问」，不能塞进业务事实。
     *
     * <p>闸门是结构性的，不靠穷举中文：<b>不许出现数字</b>（日期、条数、时间都在这里被挡住，免得它
     * 编一个数据库里没有的日期来问），不许出现完成态说法（「已经帮您取消了」），要有问句的样子，
     * 长度不超过一屏。过不了就整句丢掉、退回 Java 固定问法——候选照摆，只是话由 Java 说。
     */
    private String safeQuestion(String value) {
        if (value == null) return null;
        String question = value.trim().replace('\n', ' ');
        if (question.isEmpty() || question.length() > MAX_QUESTION_CHARS) return null;
        if (question.matches("(?s).*\\d.*")) return null;
        if (containsAny(question, "已取消", "已经取消", "取消成功", "已完成", "已经完成",
                "已成功", "已经成功", "已经帮您")) {
            return null;
        }
        boolean asksSomething = question.contains("？") || question.contains("?")
                || containsAny(question, "哪", "几", "什么", "是不是", "要不要", "吗");
        return asksSomething ? question : null;
    }

    private static String label(AppointmentSummary item) {
        return item.date().format(DATE_LABEL) + " " + item.time().format(TIME_LABEL)
                + " " + item.hospital() + " · " + item.department();
    }

    private boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }
}
