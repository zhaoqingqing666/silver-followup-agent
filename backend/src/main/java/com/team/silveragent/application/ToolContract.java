package com.team.silveragent.application;

import com.team.silveragent.agent.planning.PlannerToolCall;
import com.team.silveragent.agent.planning.ToolArgument;
import com.team.silveragent.agent.planning.ToolConstraint;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 工具参数的唯一判罚处。类型、必填、枚举、字段组合四类检查都在这里，别处不再各写一套。
 *
 * <p>判罚只回答「这次调用能不能执行」，不决定「执行不了时改说什么」——那是 {@link ToolOutcome}
 * 和调用方的事：缺少信息要补齐、对象不唯一要澄清，是两种不同的下一步。
 *
 * <p>两条刻意的宽松：
 * <ul>
 *   <li><b>未声明的参数一律忽略</b>，不判罚。模型偶尔会多塞一个 {@code appointmentId}，
 *       而 {@code appointmentId} 根本不在 {@code requestConfirmation} 的声明里，所以它读不到、
 *       也传不下去——这里忽略就是最安全的处理，没必要因此把一次范围完全正确的取消拦下来。</li>
 *   <li><b>空串等于没给</b>。模型把可选参数写成 {@code ""} 是常见事，不该被当成非法取值。</li>
 * </ul>
 *
 * <p>日期、时刻允许常见的宽松写法（{@code 2026-9-12}、{@code 9:00}），解析成本地时间对象；
 * 解析不了才算非法。枚举统一按大写比较并归一化，模型写 {@code date_range} 也认。
 */
final class ToolContract {
    /** {@code 9:00} 这种没补零的写法，模型很爱用。 */
    private static final DateTimeFormatter FLEXIBLE_TIME = DateTimeFormatter.ofPattern("H:mm");
    /**
     * {@code 2026-9-12} 这种没补零的写法，模型很爱用。
     *
     * <p>宽松的只是<b>写法</b>，日历本身仍然严格：{@code ResolverStyle.STRICT} 配 {@code uuuu}，
     * 2月30日、平年2月29日一律解析失败。默认的 SMART 会把它们悄悄挪到 2月28日——那是个
     * <b>真实存在、但不是他说</b>的日期，接下来取消的、改期的都是它，而且一路无感。
     * 少补零可以接受，多出来的那一天不行。
     */
    private static final DateTimeFormatter FLEXIBLE_DATE =
            DateTimeFormatter.ofPattern("uuuu-M-d").withResolverStyle(ResolverStyle.STRICT);

    private ToolContract() { }

    enum Code {
        UNKNOWN_TOOL,
        MISSING_ARGUMENT,
        INVALID_FORMAT,
        INVALID_ENUM,
        CONSTRAINT_VIOLATED
    }

    /**
     * 一条判罚。
     *
     * @param code      哪一类问题
     * @param tool      被判的工具名
     * @param argument  出问题的参数名；组合约束失败时列出相关字段
     * @param detail    中文说明，进日志和工具证据，不直接说给老人听
     */
    record Rejection(Code code, String tool, String argument, String detail) {
        /** 判罚归到哪一种工具结果：不存在的工具算失败，参数问题算缺少信息。 */
        ToolOutcome.Kind kind() {
            return switch (code) {
                case UNKNOWN_TOOL -> ToolOutcome.Kind.FAILURE;
                case MISSING_ARGUMENT, INVALID_FORMAT, INVALID_ENUM, CONSTRAINT_VIOLATED ->
                        ToolOutcome.Kind.MISSING_INFO;
            };
        }
    }

