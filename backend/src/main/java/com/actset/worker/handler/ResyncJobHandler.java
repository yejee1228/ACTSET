package com.actset.worker.handler;

import com.actset.common.ApiException;
import com.actset.domain.GeneratedAsset;
import com.actset.domain.Job;
import com.actset.domain.Project;
import com.actset.domain.UploadedFile;
import com.actset.render.PerformanceInfoTextMapper;
import com.actset.render.PhotoLayerRenderer;
import com.actset.render.PosterTextRenderer;
import com.actset.render.TextBlockSpec;
import com.actset.repository.GeneratedAssetRepository;
import com.actset.repository.ProjectRepository;
import com.actset.repository.UploadedFileRepository;
import com.actset.service.GeneratedAssetService;
import com.actset.storage.StorageService;
import com.actset.worker.JobHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * jobs.kind = 'resync' — 포스터 자동반영(4-4)과 "최신 반영"(4-5)의 텍스트/사진 갱신 부분.
 * 배경(base_image)은 그대로 두고 PHOTO·TITLE·INFO 레이어만 다시 합성한다(docs/02) —
 * 생성 API를 부르지 않으므로 무과금이다(docs/06).
 *
 * 지금은 포스터(category=포스터) 1건만 대상으로 한다. 4-5(규격변환 결과물까지 포함한
 * 일괄 "최신 반영")는 대상 확장이 필요해 이번 라운드 범위 밖으로 남겨둔다(OVERNIGHT-LOG).
 */
@Component
public class ResyncJobHandler implements JobHandler {

    private static final double[] TITLE_AREA = {0.08, 0.60, 0.92, 0.66};
    private static final double[] INFO_AREA = {0.08, 0.60, 0.92, 0.95};
    private static final Rectangle PHOTO_AREA_PX = new Rectangle(420, 140, 400, 400);

    private final ProjectRepository projectRepository;
    private final GeneratedAssetRepository generatedAssetRepository;
    private final UploadedFileRepository uploadedFileRepository;
    private final StorageService storageService;
    private final PerformanceInfoTextMapper textMapper;
    private final PosterTextRenderer posterTextRenderer;
    private final PhotoLayerRenderer photoLayerRenderer;
    private final GeneratedAssetService generatedAssetService;
    private final ObjectMapper objectMapper;

    public ResyncJobHandler(ProjectRepository projectRepository, GeneratedAssetRepository generatedAssetRepository,
                             UploadedFileRepository uploadedFileRepository, StorageService storageService,
                             PerformanceInfoTextMapper textMapper, PosterTextRenderer posterTextRenderer,
                             PhotoLayerRenderer photoLayerRenderer, GeneratedAssetService generatedAssetService,
                             ObjectMapper objectMapper) {
        this.projectRepository = projectRepository;
        this.generatedAssetRepository = generatedAssetRepository;
        this.uploadedFileRepository = uploadedFileRepository;
        this.storageService = storageService;
        this.textMapper = textMapper;
        this.posterTextRenderer = posterTextRenderer;
        this.photoLayerRenderer = photoLayerRenderer;
        this.generatedAssetService = generatedAssetService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String kind() {
        return "resync";
    }

    @Override
    public ObjectNode handle(Job job) throws Exception {
        Project project = projectRepository.findById(job.getProjectId()).orElseThrow(ApiException::notFound);
        GeneratedAsset poster = generatedAssetRepository
                .findFirstByProjectIdAndCategoryAndDeletedAtIsNull(project.getId(), "포스터")
                .orElseThrow(ApiException::notFound);

        BufferedImage backdrop = ImageIO.read(new ByteArrayInputStream(storageService.read(poster.getBaseImageUrl())));
        BufferedImage composed = copy(backdrop);

        JsonNode info = project.getPerformanceInfo();
        List<UploadedFile> photoFiles = uploadedFileRepository.findByProjectId(project.getId()).stream()
                .filter(f -> "cast_photo".equals(f.getKind()) || "performance_photo".equals(f.getKind()))
                .toList();

        ObjectNode photoMap = objectMapper.createObjectNode();
        if (!photoFiles.isEmpty()) {
            UploadedFile photo = photoFiles.get(0);
            BufferedImage photoImg = ImageIO.read(new ByteArrayInputStream(storageService.read(photo.getStoragePath())));
            Graphics2D pg = composed.createGraphics();
            photoLayerRenderer.render(pg, List.of(new PhotoLayerRenderer.PhotoPlacement(
                    "cast_photo_1", photo.getId(), photoImg, PHOTO_AREA_PX, PhotoLayerRenderer.Mask.CIRCLE)), photoMap);
            pg.dispose();
        }

        String title = textMapper.title(info);
        List<TextBlockSpec> infoBlocks = textMapper.infoBlocks(info);
        PosterTextRenderer.Result textResult = posterTextRenderer.render(composed, title, infoBlocks, TITLE_AREA, INFO_AREA);
        ObjectNode combinedMap = photoMap.deepCopy();
        combinedMap.setAll(textResult.objectMap());

        generatedAssetService.updateRenderInPlace(poster, textResult.image(), combinedMap);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("asset_id", poster.getId().toString());
        return result;
    }

    private BufferedImage copy(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }
}
