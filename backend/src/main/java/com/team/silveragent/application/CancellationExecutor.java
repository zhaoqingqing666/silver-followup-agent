package com.team.silveragent.application;

import com.team.silveragent.domain.tool.AppointmentTool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 取消这一族的业务执行器：凭据换成操作之后，真正落库的那一步在这里。
 *
 * <p>它只做三件事，顺序不能换：<b>取目标 → 执行 → 复位待确认状态</b>。
 * 「老人到底看到的是哪几条」由 {@link ConfirmationService.PendingOperation#cancellationTargets()}
 * 决定（签发时就定下了），这一层不重新猜范围——重新按当前会话状态猜一遍，就会在卡和库里
 * 不一致的时候默默取消掉卡片上没写的那几条。
 *
 * <p><b>归属校验与批量原子刻意不在这里重写</b>，而在
 * {@code MockAppointmentTool.cancelAll}：那条 SQL 本身就带 {@code user_id} 与
 * {@code status='CONFIRMED'} 两个条件，并且先整批查一遍、再在同一个事务里逐条取消——
 * 任一条不是他的、或者已经不是"已确认"了，整批一条都不会动。在这里再查一遍只会多出第二套
 * 口径：两处对"还能不能取消"的判断一旦哪天不一致，赢的是先执行的那一处，而它没有事务。
 * 所以本类的职责边界是「什么时候轮到我执行」，不是「这一条到底归不归他」。
 *
 * <p>代他人办理（{@code caregiving()}）不走这里：那条路有自己的归属与协同通知，见
 * {@code FollowupAgentService#executeCaregiverBooking}。
 */
@Component
final class CancellationExecutor {
    /**
     * 一次取消执行的事实。编排层拿它决定怎么说这句话，而不是再去读一遍状态——
     * 状态已经被执行过程改过了。
     *
     * @param targetIds         真正提交取消的那批预约
     * @param resumedInterrupted 取消完是回到老人被打断的那件事里，还是就此结束
     */
    record Result(List<String> targetIds, boolean resumedInterrupted) { }

    private final AppointmentTool appointmentTool;

    CancellationExecutor(AppointmentTool appointmentTool) {
        this.appointmentTool = appointmentTool;
    }

    /**
     * 执行一次已确认的取消。
     *
     * <p>目标为空是**执行不了**，不是"不用执行"：一张说不出取消哪条的卡不该被当成空操作放过去，
     * 所以照样抛出去，由编排层转成工具错误——和以前同一个说法、同一条出口。
     */
    Result cancel(ConversationState state, List<String> targetIds) {
        List<String> targets = targetIds == null ? List.of() : targetIds.stream().distinct().toList();
        if (targets.isEmpty()) throw new IllegalStateException("当前确认卡没有可取消的预约");
        appointmentTool.cancelAll(state.id, targets, state.userId);
        clearPendingTargets(state);
        // 取消的就是本次会话正在办的那一条时，会话不能再拿着一个已经不存在的预约号往下走。
        if (state.appointmentId != null && targets.contains(state.appointmentId)) state.appointmentId = null;
        return new Result(targets, settleStage(state, ConversationState.Stage.CANCELLED,
                ConversationState.TaskStatus.CANCELLED));
    }

    /**
     * 老人点了"保留预约"：什么都不执行，只把这份待确认动作收干净。
     *
     * <p>刻意不动 {@code appointmentId}——取消本来就没发生，那个预约该留着的都留着。
     */
    Result abandon(ConversationState state) {
        clearPendingTargets(state);
        return new Result(List.of(), settleStage(state, ConversationState.Stage.COMPLETED,
                ConversationState.TaskStatus.COMPLETED));
    }

    private void clearPendingTargets(ConversationState state) {
        state.pendingAction = "CREATE";
        state.pendingAppointmentId = null;
        state.pendingAppointmentIds = List.of();
    }

    /**
     * 执行完/放弃后停在哪一页。
     *
     * <p>办理被打断过就退回去接着办（这时的任务状态归各个出口自己收尾，和以前一致），
     * 否则这件事本身就算结束了——取消以 {@code CANCELLED} 收场，保留以 {@code COMPLETED}
     * 收场，两者的终态不同，所以由调用方给，不在这里猜。
     */
    private boolean settleStage(ConversationState state, ConversationState.Stage finishedStage,
                                ConversationState.TaskStatus finishedStatus) {
        if (state.interruptedStage != null) {
            state.stage = state.interruptedStage;
            return true;
        }
        state.stage = finishedStage;
        state.taskStatus = finishedStatus;
        return false;
    }
}
