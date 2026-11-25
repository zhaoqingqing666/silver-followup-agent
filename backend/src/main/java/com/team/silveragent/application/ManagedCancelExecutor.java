package com.team.silveragent.application;

import com.team.silveragent.application.ConfirmationService.PendingOperation;
import com.team.silveragent.application.care.CareBookingService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.tool.AppointmentTool;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.team.silveragent.application.ConfirmationSupport.q;

/**
 * 「取消由家属/志愿者代约的那次复诊」这一类已确认操作的执行器。
 *
 * <p>它跟 {@link CancellationExecutor} <b>不是同一件事</b>，所以分开：那边取消的是老人自己的
 * 一批预约（整批原子、逐条判归属）；这里的对象是别人替他约下的那份安排，取消完还得回头通知
 * 当初的安排者——「谁安排的」这件事只有这条链路知道，把两者塞进一个执行器，早晚要在一个
 * 分支里把通知发给不该发的人。
 *
 * <p><b>要取消的是哪一份，在执行这一刻不再重找。</b>它来自凭据上冻结的那一条
 * （{@link PendingOperation#singleTarget()}），签发时写进去的就是卡片上写给老人看的那一条。
 * 这里以前是"按当前那份代约安排现查一遍"——那个口径本身没错（谁代约、还没过期、已确认），
 * 错在用它在执行时重新决定对象：签发之后别人又代约了一条更早的，同一张卡按下去取消的就是
 * 另一条，而界面从头到尾写的是原来那条。现在查询只用来取展示与通知要用的字段（谁安排的、
 * 约在哪天），<b>取消哪一条由冻结的目标说了算</b>。
 *
 * <p>归属、是否还存在、是否还能取消，全部交给 {@code appointmentTool.cancel} 那条 SQL 判
 * （{@code id + user_id + status='CONFIRMED'} 三条同时满足才改），执行器不另开一套口径。
 * 那条判定不过就<b>什么都不取消</b>——绝不去找一条"替代的当前预约"顶上。
 *
 * <p>取消成功与否都停在 {@code COMPLETED}：这条路不改办理草稿，也没有“被打断之后接着办”
 * 的语义——一次代约取消就是这件事的终点（真的取消了另有一个 {@code CANCELLED} 终态）。
 */
@Component
final class ManagedCancelExecutor {
    private final AppointmentTool appointmentTool;
    private final CareBookingService careBooking;
    private final AppointmentRecordStore records;

    ManagedCancelExecutor(AppointmentTool appointmentTool, CareBookingService careBooking,
                          AppointmentRecordStore records) {
        this.appointmentTool = appointmentTool;
        this.careBooking = careBooking;
        this.records = records;
    }

    /** 老人点了「确认取消预约」。 */
    AgentTurnResponse commit(ConversationState state, PendingOperation operation, ConfirmationSupport support) {
        state.pendingAction = "CREATE";
        String targetId = operation.singleTarget();
        if (targetId == null) {
            // 卡上没写清要取消哪一条（凭据本身有问题）：一个写操作都不做，也绝不去猜一条。
            state.stage = ConversationState.Stage.COMPLETED;
            return support.respondSimple(state, "这张取消卡上没有指定要取消的安排，我没有执行任何操作。",
                    List.of(q("查看事项", "OPEN_TASKS", "")));
        }
        // 展示字段按冻结的那条去取：查不到（不属于这个人、或已经不存在）不是"换一条"的理由，
        // 下面那条 SQL 会自己判不通过。
        AppointmentRecordStore.AppointmentView plan = records.allFor(state.userId).stream()
                .filter(row -> row.appointmentId().equals(targetId))
                .findFirst().orElse(null);
        try {
            // 归属校验在预约工具里（那条 SQL 带 user_id 与 status='CONFIRMED'）。这里不重查一遍，
            // 否则就多出第二套口径。
            appointmentTool.cancel(state.id, targetId, state.userId);
        } catch (RuntimeException error) {
            support.recordToolFailure(state, error);
            state.stage = ConversationState.Stage.COMPLETED;
            return support.respondSimple(state, "取消没有成功，原预约保留：" + error.getMessage(),
                    List.of(q("联系人工帮助", "CONTACT_HUMAN", ""), q("查看事项", "OPEN_TASKS", "")));
        }
        if (plan != null && plan.arrangedBy() != null) {
            careBooking.notifyCaregiver(plan.arrangedBy(), state.userId,
                    "取消通知：" + support.elderName(state.userId) + "已取消您代约的复诊："
                            + support.managedShort(plan) + "。预约与关联提醒已失效。",
                    "cancel");
        }
        state.stage = ConversationState.Stage.CANCELLED;
        return support.respondSimple(state, "已取消这次由" + (plan == null ? "家人" : plan.arrangedLabel())
                        + "约好的复诊，并已通知" + (plan == null ? "安排者" : plan.arrangedLabel()) + "。号源已释放。",
                List.of(q("新建办理", "NEW_BOOKING", ""), q("查看事项", "OPEN_TASKS", "")));
    }

    /** 老人点了「保留预约」：一个业务动作都不执行。 */
    AgentTurnResponse refuse(ConversationState state, PendingOperation operation, ConfirmationSupport support) {
        AppointmentRecordStore.AppointmentView plan = records.allFor(state.userId).stream()
                .filter(row -> row.appointmentId().equals(operation.singleTarget()))
                .findFirst().orElse(null);
        state.pendingAction = "CREATE";
        state.stage = ConversationState.Stage.COMPLETED;
        return support.respondSimple(state, "好的，没有取消，这次安排仍然保留。",
                plan == null ? List.of(q("办理复诊", "CONTINUE", ""))
                        : List.of(q("查看这次安排", "VIEW_MANAGED", ""),
                                q("临时改期或取消", "MANAGE_MANAGED", ""),
                                q("查看事项", "OPEN_TASKS", "")));
    }
}
