package com.actset.conversion;

import com.actset.common.ApiException;
import com.actset.conversion.layout.ElementRole;
import com.actset.conversion.pdf.PdfPosterReader;
import com.actset.conversion.pdf.PdfTextRoles;
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
import java.util.ArrayList;
import java.util.List;

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

    /** PDF 텍스트 줄을 요소로 덧붙인다. 그리는 순서(=PDF 순서) 그대로 그림 요소 위에 쌓는다(그림자 사본 → 본체). */
    private ElementExtractionService.Result withPdfText(ElementExtractionService.Result r, PdfPosterReader.Result pdf) {
        List<ElementExtractionService.Extracted> els = new ArrayList<>(r.elements());
        for (PdfPosterReader.TextRun run : pdf.runs()) {
            ObjectNode meta = objectMapper.createObjectNode();
            meta.put("text", run.text());
            meta.put("font_name", run.fontName());
            meta.put("font_size_px", Math.round(run.sizePx() * 10) / 10.0);
            meta.put("color", String.format("#%06X", run.rgb() & 0xffffff));
            meta.put("pdf_run", run.order());
            ElementRole role = PdfTextRoles.roleOf(run, pdf.runs(), pdf.height());
            els.add(new ElementExtractionService.Extracted("P" + run.order(), role, "pdf_text", run.text(), run.layer(),
                    run.bounds(), 5000 + run.order(), meta));
        }
        r.log().put("pdf_text_runs", pdf.runs().size());
        return new ElementExtractionService.Result(r.width(), r.height(), els, r.log());
    }

    @Override
    public ObjectNode handle(Job job) throws Exception {
        Project project = projectRepository.findById(job.getProjectId()).orElseThrow(ApiException::notFound);
        String posterPath = project.getDesignAssets().path("source_poster").asText();
        BufferedImage poster = ImageIO.read(new ByteArrayInputStream(storage.read(posterPath)));

        // PDF이고 글자가 들어 있으면: 그림은 "글자 뺀 렌더"로 분해하고, 텍스트는 PDF에서 그대로 만든다(LLM·폰트 대조 없음)
        PdfPosterReader.Result pdf = null;
        String pdfPath = project.getDesignAssets().path("source_pdf").asText(null);
        if (pdfPath != null && project.getDesignAssets().path("pdf_text_runs").asInt(0) > 0) {
            pdf = new PdfPosterReader().read(storage.read(pdfPath), PosterUploadService.PDF_RENDER_LONG_SIDE);
            poster = pdf.noText();
        }

        ElementExtractionService.Result result = extraction.extract(poster, ElementExtractionService.DebugSink.NOOP, null,
                (step, total, label, from, to, sec) -> jobService.progress(job.getId(), step, total, label, from, to, sec));
        if (pdf != null) result = withPdfText(result, pdf);
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
