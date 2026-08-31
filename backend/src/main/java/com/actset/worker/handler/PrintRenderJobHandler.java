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
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * jobs.kind = 'render_print' — ⑧ 인쇄 해상도 재합성(5-3). 비주얼 레이어는 업스케일,
 * TITLE·INFO는 지정한 mm×dpi 치수로 다시 렌더링한다(docs/05 — 텍스트를 이미지로 굽지
 * 않으므로 확대해도 벡터급으로 선명하다). PHOTO는 업로드 원본을 그대로 재배치하고
 * 외부 업스케일러에 보내지 않는다(docs/05 — 얼굴 왜곡 위험, CLAUDE.md 규칙 1과 같은 원칙).
 */
@Component
public class PrintRenderJobHandler implements JobHandler {

    private static final Rectangle PHOTO_AREA_RATIO_BASE = new Rectangle(420, 140, 400, 400);

    private final ProjectRepository projectRepository;
    private final GeneratedAssetRepository generatedAssetRepository;
    private final UploadedFileRepository uploadedFileRepository;
    private final StorageService storageService;
    private final PerformanceInfoTextMapper textMapper;
    private final PosterTextRenderer posterTextRenderer;
    private final PhotoLayerRenderer photoLayerRenderer;
    private final ObjectMapper objectMapper;

    public PrintRenderJobHandler(ProjectRepository projectRepository, GeneratedAssetRepository generatedAssetRepository,
                                  UploadedFileRepository uploadedFileRepository, StorageService storageService,
                                  PerformanceInfoTextMapper textMapper, PosterTextRenderer posterTextRenderer,
                                  PhotoLayerRenderer photoLayerRenderer, ObjectMapper objectMapper) {
        this.projectRepository = projectRepository;
        this.generatedAssetRepository = generatedAssetRepository;
        this.uploadedFileRepository = uploadedFileRepository;
        this.storageService = storageService;
        this.textMapper = textMapper;
        this.posterTextRenderer = posterTextRenderer;
        this.photoLayerRenderer = photoLayerRenderer;
        this.objectMapper = objectMapper;
    }

    @Override
    public String kind() {
        return "render_print";
    }

    @Override
    public ObjectNode handle(Job job) throws Exception {
        Project project = projectRepository.findById(job.getProjectId()).orElseThrow(ApiException::notFound);
        JsonNode payload = job.getPayload();
        UUID assetId = UUID.fromString(payload.path("asset_id").asText());
        int widthMm = payload.path("width_mm").asInt();
        int heightMm = payload.path("height_mm").asInt();
        int dpi = payload.path("dpi").asInt();

        GeneratedAsset asset = generatedAssetRepository.findById(assetId)
                .filter(a -> a.getProjectId().equals(project.getId()))
                .orElseThrow(ApiException::notFound);

        int printW = (int) Math.round(widthMm / 25.4 * dpi);
        int printH = (int) Math.round(heightMm / 25.4 * dpi);

        BufferedImage backdropSource = ImageIO.read(new ByteArrayInputStream(storageService.read(asset.getBaseImageUrl())));
        BufferedImage backdrop = ImageFit.coverFit(backdropSource, printW, printH);
        BufferedImage composed = copy(backdrop);

        RatioBucket bucket = FormatPreset.byCode(asset.getFormatCode())
                .map(FormatPreset::ratioBucket)
                .orElseGet(() -> RatioBucket.fromDimensions(printW, printH));
        TempFormatLayout.Areas areas = TempFormatLayout.forBucket(bucket);

        JsonNode info = project.getPerformanceInfo();
        List<UploadedFile> photoFiles = uploadedFileRepository.findByProjectId(project.getId()).stream()
                .filter(f -> "cast_photo".equals(f.getKind()) || "performance_photo".equals(f.getKind()))
                .toList();
        if (!photoFiles.isEmpty()) {
            UploadedFile photo = photoFiles.get(0);
            BufferedImage photoImg = ImageIO.read(new ByteArrayInputStream(storageService.read(photo.getStoragePath())));
            Rectangle photoArea = scalePhotoArea(printW, printH);
            Graphics2D pg = composed.createGraphics();
            photoLayerRenderer.render(pg, List.of(new PhotoLayerRenderer.PhotoPlacement(
                    "cast_photo_1", photo.getId(), photoImg, photoArea, PhotoLayerRenderer.Mask.CIRCLE)), objectMapper.createObjectNode());
            pg.dispose();
        }

        String title = textMapper.title(info);
        List<TextBlockSpec> infoBlocks = textMapper.infoBlocks(info);
        PosterTextRenderer.Result textResult = posterTextRenderer.render(composed, title, infoBlocks, areas.title(), areas.info());

        String printPath = "projects/" + project.getId() + "/print/" + job.getId() + ".jpg";
        storageService.store(toJpeg(textResult.image()), printPath, "image/jpeg");

        ObjectNode result = objectMapper.createObjectNode();
        result.put("image_url", storageService.signedUrl(printPath, Duration.ofHours(2)));
        result.put("width", printW);
        result.put("height", printH);
        result.put("dpi", dpi);
        return result;
    }

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
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
