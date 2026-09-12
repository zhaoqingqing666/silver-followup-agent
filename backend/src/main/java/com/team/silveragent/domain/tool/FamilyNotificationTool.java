package com.team.silveragent.domain.tool;

import com.team.silveragent.domain.model.ToolModels.Contact;

public interface FamilyNotificationTool {
    /**
     * 取本次会话对应用户的主联系人。没有配置联系人时返回 null，调用方据此提示“还没有配置家属联系人”，
     * 不是异常。这个查询不影响任何写操作，本身也不发通知。
     */
    Contact findPrimaryContact(String conversationId, String userId);

    String notify(String conversationId, String contactId, String message);
}
