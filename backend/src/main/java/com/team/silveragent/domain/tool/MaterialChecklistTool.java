package com.team.silveragent.domain.tool;

import java.util.List;

public interface MaterialChecklistTool {
    List<String> checklist(String conversationId, String hospital, String department);
}
