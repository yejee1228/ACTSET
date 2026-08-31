package com.actset.web;

import com.actset.domain.Job;
import com.actset.security.CurrentUser;
import com.actset.service.ProjectService;
import com.actset.worker.JobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ⑦ 선택·일괄 다운로드(4-2), docs/11 "4. 결과물" 마지막 항목. */
@RestController
@RequestMapping("/api/v1/projects/{id}/assets/download")
public class AssetDownloadController {

    private final ProjectService projectService;
    private final JobService jobService;
    private final ObjectMapper objectMapper;

    public AssetDownloadController(ProjectService projectService, JobService jobService, ObjectMapper objectMapper) {
        this.projectService = projectService;
        this.jobService = jobService;
        this.objectMapper = objectMapper;
    }

    public record DownloadRequest(List<String> asset_ids) {
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> requestDownload(@PathVariable UUID id, @RequestBody DownloadRequest req) {
        projectService.getOwned(id, CurrentUser.id()); // 소유자 확인
        ObjectNode payload = objectMapper.createObjectNode();
        ArrayNode ids = payload.putArray("asset_ids");
        req.asset_ids().forEach(ids::add);
        Job job = jobService.enqueue("zip_download", id, payload);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("job_id", job.getId().toString()));
    }
}
