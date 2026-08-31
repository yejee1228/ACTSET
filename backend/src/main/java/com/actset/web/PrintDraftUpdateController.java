package com.actset.web;

import com.actset.security.CurrentUser;
import com.actset.service.PrintDraftService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** PATCH /print-drafts/{id} — docs/11에서 프로젝트 경로 밖의 최상위 엔드포인트로 정의됨. */
@RestController
public class PrintDraftUpdateController {

    private final PrintDraftService printDraftService;

    public PrintDraftUpdateController(PrintDraftService printDraftService) {
        this.printDraftService = printDraftService;
    }

    public record UpdateRequest(JsonNode print_spec, JsonNode shipping_address) {
    }

    @PatchMapping("/api/v1/print-drafts/{draftId}")
    public Map<String, Object> update(@PathVariable UUID draftId, @RequestBody UpdateRequest req) {
        PrintDraftService.Result result = printDraftService.update(draftId, CurrentUser.id(), req.print_spec(), req.shipping_address());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", result.id().toString());
        body.put("estimated_price", result.estimatedPrice());
        body.put("warnings", result.warnings());
        return body;
    }
}
