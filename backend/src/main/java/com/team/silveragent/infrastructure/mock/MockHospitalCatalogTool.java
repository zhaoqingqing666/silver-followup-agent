package com.team.silveragent.infrastructure.mock;

import com.team.silveragent.application.care.CareCatalogRepository;
import com.team.silveragent.domain.model.ToolModels.HospitalProfile;
import com.team.silveragent.domain.tool.HospitalCatalogTool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class MockHospitalCatalogTool implements HospitalCatalogTool {
    private final CareCatalogRepository catalog;
    private final ToolTraceStore traces;

    public MockHospitalCatalogTool(CareCatalogRepository catalog, ToolTraceStore traces) {
        this.catalog = catalog;
        this.traces = traces;
    }

    @Override
    public List<HospitalProfile> listHospitals(String conversationId) {
        List<HospitalProfile> result = catalog.hospitals().stream().map(this::profile).toList();
        traces.record(conversationId, "catalog.queryHospitals", Map.of("enabled", true), result, true);
        return result;
    }

    @Override
    public List<HospitalProfile> findHospitalsForDepartment(String conversationId, String departmentName) {
        List<HospitalProfile> result = catalog.hospitalsForDepartment(departmentName).stream()
                .map(this::profile).toList();
        traces.record(conversationId, "catalog.recommendHospitals",
                Map.of("department", departmentName), result, true);
        return result;
    }

    private HospitalProfile profile(CareCatalogRepository.Hospital item) {
        return new HospitalProfile(item.id(), item.name(), item.level(), item.address(),
                item.description(), item.specialtyTags(), item.elderlyServices());
    }
}
