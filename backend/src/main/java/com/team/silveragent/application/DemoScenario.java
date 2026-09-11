package com.team.silveragent.application;

import java.util.Arrays;
import java.util.List;

/**
 * 赛题要求的四个演示场景。用固定编号而不是“口头指导选哪一天”，
 * 保证录屏和评审时可以稳定复现。
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

    public static DemoScenario byId(String requested) {
        if (requested != null) {
            for (DemoScenario item : values()) {
                if (item.id.equalsIgnoreCase(requested.trim())) return item;
            }
        }
        throw new IllegalArgumentException("未知演示场景：" + requested + "，可选值："
                + Arrays.stream(values()).map(DemoScenario::id).toList());
    }

    public static List<String> ids() {
        return Arrays.stream(values()).map(DemoScenario::id).toList();
    }
}
