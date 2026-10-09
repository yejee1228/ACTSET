package com.actset.conversion;

import com.actset.domain.GeneratedAsset;
import com.actset.domain.Job;
import com.actset.domain.Project;
import com.actset.repository.DesignElementRepository;
import com.actset.repository.GeneratedAssetRepository;
import com.actset.repository.ProjectRepository;
import com.actset.storage.StorageService;
import com.actset.worker.JobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.UUID;

/**
 * 한 번만 도는 복구 — 없앤 /convert 화면(2026-10-07~08)으로 만든 업로드 프로젝트에는 대표 포스터 결과물이 없어
 * 홈 썸네일·대시보드 포스터가 비어 있었다. 서버 시작 시 그런 프로젝트를 찾아 대표 포스터를 만들고 분석 상태를 맞춘다.
 * 대상이 없으면 아무것도 하지 않는다(멱등).
 */
@Component
@Profile("web")
public class LegacyUploadBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyUploadBackfill.class);

    private final ProjectRepository projects;
    private final GeneratedAssetRepository assets;
    private final DesignElementRepository elements;
    private final StorageService storage;
    private final JobService jobService;
    private final ObjectMapper objectMapper;

    public LegacyUploadBackfill(ProjectRepository projects, GeneratedAssetRepository assets,
                                DesignElementRepository elements, StorageService storage, JobService jobService,
                                ObjectMapper objectMapper) {
        this.projects = projects;
        this.assets = assets;
        this.elements = elements;
        this.storage = storage;
        this.jobService = jobService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int fixed = 0;
        for (Project p : projects.findAll()) {
            if (p.getDesignAssets() == null || "deleted".equals(p.getStatus())) continue;
            if (!PosterUploadService.SOURCE_UPLOADED.equals(p.getDesignAssets().path("source").asText())) continue;
            String posterPath = p.getDesignAssets().path("source_poster").asText(null);
            if (posterPath == null || !storage.exists(posterPath)) continue;
            if (assets.findFirstByProjectIdAndCategoryAndDeletedAtIsNull(p.getId(), "포스터").isPresent()) continue;
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(storage.read(posterPath)));
                String previewPath = posterPath.replaceFirst("\\.png$", "") + "_preview.jpg";
                storage.store(jpeg(downscale(img, PosterUploadService.PREVIEW_LONG_EDGE)), previewPath, "image/jpeg");
                GeneratedAsset a = new GeneratedAsset();
                a.setId(UUID.randomUUID());
                a.setProjectId(p.getId());
                a.setCategory("포스터");
                a.setFormatCode("CUSTOM");
                a.setWidth(img.getWidth());
                a.setHeight(img.getHeight());
                a.setBaseImageUrl(posterPath);
                a.setImageUrl(posterPath);
                a.setPreviewImageUrl(previewPath);
                a.setAutoSyncText(false);
                a.setStatus("선택됨");
                ObjectNode params = objectMapper.createObjectNode();
                params.put("source", PosterUploadService.SOURCE_UPLOADED);
                params.put("backfilled", true);
                a.setGenerationParams(params);
                assets.save(a);

                ObjectNode da = (ObjectNode) p.getDesignAssets();
                if (!da.has("analysis")) {
                    if (!elements.findByProjectIdOrderByLayerOrderAsc(p.getId()).isEmpty()) {
                        da.put("analysis", "done");
                    } else {
                        Job job = jobService.enqueue(AnalyzePosterJobHandler.KIND, p.getId(), objectMapper.createObjectNode());
                        da.put("analysis", "pending");
                        da.put("analysis_job_id", job.getId().toString());
                    }
                    p.setDesignAssets(da);
                    projects.save(p);
                }
                fixed++;
            } catch (Exception e) {
                log.warn("업로드 프로젝트 {} 대표 포스터 복구 실패: {}", p.getId(), e.getMessage());
            }
        }
        if (fixed > 0) log.info("업로드 프로젝트 {}건의 대표 포스터를 복구했습니다", fixed);
    }

    private static BufferedImage downscale(BufferedImage src, int longEdge) {
        double s = Math.min(1.0, longEdge / (double) Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * s)), h = Math.max(1, (int) Math.round(src.getHeight() * s));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src.getScaledInstance(w, h, Image.SCALE_AREA_AVERAGING), 0, 0, null);
        g.dispose();
        return out;
    }

    private static byte[] jpeg(BufferedImage img) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }
}
