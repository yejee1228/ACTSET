package com.actset.web;

import com.actset.common.ApiException;
import com.actset.domain.GeneratedAsset;
import com.actset.repository.GeneratedAssetRepository;
import com.actset.security.CurrentUser;
import com.actset.service.PrintDraftService;
import com.actset.service.PrintPricingService;
import com.actset.service.ProjectService;
import com.actset.worker.JobService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** ⑧ 인쇄 페이지(5-1·5-2·5-3), docs/11 "6. 규격·인쇄". */
@RestController
@RequestMapping("/api/v1/projects/{id}")
public class PrintController {

    private final ProjectService projectService;
    private final GeneratedAssetRepository generatedAssetRepository;
    private final JobService jobService;
    private final PrintDraftService printDraftService;
    private final PrintPricingService pricingService;
    private final ObjectMapper objectMapper;

    public PrintController(ProjectService projectService, GeneratedAssetRepository generatedAssetRepository,
                            JobService jobService, PrintDraftService printDraftService,
                            PrintPricingService pricingService, ObjectMapper objectMapper) {
        this.projectService = projectService;
        this.generatedAssetRepository = generatedAssetRepository;
        this.jobService = jobService;
        this.printDraftService = printDraftService;
        this.pricingService = pricingService;
        this.objectMapper = objectMapper;
    }

    public record PrintRenderRequest(UUID asset_id, int width_mm, int height_mm, Integer dpi) {
    }

    @PostMapping("/print-renders")
    public ResponseEntity<Map<String, Object>> requestPrintRender(@PathVariable UUID id, @RequestBody PrintRenderRequest req) {
        projectService.getOwned(id, CurrentUser.id());
        GeneratedAsset asset = generatedAssetRepository.findById(req.asset_id())
                .filter(a -> a.getProjectId().equals(id))
                .orElseThrow(ApiException::notFound);

        int dpi = req.dpi() != null ? req.dpi() : pricingService.recommendedDpi(req.width_mm(), req.height_mm());
        double requiredPx = (req.width_mm() / 25.4) * dpi;

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("asset_id", req.asset_id().toString());
        payload.put("width_mm", req.width_mm());
        payload.put("height_mm", req.height_mm());
        payload.put("dpi", dpi);
        var job = jobService.enqueue("render_print", id, payload);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job_id", job.getId().toString());
        if (asset.getWidth() < requiredPx * 0.9) {
            body.put("warnings", java.util.List.of(Map.of(
                    "code", "LOW_RESOLUTION", "message", "원본 해상도가 낮아 인쇄 시 화질이 떨어질 수 있습니다.")));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    public record PrintDraftRequest(UUID generated_asset_id, JsonNode print_spec, JsonNode shipping_address) {
    }

    @PostMapping("/print-drafts")
    public ResponseEntity<Map<String, Object>> createDraft(@PathVariable UUID id, @RequestBody PrintDraftRequest req) {
        PrintDraftService.Result result = printDraftService.create(id, CurrentUser.id(), req.generated_asset_id(),
                req.print_spec(), req.shipping_address());
        return ResponseEntity.status(HttpStatus.CREATED).body(toBody(result));
    }

    private Map<String, Object> toBody(PrintDraftService.Result result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", result.id().toString());
        body.put("estimated_price", result.estimatedPrice());
        body.put("warnings", result.warnings());
        return body;
    }
}
