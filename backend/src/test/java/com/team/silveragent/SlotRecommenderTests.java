package com.team.silveragent;

import com.team.silveragent.application.SlotRecommender;
import com.team.silveragent.domain.model.ToolModels.Slot;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 号源推荐排序（R2/R5）逐条钉死：纯函数、不碰数据库。
 *
 * <p>规则出处：docs/proposals/选医交互方案（阶段2）.md——号型匹配 → 职称 → 就近日期 →
 * 上午优先 → 同档位低价。价格永远不参与跨职称、跨号别的主排序。
 */
class SlotRecommenderTests {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 16);
    private static final LocalDate NEXT_DAY = LocalDate.of(2026, 9, 17);

    private Slot slot(String id, String title, String type, LocalDate date, LocalTime time, Integer fee) {
        return new Slot(id, "h001", "市第一医院", "心内科", date, time, "doc-" + id, "医生" + id, title, type, fee);
    }

    /** 医生 id 与号源 id 分开指定：验证「按医生聚合」时两条号源必须挂同一位医生。 */
    private Slot slotOf(String id, String doctorId, String title, String type, LocalTime time, Integer fee) {
        return new Slot(id, "h001", "市第一医院", "心内科", DAY, time, doctorId, "医生" + doctorId, title, type, fee);
    }

    @Test void expertWishPutsExpertSlotsFirst() {
        Slot normal = slot("n", "主治医师", "NORMAL", DAY, LocalTime.of(9, 0), 2500);
        Slot expert = slot("e", "主任医师", "EXPERT", DAY, LocalTime.of(10, 30), 4000);

        List<Slot> sorted = SlotRecommender.sort(List.of(normal, expert), true);

        assertThat(sorted.get(0).id()).as("老人要专家号，专家号整体前移").isEqualTo("e");
    }

    @Test void titleLeadsEvenWhenTheNormalSlotIsCheaper() {
        Slot expert = slot("e", "主任医师", "EXPERT", DAY, LocalTime.of(10, 30), 4000);
        Slot normal = slot("n", "主治医师", "NORMAL", DAY, LocalTime.of(9, 0), 2500);

        List<Slot> sorted = SlotRecommender.sort(List.of(normal, expert), false);

        assertThat(sorted.get(0).id()).as("没有号别诉求时职称仍然优先").isEqualTo("e");
    }

    @Test void priceNeverOverridesTitleOrSlotType() {
        Slot cheapNormal = slot("n", "主治医师", "NORMAL", DAY, LocalTime.of(9, 0), 2500);
        Slot priceyExpert = slot("e", "主任医师", "EXPERT", DAY, LocalTime.of(9, 0), 4000);

        List<Slot> sorted = SlotRecommender.sort(List.of(cheapNormal, priceyExpert), false);

        assertThat(sorted.get(0).id()).as("R5：便宜的普通号不能越过贵的专家号").isEqualTo("e");
    }

    @Test void priceBreaksTiesOnlyInsideTheSameTier() {
        Slot pricey = slot("a", "主治医师", "NORMAL", DAY, LocalTime.of(9, 0), 3000);
        Slot cheaper = slot("b", "主治医师", "NORMAL", DAY, LocalTime.of(9, 0), 2500);

        List<Slot> sorted = SlotRecommender.sort(List.of(pricey, cheaper), false);

        assertThat(sorted.get(0).id()).as("同号别、同职称、同一天、同时段才比价格").isEqualTo("b");
    }

    @Test void nearerDateComesFirst() {
        Slot later = slot("l", "主任医师", "EXPERT", NEXT_DAY, LocalTime.of(9, 0), 4000);
        Slot sooner = slot("s", "主任医师", "EXPERT", DAY, LocalTime.of(15, 30), 4000);

        List<Slot> sorted = SlotRecommender.sort(List.of(later, sooner), true);

        assertThat(sorted.get(0).id()).as("7 天内找最近专家号靠这条").isEqualTo("s");
    }

    @Test void morningComesBeforeAfternoonOnTheSameDay() {
        Slot afternoon = slot("p", "主任医师", "EXPERT", DAY, LocalTime.of(14, 0), 4000);
        Slot morning = slot("m", "主任医师", "EXPERT", DAY, LocalTime.of(9, 0), 4000);

        List<Slot> sorted = SlotRecommender.sort(List.of(afternoon, morning), true);

        assertThat(sorted.get(0).id()).isEqualTo("m");
    }

    @Test void titleRankFollowsTheUsualLadder() {
        assertThat(SlotRecommender.titleRank("主任医师")).isEqualTo(3);
        assertThat(SlotRecommender.titleRank("副主任医师")).isEqualTo(2);
        assertThat(SlotRecommender.titleRank("主治医师")).isEqualTo(1);
        assertThat(SlotRecommender.titleRank(null)).isZero();
    }

    @Test void slotsWithoutAFeeSortLastInsideTheirTier() {
        Slot noFee = slot("x", "主治医师", "NORMAL", DAY, LocalTime.of(9, 0), null);
        Slot cheap = slot("b", "主治医师", "NORMAL", DAY, LocalTime.of(9, 0), 2500);

        List<Slot> sorted = SlotRecommender.sort(List.of(noFee, cheap), false);

        assertThat(sorted.get(0).id()).as("旧号源没有费用，不拿 null 去比").isEqualTo("b");
    }

    /**
     * 号别只看号源上的 {@code slotType}，**不看医生职称**。
     *
     * <p>同一个主任医师可以上午出专家号、下午出普通号，按职称判号别会把下午那档
     * 当成专家号（价格、候选次序全跟着错）。这条用「主任医师 + NORMAL」这个组合钉住它。
     */
    @Test void expertIsDecidedBySlotTypeNotByDoctorTitle() {
        Slot seniorTitleButNormalSlot = slot("n", "主任医师", "NORMAL", DAY, LocalTime.of(10, 30), 2500);

        assertThat(SlotRecommender.isExpert(seniorTitleButNormalSlot))
                .as("主任医师出的普通号仍然是普通号")
                .isFalse();
        assertThat(SlotRecommender.isExpert(slot("e", "主治医师", "EXPERT", DAY, LocalTime.of(9, 0), 4000)))
                .as("反过来也一样：号别不跟着职称走")
                .isTrue();
        assertThat(SlotRecommender.isExpert(slot("x", "主任医师", null, DAY, LocalTime.of(9, 0), null)))
                .as("旧号源没有号别，不能猜成专家号")
                .isFalse();
    }

    /** 没点名医生时，每位医生只留最靠前的一条——上午坐整班的主任医师不能把两个候选全占了。 */
    @Test void bestPerDoctorKeepsOnlyTheTopSlotOfEachDoctor() {
        Slot seniorMorning = slotOf("a1", "doc-senior", "主任医师", "EXPERT", LocalTime.of(9, 0), 4000);
        Slot seniorLateMorning = slotOf("a2", "doc-senior", "主任医师", "EXPERT", LocalTime.of(10, 30), 4000);
        Slot otherDoctor = slotOf("b1", "doc-normal", "主治医师", "NORMAL", LocalTime.of(9, 0), 2500);

        List<Slot> sorted = SlotRecommender.sort(List.of(seniorLateMorning, otherDoctor, seniorMorning), true);
        List<Slot> packed = SlotRecommender.bestPerDoctor(sorted);

        assertThat(packed).extracting(Slot::id)
                .as("同一位医生只留最靠前的一条，其余医生不受影响，次序也不变")
                .containsExactly("a1", "b1");
    }

    /** 本来就是两位医生时一条都不该被合掉——「点名医生时不聚合」靠的就是这个性质。 */
    @Test void bestPerDoctorKeepsEverySlotWhenTheSlotsBelongToDifferentDoctors() {
        Slot expert = slotOf("e", "doc-senior", "主任医师", "EXPERT", LocalTime.of(9, 0), 4000);
        Slot normal = slotOf("n", "doc-normal", "主治医师", "NORMAL", LocalTime.of(10, 30), 2500);

        assertThat(SlotRecommender.bestPerDoctor(List.of(expert, normal)))
                .as("本来就是两位医生，一条都不该被合掉")
                .hasSize(2);
    }

    /** 旧号源没有医生信息：各自独立，宁可多摆一条，也不要把两位医生的号源合成一条。 */
    @Test void bestPerDoctorTreatsSlotsWithoutADoctorAsIndependent() {
        Slot legacyOne = new Slot("l1", "h001", "市第一医院", "心内科", DAY, LocalTime.of(9, 0),
                null, null, null, null, null);
        Slot legacyTwo = new Slot("l2", "h001", "市第一医院", "心内科", DAY, LocalTime.of(9, 0),
                null, null, null, null, null);

        assertThat(SlotRecommender.bestPerDoctor(List.of(legacyOne, legacyTwo)))
                .as("无法证明是同一人时不能合并")
                .hasSize(2);
    }
}
