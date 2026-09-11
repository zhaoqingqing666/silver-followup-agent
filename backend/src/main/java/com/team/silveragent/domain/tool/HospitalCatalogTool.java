package com.team.silveragent.domain.tool;

import com.team.silveragent.domain.model.ToolModels.HospitalProfile;

import java.util.List;

public interface HospitalCatalogTool {
    List<HospitalProfile> listHospitals(String conversationId);
    List<HospitalProfile> findHospitalsForDepartment(String conversationId, String departmentName);
}
