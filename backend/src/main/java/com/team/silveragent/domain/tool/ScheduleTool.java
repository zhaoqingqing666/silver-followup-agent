package com.team.silveragent.domain.tool;

import com.team.silveragent.domain.model.ToolModels.Conflict;
import java.time.LocalDateTime;
import java.util.List;

public interface ScheduleTool {
    List<Conflict> findConflicts(String conversationId, String userId, LocalDateTime start, LocalDateTime end);
    String createReminder(String conversationId, String userId, String title, LocalDateTime remindAt);
}
