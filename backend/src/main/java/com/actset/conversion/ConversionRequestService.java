package com.actset.conversion;

import com.actset.common.ApiException;
import com.actset.domain.Job;
import com.actset.domain.Project;
import com.actset.format.FormatClass;
import com.actset.repository.ProjectRepository;
import com.actset.service.CostEstimateService;
import com.actset.service.CreditService;
import com.actset.storage.StorageService;
import com.actset.worker.JobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 규격변환 ① 입력 — 고객이 올린 포스터 + 변환할 규격 분류를 받아 프로젝트·작업을 만든다.
 *
 * <p>작업 등록·크레딧 차감은 한 트랜잭션(CLAUDE.md 규칙 4). 실패하면 워커가 job 단위로 환불한다(JobWorker).
 * 단가는 기존 규격 변환 단가(규격당 2C, docs/06)를 그대로 쓴다 — 포스터 업로드형은 분해·배경 재생성 등 외부 호출이
 * 추가되므로 단가 재산정이 필요하다(FORMAT-CONVERSION-REPORT.md 확인 필요 사항).
 */
@Service
public class ConversionRequestService {

    static final long MAX_BYTES = 20L * 1024 * 1024;
    static final int MIN_SIDE = 300;

    private final ProjectRepository projectRepository;
    private final StorageService storage;
    private final JobService jobService;
    private final CreditService creditService;
    private final CostEstimateService costEstimateService;
    private final ObjectMapper objectMapper;

    public ConversionRequestService(ProjectRepository projectRepository, StorageService storage, JobService jobService,
                                    CreditService creditService, CostEstimateService costEstimateService,
                                    ObjectMapper objectMapper) {
        this.projectRepository = projectRepository;
        this.storage = storage;
        this.jobService = jobService;
        this.creditService = creditService;
        this.costEstimateService = costEstimateService;
        this.objectMapper = objectMapper;
    }

    public record Requested(UUID projectId, UUID jobId, int cost, List<String> formatClasses) {
    }

    public int estimate(int classCount) {
        return classCount * costEstimateService.recomposeCostPerFormat("initial");
    }

    @Transactional
    public Requested request(UUID ownerId, MultipartFile poster, List<String> formatClasses) {
        Set<FormatClass> classes = new LinkedHashSet<>();
        for (String c : formatClasses == null ? List.<String>of() : formatClasses) {
            try {
                classes.add(FormatClass.valueOf(c));
            } catch (IllegalArgumentException e) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_FORMAT_CLASS", "알 수 없는 규격: " + c);
            }
        }
        if (classes.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_TARGET_FORMATS", "변환할 규격을 하나 이상 골라주세요.");
        }
        byte[] bytes = readPoster(poster);

        Project project = new Project();
        project.setOwnerId(ownerId);
        project.setStatus("active");
        String name = poster.getOriginalFilename() == null ? "포스터" : poster.getOriginalFilename().replaceFirst("\\.[^.]+$", "");
        project.setMainTitle(name.length() > 80 ? name.substring(0, 80) : name);
        project.setPerformanceInfo(objectMapper.createObjectNode());
        project = projectRepository.save(project);

        String path = DesignElementStore.projectFolder(ownerId, project.getId()) + "/source/poster.png";
        storage.store(bytes, path, "image/png");
        ObjectNode assets = objectMapper.createObjectNode();
        assets.put("source_poster", path);
        assets.put("source", "uploaded_poster");
        project.setDesignAssets(assets);
        projectRepository.save(project);

        ObjectNode payload = objectMapper.createObjectNode();
        ArrayNode arr = payload.putArray("format_classes");
        classes.forEach(c -> arr.add(c.name()));
        Job job = jobService.enqueue(FormatConvertJobHandler.KIND, project.getId(), payload);
        int cost = estimate(classes.size());
        creditService.consume(ownerId, cost, job.getId(), "포스터 규격변환 " + classes.size() + "종");

        return new Requested(project.getId(), job.getId(), cost, classes.stream().map(Enum::name).toList());
    }

    /** jpg/png만, 20MB 이하, 짧은 변 300px 이상. 저장은 PNG로 통일(EXIF 등 메타데이터 제거 효과도 있다). */
    private byte[] readPoster(MultipartFile file) {
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
            return DesignElementStore.png(img);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_IMAGE", "이미지를 읽을 수 없습니다.");
        }
    }
}
