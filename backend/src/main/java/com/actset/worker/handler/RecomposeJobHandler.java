package com.actset.worker.handler;

import com.actset.common.ApiException;
import com.actset.domain.GeneratedAsset;
import com.actset.domain.Job;
import com.actset.domain.Project;
import com.actset.domain.UploadedFile;
import com.actset.format.FormatPreset;
import com.actset.format.RatioBucket;
import com.actset.format.TempFormatLayout;
import com.actset.render.ImageFit;
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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * jobs.kind = 'recompose' — ⑥ 규격 일괄변환의 규격 1건(1-9의 하위 job 분해 대상, 3-2·3-4).
 *
 * 저장된 레이어를 재배치만 한다(분해를 다시 하지 않는다 — docs/05). 다만 1-15가 현재
 * 목업(통짜 BACKDROP 1장, SUBJECT·DECOR 없음)이라 "피사체를 잘라내지 않고 이동"은 아직
 * 실현할 수 없다 — 배경 전체를 대상 비율로 크롭·리사이즈하는 것으로 대체한다. 실제
 * Qwen 분해가 들어오면 SUBJECT를 별도로 이동·배치하는 로직을 추가해야 한다(OVERNIGHT-LOG).
 * 가로로 크게 늘어나는 규격(WIDE 이상)은 진짜 아웃페인팅(3-3, 목업 불가)이 없어 크롭만
 * 적용되므로 키비주얼이 잘릴 수 있다 — generation_params에 이 사실을 기록해둔다.
 */
@Component
public class RecomposeJobHandler implements JobHandler {

    private static final Rectangle PHOTO_AREA_RATIO_BASE = new Rectangle(420, 140, 400, 400); // POSTER 기준 임시값

    private final ProjectRepository projectRepository;
    private final UploadedFileRepository uploadedFileRepository;
    private final StorageService storageService;
    private final PerformanceInfoTextMapper textMapper;
    private final PosterTextRenderer posterTextRenderer;
    private final PhotoLayerRenderer photoLayerRenderer;
    private final GeneratedAssetService generatedAssetService;
    private final ObjectMapper objectMapper;

    public RecomposeJobHandler(ProjectRepository projectRepository, UploadedFileRepository uploadedFileRepository,
                                StorageService storageService, PerformanceInfoTextMapper textMapper,
                                PosterTextRenderer posterTextRenderer, PhotoLayerRenderer photoLayerRenderer,
                                GeneratedAssetService generatedAssetService, ObjectMapper objectMapper) {
        this.projectRepository = projectRepository;
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
        return "recompose";
    }

    @Override
    public ObjectNode handle(Job job) throws Exception {
        Project project = projectRepository.findById(job.getProjectId()).orElseThrow(ApiException::notFound);
        JsonNode payload = job.getPayload();
        String formatCode = payload.path("format_code").asText();
        int width = payload.path("width").asInt();
        int height = payload.path("height").asInt();
        int variants = Math.max(payload.path("variants").asInt(1), 1);

        JsonNode designAssets = project.getDesignAssets();
        if (designAssets == null || !designAssets.has("visual_layers") || designAssets.get("visual_layers").isEmpty()) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,
                    "NO_DESIGN_ASSETS", "확정된 원본(포스터)이 없어 규격 변환을 할 수 없습니다.");
        }
        String backdropPath = designAssets.get("visual_layers").get(0).path("image_url").asText();
        BufferedImage backdropSource = ImageIO.read(new ByteArrayInputStream(storageService.read(backdropPath)));

        RatioBucket bucket = RatioBucket.fromDimensions(width, height);
        TempFormatLayout.Areas areas = TempFormatLayout.forBucket(bucket);
        boolean outpaintingNeeded = bucket == RatioBucket.WIDE || bucket == RatioBucket.EXTRA_WIDE || bucket == RatioBucket.ULTRA_WIDE;

        JsonNode info = project.getPerformanceInfo();
        String title = textMapper.title(info);
        List<TextBlockSpec> infoBlocks = textMapper.infoBlocks(info);
        List<UploadedFile> photoFiles = uploadedFileRepository.findByProjectId(project.getId()).stream()
                .filter(f -> "cast_photo".equals(f.getKind()) || "performance_photo".equals(f.getKind()))
                .toList();

        ArrayNode assetIds = objectMapper.createArrayNode();
        for (int i = 0; i < variants; i++) {
            BufferedImage backdrop = ImageFit.coverFit(backdropSource, width, height);
            BufferedImage composed = copy(backdrop);

            ObjectNode photoMap = objectMapper.createObjectNode();
            if (!photoFiles.isEmpty()) {
                UploadedFile photo = photoFiles.get(0);
                BufferedImage photoImg = ImageIO.read(new ByteArrayInputStream(storageService.read(photo.getStoragePath())));
                Rectangle photoArea = scalePhotoArea(width, height);
                Graphics2D pg = composed.createGraphics();
                photoLayerRenderer.render(pg, List.of(new PhotoLayerRenderer.PhotoPlacement(
                        "cast_photo_1", photo.getId(), photoImg, photoArea, PhotoLayerRenderer.Mask.CIRCLE)), photoMap);
                pg.dispose();
            }

            PosterTextRenderer.Result textResult = posterTextRenderer.render(composed, title, infoBlocks, areas.title(), areas.info());
            ObjectNode combinedMap = photoMap.deepCopy();
            combinedMap.setAll(textResult.objectMap());

            ObjectNode generationParams = objectMapper.createObjectNode();
            generationParams.put("ratio_bucket", bucket.name());
            generationParams.put("variant_index", i);
            generationParams.put("outpainting_pending", outpaintingNeeded);
            if (outpaintingNeeded) {
                generationParams.put("note", "3-3 아웃페인팅 미구현 — 배경을 크롭만 해 키비주얼이 잘릴 수 있음");
            }

            GeneratedAsset asset = generatedAssetService.saveCandidate(
                    project.getId(), "규격변환", formatCode, width, height, i,
                    toJpeg(backdrop), textResult.image(), combinedMap, generationParams);
            assetIds.add(asset.getId().toString());
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("format_code", formatCode);
        result.set("asset_ids", assetIds);
        return result;
    }

    /** 규격마다 캔버스 크기가 다르므로 PHOTO 배치 영역을 상대 비율로 스케일한다(임시값 — docs/12). */
    private Rectangle scalePhotoArea(int width, int height) {
        double baseW = FormatPreset.POSTER.width(), baseH = FormatPreset.POSTER.height();
        int x = (int) (PHOTO_AREA_RATIO_BASE.x / baseW * width);
        int y = (int) (PHOTO_AREA_RATIO_BASE.y / baseH * height);
        int w = (int) (PHOTO_AREA_RATIO_BASE.width / baseW * width);
        int h = (int) (PHOTO_AREA_RATIO_BASE.height / baseH * height);
        return new Rectangle(x, y, Math.max(w, 40), Math.max(h, 40));
    }

    private BufferedImage copy(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private byte[] toJpeg(BufferedImage image) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
