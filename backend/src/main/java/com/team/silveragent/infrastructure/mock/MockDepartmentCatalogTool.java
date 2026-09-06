package com.team.silveragent.infrastructure.mock;

import com.team.silveragent.application.CareCatalogRepository;
import com.team.silveragent.domain.model.ToolModels.DepartmentProfile;
import com.team.silveragent.domain.tool.DepartmentCatalogTool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class MockDepartmentCatalogTool implements DepartmentCatalogTool {
    private final CareCatalogRepository catalog;
    private final ToolTraceStore traces;

    public MockDepartmentCatalogTool(CareCatalogRepository catalog, ToolTraceStore traces) {
        this.catalog = catalog;
        this.traces = traces;
    }

    @Override
    public List<DepartmentProfile> listDepartments(String conversationId, String hospitalId) {
        List<DepartmentProfile> result = catalog.departments(hospitalId).stream().map(this::profile).toList();
        traces.record(conversationId, "catalog.queryDepartments",
                Map.of("hospitalId", hospitalId), result, true);
        return result;
    }

    @Override
    public List<DepartmentProfile> searchDepartments(String conversationId, String keyword) {
        List<DepartmentProfile> result = catalog.searchDepartments(keyword).stream().map(this::profile).toList();
        traces.record(conversationId, "catalog.searchDepartments",
                Map.of("keyword", keyword), result, true);
        return result;
    }

    private DepartmentProfile profile(CareCatalogRepository.Department item) {
        return new DepartmentProfile(item.id(), item.hospitalId(), item.name(), item.description(),
                item.specialtyTags(), item.followupScope(), item.location());
    }
}
