package com.team.silveragent.domain.tool;

import com.team.silveragent.domain.model.ToolModels.Slot;
import java.time.LocalDate;
import java.util.List;

public interface AppointmentTool {
    List<Slot> queryAvailableSlots(String conversationId, String hospitalId, String department, LocalDate date);

    /**
     * 这一天仍可预约的全部号源，<b>不看时刻</b>——当天已经过去的时段也在里面。
     *
     * <p>它存在的唯一理由是**把「已经过了」和「没有号」分开说**。{@link #queryAvailableSlots}
     * 只回未来时段，于是「上午一个都没有」既可能是时间走掉了、也可能是当天上午本来就没排班；
     * 两者对老人是两句完全不同的话（「今天上午的号已经过了」vs「上午没有排班」），
     * 而光看查询结果分不出来。
     *
     * <p>这不是给模型用的工具（未登记进工具清单）：模型要的是「能不能约」，
     * 而这里回的是「为什么不能约」，属于 Java 侧的话术依据。
     */
    List<Slot> queryDaySlots(String conversationId, String hospitalId, String department, LocalDate date);

    List<Slot> queryUpcomingSlots(String conversationId, String hospitalId, String department, LocalDate from, LocalDate to);
    List<Slot> queryAlternatives(String conversationId, String hospitalId, String department, LocalDate date);
    /**
     * 提交一条预约，返回这条预约的 id。
     *
     * <p>幂等键是「会话 + 就诊人 + 号源」：只有**同一次提交**重复落下来才会返回已存在的那条，
     * 换了号源（哪怕是同一天的另一个时段）必须真的新建一条。**不要**用「这个会话里已有预约」
     * 当重复条件——那会把同一段会话里的第二次办理静默吞掉（见
     * {@code AppointmentSubmitIdempotencyTests}）。
     *
     * <p>「患者 + 日期 + 时段」的冲突判定不在这里，在 {@code FollowupAgentService.checkDuplicate}。
     */
    String submit(String conversationId, String slotId, String userId);
    String reschedule(String conversationId, String appointmentId, String slotId, String userId);
    String cancel(String conversationId, String appointmentId, String userId);
    List<String> cancelAll(String conversationId, List<String> appointmentIds, String userId);
}
