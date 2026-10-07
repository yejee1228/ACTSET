package com.actset.web;

import com.actset.conversion.ConversionRequestService;
import com.actset.format.FormatClass;
import com.actset.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 포스터 업로드형 규격변환(docs/11 "규격변환 — 포스터 업로드형"). 결과 조회는 기존
 * GET /jobs/{id} · GET /projects/{id}/assets?category=규격변환 을 그대로 쓴다.
 */
@RestController
public class ConversionController {

    private final ConversionRequestService requestService;

    public ConversionController(ConversionRequestService requestService) {
        this.requestService = requestService;
    }

    @GetMapping("/api/v1/format-classes")
    public Map<String, Object> formatClasses() {
        List<Map<String, Object>> items = Arrays.stream(FormatClass.values()).map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", c.name());
            m.put("label", c.label());
            m.put("width", c.representativePreset().width());
            m.put("height", c.representativePreset().height());
            m.put("example", c.representativePreset().label());
            return m;
        }).toList();
        return Map.of("items", items, "cost_per_format", requestService.estimate(1));
    }

    @PostMapping("/api/v1/conversions")
    public ResponseEntity<Map<String, Object>> request(@RequestParam("poster") MultipartFile poster,
                                                       @RequestParam("format_classes") List<String> formatClasses) {
        ConversionRequestService.Requested r = requestService.request(CurrentUser.id(), poster, formatClasses);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("project_id", r.projectId().toString());
        body.put("job_id", r.jobId().toString());
        body.put("credit_cost", r.cost());
        body.put("format_classes", r.formatClasses());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }
}
