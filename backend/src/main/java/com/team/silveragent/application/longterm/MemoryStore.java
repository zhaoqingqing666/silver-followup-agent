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
 * <p><b>记什么。</b>只记「实际办成的事」沉淀下来的偏好——常去的医院、常去的科室、
 * 习惯的复诊时段。这些是老人自己每次都要重说一遍的东西，记住它们能实打实省掉一轮问答。
 * 不记他某一轮随口提过的想法：那是草稿，不是习惯，记下来下次就会拿着一个过期的
 * 偏好去替他做决定。
 *
 * <p><b>谁来写。</b>只有一条路径：确认门禁放行、预约真的落进 appointments 之后。
 * 也就是说，模型不能凭空往这里写东西，前端也不能——记忆是「人确认过的事」的副产品。
 *
 * <p><b>怎么用。</b>读的时候拼成一句话塞进提示词（{@link #digest}），
 * 模型看到的只是上下文，真正的写操作仍然要过确认门禁。记住不等于可以替他办事。
 *
 * <p>同一个 key 只留最新一版：换了医院就覆盖，不堆历史。老人自己也能在「我的」里
 * 看到并逐条忘掉——记错了要能删，这是记忆功能能被接受的前提。
 */
@Repository
public class MemoryStore {
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
     * 拼成提示词里的一句话。没有记忆时返回空串——这时提示词与没有这个功能时逐字相同，
     * 老会话和既有测试都不会因为多了个空段而漂移。
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
