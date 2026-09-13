package com.team.silveragent.agent.planning;

import java.util.List;

/**
 * 一条字段组合约束：单个参数各自合法，但组合起来说不通时由它兜住。
 *
 * <p>典型例子是取消范围：{@code scope=DATE_RANGE} 光有 scope 没有 {@code date} 和
 * {@code direction}，筛不出任何范围；{@code scope=SINGLE_FILTER} 六个筛选条件全空，等于没说。
 * 这种「缺的是一整组」的情形，靠逐参数必填表达不了，所以单独声明。
 *
 * <p>语义：{@code field} 为空时约束<b>无条件</b>成立；否则只有本次调用的 {@code field} 取值落在
 * {@code fieldValues} 里时才生效。
 * <ul>
 *   <li>{@link #REQUIRES_ALL}：{@code fields} 里的每一个都必须给出。</li>
 *   <li>{@link #AT_LEAST_ONE}：{@code fields} 里至少给一个。</li>
 * </ul>
 */
public record ToolConstraint(
        String kind,
        String field,
        List<String> fieldValues,
        List<String> fields,
        String description
) {
    public static final String REQUIRES_ALL = "REQUIRES_ALL";
    public static final String AT_LEAST_ONE = "AT_LEAST_ONE";

    public ToolConstraint {
        fieldValues = fieldValues == null ? List.of() : List.copyOf(fieldValues);
        fields = fields == null ? List.of() : List.copyOf(fields);
        description = description == null ? "" : description;
    }

    /** 当 {@code whenField} 取值为 {@code whenValues} 之一时，{@code fields} 必须全部给出。 */
    public static ToolConstraint requiresAll(String whenField, List<String> whenValues,
                                             List<String> fields, String description) {
        return new ToolConstraint(REQUIRES_ALL, whenField, whenValues, fields, description);
    }

    /** 当 {@code whenField} 取值为 {@code whenValues} 之一时，{@code fields} 至少给出一个。 */
    public static ToolConstraint atLeastOne(String whenField, List<String> whenValues,
                                            List<String> fields, String description) {
        return new ToolConstraint(AT_LEAST_ONE, whenField, whenValues, fields, description);
    }

    /** 本条约束这次要不要判：无条件约束恒生效，条件约束只在条件字段命中时生效。 */
    public boolean appliesTo(java.util.Map<String, String> arguments) {
        if (field == null || field.isBlank()) return true;
        String actual = arguments.get(field);
        return actual != null && fieldValues.contains(actual);
    }
}
