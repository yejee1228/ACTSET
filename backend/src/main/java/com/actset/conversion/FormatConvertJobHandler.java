package com.actset.conversion;

import com.actset.common.ApiException;
import com.actset.conversion.layout.LayoutPlanner;
import com.actset.conversion.layout.TempLayoutPriors;
import com.actset.domain.GeneratedAsset;
import com.actset.domain.Job;
import com.actset.domain.Project;
import com.actset.format.FormatClass;
import com.actset.repository.ProjectRepository;
import com.actset.worker.JobHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * jobs.kind = 'format_convert' — 업로드 포스터 프로젝트의 ⑥ 규격 변환 1건(RecomposeService가 규격별 하위 job으로 등록).
 * 분석(analyze_poster)이 저장한 요소를 재배치·합성만 한다 — 분해·LLM을 다시 부르지 않는다(CLAUDE.md 규칙 3).
 */
@Component
public class FormatConvertJobHandler implements JobHandler {

    public static final String KIND = "format_convert";

    private final ProjectRepository projectRepository;
    private final DesignElementStore store;
    private final FormatRenderService renderer;
    private final ObjectMapper objectMapper;

    public FormatConvertJobHandler(ProjectRepository projectRepository, DesignElementStore store,
                                   FormatRenderService renderer, ObjectMapper objectMapper) {
        this.projectRepository = projectRepository;
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
        List<LayoutPlanner.SourceElement> elements = store.load(project.getId());
        if (elements.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "POSTER_NOT_ANALYZED", "포스터 분석이 끝나지 않았습니다.");
        }
        int sourceWidth = elements.stream().filter(e -> e.role() == com.actset.conversion.layout.ElementRole.BACKDROP)
                .mapToInt(e -> e.bounds().x + e.bounds().width).max().orElseThrow();
        int sourceHeight = elements.stream().filter(e -> e.role() == com.actset.conversion.layout.ElementRole.BACKDROP)
                .mapToInt(e -> e.bounds().y + e.bounds().height).max().orElseThrow();

        JsonNode payload = job.getPayload();
        String formatCode = payload.path("format_code").asText();
        int width = payload.path("width").asInt(), height = payload.path("height").asInt();
        FormatClass formatClass = FormatClass.fromDimensions(width, height);
        // 레퍼런스 학습 전이라 임시 사전값(TEMP_PRIOR)으로 배치한다 — CLAUDE.md 규칙 5
        FormatRenderService.Rendered rendered = renderer.render(sourceWidth, sourceHeight, elements,
                TempLayoutPriors.forClass(formatClass), width, height);
        GeneratedAsset asset = renderer.save(project.getOwnerId(), project.getId(), formatCode, rendered);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("format_code", formatCode);
        result.put("format_class", formatClass.name());
        result.putArray("asset_ids").add(asset.getId().toString());
        return result;
    }
}
