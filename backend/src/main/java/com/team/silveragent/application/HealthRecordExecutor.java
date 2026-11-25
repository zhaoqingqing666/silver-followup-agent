package com.team.silveragent.application;

import com.team.silveragent.application.ConversationState.PendingRecord;
import com.team.silveragent.application.health.HealthRecordStore;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.tool.HealthRecordTool;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    /**
     * 老人点了「确认记下」：把卡上列着的数值落成记录——<b>卡上几行就写几条</b>。
     *
     * <p>一句话里报了几项（“我的体温是36.5，心率80”）时，卡上把两条都列了出来，他点一次头
     * 两条一起写。所以这里不能只写第一条：写了第一条就收工，卡上第二行在他点头之后凭空消失，
     * 而库里、回读里都没有它的影子。
     */
    AgentTurnResponse commit(ConversationState state, ConfirmationSupport support) {
        List<PendingRecord> all = pending(state);
        clearDraft(state);
        if (all.isEmpty()) {
            return support.memoHandoff(state, "这条数值已经失效，请重新对我说一遍。");
        }
        List<HealthRecordStore.RecordView> created = new ArrayList<>();
        for (PendingRecord record : all) {
            HealthRecordStore.RecordView row;
            try {
                row = create(state, record, support);
            } catch (RuntimeException error) {
                // 第一条就写不进去：一条都没记下，交回统一的失败出口
                if (created.isEmpty()) return support.toolError(state, error);
                // 后面的写失败了：前面那几条已经在库里了。既不能说"没记上"把它们的影子抹掉，
                // 也不能一声不响——他照着回读去首页核对，看到的必须和话里说的一致。
                return support.memoHandoff(state, support.healthRecordedReply(created)
                        + "不过这一句里还有一条没写进去（" + error.getMessage() + "），麻烦您再说一遍。");
            }
            created.add(row);
        }
        return support.memoHandoff(state, support.healthRecordedReply(created));
    }

    /**
     * 卡上列着的全部数值：手上暂存的那一条，加上同一句话里报的其余几条。
     *
     * <p>缺一不可的判据与确认卡同一套（项目 / 数值 / 记录时间）——卡上写着哪一刻，写进库的
     * 就必须是哪一刻。这几样由 {@code ConfirmationService.payloadIntact} 在签发时就查过一遍，
     * 这里再查是防着"凭据作废之后又被执行"这条路（{@link #commit} 的先清后写顺序也在这个前提上）。
     */
    private static List<PendingRecord> pending(ConversationState state) {
        if (state.pendingRecordItem == null || state.pendingRecordItem.isBlank()
                || state.pendingRecordValueText == null || state.pendingRecordValueText.isBlank()
                || state.pendingRecordAt == null) {
            return List.of();
        }
        List<PendingRecord> all = new ArrayList<>();
        all.add(new PendingRecord(state.pendingRecordItem, state.pendingRecordValueNum,
                state.pendingRecordValueText, state.pendingRecordUnit,
                state.pendingRecordRaw == null ? state.pendingRecordValueText : state.pendingRecordRaw,
                state.pendingRecordAt, null));
        // 手上那条之后的部分：快照里带回来的那一份，原样接着写
        for (PendingRecord rest : state.pendingRecordRest == null ? List.<PendingRecord>of()
                : state.pendingRecordRest) {
            if (rest == null || rest.item() == null || rest.item().isBlank()
                    || rest.valueText() == null || rest.valueText().isBlank() || rest.at() == null) {
                continue;
            }
            all.add(rest);
        }
        return all;
    }

    /** 真往库里写一条：工具调用、参数留痕都在这里，{@link #write} 与 {@link #commit} 共用。 */
    private HealthRecordStore.RecordView create(ConversationState state, PendingRecord record,
                                                ConfirmationSupport support) {
        // 记录项带单位（“血压 100 mmHg”）：工具参数里单位单独一项，缺了记 0 会看起来像没单位
        Map<String, String> parameters = new HashMap<>();
        parameters.put("item", record.item());
        parameters.put("value", record.valueText());
        parameters.put("unit", record.unit() == null ? "" : record.unit());
        return support.callTool(state, "healthRecord.create", parameters,
                () -> healthRecordTool.create(state.id, state.userId, record.item(), record.valueNum(),
                        record.valueText(), record.unit(), record.raw(), record.at()));
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
            created = create(state, new PendingRecord(item, valueNum, valueText, unit, raw, at, null), support);
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
        // 同一句话里报的其余几条一并丢掉：它们和手上这条是同一次说话，留着就是留了半句话
        state.pendingRecordRest = List.of();
        state.pendingAction = state.recordReturnAction == null ? "CREATE" : state.recordReturnAction;
        state.recordReturnAction = null;
        if (state.recordReturnStage != null) {
            state.stage = state.recordReturnStage;
            state.recordReturnStage = null;
        }
    }
}
