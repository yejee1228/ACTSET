package com.actset.web;

import com.actset.security.CurrentUser;
import com.actset.service.RecomposeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ⑤→⑥ 규격 일괄변환(3-1·3-4), docs/11 "2. 시안·홍보물 생성". */
@RestController
@RequestMapping("/api/v1/projects/{id}/recompose")
public class RecomposeController {

    private final RecomposeService recomposeService;

    public RecomposeController(RecomposeService recomposeService) {
        this.recomposeService = recomposeService;
    }

    public record RecomposeRequest(List<String> format_codes, Integer variants_per_format, String mode) {
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> requestRecompose(@PathVariable UUID id, @RequestBody RecomposeRequest req) {
        int variants = req.variants_per_format() != null ? req.variants_per_format() : 3;
        String mode = req.mode() != null ? req.mode() : "initial";
        RecomposeService.RecomposeResult result = recomposeService.requestRecompose(id, CurrentUser.id(), req.format_codes(), variants, mode);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job_id", result.parentJobId().toString());
        body.put("children", result.children().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("job_id", c.jobId().toString());
            m.put("format_code", c.formatCode());
            return m;
        }).toList());
        body.put("skipped", result.skipped());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }
}
