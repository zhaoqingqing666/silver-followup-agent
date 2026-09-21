package com.team.silveragent.application;

import com.team.silveragent.application.health.HealthRecordStore;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.tool.HealthRecordTool;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 「往健康记录里写一条实测数值」这一类已确认操作的执行器。
 *
 * <p>与 {@link MemoExecutor} 同构，理由也一样：写健康记录有两条路——老人平静地报一个数
 * （走确认卡，他点头才落库）和被反问过“这个数不太对”之后当场重报/照记（他刚为这个数表过态，
 * 直接落库）。两条路写的是同一张表、回读的是同一套话术，所以只有<b>一条</b>写实现
 * （{@link #write}），确认卡那条路只是它的一个入口。
 *
 * <p>{@link #commit} / {@link #refuse} 分开成两个方法：拒绝那条<b>一个业务动作都不执行</b>，
 * 只把草稿收干净、把话头交回去（“这条数值没有记下”）。老人报的数、包括量不出来的数，都不会因为
 * 点了一次「先不用」就偷偷留下半条记录。
 */
@Component
final class HealthRecordExecutor {
    private final HealthRecordTool healthRecordTool;

    HealthRecordExecutor(HealthRecordTool healthRecordTool) {
        this.healthRecordTool = healthRecordTool;
    }

    /** 老人点了「确认记下」：把卡上那条数值落成一条记录。 */
    AgentTurnResponse commit(ConversationState state, ConfirmationSupport support) {
        String item = state.pendingRecordItem;
        BigDecimal valueNum = state.pendingRecordValueNum;
        String valueText = state.pendingRecordValueText;
        String unit = state.pendingRecordUnit;
        String raw = state.pendingRecordRaw;
        LocalDateTime at = state.pendingRecordAt;
        clearDraft(state);
        if (item == null || item.isBlank() || valueText == null || valueText.isBlank() || at == null) {
            return support.memoHandoff(state, "这条数值已经失效，请重新对我说一遍。");
        }
        return write(state, item, valueNum, valueText, unit, raw == null ? valueText : raw, at, support);
    }

    /** 老人点了「先不用」：草稿丢掉，不落库。 */
    AgentTurnResponse refuse(ConversationState state, ConfirmationSupport support) {
        clearDraft(state);
        return support.memoHandoff(state, "好的，这条数值没有记下。");
    }

    /**
     * 落库一条实测数值并回到原办理上下文（确认后落库与反问后落库共用）。
     *
     * <p>{@code at} 由调用方给死，不在这里取当前时间：确认卡上写着「9月14日 21:30」，
     * 写进去的就必须是这一刻。拿确认那一刻顶替，卡上写的时间与记录里的时间就成了两回事，
     * 而这张卡的全部意义就是让老人核对「记的是不是这个数、这个时间」。
     *
     * <p>写库这一步失败必须在这里收住，理由与 {@link MemoExecutor#write} 那段完全相同：
     * 它的调用点之一是 {@code FollowupAgentService.confirm()}，异常穿出去就是 HTTP 500，
     * 而这一轮到底写没写成谁也说不清。收住之后走 {@link ConfirmationSupport#toolError}，
     * 与其它工具的失败同一个出口。
     */
    AgentTurnResponse write(ConversationState state, String item, BigDecimal valueNum, String valueText,
                            String unit, String raw, LocalDateTime at, ConfirmationSupport support) {
        HealthRecordStore.RecordView created;
        try {
            // 记录项带单位（“血压 100 mmHg”）：工具参数里单位单独一项，缺了记 0 会看起来像没单位
            Map<String, String> parameters = new HashMap<>();
            parameters.put("item", item);
            parameters.put("value", valueText);
            parameters.put("unit", unit == null ? "" : unit);
            created = support.callTool(state, "healthRecord.create", parameters,
                    () -> healthRecordTool.create(state.id, state.userId, item, valueNum, valueText, unit, raw, at));
        } catch (RuntimeException error) {
            return support.toolError(state, error);
        }
        return support.memoHandoff(state, support.healthRecordedReply(
                created.item(), created.valueText(), created.unit(), created.recordedAt()));
    }

    /**
     * 丢弃待记的那条数值，并把话头还回原来的办理上下文。
     *
     * <p>它与编排层 {@code clearPendingRecord} 是同一件事（反问那条路也调用这里），所以只有这一份
     * 实现：两处各清一遍，早晚会在「还要清哪个字段」上走散，而漏掉的那个字段正好就是
     * {@code pendingRecordAt} 这类「缺了凭据就不作数」的判据。
     *
     * <p>阶段只在弹过确认卡时才有得还（{@link ConversationState#recordReturnStage} 非空）——
     * 反问不改阶段，那时把阶段改回 {@code READY_TO_PLAN} 会把老人正在办的复诊一脚踢出去。
     */
    void clearDraft(ConversationState state) {
        state.pendingRecordItem = null;
        state.pendingRecordValueNum = null;
        state.pendingRecordValueText = null;
        state.pendingRecordUnit = null;
        state.pendingRecordRaw = null;
        state.pendingRecordAt = null;
        state.pendingAction = state.recordReturnAction == null ? "CREATE" : state.recordReturnAction;
        state.recordReturnAction = null;
        if (state.recordReturnStage != null) {
            state.stage = state.recordReturnStage;
            state.recordReturnStage = null;
        }
    }
}
