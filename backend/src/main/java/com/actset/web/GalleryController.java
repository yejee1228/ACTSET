package com.actset.web;

import com.actset.common.ApiException;
import com.actset.domain.GeneratedAsset;
import com.actset.domain.PerformanceSeed;
import com.actset.domain.Project;
import com.actset.external.kopis.KopisGenres;
import com.actset.repository.GeneratedAssetRepository;
import com.actset.repository.PerformanceSeedRepository;
import com.actset.repository.ProjectRepository;
import com.actset.service.GeneratedAssetService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
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
    /** 목록 앞에 붙이는 자체 공개 프로젝트 최대 수. */
    static final int SELF_CAP = 200;

    public GalleryController(ProjectRepository projectRepository, GeneratedAssetRepository generatedAssetRepository,
                              GeneratedAssetService generatedAssetService,
                              PerformanceSeedRepository performanceSeedRepository) {
        this.projectRepository = projectRepository;
        this.generatedAssetRepository = generatedAssetRepository;
        this.generatedAssetService = generatedAssetService;
        this.performanceSeedRepository = performanceSeedRepository;
    }

    /**
     * 자체 공개 프로젝트를 먼저, 이어서 끝나지 않은 KOPIS 공연(공연중 → 공연예정 순)을 이어 붙인 목록의 offset~offset+limit.
     * 자체 공개 프로젝트는 아직 적어서(초기 부트스트래핑 단계) 한 번에 SELF_CAP건까지만 앞에 둔다.
     */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String genre,
                                      @RequestParam(defaultValue = "24") int limit,
                                      @RequestParam(defaultValue = "0") int offset) {
        int capped = Math.max(1, Math.min(limit, 60));
        int from = Math.max(0, offset);
        boolean anyGenre = genre == null || genre.isBlank();
        var selfPage = PageRequest.of(0, SELF_CAP, Sort.by(Sort.Direction.DESC, "publishedAt"));
        List<Project> self = (anyGenre
                ? projectRepository.findByVisibilityAndStatusOrderByPublishedAtDesc("public", "active", selfPage)
                : projectRepository.findByVisibilityAndStatusAndGenreOrderByPublishedAtDesc("public", "active", genre, selfPage))
                .getContent();

        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = from; i < self.size() && items.size() < capped; i++) {
            Project p = self.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("project_id", p.getId().toString());
            m.put("source", "self");
            m.put("main_title", p.getMainTitle());
            m.put("genre", p.getGenre());
            m.put("primary_date", p.getPrimaryDate() != null ? p.getPrimaryDate().toString() : null);
            m.put("end_date", null);
            m.put("venue_name", p.getPerformanceInfo().path("venue").path("name").asText(null));
            m.put("thumbnail_url", posterPreviewUrl(p.getId()));
            items.add(m);
        }
        int seedOffset = Math.max(0, from - self.size()), seedLimit = capped - items.size();
        boolean kids = KopisGenres.KIDS.equals(genre);
        // 한 건 더 받아 다음 쪽이 있는지 본다
        List<PerformanceSeed> seeds = performanceSeedRepository.findActive(LocalDate.now(),
                anyGenre || kids ? null : genre, kids, seedLimit + 1, seedOffset);
        boolean more = seeds.size() > seedLimit;
        for (PerformanceSeed s : seeds.subList(0, Math.min(seedLimit, seeds.size()))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("project_id", s.getId().toString());
            m.put("source", "kopis");
            m.put("main_title", s.getMainTitle());
            m.put("genre", s.getGenre());
            m.put("primary_date", s.getPrimaryDate() != null ? s.getPrimaryDate().toString() : null);
            m.put("end_date", s.getEndDate() != null ? s.getEndDate().toString() : null);
            m.put("venue_name", s.getVenueName());
            m.put("thumbnail_url", s.getThumbnailPath() != null
                    ? generatedAssetService.toSignedUrl(s.getThumbnailPath()) : s.getPosterUrl());
            items.add(m);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("next_offset", more || from + items.size() < self.size() ? from + items.size() : null);
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
        body.put("end_date", seed.getEndDate() != null ? seed.getEndDate().toString() : null);
        body.put("state", seed.getState());
        body.put("area", seed.getArea());
        body.put("kopis_genre", seed.getKopisGenre());
        // 상세 API 항목(출연진·가격·소개 이미지·예매처). KOPIS가 공개한 정보이므로 그대로 보여 준다
        body.put("detail", seed.getDetail());
        return body;
    }

    private String posterPreviewUrl(UUID projectId) {
        Optional<GeneratedAsset> poster = generatedAssetRepository
                .findFirstByProjectIdAndCategoryAndDeletedAtIsNull(projectId, "포스터");
        return poster.map(a -> generatedAssetService.toSignedUrl(a.getPreviewImageUrl())).orElse(null);
    }
}
