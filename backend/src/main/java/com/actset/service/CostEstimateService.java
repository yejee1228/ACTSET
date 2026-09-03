package com.actset.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 크레딧 소비량 계산을 한 곳에 모은다(6-3 "생성 전 예상 소비량"이 실제 차감 로직과
 * 어긋나지 않도록 DraftService·RecomposeService도 이 클래스를 쓴다). 단가는 아직
 * 확정되지 않은 임시값이다(docs/06, OVERNIGHT-LOG). mode="initial"이 아니면 전부
 * 재생성 취급으로 낮은 단가를 적용한다.
 *
 * 시안 생성은 "3장 세트" 단위로 과금하는 **정액**이다(count와 무관) — docs/06.
 * 규격 변환은 규격 1종당 과금하는 정액이다(variants_per_format과 무관).
 */
@Service
public class CostEstimateService {

    @Value("${actset.credit.cost-per-draft-set-initial:10}")
    private int costPerDraftSetInitial;

    @Value("${actset.credit.cost-per-draft-set-regenerate:5}")
    private int costPerDraftSetRegenerate;

    @Value("${actset.credit.cost-per-format-recompose:2}")
    private int costPerFormatRecompose;

    @Value("${actset.credit.cost-per-format-recompose-regenerate:1}")
    private int costPerFormatRecomposeRegenerate;

    public int draftCost(String mode) {
        return "initial".equals(mode) ? costPerDraftSetInitial : costPerDraftSetRegenerate;
    }

    public int recomposeCostPerFormat(String mode) {
        return "regenerate".equals(mode) ? costPerFormatRecomposeRegenerate : costPerFormatRecompose;
    }

    /** GET /credits/estimate가 쓰는 범용 진입점. kind: draft_generate | recompose. */
    public int estimate(String kind, List<String> formatCodes, String mode) {
        return switch (kind) {
            case "draft_generate" -> draftCost(mode);
            case "recompose" -> (formatCodes != null ? formatCodes.size() : 0) * recomposeCostPerFormat(mode);
            default -> 0;
        };
    }
}
