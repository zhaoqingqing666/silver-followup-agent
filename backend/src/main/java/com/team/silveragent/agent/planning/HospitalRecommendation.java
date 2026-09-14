package com.team.silveragent.agent.planning;

import java.util.List;

/**
 * 模型推荐的一家医院：<b>结构化</b>的一条，而不是一句话。
 *
 * <p>它和 {@link PlannerToolCall} 一样属于模型契约，不是业务模型：字段名、含义、以及「Java 会拿它
 * 逐条校验」这件事，都写在系统提示词里给模型看。
 *
 * <p>为什么要结构化，而不是让模型直接写一段推荐文字：那段文字没人能校验——Java 不解析中文，
 * 认不出里面点了哪几家医院，「老人明确排除的那家又回来了」就挡不住。拆成
 * {@code hospitalId} 之后，推荐对象是不是本轮真实候选、有没有被排除、理由有没有本轮工具证据，
 * 每一项都变成机器可判的。
 *
 * <p>{@code evidenceRefs} 是这条理由引用的<b>本轮真实调用过的工具名</b>（不是自然语言出处）。
 * Java 会去 {@code tool_call_logs} 里核对：这个工具这一轮真的跑过、真的返回了结果、而且（对号源、
 * 路线这类关于某一家医院的证据）就是关于这家医院的。所以给「证据」这件事不是一句承诺，
 * 是一条可以被证伪的引用。
 */
public record HospitalRecommendation(String hospitalId, String reason, List<String> evidenceRefs) {
    public HospitalRecommendation {
        hospitalId = hospitalId == null ? "" : hospitalId.trim();
        reason = reason == null ? "" : reason.trim();
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }
}
