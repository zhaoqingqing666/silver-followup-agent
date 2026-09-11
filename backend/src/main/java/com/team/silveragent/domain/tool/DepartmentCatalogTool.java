package com.team.silveragent.domain.tool;

import com.team.silveragent.domain.model.ToolModels.DepartmentProfile;

import java.util.List;

public interface DepartmentCatalogTool {
    List<DepartmentProfile> listDepartments(String conversationId, String hospitalId);
}
