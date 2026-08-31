package com.actset.web;

import com.actset.common.ApiException;
import com.actset.domain.GeneratedAsset;
import com.actset.domain.Project;
import com.actset.repository.GeneratedAssetRepository;
import com.actset.security.CurrentUser;
import com.actset.service.AssetSelectionService;
import com.actset.service.GeneratedAssetService;
import com.actset.service.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** docs/11 "4. 결과물" — GET /projects/{id}/assets, POST /assets/{id}/select. */
@RestController
public class AssetController {

    private final ProjectService projectService;
    private final GeneratedAssetRepository generatedAssetRepository;
    private final GeneratedAssetService generatedAssetService;
    private final AssetSelectionService assetSelectionService;

    public AssetController(ProjectService projectService, GeneratedAssetRepository generatedAssetRepository,
                            GeneratedAssetService generatedAssetService, AssetSelectionService assetSelectionService) {
        this.projectService = projectService;
        this.generatedAssetRepository = generatedAssetRepository;
        this.generatedAssetService = generatedAssetService;
        this.assetSelectionService = assetSelectionService;
    }

    @PostMapping("/api/v1/assets/{id}/select")
    public ResponseEntity<Void> select(@PathVariable UUID id) {
        assetSelectionService.select(id, CurrentUser.id());
        return ResponseEntity.noContent().build();
    }

    /** 4-6 개별 삭제 — 소프트 삭제, 30일 뒤 하드 삭제(4-8, 미착수). 포스터는 삭제 불가(docs/11). */
    @DeleteMapping("/api/v1/assets/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        GeneratedAsset asset = generatedAssetRepository.findById(id).orElseThrow(ApiException::notFound);
        projectService.getOwned(asset.getProjectId(), CurrentUser.id()); // 소유자 확인
        if ("포스터".equals(asset.getCategory())) {
            throw new ApiException(HttpStatus.CONFLICT, "POSTER_NOT_DELETABLE", "포스터는 삭제할 수 없습니다.");
        }
        asset.setStatus("삭제됨");
        asset.setDeletedAt(Instant.now());
        generatedAssetRepository.save(asset);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/projects/{id}/assets")
    public Map<String, Object> list(@PathVariable UUID id, @RequestParam(required = false) String category) {
        Project project = projectService.getOwned(id, CurrentUser.id());
        List<GeneratedAsset> assets = generatedAssetRepository.findByProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(project.getId());

        List<Map<String, Object>> items = assets.stream()
                .filter(a -> category == null || category.equals(a.getCategory()))
                .map(a -> toItem(a, project))
                .toList();
        return Map.of("items", items);
    }

    private Map<String, Object> toItem(GeneratedAsset a, Project project) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId().toString());
        m.put("category", a.getCategory());
        m.put("format_code", a.getFormatCode());
        m.put("width", a.getWidth());
        m.put("height", a.getHeight());
        m.put("variant_index", a.getVariantIndex());
        m.put("preview_image_url", generatedAssetService.toSignedUrl(a.getPreviewImageUrl()));
        boolean downloadable = a.getImageUrl() != null
                && (a.getDownloadExpiresAt() == null || a.getDownloadExpiresAt().isAfter(java.time.Instant.now()));
        m.put("image_url", downloadable ? generatedAssetService.toSignedUrl(a.getImageUrl()) : null);
        m.put("downloadable", downloadable);
        m.put("status", a.getStatus());
        boolean infoStale = project.getInfoUpdatedAt() != null
                && (a.getInfoSyncedAt() == null || project.getInfoUpdatedAt().isAfter(a.getInfoSyncedAt()));
        boolean designStale = project.getDesignUpdatedAt() != null
                && (a.getDesignSyncedAt() == null || project.getDesignUpdatedAt().isAfter(a.getDesignSyncedAt()));
        m.put("stale", Map.of("info", infoStale, "design", designStale));
        return m;
    }
}
