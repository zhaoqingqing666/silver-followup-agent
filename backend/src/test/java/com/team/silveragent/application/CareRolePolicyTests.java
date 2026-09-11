package com.team.silveragent.application;

import com.team.silveragent.agent.AgentRole;
import com.team.silveragent.agent.planning.PlannerTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 按角色划分工具：可见性决定模型“看得见什么”，{@link ToolPolicy} 决定“能不能执行”。
 * 两道都要有——过滤掉的工具模型仍可能凭幻觉吐出来。
 */
class CareRolePolicyTests {
    private final ToolRegistry registry = new ToolRegistry();
    private final ToolPolicy policy = new ToolPolicy();

    private List<String> toolNames(AgentRole role) {
        return registry.plannerTools(role).stream().map(PlannerTool::name).toList();
    }

    @Test
    void caregiverOnlyToolsAreInvisibleToTheElder() {
        assertThat(toolNames(AgentRole.ELDER)).doesNotContain("care.timeline", "care.notifications");
        assertThat(toolNames(AgentRole.FAMILY)).contains("care.timeline", "care.notifications");
        assertThat(toolNames(AgentRole.VOLUNTEER)).contains("care.timeline", "care.notifications");
    }

    /** 查询类工具三个角色共用：家属/志愿者问的是长辈，老人问的是自己，工具本身不认识身份。 */
    @Test
    void sharedReadToolsStayVisibleToEveryRole() {
        for (AgentRole role : AgentRole.values()) {
            assertThat(toolNames(role)).as(role.name())
                    .contains("appointment.queryMine", "material.checklist", "careGuide.search");
        }
    }

    /** 模型即使吐出一个它看不见的工具名，也必须被拦在门外。 */
    @Test
    void policyDeniesAToolTheRoleCannotSee() {
        ToolRegistry.RegisteredTool timeline = registry.find("care.timeline").orElseThrow();
        assertThat(policy.evaluate(AgentRole.ELDER, timeline)).isEqualTo(ToolPolicy.Decision.DENY_ROLE);
        assertThat(policy.evaluate(AgentRole.FAMILY, timeline)).isEqualTo(ToolPolicy.Decision.ALLOW);
        assertThat(policy.evaluate(AgentRole.VOLUNTEER, timeline)).isEqualTo(ToolPolicy.Decision.ALLOW);
    }

    @Test
    void unknownToolIsStillDeniedForEveryRole() {
        for (AgentRole role : AgentRole.values()) {
            assertThat(policy.evaluate(role, null)).as(role.name())
                    .isEqualTo(ToolPolicy.Decision.DENY_UNKNOWN_TOOL);
        }
    }

    /** 关系表里的 role 列是身份的来源；认不出来的值不能默认成任何一种照护者身份。 */
    @Test
    void relationRoleParsing() {
        assertThat(AgentRole.fromRelationRole("FAMILY")).isEqualTo(AgentRole.FAMILY);
        assertThat(AgentRole.fromRelationRole(" volunteer ")).isEqualTo(AgentRole.VOLUNTEER);
        assertThat(AgentRole.fromRelationRole("neighbour")).isNull();
        assertThat(AgentRole.fromRelationRole(null)).isNull();
    }
}
