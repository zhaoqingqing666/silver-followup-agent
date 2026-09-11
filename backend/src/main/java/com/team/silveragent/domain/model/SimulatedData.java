package com.team.silveragent.domain.model;

/**
 * 模拟数据标记。
 *
 * <p>医院和科室名称在模拟数据里带「（模拟）」后缀，展示和跨表匹配前都要去掉；
 * 全角、半角两种写法都出现过，所以由一处统一处理。
 */
public final class SimulatedData {

    public static final String MARKER_FULL_WIDTH = "（模拟）";
    public static final String MARKER_HALF_WIDTH = "(模拟)";

    private SimulatedData() { }

    /** 去掉模拟标记并修剪空白；null 视为空串。 */
    public static String stripMarker(String value) {
        if (value == null) return "";
        return value.replace(MARKER_FULL_WIDTH, "").replace(MARKER_HALF_WIDTH, "").trim();
    }
}
