package com.team.silveragent.application;

import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.model.DemoScenarioResponse;
import com.team.silveragent.infrastructure.persistence.RollingAppointmentSlotInitializer;
import com.team.silveragent.infrastructure.persistence.RollingUserScheduleInitializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 演示场景重置：把「四个场景怎么复现」从口头指导变成一次调用。
 *
 * <p>做三件事：清掉上一轮演示留下的可变数据、按「今天」重新生成号源与已有日程、
 * 开一个全新的会话。**这是破坏性操作**，只用于录屏和评审查验，不参与正常业务流程。
 *
 * <p>演示日期是滚动的（下周三体检、下周六和家人吃饭），所以重置必须连日程一起重排：
 * 否则隔一周再演示，冲突场景就再也造不出来了。
 */
@Service
public class DemoScenarioService {

    private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("M月d日");

    /**
     * 只清演示自己产生的可变数据；医院、科室、材料模板、出行路线、家属联系人这类目录数据不动。
     *
     * <p>比「预约相关的几张表」多出来的几张，都是本分支自己加的功能表：会话附件与识别结果
     * （多模态）、健康记录与健康备忘、跨会话长期记忆、照护端通知。不一起清的话，场景二会在
     * 场景一留下的习惯记忆上少问一句，看起来像流程漏了一步。
     */
    private static final List<String> RESET_TABLES = List.of(
            "appointment_materials", "reminders", "family_notifications", "appointments",
            "tool_call_logs", "conversation_messages", "conversation_sessions",
            "conversation_attachments", "vision_results",
            "care_notifications", "memos", "health_records", "user_memories");

    private final JdbcTemplate jdbc;
    private final FollowupAgentService agent;
    private final RollingAppointmentSlotInitializer slots;
    private final RollingUserScheduleInitializer schedules;

    public DemoScenarioService(JdbcTemplate jdbc, FollowupAgentService agent,
                               RollingAppointmentSlotInitializer slots,
                               RollingUserScheduleInitializer schedules) {
        this.jdbc = jdbc;
        this.agent = agent;
        this.slots = slots;
        this.schedules = schedules;
    }

    /**
     * 重置到指定场景的起始状态，并返回一个可以直接开始说话的会话。
     *
     * <p>返回的是**就诊人本人**的会话；家属/志愿者端要演示时，照护端页面仍走各自的建会话入口
     * （身份由后端按 {@code care_relations} 判定，重置接口不代替它造身份）。
     */
    public DemoScenarioResponse reset(String scenarioId) {
        DemoScenario scenario = DemoScenario.byId(scenarioId);
        RESET_TABLES.forEach(table -> jdbc.update("DELETE FROM " + table));
        // 上一轮演示约掉的号源要放开，否则重放同一场演示会碰到「号源已被占用」。
        jdbc.update("UPDATE appointment_slots SET available=TRUE");
        slots.seed();
        schedules.seed();
        // 内存里那份会话状态也要清：不清的话，旧会话 id 仍能说话（内存命中就不会回查数据库），
        // 而它对应的库记录已经被删了，等于在一个不存在的草稿上继续办事。
        agent.forgetAllSessions();
        AgentTurnResponse turn = agent.start();
        return new DemoScenarioResponse(scenario.id(), scenario.title(), steps(scenario),
                DemoScenario.ids(), turn);
    }

    /**
     * 步骤必须与真实流程一致，包括助手真正会问的那几句。
     *
     * <p>日期一律现算：写死「选 9月16日」的步骤过一周就失效了，而这正是本次重置要解决的问题。
     */
    private List<String> steps(DemoScenario scenario) {
        String checkup = checkupDate();
        String weekend = weekendDate();
        return switch (scenario) {
            case NORMAL -> List.of(
                    "选择医院：市第一医院",
                    "选择科室：心内科",
                    "日期选择下周三（" + checkup + "，助手日程里「社区体检」那天）",
                    "助手问「如果这一天没有号，您接受附近几天的其他时间吗？」时，点「可以换日期」",
                    "点「上午」或「下午」，再点具体时间：选上午 9 点那一格（不与体检冲突）",
                    "依次回答陪同、出行提醒、交通方式、家属通知",
                    "点「检查并确认」，核对确认卡后点「确认办理」");
            case NO_SLOT -> List.of(
                    "选择医院：市第一医院",
                    "选择科室：心内科",
                    "日期选择那个周末的日子（" + weekend + "）。滚动种子刻意不给周末排号源，所以这里必然没有号",
                    "观察助手的回复：「这一天暂无号源。您接受附近的其他日期吗？」",
                    "点「接受其他日期」（也可以直接说「那帮我看看附近几天」）",
                    "观察助手列出附近日期的真实号源：只往后看，不会把当天其它时段混进来说成「附近」");
            case CONFLICT -> List.of(
                    "选择医院：市第一医院",
                    "选择科室：心内科",
                    "日期选择下周三（" + checkup + "）",
                    "助手问是否接受附近日期时，点「可以换日期」",
                    "点「上午」，再点 10:30 那一格（与「社区体检」10:00-11:00 重叠；选上午 9 点则不会冲突，是不冲突的对照组）",
                    "依次回答陪同、出行提醒、交通方式、家属通知",
                    "最后一步答完，助手提示「这个时间与您的“社区体检… ”冲突」，并给出三个选项",
                    "点「仍保留这个时间」，确认卡上会多出一行「已知冲突：与“社区体检”… 您已选择保留」");
            case BOUNDARY -> List.of(
                    "直接输入「我血压有点高，要不要紧？」",
                    "观察回复下面多出一块独立的服务范围提示卡（不是普通聊天气泡）",
                    "如果这段会话正停在确认卡上，确认卡仍在、按钮照旧可用：越界回答不会打断正在办的事",
                    "再点「继续办理复诊」，确认原来的办理进度没有被清空");
        };
    }

    /** 体检那天（下周三）：冲突场景与正常办理都在这一天。 */
    private String checkupDate() {
        return RollingUserScheduleInitializer.checkupDate().format(DATE_LABEL);
    }

    /** 「和家人吃饭」那天（下周六）：周末没有号源，天然就是「指定日期无号」的现场。 */
    private String weekendDate() {
        return RollingUserScheduleInitializer.dinnerDate().format(DATE_LABEL);
    }
}
