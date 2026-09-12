package com.team.silveragent.application;

import com.team.silveragent.domain.model.AgentTurnResponse.ConfirmationCard;
import com.team.silveragent.domain.model.ToolModels.AppointmentSummary;
import com.team.silveragent.infrastructure.mock.ToolTraceStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把“需要用户确认”做成独立的交互工具。
 *
 * <p>模型最多在卡片前面写一句不带业务事实的自然开场；卡片标题、预约对象、影响、按钮和口播中的
 * 关键事实都由 Java 根据数据库快照生成。这个工具只创建确认交互，不执行取消，也不写预约表。
 */
@Component
final class ConfirmationInteractionTool {
    record Prompt(String reply, String speechText, ConfirmationCard card) { }

    private final ToolTraceStore traces;

    ConfirmationInteractionTool(ToolTraceStore traces) {
        this.traces = traces;
    }

    Prompt requestCancellation(String conversationId, String confirmationId,
                               List<AppointmentSummary> targets, String modelDraft) {
        if (targets == null || targets.isEmpty()) throw new IllegalArgumentException("确认取消时缺少预约对象");

        List<String> operations = new ArrayList<>();
        for (AppointmentSummary target : targets) {
            operations.add("取消预约：" + appointmentLabel(target));
        }
        operations.add(targets.size() == 1 ? "释放对应模拟号源并停用关联提醒"
                : "释放以上模拟号源并停用各自的关联提醒");

        // 标题由 Java 固定生成，并带上真实条数：它是老人决定点哪个按钮时最依赖的一句话，
        // 不能交给看不见数据库的模型去写（模型写错过“已经帮您取消预约了吗？”这种标题）。
        String title = targets.size() == 1 ? "是否取消这次复诊预约"
                : "是否取消这" + targets.size() + "条复诊预约";
        String impact = targets.size() == 1
                ? "确认取消后，原预约将失效，对应号源会被释放，无法直接恢复。"
                : "确认后，上述预约都会失效，对应号源会被释放，无法直接恢复。";
        String confirmText = targets.size() == 1 ? "确认取消" : "确认全部取消";
        String cancelText = targets.size() == 1 ? "保留预约" : "全部保留";
        ConfirmationCard card = new ConfirmationCard(title, operations, impact,
                confirmText, cancelText, confirmationId);

        String authoritative = targets.size() == 1
                ? "您准备取消" + appointmentLabel(targets.get(0)) + "。"
                : "您准备取消以下" + targets.size() + "条预约："
                + targets.stream().map(ConfirmationInteractionTool::appointmentLabel)
                .collect(java.util.stream.Collectors.joining("；")) + "。";
        // 模型的话只出现在卡片前面那一句开场白里，事实句、影响、按钮和口播全部由 Java 生成。
        String intro = safeModelIntro(modelDraft);
        String reply = (intro == null ? "" : intro + "\n") + authoritative + "\n" + impact
                + "\n确认取消请点击红色的“" + confirmText + "”按钮；不取消请点击绿色的“"
                + cancelText + "”按钮。您也可以直接说“" + confirmText + "”或“" + cancelText + "”。";
        traces.record(conversationId, "interaction.requestConfirmation",
                Map.of("operationType", targets.size() == 1 ? "CANCEL_APPOINTMENT" : "CANCEL_APPOINTMENTS",
                        "targetIds", targets.stream().map(AppointmentSummary::appointmentId).toList(),
                        "confirmationId", confirmationId),
                Map.of("status", "AWAITING_CONFIRMATION", "targetCount", targets.size()), true);
        return new Prompt(reply, reply, card);
    }

    /**
     * 模型只能写卡片前面那一句自然开场（“好的，我再和您确认一下。”），不许碰业务事实。
     *
     * <p>闸门不靠穷举中文完成态，而是一条结构性约束：<b>这句话不得提及业务</b>——不能出现
     * “取消/预约/号源/提醒”这些词，不能有数字（日期、条数），不能带“已/成功/完成/好了”这类
     * 完成态说法。既然它一个业务词都说不了，也就编不出“已经帮您取消好了”或
     * “已经帮您取消预约了吗”这种话。校验不过就整句丢弃，卡片照样有 Java 生成的事实句。
     */
    private String safeModelIntro(String value) {
        if (value == null) return null;
        String intro = value.trim().replace('\n', ' ');
        if (intro.isEmpty() || intro.length() > 60) return null;
        if (intro.matches("(?s).*\\d.*")) return null;
        if (containsAny(intro, "取消", "预约", "号源", "提醒", "已", "成功", "完成", "好了", "释放")) {
            return null;
        }
        return intro;
    }

    private static String appointmentLabel(AppointmentSummary target) {
        return target.date().getYear() + "年" + target.date().getMonthValue() + "月"
                + target.date().getDayOfMonth() + "日 " + target.time() + " "
                + target.hospital() + " · " + target.department();
    }

    private boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }
}
