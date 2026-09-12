package com.team.silveragent.application.demo;

import java.util.Arrays;
import java.util.List;

/**
 * 赛题要求的四个演示场景。
 *
 * <p>用固定编号而不是「口头说明选哪一天」：演示日期是滚动生成的（见
 * {@code RollingUserScheduleInitializer}），录屏和评审查验时无法靠一句话说清是几号，
 * 只能靠一个能重复执行的入口把起始状态摆好。
 */
public enum DemoScenario {
    NORMAL("normal", "场景一 正常办理"),
    NO_SLOT("no-slot", "场景二 指定日期无号源"),
    CONFLICT("conflict", "场景三 时间冲突"),
    BOUNDARY("boundary", "场景四 服务越界");

    private final String id;
    private final String title;

    DemoScenario(String id, String title) {
        this.id = id;
        this.title = title;
    }

    public String id() { return id; }

    public String title() { return title; }

    /** 编号大小写与两侧空格都容忍：手敲 curl 和写进脚本的两种场景都要能对上。 */
    public static DemoScenario byId(String requested) {
        if (requested != null) {
            for (DemoScenario item : values()) {
                if (item.id.equalsIgnoreCase(requested.trim())) return item;
            }
        }
        throw new IllegalArgumentException("未知演示场景：" + requested + "，可选值：" + ids());
    }

    public static List<String> ids() {
        return Arrays.stream(values()).map(DemoScenario::id).toList();
    }
}
