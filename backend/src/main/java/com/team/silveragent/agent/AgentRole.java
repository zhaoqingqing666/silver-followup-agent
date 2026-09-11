package com.team.silveragent.agent;

/**
 * 会话操作者身份：老人本人、家属、社区志愿者。
 * 这个身份由后端在建立会话时根据 {@code care_relations} 判定并固定到会话里，
 * 既不由模型输出，也不直接采信前端传入的角色字段。
 */
public enum AgentRole {
    ELDER, FAMILY, VOLUNTEER;

    /** 把关系表里的 role 列转成会话身份；认不出来的一律按“未授权”处理。 */
    public static AgentRole fromRelationRole(String value) {
        if (value == null) return null;
        return switch (value.trim().toUpperCase()) {
            case "FAMILY" -> FAMILY;
            case "VOLUNTEER" -> VOLUNTEER;
            case "ELDER" -> ELDER;
            default -> null;
        };
    }

    /** 替别人办理的身份：话术、可见工具与写操作权限都按“代他人办”处理。 */
    public boolean isCaregiver() { return this != ELDER; }
}
