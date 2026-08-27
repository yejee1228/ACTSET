package com.actset.web;

import com.actset.domain.Project;
import com.actset.security.CurrentUser;
import com.actset.service.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** docs/11 Stage 1 프로젝트. 목록·정보수정 등은 1-6·1-7에서 확장한다. */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
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
}
