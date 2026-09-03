package com.team.silveragent.domain.tool;

import com.team.silveragent.domain.model.ToolModels.Contact;

public interface FamilyNotificationTool {
    Contact findPrimaryContact(String conversationId, String userId);
    String notify(String conversationId, String contactId, String message);
}
