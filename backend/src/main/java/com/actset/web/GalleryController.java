package com.actset.web;

import com.actset.common.ApiException;
import com.actset.domain.GeneratedAsset;
import com.actset.domain.PerformanceSeed;
import com.actset.domain.Project;
import com.actset.repository.GeneratedAssetRepository;
import com.actset.repository.PerformanceSeedRepository;
import com.actset.repository.ProjectRepository;
import com.actset.service.GeneratedAssetService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * G-1·G-2 Gallery — DISCOVER 최소 구현(7-3·7-4, Stage 17). 인증 불필요, 공개 콘텐츠다.
 * visibility='public' AND status='active'인 프로젝트만 노출한다(Stage 10 idx_projects_gallery).
 *
 * <p>7-7(KOPIS)도 여기서 병합한다 — 초기에는 자체 공개 프로젝트가 적어 KOPIS 시드로
 * 보완한다(Stage 17 §4 부트스트래핑 전략). 항목마다 {@code source}(self|kopis)를 내려
 * 프런트가 출처를 구분 표시할 수 있게 한다(docs/14 1-4 출처 표기 의무 대응).
 */
@RestController
@RequestMapping("/api/v1/gallery")
public class GalleryController {

    private final ProjectRepository projectRepository;
    private final GeneratedAssetRepository generatedAssetRepository;
    private final GeneratedAssetService generatedAssetService;
    private final PerformanceSeedRepository performanceSeedRepository;

    public GalleryController(ProjectRepository projectRepository, GeneratedAssetRepository generatedAssetRepository,
                              GeneratedAssetService generatedAssetService,
                              PerformanceSeedRepository performanceSeedRepository) {
        this.projectRepository = projectRepository;
        this.generatedAssetRepository = generatedAssetRepository;
        this.generatedAssetService = generatedAssetService;
        this.performanceSeedRepository = performanceSeedRepository;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String genre,
                                      @RequestParam(defaultValue = "20") int limit) {
        int capped = Math.min(limit, 50);
        var pageable = PageRequest.of(0, capped, Sort.by(Sort.Direction.DESC, "publishedAt"));
        var projectPage = (genre == null || genre.isBlank())
                ? projectRepository.findByVisibilityAndStatusOrderByPublishedAtDesc("public", "active", pageable)
                : projectRepository.findByVisibilityAndStatusAndGenreOrderByPublishedAtDesc("public", "active", genre, pageable);

        var seedPageable = PageRequest.of(0, capped, Sort.by(Sort.Direction.DESC, "primaryDate"));
        var seedPage = (genre == null || genre.isBlank())
                ? performanceSeedRepository.findAllByOrderByPrimaryDateDesc(seedPageable)
                : performanceSeedRepository.findByGenreOrderByPrimaryDateDesc(genre, seedPageable);

        List<Map<String, Object>> items = new ArrayList<>();
        projectPage.getContent().forEach(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("project_id", p.getId().toString());
            m.put("source", "self");
            m.put("main_title", p.getMainTitle());
            m.put("genre", p.getGenre());
            m.put("primary_date", p.getPrimaryDate() != null ? p.getPrimaryDate().toString() : null);
            m.put("thumbnail_url", posterPreviewUrl(p.getId()));
            m.put("_sortDate", p.getPrimaryDate());
            items.add(m);
        });
        seedPage.getContent().forEach(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("project_id", s.getId().toString());
            m.put("source", "kopis");
            m.put("main_title", s.getMainTitle());
            m.put("genre", s.getGenre());
            m.put("primary_date", s.getPrimaryDate() != null ? s.getPrimaryDate().toString() : null);
            m.put("thumbnail_url", s.getPosterUrl());
            m.put("_sortDate", s.getPrimaryDate());
            items.add(m);
        });

        items.sort(Comparator.comparing((Map<String, Object> m) -> (LocalDate) m.get("_sortDate"),
                Comparator.nullsLast(Comparator.reverseOrder())));
        items.forEach(m -> m.remove("_sortDate"));
        List<Map<String, Object>> cappedItems = items.size() > capped ? items.subList(0, capped) : items;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", cappedItems);
        body.put("next_cursor", null);
        return body;
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable UUID id) {
        Optional<Project> project = projectRepository.findByIdAndVisibilityAndStatus(id, "public", "active");
        if (project.isPresent()) {
            Project p = project.get();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("project_id", p.getId().toString());
            body.put("source", "self");
            body.put("main_title", p.getMainTitle());
            body.put("genre", p.getGenre());
            body.put("primary_date", p.getPrimaryDate() != null ? p.getPrimaryDate().toString() : null);
            // 출연진·가격 등 민감할 수 있는 필드는 노출하지 않는다(Stage 4·15 확인필요 — venue만 우선 포함).
            body.put("venue_name", p.getPerformanceInfo().path("venue").path("name").asText(null));
            body.put("poster_preview_url", posterPreviewUrl(p.getId()));
            return body;
        }

        PerformanceSeed seed = performanceSeedRepository.findById(id).orElseThrow(ApiException::notFound);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("project_id", seed.getId().toString());
        body.put("source", "kopis");
        body.put("main_title", seed.getMainTitle());
        body.put("genre", seed.getGenre());
        body.put("primary_date", seed.getPrimaryDate() != null ? seed.getPrimaryDate().toString() : null);
        body.put("venue_name", seed.getVenueName());
        body.put("poster_preview_url", seed.getPosterUrl());
        return body;
    }

    private String posterPreviewUrl(UUID projectId) {
        Optional<GeneratedAsset> poster = generatedAssetRepository
                .findFirstByProjectIdAndCategoryAndDeletedAtIsNull(projectId, "포스터");
        return poster.map(a -> generatedAssetService.toSignedUrl(a.getPreviewImageUrl())).orElse(null);
    }
}
