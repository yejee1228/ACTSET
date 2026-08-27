package com.actset.web;

import com.actset.domain.Project;
import com.actset.security.CurrentUser;
import com.actset.service.ConfirmService;
import com.actset.service.ProjectInfoService;
import com.actset.service.ProjectService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** docs/11 Stage 1 프로젝트. 목록 등은 1-6에서 확장한다. */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectInfoService projectInfoService;
    private final ConfirmService confirmService;

    public ProjectController(ProjectService projectService, ProjectInfoService projectInfoService,
                              ConfirmService confirmService) {
        this.projectService = projectService;
        this.projectInfoService = projectInfoService;
        this.confirmService = confirmService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create() {
        Project project = projectService.createDraft(CurrentUser.id());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", project.getId().toString());
        body.put("status", project.getStatus());
        body.put("created_at", project.getCreatedAt().toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        Project project = projectService.getOwned(id, CurrentUser.id());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", project.getId().toString());
        body.put("status", project.getStatus());
        body.put("main_title", project.getMainTitle());
        body.put("genre", project.getGenre());
        body.put("performance_info", project.getPerformanceInfo());
        body.put("design_assets", project.getDesignAssets());
        Map<String, Object> flags = new LinkedHashMap<>();
        flags.put("date_undetermined", project.isDateUndetermined());
        flags.put("venue_undetermined", project.isVenueUndetermined());
        body.put("flags", flags);
        return body;
    }

    @PatchMapping("/{id}/info")
    public Map<String, Object> updateInfo(@PathVariable UUID id, @RequestBody JsonNode partial) {
        Project project = projectService.getOwned(id, CurrentUser.id());
        ProjectInfoService.UpdateResult result = projectInfoService.update(project, partial);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("updated_at", result.updatedAt().toString());
        if (result.posterResyncJobId() != null) {
            body.put("poster_resync", Map.of("job_id", result.posterResyncJobId().toString()));
        } else {
            body.put("poster_resync", Map.of());
        }
        return body;
    }

    public record ConfirmRequest(UUID selected_candidate_id) {
    }

    @PostMapping("/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable UUID id, @RequestBody ConfirmRequest req) {
        ConfirmService.ConfirmResult result = confirmService.confirm(id, CurrentUser.id(), req.selected_candidate_id());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "active");
        body.put("poster_asset_id", result.posterAssetId().toString());
        body.put("confirmed_at", result.confirmedAt().toString());
        return body;
    }
}
