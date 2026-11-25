package com.team.silveragent.application;

/**
 * 一次工具执行到底算哪种结果。
 *
 * <p>加这一层是因为「工具返回了什么」和「这一轮该说什么」原先混在一起：调用成功、库里查不到、
 * 参数没给全、对象不唯一、要老人确认、状态真的改了、调用失败——七种情况最后都表现为一段中文，
 * 模型只能靠读中文猜该接着做什么。这里把判断从文字里挪出来，变成机器可判的枚举，
 * 随工具证据一起回给模型（{@code outcomeKind}），模型据此决定继续查还是改口问。
 *
 * <p>七个取值刻意互斥：一次工具执行只会落在其中一个上。
 */
final class ToolOutcome {

    private ToolOutcome() { }

    enum Kind {
        /** 工具真的返回了可用结果。 */
        SUCCESS,
        /** 工具执行成功，但库里没有符合条件的对象（没查到 ≠ 出错）。 */
        NO_RESULT,
        /** 模型这次调用缺参数或参数不合法，Java 不执行，需要补齐信息。 */
        MISSING_INFO,
        /** 参数合法但无法唯一定位对象，要老人在真实候选里选一个。 */
        NEEDS_CLARIFICATION,
        /** 已经生成确认卡，等老人明确确认后才写库。 */
        NEEDS_CONFIRMATION,
        /** 状态真的变了（预约成功、取消完成等），不是「准备做」。 */
        STATE_CHANGED,
        /** 调用没能执行：工具不存在、角色没权限，或执行时抛错。 */
        FAILURE
    }

    /** 一步工具执行的结果标签：给谁看的都在 {@code detail} 里，Kind 本身只表性质。 */
    record Step(Kind kind, String tool, String detail) {
        Step {
            detail = detail == null ? "" : detail;
        }
    }
}
