package com.actset.worker.handler;

import com.actset.common.ApiException;
import com.actset.domain.GeneratedAsset;
import com.actset.domain.Job;
import com.actset.domain.Project;
import com.actset.domain.UploadedFile;
import com.actset.external.imagegen.DraftPromptBuilder;
import com.actset.external.imagegen.ImageGenerationAdapter;
import com.actset.external.imagegen.ImageGenerationRequest;
import com.actset.external.imagegen.ImageGenerationResult;
import com.actset.format.FormatPreset;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * jobs.kind = 'draft_generate' — ③ 시안 후보 생성(1-11·1-12).
 *
 * 레이아웃 상수(titleArea·infoArea·photoArea)는 GenreRule/FormatRule이 학습되기
 * 전까지의 임시값이다(CLAUDE.md 규칙 5, docs/12 "값은 학습 후 확정").
 */
@Component
public class DraftGenerateJobHandler implements JobHandler {

    // TODO(임시값 — E2 학습 파이프라인 완료 전까지): POSTER(TALL 구간) 기본 배치
    private static final double[] TITLE_AREA = {0.08, 0.60, 0.92, 0.66};
    private static final double[] INFO_AREA = {0.08, 0.60, 0.92, 0.95};
    private static final Rectangle PHOTO_AREA_PX = new Rectangle(420, 140, 400, 400); // 1240x1754 기준

    private final ProjectRepository projectRepository;
    private final UploadedFileRepository uploadedFileRepository;
    private final GeneratedAssetRepository generatedAssetRepository;
    private final StorageService storageService;
    private final DraftPromptBuilder promptBuilder;
    private final ImageGenerationAdapter imageGenerationAdapter;
    private final PerformanceInfoTextMapper textMapper;
    private final PosterTextRenderer posterTextRenderer;
    private final PhotoLayerRenderer photoLayerRenderer;
    private final GeneratedAssetService generatedAssetService;
    private final ObjectMapper objectMapper;

