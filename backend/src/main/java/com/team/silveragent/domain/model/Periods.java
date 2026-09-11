package com.team.silveragent.domain.model;

/**
 * 上午/下午时段码。
 *
 * <p>它是 {@code SET_PERIOD} 的值，也是规则提取器写入 facts 的取值。
 * 这两个字符面量原先散落在编排器、提取器和服务里各写一遍，
 * 拼错时不会编译报错，只会被 {@code recommendPeriod} 静默当成上午处理。
 */
public final class Periods {

    public static final String MORNING = "MORNING";
    public static final String AFTERNOON = "AFTERNOON";

    private Periods() { }

    /** 把任意输入规范化成两个取值之一；未知输入按上午处理。 */
    public static String normalize(String preference) {
        return AFTERNOON.equalsIgnoreCase(preference) ? AFTERNOON : MORNING;
    }

    /** 另一个时段。 */
    public static String other(String preference) {
        return MORNING.equals(normalize(preference)) ? AFTERNOON : MORNING;
    }

    /** 展示用名称。 */
    public static String label(String preference) {
        return AFTERNOON.equals(normalize(preference)) ? "下午" : "上午";
    }
}
