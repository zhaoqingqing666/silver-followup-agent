package com.team.silveragent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.silveragent.agent.AgentRole;
import com.team.silveragent.agent.planning.PlannerToolCall;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具契约的唯一判罚处的行为。
 *
 * <p>这些断言盯着的是「模型给的参数不合法时，这次调用到底执不执行」：契约检查不通过，
 * 调用必须被拦下（并且归到「缺少信息」，由模型追问），不能带着半个参数被放行到写操作里。
 */
class ToolContractTests {
    private final ToolRegistry registry = new ToolRegistry();
    private final ObjectMapper json = new ObjectMapper();

    private ToolRegistry.RegisteredTool tool(String name) {
        return registry.find(name).orElseThrow();
    }

    private Optional<ToolContract.Rejection> check(String name, Map<String, String> arguments) {
        return ToolContract.check(tool(name), new PlannerToolCall(name, arguments));
    }

    @Test
    void missingRequiredArgumentIsRejectedAsMissingInfo() {
        Optional<ToolContract.Rejection> rejection = check("interaction.requestConfirmation", Map.of());
        assertThat(rejection).isPresent();
        assertThat(rejection.get().code()).isEqualTo(ToolContract.Code.MISSING_ARGUMENT);
        assertThat(rejection.get().argument()).isEqualTo("scope");
        assertThat(rejection.get().kind()).isEqualTo(ToolOutcome.Kind.MISSING_INFO);
    }

    @Test
    void enumValueOutsideTheDeclaredListIsRejected() {
        Optional<ToolContract.Rejection> rejection = check("interaction.requestConfirmation",
                Map.of("scope", "EVERYTHING"));
        assertThat(rejection).isPresent();
        assertThat(rejection.get().code()).isEqualTo(ToolContract.Code.INVALID_ENUM);
        assertThat(rejection.get().kind()).isEqualTo(ToolOutcome.Kind.MISSING_INFO);
    }

    @Test
    void enumValueIsAcceptedCaseInsensitively() {
        // 模型写 date_range 也认，并且归一化成大写交给下游。
        assertThat(check("interaction.requestConfirmation",
                Map.of("scope", "date_range", "date", "2026-09-12", "direction", "before"))).isEmpty();
        Map<String, String> normalized = ToolContract.normalize(
                tool("interaction.requestConfirmation").definition().arguments(),
                Map.of("scope", "date_range", "direction", "before"));
        assertThat(normalized).containsEntry("scope", "DATE_RANGE").containsEntry("direction", "BEFORE");
    }

    @Test
    void malformedDateIsRejected() {
        Optional<ToolContract.Rejection> rejection = check("interaction.requestConfirmation",
                Map.of("scope", "DATE_RANGE", "date", "下周三", "direction", "BEFORE"));
        assertThat(rejection).isPresent();
        assertThat(rejection.get().code()).isEqualTo(ToolContract.Code.INVALID_FORMAT);
        assertThat(rejection.get().argument()).isEqualTo("date");
        assertThat(rejection.get().kind()).isEqualTo(ToolOutcome.Kind.MISSING_INFO);
    }

    @Test
    void malformedTimeIsRejected() {
        Optional<ToolContract.Rejection> rejection = check("interaction.requestConfirmation",
                Map.of("scope", "SINGLE_FILTER", "time", "上午九点"));
        assertThat(rejection).isPresent();
        assertThat(rejection.get().code()).isEqualTo(ToolContract.Code.INVALID_FORMAT);
        assertThat(rejection.get().argument()).isEqualTo("time");
    }

    @Test
    void dateRangeWithoutDirectionViolatesTheFieldCombination() {
        Optional<ToolContract.Rejection> rejection = check("interaction.requestConfirmation",
                Map.of("scope", "DATE_RANGE", "date", "2026-09-12"));
        assertThat(rejection).isPresent();
        assertThat(rejection.get().code()).isEqualTo(ToolContract.Code.CONSTRAINT_VIOLATED);
        assertThat(rejection.get().detail()).contains("DATE_RANGE");
        assertThat(rejection.get().kind()).isEqualTo(ToolOutcome.Kind.MISSING_INFO);
    }