    public DraftGenerateJobHandler(ProjectRepository projectRepository,
                                    UploadedFileRepository uploadedFileRepository,
                                    GeneratedAssetRepository generatedAssetRepository,
                                    StorageService storageService,
                                    DraftPromptBuilder promptBuilder,
                                    ImageGenerationAdapter imageGenerationAdapter,
                                    PerformanceInfoTextMapper textMapper,
                                    PosterTextRenderer posterTextRenderer,
                                    PhotoLayerRenderer photoLayerRenderer,
                                    GeneratedAssetService generatedAssetService,
                                    ObjectMapper objectMapper) {
        this.projectRepository = projectRepository;
        this.uploadedFileRepository = uploadedFileRepository;
        this.generatedAssetRepository = generatedAssetRepository;
        this.storageService = storageService;
        this.promptBuilder = promptBuilder;
        this.imageGenerationAdapter = imageGenerationAdapter;
        this.textMapper = textMapper;
        this.posterTextRenderer = posterTextRenderer;
        this.photoLayerRenderer = photoLayerRenderer;
        this.generatedAssetService = generatedAssetService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String kind() {
        return "draft_generate";
    }

    @Override
    public ObjectNode handle(Job job) throws Exception {
        Project project = projectRepository.findById(job.getProjectId()).orElseThrow(ApiException::notFound);
        JsonNode payload = job.getPayload();
        int count = payload != null && payload.has("count") ? payload.get("count").asInt(3) : 3;
        String mode = payload != null && payload.has("mode") ? payload.get("mode").asText("initial") : "initial";

        // 이 방향으로 더 보기(more_like) — 참고 후보의 seed·styleType을 재사용한다(docs/03 4개 액션).
        String referenceSeed = null;
        String referenceStyle = null;
        if ("more_like".equals(mode) && payload != null && payload.hasNonNull("reference_candidate_id")) {
            UUID referenceCandidateId = UUID.fromString(payload.get("reference_candidate_id").asText());
            GeneratedAsset reference = generatedAssetRepository.findById(referenceCandidateId).orElse(null);
            if (reference != null && reference.getGenerationParams() != null) {
                referenceSeed = reference.getGenerationParams().path("seed").asText(null);
                referenceStyle = reference.getGenerationParams().path("style_type").asText(null);
            }
        }

        JsonNode info = project.getPerformanceInfo();
        List<String> referenceImagePaths = uploadedFileRepository.findByProjectId(project.getId()).stream()
                .filter(f -> "reference_image".equals(f.getKind()))
                .map(UploadedFile::getStoragePath)
                .toList();

        List<UploadedFile> photoFiles = uploadedFileRepository.findByProjectId(project.getId()).stream()
                .filter(f -> "cast_photo".equals(f.getKind()) || "performance_photo".equals(f.getKind()))
                .toList();

        int width = FormatPreset.POSTER.width();
        int height = FormatPreset.POSTER.height();
        String title = textMapper.title(info);
        List<TextBlockSpec> infoBlocks = textMapper.infoBlocks(info);

        // 7-6 "참고해서 만들기"(Stage 5 4-2·17) — 있으면 팔레트 힌트만 프롬프트에 실어 보낸다.
        List<String> referencePalette = new ArrayList<>();
        if (project.getDesignAssets() != null) {
            project.getDesignAssets().path("reference_style_hint").path("palette")
                    .forEach(node -> referencePalette.add(node.asText()));
        }

        ArrayNode candidateIds = objectMapper.createArrayNode();

        for (int i = 0; i < count; i++) {
            ImageGenerationRequest request = promptBuilder.build(info, referenceImagePaths, width, height,
                    referencePalette, mode, referenceSeed, referenceStyle);
            ImageGenerationResult generated = imageGenerationAdapter.generate(request);

            BufferedImage backdrop = ImageIO.read(new ByteArrayInputStream(generated.imageBytes()));
            BufferedImage composed = copyImage(backdrop);

            ObjectNode photoMap = objectMapper.createObjectNode();
            if (!photoFiles.isEmpty()) {
                UploadedFile photo = photoFiles.get(0);
                BufferedImage photoImg = ImageIO.read(new ByteArrayInputStream(storageService.read(photo.getStoragePath())));
                Graphics2D pg = composed.createGraphics();
                photoLayerRenderer.render(pg, List.of(new PhotoLayerRenderer.PhotoPlacement(
                        "cast_photo_1", photo.getId(), photoImg, PHOTO_AREA_PX, PhotoLayerRenderer.Mask.CIRCLE)), photoMap);
                pg.dispose();
            }

            PosterTextRenderer.Result textResult = posterTextRenderer.render(composed, title, infoBlocks, TITLE_AREA, INFO_AREA);
            ObjectNode combinedMap = photoMap.deepCopy();
            combinedMap.setAll(textResult.objectMap());

            ObjectNode generationParams = objectMapper.createObjectNode();
            generationParams.put("model", generated.modelUsed());
            generationParams.put("seed", generated.seed());
            generationParams.put("genre", request.genre());
            generationParams.put("image_direction_note", request.imageDirectionNote());
            generationParams.put("variant_index", i);
            generationParams.put("mode", mode);
            // 실제로 벤더에 전송된 텍스트 그대로 — 문의 대응·디버깅용(사용자가 결과를 이해할 수 있도록).
            generationParams.put("prompt", request.prompt());
            generationParams.put("negative_prompt", request.negativePrompt());
            if (request.styleType() != null) {
                generationParams.put("style_type", request.styleType());
            }
            if (!referencePalette.isEmpty()) {
                ArrayNode paletteNode = generationParams.putArray("reference_palette");
                referencePalette.forEach(paletteNode::add);
            }

            GeneratedAsset asset = generatedAssetService.saveCandidate(
                    project.getId(), FormatPreset.POSTER.code(), width, height, i,
                    generated.imageBytes(), textResult.image(), combinedMap, generationParams);
            candidateIds.add(asset.getId().toString());
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.set("asset_ids", candidateIds);
        return result;
    }

    private BufferedImage copyImage(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return copy;
    }
}
