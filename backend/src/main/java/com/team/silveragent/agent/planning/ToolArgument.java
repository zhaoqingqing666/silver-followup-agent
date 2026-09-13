package com.team.silveragent.agent.planning;

import java.util.List;

/**
 * 发送给模型的一条工具参数声明：除了名字，还给出<b>类型、是否必填、可选枚举和用途</b>。
 *
 * <p>只声明契约，不做校验。校验统一在 {@code application.ToolContract} 里做一次——声明和判罚
 * 分成两处，改一处忘另一处，就会出现「提示词说必填、代码其实不查」这种两套规则各说各话的局面。
 *
 * <p>{@code type} 是给模型看的自然语言类型标签，也是 Java 判罚的依据：
 * {@code date} 必须是 ISO 日期、{@code time} 必须是 HH:mm、{@code enum} 必须落在 {@code values} 里、
 * {@code boolean} 只认 true/false。{@code string} 不限定格式。
 */
public record ToolArgument(
        String name,
        String type,
        boolean required,
        List<String> values,
        String description
) {
    public static final String STRING = "string";
    public static final String DATE = "date";
    public static final String TIME = "time";
    public static final String ENUM = "enum";
    public static final String BOOLEAN = "boolean";

    public ToolArgument {
        values = values == null ? List.of() : List.copyOf(values);
        description = description == null ? "" : description;
    }

    /** 兼容旧声明：只给了参数名，没给类型。一律当可选字符串，校验时不会因为缺它而拦下调用。 */
    public static ToolArgument optionalString(String name) {
        return new ToolArgument(name, STRING, false, List.of(), "");
    }

    public static ToolArgument text(String name, boolean required, String description) {
        return new ToolArgument(name, STRING, required, List.of(), description);
    }

    public static ToolArgument date(String name, boolean required, String description) {
        return new ToolArgument(name, DATE, required, List.of(), description);
    }

    public static ToolArgument time(String name, boolean required, String description) {
        return new ToolArgument(name, TIME, required, List.of(), description);
    }

    public static ToolArgument yesNo(String name, boolean required, String description) {
        return new ToolArgument(name, BOOLEAN, required, List.of(), description);
    }

    public static ToolArgument enumeration(String name, boolean required, List<String> values,
                                           String description) {
        return new ToolArgument(name, ENUM, required, values, description);
    }

    public boolean isEnum() {
        return ENUM.equals(type);
    }
}
