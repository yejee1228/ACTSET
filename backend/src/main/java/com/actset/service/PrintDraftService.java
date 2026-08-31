package com.actset.service;

import com.actset.common.ApiException;
import com.actset.domain.GeneratedAsset;
import com.actset.domain.Project;
import com.actset.domain.PrintOrderDraft;
import com.actset.repository.GeneratedAssetRepository;
import com.actset.repository.PrintOrderDraftRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * ⑧ 인쇄 페이지(5-1·5-2). MVP는 초안 저장까지만 하고 결제·주문 접수는 없다(docs/02).
 * 경고는 차단이 아니라 안내만 한다 — 사용자가 그래도 계속 진행할 수 있다.
 */
@Service
public class PrintDraftService {

    private final ProjectService projectService;
    private final GeneratedAssetRepository generatedAssetRepository;
    private final PrintOrderDraftRepository printOrderDraftRepository;
    private final PrintPricingService pricingService;
    private final ObjectMapper objectMapper;

    public PrintDraftService(ProjectService projectService, GeneratedAssetRepository generatedAssetRepository,
                              PrintOrderDraftRepository printOrderDraftRepository, PrintPricingService pricingService,
                              ObjectMapper objectMapper) {
        this.projectService = projectService;
        this.generatedAssetRepository = generatedAssetRepository;
        this.printOrderDraftRepository = printOrderDraftRepository;
        this.pricingService = pricingService;
        this.objectMapper = objectMapper;
    }

    public record Result(UUID id, int estimatedPrice, ArrayNode warnings) {
    }

    @Transactional
    public Result create(UUID projectId, UUID ownerId, UUID assetId, JsonNode printSpec, JsonNode shippingAddress) {
        PrintOrderDraft draft = new PrintOrderDraft();
        draft.setProjectId(projectId);
        draft.setGeneratedAssetId(assetId);
        return upsert(draft, projectId, ownerId, assetId, printSpec, shippingAddress);
    }

    @Transactional
    public Result update(UUID draftId, UUID ownerId, JsonNode printSpec, JsonNode shippingAddress) {
        PrintOrderDraft draft = printOrderDraftRepository.findById(draftId).orElseThrow(ApiException::notFound);
        return upsert(draft, draft.getProjectId(), ownerId, draft.getGeneratedAssetId(), printSpec, shippingAddress);
    }

    private Result upsert(PrintOrderDraft draft, UUID projectId, UUID ownerId, UUID assetId,
                           JsonNode printSpec, JsonNode shippingAddress) {
        Project project = projectService.getOwned(projectId, ownerId);
        GeneratedAsset asset = generatedAssetRepository.findById(assetId)
                .filter(a -> a.getProjectId().equals(projectId))
                .orElseThrow(ApiException::notFound);

        int widthMm = printSpec.path("width_mm").asInt();
        int heightMm = printSpec.path("height_mm").asInt();
        int quantity = Math.max(printSpec.path("quantity").asInt(1), 1);
        String paper = printSpec.path("paper").asText("광택");

        int price = pricingService.estimate(widthMm, heightMm, quantity, paper);

        ArrayNode warnings = objectMapper.createArrayNode();
        int dpi = pricingService.recommendedDpi(widthMm, heightMm);
        double requiredPx = (widthMm / 25.4) * dpi;
        if (asset.getWidth() < requiredPx * 0.9) {
            warnings.add(warning("LOW_RESOLUTION", "원본 해상도가 낮아 인쇄 시 화질이 떨어질 수 있습니다."));
        }
        if (project.isDateUndetermined() || project.isVenueUndetermined()) {
            warnings.add(warning("SCHEDULE_UNDETERMINED", "일정이 아직 미정입니다."));
        }

        draft.setPrintSpec(printSpec);
        draft.setShippingAddress(shippingAddress);
        draft.setEstimatedPrice(price);
        draft.setStatus("draft_only");
        printOrderDraftRepository.save(draft);

        return new Result(draft.getId(), price, warnings);
    }

    private ObjectNode warning(String code, String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("code", code);
        node.put("message", message);
        return node;
    }
}
