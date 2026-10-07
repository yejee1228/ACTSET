package com.actset.conversion;

import com.actset.common.ApiException;
import com.actset.conversion.layout.LayoutPlanner;
import com.actset.conversion.layout.TempLayoutPriors;
import com.actset.domain.GeneratedAsset;
import com.actset.domain.Job;
import com.actset.domain.Project;
import com.actset.format.FormatClass;
import com.actset.format.FormatPreset;
import com.actset.repository.ProjectRepository;
import com.actset.storage.StorageService;
import com.actset.worker.JobHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * jobs.kind = 'format_convert' — 포스터 업로드형 규격변환 전체(②~⑦).
 * 요소가 이미 저장된 프로젝트면 분해·LLM을 다시 부르지 않고 재배치만 한다(CLAUDE.md 규칙 3).
 */
@Component
public class FormatConvertJobHandler implements JobHandler {

    public static final String KIND = "format_convert";

    private final ProjectRepository projectRepository;
    private final StorageService storage;
    private final ElementExtractionService extraction;
    private final DesignElementStore store;
    private final FormatRenderService renderer;
    private final ObjectMapper objectMapper;

    public FormatConvertJobHandler(ProjectRepository projectRepository, StorageService storage,
                                   ElementExtractionService extraction, DesignElementStore store,
                                   FormatRenderService renderer, ObjectMapper objectMapper) {
        this.projectRepository = projectRepository;
        this.storage = storage;
        this.extraction = extraction;
        this.store = store;
        this.renderer = renderer;
        this.objectMapper = objectMapper;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public ObjectNode handle(Job job) throws Exception {
        Project project = projectRepository.findById(job.getProjectId()).orElseThrow(ApiException::notFound);
        String posterPath = project.getDesignAssets().path("source_poster").asText();
        BufferedImage poster = ImageIO.read(new ByteArrayInputStream(storage.read(posterPath)));

        ObjectNode result = objectMapper.createObjectNode();
        List<LayoutPlanner.SourceElement> elements = store.load(project.getId());
        if (elements.isEmpty()) {
            ElementExtractionService.Result extracted = extraction.extract(poster, ElementExtractionService.DebugSink.NOOP);
            store.replaceAll(project.getOwnerId(), project.getId(), extracted);
            result.set("extraction", extracted.log());
            elements = store.load(project.getId());
        }

        ArrayNode assetIds = result.putArray("asset_ids");
        for (JsonNode c : job.getPayload().path("format_classes")) {
            FormatClass formatClass = FormatClass.valueOf(c.asText());
            FormatPreset preset = formatClass.representativePreset();
            // 레퍼런스 학습 전이라 임시 사전값(TEMP_PRIOR)으로 배치한다 — CLAUDE.md 규칙 5
            FormatRenderService.Rendered rendered = renderer.render(poster.getWidth(), poster.getHeight(), elements,
                    TempLayoutPriors.forClass(formatClass), preset.width(), preset.height());
            GeneratedAsset asset = renderer.save(project.getOwnerId(), project.getId(), preset.name(), rendered);
            assetIds.add(asset.getId().toString());
        }
        return result;
    }
}
