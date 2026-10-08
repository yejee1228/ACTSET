package com.actset.conversion;

import com.actset.common.ApiException;
import com.actset.domain.Job;
import com.actset.domain.Project;
import com.actset.repository.ProjectRepository;
import com.actset.storage.StorageService;
import com.actset.worker.JobHandler;
import com.actset.worker.JobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

/**
 * jobs.kind = 'analyze_poster' — 업로드한 포스터를 요소로 분석한다(②~⑤, 프로젝트당 1회 — docs/05 "분해는 1회").
 * 끝나면 design_assets.analysis = done. 이후 규격 변환(format_convert)은 저장된 요소를 재배치만 한다.
 * 실패하면 워커가 failed로 기록하고 job 단위로 환불한다(JobWorker) — 화면은 job 상태로 실패를 안다.
 */
@Component
public class AnalyzePosterJobHandler implements JobHandler {

    public static final String KIND = "analyze_poster";

    private final ProjectRepository projectRepository;
    private final StorageService storage;
    private final ElementExtractionService extraction;
    private final DesignElementStore store;
    private final ObjectMapper objectMapper;
    private final JobService jobService;

    public AnalyzePosterJobHandler(ProjectRepository projectRepository, StorageService storage,
                                   ElementExtractionService extraction, DesignElementStore store,
                                   ObjectMapper objectMapper, JobService jobService) {
        this.jobService = jobService;
        this.projectRepository = projectRepository;
        this.storage = storage;
        this.extraction = extraction;
        this.store = store;
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

        ElementExtractionService.Result result = extraction.extract(poster, ElementExtractionService.DebugSink.NOOP, null,
                (step, total, label, from, to, sec) -> jobService.progress(job.getId(), step, total, label, from, to, sec));
        jobService.progress(job.getId(), ElementExtractionService.TOTAL_STAGES, ElementExtractionService.TOTAL_STAGES,
                "분리한 요소를 저장하는 중", 96, 100, 3);
        int saved = store.replaceAll(project.getOwnerId(), project.getId(), result).size();

        // 분석 도중 포스터를 다시 올렸으면(다른 job이 최신) 이 결과로 상태를 덮지 않는다
        Project fresh = projectRepository.findById(project.getId()).orElseThrow(ApiException::notFound);
        ObjectNode assets = (ObjectNode) fresh.getDesignAssets();
        if (job.getId().toString().equals(assets.path("analysis_job_id").asText())) {
            assets.put("analysis", "done");
            assets.put("element_count", saved);
            fresh.setDesignAssets(assets);
            projectRepository.save(fresh);
        }

        ObjectNode out = objectMapper.createObjectNode();
        out.put("element_count", saved);
        out.set("extraction", result.log());
        return out;
    }
}
