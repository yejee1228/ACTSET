package com.actset.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 크레딧 소비량 계산을 한 곳에 모은다(6-3 "생성 전 예상 소비량"이 실제 차감 로직과
 * 어긋나지 않도록 DraftService·RecomposeService도 이 클래스를 쓴다). 단가는 아직
 * 확정되지 않은 임시값이다(docs/06, OVERNIGHT-LOG).
 */
@Service
public class CostEstimateService {

    @Value("${actset.credit.cost-per-draft-image:10}")
    private int costPerDraftImage;

    @Value("${actset.credit.cost-per-format-recompose:5}")
    private int costPerFormatRecompose;

    public int draftCost(int count) {
        return costPerDraftImage * count;
    }

    public int recomposeCostPerFormat() {
        return costPerFormatRecompose;
    }

    /** GET /credits/estimate가 쓰는 범용 진입점. kind: draft_generate | recompose. */
    public int estimate(String kind, List<String> formatCodes, int variants) {
        return switch (kind) {
            case "draft_generate" -> draftCost(variants);
            case "recompose" -> (formatCodes != null ? formatCodes.size() : 0) * costPerFormatRecompose;
            default -> 0;
        };
    }
}