    /** 通过返回空；不通过返回第一条判罚。 */
    static Optional<Rejection> check(ToolRegistry.RegisteredTool tool, PlannerToolCall call) {
        if (tool == null) {
            return Optional.of(new Rejection(Code.UNKNOWN_TOOL, call == null ? null : call.toolName(), null,
                    "这个工具没有注册，不能执行"));
        }
        if (call == null) {
            return Optional.of(new Rejection(Code.UNKNOWN_TOOL, tool.definition().name(), null,
                    "没有给出工具调用"));
        }
        Map<String, String> arguments = normalize(tool.definition().arguments(), call.arguments());
        for (ToolArgument declared : tool.definition().arguments()) {
            String value = arguments.get(declared.name());
            if (value == null) {
                if (declared.required()) {
                    return Optional.of(new Rejection(Code.MISSING_ARGUMENT, tool.definition().name(),
                            declared.name(), "缺少必填参数 " + declared.name() + "（"
                            + declared.type() + "）：" + declared.description()));
                }
                continue;
            }
            Optional<Rejection> invalid = typeCheck(tool.definition().name(), declared, value);
            if (invalid.isPresent()) return invalid;
        }
        for (ToolConstraint constraint : tool.definition().constraints()) {
            if (!constraint.appliesTo(arguments)) continue;
            boolean satisfied = ToolConstraint.REQUIRES_ALL.equals(constraint.kind())
                    ? constraint.fields().stream().allMatch(arguments::containsKey)
                    : constraint.fields().stream().anyMatch(arguments::containsKey);
            if (!satisfied) {
                return Optional.of(new Rejection(Code.CONSTRAINT_VIOLATED, tool.definition().name(),
                        String.join(",", constraint.fields()),
                        constraint.description() + "（当前 " + constraint.field() + "="
                                + arguments.get(constraint.field()) + "）"));
            }
        }
        return Optional.empty();
    }

    /**
     * 把模型给的参数归一化成 Java 读得懂的那份：只保留声明过的字段、去掉空串、
     * 补齐枚举大小写和日期写法。取消链路就靠它读范围——未声明的字段在这里就已经没了。
     */
    static Map<String, String> normalize(List<ToolArgument> declared, Map<String, String> raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null) return result;
        for (ToolArgument argument : declared) {
            String value = raw.get(argument.name());
            if (value == null) continue;
            String trimmed = value.trim();
            if (trimmed.isEmpty()) continue;
            result.put(argument.name(), argument.isEnum() ? trimmed.toUpperCase(Locale.ROOT) : trimmed);
        }
        return result;
    }

    private static Optional<Rejection> typeCheck(String tool, ToolArgument declared, String value) {
        String name = declared.name();
        switch (declared.type()) {
            case ToolArgument.DATE -> {
                if (parseDate(value) == null) {
                    return Optional.of(new Rejection(Code.INVALID_FORMAT, tool, name,
                            name + " 不是合法日期，要 ISO 写法（如 2026-09-12），收到的是 " + value));
                }
            }
            case ToolArgument.TIME -> {
                if (parseTime(value) == null) {
                    return Optional.of(new Rejection(Code.INVALID_FORMAT, tool, name,
                            name + " 不是合法时刻，要 HH:mm 写法（如 09:00），收到的是 " + value));
                }
            }
            case ToolArgument.BOOLEAN -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    return Optional.of(new Rejection(Code.INVALID_FORMAT, tool, name,
                            name + " 只能是 true 或 false，收到的是 " + value));
                }
            }
            case ToolArgument.ENUM -> {
                String upper = value.toUpperCase(Locale.ROOT);
                boolean known = declared.values().stream().anyMatch(item -> item.equalsIgnoreCase(upper));
                if (!known) {
                    return Optional.of(new Rejection(Code.INVALID_ENUM, tool, name,
                            name + " 取值 " + value + " 不在允许范围内（"
                                    + String.join("|", declared.values()) + "）"));
                }
            }
            default -> { }
        }
        return Optional.empty();
    }

    static LocalDate parseDate(String value) {
        if (value == null) return null;
        String text = value.trim();
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(text, FLEXIBLE_DATE);
            } catch (DateTimeParseException error) {
                return null;
            }
        }
    }

    static LocalTime parseTime(String value) {
        if (value == null) return null;
        String text = value.trim();
        try {
            return LocalTime.parse(text);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalTime.parse(text, FLEXIBLE_TIME);
            } catch (DateTimeParseException error) {
                return null;
            }
        }
    }
}