    @Test
    void singleFilterWithoutAnyConditionViolatesTheFieldCombination() {
        Optional<ToolContract.Rejection> rejection = check("interaction.requestConfirmation",
                Map.of("scope", "SINGLE_FILTER"));
        assertThat(rejection).isPresent();
        assertThat(rejection.get().code()).isEqualTo(ToolContract.Code.CONSTRAINT_VIOLATED);
    }

    @Test
    void legalScopesPass() {
        assertThat(check("interaction.requestConfirmation", Map.of("scope", "ALL"))).isEmpty();
        assertThat(check("interaction.requestConfirmation", Map.of("scope", "AMBIGUOUS"))).isEmpty();
        assertThat(check("interaction.requestConfirmation",
                Map.of("scope", "DATE_RANGE", "date", "2026-09-12", "direction", "ON_OR_BEFORE"))).isEmpty();
        assertThat(check("interaction.requestConfirmation",
                Map.of("scope", "SINGLE_FILTER", "period", "MORNING"))).isEmpty();
    }

    @Test
    void undeclaredAppointmentIdIsIgnoredNotPassedOn() {
        // 模型不得指定取消哪一条：appointmentId 不在声明里，所以既不被判罚，也读不出来。
        Map<String, String> arguments = new LinkedHashMap<>();
        arguments.put("scope", "ALL");
        arguments.put("appointmentId", "appt-002");
        assertThat(check("interaction.requestConfirmation", arguments)).isEmpty();
        assertThat(ToolContract.normalize(tool("interaction.requestConfirmation").definition().arguments(),
                arguments)).doesNotContainKey("appointmentId");
    }

    @Test
    void emptyStringCountsAsNotGiven() {
        // 可选参数写成空串是模型常见行为，不该被当成非法取值，也不该让它满足组合约束。
        Map<String, String> arguments = new LinkedHashMap<>();
        arguments.put("scope", "DATE_RANGE");
        arguments.put("date", "");
        arguments.put("direction", "");
        Optional<ToolContract.Rejection> rejection = check("interaction.requestConfirmation", arguments);
        assertThat(rejection).isPresent();
        assertThat(rejection.get().code()).isEqualTo(ToolContract.Code.CONSTRAINT_VIOLATED);
    }

    @Test
    void unknownToolIsAFailureNotMissingInfo() {
        Optional<ToolContract.Rejection> rejection = ToolContract.check(null,
                new PlannerToolCall("appointment.deleteEverything", Map.of()));
        assertThat(rejection).isPresent();
        assertThat(rejection.get().code()).isEqualTo(ToolContract.Code.UNKNOWN_TOOL);
        assertThat(rejection.get().kind()).isEqualTo(ToolOutcome.Kind.FAILURE);
    }

    @Test
    void missingRequiredKeywordIsRejectedOnReadTools() {
        Optional<ToolContract.Rejection> rejection = check("hospital.search", Map.of());
        assertThat(rejection).isPresent();
        assertThat(rejection.get().code()).isEqualTo(ToolContract.Code.MISSING_ARGUMENT);
        assertThat(rejection.get().argument()).isEqualTo("keyword");
    }

    @Test
    void slotQueryWithoutDateIsStillLegal() {
        // 日期缺失必须由 Java 自己提示先选日期，不能在这里判成缺参数。
        assertThat(check("appointment.querySlots",
                Map.of("hospital", "第一医院", "department", "心内科"))).isEmpty();
    }

    @Test
    void askClarificationRequiresTheCandidateTool() {
        Optional<ToolContract.Rejection> rejection = check("interaction.askClarification",
                Map.of("question", "您说的是哪一条？"));
        assertThat(rejection).isPresent();
        assertThat(rejection.get().argument()).isEqualTo("candidateTool");

        assertThat(check("interaction.askClarification",
                Map.of("candidateTool", "appointment.queryMine"))).isEmpty();
        // question 是可选的：模型没写好多一句话，不该让这次澄清整轮失败。
        assertThat(check("interaction.askClarification",
                Map.of("candidateTool", "appointment.queryMine", "question", ""))).isEmpty();
    }

    @Test
    void dateAndTimeParsersAcceptBothStrictAndLooseWriting() {
        assertThat(ToolContract.parseDate("2026-09-12")).isEqualTo(LocalDate.of(2026, 9, 12));
        assertThat(ToolContract.parseDate("2026-9-12")).isEqualTo(LocalDate.of(2026, 9, 12));
        assertThat(ToolContract.parseDate("九月十二")).isNull();
        assertThat(ToolContract.parseTime("09:00")).isEqualTo(LocalTime.of(9, 0));
        assertThat(ToolContract.parseTime("9:00")).isEqualTo(LocalTime.of(9, 0));
        assertThat(ToolContract.parseTime("上午九点")).isNull();
    }

