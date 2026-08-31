package com.actset.service;

import com.actset.common.ApiException;
import com.actset.domain.Project;
import com.actset.domain.UploadedFile;
import com.actset.external.moderation.ContentModerationAdapter;
import com.actset.external.moderation.ModerationResult;
import com.actset.repository.ProjectRepository;
import com.actset.repository.UploadedFileRepository;
import com.actset.storage.StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * ② 파일 업로드(1-8). 정식 매직바이트 화이트리스트·용량 검증은 1-21에서 강화한다 —
 * 여기서는 확장자/MIME 기본 검증 + ImageIO 재인코딩으로 EXIF(GPS 포함)를 제거한다.
 *
 * CLAUDE.md 규칙 1: 이 서비스는 cast_photo·performance_photo·logo를 외부 API로 보내지 않는다.
 * reference_image만 이후 VLM 스타일 분석(1-11)에 전송될 수 있다(docs/05·15) — 그 전송 경로도
 * 이 클래스 밖(생성 파이프라인 어댑터)에서 별도로 이뤄지며, 여기서는 저장만 한다.
 */
@Service
public class FileUploadService {

    private static final Set<String> ALLOWED_KINDS = Set.of(
            "performance_photo", "cast_photo", "logo", "reference_image");
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png");
    private static final long MAX_BYTES = 15L * 1024 * 1024;

    private final UploadedFileRepository uploadedFileRepository;
    private final StorageService storageService;
    private final ProjectRepository projectRepository;
    private final ContentModerationAdapter contentModerationAdapter;

    public FileUploadService(UploadedFileRepository uploadedFileRepository, StorageService storageService,
                              ProjectRepository projectRepository, ContentModerationAdapter contentModerationAdapter) {
        this.uploadedFileRepository = uploadedFileRepository;
        this.storageService = storageService;
        this.projectRepository = projectRepository;
        this.contentModerationAdapter = contentModerationAdapter;
    }

    public record UploadResult(UUID id, String kind, String url, int width, int height, long bytes) {
    }

    @Transactional
    public UploadResult upload(UUID projectId, String kind, MultipartFile file) {
        if (!ALLOWED_KINDS.contains(kind)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_KIND", "kind 값이 올바르지 않습니다.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                    "파일 크기는 15MB를 넘을 수 없습니다.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_TYPE",
                    "jpg·png 파일만 업로드할 수 있습니다.");
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (extension == null || !ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_TYPE",
                    "허용되지 않는 확장자입니다.");
        }

        byte[] rawBytes;
        BufferedImage image;
        try {
            rawBytes = file.getBytes();
            image = ImageIO.read(new ByteArrayInputStream(rawBytes));
        } catch (IOException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNREADABLE_IMAGE", "이미지를 읽을 수 없습니다.");
        }
        if (image == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNREADABLE_IMAGE",
                    "이미지 형식이 아니거나 손상되었습니다(위장 확장자 포함).");
        }

        ModerationResult moderation = contentModerationAdapter.checkImage(rawBytes);
        if (!moderation.allowed()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CONTENT_BLOCKED", moderation.reason());
        }

        String ext = contentType.equals("image/png") ? "png" : "jpg";
        byte[] reencoded = reencode(image, ext);

        UUID fileId = UUID.randomUUID();
        String path = "projects/" + projectId + "/uploads/" + fileId + "." + ext;
        storageService.store(reencoded, path, contentType);

        UploadedFile entity = new UploadedFile();
        entity.setId(fileId);
        entity.setProjectId(projectId);
        entity.setKind(kind);
        entity.setStoragePath(path);
        entity.setMimeType(contentType);
        entity.setFileSize((long) reencoded.length);
        uploadedFileRepository.save(entity);

        String url = storageService.signedUrl(path, Duration.ofHours(1));
        return new UploadResult(fileId, kind, url, image.getWidth(), image.getHeight(), reencoded.length);
    }

    private String extensionOf(String filename) {
        if (filename == null || !filename.contains(".")) return null;
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    /** ImageIO로 다시 그려 저장 — EXIF(GPS 포함) 등 원본 메타데이터가 결과 바이트에 남지 않는다. */
    private byte[] reencode(BufferedImage image, String ext) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if ("png".equals(ext)) {
                ImageIO.write(image, "png", out);
            } else {
                BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
                rgb.createGraphics().drawImage(image, 0, 0, java.awt.Color.WHITE, null);
                ImageIO.write(rgb, "jpg", out);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("이미지 재인코딩 실패", e);
        }
    }

    /** 소유자 확인 후 삭제 — project_id는 파일 자체에서 가져와 위조된 값을 신뢰하지 않는다. */
    @Transactional
    public void delete(UUID fileId, UUID ownerId) {
        UploadedFile file = uploadedFileRepository.findById(fileId).orElseThrow(ApiException::notFound);
        Project project = projectRepository.findByIdAndOwnerId(file.getProjectId(), ownerId)
                .orElseThrow(ApiException::notFound);
        storageService.delete(file.getStoragePath());
        uploadedFileRepository.delete(file);
    }

    public List<UploadedFile> listByProject(UUID projectId) {
        return uploadedFileRepository.findByProjectId(projectId);
    }
}
