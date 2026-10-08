package com.actset.conversion;

import com.actset.common.ApiException;
import com.actset.domain.GeneratedAsset;
import com.actset.domain.Job;
import com.actset.domain.Project;
import com.actset.repository.GeneratedAssetRepository;
import com.actset.repository.ProjectRepository;
import com.actset.service.CreditService;
import com.actset.service.ProjectService;
import com.actset.storage.StorageService;
import com.actset.worker.JobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/**
 * 프로젝트 생성의 두 번째 경로 — "가지고 있는 포스터로 시작"(docs/03 U-1).
 *
 * <p>업로드한 포스터를 이 프로젝트의 대표 포스터로 등록하고(AI 생성 경로의 ④ 확정과 같은 지위), 포스터 분석 작업
 * (analyze_poster: 분해 → 배경 검증 → 요소 분리·역할 판정, 프로젝트당 1회)을 등록한다. 이후 규격 변환은 기존 ⑤⑥
 * 화면을 그대로 쓰고, 저장된 요소를 재배치만 한다(CLAUDE.md 규칙 3).
 *
 * <p>다시 올리면 대표 포스터·요소를 교체하고 design_updated_at을 갱신한다 — 기존 규격변환 결과물에 "원본 변경됨"이 붙는다.
 */
@Service
public class PosterUploadService {

    public static final String SOURCE_UPLOADED = "uploaded_poster";
    static final long MAX_BYTES = 20L * 1024 * 1024;
    static final int MIN_SIDE = 300;
    static final int PREVIEW_LONG_EDGE = 800;

    private final ProjectService projectService;
    private final ProjectRepository projectRepository;
    private final GeneratedAssetRepository assetRepository;
    private final StorageService storage;
    private final JobService jobService;
    private final CreditService creditService;
    private final ObjectMapper objectMapper;
    /** 포스터 분석 1회 크레딧. 단가 미정(FORMAT-CONVERSION-REPORT.md §9-2) — 확정 전까지 0. 0보다 크면 작업 등록과 같은 트랜잭션에서 차감. */
    private final int analysisCost;

    public PosterUploadService(ProjectService projectService, ProjectRepository projectRepository,
                               GeneratedAssetRepository assetRepository, StorageService storage, JobService jobService,
                               CreditService creditService, ObjectMapper objectMapper,
                               @Value("${actset.credit.poster-analysis-cost:0}") int analysisCost) {
        this.projectService = projectService;
        this.projectRepository = projectRepository;
        this.assetRepository = assetRepository;
        this.storage = storage;
        this.jobService = jobService;
        this.creditService = creditService;
        this.objectMapper = objectMapper;
        this.analysisCost = analysisCost;
    }

    public record Uploaded(UUID projectId, UUID jobId, int cost) {
    }

    /** 홈의 "가지고 있는 포스터로 시작" — 업로드 경로임을 표시한 draft를 만든다(작성 중 목록에서 업로드 화면으로 돌아오게). */
    @Transactional
    public Project createUploadDraft(UUID ownerId) {
        Project project = projectService.createDraft(ownerId);
        ObjectNode assets = objectMapper.createObjectNode();
        assets.put("source", SOURCE_UPLOADED);
        project.setDesignAssets(assets);
        return projectRepository.save(project);
    }

    @Transactional
    public Uploaded upload(UUID projectId, UUID ownerId, MultipartFile file, String mainTitle) {
        Project project = projectService.getOwned(projectId, ownerId);
        if (mainTitle == null || mainTitle.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TITLE_REQUIRED", "공연명을 입력해주세요.");
        }
        BufferedImage poster = readPoster(file);
        String folder = DesignElementStore.projectFolder(ownerId, projectId) + "/source/";
        String stamp = String.valueOf(System.currentTimeMillis());
        String posterPath = folder + "poster_" + stamp + ".png";
        String previewPath = folder + "poster_" + stamp + "_preview.jpg";
        byte[] png = DesignElementStore.png(poster);
        storage.store(png, posterPath, "image/png");
        storage.store(jpeg(downscale(poster, PREVIEW_LONG_EDGE)), previewPath, "image/jpeg");

        // 대표 포스터는 프로젝트당 1건(uq_project_poster) — 다시 올리면 이전 것을 소프트 삭제하고 교체
        Instant now = Instant.now();
        assetRepository.findFirstByProjectIdAndCategoryAndDeletedAtIsNull(projectId, "포스터").ifPresent(old -> {
            old.setDeletedAt(now);
            assetRepository.saveAndFlush(old);
        });
        GeneratedAsset asset = new GeneratedAsset();
        asset.setId(UUID.randomUUID());
        asset.setProjectId(projectId);
        asset.setCategory("포스터");
        asset.setFormatCode("CUSTOM");
        asset.setWidth(poster.getWidth());
        asset.setHeight(poster.getHeight());
        asset.setBaseImageUrl(posterPath);
        asset.setImageUrl(posterPath);
        asset.setPreviewImageUrl(previewPath);
        asset.setAutoSyncText(false); // 텍스트가 이미지에 들어 있어 정보 수정이 자동 반영되지 않는다
        asset.setStatus("선택됨");
        asset.setFileSize((long) png.length);
        ObjectNode params = objectMapper.createObjectNode();
        params.put("source", SOURCE_UPLOADED);
        params.put("original_filename", file.getOriginalFilename());
        asset.setGenerationParams(params);
        assetRepository.save(asset);

        Job job = jobService.enqueue(AnalyzePosterJobHandler.KIND, projectId, objectMapper.createObjectNode());
        if (analysisCost > 0) {
            creditService.consume(ownerId, analysisCost, job.getId(), "포스터 분석");
        }

        ObjectNode assets = project.getDesignAssets() instanceof ObjectNode o ? o : objectMapper.createObjectNode();
        assets.removeAll();
        assets.put("source", SOURCE_UPLOADED);
        assets.put("source_poster", posterPath);
        assets.put("analysis", "pending");
        assets.put("analysis_job_id", job.getId().toString());
        project.setDesignAssets(assets);
        project.setMainTitle(mainTitle.trim().length() > 100 ? mainTitle.trim().substring(0, 100) : mainTitle.trim());
        if (!"active".equals(project.getStatus())) {
            project.setStatus("active");
            project.setConfirmedAt(now);
        }
        project.setDesignUpdatedAt(now);
        projectRepository.save(project);
        return new Uploaded(projectId, job.getId(), analysisCost);
    }

    /** jpg/png만, 20MB 이하, 짧은 변 300px 이상. 저장은 PNG로 통일(EXIF 등 메타데이터도 함께 제거된다). */
    private BufferedImage readPoster(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_FILE", "포스터 파일을 올려주세요.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "20MB 이하 파일만 올릴 수 있습니다.");
        }
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(file.getBytes()));
            if (img == null) {
                throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_IMAGE", "JPG 또는 PNG 이미지만 올릴 수 있습니다.");
            }
            if (Math.min(img.getWidth(), img.getHeight()) < MIN_SIDE) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IMAGE_TOO_SMALL", "짧은 변이 300px 이상인 이미지를 올려주세요.");
            }
            return img;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_IMAGE", "이미지를 읽을 수 없습니다.");
        }
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

    private static byte[] jpeg(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
