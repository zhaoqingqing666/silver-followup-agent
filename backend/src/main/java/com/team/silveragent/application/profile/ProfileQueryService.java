package com.team.silveragent.application.profile;

import com.team.silveragent.agent.AgentRole;
import com.team.silveragent.application.care.CareService;
import com.team.silveragent.application.longterm.MemoryStore;
import com.team.silveragent.application.time.BusinessClock;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 画像与预约历史的受控只读查询：给模型看的是<b>已经发生过的真实记录</b>，
 * 而不是模型自己回忆出来的「您常去市一院」。
 *
 * <p><b>它只读。</b>本类不写业务数据、不碰预约草稿、不碰确认卡。查询结果里没有一条字段
 * 能让调用方顺手改掉本轮正在办的事——要改还是得走确认门禁。
 *
 * <p><b>身份由 Java 注入。</b>方法上的 {@code actorUserId} / {@code subjectUserId} 都来自会话状态
 * （建会话时就按 {@code care_relations} 判死并由后端固定），模型既不提供也无从更换。
 * 这里再查一次关系表不是不信任上游，而是把「谁能看谁的记录」这条规则钉在真正读数据的那一层：
 * 读 SQL 的入口只有一个，校验就只有一个漏点。
 *
 * <p><b>数据分三类，不混成一个「用户偏好」。</b>
 * <ol>
 *   <li>{@link AppointmentFact} —— 预约事实：「预约过市一院心内科，9 月 16 日，状态已确认」。
 *       项目里没有可靠的到院/就诊完成事实，所以这里只说「预约过」，不说「去过」。</li>
 *   <li>{@link Tendency} —— 统计倾向：多次预约形成的常见医院、科室或上午/下午分布。
 *       一次预约不构成习惯，所以只统计次数达到 {@value #TENDENCY_MIN_COUNT} 的取值，
 *       而且只有一位老人自己的记录，不含任何跨用户数据。</li>
 *   <li>{@link MemorySummary} —— 长期记忆，按 {@code source} 拆成「用户明确要求记住的偏好」
 *       与「系统从已确认预约沉淀的历史信息」两摞，来源不明的单独计数、不采用。过期（软删除）
 *       的记录由 {@link MemoryStore#list} 排除，压根不会出现在这里。</li>
 * </ol>
 * 「当轮要求」不在这里：那是当前办理草稿里的东西，属于会话状态，不是库里的历史。
 *
 * <p><b>字段白名单。</b>事实只出医院、科室、日期、时间和状态五个字段。出发时间、交通方式、
 * 材料清单、家属通知状态、代办人、联系方式一律不出库——那些是办理细节，不是画像，
 * 查一次历史顺手带出来就是把无关隐私塞进提示词。
 */
@Service
public class ProfileQueryService {

    /**
     * 越权、无此人、以及「这张会话压根没有服务对象」共用同一句话。
     *
     * <p>措辞必须一模一样：分开说就等于回答了「库里到底有没有这个人」。
     */
    public static final String REFUSED = "没有权限查看这位就诊人的信息";

    /** 一次最多摆出这么多条预约事实。再多会把这一轮的上下文挤掉，而且「最近几次」本来就不需要长列表。 */
    public static final int HISTORY_LIMIT = 5;

    /** 统计倾向回溯的预约条数上限：超过这个规模就不下统计结论，宁可不说，也不给一个自己都没看全的「习惯」。 */
    private static final int TENDENCY_SCAN_LIMIT = 200;

    /** 少于这个次数的不叫习惯。「去过一次」和「一直去」是两件事，措辞上不能混。 */
    private static final int TENDENCY_MIN_COUNT = 2;

    /** 两张表的连接方式是固定的，只有过滤条件会变；拼在一起免得四处各写一份。 */
    private static final String FROM_JOIN =
            " FROM appointments a JOIN appointment_slots s ON s.id = a.slot_id ";

    private final JdbcTemplate jdbc;
    private final CareService careService;
    private final MemoryStore memories;
    private final BusinessClock clock;

    public ProfileQueryService(JdbcTemplate jdbc, CareService careService,
                               MemoryStore memories, BusinessClock clock) {
        this.jdbc = jdbc;
        this.careService = careService;
        this.memories = memories;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- 预约历史

    /**
     * 当前服务对象的预约历史事实。
     *
     * @throws IllegalArgumentException 越权、无此人、或者调用方给不出服务对象时
     */
    public History history(String actorUserId, AgentRole actorRole, String subjectUserId, HistoryQuery query) {
        String subject = requireReadable(actorUserId, actorRole, subjectUserId);
        HistoryQuery filters = query == null ? HistoryQuery.none() : query;

        Filters unmatched = new Filters(subject, filters, null);
        int total = count(unmatched);
        int confirmedTotal = count(new Filters(subject, filters, "CONFIRMED"));

        List<AppointmentFact> facts = new ArrayList<>();
        jdbc.query("SELECT s.hospital_name, s.department, s.appointment_date, s.appointment_time, a.status"
                        + FROM_JOIN + unmatched.where()
                        + " ORDER BY s.appointment_date DESC, s.appointment_time DESC LIMIT ?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    Date date = rs.getDate(3);
                    Time time = rs.getTime(4);
                    // 没有日期的行描述不出「什么时候的事」，宁可不摆出来也不摆一条半截记录。
                    if (date == null || time == null) return;
                    LocalDate on = date.toLocalDate();
                    LocalTime at = time.toLocalTime();
                    facts.add(new AppointmentFact(on, at, rs.getString(1), rs.getString(2),
                            factStatus(rs.getString(5), on, at)));
                }, unmatched.argsWith(HISTORY_LIMIT));

        return new History(facts, total, confirmedTotal, tendencies(subject, filters, confirmedTotal));
    }

    /**
     * 这次预约还算不算「当前有效的预约」。
     *
     * <p>取消过的和日子已经过去的都不算。项目没有到院/就诊完成的事实可查，
     * 所以这里只区分「还没到日子」和「日子已经过了」，绝不说成「已经看过病了」。
     */
    private FactStatus factStatus(String raw, LocalDate date, LocalTime time) {
        if ("CANCELLED".equalsIgnoreCase(raw)) return FactStatus.CANCELLED;
        if (!"CONFIRMED".equalsIgnoreCase(raw)) return FactStatus.UNKNOWN;
        return LocalDateTime.of(date, time).isBefore(clock.now())
                ? FactStatus.CONFIRMED_PAST : FactStatus.CONFIRMED_UPCOMING;
    }

    // ---------------------------------------------------------------- 统计倾向

    /**
     * 多次预约才形成的常见医院、科室与上午/下午分布。
     *
     * <p>过滤条件与这一次查询一致（时间范围、医院、科室照旧生效），但状态恒为「已确认」——
     * 统计的是「约成了什么」，取消掉的不该算进习惯里。
     */
    private List<Tendency> tendencies(String subject, HistoryQuery query, int confirmedTotal) {
        if (confirmedTotal < TENDENCY_MIN_COUNT) return List.of();
        // 要下统计结论，就得看全。扫不全时宁可一条都不给，也不拿最近 200 条冒充全量。
        if (confirmedTotal > TENDENCY_SCAN_LIMIT) return List.of();

        Filters filters = new Filters(subject, query, "CONFIRMED");
        List<Booking> scan = new ArrayList<>();
        jdbc.query("SELECT s.hospital_name, s.department, s.appointment_time"
                        + FROM_JOIN + filters.where()
                        + " ORDER BY s.appointment_date DESC, s.appointment_time DESC LIMIT ?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    Time time = rs.getTime(3);
                    scan.add(new Booking(rs.getString(1), rs.getString(2),
                            time == null ? null : time.toLocalTime()));
                }, filters.argsWith(TENDENCY_SCAN_LIMIT));

        Map<String, Integer> hospitals = new LinkedHashMap<>();
        Map<String, Integer> departments = new LinkedHashMap<>();
        int morning = 0;
        int afternoon = 0;
        for (Booking booking : scan) {
            tally(hospitals, booking.hospital());
            tally(departments, booking.department());
            if (booking.time() == null) continue;
            if (booking.time().isBefore(LocalTime.NOON)) morning++;
            else afternoon++;
        }

        List<Tendency> result = new ArrayList<>();
        mostFrequent(Tendency.Dimension.HOSPITAL, hospitals).ifPresent(result::add);
        mostFrequent(Tendency.Dimension.DEPARTMENT, departments).ifPresent(result::add);
        // 时段倾向要求两边真的分得开：一边达到阈值、另一边更少，才算「习惯上午」。
        if (Math.max(morning, afternoon) >= TENDENCY_MIN_COUNT && morning != afternoon) {
            result.add(new Tendency(Tendency.Dimension.PERIOD,
                    morning > afternoon ? "MORNING" : "AFTERNOON", Math.max(morning, afternoon)));
        }
        return result;
    }

    private void tally(Map<String, Integer> counts, String value) {
        if (value == null || value.isBlank()) return;
        counts.merge(value, 1, Integer::sum);
    }

    /** 出现次数最多、且至少发生过两次的那个取值。并列时按名字排，保证同样的数据每次给出同样的结论。 */
    private Optional<Tendency> mostFrequent(Tendency.Dimension dimension, Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() >= TENDENCY_MIN_COUNT)
                .sorted(Comparator.comparingInt((Map.Entry<String, Integer> entry) -> entry.getValue())
                        .reversed().thenComparing(Map.Entry::getKey))
                .findFirst()
                .map(entry -> new Tendency(dimension, entry.getKey(), entry.getValue()));
    }

    // ---------------------------------------------------------------- 长期记忆

    /**
     * 当前服务对象的长期记忆摘要，按来源分成两摞。
     *
     * <p>来源不明的记录不采用，只计数：一条不知道从哪来的「常去的医院是市一院」被当成事实
     * 说出来，比不说它糟糕得多。这里不做时限判断——新旧一律带着 {@code updatedAt} 原样呈现，
     * 是否还算数由使用者结合时间自己判断，Java 不替它下结论。
     */
    public MemorySummary memorySummary(String actorUserId, AgentRole actorRole, String subjectUserId) {
        String subject = requireReadable(actorUserId, actorRole, subjectUserId);
        List<MemoryStore.Memory> explicit = new ArrayList<>();
        List<MemoryStore.Memory> booking = new ArrayList<>();
        int unverifiable = 0;
        for (MemoryStore.Memory memory : memories.list(subject)) {
            if (MemoryStore.SOURCE_USER_STATED.equals(memory.source())) explicit.add(memory);
            else if (MemoryStore.SOURCE_CONFIRMED_BOOKING.equals(memory.source())) booking.add(memory);
            else unverifiable++;
        }
        return new MemorySummary(explicit, booking, unverifiable);
    }

    // ---------------------------------------------------------------- 权限与 SQL

    /**
     * 读数据前最后一次归属校验，返回校验通过的服务对象。
     *
     * <p>口径与会话状态里的 {@code caregiving()} 一致：操作者与就诊人是同一个人时算「本人自办」，
     * 只有角色是照护者<b>且</b>两个人不是同一个，才算代他人办理。两边口径不一致，
     * 就会出现「会话认为在替人办事，这里认为在给自己查」这种最危险的错位。
     */
    private String requireReadable(String actorUserId, AgentRole actorRole, String subjectUserId) {
        if (subjectUserId == null || subjectUserId.isBlank()) throw new IllegalArgumentException(REFUSED);
        boolean caregiving = actorRole != null && actorRole.isCaregiver()
                && !subjectUserId.equals(actorUserId);
        if (caregiving) {
            if (actorUserId == null || actorUserId.isBlank() || !careService.bound(actorUserId, subjectUserId)) {
                throw new IllegalArgumentException(REFUSED);
            }
            return subjectUserId;
        }
        if (actorUserId == null || !actorUserId.equals(subjectUserId)) throw new IllegalArgumentException(REFUSED);
        return subjectUserId;
    }

    private int count(Filters filters) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*)" + FROM_JOIN + filters.where(),
                Integer.class, filters.args());
        return value == null ? 0 : value;
    }

    /**
     * 参数化的 WHERE。
     *
     * <p>拼进 SQL 文本的只有这里写死的片段，老人或模型给的取值一律走 {@code ?}——
     * 模型能调的只是「查什么条件」，不是「查哪张表、怎么查」。
     */
    private static final class Filters {
        private final StringBuilder sql = new StringBuilder(" WHERE a.user_id = ?");
        private final List<Object> args = new ArrayList<>();

        Filters(String subject, HistoryQuery query, String forcedStatus) {
            args.add(subject);
            String status = forcedStatus != null ? forcedStatus : query.status();
            if (status != null) {
                sql.append(" AND a.status = ?");
                args.add(status);
            }
            if (query.hospital() != null) {
                sql.append(" AND s.hospital_name LIKE ?");
                args.add("%" + query.hospital() + "%");
            }
            if (query.department() != null) {
                sql.append(" AND s.department LIKE ?");
                args.add("%" + query.department() + "%");
            }
            if (query.from() != null) {
                sql.append(" AND s.appointment_date >= ?");
                args.add(Date.valueOf(query.from()));
            }
            if (query.to() != null) {
                sql.append(" AND s.appointment_date <= ?");
                args.add(Date.valueOf(query.to()));
            }
        }

        String where() {
            return sql.toString();
        }

        Object[] args() {
            return args.toArray();
        }

        /** 给还要追加一个绑定值（如 LIMIT）的语句用。 */
        Object[] argsWith(Object extra) {
            List<Object> all = new ArrayList<>(args);
            all.add(extra);
            return all.toArray();
        }
    }

    private record Booking(String hospital, String department, LocalTime time) { }

    // ---------------------------------------------------------------- 对外结构

    /**
     * 一次预约历史查询的条件。全部可选：一个都不给就是「最近发生过的」。
     *
     * <p>取值合法性由 {@link com.team.silveragent.application.ToolContract} 按注册表里的声明先判一遍；
     * 这里再挡一次只是不让非法取值落到 SQL 上，不构成第二套判罚口径。
     */
    public record HistoryQuery(String hospital, String department, String status,
                               LocalDate from, LocalDate to) {
        public static HistoryQuery none() {
            return new HistoryQuery(null, null, null, null, null);
        }

        public HistoryQuery {
            hospital = trimToNull(hospital);
            department = trimToNull(department);
            status = "CONFIRMED".equals(status) || "CANCELLED".equals(status) ? status : null;
        }

        /**
         * 开始日期晚于结束日期——这段范围本身说不通。
         *
         * <p>不是「这段里没有记录」，是「这两个日期是反的」：把它当成空结果回答，
         * 老人会以为自己那段真的没有预约，而实际上他要的那段根本就没被查过。
         * 调用方必须先问清楚，别让这一条走到 SQL 上去。
         */
        public boolean rangeReversed() {
            return from != null && to != null && from.isAfter(to);
        }
    }

    /** 一条预约事实。只有这五个字段：多一个都是把办理细节混进了画像。 */
    public record AppointmentFact(LocalDate date, LocalTime time, String hospital, String department,
                                  FactStatus status) { }

    /** 事实状态。{@code CONFIRMED_PAST} 只表示日子过去了，不表示人真的去过。 */
    public enum FactStatus {
        CONFIRMED_UPCOMING, CONFIRMED_PAST, CANCELLED, UNKNOWN;

        /** 只有「已确认且还没到日子」的才算当前仍然有效的预约。 */
        public boolean currentlyValid() {
            return this == CONFIRMED_UPCOMING;
        }
    }

    /** 一条统计倾向：{@code dimension} 是维度，{@code value} 是取值，{@code count} 是确认过的次数。 */
    public record Tendency(Dimension dimension, String value, int count) {
        public enum Dimension { HOSPITAL, DEPARTMENT, PERIOD }
    }

    /**
     * @param facts          按预约日期从晚到早的少量事实，最多 {@value #HISTORY_LIMIT} 条
     * @param total          符合条件的记录总数（可能多于 {@code facts} 条数）
     * @param confirmedTotal 其中状态为「已确认」的条数，统计倾向只在它达到阈值时才给出
     */
    public record History(List<AppointmentFact> facts, int total, int confirmedTotal,
                          List<Tendency> tendencies) { }

    /**
     * @param explicitPreferences 用户明确要求记住的偏好（来源 {@code USER_STATED}）
     * @param bookingHistory      系统从已确认预约沉淀的历史信息（来源 {@code CONFIRMED_BOOKING}）
     * @param unverifiableCount   来源不明、因此不予采用的记录条数
     */
    public record MemorySummary(List<MemoryStore.Memory> explicitPreferences,
                                List<MemoryStore.Memory> bookingHistory,
                                int unverifiableCount) {
        public boolean isEmpty() {
            return explicitPreferences.isEmpty() && bookingHistory.isEmpty() && unverifiableCount == 0;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
