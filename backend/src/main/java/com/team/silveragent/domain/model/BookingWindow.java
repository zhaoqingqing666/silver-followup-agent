package com.team.silveragent.domain.model;

import java.time.LocalDate;

/**
 * 可预约时间窗：今天起一个月内。
 *
 * <p>号源生成（{@code RollingAppointmentSlotInitializer}）与日期校验
 * （{@code FollowupAgentService}）必须用同一个窗口，否则会出现“能选到却没有号源”
 * 或者“有号源却被告知超出范围”的不一致。
 */
public final class BookingWindow {

    /** 可预约的最大跨度（月）。 */
    public static final int MONTHS_AHEAD = 1;

    private BookingWindow() { }

    /** 从 {@code from} 算起可预约的最后一天。 */
    public static LocalDate lastBookableDate(LocalDate from) {
        return from.plusMonths(MONTHS_AHEAD);
    }

    /** 该日期是否落在 {@code today} 开始的预约窗口内。 */
    public static boolean isBookable(LocalDate date, LocalDate today) {
        return !date.isBefore(today) && !date.isAfter(lastBookableDate(today));
    }
}
