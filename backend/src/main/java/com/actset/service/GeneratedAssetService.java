package com.actset.service;

import com.actset.common.ApiException;
import com.actset.domain.GeneratedAsset;
import com.actset.repository.GeneratedAssetRepository;
import com.actset.storage.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 생성 결과물 저장 공통 로직. 파일 저장 구조는 docs/09를 따른다.
 * projects/{project_id}/base|render|preview/{asset_id}.*
 */
@Service
public class GeneratedAssetService {

    private static final int PREVIEW_LONG_EDGE = 800;

    /** 후보함 상한(Stage 2·6·11·17) — 재생성(유료)과 구분되는 무과금 액션이다. */
    private static final int FAVORITE_LIMIT = 5;

    private final GeneratedAssetRepository generatedAssetRepository;
    private final StorageService storageService;

    public GeneratedAssetService(GeneratedAssetRepository generatedAssetRepository, StorageService storageService) {
        this.generatedAssetRepository = generatedAssetRepository;
        this.storageService = storageService;
    }

    @Transactional
    public GeneratedAsset saveCandidate(UUID projectId, String formatCode, int width, int height,
                                          int variantIndex, byte[] baseImage, BufferedImage rendered,
                                          JsonNode objectMap, JsonNode generationParams) {
        return saveCandidate(projectId, "시안후보", formatCode, width, height, variantIndex,
                baseImage, rendered, objectMap, generationParams);
    }

    /** category를 직접 지정하는 버전 — 규격변환(3-2)은 '규격변환'으로, 시안 생성(1-11)은 '시안후보'로 저장한다. */
    @Transactional
    public GeneratedAsset saveCandidate(UUID projectId, String category, String formatCode, int width, int height,
                                          int variantIndex, byte[] baseImage, BufferedImage rendered,
                                          JsonNode objectMap, JsonNode generationParams) {
        UUID assetId = UUID.randomUUID();
        String basePath = "projects/" + projectId + "/base/" + assetId + ".jpg";
        String renderPath = "projects/" + projectId + "/render/" + assetId + ".jpg";
        String previewPath = "projects/" + projectId + "/preview/" + assetId + ".jpg";

        storageService.store(baseImage, basePath, "image/jpeg");
        byte[] renderedBytes = toJpegBytes(rendered);
        storageService.store(renderedBytes, renderPath, "image/jpeg");
        byte[] previewBytes = toJpegBytes(downscale(rendered, PREVIEW_LONG_EDGE));
        storageService.store(previewBytes, previewPath, "image/jpeg");

        GeneratedAsset asset = new GeneratedAsset();
        asset.setId(assetId);
        asset.setProjectId(projectId);
        asset.setCategory(category);
        asset.setFormatCode(formatCode);
        asset.setWidth(width);
        asset.setHeight(height);
        asset.setVariantIndex((short) variantIndex);
        asset.setBaseImageUrl(basePath);
        asset.setImageUrl(renderPath);
        asset.setPreviewImageUrl(previewPath);
        asset.setObjectMap(objectMap);
        asset.setGenerationParams(generationParams);
        asset.setAutoSyncText(false);
        asset.setStatus("제안됨");
        asset.setFileSize((long) renderedBytes.length);
        java.time.Instant now = java.time.Instant.now();
        asset.setInfoSyncedAt(now);
        asset.setDesignSyncedAt(now);
        return generatedAssetRepository.save(asset);
    }

    /**
     * 같은 GeneratedAsset 레코드의 render·preview 파일만 교체한다(4-4 포스터 자동반영,
     * 4-5 최신 반영 공통 — docs/02 "새 레코드가 생기지 않고 교체됨"). 경로가 asset id
     * 기준으로 고정돼 있어 다시 저장하면 곧 이전 파일 교체다.
     */
    @Transactional
    public void updateRenderInPlace(GeneratedAsset asset, BufferedImage rendered, JsonNode objectMap) {
        String renderPath = "projects/" + asset.getProjectId() + "/render/" + asset.getId() + ".jpg";
        String previewPath = "projects/" + asset.getProjectId() + "/preview/" + asset.getId() + ".jpg";

        byte[] renderedBytes = toJpegBytes(rendered);
        storageService.store(renderedBytes, renderPath, "image/jpeg");
        storageService.store(toJpegBytes(downscale(rendered, PREVIEW_LONG_EDGE)), previewPath, "image/jpeg");

        asset.setImageUrl(renderPath);
        asset.setPreviewImageUrl(previewPath);
        asset.setObjectMap(objectMap);
        asset.setFileSize((long) renderedBytes.length);
        asset.setInfoSyncedAt(java.time.Instant.now());
        generatedAssetRepository.save(asset);
    }

    /** preview_image_url 등 저장 경로(raw)를 서명 URL로 바꿔 API 응답에 담을 때 쓴다. */
    public String toSignedUrl(String storedPath) {
        if (storedPath == null) return null;
        return storageService.signedUrl(storedPath, Duration.ofHours(1));
    }

    /**
     * 후보함 즐겨찾기 추가·해제(7-5, Stage 2·17). 무과금 — CreditService를 거치지 않는다.
     * 5개 초과 시도는 409로 거부하고, 해제는 제한과 무관하게 항상 허용한다.
     */
    @Transactional
    public GeneratedAsset setFavorited(GeneratedAsset asset, boolean favorited) {
        if (favorited && !asset.isFavorited()) {
            long count = generatedAssetRepository.countByProjectIdAndFavoritedTrueAndDeletedAtIsNull(asset.getProjectId());
            if (count >= FAVORITE_LIMIT) {
                throw new ApiException(HttpStatus.CONFLICT, "FAVORITE_LIMIT_EXCEEDED",
                        "후보함은 최대 5개까지 담을 수 있습니다.", Map.of("limit", FAVORITE_LIMIT));
            }
        }
        asset.setFavorited(favorited);
        return generatedAssetRepository.save(asset);
    }

    private BufferedImage downscale(BufferedImage src, int longEdge) {
        int w = src.getWidth(), h = src.getHeight();
        double scale = longEdge / (double) Math.max(w, h);
        if (scale >= 1.0) return src;
        int nw = (int) Math.round(w * scale), nh = (int) Math.round(h * scale);
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    private byte[] toJpegBytes(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
