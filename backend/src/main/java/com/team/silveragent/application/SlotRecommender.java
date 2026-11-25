package com.team.silveragent.application;

import com.team.silveragent.domain.model.ToolModels.Slot;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 号源推荐排序：阶段 2「选医交互」的 R2 规则，一条一个纯函数，不碰数据库、不碰会话。
 *
 * <p>主排序（严格顺序，见 docs/proposals/选医交互方案（阶段2）.md 第一节 R2）：
 *
 * <ol>
 *   <li><b>可预约优先</b>——调用方保证传入的都是可约号源，这里不重复判（查询层兜底）；</li>
 *   <li><b>匹配号型优先</b>——老人要专家号时 EXPERT 在前；没有号别诉求时不惩罚普通号；</li>
 *   <li><b>职称优先</b>——主任医师 &gt; 副主任医师 &gt; 主治医师；</li>
 *   <li><b>就近日期优先</b>——跨日推荐（7 天内找专家号）时用得上；</li>
 *   <li><b>上午时段优先</b>——同一天上午先于下午，再按时刻从早到晚；</li>
 *   <li><b>同档位低价优先</b>——只有前面全部打平（同号别、同职称、同一天、上午下午也相同）
 *       时才看挂号费：价格永远不参与跨职称、跨号别的主排序（R5）。</li>
 * </ol>
 *
 * <p>排序之后还有一步**聚合**：没点名医生时按医生去重、每人只留最优一条
 * （{@link #bestPerDoctor(List)}），点名医生时不去重、展示他的全部时段。
 * 这一步同样收在这里，别在各个调用点各写一份。
 */
public final class SlotRecommender {

    /** 单次最多摆到老人面前的候选数：防止信息过载（R3）。 */
    public static final int MAX_OFFERED_SLOTS = 2;

    private SlotRecommender() {
    }

    /**
     * 按推荐次序排号源。{@code wantsExpert} 为 true 时专家号整体前移——
     * 这是唯一的「号型匹配」开关，普通浏览（老人没说要专家）不传 true。
     */
    public static List<Slot> sort(List<Slot> slots, boolean wantsExpert) {
        return slots.stream().sorted(comparator(wantsExpert)).toList();
    }

    public static Comparator<Slot> comparator(boolean wantsExpert) {
        return Comparator
                .comparing((Slot slot) -> wantsExpert && isExpert(slot) ? 0 : 1)
                .thenComparing(slot -> -titleRank(slot.doctorTitle()))
                .thenComparing(Slot::date)
                .thenComparing(slot -> slot.time().isBefore(LocalTime.NOON) ? 0 : 1)
                .thenComparing(Slot::time)
                .thenComparing(SlotRecommender::feeCents);
    }

    /**
     * 排序结果里同一位医生只留最靠前的一条，整体次序不变。
     *
     * <p>这就是「老人没点名医生时，每位医生只摆一个候选」这条规则的唯一出处。
     * 半天制下一位医生上午坐整班（09:00 / 10:30 各一条），职称排序又让主任医师稳居第一，
     * 不去重的话「最多 2 个候选」会全被同一个人占满，老人以为今天只有这一位医生。
     *
     * <p>**只在没点名医生时用**。老人点名了某位医生（{@code state.doctorId != null}）时不去重，
     * 要把他的每个时段都摆出来让老人挑时间——两条路的差别就在这一个开关上，
     * 所以规则收在这里而不是散在各个调用点。
     *
     * <p>医生的身份取 {@code doctorId}；旧号源没有医生维度（{@code doctorId} 为 null）时
     * 退到 {@code doctorName}，再退到号源 id——旧号源之间本就无法证明是同一人，
     * 各自独立反而是安全的一侧（宁可多摆一条，也不要把两条不同医生的号源合成一条）。
     */
    public static List<Slot> bestPerDoctor(List<Slot> sorted) {
        Set<String> seen = new LinkedHashSet<>();
        List<Slot> picked = new ArrayList<>();
        for (Slot slot : sorted) {
            String key = slot.doctorId() != null ? slot.doctorId()
                    : slot.doctorName() != null ? slot.doctorName() : slot.id();
            if (seen.add(key)) picked.add(slot);
        }
        return picked;
    }

    /** 职称等级：主任医师 3、副主任医师 2、主治医师 1，认不出按 0（不参与职称比较的场景）。 */
    public static int titleRank(String title) {
        if (title == null) return 0;
        if (title.contains("副主任")) return 2;
        if (title.contains("主任")) return 3;
        if (title.contains("主治")) return 1;
        return 0;
    }

    public static boolean isExpert(Slot slot) {
        return slot != null && "EXPERT".equals(slot.slotType());
    }

    /** 挂号费（分）。旧号源没有费用时排最后，不拿 null 去比较。 */
    private static int feeCents(Slot slot) {
        return slot.feeCents() == null ? Integer.MAX_VALUE : slot.feeCents();
    }
}
