package com.team.silveragent.domain.tool;

import com.team.silveragent.domain.model.ToolModels.RouteGuide;

import java.time.LocalDateTime;

/** 读取院外路线事实，不负责创建提醒或修改预约。 */
public interface RouteGuideTool {
    RouteGuide plan(String conversationId, String userId, String hospitalId,
                    LocalDateTime appointmentAt, String transport);
}
