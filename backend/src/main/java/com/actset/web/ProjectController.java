package com.actset.web;

import com.actset.domain.GeneratedAsset;
import com.actset.domain.Project;
import com.actset.repository.GeneratedAssetRepository;
import com.actset.security.CurrentUser;
import com.actset.service.ConfirmService;
import com.actset.service.GeneratedAssetService;
import com.actset.service.ProjectInfoService;
import com.actset.service.ProjectService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** docs/11 Stage 1 프로젝트. */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectInfoService projectInfoService;
    private final ConfirmService confirmService;
    private final com.actset.repository.ProjectRepository projectRepository;
    private final GeneratedAssetRepository generatedAssetRepository;
    private final GeneratedAssetService generatedAssetService;

    public ProjectController(ProjectService projectService, ProjectInfoService projectInfoService,
                              ConfirmService confirmService, com.actset.repository.ProjectRepository projectRepository,
                              GeneratedAssetRepository generatedAssetRepository,
                              GeneratedAssetService generatedAssetService) {
        this.projectService = projectService;
        this.projectInfoService = projectInfoService;
        this.confirmService = confirmService;
        this.projectRepository = projectRepository;
        this.generatedAssetRepository = generatedAssetRepository;
        this.generatedAssetService = generatedAssetService;
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

    /** 0-B 홈 대시보드(1-6) — active 프로젝트만 노출한다. */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String q,
                                      @RequestParam(defaultValue = "20") int limit) {
        UUID ownerId = CurrentUser.id();
        var pageable = PageRequest.of(0, Math.min(limit, 50), Sort.by(Sort.Direction.DESC, "updatedAt"));
        var page = (q == null || q.isBlank())
                ? projectRepository.findByOwnerIdAndStatusOrderByUpdatedAtDesc(ownerId, "active", pageable)
                : projectRepository.findByOwnerIdAndStatusAndMainTitleContainingIgnoreCaseOrderByUpdatedAtDesc(
                        ownerId, "active", q, pageable);

        List<Map<String, Object>> items = page.getContent().stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId().toString());
            m.put("main_title", p.getMainTitle());
            m.put("genre", p.getGenre());
            m.put("primary_date", p.getPrimaryDate() != null ? p.getPrimaryDate().toString() : null);
            m.put("date_undetermined", p.isDateUndetermined());
            Optional<GeneratedAsset> poster = generatedAssetRepository
                    .findFirstByProjectIdAndCategoryAndDeletedAtIsNull(p.getId(), "포스터");
            m.put("thumbnail_url", poster.map(a -> generatedAssetService.toSignedUrl(a.getPreviewImageUrl())).orElse(null));
            m.put("updated_at", p.getUpdatedAt().toString());
            return m;
        }).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("next_cursor", null);
        return body;
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
        List<GeneratedAsset> assets = generatedAssetRepository.findByProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(project.getId());
        long staleInfo = assets.stream().filter(a -> project.getInfoUpdatedAt() != null
                && (a.getInfoSyncedAt() == null || project.getInfoUpdatedAt().isAfter(a.getInfoSyncedAt()))).count();
        long staleDesign = assets.stream().filter(a -> project.getDesignUpdatedAt() != null
                && (a.getDesignSyncedAt() == null || project.getDesignUpdatedAt().isAfter(a.getDesignSyncedAt()))).count();

        Map<String, Object> flags = new LinkedHashMap<>();
        flags.put("date_undetermined", project.isDateUndetermined());
        flags.put("venue_undetermined", project.isVenueUndetermined());
        flags.put("stale_info_count", staleInfo);
        flags.put("stale_design_count", staleDesign);
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

    /** 0-B 카드 메뉴 → 삭제(4-9). 소프트 삭제, 30일 후 배치가 하드 삭제(4-8, 미착수). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        Project project = projectService.getOwned(id, CurrentUser.id());
        project.setStatus("deleted");
        project.setDeletedAt(Instant.now());
        project.setUpdatedAt(Instant.now());
        projectRepository.save(project);
        return ResponseEntity.noContent().build();
    }
}
