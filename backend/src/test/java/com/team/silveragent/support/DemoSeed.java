package com.team.silveragent.support;

import com.team.silveragent.infrastructure.persistence.RollingAppointmentSlotInitializer;
import com.team.silveragent.infrastructure.persistence.RollingUserScheduleInitializer;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 演示种子数据的日期全部是滚动的，回归用例一律从这里取，不要再写死某一天。
 *
 * 写死日期的问题在 2026-09 暴露过一次：日程固定排在某天，那天一过，冲突场景就再也造不出来，
 * 而一批用例要等到那天之后才开始变红——发现得太晚。这里把「哪一天、哪个号源」收敛成几个名字，
 * 日期跟着今天走，回归用例因此不会随时间失效。
 */
public final class DemoSeed {

    private DemoSeed() {
    }

    /** 演示科室：市第一医院心内科、市人民医院内分泌科。 */
    public static final String CARDIOLOGY = "d001";
    public static final String ENDOCRINOLOGY = "d003";

    /** 下周三：与「社区体检」同一天，上午 10:30 的号源必然被判为冲突（场景三）。 */
    public static LocalDate checkupDay() {
        return RollingUserScheduleInitializer.checkupDate();
    }

    /** 下周六：没有任何号源，用来复现「指定日期无号」（场景二）。 */
    public static LocalDate emptyDay() {
        return RollingUserScheduleInitializer.dinnerDate();
    }

    /** 体检那天的后一天：改期用例的目标日，和体检日程错开，避免撞上冲突判定。 */
    public static LocalDate laterDay() {
        return checkupDay().plusDays(1);
    }

    /** 后一天下午的号源：改期用例用它验证「原预约同一条记录换号源」。 */
    public static String laterDaySlot() {
        return slot(CARDIOLOGY, laterDay(), LocalTime.of(14, 0));
    }

    /** 内分泌科在后一天下午的号源：「换了一家医院/科室」的覆盖用例用它，口播记为下午复诊。 */
    public static String endocrinologySlot() {
        return slot(ENDOCRINOLOGY, laterDay(), LocalTime.of(14, 0));
    }

    /** 上午 09:00：一天里最早的一格，口播成「上午9点」，取消候选的「最早/最近一次」靠它区分。 */
    public static final LocalTime MORNING = LocalTime.of(9, 0);
    /** 下午 15:30：同一段会话里约第二次时用它，和上午那格分得开。 */
    public static final LocalTime AFTERNOON = LocalTime.of(15, 30);

    /** 心内科下周三上午的号源：口播/确认卡里写作「上午9点」。 */
    public static String morningSlot() {
        return slot(CARDIOLOGY, checkupDay(), MORNING);
    }

    /** 心内科下周三下午的号源：正常可约，且与体检不重叠——「普通办理」用它。 */
    public static String plainSlot() {
        return slot(CARDIOLOGY, checkupDay(), LocalTime.of(14, 0));
    }

    /** 心内科下周三 10:30：和体检撞在一起的号源——冲突用例用它。 */
    public static String conflictingSlot() {
        return slot(CARDIOLOGY, checkupDay(), LocalTime.of(10, 30));
    }

    /** 心内科下周三的另一个普通号源：同一段会话里再约一次时用，和 plainSlot 不冲突。 */
    public static String secondSlot() {
        return slot(CARDIOLOGY, checkupDay(), AFTERNOON);
    }

    /** 滚动号源的 id 规则由 RollingAppointmentSlotInitializer 提供，避免两处各写一遍。 */
    public static String slot(String departmentId, LocalDate day, LocalTime time) {
        return RollingAppointmentSlotInitializer.slotId(departmentId, day, time);
    }

    /** 取号源所在的那一天，用于 SET_DATE 这类需要「日期」参数的调用。 */
    public static String day(LocalDate day) {
        return day.toString();
    }

    /** 确认卡与结果卡里的时刻写法，例如「09:00」——断言直接用它，别抄一份。 */
    public static String clock(LocalTime time) {
        return time.toString();
    }

    /** 助手回复里的中文日期写法，例如「2026年9月16日」——断言直接用它，别抄一份。 */
    public static String chineseDay(LocalDate day) {
        return day.getYear() + "年" + day.getMonthValue() + "月" + day.getDayOfMonth() + "日";
    }
}