    /**
     * 宽松的只是「写法」，日历本身必须严格：2月30日这种日历上不存在的日子，绝不能顺着
     * 往前后挪一天变成「2月28日」——那是个真实存在、但不是他说出口的日期，接下来取消或改期的
     * 都是它，而整个过程一声不响。
     */
    @Test
    void februaryThirtiethIsRejectedInsteadOfBeingShifted() {
        assertThat(ToolContract.parseDate("2026-2-30")).isNull();
        assertThat(ToolContract.parseDate("2026-02-30")).isNull();
        Optional<ToolContract.Rejection> rejection = check("interaction.requestConfirmation",
                Map.of("scope", "DATE_RANGE", "date", "2026-2-30", "direction", "BEFORE"));
        assertThat(rejection).isPresent();
        assertThat(rejection.get().code()).isEqualTo(ToolContract.Code.INVALID_FORMAT);
        assertThat(rejection.get().argument()).isEqualTo("date");
    }

    /** 平年没有 2月29日；闰年有。同一句写法，两年必须给出不同答案。 */
    @Test
    void februaryTwentyNinthIsRejectedInCommonYearsAndAcceptedInLeapYears() {
        assertThat(ToolContract.parseDate("2026-2-29")).isNull();
        assertThat(ToolContract.parseDate("2026-02-29")).isNull();
        assertThat(ToolContract.parseDate("2028-2-29")).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(ToolContract.parseDate("2028-02-29")).isEqualTo(LocalDate.of(2028, 2, 29));
    }

    /** 同样不存在的还有 4月31日、13月、0日——都是「写法没问题、日历上没有」。 */
    @Test
    void otherImpossibleCalendarDatesAreRejectedToo() {
        assertThat(ToolContract.parseDate("2026-4-31")).isNull();
        assertThat(ToolContract.parseDate("2026-13-1")).isNull();
        assertThat(ToolContract.parseDate("2026-9-0")).isNull();
        assertThat(ToolContract.parseDate("2026-9-31")).isNull();
    }

    @Test
    void modelFacingToolJsonCarriesTypeRequiredEnumAndConstraints() throws Exception {
        // 模型看到的那份 JSON 必须就是 Java 判罚依据的那份：类型、必填、枚举、组合约束都要在里面，
        // 少一样就等于「提示词没告诉模型，代码却拿它拦人」。
        JsonNode tools = json.readTree(json.writeValueAsString(registry.plannerTools(AgentRole.ELDER)));
        JsonNode cancel = null;
        JsonNode clarify = null;
        for (JsonNode item : tools) {
            if ("interaction.requestConfirmation".equals(item.path("name").asText())) cancel = item;
            if ("interaction.askClarification".equals(item.path("name").asText())) clarify = item;
        }
        assertThat(cancel).isNotNull();
        assertThat(clarify).isNotNull();
        assertThat(clarify.path("risk").asText()).isEqualTo("CLARIFICATION_ONLY");

        JsonNode scope = null;
        for (JsonNode argument : cancel.path("arguments")) {
            if ("scope".equals(argument.path("name").asText())) scope = argument;
        }
        assertThat(scope).isNotNull();
        assertThat(scope.path("type").asText()).isEqualTo("enum");
        assertThat(scope.path("required").asBoolean()).isTrue();
        assertThat(scope.path("values").toString()).contains("DATE_RANGE");

        assertThat(cancel.path("constraints")).isNotEmpty();
        assertThat(cancel.path("constraints").toString()).contains("REQUIRES_ALL");

        // appointmentId 绝不能出现在模型看到的声明里。
        assertThat(cancel.path("arguments").toString()).doesNotContain("appointmentId");
    }

    @Test
    void everyRegisteredToolDeclaresUniqueArgumentNames() {
        for (var registered : registry.plannerTools().stream()
                .map(item -> registry.find(item.name()).orElseThrow()).toList()) {
            List<String> names = registered.definition().arguments().stream()
                    .map(argument -> argument.name()).toList();
            assertThat(names).as(registered.definition().name()).doesNotHaveDuplicates();
        }
    }
}
