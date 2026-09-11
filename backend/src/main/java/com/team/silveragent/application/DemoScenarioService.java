package com.team.silveragent.application;

import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.model.DemoScenarioResponse;
import com.team.silveragent.infrastructure.persistence.RollingAppointmentSlotInitializer;
import com.team.silveragent.infrastructure.persistence.RollingUserScheduleInitializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 演示场景重置。
 *
 * <p>把“四场景怎么复现”从口头指导变成一次调用：清掉上一轮演示留下的可变数据，
 * 按“今天”重新生成号源与用户已有日程，然后开一个全新的会话。
 * 这是破坏性操作，只用于录屏和评审查验，不参与正常业务流程。
 */
@Service
public class DemoScenarioService {

    private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("M月d日");

    /** 只清演示产生的可变数据；医院、科室、材料模板等目录数据保持不动。 */
    private static final List<String> RESET_TABLES = List.of(
            "appointment_materials", "reminders", "family_notifications",
            "appointments", "tool_call_logs", "conversation_messages", "conversation_sessions");

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

    public DemoScenarioResponse reset(String scenarioId) {
        DemoScenario scenario = DemoScenario.byId(scenarioId);
        RESET_TABLES.forEach(table -> jdbc.update("DELETE FROM " + table));
        jdbc.update("UPDATE appointment_slots SET available=TRUE");
        slots.seed();
        schedules.seed();
        agent.forgetAllSessions();
        AgentTurnResponse turn = agent.start();
        return new DemoScenarioResponse(scenario.id(), scenario.title(), steps(scenario),
                DemoScenario.ids(), turn);
    }

    /**
     * 步骤必须与真实流程一致：日期确定后助手会先问“这一天没号能不能换日期”，
     * 之后才查号源；陪同、出行提醒、交通方式、家属通知四项答完才能进入确认。
     */
    private List<String> steps(DemoScenario scenario) {
        return switch (scenario) {
            case NORMAL -> List.of(
                    "选择医院：市第一医院",
                    "选择科室：心内科",
                    "日期选择任意工作日（号源由滚动种子生成，选最近的即可）",
                    "助手问“如果这一天没有号，您接受前后几天的其他时间吗？”时，点「可以换日期」",
                    "选择「上午」或「下午」，再确认推荐的具体时间",
                    "依次回答陪同、出行提醒、交通方式、家属通知",
                    "输入“开始办理”或点「开始办理」，核对确认卡后确认");
            case NO_SLOT -> List.of(
                    "选择任意医院和科室",
                    "日期选择一个周末（周六或周日，滚动种子不会生成周末号源）",
                    "助手问是否接受其他日期时，点「可以换日期」",
                    "观察助手返回“这一天暂无号源”，并给出附近日期的真实号源");
            case CONFLICT -> List.of(
                    "选择医院：市第一医院",
                    "选择科室：心内科",
                    "日期选择 " + checkupDate(),
                    "助手问是否接受其他日期时，点「可以换日期」",
                    "选择「上午」，再点「看看其他上午时间」，选择 10:30"
                            + "（与“社区体检”10:00-11:00 重叠；若接受 09:00 则不会冲突）",
                    "依次回答陪同、出行提醒、交通方式、家属通知",
                    "输入“开始办理”或点「开始办理」，助手会提示冲突",
                    "点「仍保留这个时间」，确认卡上会出现“已知冲突”一行");
            case BOUNDARY -> List.of(
                    "直接输入“我血压有点高，要不要紧？”",
                    "观察独立的服务范围提示卡片（不是普通聊天气泡）",
                    "再点「继续办理」，确认原来的办理进度没有被清空");
        };
    }

    private String checkupDate() {
        return schedules.nextCommunityCheckupDate().format(DATE_LABEL);
    }
}
