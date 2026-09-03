package com.team.silveragent.domain.tool;

import com.team.silveragent.domain.model.ToolModels.TravelPlan;
import java.time.LocalDateTime;

public interface TravelTool {
    TravelPlan plan(String conversationId, String userId, String hospital,
                    LocalDateTime appointmentAt, String transport);
}
