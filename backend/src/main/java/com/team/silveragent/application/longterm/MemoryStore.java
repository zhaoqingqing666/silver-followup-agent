package com.team.silveragent.application.longterm;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 长期记忆：跨对话记住的、关于这位老人的事。
 *
 * <p><b>记什么。</b>记「实际办成的事」——最近一次确认预约的医院、科室和时段。
 * 这些都是中性的事实陈述，不是偏好：一次预约不等于习惯，所以措辞里不出现
 * 「常去」「习惯」这类归纳。不记他某一轮随口提过的想法：那是草稿，不是事实，
 * 记下来下次就会拿着一个过期的东西去替他做决定。
 *
 * <p><b>谁来写。</b>只有一条路径：确认门禁放行、预约真的落进 appointments 之后。
 * 也就是说，模型不能凭空往这里写东西，前端也不能——记忆是「人确认过的事」的副产品。
 *
 * <p><b>模型怎么读到它。</b>只有一条路：按需调用
 * {@code profile.memorySummary}（见 {@code application/profile/ProfileQueryService}），
 * 拿回按来源分好类的两摞，并带着来源与更新时间。<b>不要</b>再把 {@link #digest}
 * 拼进每轮提示词——那是本类早期给主模型用的默认画像入口，它不分来源、不带时间，
 * 一条系统归纳的记录到了模型眼里和老人亲口说的话没有区别，已经下线。
 *
 * <p><b>怎么用。</b>模型看到的只是上下文，真正的写操作仍然要过确认门禁。
 * 记住不等于可以替他办事。
 *
 * <p>同一个 key 只留最新一版：换了医院就覆盖，不堆历史。老人自己也能在「我的」里
 * 看到并逐条忘掉——记错了要能删，这是记忆功能能被接受的前提。
 */
@Repository
public class MemoryStore {
    /**
     * 来源：用户明确要求「以后都记着」的偏好（医院、科室、时段）。
     *
     * <p>常量放在这里，是因为「这条记忆是怎么来的」是记忆本身的一部分，而不是某一处调用点的私事：
     * 读取侧要按它把「老人明确说过的偏好」和「系统自己从预约里总结出来的」分开说，
     * 两边必须用同一个字符串，否则分完类还是错的。
     *
     * <p><b>目前还没有这条写入路径</b>：写入归「偏好保存」那一项，本阶段不做，
     * 所以库里现在不会有这个来源的记录。读取侧认它，是为了将来那条唯一的写入口接上就能用。
     */
    public static final String SOURCE_USER_STATED = "USER_STATED";

    /** 来源：确认门禁放行、预约真的落进 appointments 之后沉淀下来的历史信息。不是老人的明确偏好。 */
    public static final String SOURCE_CONFIRMED_BOOKING = "CONFIRMED_BOOKING";

    /**
     * 类别：系统从已确认预约里沉淀的历史事实。
     *
     * <p>与 {@code HABIT} / {@code PREFERENCE} 的区别不是显示分组，而是**不许被读成偏好**：
     * 这一类的每一条都只是「最近一次确认预约的是……」，它没有说过老人常去哪家、也没有说过
     * 老人喜欢什么。原来给时段标 {@code PREFERENCE} 是错的——那会让同一条系统归纳的记录
     * 在读取侧带上一个「这是偏好」的标签。
     *
     * <p>{@code kind} 本身只用于界面分组与排查，不参与任何决策（见 {@link Memory}）。
     */
    public static final String KIND_HISTORY = "HISTORY";

    /** 提示词里最多带这么多条：再多会把当前这一轮的上下文挤掉，反而帮倒忙。 */
    private static final int DIGEST_LIMIT = 8;
    /** 一句话的上限，和表的 VARCHAR(300) 对齐，读出来也不至于撑爆提示词。 */
    private static final int MAX_CONTENT = 300;

    private final JdbcTemplate jdbc;

    MemoryStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * 记一条。已有同 key 的就地覆盖内容与时间，但保留第一次记住的时间——
     * 「什么时候开始知道的」和「最近一次确认」是两件事。
     */
    public void remember(String userId, String key, String kind, String content,
                         String source, String conversationId) {
        if (userId == null || userId.isBlank() || key == null || key.isBlank()) return;
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) return;
        if (text.length() > MAX_CONTENT) text = text.substring(0, MAX_CONTENT);
        LocalDateTime now = LocalDateTime.now();
        int updated = jdbc.update("""
                UPDATE user_memories SET kind=?,content=?,source=?,source_conversation_id=?,
                       updated_at=?,active=TRUE
                WHERE user_id=? AND memory_key=?
                """, kind, text, source, conversationId, Timestamp.valueOf(now), userId, key);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO user_memories(user_id,memory_key,kind,content,source,
                                              source_conversation_id,created_at,updated_at,active)
                    VALUES (?,?,?,?,?,?,?,?,TRUE)
                    """, userId, key, kind, text, source, conversationId,
                    Timestamp.valueOf(now), Timestamp.valueOf(now));
        }
    }

    /** 这位老人身上还生效的记忆，最近确认的在前。 */
    public List<Memory> list(String userId) {
        return jdbc.query("""
                SELECT memory_key,kind,content,source,updated_at FROM user_memories
                WHERE user_id=? AND active=TRUE ORDER BY updated_at DESC, memory_key
                """, (rs, row) -> new Memory(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4),
                rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toLocalDateTime()),
                userId);
    }

    public Optional<Memory> find(String userId, String key) {
        return list(userId).stream().filter(item -> item.key().equals(key)).findFirst();
    }

    /**
     * 忘掉一条。做成软删除（active=FALSE）而不是 DELETE：这是老人自己按的按钮，
     * 但「他什么时候让我忘掉的」本身也是个事实，硬删就查不到了。
     */
    public boolean forget(String userId, String key) {
        return jdbc.update("UPDATE user_memories SET active=FALSE, updated_at=? WHERE user_id=? AND memory_key=? AND active=TRUE",
                Timestamp.valueOf(LocalDateTime.now()), userId, key) == 1;
    }

    /**
     * 把这位老人的记忆拼成一句话（最多 {@value #DIGEST_LIMIT} 条）。
     *
     * <p><b>已经不是主模型的画像入口了，别再拿它拼提示词。</b>它把全部 active 记忆不分来源、
     * 不带更新时间地揉成一句，模型拿到之后分不出哪条是老人明确说的、哪条是系统自己归纳的。
     * 画像统一走 {@code profile.memorySummary}（按来源分类 + 带来源与时间 + 支持越权拒绝）。
     *
     * <p>保留它是为了不打断既有调用面（「我的」页那一侧与既有测试）。新代码不要用。
     */
    public String digest(String userId) {
        if (userId == null || userId.isBlank()) return "";
        List<Memory> memories = list(userId);
        if (memories.isEmpty()) return "";
        return memories.stream().limit(DIGEST_LIMIT).map(Memory::content)
                .collect(java.util.stream.Collectors.joining("；"));
    }

    /** @param key 内部标识（如 habit.hospital），覆盖时靠它认人
     *  @param kind HABIT / PREFERENCE —— 只用于界面分组与排查，不参与任何决策 */
    public record Memory(String key, String kind, String content, String source, LocalDateTime updatedAt) { }
}
