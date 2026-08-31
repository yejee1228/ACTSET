package com.actset.web;

import com.actset.security.CurrentUser;
import com.actset.service.FileUploadService;
import com.actset.service.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** ② 파일 업로드(1-8), docs/11 "1-2. 파일 업로드". */
@RestController
public class ProjectFileController {

    private final ProjectService projectService;
    private final FileUploadService fileUploadService;

    public ProjectFileController(ProjectService projectService, FileUploadService fileUploadService) {
        this.projectService = projectService;
        this.fileUploadService = fileUploadService;
    }

    @PostMapping("/api/v1/projects/{id}/files")
    public ResponseEntity<Map<String, Object>> upload(@PathVariable UUID id,
                                                         @RequestParam("file") MultipartFile file,
                                                         @RequestParam("kind") String kind) {
        projectService.getOwned(id, CurrentUser.id()); // 소유자 확인 — 실패 시 404
        FileUploadService.UploadResult result = fileUploadService.upload(id, kind, file);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", result.id().toString());
        body.put("kind", result.kind());
        body.put("url", result.url());
        body.put("width", result.width());
        body.put("height", result.height());
        body.put("bytes", result.bytes());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @DeleteMapping("/api/v1/files/{fileId}")
    public ResponseEntity<Void> delete(@PathVariable UUID fileId) {
        fileUploadService.delete(fileId, CurrentUser.id());
        return ResponseEntity.noContent().build();
    }
}
