package com.team.silveragent.domain.tool;

import com.team.silveragent.domain.model.ToolModels.FacilityGuide;

/** 读取经过维护的院内楼栋、楼层、诊室和无障碍指引。 */
public interface FacilityGuideTool {
    FacilityGuide find(String conversationId, String locationId,
                       String hospitalId, String departmentId);
}
