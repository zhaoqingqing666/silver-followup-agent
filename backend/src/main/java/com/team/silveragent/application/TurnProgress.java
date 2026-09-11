package com.team.silveragent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.silveragent.agent.planning.PlannerToolCall;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 一轮办理的实时进度。
 *
 * <p>存在的理由是评审要看「智能体真实生成参数、真的调了工具、真的拿到了结果」。
 * 只靠回合结束后返回的 {@code toolTraces} 是看不出过程的——那已经是结论了。
 * 所以这里按会话记一串带序号的事件，前端在一轮进行中按序号往后拉，把过程画出来。
 *
 * <p>几个刻意的取舍：
 * <ul>
 *   <li><b>只在内存里</b>。进度是「此刻正在发生什么」，进程重启后本来就无从谈起；
 *       落库反而会把隐私数据（工具参数）多留一份。真正的调用记录仍然由
 *       {@code tool_call_logs} 负责，两者互不替代。</li>
 *   <li><b>有界</b>。每个会话只留最近 {@link #MAX_EVENTS} 条，会话数也有上限，
 *       否则一个长会话或一次压测就能把内存吃光。</li>
 *   <li><b>参数要脱敏</b>。这条数据会经 HTTP 回到前端、显示给评审看，
 *       手机号、图片 base64 一律不能原样出去。脱敏只在读取时做一次，
 *       内存里保留原始值，方便将来排查。</li>
 * </ul>
 */
@Component
public class TurnProgress {

    /** 一个会话最多留多少条事件。一轮最多也就十几步，40 足够覆盖还留有余量。 */
    private static final int MAX_EVENTS = 40;
    /** 最多同时跟踪多少个会话；超了直接清空重来，演示环境不需要更精细的淘汰策略。 */
    private static final int MAX_CONVERSATIONS = 500;
    /** 这么久没有任何新事件就不再认为「正在进行中」，兜住没走到 end 的异常路径，免得前端一直转圈。 */
    private static final Duration STALE_AFTER = Duration.ofMinutes(5);
    /** 单条参数/结果的长度上限，防止一段超长文本把面板撑爆。 */
    private static final int MAX_FIELD_CHARS = 600;

    /** 事件类型。前端按 kind 决定画成「思考中」还是「工具调用」。 */
    public enum Kind {
        /** 正在理解用户这一句话 */
        UNDERSTANDING,
        /** 正在决定要不要查东西、查什么 */
        PLANNING,
        /** 模型提出了工具调用，参数是模型真实生成的 */
        TOOL_PROPOSED,
        /** 工具返回了真实结果 */
        TOOL_RESULT,
        /** 正在把结果组织成人话 */
        ANSWERING
    }

    /**
     * 一条进度。
     *
     * @param parameters 工具入参的 JSON 原文（已脱敏），没有工具调用时为 null
     * @param result     工具返回的 JSON 原文（已脱敏），调用还没返回时为 null
     */
    public record Event(long seq, Kind kind, String text, String tool,
                        String parameters, String result, Boolean success, LocalDateTime at) { }

    /** 一次拉取的结果。{@code active} 为 false 表示当前没有正在进行的轮次。 */
    public record Snapshot(boolean active, List<Event> events) { }

    private static final class Turn {
        final Deque<Event> events = new ArrayDeque<>();
        long seq;
        boolean active;
        LocalDateTime lastAt;
    }

    private final Map<String, Turn> turns = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public TurnProgress(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 开始新一轮。已经是进行中的轮次时不重置——
     * {@code /images} 会把识别结论交给 {@code chatInternal} 继续走同一条主链路，
     * 那次内部调用不能再把刚记下的识图事件抹掉。
     */
    public void begin(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return;
        if (turns.size() > MAX_CONVERSATIONS) turns.clear();
        Turn turn = turns.computeIfAbsent(conversationId, key -> new Turn());
        synchronized (turn) {
            // 上一轮正常结束后 active 是 false；上一轮卡死了则靠 isStale 兜底，两种情况都允许开新一轮。
            if (turn.active && !isStale(turn)) return;
            turn.events.clear();
            turn.seq = 0;
            turn.active = true;
        }
        mark(conversationId, Kind.UNDERSTANDING);
    }

    /** 一轮结束。事件保留不删：前端可能在响应到达前最后一次拉到收尾状态。 */
    public void end(String conversationId) {
        Turn turn = conversationId == null ? null : turns.get(conversationId);
        if (turn == null) return;
        synchronized (turn) {
            turn.active = false;
        }
    }

    /** 记一条不带工具信息的事件。 */
    public void mark(String conversationId, Kind kind) {
        append(conversationId, kind, textOf(kind), null, null, null, null);
    }

    /**
     * 模型提出了这些工具调用。参数就是模型真实生成的那份，不是照着模板拼的——
     * 这正是评审要看的「参数由智能体生成」。
     */
    public void toolProposed(String conversationId, List<PlannerToolCall> calls) {
        if (calls == null) return;
        for (PlannerToolCall call : calls) {
            append(conversationId, Kind.TOOL_PROPOSED, "调用工具：" + call.toolName(),
                    call.toolName(), toJson(call.arguments()), null, null);
        }
    }

    /** 工具真的执行完了，带上真实的返回体。 */
    public void toolResult(String conversationId, String toolName, String requestJson,
                           String responseJson, boolean success) {
        append(conversationId, Kind.TOOL_RESULT,
                success ? "拿到结果：" + toolName : "调用没有成功：" + toolName,
                toolName, requestJson, responseJson, success);
    }

    /** 拉取 {@code afterSeq} 之后的事件。 */
    public Snapshot snapshot(String conversationId, long afterSeq) {
        Turn turn = conversationId == null ? null : turns.get(conversationId);
        if (turn == null) return new Snapshot(false, List.of());
        synchronized (turn) {
            List<Event> fresh = new ArrayList<>();
            for (Event event : turn.events) {
                if (event.seq() > afterSeq) fresh.add(sanitize(event));
            }
            return new Snapshot(turn.active && !isStale(turn), fresh);
        }
    }

    private void append(String conversationId, Kind kind, String text, String tool,
                        String parameters, String result, Boolean success) {
        if (conversationId == null || conversationId.isBlank()) return;
        Turn turn = turns.get(conversationId);
        if (turn == null) return;
        synchronized (turn) {
            Event stamped = new Event(++turn.seq, kind, text, tool, parameters, result, success,
                    LocalDateTime.now());
            turn.events.addLast(stamped);
            while (turn.events.size() > MAX_EVENTS) turn.events.removeFirst();
            turn.lastAt = stamped.at();
        }
    }

    private static boolean isStale(Turn turn) {
        return turn.lastAt == null || Duration.between(turn.lastAt, LocalDateTime.now()).compareTo(STALE_AFTER) > 0;
    }

    private static String textOf(Kind kind) {
        return switch (kind) {
            case UNDERSTANDING -> "正在理解您说的话…";
            case PLANNING -> "正在决定要不要查东西…";
            case ANSWERING -> "正在把结果整理成回答…";
            case TOOL_PROPOSED -> "正在调用工具…";
            case TOOL_RESULT -> "工具返回了结果";
        };
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            return String.valueOf(value);
        }
    }

    // ---------------------------------------------------------------- 脱敏

    /** 图片本体（data URL）不能原样发给前端：一条就是几十万字符，而且没有任何展示价值。 */
    private static final Pattern DATA_URL = Pattern.compile("data:[a-zA-Z0-9/+.-]*;base64,[A-Za-z0-9+/=]{100,}");
    /** 手机号按项目既有约定脱敏成 138****1234（与后端 maskPhone、目录接口一致）。 */
    private static final Pattern PHONE = Pattern.compile("(1[3-9]\\d)\\d{4}(\\d{4})");
    /** 兜底：万一哪天真把 key 塞进了工具参数，也不能顺着这个端点漏出去。 */
    private static final Pattern SECRET = Pattern.compile("sk-[A-Za-z0-9._-]{4,}");

    private static Event sanitize(Event event) {
        return new Event(event.seq(), event.kind(), event.text(), event.tool(),
                mask(event.parameters()), mask(event.result()), event.success(), event.at());
    }

    private static String mask(String value) {
        if (value == null) return null;
        String masked = SECRET.matcher(DATA_URL.matcher(value).replaceAll("（图片内容已省略）")).replaceAll("sk-***");
        masked = PHONE.matcher(masked).replaceAll("$1****$2");
        return masked.length() > MAX_FIELD_CHARS ? masked.substring(0, MAX_FIELD_CHARS) + "…" : masked;
    }
}
