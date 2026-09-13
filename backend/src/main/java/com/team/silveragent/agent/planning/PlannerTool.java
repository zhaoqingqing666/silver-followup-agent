package com.team.silveragent.agent.planning;

import java.util.List;

/**
 * 发送给规划模型的稳定工具契约；不包含 Java 实现和密钥。
 *
 * <p>{@code arguments} 是逐参数的声明（类型、必填、枚举、用途），{@code constraints} 是字段之间的
 * 组合约束。两者都原样序列化进提示词交给模型，同时被 {@code application.ToolContract} 用来判罚，
 * 所以「模型看到的」和「Java 检查的」是同一份声明，不会漂移。
 */
public record PlannerTool(
        String name,
        String description,
        String risk,
        List<ToolArgument> arguments,
        List<ToolConstraint> constraints
) {
    public PlannerTool {
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
    }

    /**
     * 兼容只给参数名的旧声明（单测和外部适配代码仍在用）。
     *
     * <p>这些参数一律当<b>可选字符串</b>：没有类型信息就不该拿类型去拦调用。
     * 生产路径的声明统一走 {@link ToolArgument} 的工厂方法。
     */
    public PlannerTool(String name, String description, String risk, List<String> arguments) {
        this(name, description, risk,
                arguments == null ? List.of() : arguments.stream().map(ToolArgument::optionalString).toList(),
                List.of());
    }
}
