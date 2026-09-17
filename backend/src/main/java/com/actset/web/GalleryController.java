package com.actset.web;

import com.actset.common.ApiException;
import com.actset.domain.GeneratedAsset;
import com.actset.domain.Project;
import com.actset.repository.GeneratedAssetRepository;
import com.actset.repository.ProjectRepository;
import com.actset.service.GeneratedAssetService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * G-1·G-2 Gallery — DISCOVER 최소 구현(7-3·7-4, Stage 17). 인증 불필요, 공개 콘텐츠다.
 * visibility='public' AND status='active'인 프로젝트만 노출한다(Stage 10 idx_projects_gallery).
 */
@RestController
@RequestMapping("/api/v1/gallery")
public class GalleryController {

    private final ProjectRepository projectRepository;
    private final GeneratedAssetRepository generatedAssetRepository;
    private final GeneratedAssetService generatedAssetService;

    public GalleryController(ProjectRepository projectRepository, GeneratedAssetRepository generatedAssetRepository,
                              GeneratedAssetService generatedAssetService) {
        this.projectRepository = projectRepository;
        this.generatedAssetRepository = generatedAssetRepository;
        this.generatedAssetService = generatedAssetService;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String genre,
                                      @RequestParam(defaultValue = "20") int limit) {
        var pageable = PageRequest.of(0, Math.min(limit, 50), Sort.by(Sort.Direction.DESC, "publishedAt"));
        var page = (genre == null || genre.isBlank())
                ? projectRepository.findByVisibilityAndStatusOrderByPublishedAtDesc("public", "active", pageable)
                : projectRepository.findByVisibilityAndStatusAndGenreOrderByPublishedAtDesc("public", "active", genre, pageable);

        List<Map<String, Object>> items = page.getContent().stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("project_id", p.getId().toString());
            m.put("main_title", p.getMainTitle());
            m.put("genre", p.getGenre());
            m.put("primary_date", p.getPrimaryDate() != null ? p.getPrimaryDate().toString() : null);
            m.put("thumbnail_url", posterPreviewUrl(p.getId()));
            return m;
        }).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("next_cursor", null);
        return body;
    }

    @GetMapping("/{projectId}")
    public Map<String, Object> detail(@PathVariable UUID projectId) {
        Project project = projectRepository.findByIdAndVisibilityAndStatus(projectId, "public", "active")
                .orElseThrow(ApiException::notFound);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("project_id", project.getId().toString());
        body.put("main_title", project.getMainTitle());
        body.put("genre", project.getGenre());
        body.put("primary_date", project.getPrimaryDate() != null ? project.getPrimaryDate().toString() : null);
        // 출연진·가격 등 민감할 수 있는 필드는 노출하지 않는다(Stage 4·15 확인필요 — venue만 우선 포함).
        body.put("venue_name", project.getPerformanceInfo().path("venue").path("name").asText(null));
        body.put("poster_preview_url", posterPreviewUrl(project.getId()));
        return body;
    }

    private String posterPreviewUrl(UUID projectId) {
        Optional<GeneratedAsset> poster = generatedAssetRepository
                .findFirstByProjectIdAndCategoryAndDeletedAtIsNull(projectId, "포스터");
        return poster.map(a -> generatedAssetService.toSignedUrl(a.getPreviewImageUrl())).orElse(null);
    }
}
