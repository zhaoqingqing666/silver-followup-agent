package com.team.silveragent.application.care;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 照护端表单路径的一次性票据：预览时开票，提交时核销。
 *
 * <p>表单路径没有会话，{@code ConversationState.confirmationId} 那套用不上——家属在页面上
 * 点「确认」时，中间隔着好几屏和好几次请求，服务端手里没有任何东西能证明「他看到的就是他要提交的」。
 * 票据补的就是这一段：预览时把<b>会改变执行结果的那几个字段</b>做成快照一起存下来，
 * 提交时按同一套规则再算一遍，对得上才放行。
 *
 * <p>存在内存里，不建表：票据的生命周期只有「看一眼 → 点确认」这一小会儿，
 * 重启后旧票据一律失效，家属会看到「这份确认已经失效，请重新查看要办理的内容后再确认」，
 * 重新看一眼就好。多发一份表、多一次数据迁移，换不来这一小会儿的可靠性。
 *
 * <p>两张票的关系是<b>一次即焚</b>：只要拿着它来提交，不论内容对不对上，票都作废——
 * 省得有人拿着一张票反复试不同内容，把这里当成一个可以猜的口子。
 */
@Component
public class CareConfirmationStore {
    /** 票据有效期。够家属读完确认卡再点确认，又不至于让一张没人管的票一直留着。 */
    private static final Duration LIFETIME = Duration.ofMinutes(30);
    /** 最多同时留几张票；超了先清过期的，还超就丢最旧的那张。 */
    private static final int MAX_TICKETS = 500;

    /**
     * @param caregiverId 开票的照护者：别人拿着这张票提交不算数
     * @param action      BOOK / MODIFY / CANCEL：拿代约的票去执行取消也不算数
     * @param snapshot    开票时那份「会改变执行结果的字段」，提交时逐项比对
     */
    public record CareConfirmation(String id, String caregiverId, String elderUserId, String action,
                                   Map<String, String> snapshot, LocalDateTime openedAt) { }

    private final Map<String, CareConfirmation> tickets = new ConcurrentHashMap<>();

    /** 开一张票，返回带 id 的那份。这一步不写任何业务数据。 */
    public CareConfirmation open(String caregiverId, String elderUserId, String action, Map<String, String> snapshot) {
        purge();
        CareConfirmation ticket = new CareConfirmation(
                "CC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                caregiverId, elderUserId, action, snapshot, LocalDateTime.now());
        tickets.put(ticket.id(), ticket);
        return ticket;
    }

    /**
     * 核销票据：照护者、就诊人、动作、快照四项全对上才算数。
     *
     * <p>返回空表示「这张票据不能用」——调用方一律回同一句话，不区分是过期、用过了、
     * 还是内容对不上：那些区别只对攻击者有意义，对家属没有任何用。
     */
    public Optional<CareConfirmation> consume(String confirmationId, String caregiverId, String elderUserId,
                                              String action, Map<String, String> snapshot) {
        if (confirmationId == null || confirmationId.isBlank()) return Optional.empty();
        purge();
        // 先取再删：一次即焚，对不上也烧掉，不给「拿一张票反复试」留口子
        CareConfirmation ticket = tickets.remove(confirmationId);
        if (ticket == null) return Optional.empty();
        boolean matches = ticket.caregiverId().equals(caregiverId)
                && ticket.elderUserId().equals(elderUserId)
                && ticket.action().equals(action)
                && ticket.snapshot().equals(snapshot);
        return matches ? Optional.of(ticket) : Optional.empty();
    }

    /** 清掉过期的；还很挤就按开票时间丢最旧的，别让内存跟着运行时长一起涨。 */
    private void purge() {
        LocalDateTime deadline = LocalDateTime.now().minus(LIFETIME);
        tickets.values().removeIf(ticket -> ticket.openedAt().isBefore(deadline));
        if (tickets.size() < MAX_TICKETS) return;
        tickets.values().stream()
                .min(Comparator.comparing(CareConfirmation::openedAt))
                .ifPresent(oldest -> tickets.remove(oldest.id()));
    }
}
