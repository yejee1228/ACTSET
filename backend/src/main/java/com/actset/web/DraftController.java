package com.actset.web;

import com.actset.security.CurrentUser;
import com.actset.service.DraftService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** ③ 시안 선택 화면 지원(1-11·1-12), docs/11 "2. 시안·홍보물 생성". */
@RestController
@RequestMapping("/api/v1/projects/{id}/drafts")
public class DraftController {

    private final DraftService draftService;

    public DraftController(DraftService draftService) {
        this.draftService = draftService;
    }

    public record DraftRequest(String mode, Integer count, String reference_candidate_id) {
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> requestDrafts(@PathVariable UUID id, @RequestBody(required = false) DraftRequest req) {
        String mode = (req != null && req.mode() != null) ? req.mode() : "initial";
        int count = (req != null && req.count() != null) ? req.count() : 3;
        UUID jobId = draftService.requestDrafts(id, CurrentUser.id(), mode, count);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("job_id", jobId.toString()));
    }
}
